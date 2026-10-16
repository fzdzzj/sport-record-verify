package com.sportverify.record.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 个人统计聚合投影（仅供 Mapper 单行聚合语句承载，非对外契约）。
 *
 * <p>E23 现状口径：{@code SportRecordMapper.selectDailyDistanceSince}（TASK-232 引入、
 * TASK-235 时间口径改写 COALESCE 后失用）已删除，本 DTO 现<b>仅服务
 * {@code selectAggByUserId}</b> 这一单行聚合投影——一次性承载 {@code COUNT(*)} /
 * {@code COALESCE(SUM(distance),0)} / {@code COUNT(CASE WHEN status IN (...) THEN 1 END)}，
 * 只置 totalCount / totalDistance / passCount 三字段；{@code createdAt} 与 {@code distance}
 * 为历史窗口投影遗留字段，现无任何查询投影。</p>
 *
 * <p>列名 → 属性映射依赖 {@code mybatis-plus.configuration.map-underscore-to-camel-case=true}
 * （既有全局配置，与其它实体一致）。</p>
 */
@Data
public class RecordAggDTO {

    /** 记录总数（COUNT(*)） */
    private Long totalCount;

    /** 总里程（COALESCE(SUM(distance),0)，未在服务端四舍五入） */
    private BigDecimal totalDistance;

    /** 通过终态记录数（COUNT(CASE WHEN status IN (2,5) THEN 1 END)） */
    private Long passCount;

    /** 记录创建时间（历史窗口投影遗留字段，现无查询投影） */
    private LocalDateTime createdAt;

    /** 单条记录里程（历史窗口投影遗留字段，现无查询投影，可能为 null） */
    private BigDecimal distance;
}
