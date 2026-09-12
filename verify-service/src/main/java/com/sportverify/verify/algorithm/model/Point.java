package com.sportverify.verify.algorithm.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 校验引擎内部轨迹点（TrackPointDTO → 引擎模型）。
 *
 * <p>{@code speed} 与 {@code dtSeconds} 在预处理阶段计算：
 * 瞬时速度 = haversine(上一有效点, 当前点) / Δt，不信任客户端上报的 speed 字段。</p>
 */
@Data
@AllArgsConstructor
public class Point {

    /** 点序号 */
    private int seq;

    /** 纬度 */
    private double lat;

    /** 经度 */
    private double lng;

    /** 时间戳（毫秒） */
    private long ts;

    /** 与上一有效点的瞬时速度（m/s）；首点无值 */
    private double speed;

    /** 与上一有效点的时间差（秒）；首点无值 */
    private double dtSeconds;
}
