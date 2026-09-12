package com.sportverify.user.auth.controller;

import com.sportverify.api.auth.dto.LoginRequestDTO;
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
}
