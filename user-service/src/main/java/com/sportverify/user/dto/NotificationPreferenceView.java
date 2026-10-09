package com.sportverify.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知偏好视图 DTO（user-service 读取与更新返回，TASK-187 add-notification-preference）。
 *
 * <p>贴合 NotificationView 风格，下发用户每种通知类型的接收开关状态。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferenceView implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 通知类型：RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED */
    private String type;

    /** 是否接收：true 开启，false 关闭 */
    private Boolean enabled;

    /** 更新时间（可空，缺行默认开启时为 null） */
    private LocalDateTime updatedAt;
}
