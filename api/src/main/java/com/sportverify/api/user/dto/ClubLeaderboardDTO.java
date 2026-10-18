package com.sportverify.api.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 跑团团队里程榜条目 DTO（user-api 契约，TASK-275）。
 *
 * <p>数据源为 leaderboard-service Redis ZSet {@code leaderboard:club:overall} /
 * {@code leaderboard:club:weekly:{weekStart}}（member=clubId，score=累计里程米），
 * clubName 经 Redis Hash {@code leaderboard:club:names} 缓存补齐，按里程降序 rank 从 1 起。</p>
 */
@Data
public class ClubLeaderboardDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 名次（从 1 开始，里程降序） */
    private Integer rank;

    /** 跑团ID（ZSet member） */
    private Long clubId;

    /** 跑团名称（Hash 缓存缺失时以「跑团{id}」兜底） */
    private String clubName;

    /** 成员数（ZSet/Hash 不携带，预留字段由后续计数同步链路填充） */
    private Integer memberCount;

    /** 累计里程（公里，保留 2 位小数） */
    private Double totalDistanceKm;

    /** 格式化里程（如 12.35 km） */
    private String formattedDistance;
}
