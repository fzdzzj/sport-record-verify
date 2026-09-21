package com.sportverify.leaderboard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 榜单每日快照实体（record_db.leaderboard_daily_summary）。
 *
 * <p>口径为<b>截止当日的 ACTIVE 累计快照</b>，不是当日增量：结算任务手里正好握着
 * {@code selectActiveSummaries} 的每用户累计值，累计口径零额外查询；
 * 增量由相邻两日相减得到。</p>
 *
 * <p>联合主键 {@code (stat_date, user_id)} 即幂等保证：每轮结算对同一日同一用户
 * 重复 upsert 只留一行，无需"今天是否已写过"的额外状态位。</p>
 *
 * <p>写入路径：{@code settleAndReconcile()} 结算成功后，由单条
 * {@code INSERT ... SELECT ... ON DUPLICATE KEY UPDATE} 完成"按用户汇总 + 幂等写入"
 * （无本地事务，见 ADR-0009；原子性来自单语句）。读取路径：{@code GET /api/leaderboard/daily}。</p>
 */
@Data
@TableName("leaderboard_daily_summary")
public class LeaderboardDailySummary {

    /** 统计日期（服务器时区，快照当日累计）；联合主键之一，非自增 */
    @TableId(type = IdType.INPUT)
    private LocalDate statDate;

    /** 用户ID（联合主键之一） */
    private Long userId;

    /** 截止当日 ACTIVE 累计里程（公里） */
    private BigDecimal totalDistance;

    /** 构成该累计的 ACTIVE 贡献条数 */
    private Integer recordCount;

    /** 本轮结算写入时间 */
    private LocalDateTime updatedAt;
}
