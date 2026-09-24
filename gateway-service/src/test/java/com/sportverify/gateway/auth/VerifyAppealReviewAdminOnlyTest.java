package com.sportverify.gateway.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-136 第一阶段：申诉复核治理面别名的「代码默认值 ↔ 实际 YAML ↔ 过滤器行为」断言。
 *
 * <p>别名仍由现有 /verify/** 路由转发到 verify-service；本阶段只把别名纳入网关治理面准入，
 * 不改变服务侧凭证机制、JWT 语义，也不覆盖服务直连风险。</p>
 */
class VerifyAppealReviewAdminOnlyTest {

    private static final String SECRET = "sport-verify-hs256-secret-key-0123456789abcdef";
    private static final String ALIAS_PATH = "/verify/api/appeals/1/review";
    private static final String ALIAS_PATTERN = "/verify/api/appeals/**";
    private static final List<String> REQUIRED_ADMIN_PATHS =
            List.of("/admin/**", "/verify/rules/**", ALIAS_PATTERN);

    private static String yml() throws IOException {
        return new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /** 读取 app.auth.admin.paths 的实际部署值，而不是复制一份硬编码列表。 */
    private static List<String> deployedAdminPaths() throws IOException {
        String content = yml();
        int anchor = content.indexOf("    admin:");
        assertTrue(anchor >= 0, "application.yml 缺少 app.auth.admin 配置段");
        int key = content.indexOf("      paths:", anchor);
        assertTrue(key >= 0, "application.yml 缺少 app.auth.admin.paths");
        int end = content.indexOf('\n', key);
        String value = content.substring(key + "      paths:".length(), end < 0 ? content.length() : end);
        return Arrays.stream(value.trim().split(","))
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .toList();
    }

    /** 读取 AuthGlobalFilter @Value 的 fallback，约束代码默认路径本身不能漏掉别名。 */
    private static List<String> codeDefaultAdminPaths() throws NoSuchFieldException {
        Field field = AuthGlobalFilter.class.getDeclaredField("adminPaths");
        Value value = field.getAnnotation(Value.class);
        assertTrue(value != null, "adminPaths 必须保留 @Value 配置入口");
        String expression = value.value();
        int start = expression.indexOf(':') + 1;
        int end = expression.lastIndexOf('}');
        assertTrue(start > 0 && end > start, "无法解析 adminPaths 的 @Value fallback：" + expression);
        return Arrays.stream(expression.substring(start, end).split(","))
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .toList();
    }

    private static void assertGovernancePathsPresent(List<String> actual, String source) {
        for (String required : REQUIRED_ADMIN_PATHS) {
            assertTrue(actual.contains(required), source + " 缺少治理面路径 " + required + "，实际=" + actual);
        }
    }

    private AuthGlobalFilter filterWithDeployedConfig() throws IOException {
        AuthGlobalFilter filter = new AuthGlobalFilter(new JwtTokenParser(SECRET));
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "whitelist", List.of("/api/auth/**", "/actuator/health"));
        ReflectionTestUtils.setField(filter, "adminPaths", deployedAdminPaths());
        ReflectionTestUtils.setField(filter, "adminRoleCheckEnabled", true);
        return filter;
    }

    private static String token(long userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "ACCESS")
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private static MockServerWebExchange run(AuthGlobalFilter filter, String path, String role,
                                             AtomicReference<ServerWebExchange> captured) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path)
                        .header("Authorization", "Bearer " + token(1L, role)));
        filter.filter(exchange, request -> {
            captured.set(request);
            return Mono.empty();
        }).block();
        return exchange;
    }

    @org.junit.jupiter.api.Test
    void codeDefaultAndDeployedYamlBothKeepAliasAndExistingGovernancePaths() throws Exception {
        assertGovernancePathsPresent(codeDefaultAdminPaths(), "AuthGlobalFilter @Value 默认值");
        List<String> deployed = deployedAdminPaths();
        assertGovernancePathsPresent(deployed, "application.yml app.auth.admin.paths");

        String content = yml();
        assertTrue(Pattern.compile("(?s)- id: route-verify-service.*?predicates:\\s+- Path=/verify/\\*\\*")
                        .matcher(content).find(),
                "现有 /verify/** 路由不得被别名治理改动");
        assertTrue(Pattern.compile("(?s)- id: route-admin-service.*?predicates:\\s+- Path=/admin/\\*\\*")
                        .matcher(content).find(),
                "现有 /admin/** 路由不得被别名治理改动");
    }

    @org.junit.jupiter.api.Test
    void deployedConfigRejectsUserOnAlias() throws IOException {
        MockServerWebExchange exchange = run(filterWithDeployedConfig(), ALIAS_PATH, "USER", new AtomicReference<>());
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(),
                "USER JWT 访问申诉复核别名必须在网关治理面被拒绝");
    }

    @org.junit.jupiter.api.Test
    void deployedConfigAllowsAdminOnAlias() throws IOException {
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        MockServerWebExchange exchange = run(filterWithDeployedConfig(), ALIAS_PATH, "ADMIN", captured);
        assertNull(exchange.getResponse().getStatusCode(), "ADMIN 应放行（网关不写终态）");
        assertEquals("ADMIN", captured.get().getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_ROLE));
    }

    @org.junit.jupiter.api.Test
    void deployedConfigKeepsExistingAdminAndRuleVersionAdmission() throws IOException {
        AuthGlobalFilter filter = filterWithDeployedConfig();
        for (String path : List.of("/admin/api/appeals/1/review", "/verify/rules/versions")) {
            assertEquals(HttpStatus.FORBIDDEN,
                    run(filter, path, "USER", new AtomicReference<>()).getResponse().getStatusCode(),
                    "USER 对既有治理面路径的准入不得放宽：" + path);
            assertNull(run(filter, path, "ADMIN", new AtomicReference<>()).getResponse().getStatusCode(),
                    "ADMIN 对既有治理面路径应保持放行：" + path);
        }
    }
}
