package com.sportverify.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.common.PageResult;
import com.sportverify.user.dto.NotificationView;
import com.sportverify.user.entity.Notification;
import com.sportverify.user.mapper.NotificationMapper;
import com.sportverify.user.ws.NotificationPushMessage;
import com.sportverify.user.ws.NotificationPushRelay;
import com.sportverify.user.ws.NotificationReadReceipt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知服务（user_db.notification，TASK-182 add-notification-center）。
 *
 * <p>服务层覆盖：幂等写入（INSERT IGNORE 以受影响行数判断首次）、分页列表（id 倒序）、
 * 未读数（COUNT + 索引）、单条已读（带 user_id 归属校验，非本人影响 0 行视为不存在）、
 * 全部已读（批量流转 is_read 并回填 read_at）。</p>
 *
 * <p>读路径可见性与已读操作权一律限定收件人本人（userId 由网关注入的 X-User-Id 认定，
 * 见 ADR-0007），不信任请求体显式携带值。</p>
 *
 * <p>实时推送（TASK-185 add-notification-ws-push）与已读回执（TASK-186 add-notification-read-receipt）：
 * {@link #createNotification} 首插落库成功后发布通知推送；{@link #markRead} / {@link #markAllRead}
 * 影响行数大于 0 时发布通知已读回执（尽力而为，零行不发，markAllRead 仅发单条 kind=all 载荷级防风暴）。
 * 推送侧可选注入 {@link NotificationPushRelay}——发布失败仅告警不影响业务返回值（尽力而为，前端 60s 轮询兜底）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;

    /** 通知推送中继（TASK-185，尽力而为）：可选注入——旧构造/旧单测不含该依赖时为空，落库成功分支跳过发布 */
    @Autowired(required = false)
    NotificationPushRelay notificationPushRelay;

    /**
     * 幂等写入通知：INSERT IGNORE，以受影响行数判断是否首次。
     *
     * <p>同一 {@code dedupKey}（判定事件=MQ eventId，好友=FRIEND_ACCEPTED:{requestId}）
     * 重复写入至多一条——唯一键冲突被吞掉并返回 false，不产生第二行。</p>
     *
     * <p>实时推送（TASK-185）：首插落库成功后触发通知推送发布（尽力而为，push 失败仅告警
     * 不回滚落库——推送是尽力而为，前端 60s 轮询兜底保证新鲜度不劣于纯轮询基线）。</p>
     *
     * @return true = 首次落库；false = 同 dedup_key 已存在（幂等跳过）
     */
    public boolean createNotification(Long userId, String type, Long sourceId,
                                      String title, String content, String dedupKey) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setSourceId(sourceId);
        n.setTitle(title);
        n.setContent(content);
        n.setIsRead(0);
        n.setDedupKey(dedupKey);
        n.setCreatedAt(LocalDateTime.now());
        int rows = notificationMapper.insertIgnore(n);
        if (rows > 0) {
            publishPush(new NotificationPushMessage(userId, type, sourceId, content, n.getCreatedAt()));
        }
        return rows > 0;
    }

    /** 发布通知推送（尽力而为）：relay 未注入或发布失败都不影响落库结果（轮询兜底语义） */
    private void publishPush(NotificationPushMessage message) {
        if (notificationPushRelay == null) {
            return;
        }
        try {
            notificationPushRelay.publish(message);
        } catch (Exception e) {
            log.warn("通知推送发布失败（尽力而为，轮询兜底）：userId={}", message.userId(), e);
        }
    }

    /**
     * 分页通知列表（仅收件人本人，id 倒序）。
     */
    public PageResult<NotificationView> pageNotifications(Long userId, long page, long size) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        Page<Notification> result = notificationMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .orderByDesc(Notification::getId));
        List<NotificationView> records = result.getRecords().stream().map(this::toView).toList();
        return new PageResult<>(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    /**
     * 未读数量：COUNT(user_id=?, is_read=0)，走 idx_user_read 复合索引，不引入 Redis 计数器。
     */
    public long unreadCount(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, 0));
        return count == null ? 0 : count;
    }

    /**
     * 单条标记已读：带 user_id 归属校验，非本人通知影响 0 行（视为不存在）。
     * 已读流转幂等：重复标记已读为无操作。
     *
     * <p>已读回执（TASK-186）：影响行数大于 0 时触发已读回执发布（single，尽力而为，
     * 零行不发，publish 失败仅告警不影响返回值，前端 60s 轮询兜底）。</p>
     *
     * @return true = 归属本人且标记成功；false = 通知不存在或非本人
     */
    public boolean markRead(Long userId, Long id) {
        if (userId == null || id == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = notificationMapper.update(null,
                new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getId, id)
                        .eq(Notification::getUserId, userId)
                        .eq(Notification::getIsRead, 0)
                        .set(Notification::getIsRead, 1)
                        .set(Notification::getReadAt, now));
        if (rows > 0) {
            publishReceipt(NotificationReadReceipt.single(userId, id, now));
        }
        return rows > 0;
    }

    /**
     * 全部标记已读：批量流转 is_read=1 并回填 read_at；重复全部已读为无操作。
     *
     * <p>已读回执（TASK-186）：受影响行数大于 0 时触发单条已读回执发布（all，尽力而为，
     * 零行不发，只发一条回执禁逐条发以防回执风暴，publish 失败仅告警不影响返回值）。</p>
     *
     * @return 受影响行数（本次实际从未读流转为已读的条数）
     */
    public int markAllRead(Long userId) {
        if (userId == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        int rows = notificationMapper.update(null,
                new LambdaUpdateWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .eq(Notification::getIsRead, 0)
                        .set(Notification::getIsRead, 1)
                        .set(Notification::getReadAt, now));
        if (rows > 0) {
            publishReceipt(NotificationReadReceipt.all(userId, now));
        }
        return rows;
    }

    /** 发布通知已读回执（尽力而为，TASK-186）：relay 未注入或发布失败都不影响已读流转结果（轮询兜底语义，零行不发） */
    private void publishReceipt(NotificationReadReceipt receipt) {
        if (notificationPushRelay == null || receipt == null) {
            return;
        }
        try {
            notificationPushRelay.publishRead(receipt);
        } catch (Exception e) {
            log.warn("通知已读回执发布失败（尽力而为，轮询兜底）：userId={}, kind={}", receipt.userId(), receipt.kind(), e);
        }
    }

    /** 实体 → 视图 DTO（is_read 码不变，读路径不做枚举名转换） */
    private NotificationView toView(Notification n) {
        NotificationView v = new NotificationView();
        v.setId(n.getId());
        v.setType(n.getType());
        v.setSourceId(n.getSourceId());
        v.setTitle(n.getTitle());
        v.setContent(n.getContent());
        v.setIsRead(n.getIsRead());
        v.setCreatedAt(n.getCreatedAt());
        v.setReadAt(n.getReadAt());
        return v;
    }
}
