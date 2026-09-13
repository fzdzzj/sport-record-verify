package com.sportverify.gateway.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JwtTokenParser 单元测试（纯逻辑，无需 Spring 容器）。
 *
 * <p>覆盖：access token 解析出 (userId, role)（role 是治理面准入依据）、
 * refresh token 被拒（拿 refresh 冒充 access → 异常 → 网关映射 401/1001）、
 * 密钥不符被拒（防伪造）。</p>
 */
class JwtTokenParserTest {

    /** 默认密钥，与 gateway/user-service application.yml 的本地默认值一致（>=32 字节） */
    private static final String SECRET = "sport-verify-hs256-secret-key-0123456789abcdef";
    /** 错误密钥（模拟伪造方的未知密钥） */
    private static final String WRONG_SECRET = "wrong-verify-hs256-secret-key-wrong-key-01";
    /** 合法性声明键，与待测解析器对齐 */
    private static final String CLAIM_TYPE = "type";

    private static JwtTokenParser parser() {
        return new JwtTokenParser(SECRET);
    }

    /** 签发一个带 role 的 access token（userId、role 传入） */
    private static String accessToken(long userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, "ACCESS")
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @Test
    void parseIdentityExtractsUserIdAndRole() {
        JwtTokenParser.TokenIdentity identity = parser().parseIdentity(accessToken(7L, "ADMIN"));
        assertEquals(7L, identity.userId());
        assertEquals("ADMIN", identity.role());
    }

    @Test
    void refreshTokenRejected() {
        // refresh token 签 type=REFRESH，冒充 access 必须被拒（否则可绕过 type 隔离）
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String refresh = Jwts.builder()
                .subject("1")
                .claim(CLAIM_TYPE, "REFRESH")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        assertThrows(JwtException.class, () -> parser().parseIdentity(refresh));
    }

    @Test
    void wrongSecretRejected() {
        // 用错误密钥伪造的 token：校验签名失败 → 异常（防 role 被伪造）
        SecretKey wrong = Keys.hmacShaKeyFor(WRONG_SECRET.getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject("1")
                .claim(CLAIM_TYPE, "ACCESS")
                .claim("role", "ADMIN")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(wrong, Jwts.SIG.HS256)
                .compact();
        assertThrows(JwtException.class, () -> parser().parseIdentity(forged));
    }
}