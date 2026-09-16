package com.sportverify.common.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
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

    /** 共享密钥：生产必须经 INTERNAL_API_TOKEN 注入，默认值仅本地演示 */
    @Value("${app.internal.token:${INTERNAL_API_TOKEN:local-demo-internal-token}}")
    private String expectedToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!authEnabled || !isInternalPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(InternalApiHeaders.TOKEN);
        if (expectedToken != null && expectedToken.equals(provided)) {
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
