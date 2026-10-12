package com.sportverify.user.auth.controller;

import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.LogoutRequestDTO;
import com.sportverify.api.auth.dto.RefreshRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.result.Result;
import com.sportverify.user.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthController cookie 下发通道单元测试（TASK-205，纯 JVM Mockito + MockHttpServletRequest/Response）。
 *
 * <p>不启 Spring 上下文、无网络/中间件：仅验证控制器层对 refresh 凭证的 cookie 化包装——
 * 红绿判别式见任务书 §2.5：R1a body 置 null + Set-Cookie 属性 / R1b cookie 优先 / R1c logout 清除 /
 * R1d body 兜底。AuthService 以 mock 打桩，本轮换/吊销语义由 AuthServiceTest 守护（服务层零改动）。</p>
 */
class AuthControllerTest {

    private AuthService authService;
    private AuthController controller;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        controller = new AuthController(authService);
        // 默认 @Value("${app.auth.jwt.refresh-ttl-days:7}") → 7 天 = 604800 秒
        controller.refreshTtlDays = 7;
        response = new MockHttpServletResponse();
    }

    private MockHttpServletRequest cookieRequest(String cookieName, String value) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new jakarta.servlet.http.Cookie[]{new jakarta.servlet.http.Cookie(cookieName, value)});
        return req;
    }

    private static TokenDTO tokenDTO(String refresh) {
        TokenDTO dto = new TokenDTO();
        dto.setAccessToken("access-abc");
        dto.setRefreshToken(refresh);
        dto.setRole("USER");
        return dto;
    }

    private static String setCookieHeader(MockHttpServletResponse resp) {
        return resp.getHeader("Set-Cookie");
    }

    // ==================== R1a 红：login 成功 → Set-Cookie 属性 + body 置 null ====================

    @Test
    void R1a_login_setsRefreshCookie_andClearsBodyRefresh() {
        LoginRequestDTO login = new LoginRequestDTO();
        login.setPhone("13800138000");
        login.setPassword("secret123");
        when(authService.login(any(), any())).thenReturn(tokenDTO("new-refresh-token"));

        Result<TokenDTO> result = controller.login(login, new MockHttpServletRequest(), response);

        // body 不泄漏 refresh
        assertNotNull(result);
        assertNotNull(result.getData());
        assertEquals("access-abc", result.getData().getAccessToken());
        assertNull(result.getData().getRefreshToken(), "body 的 refreshToken 应置 null");

        // Set-Cookie 属性齐全
        String setCookie = setCookieHeader(response);
        assertNotNull(setCookie, "响应应带 Set-Cookie");
        assertTrue(setCookie.contains("refresh_token=new-refresh-token"), "cookie 名与值");
        assertTrue(setCookie.contains("HttpOnly"), "HttpOnly");
        assertTrue(setCookie.contains("SameSite=Lax"), "SameSite=Lax");
        assertTrue(setCookie.contains("Path=/api/auth"), "Path=/api/auth");
        assertTrue(setCookie.contains("Max-Age=604800"), "maxAge 应对齐 refresh 存活 TTL 7 天(604800s)");
    }

    // ==================== R1b 红：refresh cookie 优先（body 携异值/空） ====================

    @Test
    void R1b_refresh_usesCookieValue_whenBodyDiffers() {
        RefreshRequestDTO dto = new RefreshRequestDTO();
        dto.setRefreshToken("body-value");
        MockHttpServletRequest req = cookieRequest("refresh_token", "cookie-value");
        when(authService.refresh("cookie-value")).thenReturn(tokenDTO("rotated-cookie"));

        Result<TokenDTO> result = controller.refresh(dto, req, response);

        verify(authService).refresh(eq("cookie-value"));
        assertNull(result.getData().getRefreshToken(), "body 置 null");
        String setCookie = setCookieHeader(response);
        assertTrue(setCookie.contains("refresh_token=rotated-cookie"), "应下发新 refresh cookie");
        assertTrue(setCookie.contains("Max-Age=604800"));
    }

    // ==================== R1c 红：logout cookie 优先 + Set-Cookie maxAge=0 清除 ====================

    @Test
    void R1c_logout_usesCookieValue_andClearsCookie() {
        LogoutRequestDTO dto = new LogoutRequestDTO();
        dto.setRefreshToken("body-value");
        MockHttpServletRequest req = cookieRequest("refresh_token", "logout-cookie-value");

        controller.logout(dto, req, response);

        verify(authService).logout("logout-cookie-value");
        String setCookie = setCookieHeader(response);
        assertNotNull(setCookie, "logout 应清 cookie");
        assertTrue(setCookie.contains("refresh_token="), "同名 cookie");
        assertTrue(setCookie.contains("Max-Age=0"), "maxAge=0 清除残留");
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("Path=/api/auth"));
    }

    // ==================== R1d 红：无 cookie 时 body 兜底 ====================

    @Test
    void R1d_refresh_bodyFallback_whenNoCookie() {
        RefreshRequestDTO dto = new RefreshRequestDTO();
        dto.setRefreshToken("body-only");
        when(authService.refresh("body-only")).thenReturn(tokenDTO("rotated-body"));

        Result<TokenDTO> result = controller.refresh(dto, new MockHttpServletRequest(), response);

        verify(authService).refresh(eq("body-only"));
        assertNull(result.getData().getRefreshToken());
    }

    @Test
    void R1d_logout_bodyFallback_whenNoCookie() {
        LogoutRequestDTO dto = new LogoutRequestDTO();
        dto.setRefreshToken("logout-body-only");

        controller.logout(dto, new MockHttpServletRequest(), response);

        verify(authService).logout("logout-body-only");
        assertTrue(setCookieHeader(response).contains("Max-Age=0"));
    }
}
