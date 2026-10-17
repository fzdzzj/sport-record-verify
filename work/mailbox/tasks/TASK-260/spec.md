# TASK-260 立项任务书（批 G-2：record/leaderboard 口径小批 G2+G5+G9+G11+G13）

> 状态：**已立项待派发**（指导侧 2026-10-17 立项，FULL 档——G2 触碰核心服务逻辑并发安全语义就高定档，G13 为条件项；五项证据指导侧亲验全部坐实）

## 1. 证据链（指导侧亲验坐实，2026-10-17，TASK-259 合并后实测行号）

1. **G2 整行回写**：SportRecordService L609-617 `selectById → setVisibility → updateById(record)` 整行回写（侦察口径 L615-616 为 TASK-259 前行号，实测位移 +1）；record-service 主代码 `@Version` 注解零命中——并发窗口内 verify statusCallback 的乐观锁迁移（PASSED v1）可被 updateVisibility 旧快照（VERIFYING v0）覆盖且 version 一并回退，E30（TASK-256 昵称整行回写）同根因新落点；
2. **G5 无 tie-break**：LeaderboardContributionMapper L118 `GROUP BY user_id ORDER BY totalDistance DESC LIMIT #{size}`（selectRangeSummaries 周期榜）无次序键；先例 LeaderboardDailySummaryMapper L61 `ORDER BY total_distance DESC, user_id ASC`；
3. **G9 注释滞后两处**：LeaderboardContributionMapper L112-113「settled_at 无专用索引（全局禁加索引）」vs 实况 sql/02-record-db.sql L63 `KEY idx_status_settled (status, settled_at)` + 幂等迁移已建；mapmatch application.yml L92「本服务仅依赖 MySQL（无 Redis）」实为 PostgreSQL/PostGIS；
4. **G11 日期口径错位**：SportRecordService L723-727 my 记录过滤裸 `wrapper.ge/le(SportRecord::getStartTime, ...)` vs myStats L937/GoalService L194 `COALESCE(start_time, created_at)` 口径——仅影响存量 start_time=NULL 行；
5. **G13 filesort 潜势（条件项）**：SportRecordService L778-787 pageFeed `user_id IN(...) AND (user_id=? OR visibility IN(PUBLIC, FRIENDS)) ORDER BY created_at DESC`——filesort 与否需 EXPLAIN 实测裁决。

## 2. 改动白名单（仅限下列文件，双向全等）

1. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（R1+R4+R5 条件改写）
2. `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`（R1 并发语义测试 + R4 口径测试，允许并入）
3. `leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java`（R2+R3a）
4. `leaderboard-service/src/test/java/...`（R2 tie-break 测试落点，文件由执行侧按既有测试布局选定并在 C-01 前登记增补）
5. `mapmatch-service/src/main/resources/application.yml`（R3b 注释订正，零行为变更）
6. 台账：`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md`、`work/mailbox/HANDOVER.md`、`work/mailbox/tasks/TASK-260/spec.md`、`work/mailbox/tasks/TASK-260/handoff.md`

零触碰：api 模块、user/verify/gateway 服务 src/test、全部 pom.xml、package.json、sql/（**G9 只改 Java/yml 注释，DDL 与迁移脚本零触碰——索引已建无需补**）、scripts/、web/、docker-compose*.yml、.github/、状态机/MQ/批量参数。

## 3. 修法要求

- **R1（G2 主项）**：updateVisibility 改 `LambdaUpdateWrapper` 单列 `set(visibility)`（照 E30 修法），杜绝整行回写覆盖 status/version；归属校验前置不变；测试须覆盖「旧快照不再覆盖 status/version」并发语义断言（mock verify 回调后并发窗口行为）与可见性更新生效断言；若 wrapper 需 TableInfoHelper 预注册沿 TASK-221 先例；
- **R2（G5）**：selectRangeSummaries `ORDER BY totalDistance DESC` → `ORDER BY totalDistance DESC, user_id ASC`（照 DailySummaryMapper L61 先例），测试断言同分稳定排序；
- **R3（G9）**：两处注释订正——ContributionMapper L112-113 改为如实登记 idx_status_settled 已建（DDL 02:63 + 迁移）并移除「全局禁加索引」陈旧表述；mapmatch yml L92「MySQL」→「PostgreSQL/PostGIS」；零行为变更；
- **R4（G11）**：my 记录日期过滤改 `apply("COALESCE(start_time, created_at) >= {0}", ...)` 口径（照 myStats L937/goal L194 范式），测试断言 start_time=NULL 行按 created_at 归窗；
- **R5（G13 条件项，前置 EXPLAIN）**：开工后先在 scratch 库对 pageFeed 实际 SQL 跑 EXPLAIN（**禁用生产库**）：若 filesort 坐实，仅允许改查询形态（如 OR 拆分/条件重排），**禁加索引**（红线）；若不坐实，登记「实测无 filesort」零改动收口——两种结局均如实登记 handoff。

## 4. 门禁与验证要求（FULL 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（经 `D:\git\Git\usr\bin\bash.exe --login`），跨 record/leaderboard/mapmatch 三模块 → 七模块全量逐位验证，surefire 基线 **63/78/215/308/200/108/35** 只增不减；
- 契约门（`--open=TASK-260 --baseline=<开工基线>`，handoff §2 逐行列全改动集）、token 门 29 项只增不减、词面门模式文件法+探针阳性对照、checkstyle delta=0（本笔改 LeaderboardContributionMapper 有 diff，静态门以纯净 worktree 双向坐实）、`git diff --check` 净；
- mapmatch yml 改动为注释级，mapmatch 模块测试照跑（35 基线持平）；
- 双提交 C-01/C-02；`git status --porcelain` 收口为空。

## 5. 受限声明（预登记）

1. G2 并发窗口为毫秒级，单测以 mock 语义断言承载（真实双线程竞态实测不强制）；
2. G13 EXPLAIN 读数为 scratch 库时点快照（数据量随演示库演化，登记时点）；
3. G11 仅影响存量 start_time=NULL 行（TASK-235 前旧数据），演示库量级小；
4. Nacos 运行时配置未核（沿 G 系口径）。

## 6. 验收判据

C-01+C-02 落库、七门禁全绿、白名单双向全等、R1 并发语义断言 + R2 稳定排序断言 + R4 口径断言全绿、R5 双结局如实登记；独立复核五块 PASS 后指导侧终裁；CI 外部终验读数由补记笔登记（随下一批次推送）。
