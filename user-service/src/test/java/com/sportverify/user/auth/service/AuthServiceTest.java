package com.sportverify.user.auth.service;

import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.auth.util.JwtUtil;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试——账号锁定（spec-delta：fail-lockout，见 ADR-0007）。
 *
 * <p>用 Mockito 打桩 Mapper 与 StringRedisTemplate：用一个内存 Map 充当 Redis 假存储，
 * 把 {@code increment/hasKey/set/delete/expire} 映射到该 Map，从而让「计数→达阈值写锁定键→
 * 入口查锁」具备真实的端到端语义，验证三件事：连续失败触发锁定、锁定期间拒绝、期满恢复、成功清零。</p>
 */
class AuthServiceTest {

    private static final String PHONE = "13800138000";
    /** 正确密码（登录用），测试内用真实 BCrypt 编码后写入用户 hash */
    private static final String GOOD_PASSWORD = "secret123";

    private UserMapper userMapper;
    private JwtUtil jwtUtil;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AuthService service;
    /** 内存假 Redis 存储：模拟 auth:fail:/auth:lock:/auth:refresh: 键语义 */
    private final Map<String, Object> store = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        jwtUtil = mock(JwtUtil.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);

        // —— 假存储接线：让 Redis 相关调用读写内存 Map，实现完整的计数/锁定/清零语义
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            // 假 INCR：键上续增（首次为 1），模拟 Redis 自增计数语义
            long count = store.containsKey(key) ? ((Number) store.get(key)).longValue() + 1L : 1L;
            store.put(key, count);
            return count;
        }).when(valueOps).increment(anyString());
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.hasKey(anyString())).thenAnswer(inv -> store.containsKey(inv.getArgument(0)));
        doAnswer(inv -> {
            store.remove(inv.getArgument(0));
            return Boolean.TRUE;
        }).when(redis).delete(anyString());
        when(redis.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(Boolean.TRUE);

        // JWT 签发打桩（登录成功/refresh 落键用；key 具体值不影响断言；role 参数为 add-admin-rbac 后签名）
        when(jwtUtil.issueAccessToken(anyLong(), anyString())).thenReturn("access-token");
        when(jwtUtil.issueRefreshToken(anyLong())).thenReturn("refresh-token");
        when(jwtUtil.parseRefresh(anyString())).thenReturn(new JwtUtil.ParsedRefresh(1L, "test-jti"));
        when(jwtUtil.refreshTtlSeconds()).thenReturn(604800L);
        when(jwtUtil.accessTtlSeconds()).thenReturn(900L);

        // 构造参数顺序与 @RequiredArgsConstructor 字段声明一致（userMapper, jwtUtil, stringRedisTemplate）
        service = new AuthService(userMapper, jwtUtil, redis);
        service.lockThreshold = 5; // 显式对齐默认阈值，避免与配置默认值漂移（直造不经 Spring，字段默认已为 5）
        store.clear();
    }

    // ==================== 达阈值触发锁定 → 第 6 次拒绝 ====================

    /** spec-delta「达阈值触发锁定」+「锁定期间拒绝」：5 次失败写锁定键，第 6 次登录直接 403 且不校验密码 */
    @Test
    void consecutiveFailures_lockAfterThresholdAndRejectSixth() {
        when(userMapper.selectOne(any())).thenReturn(user());
        // 前 5 次错误密码：统一 401（用户不存在/密码错误同语义），并累计失败计数
        for (int i = 0; i < 5; i++) {
            BizException e = assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-password")));
            assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode(), "失败登录应返回 401");
        }
        // 第 5 次失败已写 auth:lock: → 第 6 次登录被入口锁定检查拒绝（403，不校验密码不发 token）
        BizException locked = assertThrows(BizException.class, () -> service.login(request(PHONE, GOOD_PASSWORD)));
        assertEquals(ResultCode.FORBIDDEN.getCode(), locked.getCode(), "锁定期内应返回 403");
        assertTrue(locked.getMessage().contains("临时锁定"));
        verify(jwtUtil, never()).issueAccessToken(anyLong(), anyString());
    }

    // ==================== 锁定期满恢复 ====================

    /** spec-delta「锁定期满自动解锁」：模拟 lock 键 TTL 到期（移除键）后恢复校验密码，正确密码可登录 */
    @Test
    void lockExpires_loginRecoversToPasswordCheck() {
        when(userMapper.selectOne(any())).thenReturn(user());
        // 制造锁定：5 次失败
        for (int i = 0; i < 5; i++) {
            assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-password")));
        }
        // 锁定中：仍被拒
        assertEquals(ResultCode.FORBIDDEN.getCode(),
                assertThrows(BizException.class, () -> service.login(request(PHONE, GOOD_PASSWORD))).getCode());
        // 模拟 TTL 到期自动解锁：移除 auth:lock: 键
        store.remove("auth:lock:" + PHONE);
        // 恢复校验密码：正确密码 → 登录成功
        TokenDTO dto = service.login(request(PHONE, GOOD_PASSWORD));
        assertNotNull(dto, "锁定期满后应恢复登录");
        assertFalse(store.containsKey("auth:lock:" + PHONE), "成功后锁定键应被清除");
        assertFalse(store.containsKey("auth:fail:" + PHONE), "成功后失败计数应被清除");
    }

    // ==================== 登录成功清零 ====================

    /** spec-delta「成功清零」：成功登录清除失败计数，并对锁定键也发起删除（防计数残留的双通道清理） */
    @Test
    void successClearsFailAndLock() {
        when(userMapper.selectOne(any())).thenReturn(user());
        // 制造若干失败（未达阈值）
        for (int i = 0; i < 3; i++) {
            assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-password")));
        }
        assertTrue(store.containsKey("auth:fail:" + PHONE), "失败未达阈值前计数键应存在");
        // 成功登录：清失败计数，同时对锁定键发起删除（残留兜底，防止计数/锁定的残留）
        TokenDTO dto = service.login(request(PHONE, GOOD_PASSWORD));
        assertNotNull(dto);
        assertFalse(store.containsKey("auth:fail:" + PHONE), "成功应清空失败计数");
        verify(redis).delete("auth:lock:" + PHONE);
    }

    // ==================== 锁定开关关闭（灰度兼容） ====================

    /** enabled=false：仅计数告警不真正锁定，达阈值后登录仍走密码校验（不阻断） */
    @Test
    void lockDisabled_noLockRejection() {
        when(userMapper.selectOne(any())).thenReturn(user());
        service.lockEnabled = false;
        for (int i = 0; i < 5; i++) {
            BizException e = assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-password")));
            assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
        }
        assertFalse(store.containsKey("auth:lock:" + PHONE), "锁定关闭时不应写锁定键");
        // 第 6 次仍走密码校验：正确密码可登录（未被锁定拒绝）
        assertNotNull(service.login(request(PHONE, GOOD_PASSWORD)));
    }

    // ==================== 工具 ====================

    private User user() {
        // 真实 BCrypt 编码（与 AuthService 内部 BCryptPasswordEncoder 同版本，matches 可命中）
        User u = new User();
        u.setId(1L);
        u.setPhone(PHONE);
        u.setNickname("tester");
        u.setPasswordHash(new BCryptPasswordEncoder().encode(GOOD_PASSWORD));
        u.setStatus(1);
        return u;
    }

    private LoginRequestDTO request(String phone, String password) {
        LoginRequestDTO dto = new LoginRequestDTO();
        dto.setPhone(phone);
        dto.setPassword(password);
        return dto;
    }
}