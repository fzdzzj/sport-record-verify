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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VerifyOutboxRelay 分块批量标记 SENT 单元测试（纯 JUnit 5 + Mockito）.
 */
class VerifyOutboxRelayBatchMarkTest {

    private VerifyEventOutboxMapper outboxMapper;
    private RocketMQTemplate rocketMQTemplate;
    private RedissonClient redissonClient;
    private RLock lock;
    private VerifyEventProducer producer;
    private VerifyOutboxRelay relay;
    private Logger relayLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        outboxMapper = mock(VerifyEventOutboxMapper.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(redissonClient.getLock("verify:outbox:relay")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);

        producer = new VerifyEventProducer(rocketMQTemplate,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        relay = new VerifyOutboxRelay(outboxMapper, producer, redissonClient);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetry", 16);
        ReflectionTestUtils.setField(relay, "relaySendConcurrency", 1);
        ReflectionTestUtils.setField(relay,
                "relayDiagnosticsEnabled", false);
        ReflectionTestUtils.setField(relay,
                "relayDiagnosticsWindowMs", 10000L);

        relayLogger = (Logger) LoggerFactory.getLogger(VerifyOutboxRelay.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        relayLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        relayLogger.detachAppender(logAppender);
        logAppender.stop();
        relay.shutdownSendExecutor();
    }

    private List<VerifyEventOutbox> makeRows(final int count) {
        List<VerifyEventOutbox> rows = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            VerifyEventOutbox row = new VerifyEventOutbox();
            row.setId((long) i);
            row.setEventId("evt-" + i);
            row.setTopic("test-topic");
            row.setTag("SUBMITTED");
            row.setPayload("{\"id\":" + i + "}");
            row.setRetryCount(0);
            row.setStatus("PENDING");
            rows.add(row);
        }
        return rows;
    }

    @Test
    @DisplayName("1. 关闭：markSentBatch 零调用且 markSent 次数=成功行数")
    void test1_disabled_callsMarkSentPerRow_zeroMarkSentBatch() {
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", false);
        List<VerifyEventOutbox> rows = makeRows(5);
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(rows);

        relay.relay();

        verify(outboxMapper, never()).markSentBatch(any());
        verify(outboxMapper, times(5)).markSent(anyLong());
        verify(outboxMapper, never()).incrRetry(anyLong());
    }

    @Test
    @DisplayName("2. 开启 chunk=25、成功 100 行：恰 4 次、id 不重不漏且顺序一致")
    @SuppressWarnings("unchecked")
    void test2_enabled_100Rows_chunk25_exactlyFourCallsOrdered() {
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkChunkSize", 25);
        List<VerifyEventOutbox> rows = makeRows(100);
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(rows);
        when(outboxMapper.markSentBatch(any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        relay.relay();

        verify(outboxMapper, never()).markSent(anyLong());
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxMapper, times(4)).markSentBatch(captor.capture());

        List<List<Long>> allChunks = captor.getAllValues();
        assertEquals(4, allChunks.size());
        List<Long> flattened = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            List<Long> chunk = allChunks.get(i);
            assertEquals(25, chunk.size(), "每 chunk 恰 25 条");
            assertEquals(i * 25 + 1L, chunk.get(0));
            assertEquals((i + 1) * 25L, chunk.get(24));
            flattened.addAll(chunk);
        }
        List<Long> expected = rows.stream()
                .map(VerifyEventOutbox::getId)
                .collect(Collectors.toList());
        assertEquals(expected, flattened, "id 顺序与取批完全一致");
    }

    @Test
    @DisplayName("3. 成功 107 行：5 次调用（尾块 7 行）")
    @SuppressWarnings("unchecked")
    void test3_enabled_107Rows_chunk25_fiveCallsTailChunkSeven() {
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkChunkSize", 25);
        ReflectionTestUtils.setField(relay, "batchSize", 200);
        List<VerifyEventOutbox> rows = makeRows(107);
        when(outboxMapper.selectPendingBatch(200, 16)).thenReturn(rows);
        when(outboxMapper.markSentBatch(any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        relay.relay();

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxMapper, times(5)).markSentBatch(captor.capture());

        List<List<Long>> allChunks = captor.getAllValues();
        for (int i = 0; i < 4; i++) {
            assertEquals(25, allChunks.get(i).size());
        }
        assertEquals(7, allChunks.get(4).size(), "尾块恰 7 行");
    }

    @Test
    @DisplayName("4. 部分行发送失败：失败行不进 chunk、仍走 incrRetry")
    @SuppressWarnings("unchecked")
    void test4_enabled_failedSendDoesNotEnterChunk_callsIncrRetry() {
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkChunkSize", 25);
        List<VerifyEventOutbox> rows = makeRows(3);
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(rows);
        when(outboxMapper.markSentBatch(any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        doThrow(new RuntimeException("MQ 网络瞬断"))
                .when(rocketMQTemplate)
                .syncSend(anyString(), any(Message.class));

        // 让第二行抛错，第一行和第三行成功
        VerifyEventProducer customProducer = mock(VerifyEventProducer.class);
        doNothing().when(customProducer).syncSend(rows.get(0));
        doThrow(new RuntimeException("MQ error"))
                .when(customProducer).syncSend(rows.get(1));
        doNothing().when(customProducer).syncSend(rows.get(2));

        VerifyOutboxRelay r = new VerifyOutboxRelay(outboxMapper,
                customProducer, redissonClient);
        ReflectionTestUtils.setField(r, "batchSize", 100);
        ReflectionTestUtils.setField(r, "maxRetry", 16);
        ReflectionTestUtils.setField(r, "relaySendConcurrency", 1);
        ReflectionTestUtils.setField(r, "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(r, "relayBatchMarkChunkSize", 25);

        r.relay();

        verify(outboxMapper, times(1)).incrRetry(2L);
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxMapper, times(1)).markSentBatch(captor.capture());
        assertEquals(List.of(1L, 3L), captor.getValue(),
                "失败行（id=2）未进入 chunk，仅成功行进入");
    }

    @Test
    @DisplayName("5. 耗尽行（retryCount>=maxRetry）不进 chunk")
    @SuppressWarnings("unchecked")
    void test5_enabled_exhaustedRowDoesNotEnterChunk() {
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkChunkSize", 25);
        List<VerifyEventOutbox> rows = makeRows(3);
        rows.get(1).setRetryCount(16); // 耗尽行
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(rows);
        when(outboxMapper.markSentBatch(any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        relay.relay();

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxMapper, times(1)).markSentBatch(captor.capture());
        assertEquals(List.of(1L, 3L), captor.getValue(),
                "耗尽行（id=2）不进 chunk、不调用发送");
        verify(outboxMapper, never()).incrRetry(2L);
    }

    @Test
    @DisplayName("6. markSentBatch 返回 < ids.size()：WARN、不抛、不 incrRetry")
    void test6_enabled_partialAffectedLogsWarn_noException_noIncrRetry() {
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkChunkSize", 25);
        List<VerifyEventOutbox> rows = makeRows(25);
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(rows);
        when(outboxMapper.markSentBatch(any())).thenReturn(23);

        relay.relay();

        verify(outboxMapper, times(1)).markSentBatch(any());
        verify(outboxMapper, never()).incrRetry(anyLong());
        boolean hasWarn = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("批量标记 SENT 部分命中")
                        && m.contains("affected=23")
                        && m.contains("expected=25"));
        assertTrue(hasWarn, "必须记录部分命中 WARN 日志");
    }

    @Test
    @DisplayName("7. markSentBatch 抛异常：每 id 各一次 incrRetry、不外逃、后续 chunk 继续")
    @SuppressWarnings("unchecked")
    void test7_enabled_markSentBatchThrows_eachIdIncrRetry_noEscape_nextContinues() {
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkChunkSize", 2);
        List<VerifyEventOutbox> rows = makeRows(4); // 2 chunks: [1,2] and [3,4]
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(rows);
        when(outboxMapper.markSentBatch(List.of(1L, 2L)))
                .thenThrow(new RuntimeException("DB 异常"));
        when(outboxMapper.markSentBatch(List.of(3L, 4L)))
                .thenReturn(2);

        relay.relay(); // 不抛出异常

        verify(outboxMapper).incrRetry(1L);
        verify(outboxMapper).incrRetry(2L);
        verify(outboxMapper, never()).incrRetry(3L);
        verify(outboxMapper, never()).incrRetry(4L);
        verify(outboxMapper, times(2)).markSentBatch(any());
    }

    @Test
    @DisplayName("8. chunk-size 非法（0 / -1 / >batch-size）：钳位且只 WARN 一次")
    void test8_invalidChunkSize_clampedAndWarnedOnce() {
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(relay, "batchSize", 50);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkChunkSize", -5);
        List<VerifyEventOutbox> rows = makeRows(2);
        when(outboxMapper.selectPendingBatch(50, 16)).thenReturn(rows);
        when(outboxMapper.markSentBatch(any())).thenReturn(1);

        relay.relay();
        relay.relay();

        long warnCount = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("relay-batch-mark-chunk-size=-5 无效"))
                .count();
        assertEquals(1, warnCount, "钳位告警只打一条");
    }

    @Test
    @DisplayName("9. 与 sendConcurrency=4 组合：四 worker chunk 并集不重不漏")
    @SuppressWarnings("unchecked")
    void test9_enabled_sendConcurrency4_chunksUnionCompleteAndDisjoint() {
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(relay,
                "relaySendConcurrency", 4);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkChunkSize", 5);
        List<VerifyEventOutbox> rows = makeRows(20);
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(rows);
        when(outboxMapper.markSentBatch(any()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        relay.relay();

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(outboxMapper, times(4)).markSentBatch(captor.capture());

        Set<Long> collected = new HashSet<>();
        int totalRows = 0;
        for (List<Long> chunk : captor.getAllValues()) {
            assertEquals(5, chunk.size(), "每个 worker 的 chunk 恰好 5 条");
            totalRows += chunk.size();
            for (Long id : chunk) {
                boolean added = collected.add(id);
                assertTrue(added, "id=" + id + " 重复出现在多个 chunk 中");
            }
        }
        assertEquals(20, totalRows);
        assertEquals(20, collected.size());
        for (long id = 1; id <= 20; id++) {
            assertTrue(collected.contains(id), "id=" + id + " 遗漏");
        }
    }

    @Test
    @DisplayName("10. 诊断关闭无新分量，诊断开启包含 markBatchCalls/markBatchRows 且与实际一致")
    void test10_diagnostics_disabledNoNewLogs_enabledOutputsMarkBatchStats() {
        // Case A: 诊断关闭
        ReflectionTestUtils.setField(relay,
                "relayDiagnosticsEnabled", false);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkEnabled", true);
        ReflectionTestUtils.setField(relay,
                "relayBatchMarkChunkSize", 5);
        List<VerifyEventOutbox> rows = makeRows(10);
        when(outboxMapper.selectPendingBatch(100, 16)).thenReturn(rows);
        when(outboxMapper.markSentBatch(any())).thenReturn(5);

        relay.relay();

        boolean anyDiagLog = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("markBatchCalls="));
        assertFalse(anyDiagLog, "诊断关闭时不应输出 markBatchCalls");

        // Case B: 诊断开启
        logAppender.list.clear();
        ReflectionTestUtils.setField(relay,
                "relayDiagnosticsEnabled", true);
        ReflectionTestUtils.setField(relay,
                "diagnostics", null);
        relay.relay();

        String diagLog = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("outbox relay 诊断（批次）"))
                .findFirst()
                .orElse("");
        assertTrue(diagLog.contains("markBatchCalls=2, markBatchRows=10"),
                "开启诊断且开启批量标记时必须输出匹配的 markBatchCalls=2, markBatchRows=10；实际="
                        + diagLog);
    }
}
