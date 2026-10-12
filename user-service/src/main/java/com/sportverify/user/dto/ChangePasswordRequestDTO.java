package com.sportverify.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 自助改密请求 DTO（POST /api/auth/change-password 请求体，TASK-208）。
 *
 * <p>字段校验风格与 api 模块 RegisterRequestDTO 对齐（@NotBlank + @Size 长度 6-64）；
 * 旧密码与新密码均由服务端 BCrypt 校验/哈希（不落明文）。api 模块零改动：本 DTO 放
 * user-service 本地 dto 包（com.sportverify.user.dto）。</p>
 */
@Data
public class ChangePasswordRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 旧密码（必填；服务端 BCrypt matches 校验，失败统一 1001） */
    @NotBlank(message = "旧密码不能为空")
    @Size(min = 6, max = 64, message = "旧密码长度须在 6-64 位")
    private String oldPassword;

    /** 新密码（必填，与旧密码不得相同；服务端 BCrypt 哈希后落库） */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 64, message = "新密码长度须在 6-64 位")
    private String newPassword;
}
