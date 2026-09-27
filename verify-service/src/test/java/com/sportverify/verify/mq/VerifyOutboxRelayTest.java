package com.sportverify.verify.mq;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.api.verify.Verdict;
import com.sportverify.common.trace.TraceIds;
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

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * outbox relay 单元测试（F03：防重锁 / 投递成功标 SENT / 失败 retry+1 / 超阈值保留 / 行内 eventId 透传）。
 *
 * <p>纯 Mockito：Mapper / Redisson 全 mock，RocketMQTemplate mock 但**生产者用真实对象**——
 * relay 的发送细节只经 {@link VerifyEventProducer#syncSend} 一处，故这里顺便判定
 * 「relay 按行内 topic/tag/payload 投递、重发不换 eventId」。
 * {@code batchSize}/{@code maxRetry} 经 ReflectionTestUtils 注入（同 @Value 默认值语义）。</p>
 *
 * <p>TASK-142：取批资格（{@code retry_count < maxRetry} 排除耗尽行）的<strong>真实 SQL 行为红/绿</strong>
 * 由隔离 scratch MySQL 上执行的 {@code work/mailbox/verification/task142-outbox-poison-sql.sql} 实证，
 * 本类只判定 relay 是否把当前上限传给取批查询、以及放行后一可投递行的发送语义，不以 mock 列表冒充 SQL 红。</p>
 */
class VerifyOutboxRelayTest {

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
        // 诊断默认关闭：挂上 logback 内存 appender 以便判定「关闭时无诊断输出 / 开启时有界输出」
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

    private void enableDiagnostics() {
        ReflectionTestUtils.setField(relay, "relayDiagnosticsEnabled", true);
    }

    /** 本轮出现的诊断汇总行（按输出顺序）；关闭开关时应为空。 */
    private List<String> diagnosticMessages() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("outbox relay 诊断"))
                .collect(Collectors.toList());
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

    private void stubLockAcquired() throws InterruptedException {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
    }

    /** 拿不到防重锁（另一实例正在跑）→ 直接跳过，不查库不投递 */
    @Test
    void relay_lockNotAcquired_skips() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        relay.relay();

        verify(outboxMapper, never()).selectPendingBatch(anyInt(), anyInt());
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(lock, never()).unlock();
    }

    /** 取批资格：relay 必须把当前批次上限与重试上限一起传给 Mapper（耗尽行由 SQL 过滤，不占批次） */
    @Test
    void relay_passesBatchSizeAndRetryThreshold_toBatchQuery() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of());

        relay.relay();

        verify(outboxMapper).selectPendingBatch(100, 16);
        verify(lock).unlock();
    }

    /** 投递成功 → syncSend 到 topic:tag，traceId 透传到消息头，标记 SENT，释放锁 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_sendSuccess_marksSent() throws Exception {
        stubLockAcquired();
        VerifyEventOutbox pending = row(1L, 0);
        pending.setTraceId("trace-abc");
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(pending));
        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);

        relay.relay();

        verify(rocketMQTemplate).syncSend(dest.capture(), msgCaptor.capture());
        assertEquals("record-verify-events:VERIFIED", dest.getValue());
        assertEquals("trace-abc", msgCaptor.getValue().getHeaders().get(TraceIds.HEADER));
        verify(outboxMapper).markSent(1L);
        verify(outboxMapper, never()).incrRetry(anyLong());
        verify(lock).unlock();
    }

    /** 行内 eventId 透传：投递体里的 eventId 与行内 eventId 逐字一致（relay 不重新生成） */
    @Test
    @SuppressWarnings("unchecked")
    void relay_resendsWithRowEventId_neverRegenerates() throws Exception {
        stubLockAcquired();
        // 按写侧真实语义造行：eventId 与 payload 都由生产者生成一次
        VerifyEventOutbox pending = producer.newPendingRow(Verdict.PASSED, 7L, 100L);
        pending.setId(3L);
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(pending));
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);

        relay.relay();

        verify(rocketMQTemplate).syncSend(anyString(), msgCaptor.capture());
        String body = String.valueOf(msgCaptor.getValue().getPayload());
        assertTrue(body.contains("\"eventId\":\"" + pending.getEventId() + "\""),
                "投递体应沿用行内 eventId，实测 body=" + body);
        assertEquals(pending.getPayload(), body);
        verify(outboxMapper).markSent(3L);
    }

    /** 投递失败 → retry_count+1 留下轮，不标 SENT，不中断后续行 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_sendFailure_incrRetryAndContinues() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 0), row(2L, 0)));
        // syncSend 非 void：第一次抛异常（第 1 行投递失败），第二次返回成功（第 2 行不受影响）
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("MQ 不可用"))
                .thenReturn(null);

        relay.relay();

        verify(outboxMapper).incrRetry(1L);
        verify(outboxMapper, never()).markSent(1L);
        verify(outboxMapper).markSent(2L); // 后续行不受影响
        verify(lock).unlock();
    }

    /** 重试次数超阈值（≥16）→ 不投递不计数，仅告警保留行供人工处理；取批已按当前上限过滤，本路径为防御性兜底 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_retryExceeded_keepsRowForManual() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 16)));

        relay.relay();

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(outboxMapper, never()).markSent(anyLong());
        verify(outboxMapper, never()).incrRetry(anyLong());
        verify(lock).unlock();
    }

    /** 阈值边界：retry_count=15（maxRetry-1）仍有资格，发送成功并标 SENT */
    @Test
    @SuppressWarnings("unchecked")
    void relay_retryAtThresholdMinusOne_stillSends() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(7L, 15)));

        relay.relay();

        verify(rocketMQTemplate).syncSend(anyString(), any(Message.class));
        verify(outboxMapper).markSent(7L);
        verify(outboxMapper, never()).incrRetry(anyLong());
    }

    /**
     * 队首满批耗尽行被 SQL 过滤后，紧随其后的正常行进入本轮批次、被发送并沿用原 eventId。
     *
     * <p>此处取批结果按新 SQL 语义给出（{@code retry_count < 16} 过滤后的行）；
     * 「旧 SQL 会把该行挡在批外」的<strong>实证在 scratch MySQL 脚本</strong>，不以本 mock 充当 SQL 红。</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void relay_liveRowAfterExhaustedHead_isSent() throws Exception {
        stubLockAcquired();
        VerifyEventOutbox live = row(101L, 0);
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(live));
        ArgumentCaptor<Message> msgCaptor = ArgumentCaptor.forClass(Message.class);

        relay.relay();

        verify(rocketMQTemplate).syncSend(anyString(), msgCaptor.capture());
        assertTrue(String.valueOf(msgCaptor.getValue().getPayload()).contains("\"eventId\":\"evt-101\""),
                "应沿用行内原 eventId 投递，不重新生成");
        verify(outboxMapper).markSent(101L);
        verify(outboxMapper, never()).incrRetry(anyLong());
    }

    /** 诊断默认关闭：一批投递只产生原有逐行日志，不产生任何批次/空轮诊断汇总。 */
    @Test
    void relay_diagnosticsDisabled_emitsNoDiagnosticSummary() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 0)));

        relay.relay();

        assertTrue(diagnosticMessages().isEmpty(), "关闭时不得输出诊断汇总：" + diagnosticMessages());
        verify(outboxMapper).selectPendingBatch(100, 16);
        verify(outboxMapper).markSent(1L);
    }

    /** 诊断开启 + 成功批：每个非空批次恰好一条汇总，含行数与成功数，且不泄露 eventId/payload。 */
    @Test
    void relay_diagnosticsEnabled_successBatch_logsSingleSummary() throws Exception {
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 0), row(2L, 0)));

        relay.relay();

        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "非空批次至多一条汇总：" + diag);
        String m = diag.get(0);
        assertTrue(m.contains("rows=2"), m);
        assertTrue(m.contains("success=2"), m);
        assertTrue(m.contains("failed=0"), m);
        assertTrue(m.contains("exhausted=0"), m);
        assertTrue(m.contains("residualMs="), m);
        assertTrue(!m.contains("evt-1") && !m.contains("payload"),
                "诊断汇总不得输出 eventId/payload：" + m);
        verify(outboxMapper).markSent(1L);
        verify(outboxMapper).markSent(2L);
    }

    /** 诊断开启 + 失败行：汇总区分成功/失败计数，失败行仍按既有规则递增 retry。 */
    @Test
    void relay_diagnosticsEnabled_failureRow_logsFailedCount() throws Exception {
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 0), row(2L, 0)));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("MQ 不可用"))
                .thenReturn(null);

        relay.relay();

        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "非空批次至多一条汇总：" + diag);
        String m = diag.get(0);
        assertTrue(m.contains("success=1"), m);
        assertTrue(m.contains("failed=1"), m);
        verify(outboxMapper).incrRetry(1L);
        verify(outboxMapper).markSent(2L);
    }

    /** 诊断开启 + 空批：输出有界空轮汇总（首轮即一条），不投递不标记。 */
    @Test
    void relay_diagnosticsEnabled_emptyBatch_logsIdleSummary() throws Exception {
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of());

        relay.relay();

        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "空轮汇总有界：" + diag);
        assertTrue(diag.get(0).contains("空轮/竞争汇总"), diag.get(0));
        assertTrue(diag.get(0).contains("emptyRounds=1"), diag.get(0));
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(lock).unlock();
    }

    /** 诊断开启 + 拿不到锁：记录锁竞争计数，不查库不投递。 */
    @Test
    void relay_diagnosticsEnabled_lockNotAcquired_logsContention() throws Exception {
        enableDiagnostics();
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        relay.relay();

        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "锁竞争汇总有界：" + diag);
        assertTrue(diag.get(0).contains("lockSkips=1"), diag.get(0));
        verify(outboxMapper, never()).selectPendingBatch(anyInt(), anyInt());
        verify(lock, never()).unlock();
    }

    // ---------- TASK-146：计时口径判别（先红后绿） ----------

    /**
     * 从诊断批次汇总行里取某个 {@code xxxMs=} 字段值（正则固定字段名，避免 lockProcessingMs/lockHoldMs 误配）。
     */
    private long extractMs(String summary, String field) {
        Matcher m = Pattern.compile(Pattern.quote(field) + "=(\\d+)").matcher(summary);
        assertTrue(m.find(), "诊断汇总缺少字段 " + field + "：" + summary);
        return Long.parseLong(m.group(1));
    }

    /**
     * 判别式（目标行为红）：{@code markSent} 抛错时，已计过的发送墙钟不得被重复归到发送，
     * 失败标记尝试所耗墙钟应归属标记段。旧实现从 {@code sendStart} 重算，会把标记耗时错记到 {@code sendMs}。
     */
    @Test
    void relay_markSentFailure_doesNotAttributeMarkTimeToSend() throws Exception {
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 0)));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class))).thenReturn(null);
        when(outboxMapper.markSent(1L)).thenAnswer(inv -> {
            Thread.sleep(300);
            throw new RuntimeException("markSent 失败");
        });

        relay.relay();

        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "非空批次一条汇总：" + diag);
        long sendMs = extractMs(diag.get(0), "sendMs");
        long markMs = extractMs(diag.get(0), "markMs");
        assertTrue(sendMs < 100,
                "发送很快返回，失败的标记耗时不得错归到发送：sendMs=" + sendMs + " / " + diag.get(0));
        assertTrue(markMs >= 200,
                "标记失败尝试的耗时属标记段：markMs=" + markMs + " / " + diag.get(0));
        verify(outboxMapper).incrRetry(1L);
        verify(lock).unlock();
    }

    /**
     * 判别式（目标行为红）：{@code lockHoldMs} 的终点必须在 {@code unlock()} 调用返回之后取得，因而应包含解锁耗时；
     * 旧实现在 unlock 之前截取（仅锁内处理段）。该计时含解锁调用、<strong>不含</strong>其后的摘要输出；且当
     * {@code unlock()} 抛错被捕获时不代表锁已确实释放，故本用例只断言真实计时边界，不声称「完整占锁」或已释放。
     */
    @Test
    void relay_lockHold_reportsCompleteHoldIncludingUnlock() throws Exception {
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 0)));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class))).thenReturn(null);
        when(outboxMapper.markSent(1L)).thenReturn(1);
        doAnswer(inv -> {
            Thread.sleep(300);
            return null;
        }).when(lock).unlock();

        relay.relay();

        List<String> diag = diagnosticMessages();
        assertEquals(1, diag.size(), "非空批次一条汇总：" + diag);
        long lockHoldMs = extractMs(diag.get(0), "lockHoldMs");
        assertTrue(lockHoldMs >= 200,
                "lockHoldMs 终点须在 unlock() 调用之后，应含解锁耗时（不代表已证实释放）：lockHoldMs=" + lockHoldMs + " / " + diag.get(0));
    }

    /** 成功行：发送段与标记段各自只累计一次，两段都为正且都在合理量级（无重复计入）。 */
    @Test
    void relay_success_countsSendAndMarkOnce() throws Exception {
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 0)));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class))).thenAnswer(inv -> {
            Thread.sleep(150);
            return null;
        });
        when(outboxMapper.markSent(1L)).thenAnswer(inv -> {
            Thread.sleep(150);
            return 1;
        });

        relay.relay();

        String summary = diagnosticMessages().get(0);
        long sendMs = extractMs(summary, "sendMs");
        long markMs = extractMs(summary, "markMs");
        assertTrue(sendMs >= 100 && sendMs < 400, "sendMs 应为单次发送量级：" + sendMs);
        assertTrue(markMs >= 100 && markMs < 400, "markMs 应为单次标记量级：" + markMs);
    }

    /** incrRetry 抛错：异常照原样传播，finally 仍释放锁，后续行不再处理。 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_incrRetryFailure_propagatesAfterUnlock() throws Exception {
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 0)));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenThrow(new RuntimeException("MQ 不可用"));
        when(outboxMapper.incrRetry(1L)).thenThrow(new RuntimeException("incrRetry 失败"));

        assertThrows(RuntimeException.class, () -> relay.relay());

        verify(lock).unlock();
        verify(outboxMapper, never()).markSent(anyLong());
    }

    /** 锁获取被中断：置回中断位并跳过本轮，不查库、不投递、不解锁。 */
    @Test
    void relay_lockAcquireInterrupted_skipsWithoutUnlock() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));
        try {
            relay.relay();

            verify(outboxMapper, never()).selectPendingBatch(anyInt(), anyInt());
            verify(lock, never()).unlock();
            assertTrue(Thread.currentThread().isInterrupted(), "中断位应被置回");
        } finally {
            Thread.interrupted(); // 清理中断位，避免污染后续用例
        }
    }

    /** 解锁异常：被吞并告警，成功投递结果不受影响（租期兜底释放语义不变）。 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_unlockFailure_isSwallowed_deliveryStillDone() throws Exception {
        enableDiagnostics();
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(List.of(row(1L, 0)));
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class))).thenReturn(null);
        when(outboxMapper.markSent(1L)).thenReturn(1);
        doThrow(new RuntimeException("unlock 失败")).when(lock).unlock();

        relay.relay();

        verify(outboxMapper).markSent(1L);
        verify(outboxMapper, never()).incrRetry(anyLong());
    }
}
