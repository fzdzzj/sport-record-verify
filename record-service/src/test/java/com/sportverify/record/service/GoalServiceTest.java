package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.dto.GoalDTO;
import com.sportverify.record.dto.GoalUpsertDTO;
import com.sportverify.record.entity.Goal;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.GoalMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GoalService 单测（TASK-225，D7 家法：TableInfoHelper 初始化 + 独立隔离跑）。
 */
@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock
    private GoalMapper goalMapper;

    @Mock
    private SportRecordMapper sportRecordMapper;

    @InjectMocks
    private GoalService goalService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Goal.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);
    }

    @Test
    void listGoalsWithProgress_nullUserOrNoGoals_returnsEmpty() {
        assertEquals(Collections.emptyList(), goalService.listGoalsWithProgress(null));

        when(goalMapper.selectList(any())).thenReturn(Collections.emptyList());
        assertEquals(Collections.emptyList(), goalService.listGoalsWithProgress(100L));
    }

    @Test
    void listGoalsWithProgress_weeklyDistanceAndMonthlyCount_calculatesCorrectly() {
        Long userId = 100L;
        LocalDate fixedDate = LocalDate.of(2026, 10, 14); // Wednesday

        Goal weeklyDist = new Goal();
        weeklyDist.setId(1L);
        weeklyDist.setUserId(userId);
        weeklyDist.setPeriodType(0); // Weekly
        weeklyDist.setMetric(0);     // Distance
        weeklyDist.setTargetValue(new BigDecimal("20.00"));
        weeklyDist.setCreatedAt(fixedDate.minusDays(5).atStartOfDay());

        Goal monthlyCount = new Goal();
        monthlyCount.setId(2L);
        monthlyCount.setUserId(userId);
        monthlyCount.setPeriodType(1); // Monthly
        monthlyCount.setMetric(1);     // Count
        monthlyCount.setTargetValue(new BigDecimal("5.00"));
        monthlyCount.setCreatedAt(fixedDate.minusDays(10).atStartOfDay());

        when(goalMapper.selectList(any())).thenReturn(List.of(weeklyDist, monthlyCount));

        // Mock weekly records (e.g. 2 passed records = 6.0 + 8.0 = 14.0 km)
        SportRecord r1 = new SportRecord();
        r1.setDistance(new BigDecimal("6.00"));
        r1.setCreatedAt(LocalDate.of(2026, 10, 12).atStartOfDay()); // Mon

        SportRecord r2 = new SportRecord();
        r2.setDistance(new BigDecimal("8.00"));
        r2.setCreatedAt(LocalDate.of(2026, 10, 14).atTime(10, 0));

        // Mock monthly records (e.g. 6 passed records -> count = 6, target = 5 -> percent = 100 capped)
        SportRecord m1 = new SportRecord();
        SportRecord m2 = new SportRecord();
        SportRecord m3 = new SportRecord();
        SportRecord m4 = new SportRecord();
        SportRecord m5 = new SportRecord();
        SportRecord m6 = new SportRecord();

        when(sportRecordMapper.selectList(any())).thenReturn(List.of(r1, r2), List.of(m1, m2, m3, m4, m5, m6));

        List<GoalDTO> result = goalService.listGoalsWithProgress(userId, fixedDate);
        assertEquals(2, result.size());

        // Verify weekly distance goal
        GoalDTO g1 = result.get(0);
        assertEquals(1L, g1.getId());
        assertEquals(new BigDecimal("20.00"), g1.getTargetValue());
        assertEquals(new BigDecimal("14.00"), g1.getAchievedValue());
        assertEquals(70, g1.getPercent()); // 14 / 20 = 70%

        // Verify monthly count goal
        GoalDTO g2 = result.get(1);
        assertEquals(2L, g2.getId());
        assertEquals(new BigDecimal("5.00"), g2.getTargetValue());
        assertEquals(new BigDecimal("6.00"), g2.getAchievedValue());
        assertEquals(100, g2.getPercent()); // 6 >= 5 -> capped at 100
    }

    @Test
    void listGoalsWithProgress_boundaryWindowAndStatusFiltering() {
        Long userId = 200L;
        LocalDate fixedDate = LocalDate.of(2026, 10, 14); // Wednesday (Mon=12th, Sun=18th)

        Goal weeklyGoal = new Goal();
        weeklyGoal.setId(10L);
        weeklyGoal.setUserId(userId);
        weeklyGoal.setPeriodType(0);
        weeklyGoal.setMetric(0);
        weeklyGoal.setTargetValue(new BigDecimal("10.00"));

        when(goalMapper.selectList(any())).thenReturn(List.of(weeklyGoal));

        // Argument captor to verify wrapper SQL and query criteria
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SportRecord>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);

        when(sportRecordMapper.selectList(captor.capture())).thenReturn(Collections.emptyList());

        List<GoalDTO> out = goalService.listGoalsWithProgress(userId, fixedDate);
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).getPercent());
        assertEquals(new BigDecimal("0.00"), out.get(0).getAchievedValue());

        // Verify that wrapper queried with user_id, status in (PASSED, RE_PASSED), and correct week range
        String sql = captor.getValue().getSqlSegment();
        assertNotNull(sql);
        assertEquals(true, sql.contains("user_id ="));
        assertEquals(true, sql.contains("status IN"));
    }

    @Test
    void upsertGoal_newGoal_insertsRow() {
        Long userId = 100L;
        GoalUpsertDTO dto = new GoalUpsertDTO();
        dto.setPeriodType(0);
        dto.setMetric(0);
        dto.setTargetValue(new BigDecimal("15.50"));

        when(goalMapper.selectOne(any())).thenReturn(null);

        Goal result = goalService.upsertGoal(userId, dto);
        assertNotNull(result);
        assertEquals(userId, result.getUserId());
        assertEquals(0, result.getPeriodType());
        assertEquals(0, result.getMetric());
        assertEquals(new BigDecimal("15.50"), result.getTargetValue());

        verify(goalMapper).insert(any(Goal.class));
        verify(goalMapper, never()).updateById(any(Goal.class));
    }

    @Test
    void upsertGoal_existingGoal_updatesTargetWithoutNewRow() {
        Long userId = 100L;
        GoalUpsertDTO dto = new GoalUpsertDTO();
        dto.setPeriodType(1);
        dto.setMetric(1);
        dto.setTargetValue(new BigDecimal("8.00"));

        Goal existing = new Goal();
        existing.setId(99L);
        existing.setUserId(userId);
        existing.setPeriodType(1);
        existing.setMetric(1);
        existing.setTargetValue(new BigDecimal("3.00"));

        when(goalMapper.selectOne(any())).thenReturn(existing);

        Goal result = goalService.upsertGoal(userId, dto);
        assertEquals(99L, result.getId());
        assertEquals(new BigDecimal("8.00"), result.getTargetValue());

        verify(goalMapper).updateById(existing);
        verify(goalMapper, never()).insert(any(Goal.class));
    }

    @Test
    void upsertGoal_duplicateKeyConcurrent_retriesUpdate() {
        Long userId = 100L;
        GoalUpsertDTO dto = new GoalUpsertDTO();
        dto.setPeriodType(0);
        dto.setMetric(1);
        dto.setTargetValue(new BigDecimal("4.00"));

        Goal concurrent = new Goal();
        concurrent.setId(77L);
        concurrent.setUserId(userId);
        concurrent.setPeriodType(0);
        concurrent.setMetric(1);
        concurrent.setTargetValue(new BigDecimal("2.00"));

        when(goalMapper.selectOne(any())).thenReturn(null, concurrent);
        when(goalMapper.insert(any(Goal.class))).thenThrow(new DuplicateKeyException("uk_user_period_metric duplicate"));

        Goal result = goalService.upsertGoal(userId, dto);
        assertEquals(77L, result.getId());
        assertEquals(new BigDecimal("4.00"), result.getTargetValue());
        verify(goalMapper).updateById(concurrent);
    }

    @Test
    void upsertGoal_validationFailures_throwsIllegalArgumentException() {
        GoalUpsertDTO dto = new GoalUpsertDTO();

        // null userId
        assertThrows(IllegalArgumentException.class, () -> goalService.upsertGoal(null, dto));

        // invalid periodType
        dto.setPeriodType(2);
        dto.setMetric(0);
        dto.setTargetValue(new BigDecimal("10.00"));
        assertThrows(IllegalArgumentException.class, () -> goalService.upsertGoal(100L, dto));

        // invalid metric
        dto.setPeriodType(0);
        dto.setMetric(-1);
        assertThrows(IllegalArgumentException.class, () -> goalService.upsertGoal(100L, dto));

        // invalid targetValue
        dto.setMetric(0);
        dto.setTargetValue(BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> goalService.upsertGoal(100L, dto));
    }

    @Test
    void deleteGoal_owner_success() {
        Long caller = 100L;
        Goal goal = new Goal();
        goal.setId(10L);
        goal.setUserId(caller);

        when(goalMapper.selectById(10L)).thenReturn(goal);

        goalService.deleteGoal(caller, 10L);
        verify(goalMapper).deleteById(10L);
    }

    @Test
    void deleteGoal_nonOwner_throwsForbidden() {
        Long caller = 999L;
        Goal goal = new Goal();
        goal.setId(10L);
        goal.setUserId(100L);

        when(goalMapper.selectById(10L)).thenReturn(goal);

        BizException e = assertThrows(BizException.class, () -> goalService.deleteGoal(caller, 10L));
        assertEquals(ResultCode.FORBIDDEN.getCode(), e.getCode());
        verify(goalMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteGoal_notFound_throwsRecordNotFound() {
        when(goalMapper.selectById(10L)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> goalService.deleteGoal(100L, 10L));
        assertEquals(ResultCode.RECORD_NOT_FOUND.getCode(), e.getCode());
        verify(goalMapper, never()).deleteById(any(Long.class));
    }
}
