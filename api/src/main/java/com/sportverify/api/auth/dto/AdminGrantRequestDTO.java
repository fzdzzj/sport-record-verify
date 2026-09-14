package com.sportverify.api.auth.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 授予管理员角色请求 DTO（add-admin-rbac，见 ADR-0007）。
 *
 * <p>治理面准入：将指定用户从默认 USER 授予为 ADMIN，仅经内部接口（网内信任）调用，
 * 由签发端（user-service）落库并写入后续签发 token 的 role claim——角色由签名方背书，
 * 下游/网关不信任外部传入。</p>
 */
@Data
public class AdminGrantRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待授予 ADMIN 的目标用户ID（必填） */
    @NotNull(message = "目标用户ID不能为空")
    private Long userId;
}