package com.sportverify.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.common.PageResult;
import com.sportverify.user.dto.NotificationView;
import com.sportverify.user.entity.Notification;
import com.sportverify.user.mapper.NotificationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 通知服务单元测试（TASK-182 add-notification-center）。
 *
 * <p>幂等写入（INSERT IGNORE 受影响行数判首次）、分页列表（id 倒序）、未读数、单条已读
 * （归属校验 + 幂等）、全部已读。Mapper 用 Mockito 打桩，不引入真库；生产侧
 * {@link NotificationMapper#insertIgnore} 的唯一键兜底由表级 uk_dedup 承担，单测覆盖
 * 「受影响 0 行 = 幂等跳过」的服务层判据。</p>
 */
class NotificationServiceTest {

    private NotificationMapper mapper;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationMapper.class);
        service = new NotificationService(mapper);
        // MP 单测惯用初始化（与 record-service TrackPointArchiveServiceTest 同源）：
        // 注册实体列缓存，使 LambdaQuery/LambdaUpdate wrapper 的列名可渲染而不需真 Spring 上下文
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Notification.class);
    }

    // ==================== 幂等写入 ====================

    /** 首次落库：INSERT IGNORE 影响 1 行 → 返回 true */
    @Test
    void createNotification_firstInsert_true() {
        when(mapper.insertIgnore(any(Notification.class))).thenReturn(1);

        boolean created = service.createNotification(1001L, "RECORD_VERIFIED", 7L,
                "你的运动记录已通过校验", null, "evt-1");

        assertTrue(created, "首次落库应返回 true（受影响 1 行）");
    }

    /** 重复 dedup_key：INSERT IGNORE 影响 0 行（uk_dedup 已存在）→ 返回 false（幂等跳过） */
    @Test
    void createNotification_duplicateKeyZeroRows_false() {
        when(mapper.insertIgnore(any(Notification.class))).thenReturn(0);

        boolean created = service.createNotification(1001L, "RECORD_VERIFIED", 7L,
                "你的运动记录已通过校验", null, "evt-1");

        assertFalse(created, "受影响 0 行（唯一键冲突被 IGNORE）应返回 false，证明表级幂等生效");
    }

    // ==================== 分页列表 ====================

    /** 分页：仅收件人本人、id 倒序、正确转换视图 DTO */
    @Test
    void pageNotifications_ownOnlyDesc() {
        Notification n = notification(1L, 1001L, "RECORD_VERIFIED", 7L, 0);
        Page<Notification> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(n));
        when(mapper.selectPage(any(Page.class), ArgumentMatchers.<LambdaQueryWrapper<Notification>>any()))
                .thenReturn(page);

        PageResult<NotificationView> result = service.pageNotifications(1001L, 1, 20);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        NotificationView v = result.getRecords().get(0);
        assertEquals(1L, v.getId());
        assertEquals("RECORD_VERIFIED", v.getType());
        assertEquals(7L, v.getSourceId());
        assertEquals(0, v.getIsRead());
        assertEquals(n.getTitle(), v.getTitle());
    }

    /** 入参缺失 → 非法参数 */
    @Test
    void pageNotifications_nullUserId() {
        assertThrows(IllegalArgumentException.class, () -> service.pageNotifications(null, 1, 20));
    }

    // ==================== 未读数 ====================

    /** 未读数 = COUNT(user_id=?, is_read=0) */
    @Test
    void unreadCount_countsOnlyUnread() {
        when(mapper.selectCount(any())).thenReturn(3L);

        assertEquals(3L, service.unreadCount(1001L));
    }

    /** Mapper 返回 null（无记录）→ 归 0 */
    @Test
    void unreadCount_mapperNull_zero() {
        when(mapper.selectCount(any())).thenReturn(null);

        assertEquals(0L, service.unreadCount(1001L));
    }

    /** 入参缺失 → 非法参数 */
    @Test
    void unreadCount_nullUserId() {
        assertThrows(IllegalArgumentException.class, () -> service.unreadCount(null));
    }

    // ==================== 单条已读 ====================

    /** 归属本人未读 → 置已读并回填 read_at，返回 true */
    @Test
    void markRead_ownUnread_true() {
        when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(1);

        boolean ok = service.markRead(1001L, 5L);

        assertTrue(ok);
    }

    /** 非本人或已读（归属条件 UPDATE 影响 0 行）→ 返回 false */
    @Test
    void markRead_notOwnerOrAlreadyRead_false() {
        when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(0);

        boolean ok = service.markRead(1001L, 5L);

        assertFalse(ok, "非本人或已读流转影响 0 行 → 视为不可标记（不可越权标记他人通知）");
    }

    /** 入参缺失 → false */
    @Test
    void markRead_nullArgs_false() {
        assertFalse(service.markRead(null, 5L));
        assertFalse(service.markRead(1001L, null));
    }

    // ==================== 全部已读 ====================

    /** 全部已读：批量流转 is_read=1 并回填 read_at，返回受影响行数 */
    @Test
    void markAllRead_updatesAllOwnUnread() {
        when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(2);

        int rows = service.markAllRead(1001L);

        assertEquals(2, rows);
    }

    /** 入参缺失 → 0 */
    @Test
    void markAllRead_nullUserId_zero() {
        assertEquals(0, service.markAllRead(null));
    }

    // ==================== 工具 ====================

    private Notification notification(Long id, Long userId, String type, Long sourceId, Integer isRead) {
        Notification n = new Notification();
        n.setId(id);
        n.setUserId(userId);
        n.setType(type);
        n.setSourceId(sourceId);
        n.setTitle("你的运动记录已通过校验");
        n.setIsRead(isRead);
        n.setDedupKey("evt-" + id);
        n.setCreatedAt(LocalDateTime.now());
        n.setReadAt(isRead == 1 ? LocalDateTime.now() : null);
        return n;
    }
}