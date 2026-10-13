package com.sportverify.record.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.RecordSummaryDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.service.SportRecordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记录域控制器单元测试（提交幂等返回 / 鉴权降级真值路由 / 越权与缺头异常 / 局部异常处理）。
 *
 * <p>纯 Mockito：mock SportRecordService 与 Servlet 请求，不启动 Spring 上下文。
 * 覆盖身份认定（auth.enabled=false 显式携带 / true 网关头覆盖 / 缺头 1001 / 越权 1002）
 * 与 {@code @ExceptionHandler} 的 HTTP 401/403 映射分支。</p>
 */
class RecordControllerTest {

    private SportRecordService sportRecordService;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private RecordController controller;

    @BeforeEach
    void setUp() {
        sportRecordService = mock(SportRecordService.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        controller = new RecordController(sportRecordService);
        // 默认 auth 关闭（旧行为显式携带）
        ReflectionTestUtils.setField(controller, "authEnabled", false);
    }

    private RecordSubmitDTO submitDto(Long userId) {
        RecordSubmitDTO dto = new RecordSubmitDTO();
        dto.setRequestId("req-1");
        dto.setUserId(userId);
        return dto;
    }

    /** 提交成功（auth 关）→ 透传 service 结果，code=0 */
    @Test
    void submit_success_returnsResult() {
        RecordSubmitDTO dto = submitDto(100L);
        RecordSubmitResultDTO result = RecordSubmitResultDTO.of(7L, "req-1", 2, false, null);
        when(sportRecordService.submit(dto)).thenReturn(result);

        Result<RecordSubmitResultDTO> out = controller.submit(dto, request);

        assertEquals(0, out.getCode());
        assertEquals(7L, out.getData().getRecordId());
        verify(sportRecordService).submit(dto);
    }

    /** auth 开、请求体未携带 userId → 以 X-User-Id 注入 */
    @Test
    void submit_authEnabled_headerInjectsWhenClaimedNull() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        RecordSubmitDTO dto = submitDto(null);
        when(request.getHeader("X-User-Id")).thenReturn("200");
        RecordSubmitResultDTO result = RecordSubmitResultDTO.of(8L, "req-1", 1, false, null);
        when(sportRecordService.submit(dto)).thenReturn(result);

        controller.submit(dto, request);

        assertEquals(200L, dto.getUserId());
        verify(sportRecordService).submit(dto);
    }

    /** auth 开、缺网关头 → 1001 缺少 X-User-Id */
    @Test
    void submit_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> controller.submit(submitDto(100L), request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }

    /** auth 开、显式携带与网关身份不一致 → 1002 越权 */
    @Test
    void submit_authEnabled_claimedMismatch_throwsForbidden() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");

        BizException e = assertThrows(BizException.class,
                () -> controller.submit(submitDto(100L), request));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
    }

    /** 重复提交 → service 返回 duplicated=true → 接口返回 3004 */
    @Test
    void submit_duplicate_returnsConflictCode() {
        RecordSubmitDTO dto = submitDto(100L);
        RecordSubmitResultDTO result = RecordSubmitResultDTO.of(7L, "req-1", 2, true, "重复");
        when(sportRecordService.submit(dto)).thenReturn(result);

        Result<RecordSubmitResultDTO> out = controller.submit(dto, request);

        assertEquals(ResultCode.IDEMPOTENT_CONFLICT.getCode(), out.getCode());
        assertEquals(7L, out.getData().getRecordId());
    }

    /** 查询判定结果 */
    @Test
    void verifyResult_delegates() {
        VerificationResultDTO v = new VerificationResultDTO();
        when(sportRecordService.getVerifyResult(3L)).thenReturn(v);

        VerificationResultDTO out = controller.verifyResult(3L).getData();

        assertEquals(v, out);
        verify(sportRecordService).getVerifyResult(3L);
    }

    /** 提交申诉（auth 关）→ 关键 caller 为 null 透传 */
    @Test
    void appeal_delegates() {
        AppealCreateDTO dto = new AppealCreateDTO(5L, 100L, "理由");
        AppealDTO appeal = new AppealDTO();
        when(sportRecordService.appeal(5L, null, "理由")).thenReturn(appeal);

        AppealDTO out = controller.appeal(5L, dto, request).getData();

        assertEquals(appeal, out);
        verify(sportRecordService).appeal(5L, null, "理由");
    }

    /** 轨迹分页查询（auth 关）→ 关键 caller 为 null 透传 page/size */
    @Test
    void points_delegatesWithPagingParams() {
        Page<TrackPointDTO> page = new Page<>();
        when(sportRecordService.pagePoints(5L, null, 2, 50)).thenReturn(page);

        Page<TrackPointDTO> out = controller.points(5L, 2, 50, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pagePoints(5L, null, 2, 50);
    }

    // ==================== 我的记录（TASK-211） ====================

    /** R1a 绿（auth 开）：X-User-Id 解析为归属人 → 仅以此 userId 查服务层（mapper 过滤见 ServiceTest） */
    @Test
    void myRecords_authEnabled_resolvesOwnerFromHeader() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");
        Page<RecordSummaryDTO> page = new Page<>();
        when(sportRecordService.pageMyRecords(200L, 1, 20)).thenReturn(page);

        Page<RecordSummaryDTO> out = controller.myRecords(null, 1, 20, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pageMyRecords(200L, 1, 20);
    }

    /** R1b 绿：page/size 原样透传服务层（边界夹取在服务层，见 ServiceTest） */
    @Test
    void myRecords_delegatesPageSize() {
        Page<RecordSummaryDTO> page = new Page<>();
        when(sportRecordService.pageMyRecords(100L, 3, 50)).thenReturn(page);

        Page<RecordSummaryDTO> out = controller.myRecords(100L, 3, 50, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pageMyRecords(100L, 3, 50);
    }

    /** R1c 红（auth 开，缺网关头）→ 1001 缺少 X-User-Id */
    @Test
    void myRecords_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> controller.myRecords(null, 1, 20, request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }

    /** R1c 红（auth 关，缺显式 userId）→ 400 非法参数 */
    @Test
    void myRecords_authDisabled_missingUserId_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.myRecords(null, 1, 20, request));
        assertEquals("缺少必须的 userId 查询参数", e.getMessage());
    }

    /** R1c 绿（auth 关，显式 userId）→ 以此 userId 透传服务层 */
    @Test
    void myRecords_authDisabled_explicitUserId_delegates() {
        Page<RecordSummaryDTO> page = new Page<>();
        when(sportRecordService.pageMyRecords(88L, 1, 20)).thenReturn(page);

        Page<RecordSummaryDTO> out = controller.myRecords(88L, 1, 20, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pageMyRecords(88L, 1, 20);
    }

    /** 局部异常处理：1001 → HTTP 401 + body code=1001 */
    @Test
    void handleBizException_unauthorized_sets401() throws Exception {
        BizException e = new BizException(ResultCode.UNAUTHORIZED, "缺少 X-User-Id");

        Result<Void> out = controller.handleBizException(e, response);

        verify(response).setStatus(401);
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), out.getCode());
    }

    /** 局部异常处理：1002 → HTTP 403 + body code=1002 */
    @Test
    void handleBizException_forbidden_sets403() throws Exception {
        BizException e = new BizException(ResultCode.FORBIDDEN);

        Result<Void> out = controller.handleBizException(e, response);

        verify(response).setStatus(403);
        assertEquals(ResultCode.FORBIDDEN.getCode(), out.getCode());
    }

    /** 局部异常处理：其余业务码（如 3001）→ 不设 HTTP 状态，body 透传 code */
    @Test
    void handleBizException_otherCode_leavesStatusUnchanged() throws Exception {
        BizException e = new BizException(ResultCode.RECORD_NOT_FOUND);

        Result<Void> out = controller.handleBizException(e, response);

        verify(response, org.mockito.Mockito.never()).setStatus(org.mockito.ArgumentMatchers.anyInt());
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), out.getCode());
        assertNull(out.getData());
    }

    // ==================== 连续打卡（TASK-220） ====================

    /** R3 绿（auth 开）：X-User-Id 解析为归属人 → Result SUCCESS 携 service DTO（沿 /my 先例） */
    @Test
    void myStreak_authEnabled_resolvesOwnerFromHeader() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");
        MyStreakDTO dto = new MyStreakDTO();
        dto.setCurrent(3);
        dto.setLongest(5);
        when(sportRecordService.myStreak(200L)).thenReturn(dto);

        Result<MyStreakDTO> out = controller.myStreak(null, request);

        assertEquals(0, out.getCode());
        assertEquals(3, out.getData().getCurrent());
        assertEquals(5, out.getData().getLongest());
        verify(sportRecordService).myStreak(200L);
    }

    /** R3 红（auth 开，缺网关头）→ 1001 缺少 X-User-Id（沿 /my 缺头先例） */
    @Test
    void myStreak_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> controller.myStreak(null, request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }

    /** R3 红（auth 关，缺显式 userId）→ 400 非法参数（沿 /my 先例） */
    @Test
    void myStreak_authDisabled_missingUserId_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.myStreak(null, request));
        assertEquals("缺少必须的 userId 查询参数", e.getMessage());
    }

    /** R3 绿（auth 关，显式 userId）→ 以此 userId 透传服务层 */
    @Test
    void myStreak_authDisabled_explicitUserId_delegates() {
        MyStreakDTO dto = new MyStreakDTO();
        when(sportRecordService.myStreak(88L)).thenReturn(dto);

        Result<MyStreakDTO> out = controller.myStreak(88L, request);

        assertEquals(0, out.getCode());
        assertEquals(dto, out.getData());
        verify(sportRecordService).myStreak(88L);
    }
}