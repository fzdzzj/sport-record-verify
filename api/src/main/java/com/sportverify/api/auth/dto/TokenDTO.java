package com.sportverify.api.auth.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * Token 响应 DTO（登录/刷新成功返回体）。
 *
 * <p>双 token 模型（见 ADR-0007）：access 短时效（默认 15min，业务接口携带，
 * 网关统一校验）；refresh 长时效（默认 7 天，仅用于换新，Redis 存活校验 + 轮换作废）。</p>
 */
@Data
public class TokenDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** access token（Authorization: Bearer 携带，网关校验并解析 userId） */
    private String accessToken;

    /** refresh token（换新 access 用，轮换制：每次刷新后旧 refresh 作废） */
    private String refreshToken;

    /** token 类型（Bearer，固定） */
    private String tokenType;

    /** access token 有效期（秒，客户端可据此提前刷新） */
    private long expiresIn;
}
