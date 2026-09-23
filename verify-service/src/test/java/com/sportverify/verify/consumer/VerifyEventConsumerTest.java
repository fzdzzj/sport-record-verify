package com.sportverify.verify.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.verify.service.VerifyService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验事件消费者单元测试（eventId SETNX 去重 / 失败交 MQ 原生重试 / 解析失败丢弃）。
 *
 * <p>纯 Mockito 驱动私有 {code handleMessage}（经 ReflectionTestUtils 调用），
 * VerifyService / Redisson 全部 mock；真实 ObjectMapper 解析消息体。</p>
 *
 * <p>消费参数断言直接读 {@code buildConsumer(...)} 产出的**未启动**消费者实例：
 * 重试上限走 RocketMQ 原生 {@code maxReconsumeTimes}（超次由 broker 转
 * {@code %DLQ%verify-consumer-group}），业务侧不再有自建计数键与自建死信投递。</p>
 */
class VerifyEventConsumerTest {

    private VerifyService verifyService;
    private RedissonClient redissonClient;
    private RBucket<Object> bucket;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private VerifyEventConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        verifyService = mock(VerifyService.class);
        redissonClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);
        // 去双轨后消费者不再持有仅供自建 DLQ 使用的 RocketMQTemplate（构造参数随之收窄）
        consumer = new VerifyEventConsumer(verifyService, redissonClient,
                mapper, mock(RocketMQProperties.class));
        // 消费组名经 @Value 注入，单测手工补（buildConsumer 用它构造原生消费者）
        ReflectionTestUtils.setField(consumer, "consumerGroup", "verify-consumer-group");
    }

    private MessageExt message() throws Exception {
        String body = mapper.writeValueAsString(
                new VerifyEventDTO("evt-1", 1L, 100L, RecordVerifyEvents.EVENT_SUBMITTED,
                        LocalDateTime.now()));
        MessageExt msg = mock(MessageExt.class);
        when(msg.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(msg.getMsgId()).thenReturn("msg-1");
        when(msg.getUserProperty(anyString())).thenReturn(null);
        return msg;
    }

    /** 消费参数：重试上限由 MQ 原生承载（maxReconsumeTimes=3），不再自建计数 */
    @Test
    void buildConsumer_enablesNativeMaxReconsumeTimes3() throws Exception {
        DefaultMQPushConsumer c = consumer.buildConsumer("127.0.0.1:9876");

        assertEquals(3, c.getMaxReconsumeTimes());
    }

    /** 消费参数：订阅表达式仍为 SUBMITTED Tag（抽取观测缝不得改坏订阅） */
    @Test
    void buildConsumer_subscribesSubmittedTag() throws Exception {
        DefaultMQPushConsumer c = consumer.buildConsumer("127.0.0.1:9876");

        // 坑：DefaultMQPushConsumer#getSubscription() 读的是另一枚从未被 subscribe() 填充的字段（恒 null），
        // 订阅真身在 impl 的 rebalance 容器里，只能从 getSubscriptionInner() 读。
        SubscriptionData sub = c.getDefaultMQPushConsumerImpl()
                .getSubscriptionInner().get(RecordVerifyEvents.TOPIC);
        assertNotNull(sub);
        assertEquals(RecordVerifyEvents.TAG_SUBMITTED, sub.getSubString());
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

    /** 消息体无法解析 → 直接 ack 丢弃并告警；不占用去重键、不触发校验 */
    @Test
    void handleMessage_unparseableBody_discards() throws Exception {
        MessageExt msg = mock(MessageExt.class);
        when(msg.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));
        when(msg.getUserProperty(anyString())).thenReturn(null);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", msg);

        verify(verifyService, never()).verify(anyLong());
        verify(redissonClient, never()).getBucket(anyString());
    }

    /** 消费失败 → 删除去重键放行重投并 rethrow；**不**查询自建重试计数键（去双轨） */
    @Test
    void handleMessage_failure_neverConsultsSelfBuiltRetryKey() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(verifyService.verify(1L)).thenThrow(new RuntimeException("校验失败"));

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message()));

        verify(bucket).delete(); // 失败放行重投
        verify(redissonClient, never()).getAtomicLong(anyString());
    }

    /** 即便自建计数键被塞到旧口径的「超阈值」（4），失败仍 rethrow 交 MQ 原生重试——那套轨道已删 */
    @Test
    void handleMessage_failureAfterSelfBuiltThreshold_rethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(verifyService.verify(1L)).thenThrow(new RuntimeException("校验失败"));
        RAtomicLong legacyCounter = mock(RAtomicLong.class);
        when(legacyCounter.incrementAndGet()).thenReturn(4L); // 旧口径 count > 3 才进自建 DLQ
        when(redissonClient.getAtomicLong(anyString())).thenReturn(legacyCounter);

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message()));
        verify(redissonClient, never()).getAtomicLong(anyString());
    }

    /** Redis 删除去重键异常 → 不阻断重试放行，仍 rethrow 交 MQ 重投 */
    @Test
    void handleMessage_deleteDedupKeyRedisError_stillRethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(verifyService.verify(1L)).thenThrow(new RuntimeException("校验失败"));
        doThrow(new RuntimeException("Redis 不可用")).when(bucket).delete();

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message()));
        verify(redissonClient, never()).getAtomicLong(anyString());
    }
}
