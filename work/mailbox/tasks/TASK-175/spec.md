# TASK-175 spec：轨迹点冷热分离归档与终态记录存储治理

## 0. 硬约束与红线（继承 TASK-158/159/165/166/167/170/171/172/173/174 §0，逐字适用）

1. 本任务书是唯一权威。开工先逐位核对 §3 开工读数；**任一不符 ⇒ 停手回报原文，不要自行解释、不要自行订正**。
2. 命令行**全程无中文**（含 grep/sed/awk 模式与 here-doc）；中文内容先写临时脚本文件再执行；提交信息一律使用 UTF-8 无 BOM 文件配合 `git commit -F <file>`。
3. bash 一律写成 `.sh`（或 python `.py`）文件再用 `D:\git\Git\bin\bash.exe <路径>` 执行；禁 `bash -lc` 内联；需抓非零 rc 的段落不要放 `set -e` 下。本机 git 为 **2.20.1.windows.1**（无 `git restore`，放弃工作树改动用 `git checkout -- <path>`）。
4. Maven 唯一入口 `bash scripts/verify/mvn-verify.sh`；严禁裸 mvn、严禁并发执行任何 Maven 命令。
5. **不 push、不建 PR**、不 `git stash`、不 `git add -A`/`add .`（一律逐路径 add）。push 必须由用户显式单次授权。
6. **零触碰名单**：`spec/changes/add-verify-degrade-status-index/`（未跟踪目录，任何任务不得收编）；其余 6 个在途未定/测量提案目录（`add-record-with-points-feign`、`measure-verify-outbox-mark-sent-server-event`、`prove-verify-outbox-relay-concurrency-pool-drain`、`prove-verify-outbox-relay-interval-repeatable`、`resume-verify-outbox-mark-sent-server-event`、`update-verify-outbox-relay-delay`）本轮零触碰。
7. 不翻案、不改写任何已入库结论与历史数字（TASK-143~174）。
8. `work/mailbox/PLAN.md` **纯追加**，不得改动任何既有行（含 L4 与顶端外部门槛叙事）。
9. 词面门红线：**任何要入库的文档都不得原样内嵌词面门的正则字面量或敏感词**。
10. **本任务特有红线**：
    - 不改分片算法/分片数：`track_point` 既有规则（16 片、`user_id % 16`）与 `!SINGLE` 声明零改动；`sharding.yaml` 只允许新增 `track_point_archive` 逻辑表规则与独立 INLINE 算法；
    - 不做一次性全量历史迁移脚本、不做 UNION 视图、不做读路径双查兜底；
    - 不删 `sport_record` 主表行；
    - `getRecordWithPoints` 与 verify 链路（verify-service / api 模块）**零改动**；
    - 不引入 Flyway / Testcontainers / 新中间件 / 新 Maven 插件 / 新依赖；
    - 不动 `record.track.batch-insert-enabled` 既有配置与轨迹写入路径。

---

## 1. 唯一目标与任务背景

### 1.1 背景
课题 3（指导主 Agent 路线图）：`track_point` 按 `user_id % 16` 分 16 物理表，随用户运动记录持续增长；全仓无任何旧数据清理/归档逻辑，终态完结记录的轨迹点（冷数据）永久驻留高频在线分片。轨迹读取全部按 `record_id` 维度（无时间过滤），verify 判定链路（`getRecordWithPoints`）前置为 VERIFYING 活跃态、恒热数据——「记录终态 + 完结时长」构成确定性冷热边界。提案 `spec/changes/add-track-point-cold-archive/`（proposal.md / spec-delta.md / tasks.json 已入库）。

### 1.2 目标
1. 归档表 `track_point_archive`（16 物理表，同分片键同算法）+ `sport_record.archived` 标志；
2. 归档任务：调度互斥（RLock）、候选扫描（终态 + 冷边界 90 天 + 未归档）、单轮批 20、幂等三步迁移（崩溃重入自愈）、迁移计数器；
3. 用户端读路径按标志路由（`listPoints` / `pagePoints`），verify 聚合契约恒热表；
4. `submitAppeal` 对已归档记录拒绝（防复判读空）；
5. 全量单元测试只增不减（record-service 115 → ≥127，全仓 438 → ≥450），Checkstyle ≤862，词面门与契约门全绿。

---

## 2. 架构设计与改动清单

### 2.1 SQL 脚本（`sql/05-track-point-archive-shards.sql`，新建）
按 `sql/04-track-point-shards.sql` 的注释与格式惯例：
1. 文件头注释：说明幂等（`CREATE TABLE IF NOT EXISTS`）、docker-entrypoint-initdb.d 按文件名序在 04 之后执行、既有库需手动执行一次 ALTER；
2. `USE record_db;` 后逐一创建 `track_point_archive_0` .. `track_point_archive_15`：**DDL 逐字段同 `track_point_N`**（id BIGINT NOT NULL 应用雪花 ID / record_id / user_id / seq / lat DECIMAL(10,6) / lng DECIMAL(10,6) / ts BIGINT / speed DECIMAL(6,2)，PRIMARY KEY (id)，KEY idx_record (record_id)，utf8mb4），仅表名与 COMMENT（'轨迹点归档分片表 N'）不同；
3. 脚本尾部：
   ```sql
   ALTER TABLE `sport_record`
       ADD COLUMN `archived` TINYINT NOT NULL DEFAULT 0 COMMENT '轨迹点已归档（0 否 1 是）' AFTER `status`,
       ADD INDEX `idx_archive` (`archived`, `end_time`);
   ```
   注释注明：ALTER 非幂等，既有库手动执行一次，重复执行报列已存在属预期；新库经 initdb 全量执行。开工时用 Read 核对 `sql/02` 中 `sport_record` 既有索引，若与 `idx_archive` 前缀重复则在 handoff 登记（不自行改设计）。

### 2.2 实体（新建 `record-service/src/main/java/com/sportverify/record/entity/TrackPointArchive.java`）
照 `TrackPoint` 逐字段（id `@TableId(type = IdType.ASSIGN_ID)` / recordId / userId / seq / lat / lng / ts / speed，`@Data`），`@TableName("track_point_archive")`；类注释说明「归档分片表（课题 3 冷热分离），迁移保留原雪花 id，不重新生成」。

### 2.3 Mapper（新建 `record-service/src/main/java/com/sportverify/record/mapper/TrackPointArchiveMapper.java`）
`extends BaseMapper<TrackPointArchive>` + `insertBatch`，逐字照 `TrackPointMapper.insertBatch`（`@Insert` 多值 script），仅表名换 `track_point_archive`；注释说明「迁移写入用，id 为热表原值（调用方预置），批插不重算」。

### 2.4 分片配置（`record-service/src/main/resources/sharding.yaml`）
在 `!SHARDING` 规则中新增（既有内容零改动）：
```yaml
      track_point_archive:
        # 16 张归档物理分片表（课题 3 冷热分离，建表见 sql/05-track-point-archive-shards.sql）
        # 分片键与算法与热表一致（user_id % 16），独立 INLINE 算法实例（表达式绑定表名前缀）
        actualDataNodes: ds0.track_point_archive_$->{0..15}
        tableStrategy:
          standard:
            shardingColumn: user_id
            shardingAlgorithms 引用: track_point_archive_inline
```
并在 `shardingAlgorithms` 下新增：
```yaml
      track_point_archive_inline:
        type: INLINE
        props:
          algorithm-expression: track_point_archive_$->{user_id % 16}
```
（注意 YAML 层级与既有 `track_point_inline` 完全同构；上文「shardingAlgorithms 引用:」为行文指引，落文件时写规范键名 `shardingAlgorithmName: track_point_archive_inline`。）

### 2.5 主表实体（`SportRecord.java`）
在 `status` 字段后新增：
```java
    /** 轨迹点已归档标志（0 否 1 是，课题 3 冷热分离；归档任务置位，读路径据此路由） */
    private Integer archived;
```

### 2.6 归档服务（新建 `record-service/src/main/java/com/sportverify/record/service/TrackPointArchiveService.java`）
```java
package com.sportverify.record.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sportverify.api.record.RecordStatus;
import com.sportverify.record.entity.SportRecord;
import com.sportverify.record.entity.TrackPoint;
import com.sportverify.record.entity.TrackPointArchive;
import com.sportverify.record.mapper.SportRecordMapper;
import com.sportverify.record.mapper.TrackPointArchiveMapper;
import com.sportverify.record.mapper.TrackPointMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 轨迹点冷热分离归档任务（课题 3）。
 *
 * <p>候选边界：终态（PASSED/REJECTED/RE_PASSED/RE_CONFIRMED）且 end_time 早于
 * 冷边界（默认 90 天）且未归档。verify 判定链路前置为 VERIFYING 活跃态，
 * 恒热数据，永不与归档冲突。</p>
 *
 * <p>迁移幂等三步（无事务依赖，崩溃重入自愈）：① 归档表已有该记录数据则
 * 跳过插入；② 批插归档表（保留原雪花 id）；③ 删热表后事务外置
 * archived=1。正确性以幂等为主（同类内自调用的 @Transactional 代理不生效，
 * 不依赖事务注解）。</p>
 */
@Slf4j
@Service
public class TrackPointArchiveService {

    /** 归档任务互斥锁（多实例至多一个执行者） */
    static final String ARCHIVE_LOCK_KEY = "lock:track:archive";

    /** 终态集合：判定/复判闭环，不再被判定链路触碰 */
    private static final Set<Integer> FINAL_STATUSES = Set.of(
            RecordStatus.PASSED.getCode(),
            RecordStatus.REJECTED.getCode(),
            RecordStatus.RE_PASSED.getCode(),
            RecordStatus.RE_CONFIRMED.getCode());

    private final SportRecordMapper sportRecordMapper;
    private final TrackPointMapper trackPointMapper;
    private final TrackPointArchiveMapper trackPointArchiveMapper;
    private final RedissonClient redissonClient;
    private final Counter migratedCounter;

    @Value("${track.archive.cold-days:90}")
    private long coldDays;

    @Value("${track.archive.batch-records:20}")
    private int batchRecords;

    public TrackPointArchiveService(SportRecordMapper sportRecordMapper,
            TrackPointMapper trackPointMapper,
            TrackPointArchiveMapper trackPointArchiveMapper,
            RedissonClient redissonClient, MeterRegistry meterRegistry) {
        this.sportRecordMapper = sportRecordMapper;
        this.trackPointMapper = trackPointMapper;
        this.trackPointArchiveMapper = trackPointArchiveMapper;
        this.redissonClient = redissonClient;
        this.migratedCounter = Counter.builder("track.archive.migrated.records")
                .description("Number of records archived to track_point_archive")
                .register(meterRegistry);
    }

    /**
     * 归档扫描（默认 1h 一轮；fixedDelay 保证上一轮跑完再计时）。
     * 锁服务异常时跳过本轮（可用性优先，归档可延迟）。
     */
    @Scheduled(fixedDelayString = "${track.archive.interval-ms:3600000}")
    public void archiveSweep() {
        RLock lock = null;
        try {
            lock = redissonClient.getLock(ARCHIVE_LOCK_KEY);
        } catch (RuntimeException ex) {
            log.warn("track archive lock service error, skip round", ex);
            return;
        }
        if (!tryLockQuietly(lock)) {
            return;
        }
        try {
            sweepOnce();
        } finally {
            unlockQuietly(lock);
        }
    }

    /** 单轮扫描：候选取批后逐记录迁移，单记录失败不断轮 */
    void sweepOnce() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(coldDays);
        List<SportRecord> candidates = sportRecordMapper.selectList(
                new LambdaQueryWrapper<SportRecord>()
                        .eq(SportRecord::getArchived, 0)
                        .in(SportRecord::getStatus, FINAL_STATUSES)
                        .isNotNull(SportRecord::getEndTime)
                        .lt(SportRecord::getEndTime, cutoff)
                        .orderByAsc(SportRecord::getId)
                        .last("LIMIT " + batchRecords));
        for (SportRecord record : candidates) {
            try {
                archiveOne(record);
            } catch (RuntimeException ex) {
                log.warn("archive record failed, continue next: recordId={}",
                        record.getId(), ex);
            }
        }
    }

    /**
     * 单记录幂等迁移：热表读点 →（归档表已有则跳过插入）→ 批插 → 删热表 → 置标志。
     * 零点记录直接置标志；置标志走 archived=0 条件更新，并发竞态安全。
     */
    void archiveOne(SportRecord record) {
        Long recordId = record.getId();
        Long userId = record.getUserId();
        List<TrackPoint> hotPoints = trackPointMapper.selectList(
                new LambdaQueryWrapper<TrackPoint>()
                        .eq(TrackPoint::getRecordId, recordId)
                        .eq(TrackPoint::getUserId, userId)
                        .orderByAsc(TrackPoint::getSeq));
        Long archivedCount = trackPointArchiveMapper.selectCount(
                new LambdaQueryWrapper<TrackPointArchive>()
                        .eq(TrackPointArchive::getRecordId, recordId)
                        .eq(TrackPointArchive::getUserId, userId));
        if (hotPoints.isEmpty() && archivedCount == 0) {
            markArchived(recordId);
            return;
        }
        if (!hotPoints.isEmpty() && archivedCount == 0) {
            trackPointArchiveMapper.insertBatch(hotPoints.stream()
                    .map(this::toArchive).toList());
        }
        if (!hotPoints.isEmpty()) {
            trackPointMapper.delete(new LambdaQueryWrapper<TrackPoint>()
                    .eq(TrackPoint::getRecordId, recordId)
                    .eq(TrackPoint::getUserId, userId));
        }
        markArchived(recordId);
    }

    private TrackPointArchive toArchive(TrackPoint p) {
        TrackPointArchive a = new TrackPointArchive();
        a.setId(p.getId());
        a.setRecordId(p.getRecordId());
        a.setUserId(p.getUserId());
        a.setSeq(p.getSeq());
        a.setLat(p.getLat());
        a.setLng(p.getLng());
        a.setTs(p.getTs());
        a.setSpeed(p.getSpeed());
        return a;
    }

    /** 置归档标志（条件更新防重复置位），成功才递增迁移计数器 */
    private void markArchived(Long recordId) {
        int rows = sportRecordMapper.update(null,
                new LambdaUpdateWrapper<SportRecord>()
                        .eq(SportRecord::getId, recordId)
                        .eq(SportRecord::getArchived, 0)
                        .set(SportRecord::getArchived, 1));
        if (rows > 0) {
            migratedCounter.increment();
        }
    }

    private boolean tryLockQuietly(RLock lock) {
        try {
            return lock.tryLock();
        } catch (RuntimeException ex) {
            log.warn("track archive tryLock error, skip round", ex);
            return false;
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (RuntimeException ex) {
            log.warn("track archive unlock error", ex);
        }
    }
}
```
注意：`tryLock()` 无参（不等待不排队）；若 RecordStatus 枚举常量名与上述任一不符，停手回报。

### 2.7 读路径冷热路由（`SportRecordService.java`）
1. 注入 `TrackPointArchiveMapper`（构造/字段注入按该类既有风格适配）；
2. `listPoints` 改为（校验与 DTO 映射语义不变）：
   ```java
   public List<TrackPointDTO> listPoints(Long recordId) {
       SportRecord record = sportRecordMapper.selectById(recordId);
       if (record == null) {
           throw new BizException(ResultCode.RECORD_NOT_FOUND);
       }
       return routePoints(record).stream().map(this::toDto).toList();
   }
   ```
3. `pagePoints` 的查询分支同样按 `Objects.equals(record.getArchived(), 1)` 路由：归档分支 `trackPointArchiveMapper.selectPage`（同 `(record_id, user_id)` 双条件 + `orderByAsc(seq)`，DTO 映射经 `toHotPoint` 复用 `toDto`）；热分支既有代码逐字不动；
4. 新增私有方法：
   ```java
   /**
    * 冷热路由（课题 3）：archived=1 查归档表，否则热表；两路径均携带
    * (record_id, user_id) 双条件单分片路由并按 seq 升序。
    */
   private List<TrackPoint> routePoints(SportRecord record) {
       if (Objects.equals(record.getArchived(), 1)) {
           return trackPointArchiveMapper.selectList(
                           new LambdaQueryWrapper<TrackPointArchive>()
                                   .eq(TrackPointArchive::getRecordId, record.getId())
                                   .eq(TrackPointArchive::getUserId, record.getUserId())
                                   .orderByAsc(TrackPointArchive::getSeq))
                   .stream().map(this::toHotPoint).toList();
       }
       return trackPointMapper.selectList(new LambdaQueryWrapper<TrackPoint>()
                       .eq(TrackPoint::getRecordId, record.getId())
                       .eq(TrackPoint::getUserId, record.getUserId())
                       .orderByAsc(TrackPoint::getSeq));
   }

   /** 归档实体转热表实体形态（DTO 映射复用） */
   private TrackPoint toHotPoint(TrackPointArchive a) {
       TrackPoint p = new TrackPoint();
       p.setId(a.getId());
       p.setRecordId(a.getRecordId());
       p.setUserId(a.getUserId());
       p.setSeq(a.getSeq());
       p.setLat(a.getLat());
       p.setLng(a.getLng());
       p.setTs(a.getTs());
       p.setSpeed(a.getSpeed());
       return p;
   }
   ```
5. `getRecordWithPoints` **零改动**（恒热表，verify 前置活跃态）。

### 2.8 申诉防线（`SportRecordService.submitAppeal`）
在既有记录存在性/状态校验之后、乐观锁迁移之前，最小插入：
```java
        if (Objects.equals(record.getArchived(), 1)) {
            throw new BizException(ResultCode.RECORD_STATUS_INVALID, "记录已归档，不支持申诉");
        }
```
若 submitAppeal 实际方法结构与上述落位指引不符（如无显式 record 局部变量），按语义最小落位并在 handoff 登记偏差。

### 2.9 测试用例（判别式）
1. **TrackPointArchiveServiceTest**（新建，Mockito + `SimpleMeterRegistry`，照 RecordLikeServiceTest 风格，禁 mock MeterRegistry；MP wrapper 断言用 `TableInfoHelper.initTableInfo` 渲染惯用法）：
   - `sweepOnce_scanPredicate_andBatchLimit`：候选 SQL 段含 `archived`、`status IN`、`end_time`、`ORDER BY id`、`LIMIT`；扫描结果逐条走 archiveOne；
   - `archiveSweep_lockNotAcquired_skipsRound`：`tryLock` false → `selectList` never；
   - `archiveSweep_redissonError_skipsRound`：`getLock` 抛 RuntimeException → 不扫描、不向上抛；
   - `archiveOne_migratesPoints_idPreserved`：热表 2 点 + 归档 count 0 → `insertBatch` 恰 1 次且元素 id 为原值、`delete` 恰 1 次、置标志 update 恰 1 次、counter 值 1；
   - `archiveOne_reentry_afterInsertBeforeDelete`：热表 2 点 + 归档 count 2 → `insertBatch` never、`delete` 恰 1 次、置标志；
   - `archiveOne_reentry_afterDeleteBeforeFlag`：热表 0 点 + 归档 count 2 → `insertBatch`/`delete` 均 never、置标志、counter 1；
   - `archiveOne_emptyPoints_marksFlagDirectly`：热 0 + 归档 0 → 插删均 never、置标志；
   - `archiveOne_markArchivedLostRace_counterNotIncremented`：置标志 update 返回 0 → counter 0。
2. **SportRecordServiceTest**（适配构造注入 + 新增 4 例）：
   - `listPoints_archivedRecord_readsArchiveTable`：record.archived=1 → `trackPointArchiveMapper.selectList` 且 `trackPointMapper.selectList` never；
   - `pagePoints_archivedRecord_readsArchiveTable`：同上分页口径（`selectPage`）；
   - `getRecordWithPoints_alwaysReadsHotTable`：即便 archived=1 也恒热表（archiveMapper 交互 never）；
   - `submitAppeal_archivedRecord_rejected`：可申诉终态 + archived=1 → 抛 RECORD_STATUS_INVALID、`updateStatus` never。
3. 既有用例预期零翻转（mock record 的 archived 默认 null → 热路径）；SportRecordService 构造注入变化的 setUp 适配属预期偏差，登记即可。

---

## 3. 开工读数（开工前逐位核验）

- HEAD：`c9cbf9ad2812af52f244263d102cb3064f80e8e9`
- `origin/main`：`a8a08a8b58364f7e41b9334acecfe0be664cb87d`
- `git rev-list --left-right --count origin/main...main` = `0 1`
- 工作树状态：仅包含在途提案 `spec/changes/add-track-point-cold-archive/`（三件套已随派发笔入库，应无未跟踪残留）与本任务书 `work/mailbox/tasks/TASK-175/spec.md`，以及既有白名单脏项 `?? spec/changes/add-verify-degrade-status-index/`（严禁触碰）
- 全量 offline 单元测试：`36/41/33/115/144/59/10` 全绿，Failures/Errors/Skipped 全 0，BUILD SUCCESS
- 静态检查：Checkstyle 严格为 **862** 处（≤ 862）
- 词面门四形态：全 `ZERO_HIT rc=1`

---

## 4. 只改清单白名单（精确文件路径）

1. `sql/05-track-point-archive-shards.sql`（新建）
2. `record-service/src/main/java/com/sportverify/record/entity/TrackPointArchive.java`（新建）
3. `record-service/src/main/java/com/sportverify/record/mapper/TrackPointArchiveMapper.java`（新建）
4. `record-service/src/main/java/com/sportverify/record/service/TrackPointArchiveService.java`（新建）
5. `record-service/src/main/java/com/sportverify/record/entity/SportRecord.java`
6. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`
7. `record-service/src/main/resources/sharding.yaml`
8. `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`
9. `record-service/src/test/java/com/sportverify/record/service/TrackPointArchiveServiceTest.java`（新建）
10. `spec/changes/add-track-point-cold-archive/tasks.json`
11. `work/mailbox/tasks/TASK-175/spec.md`
12. `work/mailbox/tasks/TASK-175/handoff.md`
13. `work/mailbox/PLAN.md`（纯追加）

**严禁修改任何未列出的文件**（proposal.md / spec-delta.md 已随派发笔入库，本轮零修改）。

---

## 5. 受保护 tokens 基线（27 项，只增不减）

开工基线在 `work/mailbox/PLAN.md` 中实测行命中数（grep -cF）：
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
- `37008317295` = 2

收口时 `PLAN.md` 的所有 token 出现次数必须 ≥ 基线值。

---

## 6. 门禁与提交结构

1. **门禁（全项亲跑并记录退出码）**：
   - `bash scripts/verify/mvn-verify.sh --mode=offline test`：七模块全绿，record-service ≥127、全仓 ≥450、Skipped 全 0、既有用例零翻转；
   - `bash scripts/verify/mvn-verify.sh --static=verify-service`：Checkstyle ≤ 862；
   - 词面门四形态（default / `LC_ALL=C` / `LC_ALL=zh_CN.UTF-8` / `LC_ALL=C.UTF-8`，正则自 `.github/workflows/ci.yml` 提取到临时脚本）：全 ZERO_HIT rc=1；
   - `git diff --check` rc=0；
   - `bash scripts/verify/mailbox-contract.sh --open TASK-175 --baseline=c9cbf9ad…` 实测在途态；收口后无参模式 rc=0；
   - PLAN.md 27 项 token 只增不减（§5 基线）。
2. **红绿流程**：§2.9 新测试先于生产代码改动跑红（红证据留存 handoff），再实现跑绿。
3. **提交结构（两笔本地提交，不 push）**：
   - C-01 业务笔：白名单 1-9，提交信息 `feat(record): 轨迹点冷热分离归档任务与读路径冷热路由（TASK-175）`；
   - C-02 台账笔：白名单 10-13，提交信息 `docs(mailbox): 登记 TASK-175 验收记录与提案闭环（TASK-175）`。

---

## 7. 未覆盖项（如实登记，不得写成通过）

1. 真实 MySQL/ShardingSphere 下归档表路由、批插、删除与幂等重入未做集成测试（record-service 无 IT 先例，本轮仅 Mockito 单测判别）；
2. `sql/05` 脚本未在真实库执行验证（DDL 语法与既有 04 同构，正确性靠比对；既有库 ALTER 需运维手动执行）；
3. 归档任务周期性真实运行行为（1h 调度、多实例锁竞争）未实测；
4. 迁移完成至置标志之间的毫秒级窗口内读空为已知边界（仅影响终态 90 天以上旧记录）；
5. 不宣称任何存储/查询性能收益（未实测）；spotbugs/pmd 未覆盖（checkstyle 持平即止）。

---

## 8. 交付物

`work/mailbox/tasks/TASK-175/handoff.md`：开工读数逐位核对结果、偏差登记、红绿证据、逐门退出码、只改清单与 `git diff --name-only <开工 HEAD>` 逐条比对、未覆盖项如实登记。全部完成或触发 §0/提案停止条件后一次性汇报。
