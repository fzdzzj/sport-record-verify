package com.sportverify.verify.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.api.verify.Verdict;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mapper.VerifyEventOutboxMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
 */
class VerifyOutboxRelayTest {

    private VerifyEventOutboxMapper outboxMapper;
    private RocketMQTemplate rocketMQTemplate;
    private RedissonClient redissonClient;
    private RLock lock;
    private VerifyEventProducer producer;
    private VerifyOutboxRelay relay;

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

        verify(outboxMapper, never()).selectPendingBatch(anyInt());
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(lock, never()).unlock();
    }

    /** 投递成功 → syncSend 到 topic:tag，traceId 透传到消息头，标记 SENT，释放锁 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_sendSuccess_marksSent() throws Exception {
        stubLockAcquired();
        VerifyEventOutbox pending = row(1L, 0);
        pending.setTraceId("trace-abc");
        when(outboxMapper.selectPendingBatch(100)).thenReturn(List.of(pending));
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
        when(outboxMapper.selectPendingBatch(100)).thenReturn(List.of(pending));
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
        when(outboxMapper.selectPendingBatch(100)).thenReturn(List.of(row(1L, 0), row(2L, 0)));
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

    /** 重试次数超阈值（≥16）→ 不投递不计数，仅告警保留行供人工处理 */
    @Test
    @SuppressWarnings("unchecked")
    void relay_retryExceeded_keepsRowForManual() throws Exception {
        stubLockAcquired();
        when(outboxMapper.selectPendingBatch(100)).thenReturn(List.of(row(1L, 16)));

        relay.relay();

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class));
        verify(outboxMapper, never()).markSent(anyLong());
        verify(outboxMapper, never()).incrRetry(anyLong());
        verify(lock).unlock();
    }
}
