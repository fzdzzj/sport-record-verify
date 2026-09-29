package com.sportverify.verify.mq;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * outbox relay 批内并发投递的单元测试（TASK-160：G1b/G2/G3 硬门 + D1/D4/D6 落地行为）。
 *
 * <p>默认路径未变由既有 {@code VerifyOutboxRelayTest} 的 19 个用例兜底（一字未改），
 * 本类只测并发开关的新增行为：默认/显式 1 时不创建任何线程池对象（G1b）；
 * 已取批次按列表下标取模切成不重不漏的 N 份、每行恰一次发送与标记（G2）；
 * 发送/标记/意外单行异常被隔离、其余行完整处理且异常不逃出 worker（G3 与 D5.7）；
 * 池懒建、跨轮复用、daemon 前缀、@PreDestroy 关闭（D4）；配置 &lt; 1 钳到 1 并告警（D1）；
 * 并发摘要标明 sendConcurrency、段值为线程时间聚合、residual 可为负（D6）。</p>
 */
class VerifyOutboxRelayConcurrencyTest {

    private VerifyEventOutboxMapper outboxMapper;
    private RocketMQTemplate rocketMQTemplate;
    private RedissonClient redissonClient;
    private RLock lock;
    private VerifyEventProducer producer;
    private VerifyOutboxRelay relay;
    private Logger relayLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        freshMocks();
        relayLogger = (Logger) LoggerFactory.getLogger(VerifyOutboxRelay.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        relayLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        relayLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    /** 重建全部 mock 与 relay（跨轮独立；logback appender 复用，仅清空记录）。 */
    private void freshMocks() {
        outboxMapper = mock(VerifyEventOutboxMapper.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock("verify:outbox:relay")).thenReturn(lock);
        producer = new VerifyEventProducer(rocketMQTemplate,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        relay = new VerifyOutboxRelay(outboxMapper, producer, redissonClient);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetry", 16);
        if (logAppender != null) {
            logAppender.list.clear();
        }
    }

    private void stubLockAcquired() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
    }

    private void setConcurrency(int concurrency) {
        ReflectionTestUtils.setField(
                relay, "relaySendConcurrency", concurrency);
    }

    private void enableDiagnostics() {
        ReflectionTestUtils.setField(relay, "relayDiagnosticsEnabled", true);
    }

    private VerifyEventOutbox row(long id, int retryCount) {
        VerifyEventOutbox r = new VerifyEventOutbox();
        r.setId(id);
        r.setEventId("evt-" + id);
        r.setTopic("record-verify-events");
        r.setTag("VERIFIED");
        r.setPayload("{\"eventId\":\"evt-" + id + "\"}");
        r.setStatus("PENDING");
        r.setRetryCount(retryCount);
        return r;
    }

    private List<String> diagnosticMessages() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("outbox relay 诊断"))
                .collect(Collectors.toList());
    }

    private List<String> allMessages() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    private int countContains(List<String> values, String token) {
        int count = 0;
        for (String value : values) {
            if (value.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private List<VerifyEventOutbox> batchOfIds(long fromId, long toId) {
        List<VerifyEventOutbox> batch = new ArrayList<>();
        for (long id = fromId; id <= toId; id++) {
            batch.add(row(id, 0));
        }
        return batch;
    }

    /** G1b：默认与显式 1 时跑过真实一轮，执行器字段仍为 null（从未创建线程池对象）。 */
    @Test
    void concurrencyOne_neverCreatesExecutorPool() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, 0)));

        relay.relay();

        assertNull(ReflectionTestUtils.getField(relay, "sendExecutor"),
                "默认并发 1 不得创建线程池对象");

        setConcurrency(1);
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of());
        relay.relay();
        assertNull(ReflectionTestUtils.getField(relay, "sendExecutor"),
                "显式并发 1 不得创建线程池对象");
    }

    /**
     * G2（不重不漏划分，硬）：batch=100、N∈{1,2,3,8}——断言每行 syncSend 与
     * markSent 各恰好一次（mock 计数）、发送与标记覆盖全部 100 个 id（互不相交
     * 且并集完整，Σ子列表大小 == batch.size()）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void partition_isExhaustiveAndDisjoint() throws Exception {
        for (int n : new int[] {1, 2, 3, 8}) {
            freshMocks();
            setConcurrency(n);
            stubLockAcquired();
            when(outboxMapper.selectPendingBatch(100, 16))
                    .thenReturn(batchOfIds(1L, 100L));

            relay.relay();

            ArgumentCaptor<Message> msgCaptor =
                    ArgumentCaptor.forClass(Message.class);
            verify(rocketMQTemplate, times(100))
                    .syncSend(anyString(), msgCaptor.capture());
            List<String> payloads = new ArrayList<>();
            for (Message<?> message : msgCaptor.getAllValues()) {
                payloads.add(String.valueOf(message.getPayload()));
            }
            assertEquals(100, payloads.size(),
                    "N=" + n + "：Σ子列表大小应等于批次大小（不漏）");
            for (long id = 1; id <= 100; id++) {
                String token = "\"eventId\":\"evt-" + id + "\"";
                assertEquals(1, countContains(payloads, token),
                        "N=" + n + "：id=" + id + " 应恰好被发送一次（不重不漏）");
                verify(outboxMapper, times(1)).markSent(id);
            }
            verify(outboxMapper, never()).incrRetry(anyLong());
        }
    }

    /**
     * G3-1（失败隔离，硬）：N=3、某行 syncSend 抛错——该行恰好一次 incrRetry、
     * 不标 SENT，其余行仍被完整处理，failed/success 计数正确，异常不逃出 relay()。
     */
    @Test
    void sendFailure_isIsolated_restOfBatchStillDelivered() throws Exception {
        setConcurrency(3);
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(batchOfIds(1L, 7L));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenAnswer(inv -> {
                    Message<?> message = inv.getArgument(1);
                    if (String.valueOf(message.getPayload())
                            .contains("\"eventId\":\"evt-2\"")) {
                        throw new RuntimeException("MQ 不可用");
                    }
                    return null;
                });

        relay.relay();

        verify(outboxMapper, times(1)).incrRetry(2L);
        verify(outboxMapper, times(1)).incrRetry(anyLong());
        verify(outboxMapper, never()).markSent(2L);
        for (long id : new long[] {1L, 3L, 4L, 5L, 6L, 7L}) {
            verify(outboxMapper).markSent(id);
        }
        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "非空批次至多一条汇总：" + diag);
        assertTrue(diag.get(0).contains("success=6"), diag.get(0));
        assertTrue(diag.get(0).contains("failed=1"), diag.get(0));
        assertTrue(diag.get(0).contains("sendConcurrency=3"), diag.get(0));
    }

    /**
     * G3-2（失败隔离，硬）：N=2、某行 markSent 抛错——该行恰好一次 incrRetry、
     * 不重复投递，其余行仍被完整处理，计数正确，异常不逃出 relay()。
     */
    @Test
    void markSentFailure_incrRetryOnce_restProceeds() throws Exception {
        setConcurrency(2);
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(batchOfIds(1L, 5L));
        when(outboxMapper.markSent(eq(3L)))
                .thenThrow(new RuntimeException("标记失败"));

        relay.relay();

        verify(outboxMapper, times(1)).incrRetry(3L);
        verify(outboxMapper, times(1)).incrRetry(anyLong());
        for (long id = 1; id <= 5; id++) {
            verify(outboxMapper, times(1)).markSent(id);
        }
        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "非空批次至多一条汇总：" + diag);
        assertTrue(diag.get(0).contains("success=4"), diag.get(0));
        assertTrue(diag.get(0).contains("failed=1"), diag.get(0));
    }

    /**
     * G3-3（失败隔离，硬）：耗尽行混在批次中间——不投递不标记、exhausted=1，
     * 其余行仍被完整处理，异常不逃出 relay()。
     */
    @Test
    void exhaustedRow_mixedInBatch_isSkippedNotDelivered() throws Exception {
        setConcurrency(2);
        enableDiagnostics();
        stubLockAcquired();
        List<VerifyEventOutbox> batch = new ArrayList<>();
        batch.add(row(1L, 0));
        batch.add(row(2L, 16));
        batch.add(row(3L, 0));
        batch.add(row(4L, 0));
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(batch);

        relay.relay();

        verify(rocketMQTemplate, times(3))
                .syncSend(anyString(), any(Message.class));
        verify(outboxMapper, never()).markSent(2L);
        verify(outboxMapper, never()).incrRetry(anyLong());
        for (long id : new long[] {1L, 3L, 4L}) {
            verify(outboxMapper).markSent(id);
        }
        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "非空批次至多一条汇总：" + diag);
        assertTrue(diag.get(0).contains("exhausted=1"), diag.get(0));
        assertTrue(diag.get(0).contains("success=3"), diag.get(0));
    }

    /**
     * D5.7（单行异常绝不允许逃出 worker，硬）：某行 syncSend 抛错后 incrRetry
     * 再抛错——异常由 worker 循环体兜住（仅告警），同子列表其余行继续处理，
     * relay() 不抛。
     */
    @Test
    void unexpectedRowException_isContainedByWorker() throws Exception {
        setConcurrency(2);
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(batchOfIds(1L, 4L));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenAnswer(inv -> {
                    Message<?> message = inv.getArgument(1);
                    if (String.valueOf(message.getPayload())
                            .contains("\"eventId\":\"evt-2\"")) {
                        throw new RuntimeException("MQ 不可用");
                    }
                    return null;
                });
        when(outboxMapper.incrRetry(2L))
                .thenThrow(new RuntimeException("incrRetry 失败"));

        assertDoesNotThrow(() -> relay.relay());

        verify(outboxMapper).incrRetry(2L);
        verify(outboxMapper, never()).markSent(2L);
        for (long id : new long[] {1L, 3L, 4L}) {
            verify(outboxMapper).markSent(id);
        }
    }

    /**
     * D6（并发诊断口径）：并发摘要标明 sendConcurrency=2 与「线程时间聚合」说明，
     * 且两行并行各 ~150ms 发送 + ~150ms 标记时聚合段和必超锁内墙钟，
     * residualMs 应如实为负（不得被钳成 0 伪装成未归因墙钟）。
     */
    @Test
    void concurrencySummary_reportsThreadTimeAndNegativeResidual()
            throws Exception {
        setConcurrency(2);
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, 0), row(2L, 0)));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenAnswer(inv -> {
                    Thread.sleep(150);
                    return null;
                });
        when(outboxMapper.markSent(anyLong())).thenAnswer(inv -> {
            Thread.sleep(150);
            return 1;
        });

        relay.relay();

        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "非空批次一条汇总：" + diag);
        String summary = diag.get(0);
        assertTrue(summary.contains("sendConcurrency=2"), summary);
        assertTrue(summary.contains("线程时间"), summary);
        assertTrue(summary.contains("residualMs=-"),
                "并发下 residualMs 应如实可为负：" + summary);
    }

    /**
     * D4（线程池生命周期）：并发下两轮共用同一个池（不得每轮新建），
     * 池线程是 daemon 且名字带可识别前缀。
     */
    @Test
    void poolReused_acrossRounds_daemonThreads() throws Exception {
        setConcurrency(2);
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, 0)));

        relay.relay();
        Object first = ReflectionTestUtils.getField(relay, "sendExecutor");
        relay.relay();
        Object second = ReflectionTestUtils.getField(relay, "sendExecutor");

        assertNotNull(first, "并发 > 1 应懒建线程池");
        assertSame(first, second, "不得每轮新建线程池");
        boolean found = false;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("verify-outbox-relay-")
                    && thread.isDaemon()) {
                found = true;
            }
        }
        assertTrue(found, "应存在名为 verify-outbox-relay-* 的 daemon 池线程");
    }

    /**
     * D4：@PreDestroy 停止懒建线程池（进入关闭态）；从未建池的串行实例上
     * 直接调用不得抛错。
     */
    @Test
    void preDestroyShutsDownPool_serialPathSafeWithoutPool() throws Exception {
        setConcurrency(2);
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16))
                .thenReturn(List.of(row(1L, 0)));
        relay.relay();
        ExecutorService pool = (ExecutorService) ReflectionTestUtils
                .getField(relay, "sendExecutor");
        assertNotNull(pool, "并发 > 1 应已建池");

        relay.shutdownSendExecutor();

        assertTrue(pool.isShutdown(), "池应进入关闭态");

        freshMocks(); // 串行实例从未建池
        relay.shutdownSendExecutor(); // 字段为 null，直接返回，不得抛错
        assertNull(ReflectionTestUtils.getField(relay, "sendExecutor"));
    }

    /** D1：配置 < 1 时钳到 1 并打一条 WARN（不抛异常），钳位后走串行不建池。 */
    @Test
    void invalidConcurrency_clampedToOneWithWarn_serialPath() throws Exception {
        for (int invalid : new int[] {0, -3}) {
            freshMocks();
            setConcurrency(invalid);
            stubLockAcquired();
            when(outboxMapper.selectPendingBatch(100, 16))
                    .thenReturn(List.of(row(1L, 0)));

            relay.relay();

            assertNull(ReflectionTestUtils.getField(relay, "sendExecutor"),
                    "钳位到 1 后走串行，不得创建线程池");
            verify(outboxMapper).markSent(1L);
            String warnToken =
                    "verify.outbox.relay-send-concurrency=" + invalid;
            assertTrue(countContains(allMessages(), warnToken) >= 1,
                    "应有一条无效配置钳位 WARN：" + allMessages());
        }
    }
}
