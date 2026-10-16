package com.sportverify.gateway.trace;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RequestIdGlobalFilter 单元测试（MockServerWebExchange，无真实网关容器）。
 */
class RequestIdGlobalFilterTest {

    private final RequestIdGlobalFilter filter = new RequestIdGlobalFilter();

    @Test
    void resolveOrCreate_blank_generates() {
        assertNotNull(RequestIdGlobalFilter.resolveOrCreate(null));
        assertFalse(RequestIdGlobalFilter.resolveOrCreate(" ").isBlank());
    }

    /** E28（TASK-256）：合法既有形态沿用（镜像 common TraceIds 同规则） */
    @Test
    void resolveOrCreate_valid_reused() {
        String hex32 = "0123456789abcdef0123456789abcdef";
        assertEquals(hex32, RequestIdGlobalFilter.resolveOrCreate(hex32));
        assertEquals("client-1", RequestIdGlobalFilter.resolveOrCreate(" client-1 "));
    }

    /** E28：边界 64/1 合规沿用 */
    @Test
    void resolveOrCreate_boundary64And1_reused() {
        String len64 = "a".repeat(64);
        assertEquals(len64, RequestIdGlobalFilter.resolveOrCreate(len64));
        assertEquals("a", RequestIdGlobalFilter.resolveOrCreate("a"));
    }

    /** E28：超长（65）/非法字符（空格/中文/控制）重新生成 */
    @Test
    void resolveOrCreate_invalidOrTooLong_regenerate() {
        String tooLong = "x".repeat(65);
        assertFalse(tooLong.equals(RequestIdGlobalFilter.resolveOrCreate(tooLong)),
                "65 字符超长 ID 应重新生成");
        assertNotEquals("abc def", RequestIdGlobalFilter.resolveOrCreate("abc def"));
        assertNotEquals("中文id", RequestIdGlobalFilter.resolveOrCreate("中文id"));
        assertNotEquals("a\nb", RequestIdGlobalFilter.resolveOrCreate("a\nb"));
    }

    @Test
    void filter_missingHeader_generatesAndPropagates() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "/record/api/x").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(captor.capture());
        String downstream = captor.getValue().getRequest().getHeaders().getFirst(RequestIdGlobalFilter.HEADER);
        String response = exchange.getResponse().getHeaders().getFirst(RequestIdGlobalFilter.HEADER);
        assertNotNull(downstream);
        assertFalse(downstream.isBlank());
        assertEquals(downstream, response);
    }

    @Test
    void filter_incomingHeader_reused() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, "/x")
                        .header(RequestIdGlobalFilter.HEADER, "client-fixed")
                        .build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(captor.capture());
        assertEquals("client-fixed",
                captor.getValue().getRequest().getHeaders().getFirst(RequestIdGlobalFilter.HEADER));
        assertEquals("client-fixed",
                exchange.getResponse().getHeaders().getFirst(RequestIdGlobalFilter.HEADER));
    }

    @Test
    void order_beforeAuthFilter() {
        // AuthGlobalFilter = -100；本过滤器必须更早
        assertEquals(-200, filter.getOrder());
    }
}
