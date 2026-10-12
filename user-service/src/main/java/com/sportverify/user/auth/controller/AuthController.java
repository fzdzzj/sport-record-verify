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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 认证域对外接口（网关白名单 {@code /api/auth/**} → user-service，对应 ADR-0007）。
 *
 * <p>四个端点（注册/登录/刷新/登出）均在网关白名单下免网关全局 token 校验。
 * 登出端点由本服务自行解析 refresh token 并在 Redis 吊销；
 * access 15min 短窗口为声明接受的设计权衡（见 README 鉴权章节）。</p>
 *
 * <p>refresh 下发通道（TASK-205 web 凭证面硬加固）：login/refresh 成功后把 refresh
 * 经 <b>HttpOnly cookie</b>（SameSite=Lax，Path=/api/auth）下发，body 不含 refreshToken；
 * logout 时按 cookie 优先、body 兜底读取后吊销，并 Set-Cookie maxAge=0 清除浏览器残留。
 * 凭证窗口由「refresh 7 天 localStorage 可读」收敛为「access 15min 短窗口」——
 * HttpOnly 使 JS 不可读，XSS 到此截止（access 仍需 Bearer 头供 STOMP 用，见 README 边界）。
 * Api 模块 TokenDTO 与 AuthService 零改动：纯控制器层 cookie 包装。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 集中常量：cookie 名与 Path 不散落多处（TASK-205 禁止把属性散落）。 */
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String COOKIE_PATH = "/api/auth";

    /**
     * refresh 存活 TTL（天），与 JwtUtil/AuthService 同源配置 {@code app.auth.jwt.refresh-ttl-days}
     * （默认 7，见 user-service application.yml）：cookie maxAge 与之对齐，
     * 使刷新凭证在浏览器侧时效与服务端存活键 TTL（AuthService.issueTokenPair →
     * jwtUtil.refreshTtlSeconds()）同源一致。
     */
    @Value("${app.auth.jwt.refresh-ttl-days:7}")
    long refreshTtlDays = 7;

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
     * refresh 经 HttpOnly cookie 下发（body refreshToken 置 null，不泄漏到 JS）。
     */
    @PostMapping("/login")
    public Result<TokenDTO> login(@Valid @RequestBody LoginRequestDTO dto, HttpServletRequest request,
                                  HttpServletResponse response) {
        String ip = clientIp(request);
        TokenDTO tokenDTO = authService.login(dto, ip);
        writeRefreshCookie(response, tokenDTO.getRefreshToken());
        tokenDTO.setRefreshToken(null);
        return Result.success(tokenDTO);
    }

    /**
     * 刷新（规范「Token 刷新与轮换」）：refresh 换新 access + 新 refresh；
     * 旧 refresh 原子作废（重放 → 401），已作废/过期 → 401（1001）。
     * 读值 cookie 优先、body 兜底（平滑兼容旧客户端）；成功后新 refresh 同样经 cookie 下发。
     * 无任何通道提供值时以 null/空值走 AuthService.refresh，由其既有 401 语义裁决（不新增前置校验）——
     * 因此本端点不启用 @Valid（RefreshRequestDTO.refreshToken 为 @NotBlank，而 cookie 通道下 body 常为
     * 空，validation 会抢先 400 阻断 cookie 读取）；空值语义全权交给 AuthService 既有 401 裁决。
     */
    @PostMapping("/refresh")
    public Result<TokenDTO> refresh(@RequestBody(required = false) RefreshRequestDTO dto,
                                    HttpServletRequest request, HttpServletResponse response) {
        String refresh = refreshFromCookie(request);
        if (refresh == null || refresh.isBlank()) {
            refresh = (dto != null) ? dto.getRefreshToken() : null;
        }
        TokenDTO tokenDTO = authService.refresh(refresh);
        writeRefreshCookie(response, tokenDTO.getRefreshToken());
        tokenDTO.setRefreshToken(null);
        return Result.success(tokenDTO);
    }

    /**
     * 登出（主动吊销 refresh token）：作废 refresh 存活键（此后轮换 401）。
     *
     * <p>在网关白名单 {@code /api/auth/**} 下免网关校验，服务侧自行解析 refresh token；
     * 登出幂等处理（无效/已过期 token 同样返回成功，不泄漏有效性）。
     * 读值 cookie 优先、body 兜底；随后 Set-Cookie maxAge=0 清除浏览器残留。
     * LogoutRequestDTO.refreshToken 为 @NotBlank，与 cookie 通道（body 常空）冲突，
     * 故同 refresh 不启用 @Valid，空值语义交 AuthService.logout 既有幂等处理。
     * access 15min 短窗口为声明接受的设计权衡（见 README 鉴权章节）。</p>
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody(required = false) LogoutRequestDTO dto,
                               HttpServletRequest request, HttpServletResponse response) {
        String refresh = refreshFromCookie(request);
        if (refresh == null || refresh.isBlank()) {
            refresh = (dto != null) ? dto.getRefreshToken() : null;
        }
        authService.logout(refresh);
        writeRefreshCookie(response, 0);
        return Result.success();
    }

    /**
     * 从请求 cookie 中读取 refresh_token，缺省返回 null（调用方以 body 兜底）。
     */
    private static String refreshFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (REFRESH_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /**
     * 追加 refresh cookie：值版本用于下发（maxAge=refresh 存活 TTL），
     * {@code maxAge=0} 版本用于 logout 清除（清除值传 0）。
     * httpOnly(true)/sameSite("Lax")/path 集中在此一处构造，杜绝属性散落（TASK-205）。
     */
    private void writeRefreshCookie(HttpServletResponse response, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void writeRefreshCookie(HttpServletResponse response, String value) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(TimeUnit.DAYS.toSeconds(refreshTtlDays))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
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
