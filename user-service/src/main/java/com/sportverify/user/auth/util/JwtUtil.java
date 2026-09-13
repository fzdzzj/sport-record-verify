package com.sportverify.user.auth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JWT 工具（HS256 签发 / 校验 / 解析 userId，认证域唯一签发点，见 ADR-0007）。
 *
 * <p>设计：</p>
 * <ul>
 *   <li><b>双 token</b>：access 短时效（默认 15min，业务接口携带）、refresh 长时效
 *       （默认 7 天，仅换新用）；type 声明区分用途，refresh 不能当 access 用；</li>
 *   <li><b>负载</b>：sub=userId、type、jti（jti 唯一，refresh 的存活状态以 jti 为键存 Redis，
 *       轮换时按 jti 原子作废）；</li>
 *   <li><b>密钥</b>：HS256 对称密钥从配置注入（网关与 user-service 必须一致，
 *       本地默认值仅演示，生产经环境变量/Nacos 覆盖）；</li>
 *   <li><b>校验</b>：parse 仅做签名/时效校验，业务语义（type 匹配）由调用方按需校验。</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtUtil {

    /** token 类型声明键（区分 access / refresh 用途） */
    private static final String CLAIM_TYPE = "type";

    /** 角色声明键（access token 携带；add-admin-rbac，见 ADR-0007）：网关据此判定 /admin/** 准入 */
    private static final String CLAIM_ROLE = "role";

    /** HS256 密钥（>=32 字节；本地默认仅演示，生产必须覆盖，见 ADR-0007） */
    private final SecretKey key;
    /** access token 时效（毫秒） */
    private final long accessTtlMillis;
    /** refresh token 时效（毫秒） */
    private final long refreshTtlMillis;

    public JwtUtil(@Value("${app.auth.jwt.secret:sport-verify-hs256-secret-key-0123456789abcdef}") String secret,
                   @Value("${app.auth.jwt.access-ttl-minutes:15}") long accessTtlMinutes,
                   @Value("${app.auth.jwt.refresh-ttl-days:7}") long refreshTtlDays) {
        // HS256 要求密钥 >= 256 bit（32 字节），配置过短在启动期直接暴露，避免运行时静默降级
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("app.auth.jwt.secret 长度必须 >= 32 字节（HS256 最低密钥长度）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlMillis = TimeUnit.MINUTES.toMillis(accessTtlMinutes);
        this.refreshTtlMillis = TimeUnit.DAYS.toMillis(refreshTtlDays);
    }

    /** token 类型：access 供业务接口；refresh 仅供换新（轮换，见 ADR-0007） */
    public enum TokenType { ACCESS, REFRESH }

    /**
     * 签发 access token（短时效；携带 role claim）。
     *
     * <p>role 由签发端从库中读取传入（add-admin-rbac 见 ADR-0007），token 签名背书，
     * 网关据此判定管理端接口准入——下游/网关不信任外部传入的角色。</p>
     */
    public String issueAccessToken(Long userId, String role) {
        return issue(userId, role, TokenType.ACCESS, accessTtlMillis);
    }

    /** 签发 refresh token（长时效；jti 由调用方按需持久化到 Redis 做存活校验，不携带 role——仅供换新） */
    public String issueRefreshToken(Long userId) {
        return issue(userId, null, TokenType.REFRESH, refreshTtlMillis);
    }

    /** access token 时效（秒），供登录/刷新响应体下发（客户端可据此提前刷新） */
    public long accessTtlSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(accessTtlMillis);
    }

    /** refresh token 时效（秒），供 Redis key 的 TTL 对齐（到点自然失效，无需主动清理） */
    public long refreshTtlSeconds() {
        return TimeUnit.MILLISECONDS.toSeconds(refreshTtlMillis);
    }

    /** 校验签名与时效并解析负载；失败抛 {@link JwtException}，由调用方统一映射 1001 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * 校验 refresh token：签名/时效 + type=REFRESH，返回 userId 与 jti。
     * type 不符（拿 access 来换新）与签名/时效失败一律视为无效 → 1001。
     */
    public ParsedRefresh parseRefresh(String token) {
        Claims claims = parse(token);
        if (!TokenType.REFRESH.name().equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("token 类型错误：非 refresh token");
        }
        return new ParsedRefresh(Long.valueOf(claims.getSubject()), claims.getId());
    }

    /** 签发（sub=userId；jti 唯一，refresh 存活键用它；role 仅 access 携带，refresh 传 null 不写） */
    private String issue(Long userId, String role, TokenType type, long ttlMillis) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, type.name())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key, Jwts.SIG.HS256);
        if (role != null) {
            builder.claim(CLAIM_ROLE, role);
        }
        return builder.compact();
    }

    /** refresh token 解析结果（userId + jti） */
    public record ParsedRefresh(Long userId, String jti) {
    }
}
