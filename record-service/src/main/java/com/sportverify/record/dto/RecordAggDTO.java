package com.sportverify.record.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 个人统计聚合投影（TASK-232，仅供 Mapper 单行/多行聚合语句承载，非对外契约）。
 *
 * <p>两个用途：</p>
 * <ul>
 *   <li><b>全量指标单行聚合</b>：{@code selectAggByUserId} 的 {@code resultType}，
 *       一次性承载 {@code COUNT(*)} / {@code COALESCE(SUM(distance),0)} /
 *       {@code COUNT(CASE WHEN status IN (...) THEN 1 END)}（只置 totalCount /
 *       totalDistance / passCount 三字段）；</li>
 *   <li><b>30 天窗口日粒度投影</b>：{@code selectDailyDistanceSince} 的 {@code resultType}，
 *       每行只置 createdAt / distance，按自然日汇总下推到 SQL 时间窗、上收到
 *       {@code SportRecordService.myStats} 做日粒度累加（只置 createdAt / distance 两字段）。</li>
 * </ul>
 *
 * <p>列名 → 属性映射依赖 {@code mybatis-plus.configuration.map-underscore-to-camel-case=true}
 * （既有全局配置，与其它实体一致）。</p>
 */
@Data
public class RecordAggDTO {

    /** 记录总数（COUNT(*)；仅单行聚合语句使用） */
    private Long totalCount;

    /** 总里程（COALESCE(SUM(distance),0)；仅单行聚合语句使用，未在服务端四舍五入） */
    private BigDecimal totalDistance;

    /** 通过终态记录数（COUNT(CASE WHEN status IN (2,5) THEN 1 END)；仅单行聚合语句使用） */
    private Long passCount;

    /** 记录创建时间（仅窗口日粒度投影使用） */
    private LocalDateTime createdAt;

    /** 单条记录里程（仅窗口日粒度投影使用，可能为 null） */
    private BigDecimal distance;
}
