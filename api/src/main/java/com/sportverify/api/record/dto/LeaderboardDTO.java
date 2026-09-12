package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 榜单条目 DTO（record-api 榜单契约，审批版 §4.5）。
 *
 * <p>总榜 / 好友榜统一返回本结构：数据源为 Redis ZSet {@code leaderboard:overall}
 * （member=userId，score=累计 pass 里程），昵称由 user-service 批量补齐。
 * {@code rank} 从 1 开始，按里程降序；好友榜在此基础上按好友过滤。</p>
 */
@Data
public class LeaderboardDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 排名（从 1 开始，里程降序） */
    private Integer rank;

    /** 用户ID（ZSet member） */
    private Long userId;

    /** 昵称（经 user-service 批量查询；用户缺失时以「用户{id}」兜底） */
    private String nickname;

    /** 累计 pass 里程（公里，ZSet score） */
    private BigDecimal distance;

    public static LeaderboardDTO of(Integer rank, Long userId, String nickname, BigDecimal distance) {
        LeaderboardDTO dto = new LeaderboardDTO();
        dto.setRank(rank);
        dto.setUserId(userId);
        dto.setNickname(nickname);
        dto.setDistance(distance);
        return dto;
    }
}
