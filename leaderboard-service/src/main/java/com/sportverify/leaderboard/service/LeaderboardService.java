package com.sportverify.leaderboard.service;

import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.api.mapmatch.dto.SegmentEffortDTO;
import com.sportverify.api.mapmatch.dto.SegmentLeaderboardDTO;
import com.sportverify.api.user.UserApi;
import com.sportverify.api.user.dto.ClubLeaderboardDTO;
import com.sportverify.api.user.dto.ClubMemberContributionDTO;
import com.sportverify.api.user.dto.UserDTO;
import com.sportverify.common.result.Result;
import com.sportverify.common.trace.TraceIds;
import com.sportverify.leaderboard.entity.ClubDistanceContribution;
import com.sportverify.leaderboard.entity.LeaderboardContribution;
import com.sportverify.leaderboard.entity.LeaderboardDailySummary;
import com.sportverify.leaderboard.entity.SportRecordSnapshot;
import com.sportverify.leaderboard.enums.ContributionStatus;
import com.sportverify.leaderboard.mapper.ClubDistanceContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper;
import com.sportverify.leaderboard.mapper.LeaderboardDailySummaryMapper;
import com.sportverify.leaderboard.mapper.LeaderboardContributionMapper.UserMileage;
import com.sportverify.leaderboard.mapper.SportRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p><b>双层数据结构</b>（读写分层：Redis ZSet 热读 + 快照表权威源）：</p>
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
    /** 赛段耗时榜 ZSet 键前缀（TASK-272，member=userId，score=个人最好 elapsed_seconds，升序 KOM） */
    public static final String SEGMENT_ZSET_KEY_PREFIX = "leaderboard:segment:";
    /** 赛段成绩附属 Hash 键后缀（TASK-272，field=userId，value=recordId，榜单条目回指完赛记录） */
    public static final String SEGMENT_RECORD_HASH_SUFFIX = ":record";
    /** 跑团总榜 ZSet 键（TASK-275，member=clubId，score=累计里程米，降序取前 N） */
    public static final String CLUB_OVERALL_ZSET_KEY = "leaderboard:club:overall";
    /** 跑团周榜 ZSet 键前缀（TASK-275，{weekStart}=周一 ISO 日期，score=当周累计里程米） */
    public static final String CLUB_WEEKLY_ZSET_KEY_PREFIX = "leaderboard:club:weekly:";
    /** 跑团名称缓存 Hash 键（TASK-275，field=clubId，value=clubName，榜单条目补名称） */
    public static final String CLUB_NAMES_HASH_KEY = "leaderboard:club:names";
    /** 跑团成员贡献 ZSet 键前缀（TASK-276，{clubId}=跑团，member=userId，score=成员累计里程米） */
    public static final String CLUB_MEMBER_ZSET_KEY_PREFIX = "leaderboard:club:member:";
    /** 入榜/回滚互斥锁前缀（同一记录串行化，审批版 §7.5） */
    public static final String ROLLBACK_LOCK_PREFIX = "lock:rollback:";
    /** 定时结算防重锁（多实例仅一个执行，审批版 §7.5） */
    public static final String SCHEDULER_LOCK_KEY = "lock:scheduler:leaderboard";
    /** 总榜二级缓存名（与 {@code @Cacheable(value=...)} 一致；D1 写路径显式失效用） */
    public static final String OVERALL_CACHE_NAME = "leaderboard:overall";

    /** 榜单默认取前 N 名 */
    private static final int DEFAULT_TOP_N = 50;
    /** 跑团榜默认取前 N 名（TASK-275） */
    private static final int CLUB_DEFAULT_TOP_N = 20;
    /** 跑团榜单次查询上限（防 {@code size} 传成超大值把整榜拉进内存，TASK-275） */
    private static final int CLUB_MAX_TOP_N = 100;
    /** 跑团成员贡献榜默认取前 N 名（TASK-276 R3，与团队榜默认同口径 20） */
    private static final int CLUB_CONTRIBUTION_DEFAULT_TOP_N = 20;
    /** 报表单次查询上限（防 {@code size} 传成 10 万把整表拉进内存） */
    private static final int DAILY_REPORT_MAX_SIZE = 500;
    /** 好友榜整榜扫描批大小（TASK-239 前实现：分批 reverseRangeWithScores 窗口；已由 Redis
     *  Pipeline 批量点查取代，保留字段仅作历史注释，不再被引用）。 */
    private static final long FRIEND_SCAN_BATCH = 500;
    /** 锁等待上限（秒）：拿不到锁抛出让 MQ 重投（不跳过，入榜/回滚不可丢） */
    private static final long LOCK_WAIT_SECONDS = 3;
    /** 结算周期：10min（对账纠偏为低频兜底，热读层实时由事件维护） */
    private static final long SETTLE_FIXED_DELAY_MS = 10 * 60_000;
    /** 结算首跑延迟：1min（启动后先给事件消费留写入窗口） */
    private static final long SETTLE_INITIAL_DELAY_MS = 60_000;
    /** B18：总榜 ZSet 最大保留成员数（结算末尾容量截断保护，保留前 10,000 名） */
    private static final long OVERALL_ZSET_MAX = 10_000;
    /** B18：scan 游标分批遍历批次大小（避免整榜 range(0,-1) 倾倒进 JVM 堆） */
    private static final int OVERALL_ZSET_SCAN_BATCH = 500;
    /** 好友榜本地缓存 TTL：10~15s 轻量窗口阻断高频刷新穿透（见类 Javadoc /topFriends） */
    private static final java.time.Duration FRIEND_CACHE_TTL = java.time.Duration.ofSeconds(15);
    /** 好友榜本地缓存最大条目数（防无界累积；按 userId:size 维度计） */
    private static final int FRIEND_CACHE_MAX_SIZE = 2000;

    /**
     * 好友榜本地多级缓存（TASK-246 B23 防穿透）：Key 为 {@code userId + ":" + size}，
     * 15s TTL + 上限 2000。好友榜当前每次请求均穿透两次 Feign（好友列表 + 用户昵称）
     * 与 Redis Pipeline，高并发下极易打爆 user-service；本缓存命中即微秒级返回，
     * 未命中才执行既有拉取并在返回前回填。属单实例本地缓存，多实例各持一份（声明接受）。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, List<LeaderboardDTO>> friendLeaderboardCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(FRIEND_CACHE_MAX_SIZE)
                    .expireAfterWrite(FRIEND_CACHE_TTL)
                    .build();

    private final SportRecordMapper sportRecordMapper;
    private final LeaderboardContributionMapper contributionMapper;
    private final LeaderboardDailySummaryMapper dailySummaryMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final UserApi userApi;
    /**
     * D1 总榜缓存失效用（类型注入解析到 {@code @Primary} {@code hierarchicalCacheManager}）。
     * 刻意不引入 {@code @CacheEvict}/{@code @Cacheable} 代理注解——写路径 bean 必须零代理
     * （ADR-0009 哨兵 {@code LeaderboardWritePathStaysUnproxiedTest}），故此处显式调用管理器。
     */
    private final CacheManager cacheManager;
    /**
     * 跑团里程锚点只读 Mapper（TASK-278 R3）：以锚点 {@code CREDITED} 汇总为跑团三组 ZSet 的
     * 权威源。只读边界（不继承 {@code BaseMapper}，无写方法）——本服务对锚点表零写入，
     * 「曾入账」是历史事实，孤儿记录仅经汇总 JOIN 存活过滤剔出榜单。
     */
    private final ClubDistanceContributionMapper clubDistanceContributionMapper;

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
     *
     * <p>无本地事务（ADR-0009）：贡献表一行 + Redis ZSet 是跨存储写，Spring 事务管不了 ZSet；
     * 禁止把排行集合纳入本地事务。短暂不一致由结算任务以贡献表为准纠偏。</p>
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
                // E24 R6：用 reactivate（SET status + settled_at = NULL）而非 updateStatus——重激活行清旧结算戳，
                // 下轮 markSettled 重盖当期，改判里程归位当周/当月窗口（updateStatus 保留戳只用于回滚方向）
                credit = contributionMapper.reactivate(recordId,
                        ContributionStatus.ROLLED_BACK.getCode(),
                        ContributionStatus.ACTIVE.getCode()) == 1;
            }
            if (credit) {
                zincrby(record.getUserId(), distance.doubleValue());
                evictOverallCache();
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
     *
     * <p>无本地事务（ADR-0009）：同 {@link #applyVerified}，DB 锚点迁移与 ZSet 扣分刻意最终一致，
     * 禁止用本地事务假装 Redis 与 DB 一起原子提交。</p>
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
            evictOverallCache();
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
    @Cacheable(value = "leaderboard:overall", cacheManager = "hierarchicalCacheManager",
            key = "#size == null || #size <= 0 ? 50 : (#size > 1000 ? 1000 : #size)",
            condition = "#type != null && 'overall'.equalsIgnoreCase(#type)",
            unless = "#result == null || #result.isEmpty()")
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
     * <p>二级缓存挂在 {@link #top} 的 overall 正常入口；此方法由 {@code top} 内部调用，
     * 避免 self-invocation 绕过 Spring 缓存代理。</p>
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
     * 好友榜（规范差异「好友榜查询」）：调用 user-service listFriendIds 单次拉齐全部好友 ID → Redis Pipeline
     * 单次往返批量点查各好友在总榜的积分（ZSCORE），内存过滤/排序/截断，不再整榜分批扫描。
     *
     * <p>降级口径（产品决策，add-resilience-hardening）：user-service 不可用 → <b>返回空榜</b>，
     * 不选「不过滤」。不过滤会把总榜非好友泄露进好友榜（隐私/语义错误）；空榜仅短暂降级。
     * UserApiFallback 对 listFriendIds 返回空列表，或异常被本方法 catch 后同样空榜。</p>
     */
    public List<LeaderboardDTO> topFriends(Long userId, int size) {
        // B23 防穿透：入口优先命中本地缓存（userId:size 维度），未命中才穿透拉取并回填
        String cacheKey = userId + ":" + size;
        List<LeaderboardDTO> cached = friendLeaderboardCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<LeaderboardDTO> result = loadTopFriendsWithoutCache(userId, size);
        if (!result.isEmpty()) {
            // 空榜不缓存：降级空榜（user-service 不可用等）短暂恢复后应立刻能拉出数据，不背着旧空榜
            friendLeaderboardCache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * 好友榜原始拉取（B23 缓存未命中才执行）：调用 user-service listFriendIds 拉齐好友 ID →
     * Redis Pipeline 单次往返批量点查各好友在总榜的积分（ZSCORE），内存过滤/排序/截断。
     *
     * <p>降级口径（产品决策，add-resilience-hardening）：user-service 不可用 → <b>返回空榜</b>，
     * 不选「不过滤」。不过滤会把总榜非好友泄露进好友榜（隐私/语义错误）；空榜仅短暂降级。
     * UserApiFallback 对 listFriendIds 返回空列表，或异常被本方法 catch 后同样空榜。</p>
     */
    private List<LeaderboardDTO> loadTopFriendsWithoutCache(Long userId, int size) {
        List<Long> friendIds;
        try {
            friendIds = fetchAllFriendIds(userId);
        } catch (Exception e) {
            log.warn("好友列表拉取失败，好友榜降级为空榜：userId={}", userId, e);
            return List.of();
        }
        if (friendIds.isEmpty()) {
            log.info("用户无好友，好友榜为空：userId={}", userId);
            return List.of();
        }
        if (size <= 0) {
            return List.of();
        }

        // 去重好友 ID（每个好友只点查一次积分）
        List<Long> distinctFriendIds = new ArrayList<>(new LinkedHashSet<>(friendIds));
        // 单次往返批量 ZSCORE：Pipeline 内按 distinctFriendIds 顺序逐条点查，返回序与入参一一对应
        List<Object> rawScores = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] keyBytes = OVERALL_ZSET_KEY.getBytes(StandardCharsets.UTF_8);
            for (Long fid : distinctFriendIds) {
                connection.zSetCommands().zScore(keyBytes, String.valueOf(fid).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        if (rawScores == null) {
            return List.of();
        }
        // 内存过滤：未上榜好友（null）与零分好友（score <= 0）不入榜
        List<TypedTuple<String>> candidates = new ArrayList<>(distinctFriendIds.size());
        int n = Math.min(rawScores.size(), distinctFriendIds.size());
        for (int i = 0; i < n; i++) {
            Object raw = rawScores.get(i);
            if (raw instanceof Number number && number.doubleValue() > 0) {
                candidates.add(TypedTuple.of(String.valueOf(distinctFriendIds.get(i)), number.doubleValue()));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 分数降序；同分按 member 字符串降序（对齐 Redis ZREVRANGE 的词典序回排）
        candidates.sort((a, b) -> {
            int byScore = Double.compare(b.getScore(), a.getScore());
            return byScore != 0 ? byScore : b.getValue().compareTo(a.getValue());
        });
        // 截取前 size 条后组装
        List<TypedTuple<String>> top = candidates.size() > size
                ? new ArrayList<>(candidates.subList(0, size)) : candidates;
        return assemble(top);
    }

    /**
     * 读取全量好友 ID（TASK-271 优化）：
     * 直接调用 userApi.listFriendIds 单次拉齐全部好友 ID，消除原 while 分页循环的
     * 多轮 Feign 网络往返以及 user-service 侧无意义的昵称批量回表负载。
     */
    private List<Long> fetchAllFriendIds(Long userId) {
        Result<List<Long>> res = userApi.listFriendIds(userId);
        if (res == null || res.getData() == null) {
            return List.of();
        }
        return res.getData();
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
     *   <li>（B16）孤儿对账补偿：识别 ACTIVE 但 record 已不存在的孤儿行，流转 ROLLED_BACK
     *       终态——自动兜底 record-service Feign 回滚失败遗留的脏里程，随本轮汇总从 ZSet 剔除；</li>
     *   <li>ZADD 覆盖各用户总分（纠正漂移/负分/Redis 重启丢数据）；</li>
     *   <li>（B18）ZREM 扫掠清除已无 ACTIVE 贡献的残留成员：以 {@code opsForZSet().scan(...)}
     *       游标分批遍历比对（批次 500），不再整榜 range(0,-1) 倾倒进 JVM 堆；</li>
     *   <li>（B18）容量截断保护：结算末尾对 {@code OVERALL_ZSET_KEY} 截断，仅保留前
     *       {@value #OVERALL_ZSET_MAX} 名，消除历史未活跃用户无限累积撑爆 Redis 内存；</li>
     *   <li>（TASK-278 R3）跑团三组 ZSet 对账：以锚点 {@code club_distance_contribution}
     *       的 CREDITED 汇总为权威源，覆盖纠偏 {@code club:overall} / {@code club:weekly:*} /
     *       {@code club:member:{clubId}} 并清理残留（见 {@link #reconcileClubDistance}）；</li>
     *   <li>批量为 ACTIVE 行盖 settled_at（快照口径）。</li>
     * </ol>
     *
     * <p>已知权衡（TASK-253 R3/E7 收窄）：结算期间并发的事件消费增量经 ZREM 二次复核保住——
     * scan 候选在出榜前以贡献表复核，仍缺 ACTIVE 贡献才 ZREM，竞态窗口由结算期（秒级）
     * 收窄为复核 SELECT 与 ZREM 之间（毫秒级）；窗口内残余仍由下一轮结算覆盖收敛
     * （汇总 SELECT 与 ZADD 之间无全局锁）——这正是「最终一致」的定义；
     * Redis 键整体丢失也由本任务从 contribution 重建（自愈）。</p>
     */
    @Scheduled(fixedDelay = SETTLE_FIXED_DELAY_MS, initialDelay = SETTLE_INITIAL_DELAY_MS)
    void settleAndReconcile() {
        TraceIds.put(TraceIds.newId());
        try {
            RLock lock = redissonClient.getLock(SCHEDULER_LOCK_KEY);
            boolean locked = tryLock(lock);
            if (!locked) {
                log.debug("结算锁被其他实例持有，本轮跳过");
                return;
            }
            try {
                // B16 孤儿对账补偿：识别并回滚已删除记录的 ACTIVE 孤儿贡献（自愈 Feign 抖动遗留脏里程）
                int orphanRolledBack = 0;
                List<UserMileage> orphans =
                        contributionMapper.selectOrphanContributions(ContributionStatus.ACTIVE.getCode());
                for (UserMileage orphan : orphans) {
                    int rows = contributionMapper.updateStatus(orphan.getRecordId(),
                            ContributionStatus.ACTIVE.getCode(),
                            ContributionStatus.ROLLED_BACK.getCode());
                    if (rows > 0) {
                        orphanRolledBack++;
                        log.info("定时结算发现已删除记录的孤儿贡献，自动对账回滚：recordId={}, userId={}",
                                orphan.getRecordId(), orphan.getUserId());
                    }
                }

                List<UserMileage> summaries =
                        contributionMapper.selectActiveSummaries(ContributionStatus.ACTIVE.getCode());
                Map<Long, Double> target = summaries.stream().collect(Collectors.toMap(
                        UserMileage::getUserId,
                        m -> m.getTotalDistance() == null ? 0d : m.getTotalDistance().doubleValue()));

                // 1) 以汇总覆盖 ZSet（批量 ZADD 幂等纠偏；孤儿已回滚，不进入本 target）
                if (!target.isEmpty()) {
                    Set<TypedTuple<String>> tuples = target.entrySet().stream()
                            .map(e -> TypedTuple.of(String.valueOf(e.getKey()), e.getValue()))
                            .collect(Collectors.toSet());
                    stringRedisTemplate.opsForZSet().add(OVERALL_ZSET_KEY, tuples);
                }
                // 2) B18/D3 清除无 ACTIVE 贡献的残留成员：单次 scan 游标一遍遍历比对 + 批式 ZREM。
                //    旧实现每轮循环重开 scan 并以「上一批末尾 member 是否在下一批」判终止，当成员数
                //    ≥ 批次(500) 时该判据恒真（全量采集的批次恒含上一批末尾）→ 结算线程不可终止
                //    （持调度锁 + watchdog 续期）；单遍迭代天然收敛，不再有终止判据。
                int pruned = 0;
                List<String> staleBatch = new ArrayList<>(OVERALL_ZSET_SCAN_BATCH);
                try (org.springframework.data.redis.core.Cursor<TypedTuple<String>> zc =
                             stringRedisTemplate.opsForZSet().scan(OVERALL_ZSET_KEY,
                                     org.springframework.data.redis.core.ScanOptions
                                             .scanOptions().count(OVERALL_ZSET_SCAN_BATCH).build())) {
                    // scan 为尽力而为采集：null 视为无成员可清理，避免 NPE 阻断其余结算步骤
                    if (zc != null) {
                        while (zc.hasNext()) {
                            TypedTuple<String> t = zc.next();
                            if (t == null || t.getValue() == null) {
                                continue; // null 安全跳过（脏元素不参与 stale 判定）
                            }
                            if (!target.containsKey(Long.valueOf(t.getValue()))) {
                                staleBatch.add(t.getValue());
                                if (staleBatch.size() >= OVERALL_ZSET_SCAN_BATCH) {
                                    pruned += flushStale(staleBatch);
                                }
                            }
                        }
                    }
                }
                pruned += flushStale(staleBatch);
                // 3) B18 容量截断保护：仅保留前 OVERALL_ZSET_MAX 名，防止历史未活跃用户无限累积撑爆 Redis 内存
                long trimmed = stringRedisTemplate.opsForZSet()
                        .removeRange(OVERALL_ZSET_KEY, 0, -(OVERALL_ZSET_MAX + 1));
                // 3.1) D1：ZSet 纠偏完成，恰一次失效总榜缓存，避免结算后总榜最长 5min 仍读旧值
                evictOverallCache();
                // 3.2) TASK-278 R3：跑团三组 ZSet 以锚点 CREDITED 汇总为准纠偏（同锁同周期，best-effort）
                int clubPruned = reconcileClubDistance();
                // 4) 标记 settled_at（快照口径：本行已随最近一次结算对账）
                if (!summaries.isEmpty()) {
                    contributionMapper.markSettled(ContributionStatus.ACTIVE.getCode(), LocalDateTime.now());
                }
                // 5) 写当日报表快照（TASK-108）：单条 INSERT...SELECT...ON DUPLICATE KEY UPDATE 自证原子，
                //    不引本地事务（ADR-0009）；再清掉"今日已无 ACTIVE 贡献"的残留行，
                //    否则全量回滚过的用户会带着旧里程留在报表里。
                int upserted = dailySummaryMapper.upsertFromActiveContributions(
                        ContributionStatus.ACTIVE.getCode());
                int purged = dailySummaryMapper.deleteStaleToday(ContributionStatus.ACTIVE.getCode());
                log.info("榜单结算完成：孤儿回滚 {} 条，ACTIVE 汇总 {} 名，清理残留 {} 名，容量截断 {} 条，"
                                + "跑团对账清理 {} 条，报表写入 {} / 清理 {} 行",
                        orphanRolledBack, target.size(), pruned, trimmed, clubPruned, upserted, purged);
            } finally {
                unlock(lock, locked);
            }
        } finally {
            TraceIds.clear();
        }
    }

    /**
     * D1：总榜二级缓存失效（best-effort，写路径专用）。
     *
     * <p>入榜/回滚/结算纠偏改完 ZSet 后清空 {@code leaderboard:overall} 缓存，否则总榜最长
     * 5min（L1/L2 同 TTL）仍显示已撤销旧里程、或新入榜不可见——这是记录删除/回撤主链路的盲区。</p>
     *
     * <p><b>无代理注解</b>（ADR-0009 哨兵）：禁止 {@code @CacheEvict}——写路径 bean 必须零代理
     * （{@code LeaderboardWritePathStaysUnproxiedTest}），故此处显式经 {@link CacheManager} 清理。
     * 异常仅告警不抛出：失效是一致性的 best-effort 补偿，不得因 Redis 抖动阻断入榜/回滚主链路；
     * 偶发失效失败由下一轮失效或 TTL 到期收敛。</p>
     */
    private void evictOverallCache() {
        try {
            org.springframework.cache.Cache cache = cacheManager.getCache(OVERALL_CACHE_NAME);
            if (cache != null) {
                cache.clear();
            }
        } catch (Exception e) {
            log.warn("总榜缓存失效失败（best-effort，不阻断写链路）", e);
        }
    }

    /**
     * B18/D3 + TASK-253 R3/E7：残留成员分批出榜前先经贡献表二次复核——
     * 仍缺 ACTIVE 贡献者才 ZREM，复核命中者保留（结算 SELECT 后、scan 期间新入榜的成员
     * DB 必已落行——{@code applyVerified} 先写 DB 后 ZINCRBY——复核可保住主竞态窗口，
     * 避免成员掉榜至下一轮才收敛）；复核查询异常 → 保守跳过本批 ZREM
     * （宁漏删不误删，漏删者下轮收敛）。返回已移除条数。
     */
    private int flushStale(List<String> staleBatch) {
        if (staleBatch.isEmpty()) {
            return 0;
        }
        try {
            List<Long> candidateIds = staleBatch.stream().map(Long::valueOf).toList();
            List<Long> activeIds = contributionMapper.selectActiveUserIds(
                    ContributionStatus.ACTIVE.getCode(), candidateIds);
            Set<Long> active = new HashSet<>(activeIds == null ? List.of() : activeIds);
            List<String> stillStale = staleBatch.stream()
                    .filter(id -> !active.contains(Long.valueOf(id)))
                    .toList();
            if (stillStale.isEmpty()) {
                staleBatch.clear();
                return 0;
            }
            Object[] batch = stillStale.toArray(new String[0]);
            stringRedisTemplate.opsForZSet().remove(OVERALL_ZSET_KEY, batch);
            int n = stillStale.size();
            staleBatch.clear();
            return n;
        } catch (Exception e) {
            // 复核查询异常（DB 抖动等）：保守跳过本批 ZREM，宁漏删不误删，漏删者下轮收敛
            log.warn("结算 ZREM 二次复核查询异常，保守跳过本批清理：batchSize={}", staleBatch.size(), e);
            staleBatch.clear();
            return 0;
        }
    }

    // ==================== 跑团三组 ZSet 对账（TASK-278 R3） ====================

    /**
     * 跑团三组 ZSet 对账纠偏（TASK-278 R3，在 {@link #settleAndReconcile} 同锁同周期内执行）。
     *
     * <p><b>权威源</b>：record-service 写入的 {@code club_distance_contribution} 锚点表，
     * 以 {@code status = CREDITED} 且 {@code sport_record} 行仍存活（汇总 SQL 内
     * {@code INNER JOIN}）的行为唯一计入口径——孤儿锚点（记录已物理删除）自然落出汇总、
     * 榜上随之剔除，<b>锚点行不翻转状态</b>（「曾入账」是历史事实，本服务对锚点表零写入，裁决①）。</p>
     *
     * <p><b>纠偏口径</b>（覆盖式 ZADD = 幂等纠偏，Redis 丢键/漂移/重启均可自愈）：</p>
     * <ul>
     *   <li>{@code club:overall}：按 {@code (club_id) → SUM} 覆盖；无 CREDITED 汇总之团 ZREM
     *       （含解散团自然清零，裁决⑥），并整键 DEL 其成员贡献键；</li>
     *   <li>{@code club:weekly:{weekStart}}：按 {@code (club_id, week_start) → SUM} 逐周键覆盖，
     *       锚点分组可达的周键与<b>当周键</b>参与纠偏并清理键内残留；<b>历史周键不主动遍历</b>
     *       （锚点不可达的键不清理，漂移超集接受，裁决⑤）；</li>
     *   <li>{@code club:member:{clubId}}：按 {@code (club_id, user_id) → SUM} 逐团覆盖并清理
     *       该键内已无权威里程的成员（回退后残留的正分由此收敛）。</li>
     * </ul>
     *
     * <p><b>整键读口径说明</b>：跑团键的成员规模以「跑团数 / 单团成员数」为上界，故按整键
     * {@code ZREVRANGE} 读成员比对；个人总榜（可达 1w 级）仍走 {@code SCAN} 分批，B18/D3 口径不变。
     * 整轮 best-effort：任一汇总查询异常只 WARN（本轮跳过跑团纠偏），不影响个人榜结算与报表快照，
     * 下一轮收敛。</p>
     *
     * @return 本轮从跑团三组键中清除的残留成员（或整键）条数，仅用于结算日志留痕
     */
    private int reconcileClubDistance() {
        try {
            int pruned = 0;
            // a) 跑团总榜：club:overall ← (club_id) 汇总
            Map<String, Double> clubTarget = new HashMap<>();
            for (ClubDistanceContribution row : nullSafeRows(clubDistanceContributionMapper
                    .selectCreditedClubSummaries(ClubDistanceContribution.STATUS_CREDITED))) {
                if (row == null || row.getClubId() == null) {
                    continue;
                }
                clubTarget.put(String.valueOf(row.getClubId()), metersOf(row));
            }
            zaddAll(CLUB_OVERALL_ZSET_KEY, clubTarget);
            Set<String> staleClubs = staleMembers(CLUB_OVERALL_ZSET_KEY, clubTarget);
            if (!staleClubs.isEmpty()) {
                stringRedisTemplate.opsForZSet().remove(CLUB_OVERALL_ZSET_KEY, staleClubs.toArray(new Object[0]));
                pruned += staleClubs.size();
            }
            // 无 CREDITED 汇总之团：团内成员贡献整键清空（解散即清榜，成员明细不应留半截）
            for (String staleClub : staleClubs) {
                stringRedisTemplate.delete(CLUB_MEMBER_ZSET_KEY_PREFIX + staleClub);
            }
            // b) 周榜：仅锚点分组可达的周键 + 当周键参与纠偏（历史周键不主动遍历，裁决⑤）
            Map<String, Map<String, Double>> weeklyTargets = new HashMap<>();
            for (ClubDistanceContribution row : nullSafeRows(clubDistanceContributionMapper
                    .selectCreditedWeeklySummaries(ClubDistanceContribution.STATUS_CREDITED))) {
                if (row == null || row.getClubId() == null || row.getWeekStart() == null) {
                    continue;
                }
                weeklyTargets.computeIfAbsent(clubWeeklyKey(row.getWeekStart()), k -> new HashMap<>())
                        .merge(String.valueOf(row.getClubId()), metersOf(row), Double::sum);
            }
            weeklyTargets.computeIfAbsent(clubWeeklyKey(null), k -> new HashMap<>());
            for (Map.Entry<String, Map<String, Double>> week : weeklyTargets.entrySet()) {
                zaddAll(week.getKey(), week.getValue());
                Set<String> stale = staleMembers(week.getKey(), week.getValue());
                if (!stale.isEmpty()) {
                    stringRedisTemplate.opsForZSet().remove(week.getKey(), stale.toArray(new Object[0]));
                    pruned += stale.size();
                }
            }
            // c) 成员贡献：club:member:{clubId} ← (club_id, user_id) 汇总
            Map<Long, Map<String, Double>> memberTargets = new HashMap<>();
            for (ClubDistanceContribution row : nullSafeRows(clubDistanceContributionMapper
                    .selectCreditedMemberSummaries(ClubDistanceContribution.STATUS_CREDITED))) {
                if (row == null || row.getClubId() == null || row.getUserId() == null) {
                    continue;
                }
                memberTargets.computeIfAbsent(row.getClubId(), k -> new HashMap<>())
                        .merge(String.valueOf(row.getUserId()), metersOf(row), Double::sum);
            }
            for (Map.Entry<Long, Map<String, Double>> club : memberTargets.entrySet()) {
                String key = CLUB_MEMBER_ZSET_KEY_PREFIX + club.getKey();
                zaddAll(key, club.getValue());
                Set<String> stale = staleMembers(key, club.getValue());
                if (!stale.isEmpty()) {
                    stringRedisTemplate.opsForZSet().remove(key, stale.toArray(new Object[0]));
                    pruned += stale.size();
                }
            }
            return pruned;
        } catch (Exception e) {
            // 跑团对账 best-effort：异常不拖垮个人榜结算/报表快照，残留由下一轮收敛
            log.warn("跑团里程对账异常（best-effort，本轮跳过，下一轮收敛）", e);
            return 0;
        }
    }

    /** 覆盖式 ZADD：以权威汇总无条件覆盖成员分值（幂等纠偏；空汇总不下发任何写） */
    private void zaddAll(String key, Map<String, Double> target) {
        if (target == null || target.isEmpty()) {
            return;
        }
        Set<TypedTuple<String>> tuples = new HashSet<>();
        for (Map.Entry<String, Double> entry : target.entrySet()) {
            tuples.add(TypedTuple.of(entry.getKey(), entry.getValue()));
        }
        stringRedisTemplate.opsForZSet().add(key, tuples);
    }

    /**
     * 键内残留成员识别（只读）：现有成员中不在权威汇总里的即残留（待调用方 ZREM / 整键 DEL）。
     * 键内成员以跑团/成员数为上界，整键读取成本可控（个人总榜另有 SCAN 分批口径）。
     */
    private Set<String> staleMembers(String key, Map<String, Double> target) {
        Set<String> existing = stringRedisTemplate.opsForZSet().reverseRange(key, 0, -1);
        if (existing == null || existing.isEmpty()) {
            return Set.of();
        }
        Set<String> stale = new LinkedHashSet<>();
        for (String member : existing) {
            if (member != null && !target.containsKey(member)) {
                stale.add(member);
            }
        }
        return stale;
    }

    /** 汇总行空安全（Mapper 返回 null 视为无汇总，不阻断其余对账步骤） */
    private static List<ClubDistanceContribution> nullSafeRows(List<ClubDistanceContribution> rows) {
        return rows == null ? List.of() : rows;
    }

    /** 汇总值 → 米（SUM 空值按 0 处理，避免 NPE 阻断整轮对账） */
    private static double metersOf(ClubDistanceContribution row) {
        return row.getTotalDistance() == null ? 0d : row.getTotalDistance().doubleValue();
    }

    // ==================== 每日报表只读查询 ====================

    /**
     * 某日榜单快照前 N 名（TASK-108）。
     *
     * <p>直读 {@code leaderboard_daily_summary} 而非 ZSet：报表要"那天的数"，ZSet 只有当前值。
     * 昵称与总榜同一路径（一次 Feign 批量补齐，user-service 不可用时降级为「用户{id}」占位）。</p>
     *
     * <p>刻意不加 {@code @Cacheable}：{@code CacheConfig} 的 Caffeine 只注册了
     * {@code leaderboard:overall} 一个 cache 名，换新名字会在 L1 拿到 null。</p>
     */
    public List<LeaderboardDTO> dailyReport(LocalDate date, int topN) {
        int size = topN <= 0 ? DEFAULT_TOP_N : Math.min(topN, DAILY_REPORT_MAX_SIZE);
        List<LeaderboardDailySummary> rows = dailySummaryMapper.selectTopByDate(date, size);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = rows.stream().map(LeaderboardDailySummary::getUserId).toList();
        Map<Long, String> nicknames = resolveNicknames(userIds);

        List<LeaderboardDTO> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            LeaderboardDailySummary row = rows.get(i);
            result.add(LeaderboardDTO.of(i + 1, row.getUserId(),
                    nicknames.getOrDefault(row.getUserId(), "用户" + row.getUserId()),
                    row.getTotalDistance()));
        }
        return result;
    }

    // ==================== 日期范围榜单只读查询（TASK-209） ====================

    /**
     * 日期范围榜单前 N 名（TASK-209）：对贡献表按 {@code settled_at} 半开窗口
     * {@code [start, end+1d)} 聚合 ACTIVE 贡献里程，取前 {@code topN}（默认 50、上限 500）。
     *
     * <p><b>settled_at 归属口径</b>：贡献按<b>结算时间</b>（而非记录创建时间）归属周期——
     * 贡献行在结算任务中被批量盖 settled_at（见 {@link #settleAndReconcile}），
     * 三条边界语义：<code>settled_at</code>=start 当日 0 点后计入本窗；=
     * end+1 日 0 点即被<b>排除</b>（半开窗口）；已回滚（非 ACTIVE）行不入窗。</p>
     *
     * <p>自然周期组装（周 = ISO 周一、月 = 本月 1 号，end=今天）由调用方（web/客户端）负责，
     * 本服务只做原始范围聚合，不感知周/月语义——R2 前端按此约定传入开闭日。</p>
     *
     * <p>公共聚合面（同 {@link #dailyReport}）：不涉及 identity/userId，不缓存。昵称与
     * 总榜同一路径（一次 Feign 批量补齐，user-service 不可用时降级为「用户{id}」占位）。</p>
     */
    public List<LeaderboardDTO> rangeReport(LocalDate start, LocalDate end, int topN) {
        int size = topN <= 0 ? DEFAULT_TOP_N : Math.min(topN, DAILY_REPORT_MAX_SIZE);
        LocalDateTime windowStart = start.atStartOfDay();
        LocalDateTime windowEndExclusive = end.plusDays(1).atStartOfDay();
        List<UserMileage> rows = contributionMapper.selectRangeSummaries(
                ContributionStatus.ACTIVE.getCode(), windowStart, windowEndExclusive, size);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = rows.stream().map(UserMileage::getUserId).toList();
        Map<Long, String> nicknames = resolveNicknames(userIds);

        List<LeaderboardDTO> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            UserMileage row = rows.get(i);
            result.add(LeaderboardDTO.of(i + 1, row.getUserId(),
                    nicknames.getOrDefault(row.getUserId(), "用户" + row.getUserId()),
                    row.getTotalDistance() == null ? BigDecimal.ZERO : row.getTotalDistance()));
        }
        return result;
    }

    // ==================== 赛段 KOM 榜（TASK-272） ====================

    /**
     * 赛段成绩上报（TASK-272 路段挑战与赛段刷榜）：Redis ZSet {@code leaderboard:segment:{segmentId}}
     * （member=userId，score=个人最好 elapsed_seconds，<b>升序</b>——耗时最短即赛段擂主 KOM 👑）。
     * <ol>
     *   <li><b>PR 维护</b>：现成绩不存在或新成绩更优才 ZADD 覆盖（个人最好成绩），同时把 recordId
     *       写入附属 Hash {@code leaderboard:segment:{segmentId}:record}（榜单条目回指完赛记录）；
     *       与个人最好成绩持平（重放/重访口径）不重写 ZSet、不重复计数，但判定仍报 PR；</li>
     *   <li><b>KOM 判定</b>：写后以 ZRANK（升序）取位次，rank=0 即全场第一；未刷新 PR
     *       （本次成绩劣于自身最好成绩）时恒非 KOM——「当前用时」未进榜首语义。</li>
     * </ol>
     * <p>无分布式锁（与 applyVerified 的记录锚点域不同）：赛段榜是纯 Redis 单键写，ZADD 本身
     * 原子；并发上报的瞬态位次竞态由下一次上报的判定收敛（最终一致，ADR-0009 同哲学）。</p>
     */
    public SegmentEffortDTO recordSegmentEffort(Long segmentId, Long userId, Long recordId, Long elapsedSeconds) {
        if (segmentId == null || userId == null || elapsedSeconds == null || elapsedSeconds <= 0) {
            throw new IllegalArgumentException("赛段成绩参数非法：segmentId/userId 必填且 elapsedSeconds 必须为正");
        }
        String key = SEGMENT_ZSET_KEY_PREFIX + segmentId;
        String member = String.valueOf(userId);
        Double current = stringRedisTemplate.opsForZSet().score(key, member);
        boolean better = current == null || elapsedSeconds < current;
        if (better) {
            stringRedisTemplate.opsForZSet().add(key, member, elapsedSeconds.doubleValue());
            stringRedisTemplate.opsForHash().put(key + SEGMENT_RECORD_HASH_SUFFIX, member,
                    String.valueOf(recordId == null ? 0L : recordId));
        }
        boolean isPr = current == null || elapsedSeconds <= current;
        Long rank = stringRedisTemplate.opsForZSet().rank(key, member);
        boolean isKom = isPr && rank != null && rank == 0;
        log.info("赛段成绩上报：segmentId={}, userId={}, recordId={}, elapsed={}s, pr={}, kom={}",
                segmentId, userId, recordId, elapsedSeconds, isPr, isKom);
        SegmentEffortDTO effort = new SegmentEffortDTO();
        effort.setSegmentId(segmentId);
        effort.setUserId(userId);
        effort.setRecordId(recordId);
        effort.setElapsedSeconds(elapsedSeconds);
        effort.setIsPr(isPr);
        effort.setIsKom(isKom);
        return effort;
    }

    /**
     * 赛段 KOM 榜查询（TASK-272）：{@code ZRANGE}（升序，耗时最短在前）取前 N，
     * 批量补昵称（user-service 不可用时降级「用户{id}」占位，与总榜同路径）；recordId 经
     * 附属 Hash 回指、配速由快照表记录里程派生（耗时/里程，分'秒"/km），记录缺失时配速为 "-"。
     * 刻意不加 {@code @Cacheable}：CacheConfig 只注册了 {@code leaderboard:overall} 缓存名
     * （同 {@link #dailyReport} 的注释口径），且赛段榜读面尚未有热读诉求，保持写路径零代理
     * 哨兵口径（ADR-0009）。
     */
    public List<SegmentLeaderboardDTO> getSegmentLeaderboard(Long segmentId, Integer size) {
        if (segmentId == null) {
            throw new IllegalArgumentException("segmentId 不能为空");
        }
        int topN = (size == null || size <= 0) ? DEFAULT_TOP_N : Math.min(size, 1000);
        String key = SEGMENT_ZSET_KEY_PREFIX + segmentId;
        Set<TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, topN - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        // rangeWithScores 升序返回（score 小在前）；保序收集以维护 rank=1 即 KOM 的语义
        List<TypedTuple<String>> ordered = new ArrayList<>(tuples);
        List<Long> userIds = ordered.stream().map(t -> Long.valueOf(t.getValue())).toList();
        Map<Long, String> nicknames = resolveNicknames(userIds);
        Map<Object, Object> recordRows = stringRedisTemplate.opsForHash().entries(key + SEGMENT_RECORD_HASH_SUFFIX);

        List<SegmentLeaderboardDTO> result = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            TypedTuple<String> t = ordered.get(i);
            Long uid = Long.valueOf(t.getValue());
            long elapsed = t.getScore() == null ? 0L : Math.round(t.getScore());
            Long recordId = parseRecordId(recordRows.get(uid.toString()));
            SportRecordSnapshot snapshot = recordId > 0 ? sportRecordMapper.selectById(recordId) : null;
            double distanceKm = snapshot == null || snapshot.getDistance() == null
                    ? 0d : snapshot.getDistance().doubleValue();
            result.add(assembleSegmentEntry(i + 1, uid,
                    nicknames.getOrDefault(uid, "用户" + uid), elapsed, recordId, distanceKm));
        }
        return result;
    }

    /** 赛段榜单条目组装：rank（入参序即耗时升序）+ 回指记录 + 配速派生 */
    private static SegmentLeaderboardDTO assembleSegmentEntry(int rank, Long userId, String nickname,
                                                              long elapsedSeconds, Long recordId, double distanceKm) {
        SegmentLeaderboardDTO dto = new SegmentLeaderboardDTO();
        dto.setRank(rank);
        dto.setUserId(userId);
        dto.setNickname(nickname);
        dto.setElapsedSeconds(elapsedSeconds);
        dto.setRecordId(recordId);
        dto.setPaceFormatted(distanceKm > 0 ? formatSegmentPace(elapsedSeconds / distanceKm) : "-");
        return dto;
    }

    /** 配速格式化（秒每公里 → 分'秒"/km，如 4'05"；与前端配速展示同形） */
    private static String formatSegmentPace(double secondsPerKm) {
        int total = (int) Math.round(secondsPerKm);
        return total / 60 + "'" + String.format("%02d", total % 60) + "\"";
    }

    /** 附属 Hash 的 recordId 解析（数值/字符串双形态容忍，缺失或非法回退 0=无记录） */
    private static long parseRecordId(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ==================== 跑团团队里程榜（TASK-275） ====================

    /**
     * 跑团里程上报（TASK-275 跑团俱乐部与团队里程榜，TASK-276 R3 扩展成员贡献）：
     * 原子 {@code ZINCRBY} 同时累加总榜 {@code leaderboard:club:overall} 与对应周榜
     * {@code leaderboard:club:weekly:{weekStart}}（member=clubId，score=累计里程米），
     * 并把 clubName 同步进名称缓存 Hash {@code leaderboard:club:names}（field=clubId）供榜单条目补名称。
     *
     * <p><b>净额增量口径</b>（TASK-276 R2）：{@code distanceMeters} 为<b>带符号</b>增量——
     * 正数入账（记录迁移至 PASSED/RE_PASSED）、负数回退（迁移至 RE_CONFIRMED 且此前已入账），
     * 0 非法（无变化不应上报）。三处 ZINCRBY 同号累加，保证团队榜与成员贡献榜恒等对账。</p>
     *
     * <p><b>成员贡献</b>（TASK-276 R3）：{@code userId} 非空时同步累加成员贡献 ZSet
     * {@code leaderboard:club:member:{clubId}}（member=userId，score=成员累计米），供
     * {@link #getClubContributions} 出团内贡献明细；userId 为空（历史调用/内部重放）则只累加团队榜。</p>
     *
     * <p>无分布式锁（与赛段榜同哲学）：纯 Redis 单键原子写，并发上报天然累加不丢增量；
     * weekStart 缺省时归一化到当周周一（与 record-service 周报周一锚点同口径）。</p>
     *
     * <p><b>权威源边界（TASK-278）</b>：本方法是跑团里程的<b>投影写入</b>，权威源为 record-service
     * 的 {@code club_distance_contribution} 锚点行（入账时点团/周/里程快照）；本服务对锚点表零写入，
     * 投影漂移（上报丢失、Redis 丢键、成对失败）由 {@code settleAndReconcile} 以锚点汇总裁剪收敛。</p>
     */
    public void recordClubDistance(Long clubId, String clubName, Double distanceMeters,
                                   LocalDate weekStart, Long userId) {
        if (clubId == null || distanceMeters == null || distanceMeters == 0.0) {
            throw new IllegalArgumentException("跑团里程上报参数非法：clubId 必填且 distanceMeters 不得为 0（正数入账/负数回退）");
        }
        String member = String.valueOf(clubId);
        stringRedisTemplate.opsForZSet().incrementScore(CLUB_OVERALL_ZSET_KEY, member, distanceMeters);
        stringRedisTemplate.opsForZSet().incrementScore(clubWeeklyKey(weekStart), member, distanceMeters);
        if (clubName != null && !clubName.isBlank()) {
            stringRedisTemplate.opsForHash().put(CLUB_NAMES_HASH_KEY, member, clubName);
        }
        if (userId != null) {
            stringRedisTemplate.opsForZSet().incrementScore(
                    CLUB_MEMBER_ZSET_KEY_PREFIX + clubId, String.valueOf(userId), distanceMeters);
        }
        log.info("跑团里程入榜：clubId={}, userId={}, +{}m, weekStart={}",
                clubId, userId, distanceMeters, weekStart);
    }

    /**
     * 跑团团队里程榜查询（TASK-275）：{@code ZREVRANGE} 降序取前 N（默认 20、上限 100），
     * clubName 经名称缓存 Hash 补齐（缺失以「跑团{id}」占位，与总榜昵称降级同哲学）；
     * 公里数保留 2 位小数并同步产出格式化串。
     *
     * <p>period：{@code overall}（缺省）读总榜键；{@code weekly} 读当周周一键；
     * 其余值 400。<b>memberCount</b>（TASK-276 R4）不再是恒 0 预留字段——组装前经
     * {@link UserApi#listClubMemberCounts} 一次批查榜单内各团真实 {@code member_count}；
     * 查询失败或返回缺项时<b>降级 0</b>并保持占位语义（榜单主体不因计数缺失而失败）。</p>
     */
    public List<ClubLeaderboardDTO> getClubLeaderboard(String period, Integer size) {
        int topN = (size == null || size <= 0) ? CLUB_DEFAULT_TOP_N : Math.min(size, CLUB_MAX_TOP_N);
        String key;
        if (period == null || period.isBlank() || "overall".equalsIgnoreCase(period)) {
            key = CLUB_OVERALL_ZSET_KEY;
        } else if ("weekly".equalsIgnoreCase(period)) {
            key = clubWeeklyKey(null);
        } else {
            throw new IllegalArgumentException("不支持的跑团榜单类型：" + period);
        }
        Set<TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, 0, topN - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<TypedTuple<String>> ordered = new ArrayList<>(tuples);
        Map<Object, Object> clubNames = stringRedisTemplate.opsForHash().entries(CLUB_NAMES_HASH_KEY);
        List<Long> clubIds = ordered.stream().map(t -> Long.valueOf(t.getValue())).toList();
        Map<Long, Integer> memberCounts = resolveClubMemberCounts(clubIds);

        List<ClubLeaderboardDTO> result = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            TypedTuple<String> t = ordered.get(i);
            Long clubId = Long.valueOf(t.getValue());
            double meters = t.getScore() == null ? 0d : t.getScore();
            // 米 → 公里保留 2 位小数（先放大两位再整圆回缩，规避二进制浮点截断误差）
            double km = Math.round(meters / 10.0) / 100.0;
            Object nameRaw = clubNames.get(String.valueOf(clubId));
            Integer count = memberCounts.get(clubId);
            result.add(assembleClubEntry(i + 1, clubId,
                    nameRaw == null ? null : nameRaw.toString(), km, count == null ? 0 : count));
        }
        return result;
    }

    /**
     * 跑团成员贡献明细（TASK-276 R3）：{@code ZREVRANGE} 降序取前 N（默认 20、上限 100），
     * member=userId、score=成员累计贡献米；昵称一次 Feign 批量补齐，缺失/空昵称回退
     * 「跑者{userId}」（与总榜「用户{id}」同哲学：user-service 降级时榜单仍可用）。
     *
     * <p><b>分值口径（TASK-278 R4 精确化）</b>：入账/回退成对落库时，负增量已由
     * {@link #recordClubDistance} 的带符号 ZINCRBY 抵消，成员分值为非负；但本方法<b>不承诺</b>
     * 分值恒非负——旁路为 best-effort（Feign 抖动 / 锚点已落库而上报未达），短窗口内可能出现
     * 负分或残留正分，该异常窗口由定时对账 {@link #settleAndReconcile} 以锚点 CREDITED 汇总
     * 覆盖纠偏 + 残留清理收敛，而非由读路径钳位掩盖。刻意不加 {@code @Cacheable}
     * （CacheConfig 只注册 {@code leaderboard:overall} 一个缓存名，且写路径 bean 须零代理，
     * ADR-0009 哨兵）。</p>
     */
    public List<ClubMemberContributionDTO> getClubContributions(Long clubId, Integer size) {
        if (clubId == null) {
            throw new IllegalArgumentException("clubId 不能为空");
        }
        int topN = (size == null || size <= 0)
                ? CLUB_CONTRIBUTION_DEFAULT_TOP_N : Math.min(size, CLUB_MAX_TOP_N);
        Set<TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(CLUB_MEMBER_ZSET_KEY_PREFIX + clubId, 0, topN - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<TypedTuple<String>> ordered = new ArrayList<>(tuples);
        List<Long> userIds = ordered.stream().map(t -> Long.valueOf(t.getValue())).toList();
        Map<Long, String> nicknames = resolveNicknames(userIds);

        List<ClubMemberContributionDTO> result = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            TypedTuple<String> t = ordered.get(i);
            Long uid = Long.valueOf(t.getValue());
            String nickname = nicknames.get(uid);
            ClubMemberContributionDTO dto = new ClubMemberContributionDTO();
            dto.setRank(i + 1);
            dto.setUserId(uid);
            dto.setNickname(nickname == null || nickname.isBlank() ? "跑者" + uid : nickname);
            dto.setDistanceMeters(t.getScore() == null ? 0d : t.getScore());
            result.add(dto);
        }
        return result;
    }

    /**
     * 榜单内各跑团真实成员数批查（TASK-276 R4）：一次 Feign 调用
     * {@link UserApi#listClubMemberCounts}；user-service 不可用 / 降级返回空表 / 响应缺项
     * 均降级为空映射（调用方按 0 组装），<b>不得</b>让计数失败拖垮榜单主体。
     */
    private Map<Long, Integer> resolveClubMemberCounts(List<Long> clubIds) {
        if (clubIds.isEmpty()) {
            return Map.of();
        }
        try {
            Result<Map<Long, Integer>> res = userApi.listClubMemberCounts(clubIds);
            Map<Long, Integer> data = res == null ? null : res.getData();
            return data == null ? Map.of() : data;
        } catch (Exception e) {
            log.warn("跑团成员数批查失败，memberCount 降级 0：clubIds={}", clubIds, e);
            return Map.of();
        }
    }

    /** 跑团榜单条目组装：rank（入参序即里程降序）+ Hash 名称补齐 + 真实成员数 + 公里格式化 */
    private static ClubLeaderboardDTO assembleClubEntry(int rank, Long clubId, String clubName,
                                                        double totalDistanceKm, int memberCount) {
        ClubLeaderboardDTO dto = new ClubLeaderboardDTO();
        dto.setRank(rank);
        dto.setClubId(clubId);
        dto.setClubName(clubName == null || clubName.isBlank() ? "跑团" + clubId : clubName);
        dto.setMemberCount(memberCount);
        dto.setTotalDistanceKm(totalDistanceKm);
        dto.setFormattedDistance(String.format("%.2f km", totalDistanceKm));
        return dto;
    }

    /** 跑团周榜键：{weekStart}=周一 ISO 日期；入参缺失归一化到当周周一（Clock 相关分支） */
    private static String clubWeeklyKey(LocalDate weekStart) {
        LocalDate monday = weekStart == null ? LocalDate.now().with(DayOfWeek.MONDAY) : weekStart;
        return CLUB_WEEKLY_ZSET_KEY_PREFIX + monday.format(DateTimeFormatter.ISO_LOCAL_DATE);
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
