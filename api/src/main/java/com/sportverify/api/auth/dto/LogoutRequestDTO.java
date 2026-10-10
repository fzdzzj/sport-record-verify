package com.sportverify.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 登出请求 DTO（POST /api/auth/logout 请求体）。
 *
 * <p>携带 refresh token 主动吊销：服务端解析并删除 Redis 存活键（即刻作废）。
 * 登出具备幂等语义：无效或已过期 token 同样返回成功，不泄漏 token 有效性。</p>
 */
@Data
public class LogoutRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** refresh token（必填） */
    @NotBlank(message = "refresh token 不能为空")
    private String refreshToken;
}
