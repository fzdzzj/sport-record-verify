package com.sportverify.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    /** 手机号（登录账号，必填 + 11 位格式校验） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 明文密码（仅本次校验使用，不落库；长度 6-64） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度须在 6-64 位")
    private String password;
}
