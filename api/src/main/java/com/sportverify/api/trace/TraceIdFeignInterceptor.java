package com.sportverify.api.trace;

import com.sportverify.common.trace.TraceIds;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;

/**
 * Feign 出站拦截器：把当前 MDC traceId 写入 {@code X-Request-Id}，避免服务间直调丢链。
 *
 * <p>与 TraceIdFilter 配对；经 scanBasePackages=com.sportverify 自动注册。</p>
 */
@Component
public class TraceIdFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = TraceIds.current();
        if (traceId != null && !traceId.isBlank()) {
            template.header(TraceIds.HEADER, traceId);
        }
    }
}
