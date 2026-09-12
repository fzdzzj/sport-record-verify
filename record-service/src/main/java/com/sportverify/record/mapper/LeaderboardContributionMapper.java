package com.sportverify.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.record.entity.LeaderboardContribution;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 榜单贡献 Mapper（record_db.leaderboard_contribution，record_id 单列主键）。
 *
 * <p>本表是榜单的<b>权威源与回滚锚点</b>：所有状态迁移走「乐观语义 UPDATE，
 * 影响行数 = 操作是否真正生效」，支撑入榜/回滚双幂等（规范差异「事件幂等」「改判回滚」）。
 * ZSet 只是热读层，漂移由定时结算任务以 {@link #selectActiveSummaries} 汇总为准纠偏。</p>
 */
public interface LeaderboardContributionMapper extends BaseMapper<LeaderboardContribution> {

    /**
     * 幂等插入（INSERT IGNORE）：record_id 主键冲突静默跳过。
     * 返回 1 = 新锚点（本次 ZINCRBY 生效方）；返回 0 = 已入过榜（重复事件/重放，调用方跳过加分）。
     */
    @Insert("INSERT IGNORE INTO leaderboard_contribution (record_id, user_id, distance, status) " +
            "VALUES (#{recordId}, #{userId}, #{distance}, #{status})")
    int insertIgnore(@Param("recordId") Long recordId,
                     @Param("userId") Long userId,
                     @Param("distance") java.math.BigDecimal distance,
                     @Param("status") Integer status);

    /**
     * 重新激活（申诉链路：PASSED→驳回回滚→再申诉改判通过 RE_PASSED 时复用锚点行）：
     * 仅 ROLLED_BACK → ACTIVE 可流转，影响行数 1 = 本次 ZINCRBY 生效方。
     */
    @Update("UPDATE leaderboard_contribution SET status = #{toStatus} " +
            "WHERE record_id = #{recordId} AND status = #{fromStatus}")
    int updateStatus(@Param("recordId") Long recordId,
                     @Param("fromStatus") Integer fromStatus,
                     @Param("toStatus") Integer toStatus);

    /**
     * 结算标记：批量为 ACTIVE 行盖 settled_at 时间戳（对账已覆盖本行口径）。
     */
    @Update("UPDATE leaderboard_contribution SET settled_at = #{settledAt} " +
            "WHERE status = #{status}")
    int markSettled(@Param("status") Integer status,
                    @Param("settledAt") LocalDateTime settledAt);

    /**
     * 结算对账数据源：ACTIVE 贡献按用户汇总（SUM(distance) GROUP BY user_id）。
     * 演示规模直接全量聚合；生产可改增量游标/分页。
     */
    @Select("SELECT user_id AS userId, SUM(distance) AS totalDistance " +
            "FROM leaderboard_contribution WHERE status = #{status} GROUP BY user_id")
    List<UserMileage> selectActiveSummaries(@Param("status") Integer status);

    /**
     * 结算对账聚合行（user_id + 累计 pass 里程），列别名与属性对齐映射。
     */
    class UserMileage {

        /** 用户ID */
        private Long userId;

        /** 该用户 ACTIVE 贡献里程合计 */
        private java.math.BigDecimal totalDistance;

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public java.math.BigDecimal getTotalDistance() {
            return totalDistance;
        }

        public void setTotalDistance(java.math.BigDecimal totalDistance) {
            this.totalDistance = totalDistance;
        }
    }
}
