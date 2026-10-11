package com.sportverify.record.service;

import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.verify.VerifyApi;
import com.sportverify.common.result.Result;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mq.RecordEventProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 滞留补偿定时入口 traceId 链路单测（TASK-198 批1，纯 JVM）。
 *
 * <p>打桩范式同 {@link VerifyDegradeServiceTest}（Mapper/Producer/VerifyApi 全 mock）。
 * 判定口径：补偿轮内 MDC traceId 非空（在协作者调用点实测）、出口 finally 清理、
 * 异常传播与开关关闭提前返回两条路径同样清理。</p>
 */
class VerifyDegradeServiceTraceIdTest {

    private SportRecordMapper sportRecordMapper;
    private RecordEventProducer recordEventProducer;
    private VerifyApi verifyApi;
    private VerifyDegradeService service;

    @BeforeEach
    void setUp() {
        sportRecordMapper = mock(SportRecordMapper.class);
        recordEventProducer = mock(RecordEventProducer.class);
        verifyApi = mock(VerifyApi.class);
        service = new VerifyDegradeService(sportRecordMapper, recordEventProducer, verifyApi);
        ReflectionTestUtils.setField(service, "stuckSeconds", 120L);
        ReflectionTestUtils.setField(service, "compensateEnabled", true);
    }

    @AfterEach
    void tearDown() {
        TraceIds.clear();
    }

    private SportRecord rec(Long id, Integer status) {
        SportRecord r = new SportRecord();
        r.setId(id);
        r.setUserId(100L);
        r.setStatus(status);
        r.setVersion(0);
        return r;
    }

    /** 补偿轮内 MDC traceId 非空（探活调用点实测），出口 finally 清理。 */
    @Test
    void compensate_mdcNonEmptyDuringProbe_andClearedAfter() {
        when(sportRecordMapper.selectList(any()))
                .thenReturn(List.of(rec(1L, RecordStatus.VERIFYING.getCode())))
                .thenReturn(List.of()); // 第二轮 MANUAL_REVIEW 查询为空
        AtomicReference<String> seenDuringProbe = new AtomicReference<>();
        when(verifyApi.health()).thenAnswer(inv -> {
            seenDuringProbe.set(TraceIds.current());
            return Result.success();
        });
        when(recordEventProducer.publishSubmitted(1L, 100L)).thenReturn(true);

        service.compensateStuckVerifying();

        assertNotNull(seenDuringProbe.get(), "补偿轮内探活点 MDC traceId 不得为空");
        assertTrue(!seenDuringProbe.get().isEmpty());
        assertNull(TraceIds.current(), "出口 finally 必须清理 MDC");
    }

    /** 扫描查询抛错（异常传播路径）：出口 finally 仍清理 MDC。 */
    @Test
    void compensate_mapperThrows_mdcStillCleared() {
        when(sportRecordMapper.selectList(any()))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.compensateStuckVerifying());

        assertNull(TraceIds.current(), "异常传播时出口 finally 必须清理 MDC");
    }

    /** 开关关闭提前返回：MDC 未写入亦无残留（入口 finally 清理）。 */
    @Test
    void compensate_disabled_mdcClearedAfterEarlyReturn() {
        ReflectionTestUtils.setField(service, "compensateEnabled", false);

        service.compensateStuckVerifying();

        Mockito.verifyNoInteractions(verifyApi);
        assertNull(TraceIds.current(), "提前返回路径的入口 finally 必须清理 MDC");
    }

    /** 兜底断言：补偿内产生的 MDC 值是新生成 UUID（去横线，32 位）。 */
    @Test
    void compensate_generatesNewDashlessTraceId() {
        when(sportRecordMapper.selectList(any()))
                .thenReturn(List.of(rec(1L, RecordStatus.VERIFYING.getCode())))
                .thenReturn(List.of());
        AtomicReference<String> seenDuringProbe = new AtomicReference<>();
        when(verifyApi.health()).thenAnswer(inv -> {
            seenDuringProbe.set(TraceIds.current());
            return Result.success();
        });
        when(recordEventProducer.publishSubmitted(anyLong(), anyLong())).thenReturn(true);

        service.compensateStuckVerifying();

        assertNotNull(seenDuringProbe.get());
        assertTrue(seenDuringProbe.get().length() == 32
                        && seenDuringProbe.get().indexOf('-') < 0,
                "定时入口应生成新 UUID 去横线值，实测=" + seenDuringProbe.get());
    }
}
