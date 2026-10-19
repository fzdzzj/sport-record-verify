package com.sportverify.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sportverify.record.entity.ClubDistanceContribution;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 跑团里程锚点 Mapper（record_db.club_distance_contribution，TASK-278 R2）。
 *
 * <p>本表是跑团里程的<b>权威源与回退锚点</b>：状态迁移一律走「乐观语义 UPDATE，
 * 影响行数 = 操作是否真正生效」，支撑入账/回退双幂等；三组 ZSet 只是投影，
 * 漂移由 leaderboard-service 定时对账以汇总为准纠偏（ADR-0009 最终一致）。
 * 三个方法的形态与 {@code LeaderboardContributionMapper#insertIgnore} /
 * {@code #reactivate} / {@code #updateStatus} 逐位同构（先例即范本）。</p>
 */
public interface ClubDistanceContributionMapper extends BaseMapper<ClubDistanceContribution> {

    /**
     * 幂等插入（INSERT IGNORE）：record_id 主键冲突静默跳过。
     *
     * @param anchor 锚点行（recordId/clubId/userId/distanceMeters/weekStart/status/createdAt 必填）
     * @return 1 = 新锚点（本次上报 +distance 生效）；0 = 已有锚点（调用方转 re-credit 判定）
     */
    @Insert("INSERT IGNORE INTO club_distance_contribution "
            + "(record_id, club_id, user_id, distance_meters, week_start, status, created_at) "
            + "VALUES (#{recordId}, #{clubId}, #{userId}, #{distanceMeters}, #{weekStart}, "
            + "#{status}, #{createdAt})")
    int insertIgnore(ClubDistanceContribution anchor);

    /**
     * 改判再次通过方向（REVERSED → CREDITED）：迁移状态<b>并刷新锚点快照</b>为本次入账时点的
     * 团/周/里程（{@code reversed_at} 清空）。
     *
     * <p>刷新快照的理由：回退按锚点行快照扣减（裁决③），若 re-credit 只翻状态不刷新快照，
     * 改判链路「通过→回退→再通过」跨团/跨周时将出现「本次加在新团/新周、下次回退仍扣旧团/旧周」
     * 的反向漂移——即把 N5/跨周错扣从首次回退推后到二次回退。刷新后恒有
     * 「锚点行 = 当前 CREDITED 时点快照」不变式。</p>
     *
     * @param anchor     本次入账时点快照（recordId/clubId/userId/distanceMeters/weekStart/status 必填）
     * @param fromStatus 期望现态（REVERSED）
     * @return 影响行数：1 = 本次 re-credit 生效（上报 +distance）；0 = 现态非 fromStatus（幂等跳过）
     */
    @Update("UPDATE club_distance_contribution SET status = #{anchor.status}, reversed_at = NULL, "
            + "club_id = #{anchor.clubId}, user_id = #{anchor.userId}, "
            + "distance_meters = #{anchor.distanceMeters}, week_start = #{anchor.weekStart}, "
            + "created_at = #{anchor.createdAt} "
            + "WHERE record_id = #{anchor.recordId} AND status = #{fromStatus}")
    int reactivate(@Param("anchor") ClubDistanceContribution anchor,
                   @Param("fromStatus") Integer fromStatus);

    /**
     * 回退方向（CREDITED → REVERSED）：仅当现态确为 fromStatus 时生效（同一方向恒只生效一次）。
     *
     * @param recordId   记录ID（锚点主键）
     * @param fromStatus 期望现态（CREDITED）
     * @param toStatus   目标状态（REVERSED）
     * @param reversedAt 回退时间
     * @return 影响行数：1 = 本次回退生效（上报 -distance）；0 = 已回退/从未入账（幂等跳过）
     */
    @Update("UPDATE club_distance_contribution SET status = #{toStatus}, reversed_at = #{reversedAt} "
            + "WHERE record_id = #{recordId} AND status = #{fromStatus}")
    int updateStatus(@Param("recordId") Long recordId,
                     @Param("fromStatus") Integer fromStatus,
                     @Param("toStatus") Integer toStatus,
                     @Param("reversedAt") LocalDateTime reversedAt);
}
