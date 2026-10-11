package com.sportverify.verify.mq;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * outbox relay 定时入口 traceId 链路单测（TASK-198 批1，纯 JVM）。
 *
 * <p>打桩范式同 {@link VerifyOutboxRelayTest}（Mapper/Redisson mock、生产者真实对象 +
 * RocketMQTemplate mock）。判定口径：① 入口轮次日志 MDC traceId 非空且 finally 清理；
 * ② 行级以 outbox 行内 traceId 续链；③ 行内 traceId 为空时日志仍非空（沿用轮次值）；
 * ④ 串行多行互不串号（行值不残留到下一行）；⑤ 批内 worker 线程同样续链；
 * ⑥ 异常传播时 MDC 仍被 finally 清理。</p>
 */
class VerifyOutboxRelayTraceIdTest {

    private VerifyEventOutboxMapper outboxMapper;
    private RocketMQTemplate rocketMQTemplate;
    private RedissonClient redissonClient;
    private RLock lock;
    private VerifyOutboxRelay relay;
    private Logger relayLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        outboxMapper = mock(VerifyEventOutboxMapper.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock("verify:outbox:relay")).thenReturn(lock);
        VerifyEventProducer producer = new VerifyEventProducer(rocketMQTemplate,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        relay = new VerifyOutboxRelay(outboxMapper, producer, redissonClient);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetry", 16);
        relayLogger = (Logger) LoggerFactory.getLogger(VerifyOutboxRelay.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        relayLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        relayLogger.detachAppender(logAppender);
        logAppender.stop();
        TraceIds.clear();
    }

    private void stubLockAcquired() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
    }

    private VerifyEventOutbox row(long id, String traceId) {
        VerifyEventOutbox r = new VerifyEventOutbox();
        r.setId(id);
        r.setEventId("evt-" + id);
        r.setTopic("record-verify-events");
        r.setTag("VERIFIED");
        r.setPayload("{\"eventId\":\"evt-" + id + "\"}");
        r.setStatus("PENDING");
        r.setRetryCount(0);
        r.setTraceId(traceId);
        return r;
    }

    /** 本轮所有日志事件的 MDC traceId（逐事件取，允许无日志时为空列表）。 */
    private List<String> loggedTraceIds() {
        return logAppender.list.stream()
                .map(e -> e.getMDCPropertyMap().get(TraceIds.MDC_KEY))
                .collect(Collectors.toList());
    }

    /** 定时入口轮次：批次内两种行（有/无行内 traceId）的全部日志 MDC 非空，出口 finally 清理。 */
    @Test
    void relay_roundLogsCarryNonEmptyTraceId_andClearedAfter() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, "trace-row-1"), row(2L, null)));

        relay.relay();

        List<String> ids = loggedTraceIds();
        assertTrue(!ids.isEmpty(), "本轮应有日志产生");
        for (String id : ids) {
            assertNotNull(id, "定时任务日志 MDC traceId 不得为空");
            assertTrue(!id.isEmpty(), "定时任务日志 MDC traceId 不得为空白");
        }
        assertNull(TraceIds.current(), "出口 finally 必须清理 MDC，防线程池复用串号");
        verify(outboxMapper).markSent(1L);
        verify(outboxMapper).markSent(2L);
    }

    /** 行级续链：发送段（syncSend 调用点）MDC traceId 等于 outbox 行内 traceId。 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_rowTraceId_continuesDuringSend() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, "trace-row-abc")));
        AtomicReference<String> seenDuringSend = new AtomicReference<>();
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class))).thenAnswer(inv -> {
            seenDuringSend.set(TraceIds.current());
            return null;
        });

        relay.relay();

        assertEquals("trace-row-abc", seenDuringSend.get(),
                "发送段应以行内 traceId 续链");
        assertNull(TraceIds.current());
    }

    /** 行内 traceId 为空：发送段 MDC 仍非空（沿用轮次入口值，不产生空链日志）。 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_rowWithoutTraceId_mdcStillNonEmpty() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, null)));
        AtomicReference<String> seenDuringSend = new AtomicReference<>();
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class))).thenAnswer(inv -> {
            seenDuringSend.set(TraceIds.current());
            return null;
        });

        relay.relay();

        assertNotNull(seenDuringSend.get(), "行内 traceId 为空时沿用轮次值，不得为 null");
        assertTrue(!seenDuringSend.get().isEmpty());
        assertNull(TraceIds.current());
    }

    /** 串行多行隔离：第二行的发送段看到的是第二行自己的 traceId（行值不残留到下一行）。 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_serialRows_eachSendSeesItsOwnRowTraceId() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, "trace-row-1"), row(2L, "trace-row-2")));
        List<String> seen = new java.util.ArrayList<>();
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class))).thenAnswer(inv -> {
            seen.add(TraceIds.current());
            return null;
        });

        relay.relay();

        assertEquals(List.of("trace-row-1", "trace-row-2"), seen,
                "逐行续链且行间互不串号");
        assertNull(TraceIds.current());
    }

    /** 批内 worker 路径（并发=2）：每个 worker 的发送段同样以行内 traceId 续链，出口清理。 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_workerPath_rowTraceIdContinues_andClearedAfter() throws Exception {
        ReflectionTestUtils.setField(relay, "relaySendConcurrency", 2);
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, "trace-worker-1"), row(2L, "trace-worker-2")));
        // 两个 worker 线程并发写入：线程安全收集；完成顺序不定，断言按集合口径
        List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class))).thenAnswer(inv -> {
            seen.add(TraceIds.current());
            return null;
        });

        relay.relay();

        assertEquals(2, seen.size(), "两行各恰好一次发送");
        assertEquals(java.util.Set.copyOf(List.of("trace-worker-1", "trace-worker-2")),
                java.util.Set.copyOf(seen), "worker 线程内发送段应以行内 traceId 续链");
        verify(outboxMapper).markSent(1L);
        verify(outboxMapper).markSent(2L);
        assertNull(TraceIds.current(), "主线程出口 finally 必须清理 MDC");
    }

    /** 行处理异常照原样传播，出口 finally 仍清理 MDC（锁已在既有语义中释放）。 */
    @Test
    void relay_exceptionPropagates_mdcStillCleared() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, "trace-row-1")));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("MQ 不可用"));
        when(outboxMapper.incrRetry(1L)).thenThrow(new RuntimeException("incrRetry 失败"));

        assertThrows(RuntimeException.class, () -> relay.relay());

        assertNull(TraceIds.current(), "异常传播时出口 finally 必须清理 MDC");
    }

    /** 锁获取被中断提前返回：入口 finally 仍清理 MDC（不查库不投递语义不变）。 */
    @Test
    void relay_lockInterrupted_mdcStillCleared() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));
        try {
            relay.relay();

            verify(outboxMapper, never()).selectPendingBatch(anyInt(), anyInt());
            assertNull(TraceIds.current(), "提前返回路径的入口 finally 必须清理 MDC");
            assertTrue(Thread.currentThread().isInterrupted(), "中断位应被置回");
        } finally {
            Thread.interrupted(); // 清理中断位，避免污染后续用例
        }
    }
}
