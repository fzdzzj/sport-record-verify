package com.sportverify.user.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.RefreshRequestDTO;
import com.sportverify.api.auth.dto.RegisterRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.auth.util.JwtUtil;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.UserMapper;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务（注册 / 登录 / token 签发 / refresh 轮换）。
 *
 * <p>核心设计：</p>
 * <ul>
 *   <li><b>注册</b>：BCrypt 哈希落库（不落明文），手机号唯一——前置查询 + 唯一键
 *       DuplicateKeyException 双保险（并发注册同一手机号 → 2001）；</li>
 *   <li><b>登录</b>：BCrypt matches 校验密码；失败统一 1001（不区分「用户不存在/密码错误」，
 *       防撞库探测）+ 按手机号计失败次数（Redis INCR + TTL 窗口，超过阈值锁定的
 *       <b>设计口径</b>：本实现先计数 + 告警，账号锁定由后续变更按阈值落地）；</li>
 *   <li><b>refresh 轮换</b>：refresh token 以 {@code auth:refresh:{userId}:{jti}} 存活键存 Redis
 *       （active 集合，TTL 与 refresh 时效一致，到点自然失效）；刷新时 <b>原子消费</b>
 *       旧 jti（Lua 取走并删除，等效 GETDEL 语义且兼容 Redis <6.2；并发刷新同一 refresh
 *       只有一个赢家）→ 签发新 token 对 → 新 refresh 持久化，旧 refresh 即刻作废（防重放，见 ADR-0007）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;

    /** BCrypt 编码器：只引 spring-security-crypto，避免拉 Spring Security 全家桶（见 ADR-0007） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 登录失败计数键前缀（Redis，窗口内累计） */
    private static final String FAIL_KEY_PREFIX = "auth:fail:";
    /** 失败计数窗口（分钟）：窗口滑动重置，防爆破的设计口径 */
    private static final long FAIL_COOLDOWN_MINUTES = 15;
    /** 失败次数阈值（设计口径：达到阈值建议锁定账号；本实现先计数告警，锁定落地见注释） */
    private static final int FAIL_THRESHOLD = 5;

    /** refresh token 存活键前缀（key=auth:refresh:{userId}:{jti}，TTL 与 refresh 时效一致） */
    @Value("${app.auth.refresh.redis-prefix:auth:refresh:}")
    private String refreshPrefix;

    /**
     * 原子「取走并删除」Lua 脚本（刷新轮换的核心原语）。
     *
     * <p>为什么不用 GETDEL：宿主机 Redis 版本 < 6.2（GETDEL 是 6.2 引入），
     * Spring Data 的 {@code getAndDelete} 映射为 GETDEL 会直接报 ERR unknown command；
     * Lua 脚本由 Redis 单线程原子执行，等效 GETDEL 语义且兼容所有版本。</p>
     */
    private static final DefaultRedisScript<String> GETDEL_LUA = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]); if v then redis.call('del', KEYS[1]) end; return v",
            String.class);

    // ==================== 注册 ====================

    /**
     * 注册（规范「用户注册」）：手机号唯一，重复 → 2001 且不建用户。
     */
    public void register(RegisterRequestDTO dto) {
        if (dto.getPhone() == null || dto.getPhone().isBlank()
                || dto.getPassword() == null || dto.getPassword().isBlank()) {
            throw new IllegalArgumentException("手机号与密码不能为空");
        }
        // —— 前置唯一性查询（常规路径直接拦截，避免白白哈希一次密码）
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, dto.getPhone())
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("注册被拒：手机号已注册 phone={}", maskPhone(dto.getPhone()));
            throw new BizException(ResultCode.PHONE_ALREADY_REGISTERED);
        }

        User user = new User();
        user.setPhone(dto.getPhone());
        user.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setStatus(1);
        user.setCreatedAt(LocalDateTime.now());
        try {
            userMapper.insert(user);
            log.info("注册成功：userId={}, phone={}", user.getId(), maskPhone(dto.getPhone()));
        } catch (DuplicateKeyException e) {
            // 并发注册兜底：uk_phone 唯一键冲突 → 2001（与前置查询双保险）
            throw new BizException(ResultCode.PHONE_ALREADY_REGISTERED);
        }
    }

    // ==================== 登录 ====================

    /**
     * 登录（规范「用户登录」）：校验密码，成功签发双 token；失败 → 401（1001）并计失败次数。
     */
    public TokenDTO login(LoginRequestDTO dto) {
        if (dto.getPhone() == null || dto.getPhone().isBlank()
                || dto.getPassword() == null || dto.getPassword().isBlank()) {
            throw new IllegalArgumentException("手机号与密码不能为空");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, dto.getPhone())
                .last("LIMIT 1"));
        // 用户不存在与密码错误统一 1001（不区分，防撞库探测）；失败都计次数
        if (user == null) {
            countFailure(dto.getPhone());
            throw new BizException(ResultCode.UNAUTHORIZED, "手机号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已被禁用");
        }
        if (!passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            countFailure(dto.getPhone());
            throw new BizException(ResultCode.UNAUTHORIZED, "手机号或密码错误");
        }
        // —— 登录成功：清失败计数（防「试错清零再爆破」的计数残留），签发双 token
        stringRedisTemplate.delete(failKey(dto.getPhone()));
        log.info("登录成功：userId={}", user.getId());
        return issueTokenPair(user.getId());
    }

    // ==================== 角色授予（治理面 RBAC，add-admin-rbac 见 ADR-0007） ====================

    /**
     * 授予指定用户 ADMIN 角色（最小模型二态之一）：仅经内部接口（网内信任）调用。
     *
     * <p>治理面准入：/admin/** 与规则版本接口要求 role=ADMIN，普通用户被拒（403/1002）。
     * 用户不存在 → 2002；幂等：已是 ADMIN 直接成功返回（角色本为集合语义，无重复问题）。
     * 生效口径：<b>需重新登录</b>——角色在签发 token 时写入 role claim，重登拿新 token 才生效。</p>
     */
    public void grantAdmin(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("目标用户ID不能为空");
        }
        // —— 校验目标用户存在（不存在不落库，权限授予不应对不存在的账号静默成功）
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.USER_NOT_FOUND);
        }
        if (User.ROLE_ADMIN.equals(user.getRole())) {
            log.info("admin 授予跳过（已是 ADMIN）：userId={}", userId);
            return;
        }
        User update = new User();
        update.setId(userId);
        update.setRole(User.ROLE_ADMIN);
        userMapper.updateById(update);
        log.info("admin 授予成功：userId={}", userId);
    }

    // ==================== refresh 轮换 ====================

    /**
     * 刷新（规范「Token 刷新与轮换」）：凭 refresh token 换新 access + 新 refresh。
     *
     * <p>轮换语义（见 ADR-0007）：</p>
     * <ol>
     *   <li>JWT 校验（签名/时效/type=REFRESH）失败 → 401（1001）；</li>
     *   <li>Redis 存活校验 + <b>原子消费</b>：Lua 取走并删除旧 jti（等效 GETDEL 语义，
     *       兼容 Redis <6.2），值为空说明已轮换/过期 → 401（旧 refresh 重放被拒）；</li>
     *   <li>签发新 token 对并持久化新 refresh（旧 jti 已删、新 jti 生效，轮换闭环）。</li>
     * </ol>
     */
    public TokenDTO refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(ResultCode.UNAUTHORIZED, "refresh token 不能为空");
        }
        JwtUtil.ParsedRefresh parsed;
        try {
            parsed = jwtUtil.parseRefresh(refreshToken);
        } catch (JwtException e) {
            // 签名/时效/type 任一不符：一律视为无效 token（不区分细节，防探测）
            throw new BizException(ResultCode.UNAUTHORIZED, "refresh token 无效或过期");
        }
        // —— 原子消费旧 jti（Lua 取走并删除，兼容 Redis <6.2 的 GETDEL 语义）：
        //    并发刷新同一 refresh 只有一个赢家；值为空 = 已轮换作废或已过 TTL → 拒绝
        String key = refreshKey(parsed.userId(), parsed.jti());
        String alive = stringRedisTemplate.execute(GETDEL_LUA, List.of(key));
        if (alive == null) {
            log.warn("refresh 已作废：userId={}, jti={}", parsed.userId(), parsed.jti());
            throw new BizException(ResultCode.UNAUTHORIZED, "refresh token 已作废，请重新登录");
        }
        TokenDTO dto = issueTokenPair(parsed.userId());
        log.info("refresh 轮换成功：userId={}, oldJti={}", parsed.userId(), parsed.jti());
        return dto;
    }

    // ==================== token 签发（登录/刷新共用） ====================

    /** 签发 access + refresh 双 token，并把 refresh 持久化到 Redis（存活校验 + 轮换作废的基础） */
    private TokenDTO issueTokenPair(Long userId) {
        // —— role 从库中读取（add-admin-rbac 见 ADR-0007）：角色由签发端落库决定，写入 access role claim
        //    与响应体（供前端判断「是否可进管理端」）；库查不到则回退默认 USER（安全默认，最小权限）
        User user = userMapper.selectById(userId);
        String role = (user != null && user.getRole() != null) ? user.getRole() : User.ROLE_USER;
        TokenDTO dto = new TokenDTO();
        dto.setAccessToken(jwtUtil.issueAccessToken(userId, role));
        dto.setRole(role);
        String refreshToken = jwtUtil.issueRefreshToken(userId);
        dto.setRefreshToken(refreshToken);
        // 存活键：key=auth:refresh:{userId}:{jti}，TTL 与 refresh 时效一致（到点自然失效，无需主动清理）
        JwtUtil.ParsedRefresh parsed = jwtUtil.parseRefresh(refreshToken);
        stringRedisTemplate.opsForValue().set(refreshKey(userId, parsed.jti()), "1",
                jwtUtil.refreshTtlSeconds(), TimeUnit.SECONDS);
        dto.setTokenType("Bearer");
        dto.setExpiresIn(jwtUtil.accessTtlSeconds());
        return dto;
    }

    // ==================== 失败计数（防爆破设计口径） ====================

    /** 登录失败计数：INCR + TTL 窗口；达到阈值记告警（锁定落地属后续变更） */
    private void countFailure(String phone) {
        String key = failKey(phone);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 首次失败才设 TTL：窗口滑动后自然重置（窗口内持续 INCR）
            stringRedisTemplate.expire(key, FAIL_COOLDOWN_MINUTES, TimeUnit.MINUTES);
        }
        if (count != null && count >= FAIL_THRESHOLD) {
            // 设计口径：超过阈值建议锁定账号；本实现先告警，锁定策略由后续变更落地（不引入新错误码）
            log.warn("登录失败次数达阈值：phone={}, count={}，建议锁定账号", maskPhone(phone), count);
        }
        log.info("登录失败计数：phone={}, count={}", maskPhone(phone), count);
    }

    private String failKey(String phone) {
        return FAIL_KEY_PREFIX + phone;
    }

    /** refresh 存活键：按 (userId, jti) 唯一（同一用户的多个 refresh 互不干扰） */
    private String refreshKey(Long userId, String jti) {
        return refreshPrefix + userId + ":" + jti;
    }

    /** 手机号脱敏（日志口径，与 InternalUserController 同款：保留前 3 后 4） */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
