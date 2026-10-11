package com.sportverify.common.governance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 治理面签名凭证编解码（TASK-201 harden-governance-credential）。
 *
 * <p>把治理凭证从「静态共享密钥」升级为「HMAC 签名凭证」（绑定时间戳与角色）：凭证格式
 * {@code v1.<epochSeconds>.<role>.<sigHex>}，其中
 * {@code sig = HMAC-SHA256(key, "governance-v1|<ts>|<role>")} 输出小写 hex。</p>
 *
 * <p>定位（v2）：服务侧（verify/leaderboard 经 common 加载）唯一 verify 实现；网关端因模块依赖
 * 边界（gateway 零 common 依赖，引 common 会传递 spring-boot-starter-web 致 WebFlux 网关 servlet
 * 双栈污染，见任务书 §1.8）不共用本类，以 AuthGlobalFilter 内联同义 sign 落地，双端格式兼容由
 * 预注册 golden vector（任务书 §2.1）锚定。</p>
 *
 * <p>拒绝原因不外泄：任一校验（格式 / 常量时间签名 / 时间窗）不符一律返回 {@code null}。</p>
 */
public final class GovernanceCredentialCodec {

    /** 凭证格式版本前缀，为未来演进（如加 nonce/路径绑定）预留空间。 */
    private static final String VERSION_PREFIX = "v1";

    /** 签名内容域前缀（与网关内联实现字面一致，见 AuthGlobalFilter.sign）。 */
    private static final String SIGN_DOMAIN_PREFIX = "governance-v1|";

    /** 哨兵值：未显式配置时使用，任何情况下都不应视为可用密钥。 */
    private static final String UNSET_TOKEN = "__GOVERNANCE_TOKEN_UNSET__";

    /** 密钥最短 UTF-8 字节数（对齐 JwtTokenParser 密钥强度先例）。 */
    private static final int MIN_KEY_BYTES = 32;

    private GovernanceCredentialCodec() {
    }

    /**
     * 为指定时间戳与角色签发治理凭证串。
     *
     * @param key          HMAC 密钥（UTF-8）
     * @param epochSeconds 签名时间戳（epoch 秒）
     * @param role         角色（参与签名内容域，如 ADMIN）
     * @return {@code v1.<epochSeconds>.<role>.<sigHex>}
     */
    public static String sign(String key, long epochSeconds, String role) {
        byte[] sig = computeHmac(key, String.valueOf(epochSeconds), role);
        return VERSION_PREFIX + "." + epochSeconds + "." + role + "." + toHex(sig);
    }

    /**
     * 校验治理凭证：四段格式 / 常量时间签名比对 / 时间窗三重校验，全过返回头部声明的角色，任一不符返回 {@code null}。
     *
     * @param key             HMAC 密钥
     * @param headerValue     待校验的凭证串（期望 {@code v1.<ts>.<role>.<sigHex>}）
     * @param maxSkewSeconds  允许的最大时间偏差（秒）
     * @param nowEpochSeconds 当前时间戳（epoch 秒）
     * @return 头部声明的角色；任一校验不符返回 {@code null}
     */
    public static String verify(String key, String headerValue, long maxSkewSeconds, long nowEpochSeconds) {
        if (headerValue == null || headerValue.isEmpty()) {
            return null;
        }
        String[] parts = headerValue.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        if (!VERSION_PREFIX.equals(parts[0])) {
            return null;
        }
        long claimedEpochSeconds;
        try {
            claimedEpochSeconds = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        String role = parts[2];
        if (role.isEmpty()) {
            return null;
        }
        if (Math.abs(nowEpochSeconds - claimedEpochSeconds) > maxSkewSeconds) {
            return null;
        }
        byte[] expected = computeHmac(key, parts[1], role);
        byte[] provided = toBytes(parts[3]);
        if (provided == null || !MessageDigest.isEqual(expected, provided)) {
            return null;
        }
        return role;
    }

    /**
     * 判断密钥是否可用：非空、非哨兵、UTF-8 字节数 ≥ 32。
     *
     * @param key HMAC 密钥
     * @return 密钥可用返回 {@code true}
     */
    public static boolean isUsableKey(String key) {
        if (key == null || key.isBlank() || UNSET_TOKEN.equals(key)) {
            return false;
        }
        return key.getBytes(StandardCharsets.UTF_8).length >= MIN_KEY_BYTES;
    }

    private static byte[] computeHmac(String key, String epochSeconds, String role) {
        String content = SIGN_DOMAIN_PREFIX + epochSeconds + "|" + role;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用（JDK 必含此算法）", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] toBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
