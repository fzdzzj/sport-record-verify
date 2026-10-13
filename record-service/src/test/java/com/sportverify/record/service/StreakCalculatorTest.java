package com.sportverify.record.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 连续打卡计算器单元测试（TASK-220 R1 判别式，7 场景纯函数断言，固定日期输入确定性）。
 */
class StreakCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    /** R1a 空输入（null 与空集合）→ {0,0} */
    @Test
    void emptyOrNull_returnsZeroStreak() {
        assertArrayEquals(new int[]{0, 0}, StreakCalculator.compute(null, TODAY));
        assertArrayEquals(new int[]{0, 0}, StreakCalculator.compute(Collections.emptyList(), TODAY));
    }

    /** R1b 单日 today → {1,1}（今日有记录即当前连击=1，历史最长=1） */
    @Test
    void singleDayToday_returnsOneOne() {
        assertArrayEquals(new int[]{1, 1}, StreakCalculator.compute(List.of(TODAY), TODAY));
    }

    /** R1c 单日 yesterday → {1,1}（今日无记录：连击活到当日结束，从昨日续算） */
    @Test
    void singleDayYesterday_returnsOneOne() {
        assertArrayEquals(new int[]{1, 1}, StreakCalculator.compute(List.of(TODAY.minusDays(1)), TODAY));
    }

    /** R1d today 缺 + yesterday 在 → current 从昨日往回续算（yesterday + today-2 → 2） */
    @Test
    void todayMissing_yesterdayPresent_countsFromYesterday() {
        assertArrayEquals(new int[]{2, 2}, StreakCalculator.compute(
                List.of(TODAY.minusDays(1), TODAY.minusDays(2)), TODAY));
    }

    /** R1e 断档：today/today-1 连续但 today-2 缺失 → current 止于断档（=2，不得跨空日续算） */
    @Test
    void gapBreaksCurrentStreak() {
        assertArrayEquals(new int[]{2, 2}, StreakCalculator.compute(
                List.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(3)), TODAY));
    }

    /** R1f 乱序 + 重复输入容忍：内部 HashSet 归一后与有序去重同结果 → {3,3} */
    @Test
    void unsortedAndDuplicates_tolerated() {
        List<LocalDate> messy = Arrays.asList(
                TODAY, TODAY.minusDays(1), TODAY, TODAY.minusDays(2), TODAY.minusDays(1));
        assertArrayEquals(new int[]{3, 3}, StreakCalculator.compute(messy, TODAY));
    }

    /** R1g longest 跨断档取历史最大段：today 单日 + 历史 4 连段 → {1,4}（longest 独立于 current） */
    @Test
    void longest_isHistoricalMaxAcrossGaps() {
        assertArrayEquals(new int[]{1, 4}, StreakCalculator.compute(
                List.of(TODAY, TODAY.minusDays(10), TODAY.minusDays(9),
                        TODAY.minusDays(8), TODAY.minusDays(7)), TODAY));
    }
}
