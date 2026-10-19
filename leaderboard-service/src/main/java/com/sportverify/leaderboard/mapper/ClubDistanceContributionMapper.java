package com.sportverify.leaderboard.mapper;

import com.sportverify.leaderboard.entity.ClubDistanceContribution;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 跑团里程锚点只读 Mapper（record_db.club_distance_contribution，TASK-278 R3）。
 *
 * <p><b>只读依赖</b>（跨服务同库只读，沿 {@link SportRecordMapper} 读 {@code sport_record} 的
 * ADR-0005 先例）：接口刻意不继承 MyBatis-Plus {@code BaseMapper}、不提供任何写方法——
 * 锚点行由 record-service 的跑团里程旁路写入，榜单侧只做汇总对账，
 * 从结构上杜绝「对账顺手翻转锚点状态」的双写（裁决①）。</p>
 *
 * <p><b>孤儿过滤（只读口径）</b>：三条汇总 SQL 均以 {@code INNER JOIN sport_record} 存活连接
 * 过滤——锚点 {@code status = CREDITED} 但对应 {@code sport_record} 行已被物理删除的孤儿
 * 贡献自然落出汇总（榜上随之剔除），<b>锚点行保持「曾入账」的历史事实不被翻转</b>。</p>
 *
 * <p>汇总口径是三组 ZSet 的权威源：{@code (club_id) → SUM} 纠偏跑团总榜、
 * {@code (club_id, week_start) → SUM} 纠偏逐周键、{@code (club_id, user_id) → SUM} 纠偏
 * 团内成员贡献榜（覆盖式 ZADD，幂等纠偏）。</p>
 */
public interface ClubDistanceContributionMapper {

    /**
     * 跑团总榜权威汇总：CREDITED 且记录仍存在的锚点按 club_id 汇总里程（米）。
     *
     * @param status 计入口径（{@link ClubDistanceContribution#STATUS_CREDITED}）
     * @return 每团一行（clubId + totalDistance）；无锚点时为空表
     */
    @Select("SELECT c.club_id AS clubId, SUM(c.distance_meters) AS totalDistance "
            + "FROM club_distance_contribution c "
            + "INNER JOIN sport_record sr ON sr.id = c.record_id "
            + "WHERE c.status = #{status} "
            + "GROUP BY c.club_id")
    List<ClubDistanceContribution> selectCreditedClubSummaries(@Param("status") Integer status);

    /**
     * 周榜权威汇总：CREDITED 且记录仍存在的锚点按 (club_id, week_start) 汇总里程（米）。
     *
     * @param status 计入口径（CREDITED）
     * @return 每团每周一行（clubId + weekStart + totalDistance）
     */
    @Select("SELECT c.club_id AS clubId, c.week_start AS weekStart, "
            + "SUM(c.distance_meters) AS totalDistance "
            + "FROM club_distance_contribution c "
            + "INNER JOIN sport_record sr ON sr.id = c.record_id "
            + "WHERE c.status = #{status} "
            + "GROUP BY c.club_id, c.week_start")
    List<ClubDistanceContribution> selectCreditedWeeklySummaries(@Param("status") Integer status);

    /**
     * 团内成员贡献权威汇总：CREDITED 且记录仍存在的锚点按 (club_id, user_id) 汇总里程（米）。
     *
     * @param status 计入口径（CREDITED）
     * @return 每团每成员一行（clubId + userId + totalDistance）
     */
    @Select("SELECT c.club_id AS clubId, c.user_id AS userId, "
            + "SUM(c.distance_meters) AS totalDistance "
            + "FROM club_distance_contribution c "
            + "INNER JOIN sport_record sr ON sr.id = c.record_id "
            + "WHERE c.status = #{status} "
            + "GROUP BY c.club_id, c.user_id")
    List<ClubDistanceContribution> selectCreditedMemberSummaries(@Param("status") Integer status);
}
