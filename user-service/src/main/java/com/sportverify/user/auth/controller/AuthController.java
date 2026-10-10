package com.sportverify.user.auth.controller;

import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.LogoutRequestDTO;
import com.sportverify.api.auth.dto.RefreshRequestDTO;
import com.sportverify.api.auth.dto.RegisterRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
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
 * <p>四个端点（注册/登录/刷新/登出）均在网关白名单下免网关全局 token 校验。
 * 登出端点由本服务自行解析 refresh token 并在 Redis 吊销；
 * access 15min 短窗口为声明接受的设计权衡（见 README 鉴权章节）。</p>
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
     * 从请求中提取 clientIp（XFF 尾段）支持 (phone, ip) 登录锁定双维度。
     */
    @PostMapping("/login")
    public Result<TokenDTO> login(@Valid @RequestBody LoginRequestDTO dto, HttpServletRequest request) {
        String ip = clientIp(request);
        return Result.success(authService.login(dto, ip));
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
     * 登出（主动吊销 refresh token）：作废 refresh 存活键（此后轮换 401）。
     *
     * <p>在网关白名单 {@code /api/auth/**} 下免网关校验，服务侧自行解析 refresh token；
     * 登出幂等处理（无效/已过期 token 同样返回成功，不泄漏有效性）。
     * access 15min 短窗口为声明接受的设计权衡（见 README 鉴权章节）。</p>
     */
    @PostMapping("/logout")
    public Result<Void> logout(@Valid @RequestBody LogoutRequestDTO dto) {
        authService.logout(dto.getRefreshToken());
        return Result.success();
    }

    /**
     * 提取客户端出口 IP。
     *
     * <p>信任边界说明：单网关一跳拓扑下，Spring Cloud Gateway 会将真实客户端 IP append 到
     * X-Forwarded-For 尾部；前置段可被客户端伪造不可信，因此仅取最后一个逗号后的尾段。
     * 直连无网关时回退 remoteAddr；若解析不出或为空则返回 null（降级仅走 phone 维度）。</p>
     */
    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int lastComma = xff.lastIndexOf(',');
            String ip = (lastComma >= 0) ? xff.substring(lastComma + 1).trim() : xff.trim();
            if (!ip.isEmpty()) {
                return ip;
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr.trim() : null;
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
