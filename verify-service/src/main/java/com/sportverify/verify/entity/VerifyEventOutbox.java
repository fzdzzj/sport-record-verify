package com.sportverify.verify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 校验事件本地消息表实体（verify_db.verify_event_outbox，F03 outbox）。
 *
 * <p>判定/终判事件不再直连 MQ，而是与 verification_result upsert 同事务落本表
 * （PENDING 行），由 relay 定时扫描投递 RocketMQ：判定成功即事件不丢，
 * MQ 短暂故障由 relay 重试补偿，超阈值保留行供人工处理。</p>
 */
@Data
@TableName("verify_event_outbox")
public class VerifyEventOutbox {

    /** 自增ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 事件ID（唯一；即消费端 eventId SETNX 去重锚点） */
    private String eventId;

    /** 目标 Topic（record-verify-events） */
    private String topic;

    /** 事件 Tag（VERIFIED / REJECTED） */
    private String tag;

    /** 事件体 JSON（VerifyEventDTO） */
    private String payload;

    /** 链路追踪ID（写入时取 MDC 当前值，relay 投递时透传到消息 userProperty） */
    private String traceId;

    /** 状态：PENDING 待投递，SENT 已投递 */
    private String status;

    /** 投递失败次数 */
    private Integer retryCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 投递成功时间 */
    private LocalDateTime sentAt;
}
