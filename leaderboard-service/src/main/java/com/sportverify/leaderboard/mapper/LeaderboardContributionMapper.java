package com.sportverify.leaderboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.leaderboard.entity.LeaderboardContribution;
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
     *
     * <p><b>仅迁移状态、不清 settled_at</b>（保留历史结算戳）——用于回滚方向（ACTIVE→ROLLED_BACK，
     * 见 {@link #reactivate} 反向说明）：回滚行不入 {@code selectRangeSummaries} 的 {@code settled_at}
     * 窗口，旧戳保留无害。</p>
     */
    @Update("UPDATE leaderboard_contribution SET status = #{toStatus} " +
            "WHERE record_id = #{recordId} AND status = #{fromStatus}")
    int updateStatus(@Param("recordId") Long recordId,
                     @Param("fromStatus") Integer fromStatus,
                     @Param("toStatus") Integer toStatus);

    /**
     * 重激活（E24 R6，申诉改判通过方向 ROLLED_BACK → ACTIVE）：升级状态<b>并把 settled_at 置 NULL</b>。
     *
     * <p>缺陷链：旧实现 {@link #updateStatus} 只 SET status 不清 settled_at → 改判重激活后
     * {@code settled_at} 仍保留第一次入榜结算的旧戳 T1 → {@code markSettled} 只盖
     * {@code settled_at IS NULL} 行不再盖 → 改判里程永久归 T1 旧周期，当周/当月榜漏计。
     * 本方法清戳后，下一轮 {@code markSettled} 重盖当期，改判里程归位当周/当月窗口。</p>
     *
     * <p>与 {@link #updateStatus} 的双向语义差异：<b>updateStatus 保留结算戳（回滚方向用）、
     * reactivate 清结算戳（重激活方向用）</b>——回滚行不入窗故旧戳无害，重激活行须清零才能
     * 让结算按当期重盖。</p>
     */
    @Update("UPDATE leaderboard_contribution SET status = #{toStatus}, settled_at = NULL " +
            "WHERE record_id = #{recordId} AND status = #{fromStatus}")
    int reactivate(@Param("recordId") Long recordId,
                   @Param("fromStatus") Integer fromStatus,
                   @Param("toStatus") Integer toStatus);

    /**
     * 结算标记：批量为 ACTIVE 且<b>尚未结算</b>（{@code settled_at IS NULL}）的贡献行
     * 盖 settled_at 时间戳（TASK-235 时间窗口修复）。
     *
     * <p>「仅对未结算行盖戳」是 {@code selectRangeSummaries} 今日/本周/本月窗口的前提：
     * 若重复结算会覆盖既有历史结算时间，则同一行被多次盖戳后按 {@code settled_at}
     * 归期失效（历史行被卷进错误周期），榜单长期失真。追加 {@code AND settled_at IS NULL}
     * 后历史行结算时间只写一次，窗口归属自此自洽（对账已覆盖本行口径）。</p>
     */
    @Update("UPDATE leaderboard_contribution SET settled_at = #{settledAt} " +
            "WHERE status = #{status} AND settled_at IS NULL")
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
     * 孤儿贡献识别（B16 记录删除与榜单回撤自动对账补偿）：查询 {@code status = ACTIVE}
     * 但对应记录在 {@code sport_record} 中已不存在的孤儿行（越权乱删 / record-service
     * Feign 回滚失败导致物理记录先删而贡献残留）。
     *
     * <p>用 {@code NOT EXISTS} 半连接而非 LEFT JOIN 判空：避免 JOIN 放大逐行匹配成本，
     * 由 {@code (status, settled_at)} 索引（add-idx-leaderboard-contribution-status-settled）
     * 前缀过滤 ACTIVE 后对每行做记录存在性子查询，语义清晰且利于优化器取 status 前缀。</p>
     *
     * @return 孤儿贡献（recordId + userId + distance），供定时结算流转 ROLLED_BACK 终态
     */
    @Select("SELECT lc.record_id AS recordId, lc.user_id AS userId, lc.distance AS totalDistance " +
            "FROM leaderboard_contribution lc " +
            "WHERE lc.status = #{status} " +
            "AND NOT EXISTS (SELECT 1 FROM sport_record sr WHERE sr.id = lc.record_id)")
    List<UserMileage> selectOrphanContributions(@Param("status") Integer status);

    /**
     * 日期范围榜单聚合（TASK-209）：{@code status = ACTIVE} 且 {@code settled_at} 落入
     * 半开窗口 {@code [start, end+1d)} 的贡献按用户汇总里程降序取前 {@code size}。
     *
     * <p>窗口归属口径：按<b>结算时间</b>（{@code settled_at}）而非记录创建时间归属周期——
     * 贡献行由定时结算任务批量盖 settled_at（见 LeaderboardService#settleAndReconcile），
     * 同一用户多行贡献在窗口内累加为总分。已回滚（ROLLED_BACK）行不入窗（status 过滤）。
     * 排序带 {@code user_id ASC} tie-break（G5/TASK-260 R2，照 LeaderboardDailySummaryMapper
     * 先例）：totalDistance 同分用户名次稳定，不随引擎返回序漂移。</p>
     *
     * <p>索引现状（G9/TASK-260 R3 订正）：{@code (status, settled_at)} 复合索引
     * {@code idx_status_settled} 已建（sql/02-record-db.sql L63 + 幂等迁移
     * add-idx-leaderboard-contribution-status-settled.sql），范围扫描走 status 前缀 +
     * settled_at 窗口收窄，与 {@link #selectOrphanContributions} 共用该索引。</p>
     */
    @Select("SELECT user_id AS userId, SUM(distance) AS totalDistance " +
            "FROM leaderboard_contribution WHERE status = #{status} " +
            "AND settled_at >= #{start} AND settled_at < #{endExclusive} " +
            "GROUP BY user_id ORDER BY totalDistance DESC, user_id ASC LIMIT #{size}")
    List<UserMileage> selectRangeSummaries(@Param("status") Integer status,
                                           @Param("start") LocalDateTime start,
                                           @Param("endExclusive") LocalDateTime endExclusive,
                                           @Param("size") int size);

    /**
     * 结算 ZREM 二次复核（TASK-253 R3/E7）：批量确认候选成员当前是否仍有 ACTIVE 贡献行。
     *
     * <p>背景：{@code settleAndReconcile} 以 {@link #selectActiveSummaries} 采 DB target 后 scan
     * ZSet 比对，对「target 缺失」成员批式 ZREM；竞态窗口内 {@code applyVerified}（先写 DB 后
     * ZINCRBY）新入榜的成员 DB 必已落行——复核命中者不得被 ZREM 误删（掉榜至下一轮才收敛），
     * 仍缺失者才是真残留。IN 构造沿仓内既有 {@code <script>}/{@code foreach} 注解模式。</p>
     *
     * @param status  ACTIVE 状态码
     * @param userIds 待复核候选（ZSet 成员，非空；调用方保证）
     * @return 当前仍有 ACTIVE 贡献的用户 id 集合（DISTINCT，去重）
     */
    @Select("<script>" +
            "SELECT DISTINCT user_id FROM leaderboard_contribution " +
            "WHERE status = #{status} AND user_id IN " +
            "<foreach collection='list' item='userId' open='(' separator=',' close=')'>" +
            "#{userId}" +
            "</foreach>" +
            "</script>")
    List<Long> selectActiveUserIds(@Param("status") Integer status,
                                   @Param("list") java.util.Collection<Long> userIds);

    /**
     * 结算对账聚合行（user_id + 累计 pass 里程），列别名与属性对齐映射。
     */
    class UserMileage {

        /** 记录ID（仅孤儿贡献查询 selectOrphanContributions 会带出；汇总查询为 null） */
        private Long recordId;

        /** 用户ID */
        private Long userId;

        /** 该用户 ACTIVE 贡献里程合计 */
        private java.math.BigDecimal totalDistance;

        public Long getRecordId() {
            return recordId;
        }

        public void setRecordId(Long recordId) {
            this.recordId = recordId;
        }

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
