package com.sportverify.common.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口共享密钥过滤器：命中 {@code /internal/**} 时校验 {@link InternalApiHeaders#TOKEN}。
 *
 * <p>默认开启；密钥经 {@code app.internal.token} / 环境变量 {@code INTERNAL_API_TOKEN} 注入，
 * 本地演示默认值不可用于生产。网关无 {@code /internal} 路由，本过滤器保护的是服务本地直连面。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class InternalApiAuthFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    /** 是否启用内部接口密钥校验（灰度可关；生产应保持 true） */
    @Value("${app.internal.auth-enabled:true}")
    private boolean authEnabled;

    /** strict 判别用的演示默认串（与下方 @Value 兜底字面量保持一致） */
    static final String DEMO_TOKEN = "local-demo-internal-token";

    /** 共享密钥：生产必须经 INTERNAL_API_TOKEN 注入，默认值仅本地演示 */
    @Value("${app.internal.token:${INTERNAL_API_TOKEN:" + DEMO_TOKEN + "}}")
    private String expectedToken;

    /** 密钥治理开关（add-strict-secret-fail-fast）：true=密钥项缺失（回落演示默认）即启动失败 */
    @Value("${app.security.strict:false}")
    private boolean strictMode;

    /**
     * fail-fast（strict 模式）：密钥未显式注入（解析为 null 或等于演示默认串）时拒绝启动，
     * 杜绝生产把公开仓库里的字面值当密钥静默运行；默认（strict=false）不做任何事，零扰动。
     */
    @PostConstruct
    void failFastOnMissingSecret() {
        if (strictMode && (expectedToken == null || DEMO_TOKEN.equals(expectedToken))) {
            throw new IllegalStateException(
                    "app.security.strict=true 但 app.internal.token 未显式注入（回落演示默认值），拒绝启动");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!authEnabled || !isInternalPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(InternalApiHeaders.TOKEN);
        // 常量时间比较（MessageDigest.isEqual）：防计时探测；判定语义与等值比较等价
        boolean tokenMatches = expectedToken != null && provided != null
                && MessageDigest.isEqual(
                        expectedToken.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8));
        if (tokenMatches) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("内部接口密钥校验失败：path={}, hasHeader={}", request.getRequestURI(), provided != null);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.failure(ResultCode.FORBIDDEN));
    }

    static boolean isInternalPath(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        // 兼容 context-path / 末尾斜杠：/internal、/internal/、/internal/auth/grant-admin
        return "/internal".equals(uri) || uri.startsWith("/internal/");
    }
}
