package com.sportverify.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑团实体（user_db.running_club，TASK-275）。
 *
 * <p>团队组织载体主表：名称唯一（uk_name），一人一团约束由 club_member
 * 唯一键 uk_user_club (user_id) 物理保障；仅剩团长一人退团时置 status=0 解散。</p>
 */
@Data
@TableName("running_club")
public class RunningClub {

    /** 状态：正常 */
    public static final int STATUS_ACTIVE = 1;
    /** 状态：解散 */
    public static final int STATUS_DISSOLVED = 0;

    /** 跑团ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 跑团名称（唯一） */
    private String name;

    /** 跑团简介 */
    private String description;

    /** 团徽 URL */
    private String badgeUrl;

    /** 团长用户ID */
    private Long creatorId;

    /** 成员数（含团长，创建即 1） */
    private Integer memberCount;

    /** 状态：1 正常，0 解散 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
