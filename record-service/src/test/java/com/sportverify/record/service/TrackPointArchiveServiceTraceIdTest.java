package com.sportverify.record.service;

import com.sportverify.common.trace.TraceIds;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointArchiveMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 轨迹归档定时入口 traceId 链路单测（TASK-198 批1，纯 JVM）。
 *
 * <p>打桩范式同 {@link TrackPointArchiveServiceTest}（Mapper/Redisson 全 mock，
 * 真实 SimpleMeterRegistry——禁 mock MeterRegistry）。判定口径：归档轮内 MDC traceId
 * 非空（候选扫描调用点实测）、出口 finally 清理、锁服务异常提前返回与扫描异常传播
 * 两条路径同样清理。</p>
 */
class TrackPointArchiveServiceTraceIdTest {

    private SportRecordMapper sportRecordMapper;
    private TrackPointMapper trackPointMapper;
    private TrackPointArchiveMapper trackPointArchiveMapper;
    private RedissonClient redissonClient;
    private RLock lock;
    private TrackPointArchiveService service;

    @BeforeEach
    void setUp() throws InterruptedException {
        sportRecordMapper = mock(SportRecordMapper.class);
        trackPointMapper = mock(TrackPointMapper.class);
        trackPointArchiveMapper = mock(TrackPointArchiveMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock(TrackPointArchiveService.ARCHIVE_LOCK_KEY))
                .thenReturn(lock);
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 规避检查型异常
        doReturn(true).when(lock).tryLock();
        service = new TrackPointArchiveService(sportRecordMapper, trackPointMapper,
                trackPointArchiveMapper, redissonClient, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        TraceIds.clear();
    }

    /** 归档轮内 MDC traceId 非空（候选扫描调用点实测），出口 finally 清理。 */
    @Test
    void archiveSweep_mdcNonEmptyDuringScan_andClearedAfter() {
        AtomicReference<String> seenDuringScan = new AtomicReference<>();
        when(sportRecordMapper.selectList(any())).thenAnswer(inv -> {
            seenDuringScan.set(TraceIds.current());
            return List.of();
        });

        service.archiveSweep();

        assertNotNull(seenDuringScan.get(), "归档轮内扫描点 MDC traceId 不得为空");
        assertTrue(!seenDuringScan.get().isEmpty());
        assertNull(TraceIds.current(), "出口 finally 必须清理 MDC");
    }

    /** 锁服务异常提前返回（可用性优先语义不变）：入口 finally 仍清理 MDC。 */
    @Test
    void archiveSweep_lockServiceError_mdcStillCleared() {
        when(redissonClient.getLock(TrackPointArchiveService.ARCHIVE_LOCK_KEY))
                .thenThrow(new RuntimeException("redisson down"));

        service.archiveSweep();

        assertNull(TraceIds.current(), "提前返回路径的入口 finally 必须清理 MDC");
    }

    /** 候选扫描抛错（异常传播路径）：出口 finally 仍清理 MDC。 */
    @Test
    void archiveSweep_scanThrows_mdcStillCleared() {
        when(sportRecordMapper.selectList(any()))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.archiveSweep());

        assertNull(TraceIds.current(), "异常传播时出口 finally 必须清理 MDC");
    }
}
