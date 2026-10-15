package com.sportverify.gateway.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 网关未捕获异常统一标准包装（B24，TASK-246）。
 *
 * <p>实现 {@link ErrorWebExceptionHandler} 且 {@code getOrder()=-1}（高于 Spring 默认
 * {@code DefaultErrorWebExceptionHandler} 的 {@code Ordered.LOWEST_PRECEDENCE}），拦截所有
 * 未被过滤链捕获的异常，统一输出与业务服务一致的 {@code Result.failure(code,message)} 契约
 * {@code {"code":status,"message":msg,"data":null}}，Content-Type 为
 * {@code application/json;charset=UTF-8}，终结网关返回默认 HTML 错误页或非标 JSON 导致的
 * 前端拦截器崩溃。</p>
 *
 * <p>网关零 common 依赖（见 ADR/任务书模块边界），本类不引用
 * {@code com.sportverify.common.result.Result}，以 Jackson 序列化同形 JSON 体，契约字段
 * {@code code/message/data} 与业务侧逐字对齐。</p>
 */
@Slf4j
@Component
public class GatewayExceptionHandler implements ErrorWebExceptionHandler, Ordered {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = "系统繁忙，请稍后重试";

        if (ex instanceof ResponseStatusException rse) {
            // 路由不存在等由 Spring 抛出的带状态码异常：提取其 HTTP 状态码
            int raw = rse.getStatusCode().value();
            status = resolveStatus(raw);
            message = raw == HttpStatus.NOT_FOUND.value()
                    ? "服务或接口不存在"
                    : (rse.getReason() != null && !rse.getReason().isBlank()
                        ? rse.getReason() : status.getReasonPhrase());
        } else if (ex instanceof ConnectException
                || isCauseOf(ex, ConnectException.class)) {
            // 下游服务不可达：503
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "服务暂时不可用，请稍后重试";
        } else if (ex instanceof TimeoutException
                || isCauseOf(ex, TimeoutException.class)) {
            // 网关转发/下游超时：504
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "服务响应超时，请稍后重试";
        }

        log.warn("网关未捕获异常：path={}, status={}, cause={} {}",
                exchange.getRequest().getPath().value(), status.value(),
                ex.getClass().getSimpleName(), ex.getMessage());
        return writeJson(exchange, status, message);
    }

    /** 对包装类（如 reactor 的 Exceptions.wrap 外层）逐层剥解，识别内层根因类型 */
    private boolean isCauseOf(Throwable t, Class<? extends Throwable> target) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 32) {
            if (target.isInstance(cur)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 将非标状态码规范化到合法 HTTP 状态（兜底 500） */
    private HttpStatus resolveStatus(int raw) {
        try {
            return HttpStatus.resolve(raw) == null ? HttpStatus.INTERNAL_SERVER_ERROR
                    : HttpStatus.resolve(raw);
        } catch (Exception e) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    private Mono<Void> writeJson(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(new IllegalStateException(
                    "响应已提交，无法输出标准异常包装：status=" + status.value()));
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.parseMediaType("application/json;charset=UTF-8"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        body.put("data", null);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            log.warn("异常响应序列化失败，回退文本契约：status={}", status.value(), e);
            bytes = ("{\"code\":" + status.value() + ",\"message\":\"" + message
                    + "\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 高于 Spring 默认异常处理（DefaultErrorWebExceptionHandler = LOWEST_PRECEDENCE）
        return -1;
    }
}