package com.sportverify.record.service;

import com.sportverify.api.leaderboard.LeaderboardApi;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.ClubDTO;
import com.sportverify.common.result.Result;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.SportRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 跑团里程入账旁路服务（TASK-276 R2）。
 *
 * <p><b>挂点</b>：{@code SportRecordService.statusCallback} 的终态迁移成功分支（乐观锁 UPDATE
 * 影响 1 行后）。本服务只做「按净额增量把该记录的里程记到/撤出用户所在跑团」，不回写
 * {@code sport_record} 任何列，也不改变状态机本身。</p>
 *
 * <p><b>净额对账口径</b>（以库内记录为权威，不信任事件体）：</p>
 * <ul>
 *   <li>迁移至 {@code PASSED(2)} / {@code RE_PASSED(5)} 且该 recordId 此前<b>未入账</b> →
 *       {@code +distance}（米）；</li>
 *   <li>迁移至 {@code RE_CONFIRMED(6)} 且该 recordId 此前<b>已入账</b> → {@code -distance}（米）回退；</li>
 *   <li>其余终态（如首次判定即 REJECTED）不入账也不回退（从未入账，无需净额抵消）。</li>
 * </ul>
 *
 * <p>由此申诉改判链路天然净额正确：{@code REJECTED → RE_PASSED} 补账一次，
 * {@code PASSED → RE_CONFIRMED} 回退一次，{@code RE_CONFIRMED → RE_PASSED}（改判再次通过）
 * 重新补账——每步都由 recordId 级状态机保证「同一方向只生效一次」。</p>
 *
 * <p><b>幂等</b>：Redisson {@code RBucket} 键 {@code club:distance:settle:{recordId}}
 * （TTL 24h，沿 {@code NotificationEventConsumer} 的 eventId {@code trySet} 范式），
 * 值为该记录已生效的入账方向（{@code CREDITED} / {@code REVERSED}）：</p>
 * <ul>
 *   <li>首次入账 → {@code trySet(CREDITED)} 成功即生效（重复投递/重放直接跳过）；</li>
 *   <li>回退 → 仅当现值确为 {@code CREDITED} 时 {@code compareAndSet(CREDITED, REVERSED)} 成功才扣减
 *       （从未入账记录回退不产生负里程）；</li>
 *   <li>回退后再次通过 → {@code compareAndSet(REVERSED, CREDITED)} 成功才补账。</li>
 * </ul>
 *
 * <p><b>best-effort 旁路</b>：任何失败（记录缺失、无跑团、user-service / leaderboard-service 降级、
 * Redis 抖动）只 WARN 留证并返回，<b>严禁抛出</b>——状态回调主流程不得被跑团里程入账阻断。
 * 少记/漏记由后续对账课题兜底（跨服务最终一致，ADR-0009）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClubDistanceSettleService {

    /** 入账幂等键前缀（recordId 维度，{} 为 recordId） */
    public static final String SETTLE_KEY_PREFIX = "club:distance:settle:";
    /** 入账幂等键 TTL（小时）：与 MQ 去重窗口同量级，覆盖事件重投与人工重放 */
    static final long SETTLE_TTL_HOURS = 24L;
    /** 幂等态：该记录里程已入跑团榜 */
    static final String STATE_CREDITED = "CREDITED";
    /** 幂等态：该记录里程已从跑团榜回退 */
    static final String STATE_REVERSED = "REVERSED";
    /** 公里 → 米换算（sport_record.distance 为公里，跑团榜 ZSet 口径为米） */
    private static final double METERS_PER_KM = 1000d;

    private final SportRecordMapper sportRecordMapper;
    private final RedissonClient redissonClient;
    private final UserApi userApi;
    private final LeaderboardApi leaderboardApi;

    /**
     * 终态迁移成功后的入账入口（best-effort，永不抛出）。
     *
     * @param recordId 运动记录 id
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
            if (!claimTransition(recordId, direction)) {
                log.info("跑团里程入账幂等跳过（该方向已生效）：recordId={}, toStatus={}", recordId, toStatus);
                return;
            }
            double meters = distance.doubleValue() * METERS_PER_KM * direction;
            LeaderboardApi.ClubDistancePayload payload = new LeaderboardApi.ClubDistancePayload();
            payload.setClubId(club.getId());
            payload.setClubName(club.getName());
            payload.setDistanceMeters(meters);
            payload.setWeekStart(null);
            payload.setUserId(record.getUserId());
            leaderboardApi.recordClubDistance(payload);
            log.info("跑团里程入账成功：recordId={}, clubId={}, userId={}, {}m（toStatus={}）",
                    recordId, club.getId(), record.getUserId(), meters, toStatus);
        } catch (Exception e) {
            // best-effort：任何异常只告警留证，绝不抛出阻断状态回调主链路（ADR-0009 跨服务最终一致）
            log.warn("跑团里程入账失败（best-effort，不阻断状态回调，待对账收敛）：recordId={}, toStatus={}",
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

    /**
     * recordId 级幂等占位：入账方向 {@code trySet} 首置，回退/再入账方向以
     * {@code compareAndSet} 做状态迁移（同一方向恒只生效一次）。
     *
     * @return true = 本次调用应真正上报里程增量
     */
    private boolean claimTransition(Long recordId, int direction) {
        RBucket<String> bucket = redissonClient.getBucket(SETTLE_KEY_PREFIX + recordId);
        if (direction > 0) {
            if (bucket.trySet(STATE_CREDITED, SETTLE_TTL_HOURS, TimeUnit.HOURS)) {
                return true;
            }
            // 曾被回退（改判再次通过）→ 重新入账；仍 CREDITED（重复事件/重放）→ 幂等跳过
            if (bucket.compareAndSet(STATE_REVERSED, STATE_CREDITED)) {
                bucket.expire(Duration.ofHours(SETTLE_TTL_HOURS));
                return true;
            }
            return false;
        }
        // 回退方向：仅「确实入过账」才扣减（从未入账记录不产生负里程）
        if (bucket.compareAndSet(STATE_CREDITED, STATE_REVERSED)) {
            bucket.expire(Duration.ofHours(SETTLE_TTL_HOURS));
            return true;
        }
        return false;
    }
}
