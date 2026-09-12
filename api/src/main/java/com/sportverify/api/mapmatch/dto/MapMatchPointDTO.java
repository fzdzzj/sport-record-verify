package com.sportverify.api.mapmatch.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 匹配轨迹点（mapmatch-api 契约）。
 *
 * <p>经纬度用 double：匹配是纯计算场景，不涉及分片键/落库精度问题
 * （record 契约的 TrackPointDTO 用 BigDecimal 是数据库 NUMERIC 对齐的考量）。</p>
 */
@Data
public class MapMatchPointDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 点序号（与轨迹原始序号一致，便于证据里回指） */
    private Integer seq;

    /** 纬度 */
    private Double lat;

    /** 经度 */
    private Double lng;

    /** 时间戳（毫秒）；匹配算法目前不用时间维，保留契约位供 HMM 进阶用 */
    private Long ts;
}
