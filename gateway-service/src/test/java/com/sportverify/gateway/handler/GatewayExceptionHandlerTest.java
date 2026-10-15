package com.sportverify.gateway.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关未捕获异常统一包装单元测试（B24，TASK-246）。
 *
 * <p>用 {@link MockServerWebExchange} 直驱 {@link GatewayExceptionHandler#handle}，
 * 覆盖 404 / 503 / 504 与未捕获通用异常，断言 HTTP 状态码与响应体
 * {@code {code,message,data}} 标准 JSON 契约、Content-Type 为 application/json;charset=UTF-8。</p>
 */
class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "/record/api/records/1").build());
    }

    private JsonNode body() throws Exception {
        String s = exchange.getResponse().getBodyAsString().block();
        return mapper.readTree(s);
    }

    /** 404：ResponseStatusException 提取 statusCode → HTTP 404 + 服务不存在文案 */
    @Test
    void handle_responseStatus404_mapsToNotFound() throws Exception {
        handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND)).block();

        assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        JsonNode node = body();
        assertEquals(404, node.get("code").asInt());
        assertEquals("服务或接口不存在", node.get("message").asText());
        assertTrue(node.get("data").isNull());
    }

    /** 503：下游不可达 ConnectException → HTTP 503 + 服务暂时不可用 */
    @Test
    void handle_connectException_mapsTo503() throws Exception {
        handler.handle(exchange, new ConnectException("connection refused")).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        JsonNode node = body();
        assertEquals(503, node.get("code").asInt());
        assertEquals("服务暂时不可用，请稍后重试", node.get("message").asText());
        assertTrue(node.get("data").isNull());
    }

    /** 503：ConnectException 被异常包装链包裹（模拟 reactor 包装）也应识别根因 */
    @Test
    void handle_wrappedConnectException_mapsTo503() throws Exception {
        Throwable wrapped = new RuntimeException(
                "xxx", new IllegalStateException("wrap", new ConnectException("down")));
        handler.handle(exchange, wrapped).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        assertEquals(503, body().get("code").asInt());
    }

    /** 504：超时 TimeoutException → HTTP 504 + 服务响应超时 */
    @Test
    void handle_timeoutException_mapsTo504() throws Exception {
        handler.handle(exchange, new TimeoutException("read timed out")).block();

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exchange.getResponse().getStatusCode());
        JsonNode node = body();
        assertEquals(504, node.get("code").asInt());
        assertEquals("服务响应超时，请稍后重试", node.get("message").asText());
        assertTrue(node.get("data").isNull());
    }

    /** 通用未捕获异常 → HTTP 500 + 系统繁忙兜底 */
    @Test
    void handle_genericException_mapsTo500() throws Exception {
        handler.handle(exchange, new IllegalStateException("boom")).block();

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exchange.getResponse().getStatusCode());
        JsonNode node = body();
        assertEquals(500, node.get("code").asInt());
        assertEquals("系统繁忙，请稍后重试", node.get("message").asText());
        assertTrue(node.get("data").isNull());
    }

    /** 契约：Content-Type 恒为 application/json;charset=UTF-8 */
    @Test
    void handle_contentTypeIsUtf8Json() {
        handler.handle(exchange, new RuntimeException("x")).block();

        String ct = exchange.getResponse().getHeaders().getContentType().toString();
        assertTrue(ct.toLowerCase(java.util.Locale.ROOT).startsWith("application/json"),
                "必须输出 JSON 而非 Spring 默认 HTML，实测=" + ct);
        assertTrue(ct.toLowerCase(java.util.Locale.ROOT).contains("charset=utf-8"),
                "必须 UTF-8 编码，实测=" + ct);
    }

    /** 优先级：gateway 全局异常处理器必须高于 Spring 默认（-1 前置） */
    @Test
    void order_isNegativeOne() {
        assertEquals(-1, handler.getOrder());
    }
}