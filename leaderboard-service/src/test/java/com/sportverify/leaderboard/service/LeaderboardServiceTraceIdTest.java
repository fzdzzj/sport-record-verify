package com.sportverify.leaderboard.service;

import com.sportverify.api.user.UserApi;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardDailySummaryMapper;
import com.sportverify.leaderboard.mapper.SportRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 榜单快照结算定时入口 traceId 链路单测（TASK-198 批1，纯 JVM）。
 *
 * <p>打桩范式同 {@link LeaderboardServiceTest}（Mapper/Redis/Redisson/UserApi 全 mock）。
 * 判定口径：结算轮内 MDC traceId 非空（权威源汇总查询调用点实测）、出口 finally 清理、
 * 锁未获取提前返回与汇总查询异常传播两条路径同样清理。</p>
 */
class LeaderboardServiceTraceIdTest {

    private SportRecordMapper sportRecordMapper;
    private LeaderboardContributionMapper contributionMapper;
    private LeaderboardDailySummaryMapper dailySummaryMapper;
    private StringRedisTemplate redis;
    private RedissonClient redissonClient;
    private RLock lock;
    private LeaderboardService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        sportRecordMapper = mock(SportRecordMapper.class);
        contributionMapper = mock(LeaderboardContributionMapper.class);
        dailySummaryMapper = mock(LeaderboardDailySummaryMapper.class);
        redis = mock(StringRedisTemplate.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redis.opsForZSet()).thenReturn(mock(ZSetOperations.class));
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        service = new LeaderboardService(sportRecordMapper, contributionMapper,
                dailySummaryMapper, redis, redissonClient, mock(UserApi.class));
    }

    @AfterEach
    void tearDown() {
        TraceIds.clear();
    }

    /** 结算轮内 MDC traceId 非空（权威源汇总查询调用点实测），出口 finally 清理。 */
    @Test
    void settle_mdcNonEmptyDuringSummaryRead_andClearedAfter() {
        AtomicReference<String> seenDuringRead = new AtomicReference<>();
        when(contributionMapper.selectActiveSummaries(any()))
                .thenAnswer(inv -> {
                    seenDuringRead.set(TraceIds.current());
                    return List.of();
                });

        service.settleAndReconcile();

        assertNotNull(seenDuringRead.get(), "结算轮内汇总查询点 MDC traceId 不得为空");
        assertTrue(!seenDuringRead.get().isEmpty());
        assertNull(TraceIds.current(), "出口 finally 必须清理 MDC");
    }

    /** 结算锁未获取提前返回：入口 finally 仍清理 MDC。 */
    @Test
    void settle_lockNotAcquired_mdcStillCleared() throws InterruptedException {
        doReturn(false).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));

        service.settleAndReconcile();

        assertNull(TraceIds.current(), "提前返回路径的入口 finally 必须清理 MDC");
    }

    /** 权威源汇总查询抛错（异常传播路径）：出口 finally 仍清理 MDC。 */
    @Test
    void settle_mapperThrows_mdcStillCleared() {
        when(contributionMapper.selectActiveSummaries(any()))
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.settleAndReconcile());

        assertNull(TraceIds.current(), "异常传播时出口 finally 必须清理 MDC");
    }
}
