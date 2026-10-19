package com.sportverify.api.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 跑团成员贡献明细 DTO（user-api 契约，TASK-276 R3）。
 *
 * <p>数据源为 leaderboard-service Redis ZSet {@code leaderboard:club:member:{clubId}}
 * （member=userId，score=该成员在团期间累计入库里程米），{@code ZREVRANGE} 降序取前 N，
 * rank 从 1 起；昵称经 user-api 批量补齐，缺失（user-service 降级或用户已注销）时以
 * 「跑者{userId}」占位，与总榜「用户{id}」同哲学（降级不阻断榜单可用性）。</p>
 *
 * <p>与 {@link ClubLeaderboardDTO} 的分工：后者是团队维度（member=clubId）的聚合榜，
 * 本 DTO 是团内成员维度（member=userId）的贡献明细，二者共用同一个跑团里程 ZSet 域。</p>
 */
@Data
public class ClubMemberContributionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 名次（从 1 开始，成员贡献里程降序） */
    private Integer rank;

    /** 成员用户ID（ZSet member） */
    private Long userId;

    /** 成员昵称（批量补齐；缺失以「跑者{userId}」兜底） */
    private String nickname;

    /** 成员累计贡献里程（米，ZSet score；负数已由回退净额抵消，展示口径恒非负） */
    private Double distanceMeters;
}
