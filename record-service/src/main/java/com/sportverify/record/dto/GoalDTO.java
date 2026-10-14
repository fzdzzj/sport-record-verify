package com.sportverify.record.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运动目标及进度响应 DTO（TASK-225）。
 *
 * <p>包含目标配置与实时计算的完成进度（pull-on-read）：
 * achievedValue 为当前周期内累积达成量，percent 为达成百分比（0..100 封顶）。</p>
 */
@Data
public class GoalDTO {

    /** 目标ID */
    private Long id;

    /** 所属用户 */
    private Long userId;

    /** 周期类型：0 周，1 月 */
    private Integer periodType;

    /** 指标类型：0 距离(km)，1 次数 */
    private Integer metric;

    /** 目标值 */
    private BigDecimal targetValue;

    /** 当前周期已达成值（SUM(distance) 或 COUNT(records)） */
    private BigDecimal achievedValue;

    /** 完成百分比（0..100 整数封顶） */
    private Integer percent;

    /** 创建时间 */
    private LocalDateTime createdAt;

    public BigDecimal getTarget() {
        return targetValue;
    }

    public void setTarget(BigDecimal target) {
        this.targetValue = target;
    }

    public BigDecimal getAchieved() {
        return achievedValue;
    }

    public void setAchieved(BigDecimal achieved) {
        this.achievedValue = achieved;
    }
}
