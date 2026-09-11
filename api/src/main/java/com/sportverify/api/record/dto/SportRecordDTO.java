package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运动记录 DTO（record-api 契约）。
 *
 * <p>对应 record_db.sport_record，字段与审批版 §6.2 一致，
 * 供跨服务传递记录概要（如校验完成回调、榜单入榜事件）。</p>
 */
@Data
public class SportRecordDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录ID */
    private Long id;

    /** 客户端幂等键 */
    private String requestId;

    /** 所属用户 */
    private Long userId;

    /** 运动类型：1 RUNNING，2 CYCLING ... */
    private Integer sportType;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 距离（公里） */
    private BigDecimal distance;

    /** 时长（秒） */
    private Integer duration;

    /** 审核状态（状态机见 §5.1） */
    private Integer status;

    /** 乐观锁版本号 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
