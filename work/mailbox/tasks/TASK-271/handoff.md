# TASK-271 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-18 实施完毕，门禁全绿，待指导侧亲核）

## 1. 开工基线

- 开工基线：main @ `c3652fb`（TASK-271 立项笔；开工基线与派发令口径一致）
- C-01 业务/配置/测试提交：`a8877aa`（`feat(leaderboard,web,record): 补齐E2E勋章mock路由并优化好友榜直调与分片单表声明（TASK-271）`；5 文件 +75/−96）
- 任务书：`work/mailbox/tasks/TASK-271/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only c3652fb` 双向全等）

- 改动清单（恰 6 项 = C-01 业务/配置/测试 5 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - web/e2e/helpers.ts
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java
  - record-service/src/main/resources/sharding.yaml
  - work/mailbox/tasks/TASK-271/handoff.md
- 零触碰面声明：除上述 6 文件外，全仓其他微服务生产 Java 源码、业务逻辑、其他单测、构建脚本、编排与环境配置、数据库脚本等 100% 零触碰；未引入任何新的外部依赖。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | leaderboard 模块全量测试 | `mvn-verify.sh --mode=offline --pl leaderboard-service test` | 0 | BUILD SUCCESS（约 01:52 min）；surefire **110** 测全绿（0 失败、0 错误、0 跳过），全仓保持 1063 测总基线。读数时点：C-01 前后实测 |
| 2 | record 模块全量测试 | `mvn-verify.sh --mode=offline --pl record-service test` | 0 | BUILD SUCCESS（约 02:07 min）；surefire **351** 测全绿（0 失败、0 错误、0 跳过）。读数时点：C-01 后实测 |
| 3 | leaderboard 静态三件套 | `mvn-verify.sh --mode=offline --static=leaderboard-service` | 0 | BUILD SUCCESS（约 33 秒）；**You have 0 Checkstyle violations**，spotbugs/pmd 同轮通过。读数时点：C-01 前后实测 |
| 4 | 前端测试与类型检查 | `npm test && npm run type-check` | 0 | vitest **63** 测全绿（4 test files 全过）；vue-tsc 类型检查退出码 0。读数时点：C-01 后实测 |
| 5 | 契约门 | `mailbox-contract.sh --open=TASK-271 --baseline=c3652fb` | 0 | C-01 后预跑 rc=0；C-02 后终态复跑 rc=0，判据 B 6 路径双向全等（清单多报 0、改动集未声明 0） |
| 6 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | 0 | 基线 c3652fb 树实测 SUM = **2336**；C-01 树（a8877aa）实测 SUM = **2336**（改动文件 29-token 扫描零命中，逐位持平零减少，diff 全等）；C-02 树期望 SUM = **2365** = 2336 + 29（本 handoff 本行逐位表自枚举恰各 +1，C-02 后实测复核）。29 项逐位基线读数（c3652fb 树）：13.4=165、18.0=181、73.93=122、68.8=117、6315=97、1.8612=96、3.3066=96、5.7056=102、9.408=87、36525962432=80、36586847965=77、36438897772=79、36399582548=77、36098038547=75、2806=117、598=114、36736221648=74、36808102571=62、36821040708=56、36845152965=53、36871294588=51、36880083885=53、36958994260=52、36976873215=47、36992632143=44、36995450125=42、37008317295=41、37021305016=40、37591580687=39 |
| 7 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex268.txt` | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1；排除公共模板与归档后机器判据稳定生效 |
| 8 | diff 净与行尾 | `git diff c3652fb HEAD --check` / 入库 blob CR 计数 | 0 | diff --check 输出空 rc=0；C-01 五文件入库 blob 逐字节 CR=0 纯 LF |

## 4. 条件项与实施要点（如实回填）

- **R1（E2E mock 路由补齐）**：
  - 在 `web/e2e/helpers.ts` 的 `handleRecord` 补齐 4 条路由 mock（`GET /my/badges`、`GET /users/{id}/equipped-badge`、`PUT /my/badges/{code}/wear`、`PUT /my/badges/unwear`）；彻底解决 TASK-269 引入的前端挂载期请求在 E2E 套件中的未映射（unmocked）缺口。
- **R2（LeaderboardService 好友 ID 直调改造）**：
  - `LeaderboardService.fetchAllFriendIds` 改造为单次直调 `userApi.listFriendIds(userId)`，彻底消除原 `while (true)` 分页往返与 `user-service` 侧无意义的批量查昵称回表负载；
  - 移除无用常量 `FRIEND_FETCH_SIZE` 并优化方法注释与契约说明；
  - 同步重构 `LeaderboardServiceTest` 与 `LeaderboardCacheInvocationTest` 中的测试桩从 `listFriends` 迁移至 `listFriendIds`，110 个单测全部保持通过，Checkstyle 违规保持 0。
- **R3（sharding.yaml 单表规则校正）**：
  - 在 `record-service/src/main/resources/sharding.yaml` 的 `!SINGLE` 列表移除历史遗留的 `ds0.leaderboard_contribution`，显式补齐 `ds0.sport_goal` 与 `ds0.user_badge`，消除单表冷启动全表嗅探隐患。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；
- 未推送：`origin/main..HEAD` 收口后 = **3 笔**（开工基线 4c85635 领先 0 笔 + 立项笔 `c3652fb` + C-01 `a8877aa` + C-02 本笔）——推送由指导侧批次统一执行，本任务严格零推送。
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 6 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望 2365 一致。
