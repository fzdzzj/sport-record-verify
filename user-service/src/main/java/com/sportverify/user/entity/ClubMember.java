package com.sportverify.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑团成员关系实体（user_db.club_member，TASK-275）。
 *
 * <p>唯一键 uk_user_club (user_id) 物理保障「一人一团」：同一用户全仓至多一行成员记录，
 * 并发加入/创建由 DuplicateKeyException 捕获兜底；role 二态 CAPTAIN / MEMBER。</p>
 */
@Data
@TableName("club_member")
public class ClubMember {

    /** 角色：团长（创建跑团者，全团至多一人） */
    public static final String ROLE_CAPTAIN = "CAPTAIN";
    /** 角色：团员（默认） */
    public static final String ROLE_MEMBER = "MEMBER";

    /** 成员记录ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 跑团ID */
    private Long clubId;

    /** 成员用户ID（唯一，一人一团） */
    private Long userId;

    /** 角色：CAPTAIN 团长 / MEMBER 团员 */
    private String role;

    /** 加入时间 */
    private LocalDateTime joinedAt;
}
