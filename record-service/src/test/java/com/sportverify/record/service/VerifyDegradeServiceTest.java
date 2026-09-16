package com.sportverify.record.service;

import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.verify.VerifyApi;
import com.sportverify.common.result.Result;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mq.RecordEventProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验降级服务单元测试（熔断转人工 / 滞留补偿 / 补偿开关）。
 *
 * <p>纯 Mockito：Mapper / RecordEventProducer / VerifyApi 全部 mock；
 * 覆盖 degradeToManualReview 的「成功 / 记录不存在 / 已离开 VERIFYING / 迁移冲突」分支，
 * 以及 compensateStuckVerifying 的「开关关闭 / 探活成功后重发 / 探活失败转人工 /
 * 发布失败转人工 / 转人工记录自愈重放」分支。</p>
 */
class VerifyDegradeServiceTest {

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

    private SportRecord rec(Long id, Integer status) {
        SportRecord r = new SportRecord();
        r.setId(id);
        r.setUserId(100L);
        r.setStatus(status);
        r.setVersion(0);
        return r;
    }

    // ==================== 熔断降级转人工 ====================

    /** VERIFYING 记录 → 乐观锁迁移 MANUAL_REVIEW */
    @Test
    void degradeToManualReview_verifying_transitions() {
        when(sportRecordMapper.selectById(1L)).thenReturn(rec(1L, RecordStatus.VERIFYING.getCode()));
        when(sportRecordMapper.updateStatus(1L, RecordStatus.VERIFYING.getCode(),
                RecordStatus.MANUAL_REVIEW.getCode(), 0)).thenReturn(1);

        service.degradeToManualReview(1L, new RuntimeException("verify down"));

        verify(sportRecordMapper).updateStatus(1L, RecordStatus.VERIFYING.getCode(),
                RecordStatus.MANUAL_REVIEW.getCode(), 0);
    }

    /** 记录不存在 → 仅告警，不迁移 */
    @Test
    void degradeToManualReview_recordMissing_noop() {
        when(verifyApi.health()).thenReturn(Result.success());
        when(sportRecordMapper.selectById(1L)).thenReturn(null);

        service.degradeToManualReview(1L, null);

        verify(sportRecordMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    /** 已离开 VERIFYING（已被回调推进）→ 跳过转人工（乐观语义幂等） */
    @Test
    void degradeToManualReview_notVerifying_skip() {
        when(sportRecordMapper.selectById(1L)).thenReturn(rec(1L, RecordStatus.PASSED.getCode()));

        service.degradeToManualReview(1L, null);

        verify(sportRecordMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    /** 迁移冲突（影响 0 行，并发已被推进）→ 幂等跳过，不抛异常 */
    @Test
    void degradeToManualReview_conflict_zeroRows_skip() {
        when(sportRecordMapper.selectById(1L)).thenReturn(rec(1L, RecordStatus.VERIFYING.getCode()));
        when(sportRecordMapper.updateStatus(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(0);

        service.degradeToManualReview(1L, null);
        // 无异常即通过
    }

    // ==================== 滞留补偿 ====================

    /** 补偿开关关闭 → 直接返回，不查库 */
    @Test
    void compensate_disabled_returnsImmediately() {
        ReflectionTestUtils.setField(service, "compensateEnabled", false);

        service.compensateStuckVerifying();

        verify(sportRecordMapper, never()).selectList(any());
    }

    /** 滞留 VERIFYING + verify 探活成功 → 重发 SUBMITTED 事件（补判），不转人工 */
    @Test
    void compensate_stuckVerifyAlive_republishes() {
        when(sportRecordMapper.selectList(any()))
                .thenReturn(List.of(rec(1L, RecordStatus.VERIFYING.getCode())))
                .thenReturn(List.of()); // 第二轮 MANUAL_REVIEW 查询为空
        when(verifyApi.health()).thenReturn(Result.success());
        when(recordEventProducer.publishSubmitted(1L, 100L)).thenReturn(true);
        when(sportRecordMapper.updateStatus(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(1);

        service.compensateStuckVerifying();

        verify(recordEventProducer).publishSubmitted(1L, 100L);
        // 探活成功的补判路径：只重发事件，不转人工、不迁 MANUAL_REVIEW
        verify(sportRecordMapper, never()).updateStatus(anyLong(), anyInt(), anyInt(), anyInt());
    }

    /** 滞留 VERIFYING + 探活异常（verify 熔断/不可用）→ 立即转人工 */
    @Test
    void compensate_stuckVerifyDown_degradesToManual() {
        when(sportRecordMapper.selectList(any()))
                .thenReturn(List.of(rec(1L, RecordStatus.VERIFYING.getCode())))
                .thenReturn(List.of());
        when(verifyApi.health()).thenThrow(new RuntimeException("verify 不可用 4001"));
        when(sportRecordMapper.selectById(1L)).thenReturn(rec(1L, RecordStatus.VERIFYING.getCode()));
        when(sportRecordMapper.updateStatus(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(1);

        service.compensateStuckVerifying();

        verify(sportRecordMapper).updateStatus(1L, RecordStatus.VERIFYING.getCode(),
                RecordStatus.MANUAL_REVIEW.getCode(), 0);
    }

    /** 滞留 VERIFYING + 探活成功但事件发布失败 → 转人工 */
    @Test
    void compensate_stuckPublishFail_degradesToManual() {
        when(sportRecordMapper.selectList(any()))
                .thenReturn(List.of(rec(1L, RecordStatus.VERIFYING.getCode())))
                .thenReturn(List.of());
        when(verifyApi.health()).thenReturn(Result.success());
        when(recordEventProducer.publishSubmitted(1L, 100L)).thenReturn(false);
        when(sportRecordMapper.selectById(1L)).thenReturn(rec(1L, RecordStatus.VERIFYING.getCode()));
        when(sportRecordMapper.updateStatus(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(1);

        service.compensateStuckVerifying();

        verify(sportRecordMapper).updateStatus(1L, RecordStatus.VERIFYING.getCode(),
                RecordStatus.MANUAL_REVIEW.getCode(), 0);
    }

    /** MANUAL_REVIEW 记录 + verify 恢复 → 重放事件等待终判对账收敛 */
    @Test
    void compensate_manualRecordsVerifyAlive_republishesForReconcile() {
        when(sportRecordMapper.selectList(any()))
                .thenReturn(List.of())  // 无滞留 VERIFYING
                .thenReturn(List.of(rec(2L, RecordStatus.MANUAL_REVIEW.getCode())));
        when(verifyApi.health()).thenReturn(Result.success());
        when(recordEventProducer.publishSubmitted(2L, 100L)).thenReturn(true);

        service.compensateStuckVerifying();

        verify(recordEventProducer).publishSubmitted(2L, 100L);
    }
}