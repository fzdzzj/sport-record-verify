package com.sportverify.api.auth;

import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.RefreshRequestDTO;
import com.sportverify.api.auth.dto.RegisterRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * user-service 认证契约（auth-api，接口与实现分离）。
 *
 * <p>注册/登录/刷新三接口：对外由网关白名单放行到 {@code /api/auth/**}（AuthController），
 * 本契约实现位于 user-service 的 InternalAuthController（{@code /internal} 前缀），
 * 供内部服务按 Feign 契约复用同一认证域（与 UserApi/InternalUserController 同款约定）。</p>
 *
 * <p>鉴权闭环（见 ADR-0007）：认证端点本身不携带 token（注册/登录/刷新均无鉴权），
 * 业务接口的 token 校验统一由网关 GlobalFilter 承担，下游不自行校验 token。</p>
 *
 * <p>contextId 说明：UserApi 与 AuthApi 同指 user-service，OpenFeign 按 contextId 区分
 * 同名客户端的注册上下文，否则会因重复的 FeignClientSpecification bean 导致启动失败。</p>
 */
@FeignClient(name = "user-service", contextId = "authApi", path = "/internal")
public interface AuthApi {

    /**
     * 注册：BCrypt 哈希存密码；手机号唯一，重复 → 2001。
     */
    @PostMapping("/auth/register")
    Result<Void> register(@RequestBody RegisterRequestDTO dto);

    /**
     * 登录：校验密码，成功签发 access + refresh token；失败 → 401 并计失败次数。
     */
    @PostMapping("/auth/login")
    Result<TokenDTO> login(@RequestBody LoginRequestDTO dto);

    /**
     * 刷新：凭 refresh token 换新 access + 新 refresh（轮换，旧 refresh 作废）；已作废/过期 → 401（1001）。
     */
    @PostMapping("/auth/refresh")
    Result<TokenDTO> refresh(@RequestBody RefreshRequestDTO dto);
}
