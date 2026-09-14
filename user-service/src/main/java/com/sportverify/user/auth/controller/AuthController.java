package com.sportverify.user.auth.controller;

import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.RefreshRequestDTO;
import com.sportverify.api.auth.dto.RegisterRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.auth.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证域对外接口（网关白名单 {@code /api/auth/**} → user-service，对应 ADR-0007）。
 *
 * <p>三个端点均<b>不携带</b> token（注册/登录/刷新是发 token 的地方，属网关白名单）；
 * 业务接口的 token 校验统一由网关 GlobalFilter 承担，本服务不自行校验 token。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 注册（规范「用户注册」）：手机号唯一，重复 → 2001；密码 BCrypt 哈希落库。
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequestDTO dto) {
        authService.register(dto);
        return Result.success();
    }

    /**
     * 登录（规范「用户登录」）：成功签发 access + refresh；失败 → 401 并计失败次数。
     */
    @PostMapping("/login")
    public Result<TokenDTO> login(@Valid @RequestBody LoginRequestDTO dto) {
        return Result.success(authService.login(dto));
    }

    /**
     * 刷新（规范「Token 刷新与轮换」）：refresh 换新 access + 新 refresh；
     * 旧 refresh 原子作废（重放 → 401），已作废/过期 → 401（1001）。
     */
    @PostMapping("/refresh")
    public Result<TokenDTO> refresh(@Valid @RequestBody RefreshRequestDTO dto) {
        return Result.success(authService.refresh(dto.getRefreshToken()));
    }

    /**
     * 认证域局部异常处理：1001（密码错误/token 无效）映射为 <b>HTTP 401</b>
     * （规范「返回 401」以 HTTP 状态为准）；其余业务码（2001 等）保持全局口径
     * （HTTP 200 + body code），避免覆盖 GlobalExceptionHandler 的 200/400/500 约定。
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleAuthBizException(BizException e, HttpServletResponse response) {
        log.warn("认证异常：code={}, message={}", e.getCode(), e.getMessage());
        if (e.getCode() == ResultCode.UNAUTHORIZED.getCode()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        }
        return Result.failure(e.getCode(), e.getMessage());
    }
}
