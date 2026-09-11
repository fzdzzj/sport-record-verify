package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 轨迹点 DTO（record-api 契约）。
 *
 * <p>对应 record_db.track_point（分片键 user_id % 16），
 * 供校验引擎消费轨迹明细做真实性判定（审批版 §5.2）。</p>
 */
@Data
public class TrackPointDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 轨迹点ID（分布式生成） */
    private Long id;

    /** 所属记录 */
    private Long recordId;

    /** 冗余分片键 */
    private Long userId;

    /** 点序号 */
    private Integer seq;

    /** 纬度 */
    private BigDecimal lat;

    /** 经度 */
    private BigDecimal lng;

    /** 时间戳（毫秒） */
    private Long ts;

    /** 瞬时速度（m/s） */
    private BigDecimal speed;
}
