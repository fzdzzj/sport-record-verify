package com.sportverify.api.record.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 点赞请求 DTO（POST /api/records/{id}/like 请求体）。
 *
 * <p>身份认定（add-jwt-auth，见 ADR-0007）：userId 已改从<b>网关注入的 X-User-Id</b>读取，
 * 本字段仅为兼容旧行为（auth.enabled=false）的显式携带值；true 时若与 token 身份不一致 → 403。</p>
 */
@Data
public class LikeRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 点赞用户ID */
    private Long userId;
}
