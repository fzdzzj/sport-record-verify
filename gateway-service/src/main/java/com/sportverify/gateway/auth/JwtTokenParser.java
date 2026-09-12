package com.sportverify.gateway.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 网关侧 access token 解析器（只校验/解析，不做签发——签发在 user-service，见 ADR-0007）。
 *
 * <p>与 user-service 的 {@code JwtUtil} 共用同一 HS256 密钥（配置 {@code app.auth.jwt.secret}，
 * 两端必须一致）；type 声明必须为 ACCESS（拿 refresh token 冒充 access 直接被拒）。
 * 因网关与 user-service 是独立进程、各自持库各自的 jjwt 依赖，解析逻辑在此独立维护
 * （仅 ~20 行，密钥/声明名两端对齐，注释互为印证）。</p>
 */
@Component
public class JwtTokenParser {

    /** token 类型声明键（与 user-service JwtUtil.CLAIM_TYPE 对齐） */
    private static final String CLAIM_TYPE = "type";
    /** 合法类型：access（业务接口携带；refresh 仅供换新，网关一律拒绝） */
    private static final String TYPE_ACCESS = "ACCESS";

    /** HS256 密钥（>=32 字节；与 user-service 配置必须一致，见 ADR-0007） */
    private final SecretKey key;

    public JwtTokenParser(@Value("${app.auth.jwt.secret:sport-verify-hs256-secret-key-0123456789abcdef}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.auth.jwt.secret 长度必须 >= 32 字节（HS256 最低密钥长度）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验签名/时效/type 并解析 userId；任一不符抛 {@link JwtException}，由过滤器统一映射 401（1001）。
     */
    public Long parseUserId(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("token 类型错误：非 access token");
        }
        return Long.valueOf(claims.getSubject());
    }
}
