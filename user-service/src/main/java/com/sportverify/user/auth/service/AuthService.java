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
 * 认证服务（注册 / 登录 / 登出 / token 签发 / refresh 轮换）。
 *
 * <p>核心设计：</p>
 * <ul>
 *   <li><b>注册</b>：BCrypt 哈希落库（不落明文），手机号唯一——前置查询 + 唯一键
 *       DuplicateKeyException 双保险（并发注册同一手机号 → 2001）；</li>
 *   <li><b>登录</b>：BCrypt matches 校验密码；失败统一 1001（不区分「用户不存在/密码错误」，
 *       防撞库探测）+ 计失败次数（Redis INCR + TTL 窗口）。支持双维度锁定：单 (phone,ip)
 *       组合连续失败达 ip-threshold（默认 5）锁定该组合；跨 IP 累计失败达 threshold（默认 20）
 *       锁定全账号兜底防分布式爆破。锁定期间直接拒绝（403），到期自动解锁、登录成功清除四键（见 ADR-0007）；</li>
 *   <li><b>登出</b>：主动作废 refresh 存活键（幂等处理，Redis 异常如实报错不虚假成功）；</li>
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
    /** 登录锁定键前缀（Redis，达阈值后写入，TTL=锁定时长，存在即拒绝登录） */
    private static final String LOCK_KEY_PREFIX = "auth:lock:";

    /** refresh token 存活键前缀（key=auth:refresh:{userId}:{jti}，TTL 与 refresh 时效一致） */
    @Value("${app.auth.refresh.redis-prefix:auth:refresh:}")
    private String refreshPrefix = "auth:refresh:";

    // ===== 登录失败锁定配置（app.auth.lock.*，见 ADR-0007；field 默认值保证非 Spring 直造也可用） =====
    /** 锁定开关：false=仅计数告警不真正锁定（灰度兼容旧行为） */
    @Value("${app.auth.lock.enabled:true}")
    boolean lockEnabled = true;
    /** 全账号兜底失败阈值：跨 IP 累计连续失败达此数触发全账号锁定（默认 5→20） */
    @Value("${app.auth.lock.threshold:20}")
    int lockThreshold = 20;
    /** 单 (phone,ip) 组合失败阈值：单 IP 连续失败达此数触发组合锁定（默认 5） */
    @Value("${app.auth.lock.ip-threshold:5}")
    int ipLockThreshold = 5;
    /** 失败计数窗口（分钟）：滑动窗口，窗口内持续 INCR */
    @Value("${app.auth.lock.window-minutes:15}")
    long lockWindowMinutes = 15;
    /** 锁定时长（分钟）：auth:lock:{phone} 的 TTL，到期自然解锁 */
    @Value("${app.auth.lock.lock-minutes:15}")
    long lockMinutes = 15;

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
     *
     * <p>无本地事务（ADR-0009）：仅一次 {@code user} 行 insert；{@code role} 同表默认 USER
     * （实体默认值 + 列 DEFAULT），不存在「插入用户 + 初始化角色表」两写。单行 + uk_phone 幂等即可，
     * 禁止为形式补 {@code @Transactional}。</p>
     */
    public void register(RegisterRequestDTO dto) {
        // —— 前置唯一性查询（常规路径直接拦截，避免白白哈希一次密码）
        //    字段必填/格式校验由控制器层 @Valid + DTO 注解承担，此处不再重复判空
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
     * 登录（单参兼容重载）：默认 ip=null。
     */
    public TokenDTO login(LoginRequestDTO dto) {
        return login(dto, null);
    }

    /**
     * 登录（规范「用户登录」）：校验密码，成功签发双 token；失败 → 401（1001）并计失败次数。
     * 支持 (phone, ip) 与 phone 双维度锁定。
     */
    public TokenDTO login(LoginRequestDTO dto, String ip) {
        // 字段必填/格式校验由控制器层 @Valid + DTO 注解承担，此处不再重复判空
        // —— 锁定前置检查：账号或 IP 组合已临时锁定 → 直接拒绝（不校验密码、不 countFailure；防撞库 + 省 BCrypt）
        if (lockEnabled && isLocked(dto.getPhone(), ip)) {
            log.warn("登录被拒：账号或IP组合已临时锁定 phone={}, ip={}", maskPhone(dto.getPhone()), ip);
            throw new BizException(ResultCode.FORBIDDEN, "账号已临时锁定，请稍后重试");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, dto.getPhone())
                .last("LIMIT 1"));
        // 用户不存在与密码错误统一 1001（不区分，防撞库探测）；失败都计次数
        if (user == null) {
            countFailure(dto.getPhone(), ip);
            throw new BizException(ResultCode.UNAUTHORIZED, "手机号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已被禁用");
        }
        if (!passwordEncoder.matches(dto.getPassword(), user.getPasswordHash())) {
            countFailure(dto.getPhone(), ip);
            throw new BizException(ResultCode.UNAUTHORIZED, "手机号或密码错误");
        }
        // —— 登录成功：清失败计数 + 锁定（防「试错清零再爆破」的计数残留，两维度锁定一并解除）
        try {
            stringRedisTemplate.delete(failKey(dto.getPhone()));
            stringRedisTemplate.delete(lockKey(dto.getPhone()));
            if (ip != null && !ip.isBlank()) {
                stringRedisTemplate.delete(ipFailKey(dto.getPhone(), ip));
                stringRedisTemplate.delete(ipLockKey(dto.getPhone(), ip));
            }
        } catch (Exception e) {
            // 清计数/锁定是防残留增强：Redis 不可用降级为不影响签发 token（登录不因 Redis 故障失败）
            log.warn("登录成功清计数/锁定降级（Redis 不可用）：phone={}, ip={}, err={}",
                    maskPhone(dto.getPhone()), ip, e.getMessage());
        }
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
        // 必填校验由控制器层 @Valid + RefreshRequestDTO 注解承担；空 token 落到 JWT 解析失败同样 401
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

    // ==================== 登出（主动吊销 refresh） ====================

    /**
     * 登出（主动吊销 refresh token）：删 Redis 存活键（即刻作废，此后轮换 401）。
     *
     * <p>幂等处理：无效或已过期 token 同样返回成功，不泄漏 token 有效性。
     * Redis 异常时不虚假成功，如实抛出 500 业务异常以便客户端重试。</p>
     */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            log.info("登出幂等处理（token 为空）");
            return;
        }
        JwtUtil.ParsedRefresh parsed;
        try {
            parsed = jwtUtil.parseRefresh(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            // 幂等：无效/过期 token 同样返回成功——登出不泄 token 有效性
            log.info("登出幂等处理（token 无效或已过期）");
            return;
        }
        try {
            stringRedisTemplate.delete(refreshKey(parsed.userId(), parsed.jti()));
        } catch (Exception e) {
            // 不虚假成功：删键失败 refresh 仍可轮换，如实报错让客户端可重试
            log.error("登出吊销失败（Redis 异常）：userId={}", parsed.userId(), e);
            throw new BizException(ResultCode.SYSTEM_ERROR);
        }
        log.info("登出成功：refresh 即刻作废 userId={}", parsed.userId());
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

    /** 锁定状态检查：auth:lock:{phone} 或 auth:lock:{phone}:{ip} 存在即为已锁定；Redis 不可用降级为「不锁定」（不阻塞登录） */
    private boolean isLocked(String phone, String ip) {
        try {
            boolean accountLocked = Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey(phone)));
            if (accountLocked) {
                return true;
            }
            if (ip != null && !ip.isBlank()) {
                return Boolean.TRUE.equals(stringRedisTemplate.hasKey(ipLockKey(phone, ip)));
            }
            return false;
        } catch (Exception e) {
            // 锁定是安全增强非强一致必须：Redis 抖动时宁可放行也不因锁定检查失败阻断登录
            log.warn("锁定检查降级（Redis 不可用）：phone={}, ip={}, err={}", maskPhone(phone), ip, e.getMessage());
            return false;
        }
    }

    /** 登录失败计数：INCR + TTL 窗口；达到阈值写锁定键（此后登录入口直接拒绝），Redis 异常降级为仅告警 */
    private void countFailure(String phone, String ip) {
        try {
            // 1. 全账号维度累计（跨 IP 兜底）
            String totalKey = failKey(phone);
            Long totalCount = stringRedisTemplate.opsForValue().increment(totalKey);
            if (totalCount != null && totalCount == 1L) {
                // 首次失败才设 TTL：窗口滑动后自然重置（窗口内持续 INCR）
                stringRedisTemplate.expire(totalKey, lockWindowMinutes, TimeUnit.MINUTES);
            }
            if (totalCount != null && totalCount >= lockThreshold) {
                if (lockEnabled) {
                    stringRedisTemplate.opsForValue().set(lockKey(phone), "1", lockMinutes, TimeUnit.MINUTES);
                    log.warn("账号已达总失败兜底阈值并临时锁定：phone={}, count={}, 锁定时长={}min",
                            maskPhone(phone), totalCount, lockMinutes);
                } else {
                    // 锁定开关关闭（灰度兼容）：仅告警不真正锁定
                    log.warn("登录失败次数达总阈值（锁定关闭）：phone={}, count={}", maskPhone(phone), totalCount);
                }
            }

            // 2. 单 (phone,ip) 组合维度计数
            if (ip != null && !ip.isBlank()) {
                String ipKey = ipFailKey(phone, ip);
                Long ipCount = stringRedisTemplate.opsForValue().increment(ipKey);
                if (ipCount != null && ipCount == 1L) {
                    stringRedisTemplate.expire(ipKey, lockWindowMinutes, TimeUnit.MINUTES);
                }
                if (ipCount != null && ipCount >= ipLockThreshold) {
                    if (lockEnabled) {
                        stringRedisTemplate.opsForValue().set(ipLockKey(phone, ip), "1", lockMinutes, TimeUnit.MINUTES);
                        log.warn("单IP组合已达失败阈值并临时锁定：phone={}, ip={}, count={}, 锁定时长={}min",
                                maskPhone(phone), ip, ipCount, lockMinutes);
                    } else {
                        log.warn("单IP登录失败次数达阈值（锁定关闭）：phone={}, ip={}, count={}",
                                maskPhone(phone), ip, ipCount);
                    }
                }
            }
            log.info("登录失败计数：phone={}, ip={}, totalCount={}", maskPhone(phone), ip, totalCount);
        } catch (Exception e) {
            // 失败计数/锁定是防爆破增强：Redis 不可用降级为仅告警，不因 Redis 故障抛错阻断登录主流程
            log.warn("登录失败计数/锁定降级（Redis 不可用）：phone={}, ip={}, err={}", maskPhone(phone), ip, e.getMessage());
        }
    }

    private String failKey(String phone) {
        return FAIL_KEY_PREFIX + phone;
    }

    private String ipFailKey(String phone, String ip) {
        return FAIL_KEY_PREFIX + phone + ":" + ip;
    }

    /** 锁定键：auth:lock:{phone}（存在即表示账号处于临时锁定状态） */
    private String lockKey(String phone) {
        return LOCK_KEY_PREFIX + phone;
    }

    /** IP 组合锁定键：auth:lock:{phone}:{ip}（存在即表示该 IP 对该账号处于临时锁定状态） */
    private String ipLockKey(String phone, String ip) {
        return LOCK_KEY_PREFIX + phone + ":" + ip;
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
