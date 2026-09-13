package com.sportverify.api.auth.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录请求 DTO（POST /api/auth/login 请求体）。
 *
 * <p>凭手机号 + 明文密码换 token：成功签发 access（短时效）+ refresh（长时效）双 token；
 * 密码错误 → 401 并按手机号计失败次数（超过阈值锁定的设计口径，见 AuthService 注释）。</p>
 */
@Data
public class LoginRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 手机号（登录账号） */
    private String phone;

    /** 明文密码（仅本次校验使用，不落库） */
    private String password;
}
