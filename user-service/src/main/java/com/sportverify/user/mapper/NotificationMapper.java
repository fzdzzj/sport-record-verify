package com.sportverify.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.user.entity.Notification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 通知 Mapper（user_db.notification，TASK-182 add-notification-center）。
 *
 * <p>表级幂等由 {@code uk_dedup(dedup_key)} 唯一键兜底：{@link #insertIgnore} 用
 * {@code INSERT IGNORE}（非 MyBatis-Plus 默认 insert——后者唯一键冲突抛
 * DuplicateKeyException），受影响行数 0 = 同 dedup_key 已存在，记为幂等跳过，不产生第二行。</p>
 */
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 幂等写入：INSERT IGNORE，唯一键（uk_dedup）冲突时不报错、影响 0 行。
     *
     * @return 受影响行数；1 = 首次落库，0 = 同 dedup_key 已存在（幂等跳过）
     */
    @Insert("INSERT IGNORE INTO notification " +
            "(user_id, type, source_id, title, content, is_read, dedup_key, created_at, read_at) " +
            "VALUES (#{userId}, #{type}, #{sourceId}, #{title}, #{content}, #{isRead}, " +
            "#{dedupKey}, #{createdAt}, #{readAt})")
    int insertIgnore(Notification notification);

    /**
     * B21 过期已读通知物理分批清理：仅删除已读（is_read=1）且创建时间早于 {@code before} 的行，
     * 逐批上限 {@code limit} 限流单次删除规模（未读/alarm 与近七天在途的分页数据永不触碰）。
     *
     * <p>走 idx_read_created (is_read, created_at) 复合索引（见
     * sql/migrations/add-idx-notification-read-created.sql），消除历史已读扫描慢查。</p>
     *
     * @param before 清理截止时间（仅删除 created_at &lt; before 的已读行）
     * @param limit  单批删除行数上限
     * @return 实际删除行数
     */
    @Delete("DELETE FROM notification WHERE is_read = 1 AND created_at < #{before} LIMIT #{limit}")
    int cleanExpiredReadNotifications(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /**
     * B21 清空某用户全部已读通知（用户端「一键清空已读」）：仅删除收件人本人且已读的行。
     *
     * @param userId 收件人 userId（X-User-Id 认定）
     * @return 实际删除行数
     */
    @Delete("DELETE FROM notification WHERE user_id = #{userId} AND is_read = 1")
    int cleanUserReadNotifications(@Param("userId") Long userId);
}