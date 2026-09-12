package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运动记录提交 DTO（record-api 契约，规范「轨迹提交幂等」）。
 *
 * <p>客户端提交记录 + 轨迹点数组；{@code id / userId} 等字段由服务端填充，
 * 轨迹点按 {@code user_id % 16} 分片落库（规范「轨迹分片存储」）。</p>
 */
@Data
public class RecordSubmitDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 客户端幂等键（唯一，重复提交返回原结果 3004） */
    private String requestId;

    /** 所属用户 */
    private Long userId;

    /** 运动类型：1 RUNNING，2 CYCLING ... */
    private Integer sportType;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 距离（公里） */
    private BigDecimal distance;

    /** 时长（秒） */
    private Integer duration;

    /** 轨迹点数组（seq/lat/lng/ts 必填；id/userId 由服务端回填） */
    private List<TrackPointDTO> points;
}
