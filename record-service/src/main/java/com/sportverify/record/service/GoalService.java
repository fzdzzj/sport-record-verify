package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.dto.GoalDTO;
import com.sportverify.record.dto.GoalUpsertDTO;
import com.sportverify.record.entity.Goal;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.GoalMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 运动目标服务（TASK-225）。
 *
 * <p>负责周/月目标的 CRUD 与 pull-on-read 进度实时计算：
 * 进度统计当前 ISO 周（周一至周日）或自然月内本人通过终态（PASSED / RE_PASSED）记录，
 * 百分比按 0..100 整数截断并封顶显示。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalMapper goalMapper;
    private final SportRecordMapper sportRecordMapper;

    /** 目标值合理上限（TASK-234）：距离/次数目标均不得超过 10000，防离谱值污染进度展示。 */
    private static final BigDecimal GOAL_TARGET_VALUE_MAX = new BigDecimal("10000");

    /**
     * 查询我的目标列表及实时进度（默认使用当前服务器日期）。
     *
     * @param userId 所属用户 ID
     * @return 目标与进度列表
     */
    public List<GoalDTO> listGoalsWithProgress(Long userId) {
        return listGoalsWithProgress(userId, LocalDate.now());
    }

    /**
     * 查询我的目标列表及实时进度（支持注入基准日期，供边界单测使用）。
     *
     * @param userId 所属用户 ID
     * @param today  基准日期
     * @return 目标与进度列表
     */
    public List<GoalDTO> listGoalsWithProgress(Long userId, LocalDate today) {
        if (userId == null) {
            return Collections.emptyList();
        }
        List<Goal> goals = goalMapper.selectList(new LambdaQueryWrapper<Goal>()
                .eq(Goal::getUserId, userId)
                .orderByAsc(Goal::getPeriodType)
                .orderByAsc(Goal::getMetric));
        if (goals.isEmpty()) {
            return Collections.emptyList();
        }

        boolean hasWeekly = goals.stream().anyMatch(g -> g.getPeriodType() == 0);
        boolean hasMonthly = goals.stream().anyMatch(g -> g.getPeriodType() == 1);

        List<SportRecord> weekRecords = hasWeekly ? queryPeriodRecords(userId, getWeekStart(today), getWeekEnd(today))
                : Collections.emptyList();
        List<SportRecord> monthRecords = hasMonthly ? queryPeriodRecords(userId, getMonthStart(today), getMonthEnd(today))
                : Collections.emptyList();

        return goals.stream().map(goal -> {
            List<SportRecord> records = goal.getPeriodType() == 0 ? weekRecords : monthRecords;
            BigDecimal achieved = calculateAchieved(records, goal.getMetric());
            int percent = calculatePercent(achieved, goal.getTargetValue());

            GoalDTO dto = new GoalDTO();
            dto.setId(goal.getId());
            dto.setUserId(goal.getUserId());
            dto.setPeriodType(goal.getPeriodType());
            dto.setMetric(goal.getMetric());
            dto.setTargetValue(goal.getTargetValue());
            dto.setAchievedValue(achieved);
            dto.setPercent(percent);
            dto.setCreatedAt(goal.getCreatedAt());
            return dto;
        }).toList();
    }

    /**
     * 创建或更新目标（upsert 语义，基于 uk_user_period_metric 唯一约束）。
     *
     * @param userId 所属用户 ID
     * @param dto    目标入参
     * @return 保存后的 Goal 实体
     */
    public Goal upsertGoal(Long userId, GoalUpsertDTO dto) {
        if (userId == null) {
            throw new IllegalArgumentException("缺少必须的 userId");
        }
        validateGoalParams(dto.getPeriodType(), dto.getMetric(), dto.getTargetValue());

        Goal existing = goalMapper.selectOne(new LambdaQueryWrapper<Goal>()
                .eq(Goal::getUserId, userId)
                .eq(Goal::getPeriodType, dto.getPeriodType())
                .eq(Goal::getMetric, dto.getMetric()));
        if (existing != null) {
            existing.setTargetValue(dto.getTargetValue());
            goalMapper.updateById(existing);
            return existing;
        }

        Goal goal = new Goal();
        goal.setUserId(userId);
        goal.setPeriodType(dto.getPeriodType());
        goal.setMetric(dto.getMetric());
        goal.setTargetValue(dto.getTargetValue());
        goal.setCreatedAt(LocalDateTime.now());
        try {
            goalMapper.insert(goal);
        } catch (DuplicateKeyException e) {
            log.info("并发插入命中唯一键 uk_user_period_metric，转为更新：userId={}, period={}, metric={}",
                    userId, dto.getPeriodType(), dto.getMetric());
            Goal retry = goalMapper.selectOne(new LambdaQueryWrapper<Goal>()
                    .eq(Goal::getUserId, userId)
                    .eq(Goal::getPeriodType, dto.getPeriodType())
                    .eq(Goal::getMetric, dto.getMetric()));
            if (retry != null) {
                retry.setTargetValue(dto.getTargetValue());
                goalMapper.updateById(retry);
                return retry;
            }
            throw e;
        }
        return goal;
    }

    /**
     * 删除目标（归属人校验：非 owner 抛 403，不存在抛 404）。
     *
     * @param caller 调用者 ID
     * @param id     目标 ID
     */
    public void deleteGoal(Long caller, Long id) {
        Goal goal = id == null ? null : goalMapper.selectById(id);
        if (goal == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        if (caller != null && !caller.equals(goal.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        goalMapper.deleteById(id);
    }

    private void validateGoalParams(Integer periodType, Integer metric, BigDecimal targetValue) {
        if (periodType == null || (periodType != 0 && periodType != 1)) {
            throw new IllegalArgumentException("周期类型非法：" + periodType);
        }
        if (metric == null || (metric != 0 && metric != 1)) {
            throw new IllegalArgumentException("指标类型非法：" + metric);
        }
        if (targetValue == null || targetValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("目标值必须大于0");
        }
        // TASK-234 边界收紧：任何指标目标值均设 10000 合理上限，防离谱值污染进度展示
        if (targetValue.compareTo(GOAL_TARGET_VALUE_MAX) > 0) {
            throw new IllegalArgumentException("目标值不能超过10000");
        }
        // TASK-234 整数性：次数目标必须为正整数（无小数位）——4.5 次无物理意义；
        // 尾零小数（如 8.00）经 stripTrailingZeros 归一后视作整数放行
        if (metric == 1 && targetValue.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("次数目标必须为正整数（无小数位）");
        }
    }

    /**
     * 周期内记录查询（TASK-234 周期归属修正）：达成量统计的窗口过滤优先按 start_time
     * （离线补传/轨迹推导均会回填 start_time；其为空时回退 created_at），修正离线补传
     * 运动被 createdAt 误归入错误周期（补传当日已过周期窗口）的破窗。
     */
    private List<SportRecord> queryPeriodRecords(Long userId, LocalDateTime start, LocalDateTime end) {
        return sportRecordMapper.selectList(new LambdaQueryWrapper<SportRecord>()
                .select(SportRecord::getDistance, SportRecord::getCreatedAt)
                .eq(SportRecord::getUserId, userId)
                .in(SportRecord::getStatus, List.of(RecordStatus.PASSED.getCode(), RecordStatus.RE_PASSED.getCode()))
                .apply("COALESCE(start_time, created_at) >= {0}", start)
                .apply("COALESCE(start_time, created_at) <= {0}", end));
    }

    private BigDecimal calculateAchieved(List<SportRecord> records, Integer metric) {
        if (metric == 0) {
            return records.stream()
                    .map(SportRecord::getDistance)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(records.size()).setScale(2, RoundingMode.HALF_UP);
    }

    private int calculatePercent(BigDecimal achieved, BigDecimal target) {
        if (target == null || target.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        if (achieved.compareTo(target) >= 0) {
            return 100;
        }
        int pct = achieved.multiply(BigDecimal.valueOf(100))
                .divide(target, 0, RoundingMode.FLOOR)
                .intValue();
        return Math.max(0, Math.min(100, pct));
    }

    private LocalDateTime getWeekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
    }

    private LocalDateTime getWeekEnd(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(6).atTime(LocalTime.MAX);
    }

    private LocalDateTime getMonthStart(LocalDate date) {
        return date.with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay();
    }

    private LocalDateTime getMonthEnd(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX);
    }
}
