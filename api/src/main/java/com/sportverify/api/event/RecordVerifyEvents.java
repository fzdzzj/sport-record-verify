package com.sportverify.api.event;

/**
 * 校验事件 Topic / Tag 常量（RocketMQ，规范「校验事件与幂等」）。
 *
 * <ul>
 *   <li>Topic {@code record-verify-events}，Tag 区分事件类型（SUBMITTED / VERIFIED / REJECTED）；</li>
 *   <li>消费者按 eventId SETNX 去重保证幂等；</li>
 *   <li>消费失败超过重试阈值后进入死信队列 {@code record-verify-events-dlq} 供人工排查。</li>
 * </ul>
 */
public final class RecordVerifyEvents {

    /** 校验事件主题 */
    public static final String TOPIC = "record-verify-events";

    /** 提交事件 Tag：record 提交成功进入 VERIFYING 后发布 */
    public static final String TAG_SUBMITTED = "SUBMITTED";
    /** 通过事件 Tag：判定 PASSED / 终判 RE_PASSED 后发布 */
    public static final String TAG_VERIFIED = "VERIFIED";
    /** 拒绝事件 Tag：判定 REJECTED / 终判 RE_CONFIRMED 后发布 */
    public static final String TAG_REJECTED = "REJECTED";

    /** 死信队列主题：消费重试超阈值后投递，供人工排查 */
    public static final String DLQ_TOPIC = "record-verify-events-dlq";

    /** 事件类型（与 Tag 同名，写入事件体便于审计） */
    public static final String EVENT_SUBMITTED = "SUBMITTED";
    public static final String EVENT_VERIFIED = "VERIFIED";
    public static final String EVENT_REJECTED = "REJECTED";

    private RecordVerifyEvents() {
    }
}
