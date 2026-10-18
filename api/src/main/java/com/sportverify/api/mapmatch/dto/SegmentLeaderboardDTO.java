package com.sportverify.api.mapmatch.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 赛段排行榜条目 DTO（mapmatch-api 契约，TASK-272）。
 *
 * <p>数据源为 Redis ZSet {@code leaderboard:segment:{segmentId}}
 * （member=userId，score=个人最好 elapsed_seconds，升序），第 1 名即赛段擂主
 * （King of the Mountain / KOM 👑）；recordId 经附属 Hash 回指完赛记录；
 * 配速由记录里程派生（耗时 / 记录距离），记录缺失时为占位符。</p>
 */
@Data
public class SegmentLeaderboardDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 排名（从 1 开始，耗时升序；rank=1 即 KOM） */
    private Integer rank;

    /** 用户 ID（ZSet member） */
    private Long userId;

    /** 昵称（经 user-service 批量查询；用户缺失时以「用户{id}」兜底） */
    private String nickname;

    /** 个人最好完赛耗时（秒，ZSet score） */
    private Long elapsedSeconds;

    /** 完赛记录 ID（附属 Hash 回指；无记录时 0） */
    private Long recordId;

    /** 格式化配速（分'秒"/km，由耗时与记录里程派生；里程缺失时为 "-"） */
    private String paceFormatted;
}
