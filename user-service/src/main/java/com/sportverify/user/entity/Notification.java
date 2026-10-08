package com.sportverify.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知实体（user_db.notification，TASK-182 add-notification-center）。
 *
 * <p>两类写路径：判定事件经 MQ 异步消费落库（type=RECORD_VERIFIED / RECORD_REJECTED，
 * dedup_key=MQ eventId）；好友申请通过在 FriendService.accept() 本地事务内直写
 * （type=FRIEND_ACCEPTED，dedup_key=FRIEND_ACCEPTED:{requestId}）。</p>
 *
 * <p>幂等由 {@code uk_dedup(dedup_key)} 唯一键保证：同一 dedup_key 重复写入至多一条
 * （隔离于单测可见的 insertIgnore 受影响行数）；读路径按收件人 {@code user_id} 隔离，
 * {@code idx_user_read(user_id, is_read, id)} 覆盖分页与未读数查询。</p>
 */
@Data
@TableName("notification")
public class Notification {

    /** 通知ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收件人 */
    private Long userId;

    /** 类型（NotificationType）：RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED */
    private String type;

    /** 聚合根ID（recordId / requestId） */
    private Long sourceId;

    /** 标题 */
    private String title;

    /** 内容（可空） */
    private String content;

    /** 已读：0 未读，1 已读 */
    private Integer isRead;

    /** 幂等键（表级唯一 uk_dedup） */
    private String dedupKey;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 已读时间（可空） */
    private LocalDateTime readAt;
}