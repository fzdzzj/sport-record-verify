package com.sportverify.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知偏好实体（user_db.notification_preference，TASK-187 add-notification-preference）。
 *
 * <p>复合主键 (user_id, type)，MyBatis-Plus 复合主键不适用单一 @TableId 注解。
 * 缺行表示默认开启（读取侧补默认全 true，无预填充）。</p>
 */
@Data
@TableName("notification_preference")
public class NotificationPreference {

    /** 用户ID */
    private Long userId;

    /** 通知类型（NotificationType 三类：RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED） */
    private String type;

    /** 是否接收：1 开启，0 关闭 */
    private Integer enabled;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
