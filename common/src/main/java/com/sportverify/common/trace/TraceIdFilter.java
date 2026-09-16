package com.sportverify.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 各 MVC 服务公共过滤器：X-Request-Id → MDC{@code traceId}，响应回写同一头，finally 清理。
 *
 * <p>经 {@code scanBasePackages=com.sportverify} 自动注册；网关为 WebFlux，不走本过滤器。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = TraceIds.resolveOrCreate(request.getHeader(TraceIds.HEADER));
        TraceIds.put(traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceIds.clear();
        }
    }
}
