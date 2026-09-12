package com.sportverify.api.user.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 好友申请单 DTO（user-api 契约）。
 *
 * <p>对应 user_db.friend_request。status 对外下发枚举名（PENDING/ACCEPTED/REJECTED/CANCELLED），
 * 与审批版 §4.1 示例 {@code status:"PENDING"} 一致；落库值为 TINYINT 码
 * （审批版 §6.1：0 PENDING / 1 ACCEPTED / 2 REJECTED / 3 CANCELLED），
 * 码 ↔ 名的转换在 user-service 侧完成。</p>
 */
@Data
public class FriendRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 申请ID */
    private Long id;

    /** 申请人 */
    private Long fromUser;

    /** 被申请人 */
    private Long toUser;

    /** 状态：PENDING / ACCEPTED / REJECTED / CANCELLED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
