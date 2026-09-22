package com.sportverify.common.internal;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部接口共享密钥过滤器单测（纯 Mock，不启 Spring 容器）。
 */
class InternalApiAuthFilterTest {

    private InternalApiAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalApiAuthFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "expectedToken", "local-demo-internal-token");
    }

    @Test
    void grantAdminWithoutTokenRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/auth/grant-admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("1002"));
    }

    @Test
    void grantAdminWithTokenPasses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/auth/grant-admin");
        request.addHeader(InternalApiHeaders.TOKEN, "local-demo-internal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
    }

    @Test
    void nonInternalPathSkipped() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(request, chain.getRequest());
    }

    @Test
    void isInternalPathHelpers() {
        assertTrue(InternalApiAuthFilter.isInternalPath("/internal"));
        assertTrue(InternalApiAuthFilter.isInternalPath("/internal/"));
        assertTrue(InternalApiAuthFilter.isInternalPath("/internal/auth/grant-admin"));
        assertEquals(false, InternalApiAuthFilter.isInternalPath("/user/internal/health"));
        assertEquals(false, InternalApiAuthFilter.isInternalPath("/api/auth/login"));
    }

    /** strict 判别式的最小上下文装配：只注册过滤器与它构造所需的 ObjectMapper */
    private ApplicationContextRunner filterRunner() {
        return new ApplicationContextRunner()
                .withBean(ObjectMapper.class)
                .withUserConfiguration(InternalApiAuthFilter.class);
    }

    @Test
    void strictTrueWithoutTokenFailsStartup() {
        // strict=true 且不注入 app.internal.token → 解析回落演示默认串 → 启动必须失败（fail-fast）
        filterRunner()
                .withPropertyValues("app.security.strict=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context).getFailure().hasStackTraceContaining("app.security.strict=true");
                });
    }

    @Test
    void strictTrueWithExplicitTokenStarts() {
        // 反向绿：strict=true 但密钥已显式注入 → 正常启动
        filterRunner()
                .withPropertyValues("app.security.strict=true",
                        "app.internal.token=strict-explicit-token-0123456789abcdef")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void strictAbsentKeepsDemoDefaultBehavior() {
        // 零扰动：不开 strict → 演示默认值原样可用，上下文正常启动（现状行为）
        filterRunner()
                .run(context -> assertThat(context).hasNotFailed());
    }
}
