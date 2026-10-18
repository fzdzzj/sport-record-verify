package com.sportverify.api.mapmatch.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 赛段冲刺成果 DTO（mapmatch-api 契约，TASK-272）。
 *
 * <p>双角色复用：</p>
 * <ul>
 *   <li><b>匹配产出</b>（mapmatch-service）：segmentId/segmentName/recordId/userId/
 *       elapsedSeconds/startTime/endTime/avgSpeed 由两段式匹配算法填充，
 *       isPr/isKom 为 null（尚未上报）；</li>
 *   <li><b>上报载体/判定回执</b>（leaderboard-service）：POST effort 以本结构携带
 *       recordId/userId/elapsedSeconds（其余字段忽略），响应回填 isPr/isKom 判定。</li>
 * </ul>
 * <p>时间字段（startTime/endTime）为轨迹点 epoch 毫秒（与 MapMatchPointDTO.ts 同口径）；
 * avgSpeed 为实际完赛距离/净耗时（米每秒）；elapsedSeconds 为净耗时
 * {@code P_end.ts - P_start.ts}（秒）。</p>
 */
@Data
@NoArgsConstructor
public class SegmentEffortDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 赛段 ID */
    private Long segmentId;

    /** 赛段名（leaderboard 侧不掌握赛段元数据，上报回执中为 null） */
    private String segmentName;

    /** 运动记录 ID（完赛该赛段的记录） */
    private Long recordId;

    /** 用户 ID */
    private Long userId;

    /** 完赛净耗时（秒，P_end.ts - P_start.ts） */
    private Long elapsedSeconds;

    /** 起点锚定时刻（epoch 毫秒，P_start.ts） */
    private Long startTime;

    /** 终点锚定时刻（epoch 毫秒，P_end.ts） */
    private Long endTime;

    /** 实际完赛均速（米每秒，实际累计距离 / 净耗时） */
    private Double avgSpeed;

    /** 是否个人最好成绩（PR）：仅 leaderboard-service 判定后回填 */
    private Boolean isPr;

    /** 是否全场第一（KOM 👑）：仅 leaderboard-service 判定后回填 */
    private Boolean isKom;
}
