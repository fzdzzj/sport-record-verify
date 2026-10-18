package com.sportverify.api.mapmatch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 轨迹智能纠偏对比结果（mapmatch-api 契约，TASK-274 轨迹纠偏对比回放）。
 *
 * <p>在既有偏离指标（{@link MapMatchResultDTO}）之上叠加「吸附点序列」：每个采样点
 * 按点到最近道路边的垂足投影（参数 t ∈ [0,1] 反算 WGS84 插值坐标）吸附至路网中线，
 * 垂距 ≤ 吸附阈值的点采纳投影坐标、垂距 &gt; 1.0m 记 1 次纠偏；超阈值或无候选边的
 * 点保持原经纬度不变。前端详情页据此同屏叠加「原始轨迹 vs 纠偏中线」双图层。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrackComparisonDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 吸附到道路中线后的点位序列（保持原 seq/ts，lat/lng 为投影垂足坐标；未吸附点原样保留） */
    private List<MapMatchPointDTO> snappedPoints;

    /** 实际发生坐标纠偏的点数（垂距 ≤ 吸附阈值且位移 &gt; 1.0m） */
    private Integer correctedCount;

    /** 路网吸附率 0.0~1.0（垂距 ≤ 吸附阈值的采样点占比） */
    private Double matchedRatio;

    /** 离路比例 0.0~1.0（垂距 &gt; 吸附阈值的采样点占比） */
    private Double offRoadRatio;

    /** 平均偏离距离（米，保留 1 位小数） */
    private Double avgOffRoadDistance;

    /** 最大偏离距离（米，保留 1 位小数；无候选边时按查询半径封顶） */
    private Double maxOffRoadDistance;

    /** 采样计算总点数（超长轨迹抽稀后的值） */
    private Integer totalPoints;
}
