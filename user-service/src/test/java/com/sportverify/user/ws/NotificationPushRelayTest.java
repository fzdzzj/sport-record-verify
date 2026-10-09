package com.sportverify.user.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通知推送与已读回执跨实例扇出中继单元测试（TASK-185 / TASK-186）。
 *
 * <p>覆盖：扇出回调 → 经用户目标队列投递给正确用户与目标、JSON 往返字段全等；
 * 非法载荷丢弃告警不抛；无会话用户投递不抛；发布侧序列化并 publish 到对应 topic、
 * publish 失败仅告警不抛出（尽力而为）；READ_TOPIC / READ_QUEUE 常量断言；
 * 生命周期双订/双退对称。</p>
 */
class NotificationPushRelayTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 9, 12, 0, 0);

    private RedissonClient redissonClient;
    private RTopic pushTopic;
    private RTopic readTopic;
    private SimpMessagingTemplate messagingTemplate;
    private ObjectMapper objectMapper;
    private NotificationPushRelay relay;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        pushTopic = mock(RTopic.class);
        readTopic = mock(RTopic.class);
        when(redissonClient.getTopic(eq(NotificationPushRelay.PUSH_TOPIC), any())).thenReturn(pushTopic);
        when(redissonClient.getTopic(eq(NotificationPushRelay.READ_TOPIC), any())).thenReturn(readTopic);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        // 生产 ObjectMapper 经 Spring Boot 自动注册 JavaTimeModule（LocalDateTime 可序列化）；
        // 单测离线下手动注册，保证 JSON 往返与生产口径一致
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        relay = new NotificationPushRelay(redissonClient, messagingTemplate, objectMapper);
    }

    /** 常量定义断言：已读回执 topic 与目标队列名 */
    @Test
    void constants_assertReadTopicAndQueue() {
        assertEquals("notification:read", NotificationPushRelay.READ_TOPIC);
        assertEquals("/queue/notification-read", NotificationPushRelay.READ_QUEUE);
    }

    /** 订阅注册：推送 topic 名与 String 载荷类型传出 */
    @Test
    void subscribe_registersPushListener() {
        relay.subscribe();

        verify(redissonClient).getTopic(eq(NotificationPushRelay.PUSH_TOPIC), any());
        verify(pushTopic).addListener(eq(String.class), any(MessageListener.class));
    }

    /** 订阅注册：已读回执 topic 名与 String 载荷类型传出 */
    @Test
    void subscribe_registersReadListener() {
        relay.subscribe();

        verify(redissonClient).getTopic(eq(NotificationPushRelay.READ_TOPIC), any());
        verify(readTopic).addListener(eq(String.class), any(MessageListener.class));
    }

    /** 停机双退订对称：两个 topic 均正确调用 removeListener */
    @Test
    void destroy_unsubscribesBothTopics() {
        when(pushTopic.addListener(any(), any())).thenReturn(101);
        when(readTopic.addListener(any(), any())).thenReturn(102);

        relay.subscribe();
        relay.destroy();

        verify(pushTopic).removeListener(101);
        verify(readTopic).removeListener(102);
    }

    /** 扇出回调 → 反序列化 → 经用户目标投递给正确用户与目标，JSON 往返字段全等 */
    @Test
    void handle_incomingPush_deliversToCorrectUserWithFullRoundTrip() throws Exception {
        relay.subscribe();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageListener<String>> listenerCaptor =
                ArgumentCaptor.forClass(MessageListener.class);
        verify(pushTopic).addListener(eq(String.class), listenerCaptor.capture());
        MessageListener<String> listener = listenerCaptor.getValue();

        NotificationPushMessage original =
                new NotificationPushMessage(1001L, "RECORD_VERIFIED", 7L, "你的运动记录已通过校验", CREATED);
        String json = objectMapper.writeValueAsString(original);

        listener.onMessage("notification:push", json);

        ArgumentCaptor<NotificationPushMessage> payload = ArgumentCaptor.forClass(NotificationPushMessage.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("1001"), eq("/queue/notifications"), payload.capture());
        NotificationPushMessage got = payload.getValue();
        assertEquals(original, got, "JSON 往返后通知推送载荷字段应逐位全等");
    }

    /** 无会话用户投递不抛（Spring 语义：convertAndSendToUser 对无会话用户静默跳过） */
    @Test
    void handle_noSessionUser_doesNotThrow() throws Exception {
        relay.subscribe();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageListener<String>> listenerCaptor =
                ArgumentCaptor.forClass(MessageListener.class);
        verify(pushTopic).addListener(eq(String.class), listenerCaptor.capture());
        String json = objectMapper.writeValueAsString(
                new NotificationPushMessage(9999L, "FRIEND_ACCEPTED", 3L, null, CREATED));

        assertDoesNotThrow(() -> listenerCaptor.getValue().onMessage("notification:push", json),
                "投递无会话用户不应抛异常（Spring 用户目标注册表静默跳过）");
    }

    /** 已读回执扇出回调 → 反序列化 → 经已读队列投递给正确用户与目标，JSON 往返字段全等 */
    @Test
    void handleRead_incomingReceipt_deliversToCorrectUserWithFullRoundTrip() throws Exception {
        relay.subscribe();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageListener<String>> listenerCaptor =
                ArgumentCaptor.forClass(MessageListener.class);
        verify(readTopic).addListener(eq(String.class), listenerCaptor.capture());
        MessageListener<String> listener = listenerCaptor.getValue();

        NotificationReadReceipt original =
                NotificationReadReceipt.single(1001L, 42L, CREATED);
        String json = objectMapper.writeValueAsString(original);

        listener.onMessage("notification:read", json);

        ArgumentCaptor<NotificationReadReceipt> payload = ArgumentCaptor.forClass(NotificationReadReceipt.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("1001"), eq(NotificationPushRelay.READ_QUEUE), payload.capture());
        NotificationReadReceipt got = payload.getValue();
        assertEquals(original, got, "JSON 往返后通知已读回执载荷字段应逐位全等");
    }

    /** 已读回执非法载荷丢弃告警不抛出 */
    @Test
    void handleRead_invalidPayload_logsAndDiscardsWithoutThrowing() {
        relay.subscribe();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageListener<String>> listenerCaptor =
                ArgumentCaptor.forClass(MessageListener.class);
        verify(readTopic).addListener(eq(String.class), listenerCaptor.capture());
        MessageListener<String> listener = listenerCaptor.getValue();

        // 畸形 JSON 与缺失必要字段载荷均应被捕获丢弃
        assertDoesNotThrow(() -> listener.onMessage("notification:read", "{invalid-json"));
        assertDoesNotThrow(() -> listener.onMessage("notification:read", "{\"userId\":null}"));

        verifyNoInteractions(messagingTemplate);
    }

    /** 发布侧：序列化 JSON 并 publish 到推送 topic */
    @Test
    void publish_serializesAndPublishes() throws Exception {
        relay.subscribe();
        NotificationPushMessage message =
                new NotificationPushMessage(2002L, "RECORD_REJECTED", 9L, "未通过", CREATED);

        relay.publish(message);

        ArgumentCaptor<String> published = ArgumentCaptor.forClass(String.class);
        verify(pushTopic).publish(published.capture());
        NotificationPushMessage roundTrip = objectMapper.readValue(published.getValue(),
                NotificationPushMessage.class);
        assertEquals(message, roundTrip, "发布到 topic 的 JSON 反序列化后应等于原载荷");
    }

    /** 发布侧已读回执：序列化 JSON 并 publish 到已读 topic */
    @Test
    void publishRead_serializesAndPublishes() throws Exception {
        relay.subscribe();
        NotificationReadReceipt receipt =
                NotificationReadReceipt.all(2002L, CREATED);

        relay.publishRead(receipt);

        ArgumentCaptor<String> published = ArgumentCaptor.forClass(String.class);
        verify(readTopic).publish(published.capture());
        NotificationReadReceipt roundTrip = objectMapper.readValue(published.getValue(),
                NotificationReadReceipt.class);
        assertEquals(receipt, roundTrip, "发布到已读 topic 的 JSON 反序列化后应等于原回执");
    }

    /** 发布推送失败仅告警不抛出（尽力而为，不影响落库结果） */
    @Test
    void publish_whenTopicFails_doesNotThrow() {
        relay.subscribe();
        when(pushTopic.publish(anyString())).thenThrow(new IllegalStateException("redis down"));
        NotificationPushMessage message = new NotificationPushMessage(2002L, "RECORD_REJECTED", 9L, null, CREATED);

        assertDoesNotThrow(() -> relay.publish(message),
                "pushTopic.publish 失败只应告警，不应向外抛出");
    }

    /** 发布已读回执失败仅告警不抛出（尽力而为，不影响 REST 业务返回） */
    @Test
    void publishRead_whenTopicFails_doesNotThrow() {
        relay.subscribe();
        when(readTopic.publish(anyString())).thenThrow(new IllegalStateException("redis down"));
        NotificationReadReceipt receipt = NotificationReadReceipt.single(2002L, 42L, CREATED);

        assertDoesNotThrow(() -> relay.publishRead(receipt),
                "readTopic.publish 失败只应告警，不应向外抛出");
    }

    /** 未注入/未注册订阅时 publish 不触碰投递（relay 未 subscribe，topic 为空 → 静默跳过不抛） */
    @Test
    void publish_withoutSubscription_warnsNotThrows() {
        NotificationPushMessage message = new NotificationPushMessage(2002L, "RECORD_REJECTED", 9L, null, CREATED);

        assertDoesNotThrow(() -> relay.publish(message), "未订阅时发布推送应静默跳过不抛");
        verifyNoInteractions(messagingTemplate);
    }

    /** 未注入/未注册订阅时 publishRead 不触碰投递（relay 未 subscribe，readTopic 为空 → 静默跳过不抛） */
    @Test
    void publishRead_withoutSubscription_warnsNotThrows() {
        NotificationReadReceipt receipt = NotificationReadReceipt.single(2002L, 42L, CREATED);

        assertDoesNotThrow(() -> relay.publishRead(receipt), "未订阅时发布已读回执应静默跳过不抛");
        verifyNoInteractions(messagingTemplate);
    }
}
