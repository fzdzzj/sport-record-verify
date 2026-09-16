package com.sportverify.verify.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.verify.service.VerifyService;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验事件消费者单元测试（eventId SETNX 去重 / 失败重试 / 超阈值进死信 / 解析失败丢弃）。
 *
 * <p>纯 Mockito 驱动私有 {code handleMessage}（经 ReflectionTestUtils 调用），
 * VerifyService / Redisson / RocketMQTemplate 全部 mock；真实 ObjectMapper 解析消息体。</p>
 */
class VerifyEventConsumerTest {

    private VerifyService verifyService;
    private RedissonClient redissonClient;
    private RocketMQTemplate rocketMQTemplate;
    private RBucket<Object> bucket;
    private RAtomicLong retryCounter;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private VerifyEventConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        verifyService = mock(VerifyService.class);
        redissonClient = mock(RedissonClient.class);
        rocketMQTemplate = mock(RocketMQTemplate.class);
        bucket = mock(RBucket.class);
        retryCounter = mock(RAtomicLong.class);
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(retryCounter);
        consumer = new VerifyEventConsumer(verifyService, redissonClient, rocketMQTemplate,
                mapper, mock(RocketMQProperties.class));
    }

    private MessageExt message() throws Exception {
        String body = mapper.writeValueAsString(
                new VerifyEventDTO("evt-1", 1L, 100L, RecordVerifyEvents.EVENT_SUBMITTED,
                        LocalDateTime.now()));
        MessageExt msg = mock(MessageExt.class);
        when(msg.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(msg.getMsgId()).thenReturn("msg-1");
        return msg;
    }

    /** 事件幂等：eventId SETNX 返回 false（重复投递）→ 直接跳过，不执行校验 */
    @Test
    void handleMessage_duplicateEvent_skips() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message());

        verify(verifyService, never()).verify(anyLong());
    }

    /** 正常消费：SETNX 成功 → 执行校验，无异常 */
    @Test
    void handleMessage_firstTime_consumes() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message());

        verify(verifyService).verify(1L);
    }

    /** 消息体无法解析 → 直接 ack 丢弃并告警，不触发校验、不重试 */
    @Test
    void handleMessage_unparseableBody_discards() throws Exception {
        MessageExt msg = mock(MessageExt.class);
        when(msg.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", msg);

        verify(verifyService, never()).verify(anyLong());
        verify(rocketMQTemplate, never()).syncSend(anyString(), anyString());
    }

    /** 失败未超重试阈值 → 删除去重键、重试计数自增后会重新抛出（交由 MQ 重投） */
    @Test
    void handleMessage_retryNotExceeded_rethrowsForRetry() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(verifyService.verify(1L)).thenThrow(new RuntimeException("校验失败"));
        when(retryCounter.incrementAndGet()).thenReturn(1L); // 1 ≤ 3，未超阈值

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message()));

        verify(bucket).delete(); // 失败放行重投
        verify(rocketMQTemplate, never()).syncSend(anyString(), anyString());
    }

    /** 重试计数超阈值 → 投递死信队列，视为处理完成不再重投 */
    @Test
    void handleMessage_retryExceeded_sendsToDlq() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(verifyService.verify(1L)).thenThrow(new RuntimeException("校验失败"));
        when(retryCounter.incrementAndGet()).thenReturn(4L); // 4 > 3，超阈值

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message());

        verify(bucket).delete();
        verify(rocketMQTemplate).syncSend(eq(RecordVerifyEvents.DLQ_TOPIC), anyString());
    }

    /** 投递死信队列失败 → 仅告警，不抛出（DLQ 写入失败不阻断消费） */
    @Test
    void handleMessage_dlqSendFailure_swallows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(verifyService.verify(1L)).thenThrow(new RuntimeException("校验失败"));
        when(retryCounter.incrementAndGet()).thenReturn(4L);
        doThrow(new RuntimeException("MQ 写入失败")).when(rocketMQTemplate)
                .syncSend(anyString(), anyString());

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message()); // 不抛异常
        verify(bucket).delete();
    }

    /** Redis 重试计数异常 → 视为未超阈值，重新抛出等待 MQ 重投 */
    @Test
    void handleMessage_retryCounterRedisError_stillRethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(verifyService.verify(1L)).thenThrow(new RuntimeException("校验失败"));
        when(retryCounter.incrementAndGet()).thenThrow(new RuntimeException("Redis 不可用"));

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message()));
        verify(rocketMQTemplate, never()).syncSend(anyString(), anyString());
    }

    /** Redis 删除去重键异常 → 不阻断重试放行，仍 rethrow */
    @Test
    void handleMessage_deleteDedupKeyRedisError_stillRethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(verifyService.verify(1L)).thenThrow(new RuntimeException("校验失败"));
        when(retryCounter.incrementAndGet()).thenReturn(1L);
        doThrow(new RuntimeException("Redis 不可用")).when(bucket).delete();

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message()));
        verify(rocketMQTemplate, never()).syncSend(anyString(), anyString());
    }
}