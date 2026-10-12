package com.sportverify.leaderboard.controller;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.common.result.Result;
import com.sportverify.leaderboard.service.LeaderboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日期范围榜单控制器单元测试（TASK-209）。
 *
 * <p>纯 Mockito：mock LeaderboardService，断言 GET /range 入参校验与透传。
 * start 缺失由 Spring {@code @RequestParam(required=true)} 经
 * {@code MissingServletRequestParameterException} → GlobalExceptionHandler 映射 HTTP 400
 * （本纯单测不改起 Spring 容器，用反射断言 start 参数声明为必填以覆盖该停止判据）；
 * start&gt;end 与 跨度&gt;366 天由控制器显式抛 {@code IllegalArgumentException}
 * （语义与 top 非法 type 同口径，GlobalExceptionHandler 映射 HTTP 400 PARAM）。</p>
 */
class LeaderboardControllerTest {

    private LeaderboardService leaderboardService;
    private LeaderboardController controller;

    @BeforeEach
    void setUp() {
        leaderboardService = mock(LeaderboardService.class);
        controller = new LeaderboardController(leaderboardService);
    }

    /** R1d：start 声明为必填（@RequestParam required 缺省 true），缺失由 Spring 映射 400 */
    @Test
    void range_startRequired_reflectsParamDeclaration() throws NoSuchMethodException {
        java.lang.reflect.Parameter[] params = LeaderboardController.class
                .getMethod("range", LocalDate.class, LocalDate.class, int.class).getParameters();
        RequestParam rp = params[0].getAnnotation(RequestParam.class);
        assertTrue(rp != null && rp.required());
    }

    /** R1d：start&gt;end → 400（IllegalArgumentException → GlobalExceptionHandler 400 PARAM） */
    @Test
    void range_startAfterEnd_throws400() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.range(LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 7), 10));
    }

    /** R1d：跨度 &gt;366 天 → 400（start.plusDays(366) 早于 end 即超限） */
    @Test
    void range_spanOver366Days_throws400() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.range(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 3), 10));
    }

    /** 跨度恰 366 天边界：start.plusDays(366) == end → 放行并透传 service */
    @Test
    void range_spanExactly366Days_allowed() throws NoSuchMethodException {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2027, 1, 2);
        when(leaderboardService.rangeReport(start, end, 10)).thenReturn(List.of(dto(1L)));

        Result<List<LeaderboardDTO>> out = controller.range(start, end, 10);

        assertEquals(0, out.getCode());
        assertEquals(1L, out.getData().get(0).getUserId());
        verify(leaderboardService).rangeReport(start, end, 10);
    }

    /** end 缺省：不传 end → 默认今天并透传 service */
    @Test
    void range_endMissing_defaultsToToday() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        when(leaderboardService.rangeReport(start, LocalDate.now(), 10)).thenReturn(List.of(dto(1L)));

        Result<List<LeaderboardDTO>> out = controller.range(start, null, 10);

        assertEquals(0, out.getCode());
        verify(leaderboardService).rangeReport(start, LocalDate.now(), 10);
    }

    private LeaderboardDTO dto(Long userId) {
        return LeaderboardDTO.of(1, userId, "用户" + userId, new BigDecimal("42.50"));
    }
}
