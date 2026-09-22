package com.sportverify.user.auth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JwtUtil 单元测试（纯逻辑，无需 Spring 容器）。
 *
 * <p>覆盖：access 签发→解析、type 用途隔离（access 不能当 refresh 用）、
 * 过期 token 拒绝（对应网关 1001 的判定依据）。</p>
 */
class JwtUtilTest {

    /** 测试密钥（>=32 字节，对齐 HS256 最低要求） */
    private static final String SECRET = "test-secret-key-0123456789abcdef0123456789";

    @Test
    void issueAccessAndParseBack() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 15, 7);
        String token = jwtUtil.issueAccessToken(42L, "USER");
        Claims claims = jwtUtil.parse(token);
        assertEquals("42", claims.getSubject());
        assertEquals("ACCESS", claims.get("type"));
        assertEquals("USER", claims.get("role"));
        assertNotNull(claims.getId());
    }

    @Test
    void refreshTokenTypeEnforced() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 15, 7);
        // access token 拿去换新必须被拒（type=ACCESS，非 refresh）
        String access = jwtUtil.issueAccessToken(1L, "USER");
        assertThrows(JwtException.class, () -> jwtUtil.parseRefresh(access));
        // refresh token 解析出 userId 与 jti（jti 是 Redis 存活键的组成部分）
        String refresh = jwtUtil.issueRefreshToken(1L);
        JwtUtil.ParsedRefresh parsed = jwtUtil.parseRefresh(refresh);
        assertEquals(1L, parsed.userId());
        assertNotNull(parsed.jti());
    }

    @Test
    void expiredTokenRejected() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 15, 7);
        // 手工构造过期 token（exp 在 30s 前），校验器必须拒绝 → 网关据此返回 1001
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("1")
                .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                .expiration(new Date(System.currentTimeMillis() - 30_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        assertThrows(JwtException.class, () -> jwtUtil.parse(expired));
    }

    @Test
    void strictTrueWithoutSecretFailsStartup() {
        // strict=true 且不注入 app.auth.jwt.secret → 解析回落演示默认串 → 启动必须失败（fail-fast）
        new ApplicationContextRunner()
                .withUserConfiguration(JwtUtil.class)
                .withPropertyValues("app.security.strict=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().hasRootCauseInstanceOf(IllegalStateException.class);
                    assertThat(context).getFailure().hasStackTraceContaining("app.security.strict=true");
                });
    }

    @Test
    void strictTrueWithExplicitSecretStarts() {
        // 反向绿：strict=true 但密钥已显式注入（>=32 字节）→ 正常启动
        new ApplicationContextRunner()
                .withUserConfiguration(JwtUtil.class)
                .withPropertyValues("app.security.strict=true",
                        "app.auth.jwt.secret=strict-explicit-secret-key-0123456789abcdef-0123456789")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
