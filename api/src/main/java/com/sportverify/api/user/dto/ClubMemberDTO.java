package com.sportverify.api.user.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 跑团成员 DTO（user-api 契约，TASK-275）。
 *
 * <p>对应 user_db.club_member，nickname 由 user-service 按成员 userId 批量回表补齐。</p>
 */
@Data
public class ClubMemberDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成员记录ID */
    private Long id;

    /** 跑团ID */
    private Long clubId;

    /** 成员用户ID */
    private Long userId;

    /** 成员昵称（经 user-service 批量补齐） */
    private String nickname;

    /** 角色：CAPTAIN 团长 / MEMBER 团员 */
    private String role;

    /** 加入时间 */
    private LocalDateTime joinedAt;
}
