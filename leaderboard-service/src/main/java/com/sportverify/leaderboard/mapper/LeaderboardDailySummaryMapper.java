package com.sportverify.leaderboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.leaderboard.entity.LeaderboardDailySummary;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 榜单每日快照持久层（表 {@code leaderboard_daily_summary}，随 {@code @MapperScan} 注册）。
 *
 * <p>汇总写入刻意压成<b>单条</b> {@code INSERT ... SELECT ... ON DUPLICATE KEY UPDATE}：
 * 「按用户汇总 + 幂等写入」在一条语句内原子完成，因此本类不需要任何本地事务
 * （ADR-0009 禁止给榜单写路径铺 {@code @Transactional}），也不要在 Java 侧逐行循环写
 * （N 次往返）。</p>
 */
public interface LeaderboardDailySummaryMapper extends BaseMapper<LeaderboardDailySummary> {

    /**
     * 以贡献表 ACTIVE 行为源，按用户汇总后 upsert 进当日快照。
     *
     * <p>{@code VALUES(col)} 写法在 MySQL 8.0.20+ 有 alias 语法替代品，但仍受支持，
     * 此处保留以兼容 5.7 口径（compose 为 mysql:8.0）。</p>
     *
     * @param activeStatus {@code ContributionStatus.ACTIVE} 的 code
     * @return 受影响行数（插入记 1、更新记 2，MySQL 语义）
     */
    @Insert("INSERT INTO leaderboard_daily_summary "
            + "(stat_date, user_id, total_distance, record_count, updated_at) "
            + "SELECT CURDATE(), user_id, SUM(distance), COUNT(*), NOW() "
            + "FROM leaderboard_contribution WHERE status = #{activeStatus} "
            + "GROUP BY user_id "
            + "ON DUPLICATE KEY UPDATE total_distance = VALUES(total_distance), "
            + "record_count = VALUES(record_count), updated_at = VALUES(updated_at)")
    int upsertFromActiveContributions(@Param("activeStatus") Integer activeStatus);

    /**
     * 清掉"今日已无任何 ACTIVE 贡献"的残留快照行。
     *
     * <p>upsert 只会覆盖仍有贡献的用户；用户被全量回滚后 {@code GROUP BY} 不再产出该行，
     * 若不清理，报表会一直带着他回滚前的旧里程。单条 DELETE...LEFT JOIN 自证原子，同样不开事务。</p>
     */
    @Delete("DELETE s FROM leaderboard_daily_summary s "
            + "LEFT JOIN (SELECT user_id FROM leaderboard_contribution WHERE status = #{activeStatus} "
            + "           GROUP BY user_id) a ON a.user_id = s.user_id "
            + "WHERE s.stat_date = CURDATE() AND a.user_id IS NULL")
    int deleteStaleToday(@Param("activeStatus") Integer activeStatus);

    /**
     * 取某日快照前 N 名（里程降序）。
     *
     * <p>字段用别名直接映射驼峰，避免依赖全局 {@code map-underscore-to-camel-case} 配置口径。</p>
     */
    @Select("SELECT stat_date AS statDate, user_id AS userId, total_distance AS totalDistance, "
            + "record_count AS recordCount, updated_at AS updatedAt "
            + "FROM leaderboard_daily_summary WHERE stat_date = #{statDate} "
            + "ORDER BY total_distance DESC, user_id ASC LIMIT #{limit}")
    List<LeaderboardDailySummary> selectTopByDate(@Param("statDate") LocalDate statDate,
                                                  @Param("limit") int limit);
}
