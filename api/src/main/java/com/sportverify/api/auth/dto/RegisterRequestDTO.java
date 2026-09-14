package com.sportverify.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 注册请求 DTO（POST /api/auth/register 请求体）。
 *
 * <p>注册即创建用户：密码以 BCrypt 哈希落库（password_hash），手机号唯一（重复 → 2001）。
 * 注册成功不自动登录（不返回 token），客户端随后走登录拿 token（规范「用户注册」场景）。</p>
 */
@Data
public class RegisterRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 手机号（登录账号，唯一键；必填 + 11 位格式校验） */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 明文密码（服务端 BCrypt 哈希后落库，不落明文；长度 6-64） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度须在 6-64 位")
    private String password;

    /** 昵称（可空，列表展示用；上限 30 字） */
    @Size(max = 30, message = "昵称长度不能超过 30 字")
    private String nickname;
}
