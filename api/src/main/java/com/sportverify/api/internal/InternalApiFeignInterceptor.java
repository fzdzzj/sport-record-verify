package com.sportverify.api.internal;

import com.sportverify.common.internal.InternalApiHeaders;
import com.sportverify.common.trace.TraceIds;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feign 出站拦截器：注入内部共享密钥头，并透传当前 MDC traceId（若有）。
 *
 * <p>与 {@code InternalApiAuthFilter} / {@code TraceIdFilter} 配对；消费方服务经
 * {@code scanBasePackages=com.sportverify} 自动注册。额外打到非 /internal 路径的头会被忽略，无害。</p>
 */
@Component
public class InternalApiFeignInterceptor implements RequestInterceptor, InitializingBean {

    /** strict 判别用的演示默认串（与下方 @Value 兜底字面量保持一致） */
    static final String DEMO_TOKEN = "local-demo-internal-token";

    @Value("${app.internal.token:${INTERNAL_API_TOKEN:" + DEMO_TOKEN + "}}")
    private String internalToken;

    /** 密钥治理开关（add-strict-secret-fail-fast）：true=密钥项缺失（回落演示默认）即启动失败 */
    @Value("${app.security.strict:false}")
    private boolean strictMode;

    /**
     * fail-fast（strict 模式）：密钥未显式注入（解析为 null 或等于演示默认串）时拒绝启动，
     * 与接收侧 {@code InternalApiAuthFilter} 的 strict 判别同构；默认（strict=false）不做任何事。
     * 用 InitializingBean 而非 @PostConstruct：本模块未直接依赖 jakarta.annotation-api。
     */
    @Override
    public void afterPropertiesSet() {
        if (strictMode && (internalToken == null || DEMO_TOKEN.equals(internalToken))) {
            throw new IllegalStateException(
                    "app.security.strict=true 但 app.internal.token 未显式注入（回落演示默认值），拒绝启动");
        }
    }

    @Override
    public void apply(RequestTemplate template) {
        template.header(InternalApiHeaders.TOKEN, internalToken);
        String traceId = TraceIds.current();
        if (traceId != null && !traceId.isBlank()) {
            // 覆盖同名头，避免历史空值残留
            template.removeHeader(TraceIds.HEADER);
            template.header(TraceIds.HEADER, traceId);
        }
    }
}
