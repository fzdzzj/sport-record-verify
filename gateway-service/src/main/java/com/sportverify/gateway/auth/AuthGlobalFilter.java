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

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 网关统一鉴权过滤器（WebFlux 响应式，非 Servlet Filter；见 ADR-0007）。
 *
 * <p>安全边界：网关是唯一外部入口，「身份由网关认定」——</p>
 * <ul>
 *   <li><b>校验</b>：Authorization Bearer 解析 access token；缺失/无效 → 401/1001；</li>
 *   <li><b>透传</b>：注入并覆盖 X-User-Id / X-Role；</li>
 *   <li><b>白名单</b>：/api/auth/**、/actuator/health 放行；无 /internal 路由；</li>
 *   <li><b>治理面角色</b>：/admin/**、/verify/rules/**、/verify/api/appeals/**、日报路径要求 ADMIN；</li>
 *   <li><b>治理面专用凭证</b>（TASK-136 第二阶段）：所有入站分支先清除客户端自带的
 *       X-Gateway-Governance-Token；仅当 auth.enabled、JWT 有效、role=ADMIN、admin.enabled、
 *       专用凭证均有效时向治理目标注入。任一前提不成立时治理目标失败关闭。普通路径不注入。
 *       独立于 X-Internal-Token；专用令牌非签名，持有者可复用是剩余风险。</li>
 *   <li><b>降级</b>：auth.enabled=false 时非治理路径透传；治理路径仍失败关闭。</li>
 * </ul>
 *
 * <p>透传路径剥离外部 X-User-Id/X-Role（add-auth-degrade-header-strip）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtTokenParser jwtTokenParser;

    /** 鉴权降级开关：false=旧行为（透传不校验），true=强制 Bearer 鉴权 + X-User-Id 注入 */
    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    /** 白名单路径前缀原文（逗号分隔；/** 通配到该前缀下的所有子路径）。
     *  @Value 对 List<String> 不做逗号切分（生产上下文无 beanFactory ConversionService），须在 init 自行解析 */
    @Value("${app.auth.whitelist:/api/auth/**,/actuator/health}")
    private String whitelistConfig;

    /** 解析后的白名单；单测可直填 */
    private List<String> whitelist = List.of();

    /** 治理面角色校验开关：false=管理端降级为裸放行（灰度观察），true=要求 role=ADMIN */
    @Value("${app.auth.admin.enabled:true}")
    private boolean adminRoleCheckEnabled;

    /** 治理面路径前缀原文（逗号分隔）：命中即要求 role=ADMIN */
    @Value("${app.auth.admin.paths:/admin/**,/verify/rules/**,/verify/api/appeals/**,/leaderboard/api/leaderboard/daily}")
    private String adminPathsConfig;

    /** 解析后的治理面路径；单测可直填 */
    private List<String> adminPaths = List.of();

    /** 密钥治理开关（strict）：true 时缺治理凭证启动失败 */
    @Value("${app.security.strict:false}")
    private boolean strictMode;

    /** 网关向治理面注入的专用凭证。生产必须经 GOVERNANCE_TOKEN 注入，无演示默认值。 */
    @Value("${app.governance.token:${GOVERNANCE_TOKEN:}}")
    private String governanceToken;

    /** 鉴权请求头 */
    private static final String HEADER_AUTHORIZATION = "Authorization";
    /** Bearer 前缀（大小写不敏感） */
    private static final String BEARER_PREFIX = "Bearer ";
    /** 下游透传头：userId（网关唯一注入方） */
    public static final String HEADER_USER_ID = "X-User-Id";
    /** 下游透传头：role（治理面准入依据，网关唯一注入方） */
    public static final String HEADER_ROLE = "X-Role";
    /** 网关治理面专用凭证头（独立于 X-Internal-Token，不复用 Feign 内部 token） */
    public static final String HEADER_GOVERNANCE_TOKEN = "X-Gateway-Governance-Token";
    /** 管理员角色值 */
    private static final String ROLE_ADMIN = "ADMIN";

    /** 哨兵：未配置治理凭证时的值 */
    private static final String UNSET_GOV_TOKEN = "__GOVERNANCE_TOKEN_UNSET__";

    @PostConstruct
    void init() {
        // @Value 注入 List<String> 在生产上下文不按逗号切分（CustomCollectionEditor 整串单元素），
        // 白名单/治理路径必须自行解析，否则 yml 配置永不命中
        whitelist = splitPatterns(whitelistConfig);
        adminPaths = splitPatterns(adminPathsConfig);
        if (strictMode && !isValidGovernanceToken(governanceToken)) {
            throw new IllegalStateException(
                    "app.security.strict=true 但 app.governance.token 未显式注入，网关拒绝启动");
        }
    }

    /** 逗号分隔 → 模式列表：去空白、丢空段 */
    private static List<String> splitPatterns(String config) {
        if (config == null || config.isBlank()) {
            return List.of();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 所有入站分支先清除客户端自带的治理凭证头，防止外部伪造
        ServerHttpRequest cleanedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.remove(HEADER_GOVERNANCE_TOKEN))
                .build();
        exchange = exchange.mutate().request(cleanedRequest).build();

        // 治理面路径：任一前提不成立即失败关闭（不走降级/白名单放行）
        if (isAdminPath(path)) {
            if (!authEnabled) {
                log.warn("治理面请求因 auth.enabled=false 失败关闭：path={}", path);
                return reject(exchange, HttpStatus.FORBIDDEN, "{\"code\":1002,\"message\":\"无权限访问\"}");
            }
            if (!adminRoleCheckEnabled) {
                log.warn("治理面请求因 admin.enabled=false 失败关闭：path={}", path);
                return reject(exchange, HttpStatus.FORBIDDEN, "{\"code\":1002,\"message\":\"无权限访问\"}");
            }
            // 继续走 JWT + role 校验；后续还会检查凭证配置
        }

        // —— 降级开关关闭 / 命中白名单：透传（旧行为 / 发 token 端点 / 内部探活）
        if (!authEnabled || isWhitelisted(path)) {
            return chain.filter(stripIdentityHeaders(exchange));
        }

        // —— 提取并校验 Bearer token
        String authorization = exchange.getRequest().getHeaders().getFirst(HEADER_AUTHORIZATION);
        JwtTokenParser.TokenIdentity identity;
        try {
            identity = parseBearerIdentity(authorization);
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("网关鉴权拒绝：path={}, reason={}", path, e.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "{\"code\":1001,\"message\":\"token 无效或过期\"}");
        }

        // —— 治理面角色校验
        if (adminRoleCheckEnabled && isAdminPath(path) && !ROLE_ADMIN.equals(identity.role())) {
            log.warn("网关治理面越权拒绝：path={}, userId={}, role={}", path, identity.userId(), identity.role());
            return reject(exchange, HttpStatus.FORBIDDEN, "{\"code\":1002,\"message\":\"无权限访问\"}");
        }

        // 治理面路径：检查网关自身治理凭证是否有效（专用密钥有效）
        if (isAdminPath(path)) {
            if (!isValidGovernanceToken(governanceToken)) {
                log.warn("网关治理凭证未配置或无效，治理目标失败关闭：path={}", path);
                return reject(exchange, HttpStatus.FORBIDDEN, "{\"code\":1002,\"message\":\"治理配置缺失\"}");
            }
        }

        // —— 注入 X-User-Id / X-Role + 仅对治理面 ADMIN 注入治理专用凭证
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(HEADER_USER_ID, String.valueOf(identity.userId()));
                    headers.set(HEADER_ROLE, identity.role());
                    if (isAdminPath(path) && ROLE_ADMIN.equals(identity.role())) {
                        headers.set(HEADER_GOVERNANCE_TOKEN, governanceToken);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isValidGovernanceToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (UNSET_GOV_TOKEN.equals(token)) {
            return false;
        }
        // 允许任何非空非哨兵值；生产应使用强随机
        return true;
    }

    private ServerWebExchange stripIdentityHeaders(ServerWebExchange exchange) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_ROLE);
                    // 治理凭证头已在入口统一清除，此处不再重复
                })
                .build();
        return exchange.mutate().request(mutated).build();
    }

    private JwtTokenParser.TokenIdentity parseBearerIdentity(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw new IllegalArgumentException("Authorization 头缺失或非 Bearer 格式");
        }
        return jwtTokenParser.parseIdentity(authorization.substring(BEARER_PREFIX.length()));
    }

    private boolean isWhitelisted(String path) {
        return matchesPrefixList(whitelist, path);
    }

    private boolean isAdminPath(String path) {
        return matchesPrefixList(adminPaths, path);
    }

    private boolean matchesPrefixList(List<String> patterns, String path) {
        if (patterns == null) return false;
        for (String pattern : patterns) {
            if (pattern.endsWith("/**")) {
                String base = pattern.substring(0, pattern.length() - 3);
                if (path.equals(base) || path.startsWith(base + "/")) {
                    return true;
                }
            } else if (path.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
