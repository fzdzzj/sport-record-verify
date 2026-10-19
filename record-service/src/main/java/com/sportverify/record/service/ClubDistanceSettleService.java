package com.sportverify.record.service;

import com.sportverify.api.leaderboard.LeaderboardApi;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.ClubDTO;
import com.sportverify.common.result.Result;
import com.sportverify.record.entity.ClubDistanceContribution;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.ClubDistanceContributionMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * 跑团里程入账旁路服务（TASK-276 R2 立项，TASK-278 R2 锚点化重构）。
 *
 * <p><b>挂点</b>：{@code SportRecordService.statusCallback} 的终态迁移成功分支（乐观锁 UPDATE
 * 影响 1 行后）。本服务只做「按净额增量把该记录的里程记到/撤出跑团」，不回写
 * {@code sport_record} 任何列，也不改变状态机本身。</p>
 *
 * <p><b>净额对账口径</b>（以库内记录为权威，不信任事件体）：</p>
 * <ul>
 *   <li>迁移至 {@code PASSED(2)} / {@code RE_PASSED(5)} → {@code +distance}（米）；</li>
 *   <li>迁移至 {@code RE_CONFIRMED(6)} → {@code -distance}（米）回退；</li>
 *   <li>其余终态（如首次判定即 REJECTED）不入账也不回退（从未入账，无需净额抵消）。</li>
 * </ul>
 *
 * <p><b>幂等（TASK-278 R2：DB 锚点取代 Redis 幂等键）</b>：{@code club_distance_contribution}
 * 的 record_id 主键即回退锚点，状态迁移走乐观 UPDATE、影响行数决定是否上报：</p>
 * <ul>
 *   <li>入账方向：{@code INSERT IGNORE} 命中 1 行 = 新锚点，上报 {@code +distance}；命中 0 行时
 *       仅 {@code REVERSED → CREDITED}（改判再次通过）可迁移成功并补账；仍 {@code CREDITED}
 *       （重复投递/重放）→ 幂等跳过；</li>
 *   <li>回退方向：{@code CREDITED → REVERSED} 影响 1 行 → 按<b>锚点行快照</b>（clubId /
 *       weekStart / distanceMeters）上报 {@code -distance}；影响 0 行（已回退 / 从未入账）→ 跳过，
 *       不产生负里程。</li>
 * </ul>
 * <p>因此 TASK-276 的 {@code club:distance:settle:{recordId}} 24h TTL Redis 幂等键整体退役：
 * 键过期导致的「回退静默跳过、贡献 ZSet 残留正里程」（N4）不再存在，Redisson 依赖随之收敛。</p>
 *
 * <p><b>时点漂移根治</b>：回退不再 {@code resolveClub}（换团后不按当前所在团错扣，N5）、
 * 不再重查 {@code sport_record.distance}（按入账快照值扣减）；{@code week_start} 由本侧在入账时
 * 按东八口径归一写入锚点并随 payload 上报，回退回放入账周锚点（跨周改判不再扣错周榜）。</p>
 *
 * <p><b>best-effort 旁路</b>：任何失败（记录缺失、无跑团、user-service / leaderboard-service
 * 降级、DB 抖动）只 WARN 留证并返回，<b>严禁抛出</b>——状态回调主流程不得被跑团里程入账阻断。
 * <b>Feign 失败不回滚锚点占位</b>（裁决④）：状态回调无重放语义，回滚占位无意义；锚点为权威源、
 * ZSet 是投影，缺口由 leaderboard-service 定时对账以锚点汇总裁剪收敛（ADR-0009）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClubDistanceSettleService {

    /** 公里 → 米换算（sport_record.distance 为公里，跑团榜 ZSet 口径为米） */
    private static final double METERS_PER_KM = 1000d;
    /** 业务时区（东八）：周锚点口径沿 TASK-259 BUSINESS_ZONE，不随服务器默认时区漂移 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final SportRecordMapper sportRecordMapper;
    private final ClubDistanceContributionMapper clubDistanceContributionMapper;
    private final UserApi userApi;
    private final LeaderboardApi leaderboardApi;

    /**
     * 终态迁移成功后的入账入口（best-effort，永不抛出）。
     *
     * @param recordId 运动记录 id（锚点主键）
     * @param toStatus 本次迁移的目标状态（{@link RecordStatus} 码值）
     */
    public void settleOnMigration(Long recordId, Integer toStatus) {
        if (recordId == null || toStatus == null) {
            return;
        }
        int direction = directionOf(toStatus);
        if (direction == 0) {
            // 非跑团里程相关终态（SUBMITTED/VERIFYING/APPEALING/REJECTED 等）：不入账也不回退
            return;
        }
        try {
            if (direction > 0) {
                credit(recordId, toStatus);
            } else {
                reverse(recordId);
            }
        } catch (Exception e) {
            // best-effort：任何异常只告警留证，绝不抛出阻断状态回调主链路（ADR-0009 跨服务最终一致）
            log.warn("跑团里程结算失败（best-effort，不阻断状态回调，待对账收敛）：recordId={}, toStatus={}",
                    recordId, toStatus, e);
        }
    }

    /** 目标状态 → 净额方向：+1 入账 / -1 回退 / 0 与本域无关 */
    private static int directionOf(Integer toStatus) {
        if (toStatus == RecordStatus.PASSED.getCode() || toStatus == RecordStatus.RE_PASSED.getCode()) {
            return 1;
        }
        if (toStatus == RecordStatus.RE_CONFIRMED.getCode()) {
            return -1;
        }
        return 0;
    }

    /**
     * 入账方向：以记录为权威取里程与归属人，定位<b>当前</b>所在跑团（入账时点团即当时所在团，
     * 语义正确），先把锚点落库（INSERT IGNORE / re-credit 乐观迁移）再上报 {@code +distance}。
     */
    private void credit(Long recordId, Integer toStatus) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null || record.getUserId() == null) {
            log.warn("跑团里程入账跳过：记录不存在或缺归属人，recordId={}", recordId);
            return;
        }
        BigDecimal distance = record.getDistance();
        if (distance == null || distance.signum() <= 0) {
            log.info("跑团里程入账跳过：记录里程缺失或非正，recordId={}, distance={}", recordId, distance);
            return;
        }
        ClubDTO club = resolveClub(record.getUserId());
        if (club == null || club.getId() == null) {
            log.info("跑团里程入账跳过：用户未加入跑团，recordId={}, userId={}", recordId, record.getUserId());
            return;
        }
        double meters = distance.doubleValue() * METERS_PER_KM;
        LocalDate weekStart = currentWeekStart();
        LocalDateTime now = LocalDateTime.now();
        ClubDistanceContribution anchor = new ClubDistanceContribution();
        anchor.setRecordId(recordId);
        anchor.setClubId(club.getId());
        anchor.setUserId(record.getUserId());
        anchor.setDistanceMeters(BigDecimal.valueOf(meters));
        anchor.setWeekStart(weekStart);
        anchor.setStatus(ClubDistanceContribution.STATUS_CREDITED);
        anchor.setCreatedAt(now);
        boolean credit = clubDistanceContributionMapper.insertIgnore(anchor) == 1;
        if (!credit) {
            // 已有锚点：仅 REVERSED → CREDITED（改判再次通过）可迁移成功并补账；
            // 仍 CREDITED（重复投递/人工重放）→ 幂等跳过
            credit = clubDistanceContributionMapper.reactivate(
                    anchor, ClubDistanceContribution.STATUS_REVERSED) == 1;
        }
        if (!credit) {
            log.info("跑团里程入账幂等跳过（锚点已 CREDITED）：recordId={}, toStatus={}", recordId, toStatus);
            return;
        }
        report(recordId, club.getId(), club.getName(), record.getUserId(), meters, weekStart);
    }

    /**
     * 回退方向：只读锚点行（不 resolveClub、不重查记录里程），乐观迁移
     * {@code CREDITED → REVERSED} 影响 1 行后按锚点快照上报 {@code -distance}。
     */
    private void reverse(Long recordId) {
        ClubDistanceContribution anchor = clubDistanceContributionMapper.selectById(recordId);
        if (anchor == null) {
            // 「无贡献不回滚」：记录从未入账（如首次判定即 REJECTED），不产生负里程
            log.info("无跑团里程锚点，回退跳过：recordId={}", recordId);
            return;
        }
        BigDecimal snapshot = anchor.getDistanceMeters();
        if (snapshot == null || snapshot.signum() <= 0) {
            log.warn("跑团里程锚点里程异常，回退跳过：recordId={}, distanceMeters={}", recordId, snapshot);
            return;
        }
        int rows = clubDistanceContributionMapper.updateStatus(recordId,
                ClubDistanceContribution.STATUS_CREDITED,
                ClubDistanceContribution.STATUS_REVERSED, LocalDateTime.now());
        if (rows == 0) {
            log.info("跑团里程已回退（重复事件或从未入账），幂等跳过：recordId={}", recordId);
            return;
        }
        report(recordId, anchor.getClubId(), null, anchor.getUserId(),
                -snapshot.doubleValue(), anchor.getWeekStart());
    }

    /** 上报净额增量（正入账 / 负回退）；Feign 失败由外层 catch 吞并，锚点占位不回滚（裁决④） */
    private void report(Long recordId, Long clubId, String clubName, Long userId,
                        double meters, LocalDate weekStart) {
        LeaderboardApi.ClubDistancePayload payload = new LeaderboardApi.ClubDistancePayload();
        payload.setClubId(clubId);
        payload.setClubName(clubName);
        payload.setDistanceMeters(meters);
        payload.setWeekStart(weekStart);
        payload.setUserId(userId);
        leaderboardApi.recordClubDistance(payload);
        log.info("跑团里程结算成功：recordId={}, clubId={}, userId={}, {}m, weekStart={}",
                recordId, clubId, userId, meters, weekStart);
    }

    /** 入账时点周锚点：东八墙钟当周周一（ISO 周，与周报/周榜周一锚点同口径） */
    private static LocalDate currentWeekStart() {
        return LocalDate.now(BUSINESS_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** 定位用户所在跑团（未入团/降级返回 null → 入账跳过） */
    private ClubDTO resolveClub(Long userId) {
        try {
            Result<ClubDTO> res = userApi.getClubByUserId(userId);
            return res == null ? null : res.getData();
        } catch (Exception e) {
            log.warn("跑团定位失败（user-service 降级），本次入账跳过：userId={}", userId, e);
            return null;
        }
    }
}
