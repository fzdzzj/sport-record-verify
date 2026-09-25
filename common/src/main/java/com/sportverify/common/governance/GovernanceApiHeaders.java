package com.sportverify.common.governance;

/**
 * 网关治理面专用凭证头（TASK-136 第二阶段）。
 *
 * <p>独立于 Feign 内部接口的 X-Internal-Token。
 * 仅用于证明请求已通过网关 ADMIN 治理面校验。
 * 持有者可复用（非签名、无重放保护、无绑定 caller）是剩余风险。
 * </p>
 */
public final class GovernanceApiHeaders {

    /** 网关治理面专用凭证请求头 */
    public static final String TOKEN = "X-Gateway-Governance-Token";

    private GovernanceApiHeaders() {
    }
}
