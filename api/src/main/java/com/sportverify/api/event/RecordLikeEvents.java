package com.sportverify.api.event;

/**
 * 点赞事件常量（RocketMQ，TASK-188）。
 *
 * <ul>
 *   <li>Topic {@code record-like-events}，Tag {@code LIKED}；</li>
 *   <li>消费者按 eventId SETNX 去重保证幂等；</li>
 *   <li>原生重试超限后进入 Broker 自动死信队列 {@code %DLQ%notification-like-consumer-group}。</li>
 * </ul>
 */
public final class RecordLikeEvents {

    /** 点赞事件主题 */
    public static final String TOPIC = "record-like-events";

    /** 点赞事件 Tag */
    public static final String TAG_LIKED = "LIKED";

    /** 点赞事件类型（与 Tag 一致） */
    public static final String EVENT_LIKED = "LIKED";

    private RecordLikeEvents() {
    }
}
