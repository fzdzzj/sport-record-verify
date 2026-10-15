package com.sportverify.gateway;

import com.sportverify.gateway.auth.AuthGlobalFilter;
import com.sportverify.gateway.auth.JwtTokenParser;
import com.sportverify.gateway.trace.RequestIdGlobalFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关生产部署姿态演练测试（TASK-247 / 候选 H2，MEDIUM）。
 *
 * <p>离线可跑：复用 {@link GatewayProbeTest} 的懒初始化范式（{@code lazy-initialization=true} +
 * {@code nacos discovery fail-fast=false}），不连接真实 Nacos/Redis。</p>
 *
 * <p>四块断言：</p>
 * <ul>
 *   <li><b>R1</b> CORS 生产多域名动态注入：注入逗号分隔多域名（{@code CORS_ALLOWED_ORIGINS}）后，
 *       合法 Origin 的真实 HTTP 预检 OPTIONS 获得对应 {@code Access-Control-Allow-Origin} 与
 *       {@code Access-Control-Allow-Credentials: true}；恶意 Origin（{@code https://attacker.com}）
 *       拿不到 CORS 头（预检由 CorsWebFilter 短路响应，不触发路由，无后端依赖离线稳定）。</li>
 *   <li><b>R2</b> 反向代理 TLS 终止与协议感知：资源文件断言 application.yml 声明
 *       {@code forward-headers-strategy: framework}；并直驱 {@link ForwardedHeaderTransformer}
 *       （框架策略等价适配器）验证 X-Forwarded-Proto/Host/Port 下请求 URI scheme 识别为 https。</li>
 *   <li><b>R3</b> 跨域 Cookie 凭证透传：携带 {@code Cookie: refresh_token=...} 的请求经网关全局
 *       过滤器链（RequestIdGlobalFilter → AuthGlobalFilter 白名单透传）后，下游请求头完整保留
 *       该 Cookie 凭证（网关路由/过滤链不剥离、不丢弃）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.main.lazy-initialization=true",
        "spring.cloud.nacos.discovery.fail-fast=false",
        // 生产多域名动态注入（对应 compose/services 与 env.example 的 CORS_ALLOWED_ORIGINS）
        "CORS_ALLOWED_ORIGINS=https://sports.example.com,https://admin.example.com"})
class GatewayProductionPostureTest {

    @LocalServerPort
    private int port;

    private static final String ALLOWED_ORIGIN_1 = "https://sports.example.com";
    private static final String ALLOWED_ORIGIN_2 = "https://admin.example.com";
    private static final String MALICIOUS_ORIGIN = "https://attacker.com";
    private static final String MOCK_REFRESH_COOKIE = "refresh_token=mock_refresh_token_value";

    // ============ R1：CORS 生产多域名动态注入（真实网关上下文 + 真实 HTTP 预检） ============

    /** 注入逗号分隔多域名后，两个合法 Origin 的预检 OPTIONS 均回显对应 ACAO 且 ACAC=true。 */
    @Test
    void corsAllowedOriginsPreflightEchoOrigin() {
        assertPreflightEchoesOrigin(ALLOWED_ORIGIN_1);
        assertPreflightEchoesOrigin(ALLOWED_ORIGIN_2);
    }

    private void assertPreflightEchoesOrigin(String origin) {
        http().options().uri("/user/x")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    /** 恶意/未授权 Origin 的预检不得拿到任何 CORS 响应头（跨域凭证不被透出）。 */
    @Test
    void corsRejectsMaliciousOrigin() {
        http().options().uri("/user/x")
                .header(HttpHeaders.ORIGIN, MALICIOUS_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    private WebTestClient http() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    // ============ R2：反向代理 TLS 终止与协议感知 ============

    /** 资源文件断言：application.yml 的 server 节点声明 forward-headers-strategy: framework。 */
    @Test
    void applicationYmlDeclaresForwardHeadersFramework() throws IOException, URISyntaxException {
        var url = getClass().getClassLoader().getResource("application.yml");
        assertNotNull(url, "application.yml should be on classpath for test");
        String content = Files.readString(Paths.get(url.toURI()));
        assertTrue(content.contains("server:"), "server 配置存在");
        assertTrue(content.contains("forward-headers-strategy: framework"),
                "server.forward-headers-strategy 必须为 framework（生产反代 TLS 终止协同）");
        assertTrue(content.contains("port: 8080"), "server.port 保持 8080");
    }

    /** R2 行为断言：X-Forwarded-Proto/Host/Port 下请求 URI scheme 识别为 https。 */
    @Test
    void forwardedHeadersFlipSchemeToHttps() {
        ServerHttpRequest request = MockServerHttpRequest.get("/api/auth/refresh")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "sports.example.com")
                .header("X-Forwarded-Port", "443")
                .build();
        ServerHttpRequest adapted = new ForwardedHeaderTransformer().apply(request);

        URI uri = adapted.getURI();
        assertEquals("https", uri.getScheme(), "客户端真实协议应被识别为 https");
        assertEquals("sports.example.com", uri.getHost(), "X-Forwarded-Host 应被采纳");
    }

    // ============ R3：跨域 Cookie 凭证在网关全局过滤器链中无损透传 ============

    /** 携带 Cookie: refresh_token=... 的请求经网关全局过滤链后下游完整保留该凭证。 */
    @Test
    void refreshCookieSurvivesGlobalFilterChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "/api/auth/refresh")
                        .header(HttpHeaders.COOKIE, MOCK_REFRESH_COOKIE)
                        .build());
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        GatewayFilterChain terminal = mock(GatewayFilterChain.class);
        when(terminal.filter(any())).thenReturn(Mono.empty());

        // RequestIdGlobalFilter（-200）→ AuthGlobalFilter（-100）：/api/auth/** 白名单透传分支。
        new RequestIdGlobalFilter().filter(exchange,
                downstream -> new AuthGlobalFilter(new JwtTokenParser(
                        "sport-verify-hs256-secret-key-0123456789abcdef"))
                        .filter(downstream, terminal)).block();

        verify(terminal).filter(captor.capture());
        ServerHttpRequest downstream = captor.getValue().getRequest();
        assertEquals(MOCK_REFRESH_COOKIE, downstream.getHeaders().getFirst(HttpHeaders.COOKIE),
                "网关全局过滤链不得剥离/丢弃 Cookie: refresh_token 凭证头");
    }
}