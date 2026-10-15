# TASK-249 台账（handoff）

> **TASK**：D 系推荐序 ①②③ 合并——D1 总榜缓存失效联动 + D3 结算 scan 游标单遍重构 + D2 appeal 待审索引 + D6 mapmatch 候选集查询防护（FULL 档）  
> **开工基线**：`5212bc3`（main，工作树干净；本占位符随 C-02 回填为实际哈希）
> **方案**：2026-10-16 批 D 系只读立项侦察产出，用户拍板推荐序 ①②③ 合并立项  
> **状态**：已实施

## 1. 实施摘要

R1/R2/R3/R4 四判别式逐项落地（多模块 FULL 档）：

- **R1（D1 总榜缓存失效联动）**：`LeaderboardService` 新增 `CacheManager` 构造依赖（紧随 `userApi` 之后，`@RequiredArgsConstructor` 按 final 字段声明序生成）+ 常量 `OVERALL_CACHE_NAME = "leaderboard:overall"` + 私有 `evictOverallCache()`（`try/catch`，异常仅 `log.warn` 不抛出）。三个调用点恰各一次：`applyVerified` 在 `credit=true` 分支 `zincrby(...)` 之后；`rollbackOnRejected` 在 `rows==1` 分支 `zincrby(...)` 之后；`settleAndReconcile` 在 ZSet 纠偏（ZADD/清理/截断）完成、`markSettled` 之前。幂等路径（`inserted==0` 且 `updateStatus` 不重激活 / `rows==0`）不调用，避免无效缓存抖动。**未引入 `@CacheEvict`/`@Cacheable` 代理注解**（ADR-0009 哨兵 `LeaderboardWritePathStaysUnproxiedTest` 断言写路径 bean 零代理），故显式经管理器清理。
- **R2（D3 结算 scan 游标单遍重构）**：`settleAndReconcile` 残留清理段重写为**单次** `try (Cursor<TypedTuple<String>> zc = opsForZSet().scan(...)) ` 的 `while (zc.hasNext())` 单遍迭代；逐元组判 stale（`!target.containsKey(Long.valueOf(value))`，null 元组/值安全跳过）累积到 `staleBatch`，`staleBatch.size() >= OVERALL_ZSET_SCAN_BATCH(500)` 即 `flushStale` 批式 ZREM，循环结束再收尾 flush 一次。**已删除 `scanPage()` 方法**（无死代码残留）。语义保持：stale 判据、批 500、`pruned` 计数、锁边界（`SCHEDULER_LOCK_KEY` + try/finally unlock）与无本地事务边界不变；修复了旧实现「成员数 ≥ 批次时终止判据恒真 → 结算线程不可终止」的潜伏缺陷。
- **R3（D2 appeal 待审索引）**：`sql/03-verify-db.sql` appeal 建表在 `UNIQUE KEY uk_record` 之后补 `KEY idx_status_id (status, id)`；新增 `sql/migrations/add-idx-appeal-status-id.sql`（`USE verify_db;` + `information_schema.statistics` 幂等 PREPARE 模式，沿 `add-idx-leaderboard-contribution-status-settled.sql` 同款；文件头含课题背景与「新建库路径为 03-verify-db.sql 同名 KEY 行」边界说明）。**零 Java 改动**（查询透明受益）。
- **R4（D6 mapmatch 候选集查询防护）**：`MatchProperties` 新增 `maxQuerySpanMeters`（默认 2000）与 `maxCandidateEdges`（默认 2000，仅告警不截断）。`MapMatchService.fetchCandidates` 重构为 `collectCandidates(block, acc)` 递归：块 bbox 对角线米距（`Math.hypot`，沿用 `DEG_FACTOR` 米/度换算）> `maxQuerySpanMeters` **且块点数 > 1** 时中点分裂为两个子块递归预筛、结果并入 `acc`（子块外扩框并集与整块语义等价，无候选丢失）；叶子块 `queryForList` 后候选数 > `maxCandidateEdges` 输出 `log.warn`（含块点数与候选数），**不做 LIMIT 截断**。既有参数（`prefilterChunkPoints=50`、`maxSampledPoints=200`、`snapThresholdMeters`、`searchRadiusMeters`）默认值零改动，仅新增配置项。

测试同步：4 个 `new LeaderboardService(...)` 构造点（`LeaderboardServiceTest`/`LeaderboardCacheInvocationTest`/`LeaderboardServiceTraceIdTest`/`LeaderboardWritePathStaysUnproxiedTest`）补 `mock(CacheManager.class)`；`LeaderboardServiceTest` 新增 R1 六用例（入榜 clear / 幂等不 clear / 回滚 rows==1 clear / 回滚 rows==0 不 clear / evict 抛异常不阻断入榜 / 结算纠偏后 clear 一次）与 R2 四用例（scan times(1) / >500 成员单遍终止且 stale 全量分批 ZREM / target 全含零 ZREM / scan 返回 null 时其余结算步骤照常）；`MatchPropertiesTest` 补两新参数默认值断言与 binder 覆盖；`MapMatchServiceTest` 新增宽块分裂多次小 bbox 查询并集不变 / 单点块退化为单次查询不递归 / 候选超限触发 warn 且不截断（mock `JdbcTemplate` 捕获查询参数）。

## 2. 修改清单（实际改动集）

实际改动集 = `git diff --name-only 5212bc3` + 未跟踪文件（`.trae/*` 已按契约排除），恰为 spec §2 的 13 文件白名单，逐项双向全等声明：

- `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
- `mapmatch-service/src/main/java/com/sportverify/mapmatch/config/MatchProperties.java`
- `mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java`
- `sql/03-verify-db.sql`
- `sql/migrations/add-idx-appeal-status-id.sql`
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java`
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTraceIdTest.java`
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardWritePathStaysUnproxiedTest.java`
- `mapmatch-service/src/test/java/com/sportverify/mapmatch/config/MatchPropertiesTest.java`
- `mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchServiceTest.java`
- `work/mailbox/tasks/TASK-249/spec.md`
- `work/mailbox/tasks/TASK-249/handoff.md`

除上述 13 文件外无任何其他改动：四大业务服务 Java（verify 仅上述 2 个 SQL 文件）、`pom.xml`/`web/package.json`、V21/V22/V23 迁移脚本、`CacheConfig.java`、`PLAN.md`/`HANDOVER.md` 均零触碰。

## 3. 门禁读数

读数时点为收口态工作树（C-02 提交前），须注意时点边界：本节读数为「C-01 已提交 + 两件套待提交」时的快照，收口后工作树新增 C-02 台账提交会使部分读数（如 `git diff --check` 的对象集）不可逐字复现，语义等价口径见本节末。

1. **后端全模块离线门禁（FULL 档全量逐位）**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test` → 退出码 **0**，BUILD SUCCESS，全模块逐位通过。各模块 surefire 测试数（`.txt` 汇总）：
   - common **52** / api **无测试**（仅接口与 DTO，无 surefire 报告） / gateway-service **62** / user-service **200** / record-service **298** / verify-service **175** / leaderboard-service **104** / mapmatch-service **29**，Failures=0 / Errors=0 / Skipped=0。
   - 改动集仅触及 leaderboard-service 与 mapmatch-service：leaderboard 由 TASK-248 基线 **94 → 104**（+10，含 R1 六 + R2 四用例）；mapmatch **26 → 29**（+3）；其余模块凭白名单外零 diff 论证不变。全仓测试数只增不减。
2. **前端回归**（零前端改动，照跑）：`cd web; pnpm run type-check` → 退出码 **0**；`pnpm test` → **4 文件 61 tests 全 pass**，退出码 **0**。
3. **契约门**（Git Bash）：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-249 --baseline=5212bc3` → 退出码 **0**，判据 A 两件套齐 + 判据 B 只改清单与实际改动集一致（详见 §3 末时点说明）。
4. **token 门**（29 项 tracked 全量 `git grep -cF` SUM，权威口径，不枚举字面量以免全仓计数自增失准）：基线 `5212bc3` = **2011**，收口态工作树 = **2013**，逐项持平或增加（差值源自新增文本中 `18.0` 对既有短 token 的子串命中）→ 只增不减，通过。
5. **词面门**（CI 权威口径 `git grep -n -I -iE "...`，本机显式 `-E` 以免 `|` 退化为字面量）：
   - 真实扫描（排除 `spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）**零命中**（rc=1）；
   - **探针阳性对照**：对自含禁用词的合成 fixture（`.trae/tmp/`，`--no-index` 扫描）命中 1 行 rc=0 → 正则引擎有效、探针阳性成立；
   - 词面门通过。
6. **`git diff --check`**：C-01 提交前 **rc=0 净**（无空白错误；输出中的 LF→CRLF 为 git 换行转换提示，非空白违规）。提交后工作树仅两件套待 C-02，行尾 CRLF 与仓内既有 blob 惯例一致（未跟踪/修改文本经 git 归一后 blob 与既有 CRLF 惯例一致，无整档行尾翻转）。

## 4. 已知受限与偏差声明

- **多实例 L1 跨实例失效未做广播**：`hierarchicalCacheManager` 的 L2（Redis）为共享失效，但 L1（Caffeine）为每实例本地缓存；D1 的 `evictOverallCache()` 仅清本实例 L1 + 共享 L2。多实例部署下他实例 L1 仍在 TTL（≤5min）内可读旧值——沿本仓「单实例本地缓存声明接受」既有口径（同 `friendLeaderboardCache`），不做广播失效；最终由 TTL 到期收敛。属显式声明接受项，非缺陷。
- **D2 索引收益为静态推论，EXPLAIN/scratch 库演练留证可选**：`appeal` 表管理端待审列表 `WHERE status=? ORDER BY id` 的收益为「等值前缀 + 主键有序」的静态推论（对齐 outbox 表 `idx_status_id` 先例）；本课题未在 scratch 库做 `EXPLAIN` 实测留证（留作可选）。迁移脚本幂等性沿同款 PREPARE 模式，未在 scratch 库二次演练。
- **D6 候选数告警仅观测不截断**：`maxCandidateEdges` 超限仅 `log.warn`，**不做 LIMIT 截断**——截断会丢真近邻边导致假离路与 R5 误判；阈值属观测性护栏，不改变匹配语义。
- **R1 未做 pipeline 类执行路径校正**：D1 三调用点均为单条 ZSet 写后的缓存失效，实测执行路径未涉及 pipeline 计数断言，故无该类校正项。
- **R2 单遍迭代的 Mockito varargs 采集**：单测以 `Cursor`（`CALLS_REAL_METHODS`）模拟 scan 返回，`ZSetOperations.remove(K, Object...)` 的 varargs 经 `InvocationOnMock.getArguments()` 兼容采集（Mockito 可能展开为逐参数或保留为单数组两种形态），仅测试侧适配，不改生产行为。
- 其余无受限项：未 push、未建 PR、未触碰 `PLAN.md`/`HANDOVER.md`、未删弱既有断言、未引新外部依赖/插件（未触碰任何 `pom.xml`）。

## 5. 双提交记录

- **C-01 实施笔** `f9405d8`（`f9405d8`）：
  `fix(leaderboard,mapmatch,sql): 总榜缓存失效联动与结算scan单遍重构及查询防护（TASK-249）` — 仅文件 1-11（11 files changed, 439 insertions(+), 45 deletions(-)，含新增 `sql/migrations/add-idx-appeal-status-id.sql`）。
- **C-02 台账笔**（收口最终 HEAD）：
  `docs(mailbox): 登记TASK-249实施完成与门禁回填（TASK-249）` — 仅文件 12（spec.md，替换两处 `<立项笔哈希>` 占位符为 `5212bc3`）与文件 13（handoff.md，本台账五节回填）。工作树于收口日净（`git status --short` 为空）。
