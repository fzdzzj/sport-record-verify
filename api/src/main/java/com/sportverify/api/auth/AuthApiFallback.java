package com.sportverify.api.auth;

import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.RefreshRequestDTO;
import com.sportverify.api.auth.dto.RegisterRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * AuthApi Feign「显式失败」工厂（add-resilience-hardening）。
 *
 * <p><b>为何不可软降级</b>：注册/登录/刷新若在依赖故障时返回伪造成功或伪造 token，
 * 会造成错误身份与安全事故。统一抛 {@code USER_SERVICE_UNAVAILABLE(4006)}。</p>
 */
@Slf4j
@Component
public class AuthApiFallback implements FallbackFactory<AuthApi> {

    @Override
    public AuthApi create(Throwable cause) {
        return new AuthApi() {
            @Override
            public Result<Void> register(RegisterRequestDTO dto) {
                throw fail("register", cause);
            }

            @Override
            public Result<TokenDTO> login(LoginRequestDTO dto) {
                throw fail("login", cause);
            }

            @Override
            public Result<TokenDTO> refresh(RefreshRequestDTO dto) {
                throw fail("refresh", cause);
            }
        };
    }

    private static BizException fail(String op, Throwable cause) {
        log.warn("AuthApi 依赖不可用（不可软降级）：op={}, cause={}",
                op, cause == null ? "unknown" : cause.toString());
        return new BizException(ResultCode.USER_SERVICE_UNAVAILABLE, "认证依赖不可用：" + op);
    }
}
