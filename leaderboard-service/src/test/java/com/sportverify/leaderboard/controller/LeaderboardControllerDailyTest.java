package com.sportverify.leaderboard.controller;

import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.leaderboard.service.LeaderboardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/leaderboard/daily} 的参数绑定契约（TASK-108）。
 *
 * <p>只验 HTTP 层：日期格式、必填、size 默认与透传。Service 打桩、不起 Spring 上下文
 * （standalone MockMvc）——{@code @DateTimeFormat} 的转换发生在 web 绑定阶段，
 * 单测 Service 方法永远碰不到这条路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class LeaderboardControllerDailyTest {

    @Mock
    private LeaderboardService leaderboardService;

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new LeaderboardController(leaderboardService)).build();
    }

    @Test
    void bindsIsoDateAndSizeAndReturnsRankedRows() throws Exception {
        List<LeaderboardDTO> rows = List.of(LeaderboardDTO.of(1, 100L, "阿跑", new BigDecimal("145.55")));
        when(leaderboardService.dailyReport(any(), anyInt())).thenReturn(rows);

        mockMvc().perform(MockMvcRequestBuilders.get("/api/leaderboard/daily")
                        .param("date", "2026-09-20")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].rank").value(1))
                .andExpect(jsonPath("$.data[0].distance").value(145.55));

        ArgumentCaptor<LocalDate> capturedDate = ArgumentCaptor.forClass(LocalDate.class);
        verify(leaderboardService).dailyReport(capturedDate.capture(), anyInt());
        assertThat(capturedDate.getValue()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void defaultsSizeTo50WhenAbsent() throws Exception {
        when(leaderboardService.dailyReport(any(), anyInt())).thenReturn(List.of());

        mockMvc().perform(MockMvcRequestBuilders.get("/api/leaderboard/daily")
                        .param("date", "2026-09-01"))
                .andExpect(status().isOk());

        verify(leaderboardService).dailyReport(LocalDate.of(2026, 9, 1), 50);
    }

    @Test
    void rejectsMalformedDateWithHttp400() throws Exception {
        mockMvc().perform(MockMvcRequestBuilders.get("/api/leaderboard/daily")
                        .param("date", "20/09/2026"))
                .andExpect(status().isBadRequest());

        verify(leaderboardService, never()).dailyReport(any(), anyInt());
    }

    @Test
    void rejectsMissingDateWithHttp400() throws Exception {
        mockMvc().perform(MockMvcRequestBuilders.get("/api/leaderboard/daily"))
                .andExpect(status().isBadRequest());

        verify(leaderboardService, never()).dailyReport(any(), anyInt());
    }
}
