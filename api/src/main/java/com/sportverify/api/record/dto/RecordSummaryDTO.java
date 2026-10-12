package com.sportverify.api.record.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 我的记录列表条目 DTO（record-api 契约，GET /record/api/records/my 响应元素）。
 *
 * <p>字段与 {@code sport_record} 摘要对齐（TASK-211 我的记录端点）：仅暴露
 * 记录概要——id、运动类型、距离、审核状态与创建时间，供前端统计面板与最近记录
 * 列表聚合。归属隔离由控制器按身份语义把 userId 解析为过滤条件实现，本 DTO 不含
 * userId（防止列表元素再次暴露归属人 ID 之外的不必要字段）。</p>
 */
@Data
public class RecordSummaryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录ID（sport_record.id，自增） */
    private Long id;

    /** 运动类型（SportType.code：1 RUNNING，2 CYCLING，3 WALKING） */
    private Integer sportType;

    /** 距离（公里） */
    private BigDecimal distance;

    /** 审核状态（RecordStatus.code，状态机见 §5.1） */
    private Integer status;

    /** 创建时间（落库时间，按此字段倒序分页） */
    private LocalDateTime createdAt;
}
