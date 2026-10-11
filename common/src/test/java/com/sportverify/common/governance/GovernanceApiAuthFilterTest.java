package com.sportverify.common.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-136 第二阶段 + TASK-201：治理面签名凭证过滤器单测（纯 Mock）。
 *
 * <p>红绿判别式（任务书 §2.5）：R1 旧静态正确值 403 / R2 role=USER 403 / R3 过期 403 / R4 篡改 403 /
 * R5 伪造 X-Role+坏凭证 403 / G1 合法 ADMIN 签名三条 protected path 全放行；strict 三态与路径解析等价迁移。</p>
 */
class GovernanceApiAuthFilterTest {

    private static final String KEY = "task201-governance-hmac-key-0123456789abcdef";

    private GovernanceApiAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GovernanceApiAuthFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "expectedToken", KEY);
        ReflectionTestUtils.setField(filter, "maxSkewSeconds", 300L);
        ReflectionTestUtils.setField(filter, "protectedPaths",
                List.of("/api/appeals/**", "/rules/**", "/api/leaderboard/daily"));
        ReflectionTestUtils.setField(filter, "strictMode", false);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String adminCredential() {
        return GovernanceCredentialCodec.sign(KEY, now(), GovernanceApiHeaders.ROLE_ADMIN);
    }

    @Test
    void appealWithoutTokenRejected() throws Exception {
        assertForbidden(request("POST", "/api/appeals/1/review"));
    }

    @Test
    void rulesWithoutTokenRejected() throws Exception {
        assertForbidden(request("POST", "/rules/versions"));
    }

    @Test
    void dailyWithoutTokenRejected() throws Exception {
        assertForbidden(request("GET", "/api/leaderboard/daily"));
    }

    @Test
    void R5_forgedRoleHeaderDoesNotAuthorize() throws Exception {
        // 伪造 X-Role: ADMIN + 无凭证 → 403（服务侧不信任 X-Role 作授权依据）
        MockHttpServletRequest req = request("POST", "/api/appeals/1/review");
        req.addHeader("X-Role", "ADMIN");
        assertForbidden(req);
    }

    @Test
    void R1_legacyStaticCorrectValueRejected() throws Exception {
        // 旧静态正确值 = 配置密钥原文 → 403（无双轨期，旧静态格式在新验签下天然不可用）
        MockHttpServletRequest req = request("POST", "/rules/versions");
        req.addHeader(GovernanceApiHeaders.TOKEN, KEY);
        assertForbidden(req);
    }

    @Test
    void R2_userRoleSignedCredentialRejected() throws Exception {
        // 合法签名但 role=USER → 403（服务侧须断言 ADMIN）
        MockHttpServletRequest req = request("POST", "/api/appeals/1/review");
        req.addHeader(GovernanceApiHeaders.TOKEN, GovernanceCredentialCodec.sign(KEY, now(), "USER"));
        assertForbidden(req);
    }

    @Test
    void R3_expiredTimestampRejected() throws Exception {
        // 过期时间戳 now - maxSkew - 1 签名 → 403
        MockHttpServletRequest req = request("POST", "/api/appeals/1/review");
        req.addHeader(GovernanceApiHeaders.TOKEN,
                GovernanceCredentialCodec.sign(KEY, now() - 301, GovernanceApiHeaders.ROLE_ADMIN));
        assertForbidden(req);
    }

    @Test
    void R4_tamperedSignatureRejected() throws Exception {
        // 合法签名串篡改 sig 一字符 → 403
        String credential = adminCredential();
        char[] chars = credential.toCharArray();
        chars[chars.length - 1] = (chars[chars.length - 1] == '0') ? '1' : '0';
        MockHttpServletRequest req = request("POST", "/rules/versions");
        req.addHeader(GovernanceApiHeaders.TOKEN, new String(chars));
        assertForbidden(req);
    }

    @Test
    void wrongTokenRejected() throws Exception {
        MockHttpServletRequest req = request("POST", "/rules/versions");
        req.addHeader(GovernanceApiHeaders.TOKEN, "not-a-signed-credential");
        assertForbidden(req);
    }

    @Test
    void emptyTokenRejected() throws Exception {
        MockHttpServletRequest req = request("GET", "/api/leaderboard/daily");
        req.addHeader(GovernanceApiHeaders.TOKEN, "");
        assertForbidden(req);
    }

    @Test
    void G1_validAdminSignedPassesProtectedPaths() throws Exception {
        // 合法 ADMIN 签名凭证三条 protected path 全放行
        for (String path : List.of("/api/appeals/1/review", "/rules/versions", "/api/leaderboard/daily")) {
            MockHttpServletRequest req = request("GET", path);
            req.addHeader(GovernanceApiHeaders.TOKEN, adminCredential());
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(req, response, chain);
            assertEquals(req, chain.getRequest(), "应放行: " + path);
        }
    }

    @Test
    void overallLeaderboardNotProtected() throws Exception {
        MockHttpServletRequest req = request("GET", "/api/leaderboard");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, response, chain);
        assertEquals(req, chain.getRequest());
    }

    @Test
    void internalPathNotProtectedByGovernanceFilter() throws Exception {
        MockHttpServletRequest req = request("GET", "/internal/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, response, chain);
        assertEquals(req, chain.getRequest());
    }

    @Test
    void blankConfiguredKeyRejectsProtectedPath() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", "   ");
        assertForbidden(request("POST", "/api/appeals/1/review"));
    }

    @Test
    void weakConfiguredKeyRejectsProtectedPath() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", "short-key");
        assertForbidden(request("POST", "/api/appeals/1/review"));
    }

    @Test
    void friendLeaderboardQueryNotProtected() throws Exception {
        MockHttpServletRequest req = request("GET", "/api/leaderboard");
        req.setQueryString("type=friend&userId=1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, response, chain);
        assertEquals(req, chain.getRequest());
    }

    @Test
    void unsetKeyRejectsProtectedPathEvenWhenNonStrict() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", GovernanceApiAuthFilter.UNSET_TOKEN);
        assertForbidden(request("POST", "/api/appeals/1/review"));
    }

    @Test
    void strictTrueWithoutKeyFailsStartupWhenProtectedPathsConfigured() {
        filterRunner()
                .withPropertyValues(
                        "app.security.strict=true",
                        "app.governance.protected-paths=/api/appeals/**")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context).getFailure().hasStackTraceContaining("app.governance.token");
                });
    }

    @Test
    void strictTrueWithoutProtectedPathsDoesNotFailStartup() {
        filterRunner()
                .withPropertyValues("app.security.strict=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void strictTrueWithExplicitKeyStarts() {
        filterRunner()
                .withPropertyValues(
                        "app.security.strict=true",
                        "app.governance.token=strict-explicit-governance-token-0123456789",
                        "app.governance.protected-paths=/api/appeals/**")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void strictTrueWithWeakKeyFailsStartup() {
        filterRunner()
                .withPropertyValues(
                        "app.security.strict=true",
                        "app.governance.token=short-key",
                        "app.governance.protected-paths=/api/appeals/**")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasRootCauseInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void initSplitsCommaSeparatedProtectedPaths() {
        GovernanceApiAuthFilter fresh = new GovernanceApiAuthFilter(new ObjectMapper());
        ReflectionTestUtils.setField(fresh, "protectedPathsConfig", "/api/appeals/**, /rules/**");
        fresh.init();
        assertEquals(List.of("/api/appeals/**", "/rules/**"),
                ReflectionTestUtils.getField(fresh, "protectedPaths"));
    }

    private ApplicationContextRunner filterRunner() {
        return new ApplicationContextRunner()
                .withBean(ObjectMapper.class)
                .withUserConfiguration(GovernanceApiAuthFilter.class);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private void assertForbidden(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("1002"));
    }
}