package com.sportverify.user.dto;

import java.io.Serializable;
import java.util.List;

/**
 * 更新通知偏好请求 DTO（PUT /api/notifications/preferences，TASK-187 add-notification-preference）。
 *
 * <p>支持批量偏好列表，沿既有 record DTO 风格设计。</p>
 *
 * @param preferences 偏好项列表
 */
public record UpdateNotificationPreferenceRequest(
        List<PreferenceItem> preferences
) implements Serializable {

    /**
     * 单项偏好配置。
     *
     * @param type    通知类型（NotificationType 三类）
     * @param enabled 是否开启
     */
    public record PreferenceItem(
            String type,
            Boolean enabled
    ) implements Serializable {}
}
