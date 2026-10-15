# TASK-249 任务书（spec.md）

> **课题**：D 系推荐序 ①②③ 合并——D1 总榜缓存失效联动 + D3 结算 scan 游标单遍重构 + D2 appeal 待审索引 + D6 mapmatch 候选集查询防护（数据一致性与查询防护治理）  
> **级别**：FULL 档（leaderboard 核心事件/结算路径生产代码 + mapmatch 核心匹配逻辑 + SQL 基建，多模块 → 独立复核 + 全量逐位离线门禁）  
> **责任服务**：`leaderboard-service`、`mapmatch-service`、`sql`（verify_db 基建）  
> **开工基线**：`<立项笔哈希>`（main，工作树干净；占位符由实施侧 C-02 回填为实际哈希）  
> **状态**：待实施

---

## 1. 背景与缺口（2026-10-16 批 D 系侦察，指导侧证据抽查 8/8 坐实）

1. **D1 总榜缓存失效联动**：`LeaderboardService.top("overall")` 有 `@Cacheable leaderboard:overall` 二级缓存（L1 Caffeine + L2 Redis，TTL 5min），但 `applyVerified`（L138）/ `rollbackOnRejected`（L199）/ 定时结算改完 ZSet 后**从不失效缓存**——驳回/删除回撤后总榜最长 5min 仍显示已撤销旧里程，新入榜最长 5min 不可见，是记录删除/回撤主链路（TASK-223/230/244）的盲区；
2. **D3 结算 scan 不可终止循环（潜伏 P1，定级高于侦察原判）**：`settleAndReconcile` 清理残留成员每轮循环**重开** `opsForZSet().scan(...)` 并全量 drain，以 `scanPage()`「上一批末尾 member 是否在下一批」做终止判据——总榜成员 ≥ `OVERALL_ZSET_SCAN_BATCH`(500) 时该判据永不满足（全量 drain 的 batch 恒含上一批末尾 member），结算线程不可终止（持调度锁 + watchdog 续期）；
3. **D2 appeal 待审全表扫描**：`appeal` 表仅 id PK + `uk_record`，管理端待审列表 `WHERE status=PENDING ORDER BY id` 全表扫描（对比 outbox 表已有 `idx_status_id` 先例）；
4. **D6 mapmatch 宽 bbox 无防护**：`fetchCandidates` 单块（默认 50 点）外接矩形过宽（跨城/长轨迹）时 `ST_DWithin` 候选边可能巨量全拉 JVM 逐点垂距，无上限/分裂防护——马拉松轨迹采样 50 点一块即覆盖整条 42km 走廊。

## 2. 严格改动文件白名单（恰 13 文件）

实施侧仅允许在以下白名单内修改或新增文件，禁止超出范围：

1. `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`（D1 三调用点 + D3 遍历重构）
2. `mapmatch-service/src/main/java/com/sportverify/mapmatch/config/MatchProperties.java`（D6 新参数）
3. `mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java`（D6 自适应分块）
4. `sql/03-verify-db.sql`（D2 建表 KEY）
5. `sql/migrations/add-idx-appeal-status-id.sql`（D2 幂等迁移，新增）
6. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
7. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java`
8. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTraceIdTest.java`
9. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardWritePathStaysUnproxiedTest.java`
10. `mapmatch-service/src/test/java/com/sportverify/mapmatch/config/MatchPropertiesTest.java`
11. `mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchServiceTest.java`
12. `work/mailbox/tasks/TASK-249/spec.md`
13. `work/mailbox/tasks/TASK-249/handoff.md`

**零触碰红线声明**：
- `record-service`、`user-service`、`verify-service`、`gateway-service` Java 代码绝对零触碰（verify 仅上述 2 个 SQL 文件）；
- 根与各子模块 `pom.xml`、`web/package.json` 绝对零触碰（零新外部依赖）；
- `work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 绝对禁止子 Agent 触碰；
- V21/V22/V23 既有迁移脚本、`CacheConfig.java`、`LeaderboardEventConsumer`、`InternalLeaderboardController` 零触碰。

## 3. 详细判别式与实现契约

### 3.1 R1（D1）总榜缓存失效联动
- `LeaderboardService` 新增构造依赖 `org.springframework.cache.CacheManager`（类型注入解析到 `@Primary` `hierarchicalCacheManager`）+ 常量 `OVERALL_CACHE_NAME = "leaderboard:overall"` + 私有 `evictOverallCache()`（try/catch 包裹，异常仅 `log.warn`，best-effort 不抛出）；
- 三个调用点：`applyVerified` credit=true 分支 ZINCRBY 后；`rollbackOnRejected` rows==1 ZINCRBY 后；`settleAndReconcile` ZSet 纠偏完成段（ZADD/清理/截断之后）恰一次；
- 幂等路径（重复 VERIFIED 锚点跳过 / rows==0）**不**调用，避免无效缓存抖动；
- **禁止 @CacheEvict/@Cacheable 等触发代理的注解**（ADR-0009 哨兵 `LeaderboardWritePathStaysUnproxiedTest` 断言写路径 bean 零代理，注解即红）；
- 更新恰 4 处 `new LeaderboardService(...)` 构造调用点补 `mock(CacheManager.class)`：`LeaderboardServiceTest`、`LeaderboardCacheInvocationTest`、`LeaderboardServiceTraceIdTest`、`LeaderboardWritePathStaysUnproxiedTest`；
- 新增单测（纯 Mockito 验证 mock CacheManager 交互，不依赖代理）：入榜 clear / 幂等跳过不 clear / 回滚 rows==1 clear、rows==0 不 clear / evict 抛异常事件照常 / 结算纠偏后 clear 一次。

### 3.2 R2（D3）结算 scan 游标单遍重构
- `settleAndReconcile` 清理段重写：单次 `try (Cursor<TypedTuple<String>> zc = ...scan(...))`（null 安全跳过）+ `while (zc.hasNext())` 逐元组判 stale 累积、`staleBatch.size() >= OVERALL_ZSET_SCAN_BATCH` 批式 `flushStale`、循环后收尾 flush；**删除 `scanPage()` 方法**，无死代码残留；
- 语义保持：stale 判据 `!target.containsKey(...)`、批 500、`pruned` 计数、锁/事务边界不变；
- 新增单测（mock `Cursor`，含 >500 成员场景）：恰一次 `opsForZSet().scan(...)`（times(1)）/ >500 成员单遍终止且 stale 全量分批 ZREM / target 全含零 ZREM / `scan` 返回 null 时结算其余步骤照常。

### 3.3 R3（D2）appeal 待审索引
- `sql/03-verify-db.sql` appeal 建表补 `KEY idx_status_id (status, id)`（对齐 outbox 表同名索引先例）；
- 新增 `sql/migrations/add-idx-appeal-status-id.sql`：`USE verify_db;` + information_schema 幂等 PREPARE 模式（沿 `add-idx-leaderboard-contribution-status-settled.sql` 同款），文件头注释含课题背景与「新建库路径为 03-verify-db.sql 同名 KEY 行」边界说明；
- 不改任何 Java（查询透明受益）；V21/V22/V23 零触碰；scratch 库幂等演练留证可选。

### 3.4 R4（D6）mapmatch 候选集查询防护
- `MatchProperties` 新增 `maxQuerySpanMeters`（默认 2000）与 `maxCandidateEdges`（默认 2000，仅告警不截断），Javadoc 含分裂正确性论证（单点真近邻边必在其子块外扩框内，无候选丢失）；
- `fetchCandidates` 自适应重构：块 bbox 对角线米距（沿用 DEG_FACTOR 换算）> 阈值且块 >1 点 → 中点二分递归，子块独立预筛后并集；候选数超 `maxCandidateEdges` 输出 warn（含块点数与候选数）；**严禁 LIMIT 截断**（丢真近邻边 → 假离路 → R5 误判）；
- `prefilterChunkPoints=50`、`maxSampledPoints=200`、`snapThresholdMeters`、`searchRadiusMeters` 等既有参数默认值零改动，仅新增配置项；
- `MatchPropertiesTest` 补默认值断言；`MapMatchServiceTest` 补用例（mock JdbcTemplate 捕获查询参数）：宽块分裂为多次小 bbox 查询且并集语义不变 / 退化单点单查询 / 候选超限触发 warn 且不截断。

## 4. 本地门禁要求（FULL 档全量逐位）

1. **后端单测（全模块）**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test` → rc=0，全仓测试数只增不减；
2. **前端回归**：`cd web && pnpm run type-check && pnpm test` 全绿通过；
3. **契约门禁**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-249 --baseline=<立项笔哈希>` → 退出码 0；
4. **token 门**：29 项受保护 token 字面量 `git grep -cF` tracked 全量只增不减（改动集零命中，清单勿枚举字面量）；
5. **词面门**：CI 权威口径 `git grep -n -I -iE` 零命中（本机须显式 `-E` + 合成探针阳性对照证明模式有效）；
6. **`git diff --check`**：提交前净。

## 5. 双提交规范

- **C-01 实施笔**：`fix(leaderboard,mapmatch,sql): 总榜缓存失效联动与结算scan单遍重构及查询防护（TASK-249）`——仅文件 1-11；
- **C-02 台账笔**：`docs(mailbox): 登记TASK-249实施完成与门禁回填（TASK-249）`——仅文件 12、13，回填 handoff 五节（实施摘要/只改清单全等声明/门禁读数/受限声明/双提交哈希），并把两件套 `<立项笔哈希>` 占位符替换为实际哈希；
- 已知受限（handoff §4 须登记）：多实例 L1 跨实例失效未做广播（沿「单实例声明接受」口径）；D2 索引收益为静态推论，EXPLAIN 留证可选；D6 候选数告警为观测性手段，不做语义截断。
