package com.sportverify.user.ws;

import java.time.LocalDateTime;

/**
 * 通知已读回执事件载荷（通知已读回执·跨端已读同步，TASK-186 add-notification-read-receipt）。
 *
 * <p>统一承载「通知已读回执」在两条链路上的数据形态：Redis pub/sub 扇出通道（
 * {@value NotificationPushRelay#READ_TOPIC} topic）的 JSON 载荷与 STOMP 用户目标队列（
 * {@value NotificationPushRelay#READ_QUEUE}）投递的对象。</p>
 *
 * <p>本类为瞬时事件（transient event），不持久化到数据库，收件人全部在线会话接收后用于
 * 刷新铃铛未读数与重载通知列表。REST 接口仍是已读流转的唯一权威通道。</p>
 *
 * @param userId         收件人 userId（STOMP 用户目标投递键）
 * @param kind           回执种类：{@value #KIND_SINGLE}（单条已读）或 {@value #KIND_ALL}（全部已读）
 * @param notificationId 通知 ID（单条已读时为通知 ID，全部已读时为 null）
 * @param readAt         标记已读时间戳
 */
public record NotificationReadReceipt(
        Long userId,
        String kind,
        Long notificationId,
        LocalDateTime readAt) {

    /** 单条已读回执类型 */
    public static final String KIND_SINGLE = "single";

    /** 全部已读回执类型（回执风暴防护，载荷级 O(1)） */
    public static final String KIND_ALL = "all";

    /**
     * 创建单条已读回执。
     *
     * @param userId         收件人 userId
     * @param notificationId 被标记已读的通知 ID
     * @param readAt         标记已读时间
     * @return 单条已读回执
     */
    public static NotificationReadReceipt single(Long userId, Long notificationId, LocalDateTime readAt) {
        return new NotificationReadReceipt(userId, KIND_SINGLE, notificationId, readAt);
    }

    /**
     * 创建全部已读回执。
     *
     * @param userId 收件人 userId
     * @param readAt 标记已读时间
     * @return 全部已读回执
     */
    public static NotificationReadReceipt all(Long userId, LocalDateTime readAt) {
        return new NotificationReadReceipt(userId, KIND_ALL, null, readAt);
    }
}
