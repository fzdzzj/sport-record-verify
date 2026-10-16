package com.sportverify.user.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.user.entity.User;
import com.sportverify.user.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户资料域单测（E30，TASK-256）。
 *
 * <p>覆盖 {@link UserController#updateProfile}：昵称单列 UPDATE（不整行回写）、
 * 昵称校验（空/超 32）、USER_NOT_FOUND 既有行为保持、鉴权开启身份认定。</p>
 *
 * <p>构造要点（沿 TASK-221 教训）：对 LambdaUpdateWrapper 断言其 SQL SET 前需
 * {@link TableInfoHelper#initTableInfo} 注册 User 实体列缓存，否则 lambda 列名无法渲染。</p>
 */
class UserControllerTest {

    private UserMapper userMapper;
    private HttpServletRequest request;
    private UserController controller;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        request = mock(HttpServletRequest.class);
        controller = new UserController(userMapper);
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
    }

    private User user(Long id) {
        User u = new User();
        u.setId(id);
        u.setPhone("13800000000");
        u.setPasswordHash("$2a$10$hash");
        u.setNickname("旧昵称");
        u.setRole(User.ROLE_ADMIN);
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        return u;
    }

    private void stubRequestHeader(Long userId) {
        when(request.getHeader("X-User-Id")).thenReturn(String.valueOf(userId));
    }

    /** E30：昵称单列 UPDATE——SQL SET 仅 nickname，不含 password_hash/role/status/phone
     *  等敏感整行字段（防与改密/撤销管理员并发窗口回写旧值），且 DTO 回带新昵称。 */
    @Test
    void updateProfile_singleColumnUpdate_onlyNickname() {
        stubRequestHeader(1001L);
        when(userMapper.selectById(1001L)).thenReturn(user(1001L));

        Result<UserDTO> out =
                controller.updateProfile(Map.of("nickname", "新昵称"), request);

        assertEquals(0, out.getCode());
        assertEquals("新昵称", out.getData().getNickname(), "DTO 应用局部变量携带新昵称");
        assertEquals(1001L, out.getData().getId());

        ArgumentCaptor<LambdaUpdateWrapper<User>> wrapperCap =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(userMapper).update(org.mockito.ArgumentMatchers.isNull(), wrapperCap.capture());
        // 先渲染 getSqlSet() 使参数/列名落地（MP 渲染是惰性的），再断言列集合
        String set = wrapperCap.getValue().getSqlSet();
        assertTrue(set.contains("nickname"), "单列 UPDATE 应写 nickname，实测 SET=" + set);
        assertFalse(set.toLowerCase().contains("password_hash"),
                "不得整行回写 password_hash：" + set);
        assertFalse(set.contains("role"), "不得整行回写 role：" + set);
        assertFalse(set.contains("status"), "不得整行回写 status：" + set);
        assertFalse(set.contains("phone"), "不得整行回写 phone：" + set);
    }

    /** E30：昵称为空 → IllegalArgumentException（400 口径，既有行为保持） */
    @Test
    void updateProfile_blankNickname_rejected() {
        stubRequestHeader(1001L);
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateProfile(Map.of("nickname", "  "), request));
        verify(userMapper, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** E30：昵称超 32 字符 → IllegalArgumentException（400 口径，既有行为保持） */
    @Test
    void updateProfile_tooLongNickname_rejected() {
        stubRequestHeader(1001L);
        assertThrows(IllegalArgumentException.class,
                () -> controller.updateProfile(Map.of("nickname", "x".repeat(33)), request));
        verify(userMapper, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** E30：目标用户不存在 → USER_NOT_FOUND（2002，既有行为保持，不触发 UPDATE） */
    @Test
    void updateProfile_userNotFound_throwUserNotFound() {
        stubRequestHeader(1001L);
        when(userMapper.selectById(1001L)).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> controller.updateProfile(Map.of("nickname", "新昵称"), request));

        assertEquals(ResultCode.USER_NOT_FOUND.getCode(), e.getCode());
        verify(userMapper, never()).update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** E30：鉴权未开启 → 401（无可信身份，resovveUserId 拒绝，既有行为保持） */
    @Test
    void updateProfile_authDisabled_rejectedUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", false);
        BizException e = assertThrows(BizException.class,
                () -> controller.updateProfile(Map.of("nickname", "x"), request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }
}
