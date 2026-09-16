package com.sportverify.gateway.trace;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关请求 ID 过滤器（WebFlux GlobalFilter，非 Servlet Filter）。
 *
 * <p>读取入站 {@code X-Request-Id}，缺失则生成；覆盖写入下游请求头与响应头。
 * 与各服务 common {@code TraceIds} 约定同一头名/MDC 键；网关不依赖 common
 * （避免 spring-boot-starter-web 与 Gateway WebFlux 冲突）。</p>
 * <p>优先级高于鉴权过滤器（-100），使 401/403 拒绝响应也带上同一请求 ID。</p>
 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    /** 与 common TraceIds.HEADER 保持一致 */
    public static final String HEADER = "X-Request-Id";
    /** 与 common TraceIds.MDC_KEY 保持一致 */
    public static final String MDC_KEY = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String traceId = resolveOrCreate(incoming);
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, traceId))
                .build();
        exchange.getResponse().getHeaders().set(HEADER, traceId);
        // 尽力写入 MDC（WebFlux 线程切换下不保证全程；头透传才是网关硬保证）
        MDC.put(MDC_KEY, traceId);
        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signalType -> MDC.remove(MDC_KEY));
    }

    static String resolveOrCreate(String incoming) {
        if (incoming == null) {
            return newId();
        }
        String trimmed = incoming.trim();
        return trimmed.isEmpty() ? newId() : trimmed;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public int getOrder() {
        return -200;
    }
}
