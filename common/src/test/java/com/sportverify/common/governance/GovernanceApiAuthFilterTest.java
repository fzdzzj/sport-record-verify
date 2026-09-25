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
 * TASK-136 第二阶段：治理面专用凭证过滤器单测（纯 Mock）。
 */
class GovernanceApiAuthFilterTest {

    private static final String TOKEN = "explicit-governance-token-for-tests";

    private GovernanceApiAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new GovernanceApiAuthFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "expectedToken", TOKEN);
        ReflectionTestUtils.setField(filter, "protectedPaths",
                List.of("/api/appeals/**", "/rules/**", "/api/leaderboard/daily"));
        ReflectionTestUtils.setField(filter, "strictMode", false);
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
    void forgedRoleHeaderDoesNotAuthorize() throws Exception {
        MockHttpServletRequest req = request("POST", "/api/appeals/1/review");
        req.addHeader("X-Role", "ADMIN");
        assertForbidden(req);
    }

    @Test
    void wrongTokenRejected() throws Exception {
        MockHttpServletRequest req = request("POST", "/rules/versions");
        req.addHeader(GovernanceApiHeaders.TOKEN, "wrong-token");
        assertForbidden(req);
    }

    @Test
    void emptyTokenRejected() throws Exception {
        MockHttpServletRequest req = request("GET", "/api/leaderboard/daily");
        req.addHeader(GovernanceApiHeaders.TOKEN, "");
        assertForbidden(req);
    }

    @Test
    void validTokenPassesProtectedPaths() throws Exception {
        for (String path : List.of("/api/appeals/1/review", "/rules/versions", "/api/leaderboard/daily")) {
            MockHttpServletRequest req = request("GET", path);
            req.addHeader(GovernanceApiHeaders.TOKEN, TOKEN);
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
    void blankConfiguredTokenRejectsProtectedPath() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", "   ");
        assertForbidden(request("POST", "/api/appeals/1/review"));
    }

    @Test
    void friendLeaderboardQueryNotProtected() throws Exception {
        // 总榜/好友榜同走 GET /api/leaderboard?type=...，不得因 daily 精确保护被误伤
        MockHttpServletRequest req = request("GET", "/api/leaderboard");
        req.setQueryString("type=friend&userId=1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, response, chain);
        assertEquals(req, chain.getRequest());
    }
    @Test
    void unsetTokenRejectsProtectedPathEvenWhenNonStrict() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", GovernanceApiAuthFilter.UNSET_TOKEN);
        assertForbidden(request("POST", "/api/appeals/1/review"));
    }

    @Test
    void strictTrueWithoutTokenFailsStartupWhenProtectedPathsConfigured() {
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
        // user/record 等服务也会扫描到本过滤器，但无受保护路径时不得因缺治理凭证启动失败
        filterRunner()
                .withPropertyValues("app.security.strict=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void strictTrueWithExplicitTokenStarts() {
        filterRunner()
                .withPropertyValues(
                        "app.security.strict=true",
                        "app.governance.token=strict-explicit-governance-token-0123456789",
                        "app.governance.protected-paths=/api/appeals/**")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void initSplitsCommaSeparatedProtectedPaths() {
        // @Value 对 List 不做逗号切分：真实 yml 的 protected-paths 必须经 init 自行解析，否则永不命中
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
