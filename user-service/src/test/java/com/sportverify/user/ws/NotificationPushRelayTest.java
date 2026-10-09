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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通知推送跨实例扇出中继单元测试（TASK-185 add-notification-ws-push）。
 *
 * <p>覆盖：扇出回调 → 经用户目标队列投递给正确用户与目标、JSON 往返字段全等；无会话用户
 * 投递不抛（Spring 语义由 mock 不抛体现，真实无会话静默跳过）；发布侧序列化并 publish 到
 * topic、publish 失败仅告警不抛出（尽力而为）。</p>
 */
class NotificationPushRelayTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 9, 12, 0, 0);

    private RedissonClient redissonClient;
    private RTopic topic;
    private SimpMessagingTemplate messagingTemplate;
    private ObjectMapper objectMapper;
    private NotificationPushRelay relay;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        topic = mock(RTopic.class);
        when(redissonClient.getTopic(anyString(), any())).thenReturn(topic);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        // 生产 ObjectMapper 经 Spring Boot 自动注册 JavaTimeModule（LocalDateTime 可序列化）；
        // 单测离线下手动注册，保证 JSON 往返与生产口径一致
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        relay = new NotificationPushRelay(redissonClient, messagingTemplate, objectMapper);
    }

    /** 订阅注册：topic 名与 String 载荷类型传出 */
    @Test
    void subscribe_registersStringListener() {
        relay.subscribe();

        verify(redissonClient).getTopic(eq("notification:push"), any());
        verify(topic).addListener(eq(String.class), any(MessageListener.class));
    }

    /** 扇出回调 → 反序列化 → 经用户目标投递给正确用户与目标，JSON 往返字段全等 */
    @Test
    void handle_incomingPush_deliversToCorrectUserWithFullRoundTrip() throws Exception {
        relay.subscribe();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<MessageListener<String>> listenerCaptor =
                ArgumentCaptor.forClass(MessageListener.class);
        verify(topic).addListener(eq(String.class), listenerCaptor.capture());
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
        verify(topic).addListener(eq(String.class), listenerCaptor.capture());
        String json = objectMapper.writeValueAsString(
                new NotificationPushMessage(9999L, "FRIEND_ACCEPTED", 3L, null, CREATED));

        assertDoesNotThrow(() -> listenerCaptor.getValue().onMessage("notification:push", json),
                "投递无会话用户不应抛异常（Spring 用户目标注册表静默跳过）");
    }

    /** 发布侧：序列化 JSON 并 publish 到扇出 topic */
    @Test
    void publish_serializesAndPublishes() throws Exception {
        relay.subscribe();
        NotificationPushMessage message =
                new NotificationPushMessage(2002L, "RECORD_REJECTED", 9L, "未通过", CREATED);

        relay.publish(message);

        ArgumentCaptor<String> published = ArgumentCaptor.forClass(String.class);
        verify(topic).publish(published.capture());
        NotificationPushMessage roundTrip = objectMapper.readValue(published.getValue(),
                NotificationPushMessage.class);
        assertEquals(message, roundTrip, "发布到 topic 的 JSON 反序列化后应等于原载荷");
    }

    /** 发布失败仅告警不抛出（尽力而为，不影响落库结果） */
    @Test
    void publish_whenTopicFails_doesNotThrow() {
        relay.subscribe();
        when(topic.publish(anyString())).thenThrow(new IllegalStateException("redis down"));
        NotificationPushMessage message = new NotificationPushMessage(2002L, "RECORD_REJECTED", 9L, null, CREATED);

        assertDoesNotThrow(() -> relay.publish(message),
                "topic.publish 失败只应告警，不应向外抛出");
    }

    /** 未注入/未注册订阅时 publish 不触碰投递（relay 未 subscribe，topic 为空 → 无会话可投递） */
    @Test
    void publish_withoutSubscription_warnsNotThrows() {
        NotificationPushMessage message = new NotificationPushMessage(2002L, "RECORD_REJECTED", 9L, null, CREATED);

        assertDoesNotThrow(() -> relay.publish(message), "未订阅时发布应静默跳过不抛");
        verifyNoInteractions(messagingTemplate);
    }
}
