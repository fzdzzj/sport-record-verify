package com.sportverify.gateway.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 治理面路径「配置 ↔ 过滤器行为」一致性断言。
 *
 * <p>{@link AuthGlobalFilterTest} 用 {@code ReflectionTestUtils} 把 {@code adminPaths}
 * <b>硬编码</b>注入，所以它只能证明"命中治理面路径会被拦"，证明不了
 * {@code application.yml} 里到底配了哪些路径——yml 少写一条，它照样全绿。
 * 本类反过来：从 classpath 上的 {@code application.yml} 读出真实配置灌进过滤器，
 * 再用过滤器自己的匹配语义判定，于是"配置漂移"当场变红。</p>
 *
 * <p>当前守的这条：榜单每日报表（外部路径 {@code /leaderboard/api/leaderboard/daily}，
 * 网关 StripPrefix=1 后转发到 leaderboard-service 的 {@code /api/leaderboard/daily}）
 * 返回某日全平台用户里程排行，属治理面数据，只允许 ADMIN 读。</p>
 */
class LeaderboardDailyAdminOnlyTest {

    private static final String SECRET = "sport-verify-hs256-secret-key-0123456789abcdef";

    /** 网关外部路径（路由 Path=/leaderboard/** + StripPrefix=1，见 gateway application.yml） */
    private static final String DAILY_PATH = "/leaderboard/api/leaderboard/daily";

    private static String yml() throws IOException {
        return new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /** 取 after 之后首个 key 行的值（逗号分隔列表）；after 用于消歧，例如 admin: 下的 paths: */
    private static List<String> ymlValue(String key, String after) throws IOException {
        String content = yml();
        int anchor = after == null ? 0 : content.indexOf(after);
        assertTrue(anchor >= 0, "配置锚点缺失：" + after);
        int k = content.indexOf(key, anchor);
        assertTrue(k >= 0, key + " 应存在于 " + after + " 之下");
        int end = content.indexOf('\n', k);
        String value = content.substring(k + key.length(), end < 0 ? content.length() : end);
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> adminPaths() throws IOException {
        return ymlValue("paths:", "admin:");
    }

    private static List<String> whitelist() throws IOException {
        return ymlValue("whitelist:", null);
    }

    private AuthGlobalFilter filterWith(List<String> admin, List<String> white) {
        AuthGlobalFilter filter = new AuthGlobalFilter(new JwtTokenParser(SECRET));
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "whitelist", white);
        ReflectionTestUtils.setField(filter, "adminPaths", admin);
        ReflectionTestUtils.setField(filter, "adminRoleCheckEnabled", true);
        return filter;
    }

    private String token(long userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().subject(String.valueOf(userId)).claim("type", "ACCESS").claim("role", role)
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    private MockServerWebExchange run(AuthGlobalFilter filter, String role) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(DAILY_PATH)
                .header("Authorization", "Bearer " + token(7L, role)));
        filter.filter(exchange, e -> Mono.empty()).block();
        return exchange;
    }

    @Test
    void deployedAdminPathsCoverDailyReportAndKeepTheOldOnes() throws IOException {
        List<String> admin = adminPaths();
        AuthGlobalFilter filter = filterWith(admin, whitelist());
        Boolean covered = ReflectionTestUtils.invokeMethod(filter, "isAdminPath", DAILY_PATH);
        assertEquals(Boolean.TRUE, covered,
                "榜单每日报表属治理面，必须被 app.auth.admin.paths 覆盖，实际配置=" + admin);
        // 防"追加时手滑整行覆盖"：原有两条治理面路径必须还在
        assertTrue(admin.contains("/admin/**"), "/admin/** 不得被替换掉，实际=" + admin);
        assertTrue(admin.contains("/verify/rules/**"), "/verify/rules/** 不得被替换掉，实际=" + admin);
        // 防"顺手加进白名单"：白名单会整条绕过鉴权，绝不能出现榜单路径
        assertTrue(whitelist().stream().noneMatch(p -> p.contains("leaderboard")),
                "榜单接口不得进白名单，实际=" + whitelist());
    }

    @Test
    void normalUserGets403OnDailyReportWithDeployedConfig() throws IOException {
        AuthGlobalFilter filter = filterWith(adminPaths(), whitelist());
        assertEquals(HttpStatus.FORBIDDEN, run(filter, "USER").getResponse().getStatusCode(),
                "普通用户能读到全平台某日里程排行＝数据越权");
    }

    @Test
    void adminPassesDailyReportWithDeployedConfig() throws IOException {
        AuthGlobalFilter filter = filterWith(adminPaths(), whitelist());
        MockServerWebExchange exchange = run(filter, "ADMIN");
        assertNull(exchange.getResponse().getStatusCode(), "ADMIN 应放行（网关不写终态）");
        assertNotNull(exchange.getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_ROLE));
    }
}
