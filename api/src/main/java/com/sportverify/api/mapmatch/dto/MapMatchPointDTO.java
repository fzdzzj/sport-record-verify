package com.sportverify.api.mapmatch.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 匹配轨迹点（mapmatch-api 契约）。
 *
 * <p>经纬度用 double：匹配是纯计算场景，不涉及分片键/落库精度问题
 * （record 契约的 TrackPointDTO 用 BigDecimal 是数据库 NUMERIC 对齐的考量）。
 * seq/lat/lng 必填；ts 匹配算法目前不用时间维，保留契约位供 HMM 进阶用。</p>
 */
@Data
public class MapMatchPointDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 点序号（与轨迹原始序号一致，便于证据里回指；必填，非负） */
    @NotNull(message = "匹配点 seq 不能为空")
    @DecimalMin(value = "0", message = "匹配点 seq 不能为负")
    private Integer seq;

    /** 纬度（必填，[-90, 90]） */
    @NotNull(message = "匹配点 lat 不能为空")
    @DecimalMin(value = "-90", message = "纬度超出范围")
    @DecimalMax(value = "90", message = "纬度超出范围")
    private Double lat;

    /** 经度（必填，[-180, 180]） */
    @NotNull(message = "匹配点 lng 不能为空")
    @DecimalMin(value = "-180", message = "经度超出范围")
    @DecimalMax(value = "180", message = "经度超出范围")
    private Double lng;

    /** 时间戳（毫秒）；匹配算法目前不用时间维，保留契约位供 HMM 进阶用 */
    private Long ts;
}
