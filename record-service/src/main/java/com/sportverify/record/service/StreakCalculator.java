package com.sportverify.record.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 连续打卡天数计算器（TASK-220 纯函数，无状态无依赖）。
 *
 * <p>语义：输入为打卡日期集合（调用方按通过终态 PASSED/RE_PASSED 过滤并映射为
 * 自然日后传入；本方法容忍乱序与重复，内部以 {@link HashSet} 归一）。返回
 * {@code {current, longest}}：current 为截止 today 的当前连续天数——集合含 today
 * 则从 today 往回连续计数；否则含 yesterday 则从 yesterday 往回连续计数
 * （连击活到当日结束）；两者均不含则为 0。longest 为全集合最大连续天数
 * （跨断档取最大连续段）。null/空集合返回 {@code {0, 0}}。</p>
 */
public final class StreakCalculator {

    private StreakCalculator() {
        // 纯静态工具类：禁止实例化
    }

    /**
     * 计算当前连续天数与历史最长连续天数。
     *
     * @param distinctDates 打卡日期集合（无需排序/去重，方法内部归一；null 视为空）
     * @param today         服务器当日（LocalDate.now() 由调用方注入，便于固定日期断言）
     * @return {@code {current, longest}}
     */
    public static int[] compute(List<LocalDate> distinctDates, LocalDate today) {
        if (distinctDates == null || distinctDates.isEmpty()) {
            return new int[]{0, 0};
        }
        Set<LocalDate> dates = new HashSet<>(distinctDates);

        int current = 0;
        LocalDate anchor = dates.contains(today) ? today
                : (dates.contains(today.minusDays(1)) ? today.minusDays(1) : null);
        if (anchor != null) {
            current = 1;
            LocalDate cursor = anchor.minusDays(1);
            while (dates.contains(cursor)) {
                current++;
                cursor = cursor.minusDays(1);
            }
        }

        int longest = 0;
        List<LocalDate> sorted = new ArrayList<>(dates);
        sorted.sort(Comparator.naturalOrder());
        int run = 0;
        LocalDate prev = null;
        for (LocalDate d : sorted) {
            run = (prev != null && d.equals(prev.plusDays(1))) ? run + 1 : 1;
            if (run > longest) {
                longest = run;
            }
            prev = d;
        }
        return new int[]{current, longest};
    }
}
