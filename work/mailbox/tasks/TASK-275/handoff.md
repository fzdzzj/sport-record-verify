# TASK-275 执行交接（handoff）

> 状态：**待实施**（2026-10-19 指导侧立项；开工基线 main @ `e512add`；FULL 档，待子 Agent 落地 C-01 业务/测试/UI 与 C-02 台账回填）

## 1. 开工基线

- 开工基线：main @ `e512add`（TASK-275 立项笔；派发令同源口径，工作树纯净确认，无跨任务窗口效应）
- C-01 业务/测试/UI 提交：（待回填）
- 任务书：`work/mailbox/tasks/TASK-275/spec.md`
- 双提交纪律执行：C-01 恰 24 业务/测试/UI/SQL/route 文件 + C-02 仅本 handoff 1 文件；严格零推送（`origin/main..HEAD` 收口后 3 笔，推送由指导侧统一执行）

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only e512add` 双向全等）

- 改动清单（恰 25 项 = C-01 业务/测试/UI/SQL/route 24 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - record-service/src/test/java/com/sportverify/record/service/WeeklyDigestServiceTest.java
  - sql/migrations/add-running-club.sql
  - sql/01-user-db.sql
  - api/src/main/java/com/sportverify/api/user/dto/ClubDTO.java
  - api/src/main/java/com/sportverify/api/user/dto/ClubMemberDTO.java
  - api/src/main/java/com/sportverify/api/user/dto/ClubCreateDTO.java
  - api/src/main/java/com/sportverify/api/user/dto/ClubLeaderboardDTO.java
  - user-service/src/main/java/com/sportverify/user/entity/RunningClub.java
  - user-service/src/main/java/com/sportverify/user/entity/ClubMember.java
  - user-service/src/main/java/com/sportverify/user/mapper/RunningClubMapper.java
  - user-service/src/main/java/com/sportverify/user/mapper/ClubMemberMapper.java
  - user-service/src/main/java/com/sportverify/user/service/ClubService.java
  - user-service/src/main/java/com/sportverify/user/controller/ClubController.java
  - user-service/src/test/java/com/sportverify/user/service/ClubServiceTest.java
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardClubTest.java
  - web/src/api/client.ts
  - web/src/pages/clubs.page.vue
  - web/src/pages/leaderboard.page.vue
  - web/src/pages/index.page.vue
  - web/src/App.vue
  - web/src/typed-router.d.ts
  - web/e2e/helpers.ts
  - work/mailbox/tasks/TASK-275/handoff.md
- 零触碰面声明：除上述 25 文件外，全仓其他微服务生产源码、业务逻辑、其他单测、构建脚本、编排与环境配置、其他数据库脚本等 100% 零触碰；零新增外部依赖。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 全仓 offline 全量测试 | `mvn-verify.sh --mode=offline test` | （待回填） | BUILD SUCCESS；全仓七模块 surefire 1135 测只增不减（0 败 0 错 0 跳） |
| 2 | 静态代码门禁 | `mvn-verify.sh --mode=offline --static=leaderboard-service` | （待回填） | BUILD SUCCESS，Checkstyle 0 违规，spotbugs/pmd 同轮通过 |
| 3 | 前端三件套 | `pnpm --dir web test`、`pnpm --dir web type-check`、`pnpm --dir web run e2e` | （待回填） | vitest 90 测只增不减全绿；vue-tsc 类型检查退出码 0；E2E 14/14 全绿 |
| 4 | 契约门 | `mailbox-contract.sh --open=TASK-275 --baseline=e512add` | （待回填） | C-01 后预跑 rc=0，判据 B 清单一致；C-02 后终态复跑 rc=0（25 路径双向全等） |
| 5 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | （待回填） | 基线 e512add 树实测 SUM = **2452**；C-01 树实测 SUM = **2452**（逐位零命中）；C-02 树期望 SUM = **2481** = 2452 + 29（本 handoff 逐位表自枚举恰各 +1）。29 项逐位基线读数：13.4=169、18.0=185、73.93=126、68.8=121、6315=101、1.8612=100、3.3066=100、5.7056=106、9.408=91、36525962432=84、36586847965=81、36438897772=83、36399582548=81、36098038547=79、2806=121、598=118、36736221648=78、36808102571=66、36821040708=60、36845152965=57、36871294588=55、36880083885=57、36958994260=56、36976873215=51、36992632143=48、36995450125=46、37008317295=45、37021305016=44、37591580687=43 |
| 6 | 词面门 | `scripts/verify/check-public-wording.sh` 四 locale | （待回填） | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1 |
| 7 | diff 净与行尾 | `git diff e512add HEAD --check` / 入库 blob CR 计数 | （待回填） | diff --check 输出空 rc=0；入库 blob 逐字节 CR=0 纯 LF 无 BOM |

## 4. 条件项与实施要点（如实回填）

- **R0（单测隔离加固）**：（待回填）
- **R1（数据库表与幂等迁移脚本）**：（待回填）
- **R2（api 模块 DTO 契约）**：（待回填）
- **R3（user-service 跑团服务与控制器）**：（待回填）
- **R4（leaderboard-service 团队里程榜）**：（待回填）
- **R5（前端跑团广场、排行榜与个人仪表盘）**：（待回填）
- **R6（E2E mock 协同）**：（待回填）

## 5. 收口自证

- `git status --porcelain` 输出为空；
- 未推送：`origin/main..HEAD` 收口后 = 3 笔（立项笔 + C-01 + C-02）——推送由指导侧统一执行，本任务严格零推送；
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 25 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望 2481 一致；词面门 C-02 后终态四 locale ZERO_HIT 复核。
