package com.sportverify.api.internal;

import com.sportverify.common.internal.InternalApiHeaders;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feign 出站拦截器：为所有 Feign 请求注入内部共享密钥头。
 *
 * <p>与 {@code InternalApiAuthFilter} 配对；消费方服务经 {@code scanBasePackages=com.sportverify}
 * 自动注册。额外打到非 /internal 路径（如 mapmatch /match）的头会被忽略，无害。</p>
 */
@Component
public class InternalApiFeignInterceptor implements RequestInterceptor {

    @Value("${app.internal.token:${INTERNAL_API_TOKEN:local-demo-internal-token}}")
    private String internalToken;

    @Override
    public void apply(RequestTemplate template) {
        template.header(InternalApiHeaders.TOKEN, internalToken);
    }
}
