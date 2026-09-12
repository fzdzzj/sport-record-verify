package com.sportverify.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 好友申请实体（user_db.friend_request）。
 *
 * <p>状态机见 {@code FriendRequestStatus}：PENDING(0)/ACCEPTED(1)/REJECTED(2)/CANCELLED(3)，
 * 仅 PENDING 可流转，流转走乐观语义（Mapper#updateStatus 的 WHERE status 条件）。</p>
 */
@Data
@TableName("friend_request")
public class FriendRequest {

    /** 申请ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人 */
    private Long fromUser;

    /** 被申请人 */
    private Long toUser;

    /** 状态（FriendRequestStatus.code） */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
