package com.sportverify.leaderboard.service;

import com.sportverify.api.event.RecordVerifyEvents;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.leaderboard.entity.LeaderboardContribution;
import com.sportverify.leaderboard.entity.SportRecordSnapshot;
import com.sportverify.leaderboard.enums.ContributionStatus;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper.UserMileage;
import com.sportverify.leaderboard.mapper.SportRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 排行榜服务（审批版 §4.5/§6.2/§7.1/§7.5，规范差异：仅通过记录入榜 / 事件驱动入榜 /
 * 改判回滚 / 回滚与入榜并发安全 / 总榜查询 / 好友榜查询 / 快照结算防重）。
 *
 * <p><b>服务拆分归属</b>（服务数 4→5，见 ADR-0005）：本服务整体迁自 record-service，
 * 职责边界为「record=记录读写，leaderboard=榜单读热+事件沉淀」；对记录域的依赖收窄为
 * <b>只读</b>——经 {@link SportRecordMapper} 快照查询记录状态/里程做入榜闸门，
 * 对 {@code sport_record} 零写入，写路径与榜单消费互不双写。</p>
 *
 * <p><b>双层数据结构</b>（面试弹药：Redis ZSet 热读 + 快照表权威源）：</p>
 * <ul>
 *   <li><b>热读层</b>：ZSet {@code leaderboard:overall}（member=userId，score=累计 pass 里程），
 *       ZREVRANGE 秒级取前 N，读多写少；</li>
 *   <li><b>权威层</b>：{@code leaderboard_contribution}（record_id 主键=回滚锚点），
 *       每条 pass 记录一行贡献；ZSet 漂移由定时结算任务以本表 ACTIVE 汇总为准纠偏；</li>
 *   <li><b>写入</b>：事件驱动（VERIFIED 入榜 / REJECTED 回滚，消费端 eventId SETNX 幂等），
 *       与校验主链路解耦，最终一致；</li>
 *   <li><b>并发安全</b>：同一记录的入榜与回滚共用 {@code lock:rollback:{recordId}}
 *       串行化（规范差异「回滚与入榜并发安全」），状态迁移走乐观 UPDATE，
 *       影响行数决定是否 ZINCRBY——锚点行为准，重复事件天然幂等。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    // ==================== Redis 键与常量 ====================

    /** 总榜 ZSet 键（member=userId，score=累计 pass 里程，审批版 §4.5） */
    public static final String OVERALL_ZSET_KEY = "leaderboard:overall";
    /** 入榜/回滚互斥锁前缀（同一记录串行化，审批版 §7.5） */
    public static final String ROLLBACK_LOCK_PREFIX = "lock:rollback:";
    /** 定时结算防重锁（多实例仅一个执行，审批版 §7.5） */
    public static final String SCHEDULER_LOCK_KEY = "lock:scheduler:leaderboard";

    /** 榜单默认取前 N 名 */
    private static final int DEFAULT_TOP_N = 50;
    /** 好友列表一次 Feign 拉取上限（演示规模 1000 用户：一次拉齐后内存过滤，不逐条远程调用） */
    private static final long FRIEND_FETCH_SIZE = 1000;
    /** 锁等待上限（秒）：拿不到锁抛出让 MQ 重投（不跳过，入榜/回滚不可丢） */
    private static final long LOCK_WAIT_SECONDS = 3;
    /** 结算周期：10min（对账纠偏为低频兜底，热读层实时由事件维护） */
    private static final long SETTLE_FIXED_DELAY_MS = 10 * 60_000;
    /** 结算首跑延迟：1min（启动后先给事件消费留写入窗口） */
    private static final long SETTLE_INITIAL_DELAY_MS = 60_000;

    private final SportRecordMapper sportRecordMapper;
    private final LeaderboardContributionMapper contributionMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final UserApi userApi;

    // ==================== 事件驱动入榜（VERIFIED） ====================

    /**
     * VERIFIED 事件入榜（规范差异「仅通过记录入榜」「事件驱动入榜」）：
     * <ol>
     *   <li>以库内 sport_record 当前状态做「仅 pass」闸门：仅 PASSED/RE_PASSED 入榜；
     *       事件体不携带里程，以库内记录为权威（防伪造/重放旧事件）；</li>
     *   <li>拿 {@code lock:rollback:{recordId}} 与回滚互斥；拿不到锁抛出让 MQ 重投
     *       （入榜不可丢，也不能与回滚交错）；</li>
     *   <li>锁内先写锚点：INSERT IGNORE（record_id 主键）——
     *       命中 1 行 = 新贡献 → ZINCRBY +distance；
     *       命中 0 行 = 已有锚点，看其状态：ROLLED_BACK（驳回后再改判通过）→ 重激活并补加分，
     *       ACTIVE（重复事件/重放）→ 幂等跳过。</li>
     * </ol>
     */
    public void applyVerified(Long recordId) {
        SportRecordSnapshot record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            // 记录不存在（脏事件）：ack 丢弃，不重投
            log.warn("VERIFIED 事件对应记录不存在，跳过：recordId={}", recordId);
            return;
        }
        if (!isPassed(record.getStatus())) {
            // 规范差异「未通过不入榜」：SUBMITTED/VERIFYING/REJECTED/RE_CONFIRMED 一律不计
            log.info("非 pass 记录不入榜：recordId={}, status={}", recordId, record.getStatus());
            return;
        }
        BigDecimal distance = record.getDistance();
        if (distance == null || distance.signum() <= 0) {
            log.warn("记录里程缺失或非正，跳过入榜：recordId={}, distance={}", recordId, distance);
            return;
        }

        RLock lock = rollbackLock(recordId);
        boolean locked = tryLock(lock);
        if (!locked) {
            throw new IllegalStateException("获取回滚互斥锁超时，等待 MQ 重投：recordId=" + recordId);
        }
        try {
            boolean credit;
            int inserted = contributionMapper.insertIgnore(recordId, record.getUserId(),
                    distance, ContributionStatus.ACTIVE.getCode());
            if (inserted == 1) {
                credit = true; // 新锚点：首次入榜
            } else {
                // 已有锚点：驳回（ROLLED_BACK）后再改判通过 → 重激活补加分；仍 ACTIVE → 重复事件幂等跳过
                credit = contributionMapper.updateStatus(recordId,
                        ContributionStatus.ROLLED_BACK.getCode(),
                        ContributionStatus.ACTIVE.getCode()) == 1;
            }
            if (credit) {
                zincrby(record.getUserId(), distance.doubleValue());
                log.info("入榜成功：recordId={}, userId={}, +{}km", recordId, record.getUserId(), distance);
            } else {
                log.info("记录已入榜（重复事件），幂等跳过：recordId={}", recordId);
            }
        } finally {
            unlock(lock, locked);
        }
    }

    // ==================== 改判回滚（REJECTED/REVERSED） ====================

    /**
     * REJECTED/REVERSED 事件回滚（规范差异「改判回滚」）：
     * <ol>
     *   <li>锚点行乐观迁移 ACTIVE → ROLLED_BACK：影响 0 行 = 无贡献或已回滚 →
     *       幂等跳过（「无贡献不回滚」「回滚幂等」，不产生负里程）；</li>
     *   <li>影响 1 行 → 按锚点行的 distance/user_id 精确扣回 ZSet
     *       （以锚点为权威值，不信任事件体重放值；同一把 {@code lock:rollback:{recordId}}
     *       与入榜串行化）。</li>
     * </ol>
     */
    public void rollbackOnRejected(Long recordId) {
        RLock lock = rollbackLock(recordId);
        boolean locked = tryLock(lock);
        if (!locked) {
            throw new IllegalStateException("获取回滚互斥锁超时，等待 MQ 重投：recordId=" + recordId);
        }
        try {
            LeaderboardContribution anchor = contributionMapper.selectById(recordId);
            if (anchor == null) {
                // 规范差异「无贡献不回滚」：记录从未入榜（如首次判定即 REJECTED），不产生负里程
                log.info("无贡献行，回滚跳过：recordId={}", recordId);
                return;
            }
            // 乐观迁移：只有 ACTIVE → ROLLED_BACK 才真正扣分；已 ROLLED_BACK 影响行数 0 = 幂等
            int rows = contributionMapper.updateStatus(recordId,
                    ContributionStatus.ACTIVE.getCode(),
                    ContributionStatus.ROLLED_BACK.getCode());
            if (rows == 0) {
                log.info("贡献已回滚（重复事件），幂等跳过：recordId={}", recordId);
                return;
            }
            zincrby(anchor.getUserId(), -anchor.getDistance().doubleValue());
            log.info("回滚成功：recordId={}, userId={}, -{}km",
                    recordId, anchor.getUserId(), anchor.getDistance());
        } finally {
            unlock(lock, locked);
        }
    }

    // ==================== 榜单查询（读多写少，ZSet 秒级） ====================

    /**
     * 榜单查询入口（审批版 §4.5）：overall 总榜 / friend 好友榜。
     *
     * @param type   榜单类型（overall / friend，其余 400）
     * @param userId 查询人（friend 榜必填：按其好友列表过滤；overall 忽略）
     * @param size   取前 N 名
     */
    public List<LeaderboardDTO> top(String type, Long userId, Integer size) {
        int topN = (size == null || size <= 0) ? DEFAULT_TOP_N : Math.min(size, 1000);
        if ("overall".equalsIgnoreCase(type)) {
            return topOverall(topN);
        }
        if ("friend".equalsIgnoreCase(type)) {
            if (userId == null) {
                throw new IllegalArgumentException("好友榜查询必须携带 userId");
            }
            return topFriends(userId, topN);
        }
        throw new IllegalArgumentException("不支持的榜单类型：" + type);
    }

    /**
     * 总榜（规范差异「总榜查询」）：ZREVRANGE 取前 N（按里程降序），批量补昵称。
     */
    public List<LeaderboardDTO> topOverall(int size) {
        Set<TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(OVERALL_ZSET_KEY, 0, size - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return assemble(new ArrayList<>(tuples));
    }

    /**
     * 好友榜（规范差异「好友榜查询」）：Feign 一次拉齐好友列表 → ZSet 按好友过滤
     * （内存过滤，不逐条远程调用；非好友与本人均被排除，只显示好友）。
     *
     * <p>降级口径（产品决策，add-resilience-hardening）：user-service 不可用 → <b>返回空榜</b>，
     * 不选「不过滤」。不过滤会把总榜非好友泄露进好友榜（隐私/语义错误）；空榜仅短暂降级。
     * UserApiFallback 对 listFriends 返回空页，或异常被本方法 catch 后同样空榜。</p>
     */
    public List<LeaderboardDTO> topFriends(Long userId, int size) {
        List<Long> friendIds;
        try {
            friendIds = userApi.listFriends(userId, 1, FRIEND_FETCH_SIZE).getData().getRecords()
                    .stream().map(f -> f.getUserId()).toList();
        } catch (Exception e) {
            log.warn("好友列表拉取失败，好友榜降级为空榜：userId={}", userId, e);
            return List.of();
        }
        if (friendIds.isEmpty()) {
            log.info("用户无好友，好友榜为空：userId={}", userId);
            return List.of();
        }
        Set<TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(OVERALL_ZSET_KEY, 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        Set<Long> friendSet = new LinkedHashSet<>(friendIds);
        // 按好友过滤（保持 ZSet 分数降序）后截取前 N；本人与非好友均被排除
        List<TypedTuple<String>> filtered = tuples.stream()
                .filter(t -> friendSet.contains(Long.valueOf(t.getValue())))
                .limit(size)
                .toList();
        return assemble(filtered);
    }

    /**
     * ZSet 元组 → DTO 组装：rank（入参序即分数降序，从 1 起）+ 批量昵称补齐。
     */
    private List<LeaderboardDTO> assemble(List<TypedTuple<String>> ordered) {
        List<Long> userIds = ordered.stream().map(t -> Long.valueOf(t.getValue())).toList();
        Map<Long, String> nicknames = resolveNicknames(userIds);

        List<LeaderboardDTO> result = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            TypedTuple<String> t = ordered.get(i);
            Long uid = Long.valueOf(t.getValue());
            BigDecimal distance = t.getScore() == null ? BigDecimal.ZERO : BigDecimal.valueOf(t.getScore());
            result.add(LeaderboardDTO.of(i + 1, uid,
                    nicknames.getOrDefault(uid, "用户" + uid), distance));
        }
        return result;
    }

    /** 批量拉昵称（一次 Feign 调用；user-service 不可用时降级为「用户{id}」占位，榜单仍可用） */
    private Map<Long, String> resolveNicknames(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nicknames = new java.util.HashMap<>();
        try {
            List<UserDTO> users = userApi.listUsersByIds(userIds).getData();
            if (users != null) {
                for (UserDTO u : users) {
                    // 昵称可能为 null（未设置），HashMap 手工填充以容忍空值
                    nicknames.put(u.getId(), u.getNickname());
                }
            }
        } catch (Exception e) {
            log.warn("批量昵称查询失败，以占位符降级：userIds={}", userIds, e);
        }
        return nicknames;
    }

    // ==================== 快照结算（定时对账，防重） ====================

    /**
     * 快照结算（规范差异「快照结算防重」，审批版 §7.5）：
     * Redisson 锁 {@code lock:scheduler:leaderboard}（lease=-1 → 默认 30s 看门狗）
     * 保证多实例仅一个执行；以 contribution ACTIVE 汇总为<b>权威源</b>纠偏 ZSet：
     * <ol>
     *   <li>ZADD 覆盖各用户总分（纠正漂移/负分/Redis 重启丢数据）；</li>
     *   <li>ZREM 清除已无 ACTIVE 贡献的残留成员（全量回滚后 ZSet 不留幽灵分数）；</li>
     *   <li>批量为 ACTIVE 行盖 settled_at（快照口径）。</li>
     * </ol>
     *
     * <p>已知权衡：结算期间并发的事件消费增量会被下一轮结算覆盖收敛
     * （汇总 SELECT 与 ZADD 之间无全局锁）——这正是「最终一致」的定义；
     * Redis 键整体丢失也由本任务从 contribution 重建（自愈）。</p>
     */
    @Scheduled(fixedDelay = SETTLE_FIXED_DELAY_MS, initialDelay = SETTLE_INITIAL_DELAY_MS)
    void settleAndReconcile() {
        RLock lock = redissonClient.getLock(SCHEDULER_LOCK_KEY);
        boolean locked = tryLock(lock);
        if (!locked) {
            log.debug("结算锁被其他实例持有，本轮跳过");
            return;
        }
        try {
            List<UserMileage> summaries =
                    contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode());
            Map<Long, Double> target = summaries.stream().collect(Collectors.toMap(
                    UserMileage::getUserId,
                    m -> m.getTotalDistance() == null ? 0d : m.getTotalDistance().doubleValue()));

            // 1) 以汇总覆盖 ZSet（批量 ZADD 幂等纠偏）
            if (!target.isEmpty()) {
                Set<TypedTuple<String>> tuples = target.entrySet().stream()
                        .map(e -> TypedTuple.of(String.valueOf(e.getKey()), e.getValue()))
                        .collect(Collectors.toSet());
                stringRedisTemplate.opsForZSet().add(OVERALL_ZSET_KEY, tuples);
            }
            // 2) 清除无 ACTIVE 贡献的残留成员
            Set<String> members = stringRedisTemplate.opsForZSet().range(OVERALL_ZSET_KEY, 0, -1);
            List<String> stale = members == null ? List.of() : members.stream()
                    .filter(m -> !target.containsKey(Long.valueOf(m)))
                    .toList();
            if (!stale.isEmpty()) {
                stringRedisTemplate.opsForZSet().remove(OVERALL_ZSET_KEY, stale.toArray(new String[0]));
            }
            // 3) 标记 settled_at（快照口径：本行已随最近一次结算对账）
            if (!summaries.isEmpty()) {
                contributionMapper.markSettled(ContributionStatus.ACTIVE.getCode(), LocalDateTime.now());
            }
            log.info("榜单结算完成：ACTIVE 汇总 {} 名，纠偏 {} 名，清理残留 {} 名",
                    target.size(), target.size(), stale.size());
        } finally {
            unlock(lock, locked);
        }
    }

    // ==================== 内部工具 ====================

    /** 「通过校验」终态：PASSED 与 RE_PASSED（申诉终判改判通过），与点赞域口径一致 */
    private static boolean isPassed(Integer status) {
        return status != null
                && (status == RecordStatus.PASSED.getCode()
                || status == RecordStatus.RE_PASSED.getCode());
    }

    /** ZINCRBY：正数入榜 / 负数回滚（原子操作，并发消费下不丢增量） */
    private void zincrby(Long userId, double delta) {
        stringRedisTemplate.opsForZSet().incrementScore(
                OVERALL_ZSET_KEY, String.valueOf(userId), delta);
    }

    private RLock rollbackLock(Long recordId) {
        return redissonClient.getLock(ROLLBACK_LOCK_PREFIX + recordId);
    }

    /** 加锁（lease=-1 → Redisson 默认 30s 看门狗自动续期，审批版 §7.5 同款） */
    private boolean tryLock(RLock lock) {
        try {
            return lock.tryLock(LOCK_WAIT_SECONDS, -1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 仅当本次调用确实持有锁时才释放（防误释放他人重入层） */
    private void unlock(RLock lock, boolean locked) {
        if (locked) {
            lock.unlock();
        }
    }
}
