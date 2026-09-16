package com.sportverify.gateway.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AuthGlobalFilter 单元测试（治理面角色校验：add-admin-rbac，见 ADR-0007）。
 *
 * <p>用 spring-test 的 {@link MockServerWebExchange} + 内联 {@link GatewayFilterChain}，
 * 无需启动网关即可验证：未登录 401（1001）、普通用户访问治理面 403（1002）、
 * ADMIN 放行并注入 X-User-Id/X-Role、灰度降级（admin.enabled=false）放行。</p>
 */
class AuthGlobalFilterTest {

    private static final String SECRET = "sport-verify-hs256-secret-key-0123456789abcdef";

    private JwtTokenParser parser = new JwtTokenParser(SECRET);
    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthGlobalFilter(parser);
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "whitelist",
                List.of("/api/auth/**", "/actuator/**"));
        ReflectionTestUtils.setField(filter, "adminPaths", List.of("/admin/**", "/verify/rules/**"));
        ReflectionTestUtils.setField(filter, "adminRoleCheckEnabled", true);
    }

    /** 签发带指定 role 的 access token（JDK 默认密钥，与配置一致） */
    private static String token(long userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "ACCESS")
                .claim("role", role)
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 请求治理面路径并执行过滤；无 token 场景不带头 */
    private MockServerWebExchange run(String path, String authorization) {
        MockServerWebExchange exchange = buildExchange(path, authorization);
        filter.filter(exchange, e -> Mono.empty()).block();
        return exchange;
    }

    /** 构造请求（from(BaseBuilder) 内部完成 build，避免中间类型把具体 Builder 上抛为 ServerHttpRequest.Builder） */
    private MockServerWebExchange buildExchange(String path, String authorization) {
        if (authorization == null) {
            return MockServerWebExchange.from(MockServerHttpRequest.get(path));
        }
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).header("Authorization", authorization));
    }

    @Test
    void noTokenAdminPathReturns401() {
        MockServerWebExchange exchange = run("/admin/api/appeals/1/review", null);
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void userRoleAdminPathReturns403() {
        MockServerWebExchange exchange = run("/admin/api/appeals/1/review", "Bearer " + token(1L, "USER"));
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void adminRoleAdminPathPasses() {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange = runCaptured("/admin/api/appeals/1/review", "Bearer " + token(2L, "ADMIN"), captured);
        // 放行即不改响应（未提交状态码），并注入身份头：业务面认 X-User-Id，治理面可认 X-Role
        assertEquals(null, exchange.getResponse().getStatusCode());
        assertEquals("2", captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_USER_ID));
        assertEquals("ADMIN", captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_ROLE));
    }

    @Test
    void userRoleRuleVersionPathReturns403() {
        MockServerWebExchange exchange = run("/verify/rules/versions", "Bearer " + token(3L, "USER"));
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void adminRoleRuleVersionPathPasses() {
        MockServerWebExchange exchange = run("/verify/rules/versions", "Bearer " + token(4L, "ADMIN"));
        assertEquals(null, exchange.getResponse().getStatusCode());
    }

    @Test
    void userRoleBusinessPathPasses() {
        // 业务面不受治理面角色校验影响：普通用户访问 /verify/** 非规则接口仍放行
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange = runCaptured("/verify/1/submit", "Bearer " + token(5L, "USER"), captured);
        assertEquals(null, exchange.getResponse().getStatusCode());
        assertEquals("USER", captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_ROLE));
    }

    @Test
    void whitelistedApiAuthNoTokenPasses() {
        // 发 token 的端点始终放行（无 token 也能打到 /api/auth/login）：不判角色、不改响应
        MockServerWebExchange exchange = run("/api/auth/login", null);
        assertEquals(null, exchange.getResponse().getStatusCode());
    }

    @Test
    void adminCheckDisabledUserPasses() {
        // 灰度降级：admin.enabled=false 时管理端退化为透传（不判角色），身份头照常注入
        ReflectionTestUtils.setField(filter, "adminRoleCheckEnabled", false);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange = runCaptured("/admin/api/appeals/1/review", "Bearer " + token(6L, "USER"), captured);
        assertEquals(null, exchange.getResponse().getStatusCode());
        assertEquals("USER", captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_ROLE));
    }

    /** 执行过滤并把传入链的 exchange 记录下来（用于断言注入的头） */
    private MockServerWebExchange runCaptured(String path, String authorization, AtomicReference<ServerWebExchange> captured) {
        MockServerWebExchange exchange = buildExchange(path, authorization);
        filter.filter(exchange, e -> {
            captured.set(e);
            return Mono.empty();
        }).block();
        return exchange;
    }
}