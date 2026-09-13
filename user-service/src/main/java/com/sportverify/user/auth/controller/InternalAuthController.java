package com.sportverify.user.auth.controller;

import com.sportverify.api.auth.dto.AdminGrantRequestDTO;
import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.RefreshRequestDTO;
import com.sportverify.api.auth.dto.RegisterRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.result.Result;
import com.sportverify.user.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * auth-api Feign 契约实现（认证域，接口与实现分离，与 UserApi/InternalUserController 同款约定）。
 *
 * <p>内部服务如需走认证域（注册/登录/刷新）统一经本控制器，
 * 与对外 {@link AuthController}（/api/auth/**）共用同一 {@link AuthService}。</p>
 */
@RestController
@RequestMapping("/internal/auth")
@RequiredArgsConstructor
public class InternalAuthController {

    private final AuthService authService;

    /** 注册（手机号唯一，重复 → 2001） */
    @PostMapping("/register")
    public Result<Void> register(@RequestBody RegisterRequestDTO dto) {
        authService.register(dto);
        return Result.success();
    }

    /** 登录（成功签发 access + refresh；失败 → 401 + 计失败次数） */
    @PostMapping("/login")
    public Result<TokenDTO> login(@RequestBody LoginRequestDTO dto) {
        return Result.success(authService.login(dto));
    }

    /** 刷新（refresh 换新 + 轮换作废旧 refresh；已作废/过期 → 401） */
    @PostMapping("/refresh")
    public Result<TokenDTO> refresh(@RequestBody RefreshRequestDTO dto) {
        return Result.success(authService.refresh(dto.getRefreshToken()));
    }

    /**
     * 授予指定用户 ADMIN 角色（add-admin-rbac，治理面准入，见 ADR-0007）。
     *
     * <p>网内信任：走 /internal/**（不对公网暴露）；角色由签发端落库并写入后续登录签发的
     * role claim，网关据此判定 /admin/** 与规则版本接口准入。生效需目标用户重新登录。</p>
     */
    @PostMapping("/grant-admin")
    public Result<Void> grantAdmin(@RequestBody AdminGrantRequestDTO dto) {
        authService.grantAdmin(dto.getUserId());
        return Result.success();
    }
}
