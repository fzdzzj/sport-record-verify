package com.sportverify.record.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.record.entity.RecordLike;
import com.sportverify.record.mapper.RecordLikeMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mq.LikeEventProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
 * 点赞异步落库 / 对账两处定时入口 traceId 链路单测（TASK-198 批1，纯 JVM）。
 *
 * <p>打桩范式同 {@link RecordLikeServiceTest}（Redis/Mapper/Redisson/事务管理器全 mock，
 * 真实 SimpleMeterRegistry）。判定口径：两入口轮内 MDC traceId 非空（协作者调用点实测）、
 * 出口 finally 清理、异常传播路径同样清理。</p>
 */
class RecordLikeServiceTraceIdTest {

    private static final String OP_JSON = "{\"recordId\":1,\"userId\":100,\"action\":\"LIKE\"}";

    private SportRecordMapper sportRecordMapper;
    private RecordLikeMapper recordLikeMapper;
    private StringRedisTemplate redis;
    private ListOperations<String, String> listOps;
    private RedissonClient redissonClient;
    private RLock lock;
    private RecordLikeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        sportRecordMapper = mock(SportRecordMapper.class);
        recordLikeMapper = mock(RecordLikeMapper.class);
        redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redis.opsForList()).thenReturn(listOps);
        // RLock.tryLock 声明 throws InterruptedException，用 doReturn 规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(inv -> new SimpleTransactionStatus());
        service = new RecordLikeService(sportRecordMapper, recordLikeMapper, redis, redissonClient,
                new ObjectMapper(), txManager, new SimpleMeterRegistry(),
                mock(LikeEventProducer.class));
    }

    @AfterEach
    void tearDown() {
        TraceIds.clear();
    }

    /** flush 轮内 MDC traceId 非空（批量落库调用点实测），出口 finally 清理。 */
    @Test
    void flush_mdcNonEmptyDuringBatchWrite_andClearedAfter() {
        when(listOps.range(RecordLikeService.PENDING_QUEUE_KEY, 0, 199))
                .thenReturn(List.of(OP_JSON));
        AtomicReference<String> seenDuringWrite = new AtomicReference<>();
        when(recordLikeMapper.batchInsertIgnore(any())).thenAnswer(inv -> {
            seenDuringWrite.set(TraceIds.current());
            return 1;
        });

        service.flushPendingLikes();

        assertNotNull(seenDuringWrite.get(), "flush 轮内落库点 MDC traceId 不得为空");
        assertTrue(!seenDuringWrite.get().isEmpty());
        assertNull(TraceIds.current(), "出口 finally 必须清理 MDC");
    }

    /** 队列读取抛错（异常传播路径）：出口 finally 仍清理 MDC。 */
    @Test
    void flush_rangeThrows_mdcStillCleared() {
        when(listOps.range(RecordLikeService.PENDING_QUEUE_KEY, 0, 199))
                .thenThrow(new RuntimeException("redis down"));

        assertThrows(RuntimeException.class, () -> service.flushPendingLikes());

        assertNull(TraceIds.current(), "异常传播时出口 finally 必须清理 MDC");
    }

    /** 对账轮内 MDC traceId 非空（权威源查询调用点实测），出口 finally 清理。 */
    @Test
    void reconcile_mdcNonEmptyDuringSummaryRead_andClearedAfter() {
        RecordLike pair = new RecordLike();
        pair.setRecordId(1L);
        pair.setUserId(100L);
        AtomicReference<String> seenDuringRead = new AtomicReference<>();
        when(recordLikeMapper.selectRecordLikePairs()).thenAnswer(inv -> {
            seenDuringRead.set(TraceIds.current());
            return List.of(pair);
        });

        service.reconcileLikeCounts();

        assertNotNull(seenDuringRead.get(), "对账轮内查询点 MDC traceId 不得为空");
        assertTrue(!seenDuringRead.get().isEmpty());
        assertNull(TraceIds.current(), "出口 finally 必须清理 MDC");
    }

    /** 对账权威源查询抛错（异常传播路径）：出口 finally 仍清理 MDC。 */
    @Test
    void reconcile_mapperThrows_mdcStillCleared() {
        when(recordLikeMapper.selectRecordLikePairs())
                .thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.reconcileLikeCounts());

        assertNull(TraceIds.current(), "异常传播时出口 finally 必须清理 MDC");
    }
}
