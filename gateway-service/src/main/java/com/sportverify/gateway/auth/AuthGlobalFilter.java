package com.sportverify.gateway.auth;

import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关统一鉴权过滤器（WebFlux 响应式，非 Servlet Filter；见 ADR-0007）。
 *
 * <p>安全边界：网关是唯一外部入口，「身份由网关认定」——</p>
 * <ul>
 *   <li><b>校验</b>：Authorization: Bearer 头解析 access token（签名/时效/type=ACCESS），
 *       缺失或无效 → HTTP 401 + body 1001，不放行至下游；</li>
 *   <li><b>透传</b>：解析出的 userId 以 {@code X-User-Id} 头注入并<b>覆盖</b>同名头
 *       （外部伪造的同名头被冲掉，下游只信任网关注入值——内网信任边界）；</li>
 *   <li><b>白名单</b>：/api/auth/**（发 token 的端点）、/internal/**（服务间 Feign）、
 *       /actuator/**（健康探针）、/admin/**（管理端暂不接鉴权，保持现状）直接放行；</li>
 *   <li><b>降级开关</b>：{@code app.auth.enabled=false}（默认）时整体透传不校验，
 *       兼容本地调试与压测脚本的旧行为（显式携带 userId）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtTokenParser jwtTokenParser;

    /** 鉴权降级开关：false=旧行为（透传不校验），true=强制 Bearer 鉴权 + X-User-Id 注入 */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 白名单路径前缀（逗号分隔；/** 通配到该前缀下的所有子路径） */
    @Value("${app.auth.whitelist:/api/auth/**,/internal/**,/actuator/**,/admin/**}")
    private List<String> whitelist;

    /** 鉴权请求头 */
    private static final String HEADER_AUTHORIZATION = "Authorization";
    /** Bearer 前缀（大小写不敏感） */
    private static final String BEARER_PREFIX = "Bearer ";
    /** 下游透传头：userId（网关唯一注入方，下游不信任外部同名头） */
    public static final String HEADER_USER_ID = "X-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // —— 降级开关关闭 / 命中白名单：透传（旧行为 / 发 token 端点 / 内部探活）
        if (!authEnabled || isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        // —— 提取并校验 Bearer token：缺失或无效 → 401（1001），不放行至下游
        String authorization = exchange.getRequest().getHeaders().getFirst(HEADER_AUTHORIZATION);
        Long userId;
        try {
            userId = parseBearerUserId(authorization);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("网关鉴权拒绝：path={}, reason={}", path, e.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "{\"code\":1001,\"message\":\"token 无效或过期\"}");
        }

        // —— 注入 X-User-Id（覆盖同名头，防外部伪造；见 ADR-0007「内网信任边界」）后透传下游
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER_USER_ID, String.valueOf(userId)))
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** 解析 Bearer token；非 Bearer 格式直接判无效（不尝试解析，防畸形输入） */
    private Long parseBearerUserId(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new IllegalArgumentException("Authorization 头缺失或非 Bearer 格式");
        }
        return jwtTokenParser.parseUserId(authorization.substring(BEARER_PREFIX.length()));
    }

    /** 白名单匹配：/xxx/** 前缀通配 + 精确匹配 */
    private boolean isWhitelisted(String path) {
        for (String pattern : whitelist) {
            if (pattern.endsWith("/**")) {
                String base = pattern.substring(0, pattern.length() - 3); // 去掉 /**
                if (path.equals(base) || path.startsWith(base + "/")) {
                    return true;
                }
            } else if (path.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** 直接写响应（WebFlux 无 Servlet API，用 bufferFactory + writeWith） */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** 尽早执行（负数优先级）：在路由过滤器之前先完成身份认定 */
    @Override
    public int getOrder() {
        return -100;
    }
}
