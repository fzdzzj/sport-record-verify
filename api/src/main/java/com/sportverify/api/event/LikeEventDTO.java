package com.sportverify.api.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 点赞事件体（record → user 经 RocketMQ 传递，TASK-188）。
 *
 * <p>{@code eventId} 全局唯一（UUID），消费者按 eventId SETNX 去重，
 * 保证消息重复投递时业务只执行一次（规范「事件幂等消费」）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeEventDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一ID（幂等键） */
    private String eventId;

    /** 运动记录ID */
    private Long recordId;

    /** 点赞者用户ID */
    private Long likerId;

    /** 记录所有者用户ID（通知接收者，冗余避免回查） */
    private Long recordOwnerId;

    /** 事件发生时间 */
    private LocalDateTime occurredAt;
}
