package com.sportverify.user.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.event.VerifyEventDTO;
import com.sportverify.user.enums.NotificationType;
import com.sportverify.user.service.NotificationService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 判定事件通知消费者单元测试（TASK-182 add-notification-center）。
 *
 * <p>消费范式沿 {@code LeaderboardEventConsumerTest}：buildConsumer 配置断言（maxReconsumeTimes=3、
 * 订阅表达式）+ handleMessage 分发（VERIFIED → RECORD_VERIFIED / REJECTED → RECORD_REJECTED）、
 * 去重、解析失败 ack 跳过、失败交 MQ 原生重试。Mapper/Redisson mock、生产侧真实对象不用。</p>
 *
 * <p>双重幂等证据（单测侧）：Redis SETNX 去重（首投 true 落通知、重投 false 跳过）+
 * 表级 {@code uk_dedup} 由 NotificationService.createNotification 的 INSERT IGNORE 承载
 * （实测见 NotificationServiceTest），本用例聚焦消费者的 SETNX 去重与按 eventType 分发。</p>
 */
class NotificationEventConsumerTest {

    private NotificationService notificationService;
    private RedissonClient redissonClient;
    private RBucket<Object> bucket;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private NotificationEventConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        notificationService = mock(NotificationService.class);
        redissonClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);
        consumer = new NotificationEventConsumer(notificationService, redissonClient,
                mapper, mock(RocketMQProperties.class));
        // 消费组名经 @Value 注入，单测手工补（buildConsumer 用它构造原生消费者）
        ReflectionTestUtils.setField(consumer, "consumerGroup", "notification-consumer-group");
    }

    private MessageExt message(String eventType) throws Exception {
        String body = mapper.writeValueAsString(
                new VerifyEventDTO("evt-x", 7L, 100L, eventType, LocalDateTime.now()));
        MessageExt msg = mock(MessageExt.class);
        when(msg.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(msg.getMsgId()).thenReturn("msg-x");
        when(msg.getUserProperty(anyString())).thenReturn(null);
        return msg;
    }

    /** 消费参数：重试上限由 MQ 原生承载（maxReconsumeTimes=3），订阅 VERIFIED||REJECTED */
    @Test
    void buildConsumer_configuresNativeMaxReconsumeTimes3AndTagSubscription() throws Exception {
        DefaultMQPushConsumer built = consumer.buildConsumer("127.0.0.1:9876");

        assertEquals(3, built.getMaxReconsumeTimes(),
                "重试上限必须走 RocketMQ 原生 maxReconsumeTimes=3，不自建重试计数");
        // 坑（沿 LeaderboardEventConsumerTest）：getSubscription() 恒 null，真身在 impl 的 rebalance 容器
        SubscriptionData sub = built.getDefaultMQPushConsumerImpl()
                .getSubscriptionInner().get(RecordVerifyEvents.TOPIC);
        assertNotNull(sub);
        String expr = sub.getSubString();
        assertTrue(expr.contains(RecordVerifyEvents.TAG_VERIFIED), "订阅表达式缺 VERIFIED：" + expr);
        assertTrue(expr.contains(RecordVerifyEvents.TAG_REJECTED), "订阅表达式缺 REJECTED：" + expr);
    }

    /** 事件分发：VERIFIED → 落 RECORD_VERIFIED 通知（收件人 = VerifyEventDTO.userId，dedup_key=eventId） */
    @Test
    void handleMessage_verified_deliversRecordVerifiedNotification() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                message(RecordVerifyEvents.EVENT_VERIFIED));

        verify(notificationService).createNotification(
                100L, NotificationType.RECORD_VERIFIED, 7L, "你的运动记录已通过校验", null, "evt-x");
    }

    /** 事件分发：REJECTED → 落 RECORD_REJECTED 通知 */
    @Test
    void handleMessage_rejected_deliversRecordRejectedNotification() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                message(RecordVerifyEvents.EVENT_REJECTED));

        verify(notificationService).createNotification(
                100L, NotificationType.RECORD_REJECTED, 7L, "你的运动记录未通过校验", null, "evt-x");
    }

    /** 去重：SETNX 返回 false（同 eventId 已消费）→ 跳过，不落通知 */
    @Test
    void handleMessage_dedupKeyAlreadySet_skips() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                message(RecordVerifyEvents.EVENT_VERIFIED));

        verifyNoInteractions(notificationService);
    }

    /** 未知事件类型 → 跳过且不落通知 */
    @Test
    void handleMessage_unknownEventType_skips() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", message("UNKNOWN"));

        verifyNoInteractions(notificationService);
    }

    /** 无法解析的消息体 → 直接 ack 丢弃（不返回 RECONSUME_LATER、不落通知、不写去重键） */
    @Test
    void handleMessage_unparseableBody_acksAndSkips() {
        MessageExt bad = mock(MessageExt.class);
        when(bad.getBody()).thenReturn("not-json".getBytes(StandardCharsets.UTF_8));
        when(bad.getUserProperty(anyString())).thenReturn(null);

        // handleMessage 在解析失败路径 catch 后 return（不抛），且未触达 setnx/notification
        ReflectionTestUtils.invokeMethod(consumer, "handleMessage", bad);

        verifyNoInteractions(redissonClient);
        verifyNoInteractions(notificationService);
    }

    /** 业务落库失败 → 删除去重键放行重投并 rethrow（交监听器返回 RECONSUME_LATER） */
    @Test
    void handleMessage_businessWriteFails_deletesKeyAndRethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("落库失败")).when(notificationService)
                .createNotification(anyLong(), anyString(), anyLong(), anyString(), any(), anyString());

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                        message(RecordVerifyEvents.EVENT_VERIFIED)));
        verify(bucket).delete();
    }

    /** Redis 删除去重键异常 → 不阻断 rethrow */
    @Test
    void handleMessage_deleteDedupKeyRedisError_stillRethrows() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("落库失败")).when(notificationService)
                .createNotification(anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
        doThrow(new RuntimeException("Redis 不可用")).when(bucket).delete();

        assertThrows(RuntimeException.class,
                () -> ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                        message(RecordVerifyEvents.EVENT_VERIFIED)));
    }
}