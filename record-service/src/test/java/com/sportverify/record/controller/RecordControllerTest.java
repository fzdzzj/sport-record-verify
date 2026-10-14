package com.sportverify.record.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sportverify.api.record.dto.MyStatsDTO;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.RecordSummaryDTO;
import com.sportverify.api.record.dto.FeedItemDTO;
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

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    /** 查询判定结果（auth 关）→ caller=null 透传（降级态，行为与今日持平） */
    @Test
    void verifyResult_delegates() {
        VerificationResultDTO v = new VerificationResultDTO();
        when(sportRecordService.getVerifyResult(3L, null)).thenReturn(v);

        VerificationResultDTO out = controller.verifyResult(3L, request).getData();

        assertEquals(v, out);
        verify(sportRecordService).getVerifyResult(3L, null);
    }

    /** 查询判定结果（auth 开）→ 以 X-User-Id 为 caller透传（读取面授权，R3） */
    @Test
    void verifyResult_authEnabled_resolvesCallerFromHeader() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");
        VerificationResultDTO v = new VerificationResultDTO();
        when(sportRecordService.getVerifyResult(3L, 200L)).thenReturn(v);

        VerificationResultDTO out = controller.verifyResult(3L, request).getData();

        assertEquals(v, out);
        verify(sportRecordService).getVerifyResult(3L, 200L);
    }

    /** 查询判定结果（auth 开，缺网关头）→ 1001 缺少 X-User-Id（读取面门不让陌生人静默过） */
    @Test
    void verifyResult_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> controller.verifyResult(3L, request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
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
        when(sportRecordService.pageMyRecords(200L, 1, 20, null, null, null, null)).thenReturn(page);

        Page<RecordSummaryDTO> out = controller.myRecords(null, 1, 20, null, null, null, null, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pageMyRecords(200L, 1, 20, null, null, null, null);
    }

    /** R1b 绿：page/size 原样透传服务层（边界夹取在服务层，见 ServiceTest） */
    @Test
    void myRecords_delegatesPageSize() {
        Page<RecordSummaryDTO> page = new Page<>();
        when(sportRecordService.pageMyRecords(100L, 3, 50, null, null, null, null)).thenReturn(page);

        Page<RecordSummaryDTO> out = controller.myRecords(100L, 3, 50, null, null, null, null, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pageMyRecords(100L, 3, 50, null, null, null, null);
    }

    /** R1c 红（auth 开，缺网关头）→ 1001 缺少 X-User-Id */
    @Test
    void myRecords_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> controller.myRecords(null, 1, 20, null, null, null, null, request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }

    /** R1c 红（auth 关，缺显式 userId）→ 400 非法参数 */
    @Test
    void myRecords_authDisabled_missingUserId_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.myRecords(null, 1, 20, null, null, null, null, request));
        assertEquals("缺少必须的 userId 查询参数", e.getMessage());
    }

    /** R1c 绿（auth 关，显式 userId）→ 以此 userId 透传服务层 */
    @Test
    void myRecords_authDisabled_explicitUserId_delegates() {
        Page<RecordSummaryDTO> page = new Page<>();
        when(sportRecordService.pageMyRecords(88L, 1, 20, null, null, null, null)).thenReturn(page);

        Page<RecordSummaryDTO> out = controller.myRecords(88L, 1, 20, null, null, null, null, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pageMyRecords(88L, 1, 20, null, null, null, null);
    }

    /** TASK-224 R2：携带过滤参数（sportType/status/startDate/endDate）原样透传服务层 */
    @Test
    void myRecords_withFilters_delegatesAllFilters() {
        Page<RecordSummaryDTO> page = new Page<>();
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 5);
        when(sportRecordService.pageMyRecords(100L, 2, 30, 1, 2, start, end)).thenReturn(page);

        Page<RecordSummaryDTO> out = controller.myRecords(100L, 2, 30, 1, 2, start, end, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pageMyRecords(100L, 2, 30, 1, 2, start, end);
    }

    /** TASK-224 R3：非法 sportType 枚举外取值 → 抛 400（IllegalArgumentException） */
    @Test
    void myRecords_illegalSportType_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.myRecords(100L, 1, 20, 99, null, null, null, request));
        assertEquals("非法运动类型：99", e.getMessage());
    }

    /** TASK-224 R3：非法 status 状态域外取值 → 抛 400（IllegalArgumentException） */
    @Test
    void myRecords_illegalStatus_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.myRecords(100L, 1, 20, null, 99, null, null, request));
        assertEquals("非法记录状态：99", e.getMessage());
    }

    /** TASK-224 R3：startDate > endDate → 抛 400（IllegalArgumentException） */
    @Test
    void myRecords_startDateAfterEndDate_throwsIllegalArgument() {
        LocalDate start = LocalDate.of(2026, 10, 10);
        LocalDate end = LocalDate.of(2026, 10, 1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.myRecords(100L, 1, 20, null, null, start, end, request));
        assertEquals("开始日期不能晚于结束日期", e.getMessage());
    }

    // ==================== 好友动态 feed（TASK-222 add-friend-feed） ====================

    /** 绿 R2（auth 开）：X-User-Id 解析为 caller → 以此透传服务层 pageFeed /my 同构门 */
    @Test
    void feed_authEnabled_resolvesCallerFromHeader() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");
        Page<FeedItemDTO> page = new Page<>();
        when(sportRecordService.pageFeed(200L, 2, 50)).thenReturn(page);

        Page<FeedItemDTO> out = controller.feed(2, 50, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pageFeed(200L, 2, 50);
    }

    /** 红 R2（auth 开，缺网关头）→ 1001 缺少 X-User-Id（/my 同构门） */
    @Test
    void feed_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> controller.feed(1, 20, request));

        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
        verify(sportRecordService, never()).pageFeed(any(), anyLong(), anyLong());
    }

    /** 红 R2 degrade（auth 关）：caller=null → 服务层 PAGE feed 走 PUBLIC-only（degrade 零扰动） */
    @Test
    void feed_authDisabled_degrade_callerNull() {
        Page<FeedItemDTO> page = new Page<>();
        when(sportRecordService.pageFeed(null, 1, 20)).thenReturn(page);

        Page<FeedItemDTO> out = controller.feed(1, 20, request).getData();

        assertEquals(page, out);
        verify(sportRecordService).pageFeed(null, 1, 20);
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

    // ==================== 个人核心统计 my/stats（TASK-231） ====================

    /** R1 绿（auth 开）：X-User-Id 解析为归属人 → Result SUCCESS 携 service 统计 DTO（沿 /my 先例） */
    @Test
    void myStats_authEnabled_resolvesOwnerFromHeader() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");
        MyStatsDTO dto = new MyStatsDTO();
        dto.setTotalCount(7L);
        dto.setPassRate("71.4%");
        when(sportRecordService.myStats(200L)).thenReturn(dto);

        Result<MyStatsDTO> out = controller.myStats(null, request);

        assertEquals(0, out.getCode());
        assertEquals(7L, out.getData().getTotalCount());
        assertEquals("71.4%", out.getData().getPassRate());
        verify(sportRecordService).myStats(200L);
    }

    /** R1 红（auth 开，缺网关头）→ 1001 缺少 X-User-Id（沿 /my 缺头先例） */
    @Test
    void myStats_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("  ");

        BizException e = assertThrows(BizException.class, () -> controller.myStats(null, request));

        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
        verify(sportRecordService, never()).myStats(any());
    }

    /** R1 红（auth 关，缺显式 userId）→ 400 非法参数（沿 /my 先例） */
    @Test
    void myStats_authDisabled_missingUserId_throwsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.myStats(null, request));

        assertEquals("缺少必须的 userId 查询参数", e.getMessage());
        verify(sportRecordService, never()).myStats(any());
    }

    /** R1 绿（auth 关，显式 userId）→ 以此 userId 透传服务层 */
    @Test
    void myStats_authDisabled_explicitUserId_delegates() {
        MyStatsDTO dto = new MyStatsDTO();
        when(sportRecordService.myStats(88L)).thenReturn(dto);

        Result<MyStatsDTO> out = controller.myStats(88L, request);

        assertEquals(0, out.getCode());
        assertEquals(dto, out.getData());
        verify(sportRecordService).myStats(88L);
    }

    // ==================== 记录删除（TASK-223） ====================

    /** R1 绿（auth 开）：X-User-Id 解析为调用者 → 成功调用 deleteRecord(caller, id) */
    @Test
    void deleteRecord_authEnabled_success() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");

        Result<Void> out = controller.deleteRecord(100L, null, request);

        assertEquals(0, out.getCode());
        verify(sportRecordService).deleteRecord(200L, 100L);
    }

    /** R1 红（auth 开，缺网关头）→ 1001 UNAUTHORIZED */
    @Test
    void deleteRecord_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> controller.deleteRecord(100L, null, request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }

    /** R1 红（auth 关，缺显式 userId）→ 400 非法参数 */
    @Test
    void deleteRecord_authDisabled_missingUserId_throwsIllegalArgument() {
        ReflectionTestUtils.setField(controller, "authEnabled", false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.deleteRecord(100L, null, request));
        assertEquals("缺少必须的 userId 查询参数", e.getMessage());
    }

    /** R1 绿（auth 关，显式 userId）→ 透传调用者 */
    @Test
    void deleteRecord_authDisabled_explicitUserId_delegates() {
        ReflectionTestUtils.setField(controller, "authEnabled", false);

        Result<Void> out = controller.deleteRecord(100L, 88L, request);

        assertEquals(0, out.getCode());
        verify(sportRecordService).deleteRecord(88L, 100L);
    }
}
