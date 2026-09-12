package com.sportverify.api.user.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 好友申请创建入参（user-api 契约）。
 *
 * <p>骨架阶段无认证鉴权，调用方显式携带申请人 userId（与 record 域
 * 提交/申诉入参约定一致）；后续接入 JWT 后由网关/过滤器从 token 解析并覆盖该字段。</p>
 */
@Data
public class FriendRequestCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 申请人（发起方）用户ID */
    private Long userId;

    /** 目标用户ID（被申请人） */
    private Long targetUserId;
}
