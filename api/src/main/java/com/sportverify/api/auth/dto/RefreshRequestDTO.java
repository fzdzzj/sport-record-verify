package com.sportverify.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 刷新请求 DTO（POST /api/auth/refresh 请求体）。
 *
 * <p>携带 refresh token 换新 token 对：服务端校验 JWT 签名/时效 + Redis 存活状态，
 * 通过后签发新 access + 新 refresh，并原子作废旧 refresh（轮换防重放，见 ADR-0007）。</p>
 */
@Data
public class RefreshRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** refresh token（登录/上次刷新时签发，7 天时效；必填） */
    @NotBlank(message = "refresh token 不能为空")
    private String refreshToken;
}
