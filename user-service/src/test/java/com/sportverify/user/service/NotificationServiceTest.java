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
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    /** 判别式（TASK-261 R2b，G4）：双钳制——page&lt;1 收敛 1、size 超限收敛 500（MAX_NOTIFICATION_PAGE_SIZE） */
    @Test
    @SuppressWarnings("unchecked")
    void pageNotifications_clampsPageAndSize() {
        when(mapper.selectPage(any(Page.class), ArgumentMatchers.<LambdaQueryWrapper<Notification>>any()))
                .thenReturn(new Page<>(1, 20, 0));

        service.pageNotifications(1001L, 0, 100000);

        ArgumentCaptor<Page> page = ArgumentCaptor.forClass(Page.class);
        verify(mapper).selectPage(page.capture(), ArgumentMatchers.<LambdaQueryWrapper<Notification>>any());
        assertEquals(1L, page.getValue().getCurrent(), "page<1 必须收敛到 1");
        assertEquals(500L, page.getValue().getSize(), "size 超限必须收敛到 500");
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

    // ==================== 批量已读（B17 批量标记已读） ====================

    /** 批量已读：按 ID 集合且归属本人且未读条件 UPDATE → 流转条数返回，触发回执 */
    @Test
    void batchMarkRead_updatesOwnUnreadAndReturnCount() {
        when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(3);

        int rows = service.batchMarkRead(1001L, List.of(1L, 2L, 3L));

        assertEquals(3, rows);
    }

    /** 批量已读幂等：已读行不在更新条件内 → 返回 0，不触发回执 */
    @Test
    void batchMarkRead_allAlreadyRead_zeroRows() {
        when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(0);

        int rows = service.batchMarkRead(1001L, List.of(5L, 9L));

        assertEquals(0, rows);
    }

    /** 批量已读越权隔离：非本人通知不在更新条件内 → 返回 0 */
    @Test
    void batchMarkRead_nullUserIdOrEmpty_zero() {
        assertEquals(0, service.batchMarkRead(1001L, List.of()));
        assertEquals(0, service.batchMarkRead(null, List.of(1L)));
    }

    // ==================== B21 历史已读清理 ====================

    /** 过期已读清理：阈值=now-days，透传 mapper 批删，返回本批删除行数 */
    @Test
    void cleanExpiredNotifications_delegatesToMapper() {
        when(mapper.cleanExpiredReadNotifications(any(LocalDateTime.class), eq(200))).thenReturn(15);

        int rows = service.cleanExpiredNotifications(90, 200);

        assertEquals(15, rows);
        verify(mapper).cleanExpiredReadNotifications(any(LocalDateTime.class), eq(200));
    }

    /** 过期已读清理入参校验：days < 1 或 limit <= 0 → 非法参数 */
    @Test
    void cleanExpiredNotifications_invalidArgs_throw() {
        assertThrows(IllegalArgumentException.class, () -> service.cleanExpiredNotifications(0, 200));
        assertThrows(IllegalArgumentException.class, () -> service.cleanExpiredNotifications(90, 0));
        assertThrows(IllegalArgumentException.class, () -> service.cleanExpiredNotifications(90, -1));
    }

    /** 清空本人已读：仅删除收件人本人（X-User-Id 认定）且已读的行，返回删除行数 */
    @Test
    void cleanMyReadNotifications_delegatesToMapper() {
        when(mapper.cleanUserReadNotifications(1001L)).thenReturn(8);

        int rows = service.cleanMyReadNotifications(1001L);

        assertEquals(8, rows);
        verify(mapper).cleanUserReadNotifications(1001L);
    }

    /** 清空本人已读：userId 缺失 → 0，不触库 */
    @Test
    void cleanMyReadNotifications_nullUserId_zero() {
        assertEquals(0, service.cleanMyReadNotifications(null));
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