package com.sportverify.user.service;

import com.sportverify.user.entity.Notification;
import com.sportverify.user.mapper.NotificationMapper;
import com.sportverify.user.ws.NotificationPushMessage;
import com.sportverify.user.ws.NotificationPushRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通知首插落库推送发布钩子单元测试（TASK-185 add-notification-ws-push）。
 *
 * <p>覆盖：落库成功（受影响 1 行）→ 发布钩子只发一次且载荷字段正确；落库失败（受影响
 * 0 行，幂等跳过）→ 不发布；relay.publish 抛异常 → 不影响返回 true（尽力而为，前端轮询兜底）。</p>
 */
class NotificationServicePushTest {

    private NotificationMapper mapper;
    private NotificationPushRelay relay;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationMapper.class);
        relay = mock(NotificationPushRelay.class);
        service = new NotificationService(mapper);
        service.notificationPushRelay = relay;
    }

    /** 落库成功 → publish 一次且载荷字段全部正确 */
    @Test
    void createNotification_firstInsert_publishesOnceWithCorrectPayload() {
        when(mapper.insertIgnore(any(Notification.class))).thenReturn(1);

        boolean created = service.createNotification(1001L, "RECORD_VERIFIED", 7L,
                "你的运动记录已通过校验", "细粒度内容", "evt-1");

        assertTrue(created, "首插应返回 true");
        ArgumentCaptor<NotificationPushMessage> captured = ArgumentCaptor.forClass(NotificationPushMessage.class);
        verify(relay).publish(captured.capture());
        NotificationPushMessage msg = captured.getValue();
        assertEquals(1001L, msg.userId(), "推送载荷收件人应等于 userId");
        assertEquals("RECORD_VERIFIED", msg.type(), "推送载荷类型应等于通知类型");
        assertEquals(7L, msg.sourceId(), "推送载荷 sourceId 应正确");
        assertEquals("细粒度内容", msg.content(), "推送载荷 content 应正确");
        assertEquals(LocalDateTime.class, msg.createdAt().getClass(), "createdAt 应为时间载体");
    }

    /** 落库失败（受影响 0 行，uk_dedup 幂等跳过）→ 不发布任何推送 */
    @Test
    void createNotification_zeroRows_doesNotPublish() {
        when(mapper.insertIgnore(any(Notification.class))).thenReturn(0);

        service.createNotification(1001L, "RECORD_VERIFIED", 7L,
                "你的运动记录已通过校验", null, "evt-dup");

        verifyNoInteractions(relay);
    }

    /** relay.publish 抛异常 → 不影响 createNotification 返回 true（尽力而为，轮询兜底） */
    @Test
    void createNotification_whenPublishThrows_stillReturnsTrue() {
        when(mapper.insertIgnore(any(Notification.class))).thenReturn(1);
        doThrow(new IllegalStateException("redis down")).when(relay).publish(any(NotificationPushMessage.class));

        boolean created = service.createNotification(1001L, "RECORD_VERIFIED", 7L,
                "你的运动记录已通过校验", null, "evt-1");

        assertTrue(created, "发布失败不应影响落库结果的返回（尽力而为）");
    }
}
