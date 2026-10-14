package com.sportverify.record.controller;

import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.dto.GoalDTO;
import com.sportverify.record.dto.GoalUpsertDTO;
import com.sportverify.record.entity.Goal;
import com.sportverify.record.service.GoalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GoalController 单测（TASK-225，覆盖 R2 身份门与所有端点契约）。
 */
@ExtendWith(MockitoExtension.class)
class GoalControllerTest {

    @Mock
    private GoalService goalService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private GoalController controller;

    @Test
    void listGoals_authEnabled_resolvesOwnerFromHeader() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("100");
        when(goalService.listGoalsWithProgress(100L)).thenReturn(Collections.emptyList());

        Result<List<GoalDTO>> out = controller.listGoals(null, request);
        assertEquals(0, out.getCode());
        verify(goalService).listGoalsWithProgress(100L);
    }

    @Test
    void listGoals_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> controller.listGoals(null, request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }

    @Test
    void listGoals_authDisabled_explicitUserId_success() {
        ReflectionTestUtils.setField(controller, "authEnabled", false);
        when(goalService.listGoalsWithProgress(200L)).thenReturn(Collections.emptyList());

        Result<List<GoalDTO>> out = controller.listGoals(200L, request);
        assertEquals(0, out.getCode());
        verify(goalService).listGoalsWithProgress(200L);
    }

    @Test
    void listGoals_authDisabled_missingUserId_throwsIllegalArgument() {
        ReflectionTestUtils.setField(controller, "authEnabled", false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> controller.listGoals(null, request));
        assertEquals("缺少必须的 userId 查询参数", e.getMessage());
    }

    @Test
    void upsertGoal_authEnabled_success() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("100");

        GoalUpsertDTO dto = new GoalUpsertDTO();
        dto.setPeriodType(0);
        dto.setMetric(0);
        dto.setTargetValue(new BigDecimal("10.00"));

        Goal expected = new Goal();
        expected.setId(1L);
        when(goalService.upsertGoal(100L, dto)).thenReturn(expected);

        Result<Goal> out = controller.upsertGoal(dto, null, request);
        assertEquals(0, out.getCode());
        assertEquals(1L, out.getData().getId());
        verify(goalService).upsertGoal(100L, dto);
    }

    @Test
    void upsertGoal_authEnabled_mismatchedUserId_throwsForbidden() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("100");

        GoalUpsertDTO dto = new GoalUpsertDTO();
        dto.setUserId(999L); // Claiming another user

        BizException e = assertThrows(BizException.class, () -> controller.upsertGoal(dto, null, request));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    void upsertGoal_authDisabled_missingUserId_throwsIllegalArgument() {
        ReflectionTestUtils.setField(controller, "authEnabled", false);

        GoalUpsertDTO dto = new GoalUpsertDTO();
        dto.setPeriodType(0);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> controller.upsertGoal(dto, null, request));
        assertEquals("缺少必须的 userId 参数", e.getMessage());
    }

    @Test
    void deleteGoal_authEnabled_success() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("100");

        Result<Void> out = controller.deleteGoal(5L, null, request);
        assertEquals(0, out.getCode());
        verify(goalService).deleteGoal(100L, 5L);
    }

    @Test
    void deleteGoal_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> controller.deleteGoal(5L, null, request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }

    @Test
    void deleteGoal_authDisabled_missingUserId_throwsIllegalArgument() {
        ReflectionTestUtils.setField(controller, "authEnabled", false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> controller.deleteGoal(5L, null, request));
        assertEquals("缺少必须的 userId 查询参数", e.getMessage());
    }

    @Test
    void handleBizException_unauthorized_sets401() {
        BizException e = new BizException(ResultCode.UNAUTHORIZED, "缺少 X-User-Id");
        Result<Void> out = controller.handleBizException(e, response);
        verify(response).setStatus(401);
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), out.getCode());
    }

    @Test
    void handleBizException_forbidden_sets403() {
        BizException e = new BizException(ResultCode.FORBIDDEN);
        Result<Void> out = controller.handleBizException(e, response);
        verify(response).setStatus(403);
        assertEquals(ResultCode.FORBIDDEN.getCode(), out.getCode());
    }

    @Test
    void handleBizException_notFound_sets404() {
        BizException e = new BizException(ResultCode.RECORD_NOT_FOUND);
        Result<Void> out = controller.handleBizException(e, response);
        verify(response).setStatus(404);
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), out.getCode());
    }

    @Test
    void handleIllegalArgument_returns400() {
        IllegalArgumentException e = new IllegalArgumentException("参数错误");
        Result<Void> out = controller.handleIllegalArgument(e);
        assertEquals(400, out.getCode());
        assertEquals("参数错误", out.getMessage());
    }
}
