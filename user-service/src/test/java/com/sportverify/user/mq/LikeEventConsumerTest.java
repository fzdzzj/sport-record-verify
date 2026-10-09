package com.sportverify.user.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.api.event.LikeEventDTO;
import com.sportverify.api.event.RecordLikeEvents;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 点赞事件通知消费者单元测试（TASK-188 add-notification-like）。
 *
 * <p>消费范式沿 {@link NotificationEventConsumerTest}：buildConsumer 配置断言（maxReconsumeTimes=3、
 * 消费组、订阅 Topic 与 Tag）+ handleMessage 分发、SETNX 去重、解析失败 ack 跳过、未知类型跳过、
 * 失败交 MQ 原生重试。Mapper/Redisson mock，不依赖真实中间件。</p>
 */
class LikeEventConsumerTest {

    private NotificationService notificationService;
    private RedissonClient redissonClient;
    private RBucket<Object> bucket;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private LikeEventConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        notificationService = mock(NotificationService.class);
        redissonClient = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);
        consumer = new LikeEventConsumer(notificationService, redissonClient,
                mapper, mock(RocketMQProperties.class));
        ReflectionTestUtils.setField(consumer, "consumerGroup", "notification-like-consumer-group");
    }

    private MessageExt message(String tag) throws Exception {
        String body = mapper.writeValueAsString(
                new LikeEventDTO("evt-like-1", 7L, 200L, 300L, LocalDateTime.now()));
        MessageExt msg = mock(MessageExt.class);
        when(msg.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(msg.getMsgId()).thenReturn("msg-like-1");
        when(msg.getTags()).thenReturn(tag);
        when(msg.getUserProperty(anyString())).thenReturn(null);
        return msg;
    }

    /** 消费参数：buildConsumer 订阅参数四断言（消费组/重试上限3/Topic/Tag） */
    @Test
    void buildConsumer_configuresSubscriptionParametersFourAssertions() throws Exception {
        DefaultMQPushConsumer built = consumer.buildConsumer("127.0.0.1:9876");

        // 1) 独立消费组
        assertEquals("notification-like-consumer-group", built.getConsumerGroup());
        // 2) 重试上限走 RocketMQ 原生 maxReconsumeTimes=3
        assertEquals(3, built.getMaxReconsumeTimes());

        SubscriptionData sub = built.getDefaultMQPushConsumerImpl()
                .getSubscriptionInner().get(RecordLikeEvents.TOPIC);
        assertNotNull(sub);
        // 3) 订阅 Topic
        assertEquals(RecordLikeEvents.TOPIC, sub.getTopic());
        // 4) 订阅 Tag
        String expr = sub.getSubString();
        assertTrue(expr.contains(RecordLikeEvents.TAG_LIKED), "订阅表达式缺 LIKED：" + expr);
    }

    /** 事件处理：LIKED Tag 落通知，收件人=recordOwnerId，dedupKey=RECORD_LIKED:{recordId}:{likerId} 逐参校验 */
    @Test
    void handleMessage_liked_deliversRecordLikedNotification() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                message(RecordLikeEvents.TAG_LIKED));

        verify(notificationService).createNotification(
                300L,
                NotificationType.RECORD_LIKED,
                7L,
                "你的运动记录收到新的点赞",
                null,
                "RECORD_LIKED:7:200"
        );
    }

    /** 去重：SETNX 返回 false（同 eventId 已消费）→ 跳过，不落通知（零调用） */
    @Test
    void handleMessage_dedupKeyAlreadySet_skips() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        ReflectionTestUtils.invokeMethod(consumer, "handleMessage",
                message(RecordLikeEvents.TAG_LIKED));

        verifyNoInteractions(notificationService);
    }

    /** 未知事件类型：Tag 不匹配 → 跳过且不落通知 */
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
                        message(RecordLikeEvents.TAG_LIKED)));
        verify(bucket).delete();
    }
}
