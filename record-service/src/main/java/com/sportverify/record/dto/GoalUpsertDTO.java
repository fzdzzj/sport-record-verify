package com.sportverify.record.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 运动目标创建或更新 DTO（TASK-225）。
 */
@Data
public class GoalUpsertDTO {

    /** 所属用户（可选，鉴权关闭时显式传递，开启时以网关注入为准） */
    private Long userId;

    /** 周期类型：0 周，1 月 */
    @NotNull(message = "周期类型不能为空")
    @Min(value = 0, message = "周期类型非法")
    @Max(value = 1, message = "周期类型非法")
    private Integer periodType;

    /** 指标类型：0 距离(km)，1 次数 */
    @NotNull(message = "指标类型不能为空")
    @Min(value = 0, message = "指标类型非法")
    @Max(value = 1, message = "指标类型非法")
    private Integer metric;

    /** 目标值（必须大于 0） */
    @NotNull(message = "目标值不能为空")
    @DecimalMin(value = "0.01", message = "目标值必须大于0")
    private BigDecimal targetValue;

    public BigDecimal getTarget() {
        return targetValue;
    }

    public void setTarget(BigDecimal target) {
        this.targetValue = target;
    }
}
