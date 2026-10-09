package com.sportverify.user.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sportverify.user.entity.Notification;
import com.sportverify.user.mapper.NotificationMapper;
import com.sportverify.user.ws.NotificationPushMessage;
import com.sportverify.user.ws.NotificationPushRelay;
import com.sportverify.user.ws.NotificationReadReceipt;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通知推送与已读回执发布钩子单元测试（TASK-185 / TASK-186）。
 *
 * <p>覆盖：落库成功（受影响 1 行）→ 发布钩子只发一次且载荷字段正确；落库失败（受影响
 * 0 行，幂等跳过）→ 不发布；relay.publish 抛异常 → 不影响返回 true（尽力而为，前端轮询兜底）。
 * 单条已读成功 → 恰一条 kind=single 回执（含 notificationId 与 readAt）；零行 → 零回执；
 * 全部已读成功（N>0）→ 恰一条 kind=all 回执（防风暴，载荷级 O(1)）；零行 → 零回执；
 * publishRead 抛异常 → 不影响 markRead / markAllRead 业务返回值（尽力而为）。</p>
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

        // MP 单测初始化：注册实体列缓存，使 LambdaUpdateWrapper 可在离线单元测试中解析字段名
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Notification.class);
    }

    // ==================== 首插落库推送钩子（TASK-185） ====================

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

    // ==================== 单条标记已读回执钩子（TASK-186） ====================

    /** 单条已读成功（受影响 1 行）→ 发布恰一条 kind=single 回执（含 id 与 readAt） */
    @Test
    void markRead_whenSuccess_publishesSingleReceiptWithIdAndReadAt() {
        when(mapper.update(isNull(), any())).thenReturn(1);

        boolean ok = service.markRead(1001L, 42L);

        assertTrue(ok, "标记成功应返回 true");
        ArgumentCaptor<NotificationReadReceipt> captured = ArgumentCaptor.forClass(NotificationReadReceipt.class);
        verify(relay).publishRead(captured.capture());
        NotificationReadReceipt receipt = captured.getValue();
        assertEquals(1001L, receipt.userId(), "回执 userId 应正确");
        assertEquals(NotificationReadReceipt.KIND_SINGLE, receipt.kind(), "回执类型应为 single");
        assertEquals(42L, receipt.notificationId(), "回执 notificationId 应正确");
        assertNotNull(receipt.readAt(), "回执 readAt 不应为空");
    }

    /** 单条已读影响 0 行（非本人/不存在/已是已读态）→ 零回执 */
    @Test
    void markRead_whenZeroRows_doesNotPublishReceipt() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        boolean ok = service.markRead(1001L, 42L);

        assertFalse(ok, "标记失败应返回 false");
        verify(relay, never()).publishRead(any());
    }

    /** publishRead 抛异常 → 不影响 markRead 返回 true（尽力而为，轮询兜底） */
    @Test
    void markRead_whenPublishReadThrows_stillReturnsTrue() {
        when(mapper.update(isNull(), any())).thenReturn(1);
        doThrow(new IllegalStateException("redis down")).when(relay).publishRead(any(NotificationReadReceipt.class));

        boolean ok = service.markRead(1001L, 42L);

        assertTrue(ok, "回执发布失败不应影响标记已读的业务返回值");
    }

    // ==================== 全部标记已读回执钩子（TASK-186） ====================

    /** 全部已读成功（影响行数 N>0）→ 发布恰一条 kind=all 回执（防风暴，载荷级 O(1)） */
    @Test
    void markAllRead_whenPositiveRows_publishesAllReceipt() {
        when(mapper.update(isNull(), any())).thenReturn(5);

        int rows = service.markAllRead(1001L);

        assertEquals(5, rows, "应返回实际流转行数");
        ArgumentCaptor<NotificationReadReceipt> captured = ArgumentCaptor.forClass(NotificationReadReceipt.class);
        verify(relay).publishRead(captured.capture());
        NotificationReadReceipt receipt = captured.getValue();
        assertEquals(1001L, receipt.userId(), "回执 userId 应正确");
        assertEquals(NotificationReadReceipt.KIND_ALL, receipt.kind(), "回执类型应为 all");
        assertNull(receipt.notificationId(), "all 类型回执 notificationId 应为 null");
        assertNotNull(receipt.readAt(), "回执 readAt 不应为空");
    }

    /** 全部已读影响 0 行（无未读通知）→ 零回执 */
    @Test
    void markAllRead_whenZeroRows_doesNotPublishReceipt() {
        when(mapper.update(isNull(), any())).thenReturn(0);

        int rows = service.markAllRead(1001L);

        assertEquals(0, rows, "应返回 0 行");
        verify(relay, never()).publishRead(any());
    }

    /** publishRead 抛异常 → 不影响 markAllRead 返回实际受影响行数（尽力而为） */
    @Test
    void markAllRead_whenPublishReadThrows_stillReturnsRowCount() {
        when(mapper.update(isNull(), any())).thenReturn(3);
        doThrow(new IllegalStateException("redis down")).when(relay).publishRead(any(NotificationReadReceipt.class));

        int rows = service.markAllRead(1001L);

        assertEquals(3, rows, "回执发布失败不应影响标记全部已读返回的受影响行数");
    }
}
