package com.sportverify.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运动目标实体（record_db.sport_goal，TASK-225）。
 *
 * <p>唯一键 uk_user_period_metric (user_id, period_type, metric)：
 * 每维度仅允许一个活跃目标，POST 端点执行 upsert 语义。</p>
 */
@Data
@TableName("sport_goal")
public class Goal {

    /** 目标ID（自增主键） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属用户 */
    private Long userId;

    /** 周期类型：0 周，1 月 */
    private Integer periodType;

    /** 指标类型：0 距离(km)，1 次数 */
    private Integer metric;

    /** 目标值 */
    private BigDecimal targetValue;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
