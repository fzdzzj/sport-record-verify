package com.sportverify.api.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 校验事件体（record ↔ verify 经 RocketMQ 传递）。
 *
 * <p>{@code eventId} 全局唯一（UUID），消费者按 eventId SETNX 去重，
 * 保证消息重复投递时业务只执行一次（规范「事件幂等消费」）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyEventDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一ID（幂等键） */
    private String eventId;

    /** 运动记录ID */
    private Long recordId;

    /** 所属用户（冗余分片键） */
    private Long userId;

    /** 事件类型：SUBMITTED / VERIFIED / REJECTED（与 Tag 一致） */
    private String eventType;

    /** 事件发生时间 */
    private LocalDateTime occurredAt;
}
