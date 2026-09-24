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
 *   <li><b>透传</b>：解析出的 userId 以 {@code X-User-Id}、role 以 {@code X-Role} 头注入并<b>覆盖</b>
 *       同名头（外部伪造的同名头被冲掉，下游只信任网关注入值——内网信任边界）；</li>
 *   <li><b>白名单</b>：/api/auth/**（发 token 的端点）、/actuator/health（健康探针，精确匹配）
 *       直接放行，actuator 其余端点走鉴权分支；
 *       已删除无路由的 /internal/** 死配置（服务间 Feign 不经网关）；</li>
 *   <li><b>治理面角色校验</b>（add-admin-rbac）：/admin/**、规则版本接口（/verify/rules/**）与申诉复核别名（/verify/api/appeals/**）
 *       移除白名单裸放行，改为「进入过滤链 + 校验 role=ADMIN」——普通用户 403（1002）、
 *       未登录 401（1001）、ADMIN 放行；可配开关 {@code app.auth.admin.enabled} 保留灰度；</li>
 *   <li><b>降级开关</b>：{@code app.auth.enabled=false}（默认）时整体透传不校验，
 *       兼容本地调试与压测脚本的旧行为（显式携带 userId）。</li>
 * </ul>
 *
 * <p><b>透传路径的身份头边界</b>（add-auth-degrade-header-strip）：降级开关分支与白名单分支
 * 在放行前<b>剥离</b>外部携带的 {@code X-User-Id}/{@code X-Role}——「不校验」只关掉鉴权判定，
 * 不等于把身份认定权交给调用方。否则默认 {@code app.auth.enabled=false} 下（网关 8080 是唯一对外端口），
 * 任意调用方自带 {@code X-User-Id} 即可冒充他人。鉴权开启分支不受影响，仍为覆盖式注入。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtTokenParser jwtTokenParser;

    /** 鉴权降级开关：false=旧行为（透传不校验），true=强制 Bearer 鉴权 + X-User-Id 注入 */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 白名单路径前缀（逗号分隔；/** 通配到该前缀下的所有子路径）：
     *  管理端 /admin/** 与规则版本接口已移除（纳入角色校验，见治理面鉴权） */
    @Value("${app.auth.whitelist:/api/auth/**,/actuator/health}")
    private List<String> whitelist;

    /** 治理面角色校验开关：false=管理端降级为裸放行（灰度观察），true=要求 role=ADMIN */
    @Value("${app.auth.admin.enabled:true}")
    private boolean adminRoleCheckEnabled;

    /** 治理面路径前缀（逗号分隔）：命中即要求 role=ADMIN；包含 /verify/rules/** 与 /verify/api/appeals/** 外部别名 */
    @Value("${app.auth.admin.paths:/admin/**,/verify/rules/**,/verify/api/appeals/**}")
    private List<String> adminPaths;

    /** 鉴权请求头 */
    private static final String HEADER_AUTHORIZATION = "Authorization";
    /** Bearer 前缀（大小写不敏感） */
    private static final String BEARER_PREFIX = "Bearer ";
    /** 下游透传头：userId（网关唯一注入方，下游不信任外部同名头） */
    public static final String HEADER_USER_ID = "X-User-Id";
    /** 下游透传头：role（治理面准入依据，网关唯一注入方；add-admin-rbac 见 ADR-0007） */
    public static final String HEADER_ROLE = "X-Role";
    /** 管理员角色值（与 user-service User.ROLE_ADMIN 对齐）：治理面接口准入的唯一角色 */
    private static final String ROLE_ADMIN = "ADMIN";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // —— 降级开关关闭 / 命中白名单：透传（旧行为 / 发 token 端点 / 内部探活）
        //    边界：透传同样清洗外部身份头——不校验不等于放开身份（add-auth-degrade-header-strip）
        if (!authEnabled || isWhitelisted(path)) {
            return chain.filter(stripIdentityHeaders(exchange));
        }

        // —— 提取并校验 Bearer token：缺失或无效 → 401（1001），不放行至下游
        String authorization = exchange.getRequest().getHeaders().getFirst(HEADER_AUTHORIZATION);
        JwtTokenParser.TokenIdentity identity;
        try {
            identity = parseBearerIdentity(authorization);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("网关鉴权拒绝：path={}, reason={}", path, e.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "{\"code\":1001,\"message\":\"token 无效或过期\"}");
        }

        // —— 治理面角色校验（add-admin-rbac）：命中治理面路径但非 ADMIN → 403（1002）
        //    /admin/**、规则版本接口与申诉复核别名已从白名单移除，走到这里必是有效 token，剩余只判角色
        if (adminRoleCheckEnabled && isAdminPath(path) && !ROLE_ADMIN.equals(identity.role())) {
            log.warn("网关治理面越权拒绝：path={}, userId={}, role={}", path, identity.userId(), identity.role());
            return reject(exchange, HttpStatus.FORBIDDEN, "{\"code\":1002,\"message\":\"无权限访问\"}");
        }

        // —— 注入 X-User-Id / X-Role（覆盖同名头，防外部伪造；见 ADR-0007「内网信任边界」）后透传下游
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(HEADER_USER_ID, String.valueOf(identity.userId()));
                    headers.set(HEADER_ROLE, identity.role());
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 剥离外部携带的身份头后透传（降级开关分支与白名单分支共用）。
     *
     * <p>下游按「{@code X-User-Id}/{@code X-Role} 是网关唯一注入值」取值，而这两条分支不做注入，
     * 故必须把外部同名头删干净：否则调用方自带 {@code X-User-Id} 就能冒充任意用户。
     * 与鉴权分支的 {@code headers.set} 覆盖式注入互补，二者都保证「下游见到的身份只能是网关注入的」。</p>
     */
    private ServerWebExchange stripIdentityHeaders(ServerWebExchange exchange) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_ROLE);
                })
                .build();
        return exchange.mutate().request(mutated).build();
    }

    /** 解析 Bearer token 的身份；非 Bearer 格式直接判无效（不尝试解析，防畸形输入） */
    private JwtTokenParser.TokenIdentity parseBearerIdentity(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new IllegalArgumentException("Authorization 头缺失或非 Bearer 格式");
        }
        return jwtTokenParser.parseIdentity(authorization.substring(BEARER_PREFIX.length()));
    }

    /** 白名单匹配：/xxx/** 前缀通配 + 精确匹配 */
    private boolean isWhitelisted(String path) {
        return matchesPrefixList(whitelist, path);
    }

    /** 治理面路径匹配：命中即要求 role=ADMIN（add-admin-rbac，见 ADR-0007） */
    private boolean isAdminPath(String path) {
        return matchesPrefixList(adminPaths, path);
    }

    /** 通用前缀通配匹配（/xxx/** 前缀通配 + 精确匹配），供白名单与治理面路径共用 */
    private boolean matchesPrefixList(List<String> patterns, String path) {
        for (String pattern : patterns) {
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
