package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 点赞请求 DTO（POST /api/records/{id}/like 请求体）。
 *
 * <p>骨架无认证鉴权，点赞人 userId 由调用方显式携带
 * （与 record 域提交/申诉、user 域好友申请入参约定一致，接 JWT 后改从 token 解析）。</p>
 */
@Data
public class LikeRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 点赞用户ID */
    private Long userId;
}
