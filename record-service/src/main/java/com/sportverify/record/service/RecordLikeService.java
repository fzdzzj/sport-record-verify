package com.sportverify.record.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.api.record.dto.LikeDTO;
import com.sportverify.common.exception.BizException;
import com.sportverify.common.result.ResultCode;
import com.sportverify.record.entity.RecordLike;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.mapper.RecordLikeMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 点赞服务（审批版 §4.6，规范差异：点赞前置校验 / 点赞幂等 / 计数读热写冷 /
 * 异步批量落库 / 取消点赞 / 最终一致）。
 *
 * <p><b>读热写冷 + 最终一致</b>（与校验主链路解耦，冷热分层：Redis 原子计数 + 异步批量）：</p>
 * <ul>
 *   <li><b>写路径</b>（点赞/取消，微秒级，不碰 DB）：
 *       成员集 {@code SADD/SREM} 判幂等 → 计数 {@code INCR/DECR} → 往 Redis 队列
 *       {@code like:pending:ops} push 一条 pending 操作；</li>
 *   <li><b>落库路径</b>（{@code @Scheduled} 定时批量 flush）：
 *       Redisson 锁 {@code lock:like:flush}（30s 看门狗）保证多实例仅一个执行，
 *       LRANGE 取一批 → 按 (record_id,user_id) 去重取末次动作 → 批量 INSERT IGNORE / DELETE；
 *       <b>落库成功才 LTRIM</b>，失败保留待重试（flush 本身幂等：INSERT IGNORE 撞主键跳过、DELETE 无行可删）；</li>
 *   <li><b>读路径</b>：计数优先读 Redis，键缺失时兜底 DB COUNT(*) 并回填（读热写冷）；</li>
 *   <li><b>对账</b>（{@code @Scheduled} 低频）：以 record_like 行为<b>权威源</b>，
 *       纠正 Redis 计数并重建成员集（进程重启丢 Redis 后的兜底收敛）。</li>
 * </ul>
 *
 * <p><b>幂等双保险</b>：业务层成员集 SADD 返回 0 视为已赞（计数不增、不产生 pending），
 * 物理层联合主键 INSERT IGNORE 跳过重复行——两层任意一层失效都由另一层兜住。</p>
 */
@Slf4j
@Service
public class RecordLikeService {

    private final SportRecordMapper sportRecordMapper;
    private final RecordLikeMapper recordLikeMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    /** 仅用于 flush 两写同进退；热路径 like/unlike 不走事务（见 ADR-0009） */
    private final PlatformTransactionManager transactionManager;
    /** Micrometer 注册表：pending 队列两 Gauge 的注册载体 */
    private final MeterRegistry meterRegistry;

    /** pending 队列堆积量 Gauge 载体（like.pending.queue.size） */
    private final AtomicLong pendingQueueSize = new AtomicLong(-1);
    /** pending 队头消费延迟 Gauge 载体（like.pending.head.age.ms；-1 表示旧格式无时间戳） */
    private final AtomicLong pendingHeadAgeMs = new AtomicLong(-1);

    public RecordLikeService(SportRecordMapper sportRecordMapper, RecordLikeMapper recordLikeMapper,
                             StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient,
                             ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                             MeterRegistry meterRegistry) {
        this.sportRecordMapper = sportRecordMapper;
        this.recordLikeMapper = recordLikeMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
        this.transactionManager = transactionManager;
        this.meterRegistry = meterRegistry;
        Gauge.builder("like.pending.queue.size", pendingQueueSize, AtomicLong::get)
                .description("like pending queue backlog size (LLEN like:pending:ops)")
                .register(meterRegistry);
        Gauge.builder("like.pending.head.age.ms", pendingHeadAgeMs, AtomicLong::get)
                .description("like pending head age in ms (-1 for legacy entries without timestamp)")
                .register(meterRegistry);
    }

    // ==================== Redis 键与常量 ====================

    /** 点赞数计数键（String，INCR/DECR 维护） */
    private static final String COUNT_KEY_PREFIX = "like:count:";
    /** 点赞用户成员集键（Set，SADD/SREM 判幂等、SISMEMBER 查已赞） */
    private static final String USERS_KEY_PREFIX = "like:record:";
    /** 点赞成员集键后缀 */
    private static final String USERS_KEY_SUFFIX = ":users";
    /** pending 操作队列键（List，flush 批量消费；进程重启后仍在 Redis，可重放） */
    static final String PENDING_QUEUE_KEY = "like:pending:ops";
    /** flush 防重锁键（多实例仅一个执行，审批版 §7.5 定时任务防重同款） */
    static final String FLUSH_LOCK_KEY = "lock:like:flush";
    /** 对账防重锁键（同样多实例仅一个执行） */
    static final String RECONCILE_LOCK_KEY = "lock:like:reconcile";

    /** flush 每批消费上限（LRANGE 0..flushBatch-1）：可配置 app.like.flush-batch，默认 200 */
    @Value("${app.like.flush-batch:200}")
    private int flushBatch = 200;
    /** flush 周期：5s（最终一致窗口可控） */
    private static final long FLUSH_FIXED_DELAY_MS = 5_000;
    /** flush 首跑延迟：5s（启动后先给业务留写入窗口） */
    private static final long FLUSH_INITIAL_DELAY_MS = 5_000;
    /** 对账周期：10min（低频，避免与 flush 争抢） */
    private static final long RECONCILE_FIXED_DELAY_MS = 10 * 60_000;
    /** 对账首跑延迟：1min */
    private static final long RECONCILE_INITIAL_DELAY_MS = 60_000;
    /** 锁等待上限（秒）：拿不到锁直接跳过本轮（下一轮再试） */
    private static final long LOCK_WAIT_SECONDS = 3;
    /** 对账 Redis pipeline 分批提交大小（TASK-180 定档：默认 500）。 */
    private static final int PIPELINE_BATCH_SIZE = 500;

    /** 计数重建锁键前缀（per-record 互斥回源，防击穿 F17） */
    static final String COUNT_INIT_LOCK_PREFIX = "lock:like:count-init:";
    /** 重建锁等待上限（秒）：等待持锁者完成回填，实际等待≈回填耗时 */
    private static final long COUNT_INIT_LOCK_WAIT_SECONDS = 1;
    /** 未获锁者缓存重读次数上限（共享重建结果；耗尽走兜底直读） */
    private static final int COUNT_REBUILD_READ_RETRIES = 3;
    /** 空值哨兵 TTL（秒）：0 计数短 TTL 回填，防冷记录键长驻 */
    private static final long ZERO_SENTINEL_TTL_SECONDS = 60;
    /** pending 堆积告警阈值（观测下限，不背压不拒写） */
    private static final long PENDING_ALERT_THRESHOLD = 1_000;

    /** pending 操作动作 */
    private enum Action { LIKE, UNLIKE }

    /** pending 操作载体（Redis 队列元素，JSON 序列化；enqueuedAt 供队头年龄度量） */
    private record PendingOp(Long recordId, Long userId, Action action, long enqueuedAt) {
        String key() {
            return recordId + ":" + userId;
        }
    }

    // ==================== 点赞（前置校验 + 幂等） ====================

    /**
     * 点赞（规范差异「点赞前置校验」「点赞幂等」）：
     * <ol>
     *   <li>记录存在性（3001）与状态校验：仅 PASSED / RE_PASSED 可赞，否则 6001；
     *       RE_PASSED 是申诉终判「改判通过」，语义同「通过校验」（状态机终态，见 RecordStatus）；</li>
     *   <li>成员集 {@code SADD}：返回 1 = 首次点赞 → 计数 INCR + push pending；
     *       返回 0 = 已赞 → <b>幂等</b>，计数不变、不重复产生 pending（T9：计数 +1 仅一次，落库仅一条）；</li>
     * </ol>
     *
     * <p>无本地事务（ADR-0009）：热路径只写 Redis 成员集/计数/pending 队列，刻意最终一致；
     * Spring 事务管不了 Redis。DB 落库由 flush 批量完成，禁止在此开启覆盖 Redis+DB 的本地事务。</p>
     */
    public LikeDTO like(Long recordId, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        SportRecord record = requireRecord(recordId);
        if (!isLikeable(record.getStatus())) {
            // 规范差异「未通过校验被拒」：不产生点赞与计数变化
            throw new BizException(ResultCode.RECORD_NOT_PASSED, "记录未通过校验，不可点赞");
        }

        // —— 幂等闸门：SADD 原子判重（并发同人同赞只有一个赢家，返回 1 者负责计数）
        Long added = stringRedisTemplate.opsForSet().add(usersKey(recordId), String.valueOf(userId));
        boolean firstLike = added != null && added == 1L;

        long count;
        if (firstLike) {
            count = incrementCount(recordId);
            pushPending(recordId, userId, Action.LIKE);
            log.info("点赞成功：recordId={}, userId={}, count={}", recordId, userId, count);
        } else {
            count = readCount(recordId);
            log.info("重复点赞幂等跳过：recordId={}, userId={}, count={}", recordId, userId, count);
        }
        return LikeDTO.of(recordId, count, true);
    }

    // ==================== 取消点赞（幂等） ====================

    /**
     * 取消点赞（规范差异「取消点赞」「重复取消幂等」）：
     * 成员集 {@code SREM} 返回 1 = 确实点过赞 → 计数 DECR（下限 0）+ push pending 删除；
     * 返回 0 = 未赞 → 幂等，计数不变、无行可删。取消不校验 PASSED
     * （记录事后被驳回也应允许收回点赞）。
     *
     * <p>无本地事务（ADR-0009）：同 {@link #like}，仅 Redis 热写 + pending；DB 删除由 flush 事务批处理。</p>
     */
    public LikeDTO unlike(Long recordId, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        requireRecord(recordId);

        Long removed = stringRedisTemplate.opsForSet().remove(usersKey(recordId), String.valueOf(userId));
        boolean hadLiked = removed != null && removed == 1L;

        long count;
        if (hadLiked) {
            count = decrementCount(recordId);
            pushPending(recordId, userId, Action.UNLIKE);
            log.info("取消点赞：recordId={}, userId={}, count={}", recordId, userId, count);
        } else {
            count = readCount(recordId);
            log.info("重复取消幂等跳过：recordId={}, userId={}", recordId, userId);
        }
        return LikeDTO.of(recordId, count, false);
    }

    // ==================== 计数查询（读热写冷） ====================

    /**
     * 查询点赞状态（规范差异「计数走 Redis」「兜底回填」）：
     * 「是否已赞」走成员集 SISMEMBER；计数优先读 Redis，键缺失（未初始化/被清）时
     * 兜底 DB COUNT(*) 并回填，避免 Redis 冷启动后读到 0 的假象。
     */
    public LikeDTO getLike(Long recordId, Long userId) {
        requireRecord(recordId);
        boolean liked = Boolean.TRUE.equals(
                stringRedisTemplate.opsForSet().isMember(usersKey(recordId), String.valueOf(userId)));
        long count = readCount(recordId);
        return LikeDTO.of(recordId, count, liked);
    }

    // ==================== 异步批量落库（定时任务 + Redisson 防重） ====================

    /**
     * 批量落库（规范差异「批量落库」「多实例防重」，审批版 §7.5 定时任务防重）：
     * <ul>
     *   <li>Redisson 锁 {@code lock:like:flush}（lease=-1 走默认 30s 看门狗自动续期，
     *       与好友互加/榜单定时任务同款）→ 多实例仅一个执行，其余跳过本轮；</li>
     *   <li>LRANGE 取一批（批量上限可配置 {@code app.like.flush-batch}，默认 200）
     *       pending → 按 (record_id,user_id) 去重取<b>末次动作</b>
     *       （同批内 like→unlike 净删、unlike→like 净插）→ 批量 INSERT IGNORE / DELETE；</li>
     *   <li>同一批 {@code batchInsertIgnore} + {@code batchDelete} 经 {@link TransactionTemplate}
     *       同一本地事务同进退（ADR-0009 唯一补点）；</li>
     *   <li><b>事务成功返回之后才 LTRIM</b> 消费队列：DB 任一侧失败则整批回滚且不裁剪，
     *       下一轮重放（flush 幂等：INSERT IGNORE 撞主键跳过、DELETE 无行可删）。
     *       禁止把含 trim 的方法直接标 {@code @Transactional}，以免裁剪发生在 commit 前；</li>
     * </ul>
     */
    @Scheduled(fixedDelay = FLUSH_FIXED_DELAY_MS, initialDelay = FLUSH_INITIAL_DELAY_MS)
    void flushPendingLikes() {
        RLock lock = redissonClient.getLock(FLUSH_LOCK_KEY);
        boolean locked = tryLock(lock);
        if (!locked) {
            log.debug("flush 锁被其他实例持有，本轮跳过");
            return;
        }
        try {
            observePendingQueue();
            List<String> raw = stringRedisTemplate.opsForList().range(PENDING_QUEUE_KEY, 0, flushBatch - 1);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            // 同键取末次动作：LinkedHashMap 保序覆盖 → 净结果（点赞集合 / 取消集合）
            Map<String, PendingOp> lastWins = new LinkedHashMap<>();
            for (String json : raw) {
                PendingOp op = parseOp(json);
                if (op != null) {
                    lastWins.put(op.key(), op);
                }
            }
            LocalDateTime now = LocalDateTime.now();
            List<RecordLike> likes = new ArrayList<>();
            List<RecordLike> unlikes = new ArrayList<>();
            for (PendingOp op : lastWins.values()) {
                RecordLike l = new RecordLike();
                l.setRecordId(op.recordId());
                l.setUserId(op.userId());
                l.setCreatedAt(now);
                (op.action() == Action.LIKE ? likes : unlikes).add(l);
            }
            // —— 两 DB 写同一本地事务；trim 必须在事务成功返回之后（ADR-0009）
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                if (!likes.isEmpty()) {
                    recordLikeMapper.batchInsertIgnore(likes);   // 联合主键冲突自动跳过（幂等）
                }
                if (!unlikes.isEmpty()) {
                    recordLikeMapper.batchDelete(unlikes);       // 无行可删天然幂等
                }
            });
            // 事务已成功提交，才裁剪 pending；失败抛异常 → 不 trim、整批保留重试
            trimConsumed(raw.size());
            log.info("flush 落库完成：点赞 {} 条、取消 {} 条", likes.size(), unlikes.size());
        } finally {
            unlock(lock, locked);
        }
    }

    /** 队列可观测：每轮采集堆积量与队头消费延迟（仅度量，不背压不拒写） */
    private void observePendingQueue() {
        Long size = stringRedisTemplate.opsForList().size(PENDING_QUEUE_KEY);
        long len = size == null ? 0 : size;
        pendingQueueSize.set(len);
        if (len == 0) {
            pendingHeadAgeMs.set(0);
            return;
        }
        long age = -1;
        List<String> head = stringRedisTemplate.opsForList().range(PENDING_QUEUE_KEY, 0, 0);
        if (head != null && !head.isEmpty()) {
            PendingOp op = parseOp(head.get(0));
            if (op != null && op.enqueuedAt() > 0) {
                age = Math.max(0, System.currentTimeMillis() - op.enqueuedAt());
            }
        }
        pendingHeadAgeMs.set(age);
        if (len > PENDING_ALERT_THRESHOLD) {
            log.warn("pending 队列堆积超阈值：len={}, threshold={}", len, PENDING_ALERT_THRESHOLD);
        }
    }

    // ==================== 对账兜底（最终一致） ====================

    /**
     * 对账纠偏（规范差异「对账纠偏」）：以 record_like 行为<b>权威源</b>，
     * 单次批量取全表 (record_id, user_id) 对、内存按 record_id 分组
     * （替代 DISTINCT record_id + 逐 record 查成员的 N+1 扫描），
     * 以 DB 行数覆盖 Redis 计数、按 DB 用户集合重建成员集（恢复幂等防重能力）。
     * 进程重启丢 Redis 计数/成员集后由本任务兜底收敛。
     *
     * <p>已知权衡：pending 尚未落库的操作（窗口内）会被 DB 权威值覆盖，下一轮 flush +
     * 再下一轮对账后收敛——这正是「最终一致」的定义，写路径从不等待落库。</p>
     */
    @Scheduled(fixedDelay = RECONCILE_FIXED_DELAY_MS,
            initialDelay = RECONCILE_INITIAL_DELAY_MS)
    void reconcileLikeCounts() {
        RLock lock = redissonClient.getLock(RECONCILE_LOCK_KEY);
        boolean locked = tryLock(lock);
        if (!locked) {
            log.debug("对账锁被其他实例持有，本轮跳过");
            return;
        }
        try {
            // 一次批量取全表 (record_id, user_id)，内存按 record 分组
            List<RecordLike> pairs = recordLikeMapper.selectRecordLikePairs();
            Map<Long, List<Long>> usersByRecord = new LinkedHashMap<>();
            for (RecordLike pair : pairs) {
                usersByRecord.computeIfAbsent(pair.getRecordId(),
                        k -> new ArrayList<>()).add(pair.getUserId());
            }
            int corrected = 0;
            List<Consumer<RedisConnection>> chunk = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : usersByRecord.entrySet()) {
                Long recordId = entry.getKey();
                List<Long> userIds = entry.getValue();
                // 1) 构造段：逐 record 生成命令三元组（含空成员只 DEL）
                byte[] countKeyBytes =
                        countKey(recordId).getBytes(StandardCharsets.UTF_8);
                byte[] countValBytes = String.valueOf(userIds.size())
                        .getBytes(StandardCharsets.UTF_8);
                chunk.add(conn -> conn.stringCommands().set(
                        countKeyBytes, countValBytes));

                String usersKey = usersKey(recordId);
                byte[] usersKeyBytes =
                        usersKey.getBytes(StandardCharsets.UTF_8);
                chunk.add(conn -> conn.keyCommands().del(usersKeyBytes));

                if (!userIds.isEmpty()) {
                    byte[][] memberBytes = userIds.stream()
                            .map(id -> String.valueOf(id)
                                    .getBytes(StandardCharsets.UTF_8))
                            .toArray(byte[][]::new);
                    chunk.add(conn -> conn.setCommands().sAdd(
                            usersKeyBytes, memberBytes));
                }
                corrected++;

                // 2) 提交段：累积至 PIPELINE_BATCH_SIZE 即 executePipelined
                if (chunk.size() >= PIPELINE_BATCH_SIZE) {
                    List<Consumer<RedisConnection>> batch = chunk;
                    stringRedisTemplate.executePipelined(
                            (RedisCallback<Object>) connection -> {
                                for (Consumer<RedisConnection> cmd : batch) {
                                    cmd.accept(connection);
                                }
                                return null;
                            });
                    chunk = new ArrayList<>();
                }
            }
            // 循环尾提交余量
            if (!chunk.isEmpty()) {
                List<Consumer<RedisConnection>> batch = chunk;
                stringRedisTemplate.executePipelined(
                        (RedisCallback<Object>) connection -> {
                            for (Consumer<RedisConnection> cmd : batch) {
                                cmd.accept(connection);
                            }
                            return null;
                        });
            }
            log.info("点赞对账完成：纠正 {} 条记录的 Redis 计数与成员集", corrected);
        } finally {
            unlock(lock, locked);
        }
    }

    // ==================== 内部工具 ====================

    /** 记录存在性校验（3001） */
    private SportRecord requireRecord(Long recordId) {
        SportRecord record = sportRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ResultCode.RECORD_NOT_FOUND);
        }
        return record;
    }

    /** 可赞状态：PASSED 与 RE_PASSED（申诉终判改判通过，同为「通过校验」终态） */
    private static boolean isLikeable(Integer status) {
        return status != null
                && (status == RecordStatus.PASSED.getCode()
                || status == RecordStatus.RE_PASSED.getCode());
    }

    /** 计数 +1（原子 INCR，不碰 DB；随后清 TTL，防哨兵键过期引发计数回退） */
    private long incrementCount(Long recordId) {
        String key = countKey(recordId);
        Long count = stringRedisTemplate.opsForValue().increment(key);
        persistCountKey(key);
        return count == null ? 0 : count;
    }

    /** 计数 -1（原子 DECR，下限 0；随后清 TTL，防哨兵键过期引发计数回退） */
    private long decrementCount(Long recordId) {
        String key = countKey(recordId);
        Long count = stringRedisTemplate.opsForValue().decrement(key);
        if (count != null && count < 0) {
            stringRedisTemplate.opsForValue().set(key, "0");
            return 0;
        }
        persistCountKey(key);
        return count == null ? 0 : count;
    }

    /** 清除计数键 TTL：哨兵 60s 过期会让已点赞计数幽灵回退为 0（persist 失败不阻断热路径） */
    private void persistCountKey(String key) {
        try {
            stringRedisTemplate.persist(key);
        } catch (Exception e) {
            log.warn("计数键 persist 失败：key={}", key, e);
        }
    }

    /**
     * 读计数：优先 Redis，键缺失（未初始化/冷启动）经互斥重建回填（防击穿 F17）。
     *
     * <p>miss 后以 per-record 锁 {@code lock:like:count-init:{recordId}} 互斥回源：
     * 获锁者双重检查后查 DB 并按哨兵规则回填（0 计数写 60s 空值哨兵，非 0 持久回填）；
     * 未获锁者重读缓存至多 3 次共享结果，耗尽后兜底直读 DB 返回（不回填，
     * 不与持锁者竞争写）；锁服务异常时降级为既有直读回填（可用性优先）。</p>
     */
    private long readCount(Long recordId) {
        String key = countKey(recordId);
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            return Long.parseLong(cached);
        }
        RLock lock = null;
        boolean locked = false;
        try {
            // getLock 与 tryLock 同属重建路径：锁服务任一步抛错都降级直读回填（spec-delta「锁服务异常降级」）
            lock = redissonClient.getLock(COUNT_INIT_LOCK_PREFIX + recordId);
            locked = lock.tryLock(COUNT_INIT_LOCK_WAIT_SECONDS, -1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("计数重建锁服务异常，降级直读回填：recordId={}", recordId, e);
            return rebuildCountFromDb(key, recordId);
        }
        if (locked) {
            try {
                // 双重检查：等锁期间可能已被回填（前一个持锁者 / 对账覆盖）
                cached = stringRedisTemplate.opsForValue().get(key);
                if (cached != null) {
                    return Long.parseLong(cached);
                }
                return rebuildCountFromDb(key, recordId);
            } finally {
                lock.unlock();
            }
        }
        return awaitSharedCount(key, recordId);
    }

    /** 回源 DB 计数并按哨兵规则回填（0 → 60s TTL 哨兵；非 0 → 持久回填） */
    private long rebuildCountFromDb(String key, Long recordId) {
        Long dbCount = recordLikeMapper.countByRecordId(recordId);
        long count = dbCount == null ? 0 : dbCount;
        if (count == 0) {
            stringRedisTemplate.opsForValue().set(key, "0",
                    ZERO_SENTINEL_TTL_SECONDS, TimeUnit.SECONDS);
        } else {
            stringRedisTemplate.opsForValue().set(key, String.valueOf(count));
        }
        log.info("Redis 计数键缺失，DB 兜底回填：recordId={}, count={}", recordId, count);
        return count;
    }

    /** 未获锁等待者：重读缓存共享结果，耗尽后兜底直读 DB 返回（不回填） */
    private long awaitSharedCount(String key, Long recordId) {
        for (int i = 0; i < COUNT_REBUILD_READ_RETRIES; i++) {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached);
            }
        }
        Long dbCount = recordLikeMapper.countByRecordId(recordId);
        log.info("计数重建等待超限，兜底直读：recordId={}, count={}", recordId, dbCount);
        return dbCount == null ? 0 : dbCount;
    }

    /** push 一条 pending 操作（JSON 进 Redis 队列；重启后仍在队列，flush 可重放） */
    private void pushPending(Long recordId, Long userId, Action action) {
        try {
            stringRedisTemplate.opsForList().rightPush(PENDING_QUEUE_KEY,
                    objectMapper.writeValueAsString(
                            new PendingOp(recordId, userId, action, System.currentTimeMillis())));
        } catch (Exception e) {
            // 队列写失败不应阻断点赞主链路：计数与成员集已就绪，flush 轮空即可；
            // 该次落库缺失由对账任务以 DB 行为准兜底（此处记日志供排查）
            log.error("pending 队列写入失败：recordId={}, userId={}", recordId, userId, e);
        }
    }

    /** 解析 pending 队列元素（脏数据返回 null，由调用方跳过；enqueuedAt 缺失记 -1，旧格式兼容） */
    private PendingOp parseOp(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new PendingOp(node.get("recordId").asLong(),
                    node.get("userId").asLong(),
                    Action.valueOf(node.get("action").asText()),
                    node.has("enqueuedAt") ? node.get("enqueuedAt").asLong() : -1L);
        } catch (Exception e) {
            log.warn("pending 元素解析失败，跳过：{}", json, e);
            return null;
        }
    }

    /** 消费队列头 n 条（仅在批量落库成功后调用） */
    private void trimConsumed(int n) {
        stringRedisTemplate.opsForList().trim(PENDING_QUEUE_KEY, n, -1);
    }

    private String countKey(Long recordId) {
        return COUNT_KEY_PREFIX + recordId;
    }

    private String usersKey(Long recordId) {
        return USERS_KEY_PREFIX + recordId + USERS_KEY_SUFFIX;
    }

    /** 加锁（lease=-1 → Redisson 默认 30s 看门狗自动续期，审批版 §7.5 定时任务防重同款） */
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
