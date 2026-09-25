# TASK-137：点赞对账去 N+1；flush 批次可配置

## 目标

- **F15 对账去 N+1**：`RecordLikeService.reconcileLikeCounts` 由「`selectDistinctRecordIds()` + 逐 record `selectUserIdsByRecordId()`」的 N+1 扫描，改为**一次批量**取全表 `(record_id, user_id)` 对、内存按 record_id 分组后写 Redis。
- **F16 flush 批次可配置**：硬编码 `FLUSH_BATCH=200` 改为配置 `app.like.flush-batch`（默认仍 200）；flush 的 LRANGE 上界必须使用该配置值，不得写死 199。

## 语义保持（不得改变的既有契约）

- 对账：计数以 DB 行数为准覆盖；成员集 DEL+SADD 重建；空成员只删不 SADD；对账锁竞争跳过本轮。
- flush：周期 5s、last-wins、失败不 trim、锁竞争跳过、插入/删除同事务（ADR-0009）均不变。
- 明确不做：不加 Micrometer、不加队列上限、不加背压；不把「200/5s=40 ops/s」写成已证实吞吐——批次与周期是配置，非实测吞吐结论。

## 方案

- Mapper 新增 `selectRecordLikePairs()`：`SELECT record_id, user_id FROM record_like`，返回 `List<RecordLike>`（演示规模直接全扫；生产可改增量游标/位图）。
- Service 对账：一次查询 → `LinkedHashMap<Long, List<Long>>` 内存分组 → 逐 record 覆盖计数与成员集。旧方法 `selectDistinctRecordIds` / `selectUserIdsByRecordId` 保留，判别式测试约束对账路径对其调用为 0 次。
- Service flush：`@Value("${app.like.flush-batch:200}")` 字段注入（仓库既有 `@Value` 字段风格，`AuthGlobalFilter` / `LeaderboardController` 同款），LRANGE 上界 `flushBatch - 1`；`application.properties` 显式落 `app.like.flush-batch=200`（本服务配置为 properties 格式，snakeyaml 冲突见该文件头注释）。

## 受控红绿判别式（先红后绿）

红相判别式（对基线实现必须失败，且失败原因须为行为差异、非编译错）：

1. `reconcile_singleBatchQuery_fixesRedisFromDb`：改后 `selectUserIdsByRecordId` 调用 0 次、`selectDistinctRecordIds` 0 次、`selectRecordLikePairs` 恰 1 次；两条记录的计数覆盖与成员集重建结果与原逐条实现一致。基线红相：`NeverWantedButInvoked`——`selectUserIdsByRecordId` 在对账路径被逐 record 调用。
2. `flush_batchSizeConfigurable_takesOnlyConfiguredBatch`：批次配置为 2 时 LRANGE 只取 `[0,1]`、trim 2 条。基线红相：`range` 实际上界 199（写死）。

红阶段对主代码仅做**纯增量脚手架**（Mapper 新方法声明、Service 未接线字段、properties 配置行），不改变任何既有行为路径；绿阶段接线并重写对账。旧逐条对账测试被对账判别式替换（其「记录 2 空成员」场景只能经 stub 构造，批量分组结构下天然非空；「空成员只删不 SADD」保留为防御分支）。

## 停止边界

- 只改 `record-service` 与本任务台账（`work/mailbox/tasks/TASK-137/`、`work/mailbox/PLAN.md`）。
- 不碰 `.trae/`；不 push、不建 PR；先交 diff 供审核，审核通过后再提交。
- 真实 Redis/MySQL 上的对账/flush 联调不在本轮（无真中间件 IT 环境），记未覆盖。
