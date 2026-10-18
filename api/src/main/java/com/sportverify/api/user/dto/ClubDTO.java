package com.sportverify.api.user.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 跑团 DTO（user-api 契约，TASK-275）。
 *
 * <p>对应 user_db.running_club，跑团广场 / 详情 / 我的跑团统一返回本结构；
 * creatorNickname 由 user-service 回表批量补齐（用户缺失时为 null）。</p>
 */
@Data
public class ClubDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 跑团ID */
    private Long id;

    /** 跑团名称（唯一） */
    private String name;

    /** 跑团简介 */
    private String description;

    /** 团徽 URL */
    private String badgeUrl;

    /** 团长用户ID */
    private Long creatorId;

    /** 团长昵称（经 user-service 回表补齐） */
    private String creatorNickname;

    /** 成员数（含团长，创建即 1） */
    private Integer memberCount;

    /** 状态：1 正常，0 解散 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
