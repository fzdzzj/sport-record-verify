package com.sportverify.common.internal;

/**
 * 服务间内部接口凭证头常量（add-resilience-hardening，见 ADR-0007）。
 *
 * <p>把「仅靠网内拓扑信任」升级为「共享密钥头」：所有命中 {@code /internal/**}
 * 的本地请求须携带本头，值与 {@code app.internal.token}（环境变量
 * {@code INTERNAL_API_TOKEN}）一致。</p>
 */
public final class InternalApiHeaders {

    /** 内部接口共享密钥请求头 */
    public static final String TOKEN = "X-Internal-Token";

    private InternalApiHeaders() {
    }
}
