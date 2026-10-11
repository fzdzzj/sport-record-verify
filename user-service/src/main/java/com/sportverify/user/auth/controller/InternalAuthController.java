package com.sportverify.user.auth.controller;

import com.sportverify.api.auth.dto.AdminGrantRequestDTO;
import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.RefreshRequestDTO;
import com.sportverify.api.auth.dto.RegisterRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.result.Result;
import com.sportverify.user.auth.service.AuthService;
import jakarta.validation.Valid;
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
    public Result<Void> register(@Valid @RequestBody RegisterRequestDTO dto) {
        authService.register(dto);
        return Result.success();
    }

    /** 登录（成功签发 access + refresh；失败 → 401 + 计失败次数） */
    @PostMapping("/login")
    public Result<TokenDTO> login(@Valid @RequestBody LoginRequestDTO dto) {
        return Result.success(authService.login(dto));
    }

    /** 刷新（refresh 换新 + 轮换作废旧 refresh；已作废/过期 → 401） */
    @PostMapping("/refresh")
    public Result<TokenDTO> refresh(@Valid @RequestBody RefreshRequestDTO dto) {
        return Result.success(authService.refresh(dto.getRefreshToken()));
    }

    /**
     * 授予指定用户 ADMIN 角色（add-admin-rbac，治理面准入，见 ADR-0007）。
     *
     * <p>硬化（add-resilience-hardening / ADR-0007）：路径仍属服务本地 /internal/**
     * （网关无对应路由），并须携带 {@code X-Internal-Token} 共享密钥；不再仅靠网内拓扑。
     * 角色由签发端落库并写入后续登录签发的 role claim；重新登录或 refresh 续签后生效，
     * 最长延迟=access 剩余时效，主动续签即时（TASK-202 时效口径订正）。</p>
     */
    @PostMapping("/grant-admin")
    public Result<Void> grantAdmin(@Valid @RequestBody AdminGrantRequestDTO dto) {
        authService.grantAdmin(dto.getUserId());
        return Result.success();
    }

    /**
     * 撤销指定用户 ADMIN 角色（TASK-202，与 grant-admin 同构，见 ADR-0007）。
     *
     * <p>路径同属服务本地 /internal/**（网关无对应路由），须携带 {@code X-Internal-Token} 共享密钥。
     * 时效语义：撤销后旧 access 最长在剩余时效内自然过期（≤15min），重新登录或 refresh 续签即时降权。</p>
     */
    @PostMapping("/revoke-admin")
    public Result<Void> revokeAdmin(@Valid @RequestBody AdminGrantRequestDTO dto) {
        authService.revokeAdmin(dto.getUserId());
        return Result.success();
    }
}
