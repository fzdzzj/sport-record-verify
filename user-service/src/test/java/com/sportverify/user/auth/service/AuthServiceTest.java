package com.sportverify.user.auth.service;

import com.sportverify.api.auth.dto.LoginRequestDTO;
import com.sportverify.api.auth.dto.TokenDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.auth.util.JwtUtil;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.UserMapper;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试——账号锁定双维度与登出吊销（spec-delta：TASK-193，见 ADR-0007）。
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
        // —— 原子消费旧 jti（GETDEL_LUA）接线：按 key 从假存储「取走并删除」，等效真实 GETDEL 语义
        //    值为空 → 返回 null（已轮换/过期），据此可验证「旧 jti 已消费 → 重放 401」闭环
        when(redis.execute(any(), anyList())).thenAnswer(inv -> {
            List<?> keys = inv.getArgument(1);
            Object v = store.remove(keys.get(0));
            return v == null ? null : v;
        });

        // JWT 签发打桩（登录成功/refresh 落键用；key 具体值不影响断言；role 参数为 add-admin-rbac 后签名）
        when(jwtUtil.issueAccessToken(anyLong(), anyString())).thenReturn("access-token");
        when(jwtUtil.issueRefreshToken(anyLong())).thenReturn("refresh-token");
        when(jwtUtil.parseRefresh(anyString())).thenReturn(new JwtUtil.ParsedRefresh(1L, "test-jti"));
        when(jwtUtil.refreshTtlSeconds()).thenReturn(604800L);
        when(jwtUtil.accessTtlSeconds()).thenReturn(900L);

        // 构造参数顺序与 @RequiredArgsConstructor 字段声明一致（userMapper, jwtUtil, stringRedisTemplate）
        service = new AuthService(userMapper, jwtUtil, redis);
        service.lockThreshold = 20; // 显式对齐全账号兜底阈值 20
        service.ipLockThreshold = 5; // 显式对齐单 (phone,ip) 组合阈值 5
        store.clear();
    }

    // ==================== 达阈值触发锁定 → 第 6 次拒绝 ====================

    /** spec-delta「达阈值触发锁定」+「锁定期间拒绝」：单 IP 5 次失败写组合锁定键，第 6 次该 IP 登录直接 403 且不校验密码 */
    @Test
    void consecutiveFailures_lockAfterThresholdAndRejectSixth() {
        when(userMapper.selectOne(any())).thenReturn(user());
        String ip = "192.168.1.100";
        // 前 5 次错误密码：统一 401（用户不存在/密码错误同语义），并累计单 IP 失败计数
        for (int i = 0; i < 5; i++) {
            BizException e = assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-password"), ip));
            assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode(), "失败登录应返回 401");
        }
        // 第 5 次失败已写 auth:lock:{phone}:{ip} → 第 6 次登录被入口锁定检查拒绝（403，不校验密码不发 token）
        BizException locked = assertThrows(BizException.class, () -> service.login(request(PHONE, GOOD_PASSWORD), ip));
        assertEquals(ResultCode.FORBIDDEN.getCode(), locked.getCode(), "锁定期内应返回 403");
        assertTrue(locked.getMessage().contains("临时锁定"));
        verify(jwtUtil, never()).issueAccessToken(anyLong(), anyString());
    }

    // ==================== 锁定期满恢复 ====================

    /** spec-delta「锁定期满自动解锁」：模拟 lock 键 TTL 到期（移除键）后恢复校验密码，正确密码可登录 */
    @Test
    void lockExpires_loginRecoversToPasswordCheck() {
        when(userMapper.selectOne(any())).thenReturn(user());
        String ip = "192.168.1.100";
        // 制造锁定：5 次失败
        for (int i = 0; i < 5; i++) {
            assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-password"), ip));
        }
        // 锁定中：仍被拒
        assertEquals(ResultCode.FORBIDDEN.getCode(),
                assertThrows(BizException.class, () -> service.login(request(PHONE, GOOD_PASSWORD), ip)).getCode());
        // 模拟 TTL 到期自动解锁：移除 auth:lock:{phone}:{ip} 键
        store.remove("auth:lock:" + PHONE + ":" + ip);
        // 恢复校验密码：正确密码 → 登录成功
        TokenDTO dto = service.login(request(PHONE, GOOD_PASSWORD), ip);
        assertNotNull(dto, "锁定期满后应恢复登录");
        assertFalse(store.containsKey("auth:lock:" + PHONE + ":" + ip), "成功后组合锁定键应被清除");
        assertFalse(store.containsKey("auth:fail:" + PHONE + ":" + ip), "成功后组合失败计数应被清除");
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
        String ip = "192.168.1.100";
        for (int i = 0; i < 5; i++) {
            BizException e = assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-password"), ip));
            assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
        }
        assertFalse(store.containsKey("auth:lock:" + PHONE + ":" + ip), "锁定关闭时不应写组合锁定键");
        // 第 6 次仍走密码校验：正确密码可登录（未被锁定拒绝）
        assertNotNull(service.login(request(PHONE, GOOD_PASSWORD), ip));
    }

    // ==================== TASK-193 登出 3 例 ====================

    /** 登出吊销有效 refresh 存活键 */
    @Test
    void logoutRevokesRefreshLiveKey() {
        String token = "valid-refresh-token";
        when(jwtUtil.parseRefresh(token)).thenReturn(new JwtUtil.ParsedRefresh(123L, "jti-abc"));
        service.logout(token);
        verify(redis).delete("auth:refresh:123:jti-abc");
    }

    /** 登出无效/过期 token 仍幂等成功，delete 零调用 */
    @Test
    void logoutInvalidTokenStillSucceeds() {
        String badToken = "bad-token";
        when(jwtUtil.parseRefresh(badToken)).thenThrow(new JwtException("invalid token"));
        assertDoesNotThrow(() -> service.logout(badToken));
        verify(redis, never()).delete(anyString());
    }

    /** 登出 Redis 异常如实报错（500 语义，不虚假成功） */
    @Test
    void logoutRedisFailureReportsError() {
        String token = "valid-refresh-token";
        when(jwtUtil.parseRefresh(token)).thenReturn(new JwtUtil.ParsedRefresh(123L, "jti-abc"));
        when(redis.delete("auth:refresh:123:jti-abc")).thenThrow(new RuntimeException("redis down"));
        BizException e = assertThrows(BizException.class, () -> service.logout(token));
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), e.getCode());
    }

    // ==================== TASK-193 登录双维度锁定 5 例 ====================

    /** 单 (phone, ip) 失败达 ip-threshold(5) 仅锁该组合，未锁全账号（未达 20） */
    @Test
    void ipFailureLocksOnlyThatPhoneIpPair() {
        when(userMapper.selectOne(any())).thenReturn(user());
        String ipA = "10.0.0.1";
        for (int i = 0; i < 5; i++) {
            assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-pwd"), ipA));
        }
        assertTrue(store.containsKey("auth:lock:" + PHONE + ":" + ipA));
        assertFalse(store.containsKey("auth:lock:" + PHONE));
    }

    /** 同 phone 跨不同 IP 累计达 threshold(20) 触发全账号兜底锁 */
    @Test
    void phoneTotalThresholdStillLocksAccount() {
        when(userMapper.selectOne(any())).thenReturn(user());
        for (int i = 1; i <= 20; i++) {
            String ip = "10.0.0." + i;
            assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-pwd"), ip));
        }
        assertTrue(store.containsKey("auth:lock:" + PHONE));
    }

    /** isLocked 检查双维度：组合锁或全账号锁任一存在即拒，两者皆无放行 */
    @Test
    void isLockedChecksBothDimensions() {
        String ip = "10.0.0.1";
        when(userMapper.selectOne(any())).thenReturn(user());

        // 1. 组合锁存在 → 拒
        store.put("auth:lock:" + PHONE + ":" + ip, "1");
        BizException e1 = assertThrows(BizException.class, () -> service.login(request(PHONE, GOOD_PASSWORD), ip));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e1.getCode());
        store.remove("auth:lock:" + PHONE + ":" + ip);

        // 2. 组合锁不存在但全账号锁存在 → 拒
        store.put("auth:lock:" + PHONE, "1");
        BizException e2 = assertThrows(BizException.class, () -> service.login(request(PHONE, GOOD_PASSWORD), ip));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e2.getCode());
        store.remove("auth:lock:" + PHONE);

        // 3. 两者皆无 → 放行
        TokenDTO dto = service.login(request(PHONE, GOOD_PASSWORD), ip);
        assertNotNull(dto);
    }

    /** 登录成功清 4 键（fail:{phone}, lock:{phone}, fail:{phone}:{ip}, lock:{phone}:{ip}）各一次 */
    @Test
    void loginSuccessClearsAllFourKeys() {
        when(userMapper.selectOne(any())).thenReturn(user());
        String ip = "10.0.0.1";
        TokenDTO dto = service.login(request(PHONE, GOOD_PASSWORD), ip);
        assertNotNull(dto);
        verify(redis).delete("auth:fail:" + PHONE);
        verify(redis).delete("auth:lock:" + PHONE);
        verify(redis).delete("auth:fail:" + PHONE + ":" + ip);
        verify(redis).delete("auth:lock:" + PHONE + ":" + ip);
    }

    /** ip=null 跳过 IP 维度，仅计数 phone 维度，IP 维度键零触碰 */
    @Test
    void nullIpSkipsIpDimension() {
        when(userMapper.selectOne(any())).thenReturn(user());
        assertThrows(BizException.class, () -> service.login(request(PHONE, "wrong-pwd"), null));
        assertTrue(store.containsKey("auth:fail:" + PHONE));
        for (String key : store.keySet()) {
            assertFalse(key.contains("null"), "不应生成含 null 的 IP 维度键");
            assertFalse(key.startsWith("auth:fail:" + PHONE + ":"), "不应生成 IP fail 键");
            assertFalse(key.startsWith("auth:lock:" + PHONE + ":"), "不应生成 IP lock 键");
        }
    }

    // ==================== TASK-202 角色时效缺口 R/G 判别式 ====================

    /** R1 红：status=0 用户合法 refresh → 401；旧 jti 已消费，重放同 refresh 亦 401（封禁强制下线闭环） */
    @Test
    void R1_bannedUserRefreshRejectedAndReplayRejected() {
        when(jwtUtil.parseRefresh("banned-refresh")).thenReturn(new JwtUtil.ParsedRefresh(1L, "jti-banned"));
        User banned = user();
        banned.setStatus(0);
        when(userMapper.selectById(1L)).thenReturn(banned);
        store.put("auth:refresh:1:jti-banned", "1");

        BizException first = assertThrows(BizException.class, () -> service.refresh("banned-refresh"));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), first.getCode(), "封禁用户续签应 401");
        assertFalse(store.containsKey("auth:refresh:1:jti-banned"), "旧 jti 应已被原子消费");
        verify(jwtUtil, never()).issueAccessToken(anyLong(), anyString());

        // 重放同一 refresh：jti 已消费（假存储中不存在）→ alive=null → 401
        BizException replay = assertThrows(BizException.class, () -> service.refresh("banned-refresh"));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), replay.getCode(), "重放同 refresh 应 401");
    }

    /** R2 红：库中用户已删（selectById 返回 null）→ 401 */
    @Test
    void R2_deletedUserRefreshRejected() {
        when(jwtUtil.parseRefresh("deleted-refresh")).thenReturn(new JwtUtil.ParsedRefresh(1L, "jti-deleted"));
        when(userMapper.selectById(1L)).thenReturn(null);
        store.put("auth:refresh:1:jti-deleted", "1");

        BizException e = assertThrows(BizException.class, () -> service.refresh("deleted-refresh"));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode(), "删号用户续签应 401");
        assertFalse(store.containsKey("auth:refresh:1:jti-deleted"), "旧 jti 应已被原子消费");
    }

    /** R3 红：revokeAdmin 目标用户不存在 → USER_NOT_FOUND */
    @Test
    void R3_revokeAdminMissingUserThrowsNotFound() {
        when(userMapper.selectById(9L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> service.revokeAdmin(9L));
        assertEquals(ResultCode.USER_NOT_FOUND.getCode(), e.getCode(), "不存在用户撤销应 2002");
    }

    /** G1 绿：status=1 用户正常轮换语义不回归（新对签发 + 旧 jti 作废 + 新 refresh 存活键落库） */
    @Test
    void G1_validRotationNotRegressed() {
        when(jwtUtil.parseRefresh("incoming-refresh")).thenReturn(new JwtUtil.ParsedRefresh(1L, "old-jti"));
        when(jwtUtil.parseRefresh("refresh-token")).thenReturn(new JwtUtil.ParsedRefresh(1L, "new-jti"));
        when(userMapper.selectById(1L)).thenReturn(user());
        store.put("auth:refresh:1:old-jti", "1");

        TokenDTO dto = service.refresh("incoming-refresh");
        assertNotNull(dto, "正常用户应轮换成功");
        assertEquals("access-token", dto.getAccessToken());
        assertEquals("refresh-token", dto.getRefreshToken());
        assertFalse(store.containsKey("auth:refresh:1:old-jti"), "旧 jti 应已作废");
        assertTrue(store.containsKey("auth:refresh:1:new-jti"), "新 refresh 存活键应落库");
    }

    /** G2 绿：revokeAdmin 已是 USER 幂等跳过；ADMIN 化改库 USER；撤销后续签返回 USER（降权即时性） */
    @Test
    void G2_revokeAdminIdempotentAndDowngradeOnRefresh() {
        // A. 已是 USER：幂等跳过，不改库
        when(userMapper.selectById(1L)).thenReturn(user());
        assertDoesNotThrow(() -> service.revokeAdmin(1L));
        verify(userMapper, never()).updateById(any(User.class));

        // B. ADMIN → 撤销改库 role=USER
        User admin = user();
        admin.setId(2L);
        admin.setRole(User.ROLE_ADMIN);
        when(userMapper.selectById(2L)).thenReturn(admin);
        service.revokeAdmin(2L);
        verify(userMapper).updateById(any(User.class));

        // C. 撤销后续签返回 USER（issueTokenPair 实时读库取 role）
        when(jwtUtil.parseRefresh("incoming-refresh")).thenReturn(new JwtUtil.ParsedRefresh(3L, "old-jti"));
        when(jwtUtil.parseRefresh("refresh-token")).thenReturn(new JwtUtil.ParsedRefresh(3L, "new-jti"));
        User downgraded = user();
        downgraded.setId(3L);
        downgraded.setRole(User.ROLE_USER);
        when(userMapper.selectById(3L)).thenReturn(downgraded);
        store.put("auth:refresh:3:old-jti", "1");

        TokenDTO dto = service.refresh("incoming-refresh");
        assertEquals(User.ROLE_USER, dto.getRole(), "撤销后续签应返回 USER");
        verify(jwtUtil).issueAccessToken(3L, User.ROLE_USER);
    }

    /** G3 绿：grantAdmin 后立即续签返回 ADMIN（提权即时性，实证 β 修正后的语义） */
    @Test
    void G3_grantAdminThenRefreshReturnsAdmin() {
        when(userMapper.selectById(5L)).thenReturn(user());
        service.grantAdmin(5L);
        verify(userMapper).updateById(any(User.class));

        // 授予后续签返回 ADMIN（issueTokenPair 实时读库取 role）
        when(jwtUtil.parseRefresh("incoming-refresh")).thenReturn(new JwtUtil.ParsedRefresh(5L, "old-jti"));
        when(jwtUtil.parseRefresh("refresh-token")).thenReturn(new JwtUtil.ParsedRefresh(5L, "new-jti"));
        User elevated = user();
        elevated.setId(5L);
        elevated.setRole(User.ROLE_ADMIN);
        when(userMapper.selectById(5L)).thenReturn(elevated);
        store.put("auth:refresh:5:old-jti", "1");

        TokenDTO dto = service.refresh("incoming-refresh");
        assertEquals(User.ROLE_ADMIN, dto.getRole(), "授予后续签应返回 ADMIN");
        verify(jwtUtil).issueAccessToken(5L, User.ROLE_ADMIN);
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
