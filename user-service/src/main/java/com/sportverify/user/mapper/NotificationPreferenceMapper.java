package com.sportverify.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.user.entity.NotificationPreference;
import org.apache.ibatis.annotations.Insert;

/**
 * 通知偏好 Mapper（user_db.notification_preference，TASK-187 add-notification-preference）。
 *
 * <p>复合主键 (user_id, type)，upsert 采用 ON DUPLICATE KEY UPDATE 实现行级原子写入。</p>
 */
public interface NotificationPreferenceMapper extends BaseMapper<NotificationPreference> {

    /**
     * 行级原子 upsert：主键 (user_id, type) 冲突时更新 enabled 与 updated_at。
     *
     * @param preference 偏好实体
     * @return 影响行数（1=插入新行，2=更新既有行，0=重复同值且无变化）
     */
    @Insert("INSERT INTO notification_preference (user_id, type, enabled, updated_at) " +
            "VALUES (#{userId}, #{type}, #{enabled}, #{updatedAt}) " +
            "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), updated_at = VALUES(updated_at)")
    int upsert(NotificationPreference preference);
}
