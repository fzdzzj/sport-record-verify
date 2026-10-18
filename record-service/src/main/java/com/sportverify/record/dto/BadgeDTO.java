package com.sportverify.record.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 勋章视图对象（TASK-269，勋章墙 / 动态流外显统一载体）。
 *
 * <p>{@code unlocked=false} 时 {@code unlockedAt/isEquipped} 恒为 null/false，
 * 前端按 {@code progressPct} 渲染进度条；{@code currentValue} 为勋章语义下的
 * 实时进度值（公里数或天数），{@code targetValue} 为解锁目标。</p>
 */
@Data
public class BadgeDTO {

    /** 勋章编码（{@code BadgeType} 枚举名） */
    private String code;

    /** 勋章中文名 */
    private String name;

    /** 达成描述 */
    private String description;

    /** 分类：MILESTONE / STREAK / SPECIAL */
    private String category;

    /** 阶梯：BRONZE / SILVER / GOLD / PLATINUM */
    private String tier;

    /** 图标（emoji 展示字符） */
    private String icon;

    /** 是否已解锁 */
    private Boolean unlocked;

    /** 解锁时间（未解锁为 null） */
    private LocalDateTime unlockedAt;

    /** 是否佩戴中 */
    private Boolean isEquipped;

    /** 当前进度值（公里数或天数） */
    private BigDecimal currentValue;

    /** 目标值（解锁阈值） */
    private BigDecimal targetValue;

    /** 进度百分比（0..100 整数截断封顶） */
    private Integer progressPct;
}
