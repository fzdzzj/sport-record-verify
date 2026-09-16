package com.sportverify.common.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
