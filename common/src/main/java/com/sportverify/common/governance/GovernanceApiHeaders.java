package com.sportverify.common.governance;

/**
 * 网关治理面专用凭证头（TASK-136 第二阶段；TASK-201 升级为 HMAC 签名凭证）。
 *
 * <p>独立于 Feign 内部接口的 X-Internal-Token。
 * 凭证格式 {@code v1.<ts>.<role>.<sigHex>}（HMAC-SHA256，见 GovernanceCredentialCodec），
 * 服务侧验签并断言 role=ADMIN，作为「网关已认定 ADMIN」的密码学证明。</p>
 *
 * <p>剩余风险（v1 声明接受）：时间窗内（默认 300s）同凭证可重放；不绑路径（网关外部路径与
 * StripPrefix 后服务路径不一致，绑定即失配）；相比现状「静态值永久重放」已是数量级收紧。
 * </p>
 */
public final class GovernanceApiHeaders {

    /** 网关治理面专用凭证请求头 */
    public static final String TOKEN = "X-Gateway-Governance-Token";

    /** 治理面 ADMIN 角色值：服务侧验签后须断言等于该值方放行 */
    public static final String ROLE_ADMIN = "ADMIN";

    private GovernanceApiHeaders() {
    }
}
