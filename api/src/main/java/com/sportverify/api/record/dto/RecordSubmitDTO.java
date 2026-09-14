package com.sportverify.api.record.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运动记录提交 DTO（record-api 契约，规范「轨迹提交幂等」）。
 *
 * <p>客户端提交记录 + 轨迹点数组；{@code id / userId} 等字段由服务端填充，
 * 轨迹点按 {@code user_id % 16} 分片落库（规范「轨迹分片存储」）。
 * 入参校验：requestId/sportType 必填，sportType 取值 1-3（与 {@link com.sportverify.api.record.SportType} 对齐），
 * 轨迹点列表必填且上限 20000 个（防超大请求体）。</p>
 */
@Data
public class RecordSubmitDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 客户端幂等键（必填，唯一，重复提交返回原结果 3004） */
    @NotBlank(message = "requestId 不能为空")
    @Size(max = 64, message = "requestId 长度不能超过 64")
    private String requestId;

    /** 所属用户（鉴权降级时显式携带；auth.enabled=true 由网关注入覆盖，不做必填约束） */
    private Long userId;

    /** 运动类型（SportType.code：1 RUNNING，2 CYCLING，3 WALKING；必填，枚举外取值拒绝） */
    @NotNull(message = "sportType 不能为空")
    @Min(value = 1, message = "运动类型非法")
    @Max(value = 3, message = "运动类型非法")
    private Integer sportType;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 距离（公里，非负） */
    @DecimalMin(value = "0", message = "距离不能为负")
    private BigDecimal distance;

    /** 时长（秒，非负） */
    @Min(value = 0, message = "时长不能为负")
    private Integer duration;

    /** 轨迹点数组（seq/lat/lng/ts 必填；id/userId 由服务端回填；必填 + 上限 20000） */
    @NotEmpty(message = "轨迹点不能为空")
    @Size(max = 20000, message = "轨迹点数量超出上限（20000）")
    @Valid
    private List<TrackPointDTO> points;
}
