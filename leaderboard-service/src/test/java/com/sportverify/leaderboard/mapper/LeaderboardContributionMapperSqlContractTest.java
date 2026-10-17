package com.sportverify.leaderboard.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期榜聚合 SQL 契约判别（TASK-260 R2/G5）：selectRangeSummaries 必须带
 * {@code ORDER BY totalDistance DESC, user_id ASC} 稳定排序——totalDistance 同分用户的
 * 名次不再随引擎返回序漂移（照 LeaderboardDailySummaryMapper 先例与 TASK-142 SQL 契约测试范式）。
 *
 * <p>本类只守卫 Mapper 注解 SQL 文本的<strong>排序契约</strong>，使「同分稳定排序」在纯 JVM
 * 单测中可执行、可回归；<strong>真实引擎下的同分次序行为</strong>由 scratch MySQL 实证，
 * 不由 Mockito 预制排序结果冒充。</p>
 */
class LeaderboardContributionMapperSqlContractTest {

    @Test
    void selectRangeSummaries_tieBreakByUserIdAsc() throws Exception {
        Method m = LeaderboardContributionMapper.class.getMethod("selectRangeSummaries",
                Integer.class, LocalDateTime.class, LocalDateTime.class, int.class);

        Select select = m.getAnnotation(Select.class);
        assertNotNull(select, "selectRangeSummaries 应为 @Select 注解查询");
        String sql = String.join(" ", select.value());
        assertTrue(sql.contains("ORDER BY totalDistance DESC, user_id ASC"),
                "周期榜聚合必须带同分 tie-break（user_id ASC），实测 SQL=" + sql);
        assertTrue(sql.contains("LIMIT #{size}"),
                "周期榜仍受榜单容量上限约束，实测 SQL=" + sql);
    }
}
