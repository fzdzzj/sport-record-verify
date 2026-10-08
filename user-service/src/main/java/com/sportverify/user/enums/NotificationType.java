package com.sportverify.user.enums;

/**
 * 通知类型常量（user_db.notification.type，TASK-182 add-notification-center）。
 *
 * <p>与建表脚本 COMMENT 的三类枚举一一对应：判定结果通知（RECORD_VERIFIED / RECORD_REJECTED）
 * 由 {@code NotificationEventConsumer} 消费 MQ 事件落库；好友通过通知（FRIEND_ACCEPTED）
 * 由 {@code FriendService.accept()} 本地事务内直写。</p>
 */
public final class NotificationType {

    /** 记录校验通过 */
    public static final String RECORD_VERIFIED = "RECORD_VERIFIED";

    /** 记录校验驳回 */
    public static final String RECORD_REJECTED = "RECORD_REJECTED";

    /** 好友申请被通过 */
    public static final String FRIEND_ACCEPTED = "FRIEND_ACCEPTED";

    private NotificationType() {
    }
}