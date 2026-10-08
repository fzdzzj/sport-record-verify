package com.sportverify.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.user.entity.Notification;
import org.apache.ibatis.annotations.Insert;

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
}