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
 *
 * <p>另覆盖透传路径的身份头边界（add-auth-degrade-header-strip）：降级开关分支与白名单分支
 * 在放行前剥离外部携带的 X-User-Id/X-Role（不校验不等于放开身份），而鉴权分支仍为覆盖式注入。</p>
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
        ReflectionTestUtils.setField(filter, "adminPaths", List.of("/admin/**", "/verify/rules/**", "/verify/api/appeals/**", "/leaderboard/api/leaderboard/daily"));
        ReflectionTestUtils.setField(filter, "adminRoleCheckEnabled", true);
        ReflectionTestUtils.setField(filter, "governanceToken", "test-governance-token");
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
    void userRoleVerifyAppealAliasReturns403() {
        MockServerWebExchange exchange = run("/verify/api/appeals/1/review", "Bearer " + token(3L, "USER"));
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
    void adminCheckDisabledGovernanceFailsClosed() {
        // TASK-136 第二阶段：admin.enabled=false 时治理目标失败关闭，不再裸放行
        ReflectionTestUtils.setField(filter, "adminRoleCheckEnabled", false);
        MockServerWebExchange exchange = run("/admin/api/appeals/1/review", "Bearer " + token(6L, "USER"));
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void authDisabledGovernanceFailsClosed() {
        ReflectionTestUtils.setField(filter, "authEnabled", false);
        MockServerWebExchange exchange = run("/admin/api/appeals/1/review", "Bearer " + token(6L, "ADMIN"));
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void adminJwtInjectsGovernanceTokenAndStripsClientForgery() {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange = runCapturedWithIdentity(
                "/admin/api/appeals/1/review",
                "Bearer " + token(9L, "ADMIN"),
                null,
                null,
                captured,
                "forged-client-governance-token");
        assertEquals(null, exchange.getResponse().getStatusCode());
        assertEquals("9", captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_USER_ID));
        assertEquals("ADMIN", captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_ROLE));
        assertEquals("test-governance-token",
                captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_GOVERNANCE_TOKEN));
    }

    @Test
    void userPathDoesNotInjectGovernanceToken() {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange = runCaptured("/verify/1/submit", "Bearer " + token(5L, "USER"), captured);
        assertEquals(null, exchange.getResponse().getStatusCode());
        assertEquals(null, captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_GOVERNANCE_TOKEN));
    }

    @Test
    void userPathForgedGovernanceTokenStripped() {
        // 入口剥离对所有分支生效：普通用户路径即使自带伪造治理凭证，下游也必须见不到
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange = runCapturedWithIdentity(
                "/verify/1/submit",
                "Bearer " + token(5L, "USER"),
                null,
                null,
                captured,
                "forged-client-governance-token");
        assertEquals(null, exchange.getResponse().getStatusCode());
        assertEquals(null, captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_GOVERNANCE_TOKEN));
    }

    @Test
    void initSplitsCommaSeparatedPathLists() {
        // @Value 对 List 不做逗号切分：生产 yml 的白名单/治理路径必须经 init 自行解析，否则永不命中
        AuthGlobalFilter fresh = new AuthGlobalFilter(parser);
        ReflectionTestUtils.setField(fresh, "whitelistConfig", "/api/auth/**, /actuator/health");
        ReflectionTestUtils.setField(fresh, "adminPathsConfig",
                "/admin/**, ,/verify/rules/**,/verify/api/appeals/**,/leaderboard/api/leaderboard/daily");
        fresh.init();
        assertEquals(List.of("/api/auth/**", "/actuator/health"), ReflectionTestUtils.getField(fresh, "whitelist"));
        assertEquals(
                List.of("/admin/**", "/verify/rules/**", "/verify/api/appeals/**", "/leaderboard/api/leaderboard/daily"),
                ReflectionTestUtils.getField(fresh, "adminPaths"));
    }

    @Test
    void missingGovernanceTokenFailsClosedOnAdminPath() {
        ReflectionTestUtils.setField(filter, "governanceToken", "");
        MockServerWebExchange exchange = run("/verify/rules/versions", "Bearer " + token(1L, "ADMIN"));
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void degradeDisabledStripsForeignIdentityHeaders() {
        // 降级开关（默认 false）不是「无边界」：外部自带的身份头必须在透传前被剥离
        ReflectionTestUtils.setField(filter, "authEnabled", false);
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange = runCapturedWithIdentity("/verify/1/submit", null, "999", "ADMIN", captured);
        assertEquals(null, exchange.getResponse().getStatusCode());
        assertEquals(null, captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_USER_ID));
        assertEquals(null, captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_ROLE));
    }

    @Test
    void whitelistedPathStripsForeignIdentityHeaders() {
        // 白名单（发 token 的端点 / 探针）同样不与身份头共存：放行的是请求，不是身份
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange = runCapturedWithIdentity("/api/auth/login", null, "999", "ADMIN", captured);
        assertEquals(null, exchange.getResponse().getStatusCode());
        assertEquals(null, captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_USER_ID));
        assertEquals(null, captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_ROLE));
    }

    @Test
    void forgedIdentityOnAuthedPathIsOverridden() {
        // 鉴权路径维持覆盖式注入：伪造头不得胜过 token 解析值（剥离逻辑不作用于该分支）
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange =
                runCapturedWithIdentity("/verify/1/submit", "Bearer " + token(7L, "USER"), "999", "ADMIN", captured);
        assertEquals(null, exchange.getResponse().getStatusCode());
        assertEquals("7", captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_USER_ID));
        assertEquals("USER", captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_ROLE));
    }

    /** 执行过滤并把传入链的 exchange 记录下来（用于断言注入/剥离的头）；允许附加外部伪造身份头 */
    private MockServerWebExchange runCapturedWithIdentity(String path, String authorization,
                                                          String forgedUserId, String forgedRole,
                                                          AtomicReference<ServerWebExchange> captured) {
        return runCapturedWithIdentity(path, authorization, forgedUserId, forgedRole, captured, null);
    }

    private MockServerWebExchange runCapturedWithIdentity(String path, String authorization,
                                                          String forgedUserId, String forgedRole,
                                                          AtomicReference<ServerWebExchange> captured,
                                                          String forgedGovernanceToken) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        if (forgedUserId != null) {
            builder.header(AuthGlobalFilter.HEADER_USER_ID, forgedUserId);
        }
        if (forgedRole != null) {
            builder.header(AuthGlobalFilter.HEADER_ROLE, forgedRole);
        }
        if (forgedGovernanceToken != null) {
            builder.header(AuthGlobalFilter.HEADER_GOVERNANCE_TOKEN, forgedGovernanceToken);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder);
        filter.filter(exchange, e -> {
            captured.set(e);
            return Mono.empty();
        }).block();
        return exchange;
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
