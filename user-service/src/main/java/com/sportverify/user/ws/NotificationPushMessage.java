package com.sportverify.user.ws;

import java.time.LocalDateTime;

/**
 * 通知推送消息载荷（通知实时推送 WS+STOMP，TASK-185 add-notification-ws-push）。
 *
 * <p>统一承载「通知实时推送」在两条链路上的数据形态：Redis pub/sub 扇出通道（
 * {@code notification:push} topic）的 JSON 载荷与 STOMP 用户目标队列投递的对象。
 * 字段从落库后的 {@code Notification} 汇聚而来（不含 id/isRead/dedupKey/readAt 等
 * 非推送相关字段），userId 为收件人、type 沿用 {@code NotificationType} 枚举名。
 * 经 {@code NotificationPushRelay} 以 String 载荷发布/接收，JSON 序列化用注入的
 * {@code ObjectMapper}。</p>
 *
 * @param userId     收件人 userId（STOMP 用户目标投递键）
 * @param type       通知类型（RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED）
 * @param sourceId   聚合根 ID（recordId / requestId）
 * @param content    通知内容（可空）
 * @param createdAt  通知创建时间
 */
public record NotificationPushMessage(
        Long userId,
        String type,
        Long sourceId,
        String content,
        LocalDateTime createdAt) {
}
