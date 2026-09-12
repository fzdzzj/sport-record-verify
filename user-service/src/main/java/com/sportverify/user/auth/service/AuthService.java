package com.sportverify.user.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.RegisterRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.auth.util.JwtUtil;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务（注册 / 登录 / token 签发；refresh 轮换见 AuthService#refresh）。
 *
 * <p>核心设计：</p>
 * <ul>
 *   <li><b>注册</b>：BCrypt 哈希落库（不落明文），手机号唯一——前置查询 + 唯一键
 *       DuplicateKeyException 双保险（并发注册同一手机号 → 2001）；</li>
 *   <li><b>登录</b>：BCrypt matches 校验密码；失败统一 1001（不区分「用户不存在/密码错误」，
 *       防撞库探测）+ 按手机号计失败次数（Redis INCR + TTL 窗口，超过阈值锁定的
 *       <b>设计口径</b>：本实现先计数 + 告警，账号锁定由后续变更按阈值落地）；</li>
 *   <li><b>签发</b>：access（15min）+ refresh（7d）双 token（见 {@link JwtUtil}）。</li>
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

    // ==================== token 签发（登录/刷新共用） ====================

    /** 签发 access + refresh 双 token（access 15min / refresh 7d，见 JwtUtil） */
    private TokenDTO issueTokenPair(Long userId) {
        TokenDTO dto = new TokenDTO();
        dto.setAccessToken(jwtUtil.issueAccessToken(userId));
        dto.setRefreshToken(jwtUtil.issueRefreshToken(userId));
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

    /** 手机号脱敏（日志口径，与 InternalUserController 同款：保留前 3 后 4） */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
