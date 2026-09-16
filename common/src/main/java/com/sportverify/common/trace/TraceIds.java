package com.sportverify.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 请求贯穿标识常量与 MDC 辅助（最小实现，非 Sleuth/Zipkin）。
 *
 * <p>HTTP 头与 MQ userProperty 共用 {@link #HEADER}；日志 MDC 键为 {@link #MDC_KEY}（pattern：{@code %X{traceId}}）。</p>
 */
public final class TraceIds {

    /** HTTP 请求/响应头与 RocketMQ userProperty 键 */
    public static final String HEADER = "X-Request-Id";

    /** SLF4J MDC 键，与 logging.pattern 中 %X{traceId} 对齐 */
    public static final String MDC_KEY = "traceId";

    private TraceIds() {
    }

    /** 生成新的 traceId（UUID 去横线，缩短日志宽度） */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 沿用非空入站 ID，否则新生成。
     *
     * @param incoming 请求头或消息属性中的原始值，可为 null/空白
     */
    public static String resolveOrCreate(String incoming) {
        if (incoming == null) {
            return newId();
        }
        String trimmed = incoming.trim();
        return trimmed.isEmpty() ? newId() : trimmed;
    }

    /** 写入 MDC；null 时不写入 */
    public static void put(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(MDC_KEY, traceId);
        }
    }

    /** 读取当前 MDC 中的 traceId，可能为 null */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 请求/消费结束时清理，避免线程池复用串号 */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
