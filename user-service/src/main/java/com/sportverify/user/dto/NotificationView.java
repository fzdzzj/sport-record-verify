package com.sportverify.user.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知视图 DTO（user-service 读取入口下发，TASK-182 add-notification-center）。
 *
 * <p>承载分页列表的单条通知（与 user_db.notification 列一一对应，is_read 码值下发）：
 * 供 {@code NotificationController} 的读取接口返回；可见性仅限收件人本人。api 模块按任务书
 * 红线零触碰，故本 DTO 落在 user-service 内部，不进入 api 契约。</p>
 */
@Data
public class NotificationView implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 通知ID */
    private Long id;

    /** 类型：RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED */
    private String type;

    /** 聚合根ID（recordId / requestId） */
    private Long sourceId;

    /** 标题 */
    private String title;

    /** 内容（可空） */
    private String content;

    /** 已读：0 未读，1 已读 */
    private Integer isRead;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 已读时间（可空） */
    private LocalDateTime readAt;
}