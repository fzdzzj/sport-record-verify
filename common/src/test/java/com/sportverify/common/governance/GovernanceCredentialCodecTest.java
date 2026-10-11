package com.sportverify.common.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-201：治理签名凭证编解码单测（纯 JVM，JDK Mac）。
 *
 * <p>golden vector（任务书 §2.1，指导侧 HMAC-SHA256 离线算定）两端各自断言——common 本类与网关
 * AuthGlobalFilter 内联 sign 同源向量，任一端格式/内容域/编码漂移即红（G3 双端锚定）。</p>
 */
class GovernanceCredentialCodecTest {

    /** 预注册 golden vector 密钥（44 字节 ASCII） */
    private static final String VECTOR_KEY = "task201-governance-hmac-key-0123456789abcdef";

    /** 向量一（正向）：ts=1700000000、role=ADMIN */
    private static final String VECTOR_ADMIN_SIG = "67d715c97093426d344f34f8621b31849cae5d76ef9ef94cbe04f3600cd7e6b9";
    private static final String VECTOR_ADMIN = "v1.1700000000.ADMIN." + VECTOR_ADMIN_SIG;

    /** 向量二（role 参与签名域对照）：同 key 同 ts、role=USER */
    private static final String VECTOR_USER_SIG = "012ada0fd8c2cb393bcff183a4c1192cc16894628d0595edfbff2fd14260a035";
    private static final String VECTOR_USER = "v1.1700000000.USER." + VECTOR_USER_SIG;

    private static final String TEST_KEY = "task201-governance-hmac-key-0123456789abcdef";

    @Test
    void goldenVectorOneVerifiesAsAdmin() {
        // 向量一：时间窗内（ts=1700000000，now=1700000300 恰在 300s 窗口上界内，|300|<=maxSkew）
        assertEquals(GovernanceApiHeaders.ROLE_ADMIN,
                GovernanceCredentialCodec.verify(VECTOR_KEY, VECTOR_ADMIN, 300, 1700000300L));
    }

    @Test
    void goldenVectorTwoVerifiesAsUser() {
        // 向量二：role=USER 同 key 同 ts sig 与向量一不同 → 实证 role 入签名域（时间窗内）
        assertEquals("USER", GovernanceCredentialCodec.verify(VECTOR_KEY, VECTOR_USER, 300, 1700000300L));
    }

    @Test
    void goldenVectorsDifferByRole() {
        // 同 key 同 ts，仅 role 不同 → sig 不同（role 参与签名内容域）
        assertThat(VECTOR_ADMIN_SIG).isNotEqualTo(VECTOR_USER_SIG);
    }

    @Test
    void signRoundTripVerifiesAndReturnsRole() {
        String credential = GovernanceCredentialCodec.sign(TEST_KEY, 1700000500L, "ADMIN");
        assertTrue(credential.startsWith("v1.1700000500.ADMIN."));
        assertEquals("ADMIN", GovernanceCredentialCodec.verify(TEST_KEY, credential, 300, 1700000500L));
    }

    @Test
    void domainSeparatedNonGovernanceSignatureRejected() {
        // 同 key 下其它内容域签名不得通过（域分隔）：改变角色/时间戳即改变签名内容域，
        // 形成的格式合法串因签名不匹配而必须被拒（role/ts 参与签名域的证据）。
        String admin = GovernanceCredentialCodec.sign(TEST_KEY, 1700000500L, "ADMIN");
        // 把 role 段换成 USER（其余不动），sig 仍是 ADMIN 内容域的 → 验签必须失配
        String roleSwapped = admin.replace(".ADMIN.", ".USER.");
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, roleSwapped, 300, 1700000500L));
        // 把 ts 段改动使其与 sig 不一致 → 验签必须失配
        String tsSwapped = "v1.1700000501.ADMIN." + admin.substring(admin.lastIndexOf('.') + 1);
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, tsSwapped, 300, 1700000500L));
    }

    @Test
    void weakKeyRejectedByIsUsableKey() {
        // <32 字节弱密钥不可用
        assertTrue(!GovernanceCredentialCodec.isUsableKey("short-key"));
        assertTrue(GovernanceCredentialCodec.isUsableKey("task201-governance-hmac-key-0123456789abcdef"));
        assertTrue(!GovernanceCredentialCodec.isUsableKey("  "));
        assertTrue(!GovernanceCredentialCodec.isUsableKey("__GOVERNANCE_TOKEN_UNSET__"));
        assertTrue(!GovernanceCredentialCodec.isUsableKey(null));
    }

    @Test
    void garbageFormatRejected() {
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, "not-a-credential", 300, 1700000500L));
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, "", 300, 1700000500L));
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, null, 300, 1700000500L));
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, "v1.notnum.ADMIN.aa", 300, 1700000500L));
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, "v2.1700000500.ADMIN.aa", 300, 1700000500L));
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, "v1.1700000500.ADMIN", 300, 1700000500L));
    }

    @Test
    void expiredTimestampRejected() {
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, VECTOR_ADMIN, 300, 1700000800L + 1));
    }

    @Test
    void tamperedSignatureRejected() {
        // 篡改 sig 一字符 → verify 拒绝（常量时间比对不通过）
        char[] chars = VECTOR_ADMIN.toCharArray();
        chars[chars.length - 1] = (chars[chars.length - 1] == '0') ? '1' : '0';
        assertNull(GovernanceCredentialCodec.verify(TEST_KEY, new String(chars), 300, 1700000500L));
    }

    @Test
    void wrongKeyRejected() {
        assertNull(GovernanceCredentialCodec.verify(
                "another-governance-hmac-key-0123456789abcdef", VECTOR_ADMIN, 300, 1700000500L));
    }
}
