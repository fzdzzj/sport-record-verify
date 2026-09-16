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
