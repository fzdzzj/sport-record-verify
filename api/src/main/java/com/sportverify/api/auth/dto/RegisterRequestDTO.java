package com.sportverify.api.auth.dto;

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

    /** 手机号（登录账号，唯一键） */
    private String phone;

    /** 明文密码（服务端 BCrypt 哈希后落库，不落明文） */
    private String password;

    /** 昵称（可空，列表展示用） */
    private String nickname;
}
