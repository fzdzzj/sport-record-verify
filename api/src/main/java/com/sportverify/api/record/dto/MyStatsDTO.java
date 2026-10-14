package com.sportverify.api.record.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

/**
 * 个人核心统计 DTO（record-api 契约，GET /record/api/records/my/stats 响应）。
 *
 * <p>统计口径（TASK-231）：基于该用户<b>全量</b>记录的服务端聚合，与列表分页/过滤彻底解耦——
 * {@code totalCount}/{@code totalDistance}/{@code passCount} 覆盖全部记录，
 * {@code dailyDistances} 覆盖近 30 个自然日（含今日）。</p>
 *
 * <ul>
 *   <li>{@code totalCount}：记录总数；</li>
 *   <li>{@code totalDistance}：总里程（km，两位小数，HALF_UP）；</li>
 *   <li>{@code passCount}：通过终态记录数（PASSED(2) / RE_PASSED(5)，与榜单入榜口径一致）；</li>
 *   <li>{@code passRate}：通过率格式化字符串（如 {@code 75.0%}；无记录时为 {@code 0%}）；</li>
 *   <li>{@code dailyDistances}：近 30 天每日里程，key 为 {@code YYYY-MM-DD}（升序，恒 30 个键），
 *       value 为该日里程合计（两位小数）；当日无记录补 {@code 0}。</li>
 * </ul>
 */
@Data
public class MyStatsDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录总数（全量，不受列表分页与过滤影响） */
    private Long totalCount;

    /** 总里程（km，两位小数） */
    private BigDecimal totalDistance;

    /** 通过终态记录数（PASSED(2) / RE_PASSED(5)） */
    private Long passCount;

    /** 通过率格式化字符串（如 75.0%；无记录时为 0%） */
    private String passRate;

    /** 近 30 天每日里程（key=YYYY-MM-DD 升序，value=当日里程合计，两位小数，恒 30 键） */
    private Map<String, BigDecimal> dailyDistances;
}
