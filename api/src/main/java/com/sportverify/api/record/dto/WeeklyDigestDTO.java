package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 每周运动智能周报 DTO（record-api 契约，TASK-274 每周运动智能周报）。
 *
 * <p>自然周（周一 00:00:00 至周日 23:59:59，东八墙钟）维度聚合通过终态记录：
 * 总里程/耗时/热量、出勤天数、主修类型与最快配速，叠加环比前一周增减率、
 * 周跑量分位数推算的击败比例与个性化体能恢复建议，供首页周报卡片渲染。</p>
 */
@Data
public class WeeklyDigestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 归属用户 id */
    private Long userId;

    /** 用户昵称（user-service 不可用时为 null，前端回退 userId 渲染） */
    private String nickname;

    /** 周起始日（周一 ISO 日期，如 2026-10-12） */
    private String weekStartDate;

    /** 周结束日（周日 ISO 日期，如 2026-10-18） */
    private String weekEndDate;

    /** 自然周总有效运动里程（米） */
    private Double totalDistanceMeters;

    /** 自然周总运动耗时（秒） */
    private Integer totalElapsedSeconds;

    /** 自然周估算消耗总热量（kcal，按运动类型公里系数估算，保留 1 位小数） */
    private Double totalCalories;

    /** 自然周运动完成次数（通过终态记录条数） */
    private Integer workoutCount;

    /** 自然周出勤活跃天数（0~7，按活动时间归日去重） */
    private Integer activeDays;

    /**
     * 周里程环比前一周增减率（如 0.152 表示 +15.2%；前一周无数据设为 1.0，本封周与前一周均无数据为 0.0）
     */
    private Double distanceGrowthRate;

    /** 击败跑者比例（按周跑量分位数推算 0.0~0.99，跑量为 0 时为 0.0） */
    private Double beatRatio;

    /** 当周主修运动类型（RUNNING / CYCLING / WALKING 枚举名；无通过记录时为 null） */
    private String primarySportType;

    /** 当周最佳满公里配速（如 4'35"；当周无 ≥1 公里记录时为 --） */
    private String fastestPaceFormatted;

    /** 基于出勤天数与周跑量的个性化体能恢复与训练建议 */
    private String recoveryAdvice;
}
