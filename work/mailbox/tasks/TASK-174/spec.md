# TASK-174 spec：点赞读路径防击穿治理与 pending 队列可观测（F17 + 队列度量）

## 0. 硬约束与红线（继承 TASK-158/159/165/166/167/170/171/172/173 §0，逐字适用）

1. 本任务书是唯一权威。开工先逐位核对 §3 开工读数；**任一不符 ⇒ 停手回报原文，不要自行解释、不要自行订正**。
2. 命令行**全程无中文**（含 grep/sed/awk 模式与 here-doc）；中文内容先写临时脚本文件再执行；提交信息一律使用 UTF-8 无 BOM 文件配合 `git commit -F <file>`。
3. bash 一律写成 `.sh`（或 python `.py`）文件再用 `D:\git\Git\bin\bash.exe <路径>` / `python <路径>` 执行；禁 `bash -lc` 内联；需抓非零 rc 的段落不要放 `set -e` 下。本机 git 为 **2.20.1.windows.1**（无 `git restore`，放弃工作树改动用 `git checkout -- <path>`）。
4. Maven 唯一入口 `bash scripts/verify/mvn-verify.sh`；严禁裸 mvn、严禁并发执行任何 Maven 命令（Maven 命令必须严格串行执行）。
5. **不 push、不建 PR**、不 `git stash`、不 `git add -A`/`add .`（一律逐路径 add）。push 必须由用户显式单次授权。
6. **零触碰名单**：`spec/changes/add-verify-degrade-status-index/`（未跟踪目录，任何任务不得收编）；在途提案目录（`add-record-with-points-feign`、`measure-verify-outbox-mark-sent-server-event`、`prove-verify-outbox-relay-concurrency-pool-drain`、`prove-verify-outbox-relay-interval-repeatable`、`resume-verify-outbox-mark-sent-server-event`、`update-verify-outbox-relay-delay`）本轮零触碰。
7. 不翻案、不改写任何已入库结论与历史数字（TASK-103 的 F17 虚报登记、TASK-130 的复核结论、TASK-137 的 F15/F16 修复均保持原文）。
8. `work/mailbox/PLAN.md` **纯追加**，不得改动任何既有行（含 L4 与顶端外部门槛叙事）。
9. 词面门红线：**任何要入库的文档都不得原样内嵌词面门的正则字面量或敏感词**。
10. **范围红线（本任务核心）**：
    - 不加背压 / 队列长度上限 / 拒写（须以真实到达率证据另立提案）；
    - 不改 flush 周期（5s）、批大小默认值（200）、对账周期（10min）、`app.like.flush-batch` 配置；
    - 不动幂等双保险（成员集 SADD + INSERT IGNORE）、写路径无本地事务（ADR-0009）、flush 的 LTRIM 时序、对账权威源语义；
    - 不改任何 SQL、不加任何 Maven 依赖（Micrometer/actuator/Redisson 均已在 classpath）；
    - 不写任何吞吐 / 延迟性能结论（击穿治理是并发正确性治理，无实测性能数字）。
11. **既有用例零翻转**：既有 18 个 `RecordLikeServiceTest` 用例只许适配打桩（如锁路径打桩），不许删除、不许改断言语义。

---

## 1. 唯一目标与任务背景

### 1.1 背景
findings-summary F17（`work/mailbox/findings-summary.md` L102，TASK-130 2026-09-23 复核仍在）：`RecordLikeService.readCount`（`record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java:341-351`）在 Redis 计数键缺失时并发回源 DB COUNT——热点记录冷启动 / 键淘汰后高并发 `getLike` 同时 miss，N 个请求触发 N 次 COUNT 与 N 次 SET（击穿）。TASK-103 曾声称以 SETNX 修复，经 TASK-130 核实属台账虚报，代码从未落地。同文件 pending 队列 `like:pending:ops` 零度量（TASK-130 证据：全类零 Micrometer 引用），flush 理论上界 40 ops/s（批 200 / 5s），持续超限单调堆积且不可见。

架构决策（指导侧拍板，见 `spec/changes/add-like-read-cache-breakdown-protection/proposal.md` §2.1）：
- F17 二选一（TASK-130 登记待拍板）**取 Redisson 跨实例互斥锁**，弃进程内 Caffeine 单飞——与 flush / reconcile 既有 RLock 同款、跨实例一致、避免 F13「Caffeine 单层多实例不一致窗口」口径冲突。

### 1.2 目标
1. `readCount` miss 路径互斥重建：获锁者双重检查后单线程回源回填；等待者自旋共享结果；耗尽兜底直读不回填；锁异常降级既有行为；
2. 空值哨兵：0 计数回填 60s TTL 哨兵；非 0 维持既有持久回填；INCR/DECR 后 persist 清 TTL 防计数幽灵回退；
3. pending 队列可观测：`PendingOp` 增 `enqueuedAt`；flush 每轮采集 LLEN 与队头年龄 → Micrometer Gauge（`like.pending.queue.size` / `like.pending.head.age.ms`）；堆积超 1000 记 WARN；
4. 全量单元测试只增不减（全仓 428 → ≥438），Checkstyle ≤862，词面门与契约门全绿，既有用例零翻转。

---

## 2. 架构设计与改动清单

### 2.1 `RecordLikeService` 常量与依赖注入

1. 新增常量：
   ```java
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
   ```
2. 移除类上 `@RequiredArgsConstructor`，手写全参构造器（原 6 参 + `MeterRegistry meterRegistry`），并在构造器末尾注册两个 Gauge（载体为实例字段 `AtomicLong`，初始 -1）：
   ```java
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
               .description("like pending queue head age in ms (-1 for legacy entries without timestamp)")
               .register(meterRegistry);
   }
   ```
   import 增加：`io.micrometer.core.instrument.Gauge`、`io.micrometer.core.instrument.MeterRegistry`、`java.util.concurrent.atomic.AtomicLong`。**严禁在 Gauge description 或日志里使用中文**（保持既有日志中文风格不受影响——description 走英文是 Micrometer 惯例；中文日志行保持既有风格不变）。

### 2.2 `readCount` 互斥重建（核心改动）

替换现有 `readCount` 方法体（L341-351）与新增两个私有方法：
```java
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
    RLock lock = redissonClient.getLock(COUNT_INIT_LOCK_PREFIX + recordId);
    boolean locked = false;
    try {
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
```

### 2.3 计数写入后 persist 清 TTL（防幽灵回退）

`incrementCount` 与 `decrementCount` 修改为：
```java
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
```
（DECR 下限分支 `set(key, "0")` 本身即清除 TTL，无需再 persist。）

### 2.4 `PendingOp` 时间戳与 `parseOp` 容错

```java
/** pending 操作载体（Redis 队列元素，JSON 序列化；enqueuedAt 供队头年龄度量） */
private record PendingOp(Long recordId, Long userId, Action action, long enqueuedAt) {
    String key() {
        return recordId + ":" + userId;
    }
}
```
- `pushPending` 改为 `new PendingOp(recordId, userId, action, System.currentTimeMillis())`；
- `parseOp` 对 `enqueuedAt` 容错：`node.has("enqueuedAt") ? node.get("enqueuedAt").asLong() : -1L`（旧格式队列元素不抛错）。

### 2.5 flush 每轮队列度量采集

`flushPendingLikes` 在获锁后、`range` 取批之前调用新私有方法 `observePendingQueue()`：
```java
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
```
语义边界：空队列两个 Gauge 均归 0；旧格式队头年龄记 -1；WARN 不做单测断言（实现细节）；**不改变任何消费行为**。

### 2.6 测试用例更新与补充（`RecordLikeServiceTest`）

1. **既有用例适配（零翻转）**：
   - `setUp` 构造器调用改为 7 参（末参 `new io.micrometer.core.instrument.simple.SimpleMeterRegistry()`，真实注册表，禁 mock MeterRegistry）；
   - 既有 18 个用例中，除下列打桩适配外**断言与语义一律不动**：
     - `getLike_dbFallbackBackfill`（COUNT=5 → set 两参持久回填）：新实现走「tryLock=true（setUp 全局打桩恒真）→ 二次 GET=null → COUNT=5 → set("like:count:1","5")」，原断言天然成立，**预期零适配**；若实际红，先核对二次 GET 打桩语义再按最小 diff 适配并登记；
     - flush 系用例（`flush_batch_lastWinsAndTrim` 等）：`listOps.size(...)` 未打桩时 Mockito 返回 null → `len=0` → 提前 return（不调 `range(0,0)`），主流程照旧，**预期零适配**；若实际红，补 `when(listOps.size(...)).thenReturn(0L)` 打桩并登记。
2. **新增 10 个用例**（全部走 `getLike`/`like`/`unlike`/`flushPendingLikes` 公有或包内入口触发，确定性 Mockito 判别式，禁多线程 latch 形态）：
   - `getLike_miss_rebuildUnderMutexLock`：GET=null → tryLock=true → 二次 GET=null → COUNT 恰 1 次 → set("like:count:1","5") 两参 → 返回 5 → `verify(lock).unlock()`；
   - `getLike_missAfterLock_doubleCheckHitsCache`：GET=null → tryLock=true → 二次 GET="7" → 返回 7 → `verify(recordLikeMapper, never()).countByRecordId(anyLong())`；
   - `getLike_lockWaiter_readsSharedResult`：GET=null → tryLock=false → 自旋重读命中（`thenReturn(null, "5")`）→ 返回 5 → COUNT never；
   - `getLike_lockWaiter_fallbackDirectReadNoBackfill`：GET=null → tryLock=false → 自旋 3 次全 null → COUNT=5 兜底直读返回 → `verify(valueOps, never()).set(anyString(), anyString())` 且 `verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class))`（不回填）；
   - `getLike_redissonError_degradesToDirectBackfill`：`when(redissonClient.getLock("lock:like:count-init:1")).thenThrow(new RuntimeException("redisson down"))` → COUNT=5 → set("like:count:1","5") 两参（降级=既有行为）→ 不抛锁异常；
   - `getLike_zeroCount_sentinelShortTtl`：COUNT=0 → `verify(valueOps).set("like:count:1", "0", 60L, TimeUnit.SECONDS)`；
   - `like_firstLike_persistsCountKey`：PASSED 记录首次点赞 → `verify(redis).persist("like:count:1")`；
   - `unlike_success_persistsCountKey`：SREM=1 → DECR=0 → `verify(redis).persist("like:count:1")`；
   - `flush_publishesPendingGauges`：`listOps.size`=2、`range(0,0)` 返回含 `enqueuedAt`（now−5000）的 JSON、主 `range(0,199)` 返回常规 ops → 断言 SimpleMeterRegistry 读数 `get("like.pending.queue.size").gauge().value()==2.0` 且 `get("like.pending.head.age.ms").gauge().value()>0`（用例内捕获 flush 前后时钟差，断言 age>0 即可，不做精确值断言）；
   - `flush_legacyHeadElement_ageUnknown`：`size`=1、`range(0,0)` 返回**不含** `enqueuedAt` 的旧格式 JSON、主 `range(0,199)` 返回该元素 → `get("like.pending.head.age.ms").gauge().value()==-1.0`，主消费正常完成（trim 被调用）。
3. 测试数：record-service 105 → **≥115**（+10）、全仓 428 → **≥438**；Failures/Errors/Skipped 全 0。

---

## 3. 开工读数（开工前逐位核验）

- 派发笔父提交（门槛基线）：`c4f92abc7feb3dddc1880e1160a1b3a5ea893e03`（含第十四次门槛登记，本地领先 origin/main 1 笔，`origin/main`=`af17908d8cde8a77687e2340d993aec68de81d69`）
- 开工 HEAD：本任务书与提案三件套的入库提交（派发笔），开工时以 `git rev-parse HEAD` 实测并逐位登记于 handoff §0（派发笔自身即开工基线，`git rev-list --left-right --count origin/main...main` 应为 `0 2`）
- 工作树状态：仅含既有白名单脏项 `?? spec/changes/add-verify-degrade-status-index/`（严禁触碰）与执行侧自建的本任务目录 `work/mailbox/tasks/TASK-174/handoff.md`
- 全量 offline 单元测试基线：`36/41/33/105/144/59/10` 全绿（全仓 428），Failures/Errors/Skipped 全 0
- 静态检查：Checkstyle 严格为 **862** 处（`--static=verify-service` 口径，record-service 不在静态扫描范围，该门只为全局不回退）
- 词面门四形态：全 `ZERO_HIT rc=1`
- 契约门在途口径：`mailbox-contract.sh --open TASK-174 --baseline=<派发笔哈希>` rc=0（提交前实测，届时本任务无只改清单 claims）

---

## 4. 只改清单白名单（精确文件路径）

1. `record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java`
2. `record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java`
3. `spec/changes/add-like-read-cache-breakdown-protection/tasks.json`（四任务闭环勾选，proposal 与 spec-delta 零修改）
4. `work/mailbox/tasks/TASK-174/handoff.md`（新建，执行侧交付）
5. `work/mailbox/PLAN.md`（纯追加 TASK-174 验收记录）

**严禁修改任何未列出的文件**（含提案 proposal.md / spec-delta.md、主规格、`spec/changes/archive/**`、一切 `scripts/**`、`pom.xml`、配置、SQL）。

---

## 5. 受保护 tokens 基线（26 个，只增不减）

开工基线在 `work/mailbox/PLAN.md` 中实测行命中数（指导侧 2026-10-02 于派发笔父提交 `c4f92ab` 实测）：
- `13.4` = 16
- `18.0` = 18
- `73.93` = 17
- `68.8` = 13
- `6315` = 14
- `1.8612` = 13
- `3.3066` = 13
- `5.7056` = 13
- `9.408` = 13
- `36525962432` = 13
- `36586847965` = 12
- `36438897772` = 13
- `36399582548` = 12
- `36098038547` = 12
- `2806` = 19
- `598` = 12
- `36736221648` = 11
- `36808102571` = 6
- `36821040708` = 4
- `36845152965` = 3
- `36871294588` = 3
- `36880083885` = 4
- `36958994260` = 4
- `36976873215` = 4
- `36992632143` = 3
- `36995450125` = 1

收口时 `PLAN.md` 的所有 token 出现次数必须 ≥ 基线值。

---

## 6. 验证命令与闭环标准

1. 离线全量测试：
   `& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test`
   - 要求：退出码 0，全模块全绿，`record-service` 测试数 105 → ≥115，全仓测试数 428 → ≥438，Skipped 全 0，既有用例零翻转。
2. 静态检查门禁：
   `& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --static=verify-service`
   - 要求：Checkstyle 违规总数 ≤ 862。
3. 词面门四形态：
   - 提取 CI 正则并在 default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8` 四种形态下实测，全部 `ZERO_HIT rc=1`。
4. 契约校验门禁：
   - 在途测试：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open TASK-174 --baseline=<派发笔哈希>`，退出码 0；
   - 收口无参测试：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh`，退出码 0（判据 B 以无参为准；受保护目录未跟踪文件致 `--open` 判 B 失败属既知表达边界，按 TASK-173 handoff §1.2-3 先例登记）。
5. 格式与空白检查：
   `git diff --check` 退出码 0。
6. 提交结构（两笔本地提交，不 push）：
   - C-01 业务笔：白名单 1–2（生产实现 + 测试），`feat(record): 点赞读路径互斥重建与空值哨兵及 pending 队列度量（TASK-174）`；
   - C-02 台账笔：白名单 3–5（tasks.json 闭环 + handoff + PLAN 纯追加），`docs(mailbox): 登记 TASK-174 验收记录与提案闭环（TASK-174）`。
7. 回传要求（handoff 必含）：
   - 开工读数逐位核对结果（含派发笔哈希与 `0 2` 领先计数）；
   - 红绿证据：新增判别式用例在实现前的红（改 `RecordLikeService` 前先加测试跑红）与实现后的绿；
   - 逐门 G0–Gn 实测退出码（offline / static / 词面 / 契约在途与无参 / diff --check）；
   - §4 只改清单与 `git diff --name-only <派发笔>` 实际改动集逐条比对；
   - 未覆盖项如实登记（如真实 Redis/MySQL 下互斥锁行为未做 IT——record-service 无 IT 先例，记未覆盖）。
