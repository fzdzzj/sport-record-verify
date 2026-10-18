package com.sportverify.record.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.common.PageResult;
import com.sportverify.api.record.dto.MyStatsDTO;
import com.sportverify.api.record.dto.MyStreakDTO;
import com.sportverify.api.record.dto.RecordSubmitDTO;
import com.sportverify.api.record.dto.RecordSubmitResultDTO;
import com.sportverify.api.record.dto.RecordSplitsDTO;
import com.sportverify.api.record.dto.RecordSummaryDTO;
import com.sportverify.api.record.dto.FeedItemDTO;
import com.sportverify.api.record.dto.TrackPointDTO;
import com.sportverify.api.verify.dto.AppealCreateDTO;
import com.sportverify.api.verify.dto.AppealDTO;
import com.sportverify.api.verify.dto.VerificationResultDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.exception.GlobalExceptionHandler;
import com.sportverify.common.result.Result;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.service.SportRecordService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 记录域控制器单元测试（提交幂等返回 / 鉴权降级真值路由 / 越权与缺头异常 / 公共层异常映射 / PageResult 薄转换）。
 *
 * <p>纯 Mockito：mock SportRecordService 与 Servlet 请求，不启动 Spring 上下文。
 * 覆盖身份认定（auth.enabled=false 显式携带 / true 网关头覆盖 / 缺头 1001 / 越权 1002）。
 * TASK-262 R1：局部 {@code @ExceptionHandler(BizException)} 删除，业务异常统一由 common
 * {@link GlobalExceptionHandler} 承接——handler 直测改写为公共层等价断言（HTTP 状态 + body 双断言），
 * 并新增 3001→404 契约 delta 用例（standalone MockMvc + 公共 advice 全链路）。</p>
 */
class RecordControllerTest {

    private SportRecordService sportRecordService;
    private HttpServletRequest request;
    private RecordController controller;

    @BeforeEach
    void setUp() {
        sportRecordService = mock(SportRecordService.class);
        request = mock(HttpServletRequest.class);
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

    /** 构造带字段值的 MP Page（service mock 返回值，R3 薄转换断言的输入）。 */
    private static <T> Page<T> pageOf(long current, long size, long total, List<T> records) {
        Page<T> page = new Page<>();
        page.setCurrent(current);
        page.setSize(size);
        page.setTotal(total);
        page.setRecords(records);
        return page;
    }

    /** R3 形态断言：PageResult 四字段与 MP Page 语义对齐（current/size/total/records）。 */
    private static void assertPageResult(PageResult<?> out, long current, long size, long total, int recordCount) {
        assertEquals(current, out.getCurrent());
        assertEquals(size, out.getSize());
        assertEquals(total, out.getTotal());
        assertEquals(recordCount, out.getRecords().size());
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

    /** 轨迹分页查询（auth 关）→ 关键 caller 为 null 透传 page/size；R3 薄转换 PageResult 形态 */
    @Test
    void points_delegatesWithPagingParams() {
        when(sportRecordService.pagePoints(5L, null, 2, 50))
                .thenReturn(pageOf(2, 50, 120, List.of(new TrackPointDTO())));

        PageResult<TrackPointDTO> out = controller.points(5L, 2, 50, request).getData();

        assertPageResult(out, 2, 50, 120, 1);
        verify(sportRecordService).pagePoints(5L, null, 2, 50);
    }

    // ==================== 我的记录（TASK-211） ====================

    /** R1a 绿（auth 开）：X-User-Id 解析为归属人 → 仅以此 userId 查服务层（mapper 过滤见 ServiceTest）；R3 PageResult 形态 */
    @Test
    void myRecords_authEnabled_resolvesOwnerFromHeader() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");
        when(sportRecordService.pageMyRecords(200L, 1, 20, null, null, null, null))
                .thenReturn(pageOf(1, 20, 7, List.of(new RecordSummaryDTO())));

        PageResult<RecordSummaryDTO> out =
                controller.myRecords(null, 1, 20, null, null, null, null, request).getData();

        assertPageResult(out, 1, 20, 7, 1);
        verify(sportRecordService).pageMyRecords(200L, 1, 20, null, null, null, null);
    }

    /** R1b 绿：page/size 原样透传服务层（边界夹取在服务层，见 ServiceTest）；R3 PageResult 形态 */
    @Test
    void myRecords_delegatesPageSize() {
        when(sportRecordService.pageMyRecords(100L, 3, 50, null, null, null, null))
                .thenReturn(pageOf(3, 50, 30, List.of()));

        PageResult<RecordSummaryDTO> out =
                controller.myRecords(100L, 3, 50, null, null, null, null, request).getData();

        assertPageResult(out, 3, 50, 30, 0);
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

    /** R1c 绿（auth 关，显式 userId）→ 以此 userId 透传服务层；R3 PageResult 形态 */
    @Test
    void myRecords_authDisabled_explicitUserId_delegates() {
        when(sportRecordService.pageMyRecords(88L, 1, 20, null, null, null, null))
                .thenReturn(pageOf(1, 20, 5, List.of(new RecordSummaryDTO())));

        PageResult<RecordSummaryDTO> out =
                controller.myRecords(88L, 1, 20, null, null, null, null, request).getData();

        assertPageResult(out, 1, 20, 5, 1);
        verify(sportRecordService).pageMyRecords(88L, 1, 20, null, null, null, null);
    }

    /** TASK-224 R2：携带过滤参数（sportType/status/startDate/endDate）原样透传服务层 */
    @Test
    void myRecords_withFilters_delegatesAllFilters() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 5);
        when(sportRecordService.pageMyRecords(100L, 2, 30, 1, 2, start, end))
                .thenReturn(pageOf(2, 30, 9, List.of(new RecordSummaryDTO())));

        PageResult<RecordSummaryDTO> out =
                controller.myRecords(100L, 2, 30, 1, 2, start, end, request).getData();

        assertPageResult(out, 2, 30, 9, 1);
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

    /** 绿 R2（auth 开）：X-User-Id 解析为 caller → 以此透传服务层 pageFeed /my 同构门；R3 PageResult 形态 */
    @Test
    void feed_authEnabled_resolvesCallerFromHeader() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");
        when(sportRecordService.pageFeed(200L, 2, 50)).thenReturn(pageOf(2, 50, 11, List.of(new FeedItemDTO())));

        PageResult<FeedItemDTO> out = controller.feed(2, 50, request).getData();

        assertPageResult(out, 2, 50, 11, 1);
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

    /** 红 R2 degrade（auth 关）：caller=null → 服务层 PAGE feed 走 PUBLIC-only（degrade 零扰动）；R3 PageResult 形态 */
    @Test
    void feed_authDisabled_degrade_callerNull() {
        when(sportRecordService.pageFeed(null, 1, 20)).thenReturn(pageOf(1, 20, 0, List.of()));

        PageResult<FeedItemDTO> out = controller.feed(1, 20, request).getData();

        assertPageResult(out, 1, 20, 0, 0);
        verify(sportRecordService).pageFeed(null, 1, 20);
    }

    // ==================== 公共层异常映射（TASK-262 R1 收敛公共层） ====================

    /** 公共层映射：1001 → HTTP 401 + body code=1001（局部 handler 删除后由 GlobalExceptionHandler 承接） */
    @Test
    void publicAdvice_unauthorized_maps401AndBodyCode() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        Result<Void> out = advice.handleBizException(new BizException(ResultCode.UNAUTHORIZED, "缺少 X-User-Id"), resp);

        assertEquals(401, resp.getStatus());
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), out.getCode());
    }

    /** 公共层映射：1002 → HTTP 403 + body code=1002 */
    @Test
    void publicAdvice_forbidden_maps403AndBodyCode() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        Result<Void> out = advice.handleBizException(new BizException(ResultCode.FORBIDDEN), resp);

        assertEquals(403, resp.getStatus());
        assertEquals(ResultCode.FORBIDDEN.getCode(), out.getCode());
    }

    /** 公共层映射（TASK-262 R1 契约 delta 语义升级）：3001 局部恒 200 → 公共层 HTTP 404 + body code=3001 */
    @Test
    void publicAdvice_recordNotFound_maps404AndBodyCode() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        Result<Void> out = advice.handleBizException(new BizException(ResultCode.RECORD_NOT_FOUND), resp);

        assertEquals(404, resp.getStatus());
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), out.getCode());
    }

    /** TASK-262 R1 契约 delta（record 3001→404）：详情路径记录不存在 → 公共层映射 HTTP 404 + body 3001（全链路） */
    @Test
    void recordDetail_recordNotFound_mapsHttp404ViaPublicAdvice() throws Exception {
        when(sportRecordService.getRecordDetail(9L, null))
                .thenThrow(new BizException(ResultCode.RECORD_NOT_FOUND));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/records/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));
    }

    // ==================== PageResult 序列化形态（TASK-262 R3/G14） ====================

    /** /my：序列化为 PageResult 四字段，无 MP Page 的 pages 等实现细节字段 */
    @Test
    void myRecords_serializedPageResultShape_hasNoPagesField() throws Exception {
        when(sportRecordService.pageMyRecords(100L, 1, 20, null, null, null, null))
                .thenReturn(pageOf(1, 20, 3, List.of(new RecordSummaryDTO())));

        Object data = controller.myRecords(100L, 1, 20, null, null, null, null, request).getData();
        String json = new ObjectMapper().writeValueAsString(data);

        assertTrue(json.contains("\"current\":1"), "实测=" + json);
        assertTrue(json.contains("\"size\":20"), "实测=" + json);
        assertTrue(json.contains("\"total\":3"), "实测=" + json);
        assertTrue(json.contains("\"records\""), "实测=" + json);
        assertFalse(json.contains("\"pages\""), "MP Page 实现细节字段不得外泄（G14 统一 PageResult），实测=" + json);
    }

    /** /feed：同上形态契约（前端按 PageResult 契约消费） */
    @Test
    void feed_serializedPageResultShape_hasNoPagesField() throws Exception {
        when(sportRecordService.pageFeed(200L, 2, 50)).thenReturn(pageOf(2, 50, 11, List.of(new FeedItemDTO())));
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");

        Object data = controller.feed(2, 50, request).getData();
        String json = new ObjectMapper().writeValueAsString(data);

        assertTrue(json.contains("\"current\":2"), "实测=" + json);
        assertTrue(json.contains("\"size\":50"), "实测=" + json);
        assertTrue(json.contains("\"total\":11"), "实测=" + json);
        assertTrue(json.contains("\"records\""), "实测=" + json);
        assertFalse(json.contains("\"pages\""), "MP Page 实现细节字段不得外泄（G14 统一 PageResult），实测=" + json);
    }

    /** /{id}/points：同上形态契约 */
    @Test
    void points_serializedPageResultShape_hasNoPagesField() throws Exception {
        when(sportRecordService.pagePoints(5L, null, 2, 50))
                .thenReturn(pageOf(2, 50, 120, List.of(new TrackPointDTO())));

        Object data = controller.points(5L, 2, 50, request).getData();
        String json = new ObjectMapper().writeValueAsString(data);

        assertTrue(json.contains("\"current\":2"), "实测=" + json);
        assertTrue(json.contains("\"size\":50"), "实测=" + json);
        assertTrue(json.contains("\"total\":120"), "实测=" + json);
        assertTrue(json.contains("\"records\""), "实测=" + json);
        assertFalse(json.contains("\"pages\""), "MP Page 实现细节字段不得外泄（G14 统一 PageResult），实测=" + json);
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

    // ==================== 公里分段配速切片（TASK-273） ====================

    /** R1 绿（auth 关）：caller=null 降级透传 → Result SUCCESS 携 service 分片 DTO */
    @Test
    void splits_delegates() {
        RecordSplitsDTO dto = new RecordSplitsDTO();
        dto.setRecordId(5L);
        when(sportRecordService.getRecordSplits(null, 5L)).thenReturn(dto);

        Result<RecordSplitsDTO> out = controller.splits(5L, request);

        assertEquals(0, out.getCode());
        assertEquals(dto, out.getData());
        verify(sportRecordService).getRecordSplits(null, 5L);
    }

    /** R1 绿（auth 开）：X-User-Id 解析为 caller → 以此透传服务层（读取面授权同 /{id} 详情门） */
    @Test
    void splits_authEnabled_resolvesCallerFromHeader() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn("200");
        RecordSplitsDTO dto = new RecordSplitsDTO();
        dto.setRecordId(3L);
        when(sportRecordService.getRecordSplits(200L, 3L)).thenReturn(dto);

        Result<RecordSplitsDTO> out = controller.splits(3L, request);

        assertEquals(0, out.getCode());
        assertEquals(dto, out.getData());
        verify(sportRecordService).getRecordSplits(200L, 3L);
    }

    /** R1 红（auth 开，缺网关头）→ 1001 缺少 X-User-Id（沿 /{id} 详情缺头先例） */
    @Test
    void splits_authEnabled_missingHeader_throwsUnauthorized() {
        ReflectionTestUtils.setField(controller, "authEnabled", true);
        when(request.getHeader("X-User-Id")).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> controller.splits(3L, request));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), e.getCode());
    }

    /** 公共层映射：/splits 路径记录不存在（3001）→ HTTP 404 + body code=3001（全链路） */
    @Test
    void splits_recordNotFound_mapsHttp404ViaPublicAdvice() throws Exception {
        when(sportRecordService.getRecordSplits(null, 9L))
                .thenThrow(new BizException(ResultCode.RECORD_NOT_FOUND));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/records/9/splits"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(3001));
    }

    /** 公共层映射：/splits 路径越权（1002）→ HTTP 403 + body code=1002（全链路） */
    @Test
    void splits_forbidden_mapsHttp403ViaPublicAdvice() throws Exception {
        when(sportRecordService.getRecordSplits(null, 9L))
                .thenThrow(new BizException(ResultCode.FORBIDDEN));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/records/9/splits"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1002));
    }
}
