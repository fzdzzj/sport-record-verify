package com.sportverify.api.record.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 轨迹点 DTO（record-api 契约）。
 *
 * <p>对应 record_db.track_point（分片键 user_id % 16），
 * 供校验引擎消费轨迹明细做真实性判定（审批版 §5.2）。
 * 提交契约下 seq/lat/lng/ts 必填（id/userId 由服务端回填）；speed 可选非负。</p>
 */
@Data
public class TrackPointDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 轨迹点ID（分布式生成，服务端回填） */
    private Long id;

    /** 所属记录（服务端回填） */
    private Long recordId;

    /** 冗余分片键（服务端回填） */
    private Long userId;

    /** 点序号（必填，非负） */
    @NotNull(message = "轨迹点 seq 不能为空")
    @DecimalMin(value = "0", message = "轨迹点 seq 不能为负")
    private Integer seq;

    /** 纬度（必填，[-90, 90]） */
    @NotNull(message = "轨迹点 lat 不能为空")
    @DecimalMin(value = "-90", message = "纬度超出范围")
    @DecimalMax(value = "90", message = "纬度超出范围")
    private BigDecimal lat;

    /** 经度（必填，[-180, 180]） */
    @NotNull(message = "轨迹点 lng 不能为空")
    @DecimalMin(value = "-180", message = "经度超出范围")
    @DecimalMax(value = "180", message = "经度超出范围")
    private BigDecimal lng;

    /** 时间戳（毫秒，必填，非负） */
    @NotNull(message = "轨迹点 ts 不能为空")
    @DecimalMin(value = "0", message = "轨迹点 ts 不能为负")
    private Long ts;

    /** 瞬时速度（m/s，可选非负） */
    @DecimalMin(value = "0", message = "瞬时速度不能为负")
    private BigDecimal speed;
}
