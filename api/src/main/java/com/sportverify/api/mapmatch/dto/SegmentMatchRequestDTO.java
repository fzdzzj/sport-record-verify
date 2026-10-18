package com.sportverify.api.mapmatch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 赛段匹配请求 DTO（mapmatch-api 契约，TASK-272）。
 *
 * <p>与 {@link MapMatchRequestDTO} 同款点列契约（MapMatchPointDTO 复用，按时间升序），
 * 另携带记录归属上下文：命中赛段后的冲刺成果（SegmentEffortDTO）回填 recordId/userId，
 * 供 leaderboard-service 赛段成绩上报直接使用。轨迹点必填且上限 20000 个（防超大请求体）。</p>
 */
@Data
public class SegmentMatchRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 运动记录 ID（可选：匿名演示匹配可缺省；命中成果回填此字段） */
    private Long recordId;

    /** 用户 ID（可选：auth 会话下由网关注入侧补齐；命中成果回填此字段） */
    private Long userId;

    /** 运动类型（可选：1 RUNNING, 2 CYCLING；缺省匹配全部类型赛段） */
    private Integer sportType;

    /** 轨迹点序列（按时间升序；必填，上限 20000） */
    @NotEmpty(message = "轨迹点不能为空")
    @Size(max = 20000, message = "轨迹点数量超出上限（20000）")
    @Valid
    private List<MapMatchPointDTO> points;
}
