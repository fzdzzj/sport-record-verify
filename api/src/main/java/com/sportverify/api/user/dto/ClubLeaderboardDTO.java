package com.sportverify.api.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 跑团团队里程榜条目 DTO（user-api 契约，TASK-275）。
 *
 * <p>数据源为 leaderboard-service Redis ZSet {@code leaderboard:club:overall} /
 * {@code leaderboard:club:weekly:{weekStart}}（member=clubId，score=累计里程米），
 * clubName 经 Redis Hash {@code leaderboard:club:names} 缓存补齐，按里程降序 rank 从 1 起。</p>
 *
 * <p>TASK-276 R4 起 {@code memberCount} 不再是「恒 0 预留字段」：榜单组装时经 user-service
 * 内部端点批查真实 {@code running_club.member_count} 填充；批查失败（user-service 不可用/
 * 降级/返回缺项）时<b>降级 0</b>并保持占位语义——榜单主体（名次/里程/名称）不因计数缺失而
 * 失败，前端按「未知成员数」展示。</p>
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

    /** 成员数（TASK-276 R4：经 user-service 内部端点批查真实值；查询失败降级 0 保持占位语义） */
    private Integer memberCount;

    /** 累计里程（公里，保留 2 位小数） */
    private Double totalDistanceKm;

    /** 格式化里程（如 12.35 km） */
    private String formattedDistance;
}
