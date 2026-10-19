# TASK-276 跑团域二期：团长移交 · 里程入账与贡献明细 · memberCount 真实计数

> 状态：**进行中（待实施）**（2026-10-19 指导侧立项；开工基线 = 本立项笔（契约门 `--baseline` 以实施派发书指定哈希为准，TASK-275 教训：spec 不写死自引用哈希）；FULL 档，待子 Agent 落地 C-01 业务/测试/UI 与 C-02 台账，全部门禁实测达标后由指导侧与独立复核验收）

## 一、背景与动机

TASK-275 已落地跑团俱乐部与团队里程榜基础设施（DDL/服务/端点/前端/E2E），但留有四项功能缺口与一项关键链路缺口：

1. **团长移交不存在**：`ClubService.leaveClub` 团长且有成员时仅 1002 拦截（L235「团长需先移交团长职位或解散跑团后才能退出」），全仓无 `transferCaptain` 实现——移交提示语指向的功能空缺；
2. **里程入账链路缺失**：`LeaderboardService.recordClubDistance`（L801）仅有 internal 端点 `POST /internal/leaderboard/clubs/distance`（LeaderboardController L190）与单测自环，record/verify 生产链路**零调用**——跑团里程榜当前无数据来源；
3. **贡献明细无承载**：club_member 表无里程字段，ZSet 仅按 clubId 聚合，无成员级明细；
4. **memberCount 恒 0**：`ClubLeaderboardDTO.memberCount` 预留字位（TASK-275 决策④），无真实计数链路。

## 二、目标与验收标准（R0–R5）

- **R0 侦察确认基线**：本 spec 引用行号以 `fba1cb3` 为准；开工前 `git status --porcelain` 必须为空。
- **R1 团长移交**：`ClubService.transferCaptain(operatorId, clubId, targetUserId)` + `POST /api/clubs/{clubId}/transfer-captain`；校验链：操作者须为在任团长（否则 3001/1002）、目标须为本团在册成员且不等于操作者（否则 3001）；同事务双 role 更新（CAPTAIN→MEMBER、目标 MEMBER→CAPTAIN）；member_count 不变；`ClubServiceTest` 扩展移交用例（成功/非团长/目标非成员/目标是自己/目标未入团）。
- **R2 跑团里程入账链路（核心）**：新增 `record-service` `ClubDistanceSettleService`，挂点 `SportRecordService.statusCallback` 终态迁移成功分支：按 record 当前终态对账净额——迁移至 PASSED/RE_PASSED 且该 recordId 未入账 → `+distanceMeters`；迁移至 RE_CONFIRMED 且此前已入账 → `−distanceMeters` 回退；recordId 级 Redisson `trySet` 幂等（24h，沿 NotificationEventConsumer 范式）；入账前经 `UserApi` 查用户所在跑团（未入团跳过）；经 `LeaderboardApi` 调既有 internal 入账端点；**入账为旁路 best-effort：失败仅日志告警不抛出、不阻断状态回调主流程**；申诉改判链路（REJECTED→RE_PASSED 补账、PASSED→RE_CONFIRMED 回退）净额正确。
- **R3 贡献明细**：`LeaderboardService.recordClubDistance` 扩展 userId 参数，同时写总榜/周榜（既有）与成员贡献 ZSet `leaderboard:club:member:{clubId}`（member=userId，score=累计米）；新增查询 `GET /api/leaderboard/clubs/{clubId}/contributions?size=`（TOP N 降序、size 钳制 1..100 默认 20），返回 `List<ClubMemberContributionDTO>`（userId/nickname/distanceMeters/rank），昵称经 UserApi 批查、缺失回退「跑者{userId}」（沿 resolveNicknames 先例）；`LeaderboardClubTest` 扩展贡献写入与查询用例。
- **R4 memberCount 真实计数**：user 侧新增 `InternalClubController`（`/internal/clubs`）：`GET /user/{userId}` 返回所在跑团 ClubDTO（无团返回 null data）、`GET /member-counts?ids=` 批查 clubId→member_count；`LeaderboardService.getClubLeaderboard` 组装时对榜单 TOP N 批查真实 memberCount，**失败降级 0 并保持占位语义**（javadoc 订正「恒 0」表述为「降级 0」）；`ClubLeaderboardDTO` javadoc 同步订正。
- **R5 前端与 E2E**：跑团 Tab 条目展示成员数（memberCount 已有字位，若前端未渲染则补渲染）；clubs 页成员抽屉/详情增成员贡献列或贡献榜段（按贡献明细端点）；`client.ts` 增类型化调用；`web/e2e/helpers.ts` 增 mock 分支与夹具；E2E 全绿（既有 14 用例不破坏，可新增用例）。

## 三、改动白名单（21 项：20 业务/测试/UI + 1 handoff）

1. `api/src/main/java/com/sportverify/api/user/dto/ClubMemberContributionDTO.java`（新增）
2. `api/src/main/java/com/sportverify/api/user/dto/ClubLeaderboardDTO.java`（javadoc 订正）
3. `api/src/main/java/com/sportverify/api/user/UserApi.java`（扩展：按 userId 查所在跑团、批查 member-counts、批查昵称如需）
4. `api/src/main/java/com/sportverify/api/user/UserApiFallback.java`
5. `api/src/main/java/com/sportverify/api/leaderboard/LeaderboardApi.java`（扩展：recordClubDistance 入账契约）
6. `api/src/main/java/com/sportverify/api/leaderboard/LeaderboardApiFallback.java`
7. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（statusCallback 终态挂点注入）
8. `record-service/src/main/java/com/sportverify/record/service/ClubDistanceSettleService.java`（新增）
9. `record-service/src/test/java/com/sportverify/record/service/ClubDistanceSettleServiceTest.java`（新增）
10. `user-service/src/main/java/com/sportverify/user/service/ClubService.java`（transferCaptain + internal 查询支撑）
11. `user-service/src/main/java/com/sportverify/user/controller/ClubController.java`（移交端点）
12. `user-service/src/main/java/com/sportverify/user/controller/InternalClubController.java`（新增）
13. `user-service/src/test/java/com/sportverify/user/service/ClubServiceTest.java`（移交用例）
14. `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`（贡献 ZSet + 贡献查询 + memberCount 组装）
15. `leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java`（贡献端点）
16. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardClubTest.java`（贡献/计数用例）
17. `web/src/pages/clubs.page.vue`（成员抽屉贡献展示）
18. `web/src/pages/leaderboard.page.vue`（跑团 Tab 成员数）
19. `web/src/api/client.ts`
20. `web/e2e/helpers.ts`
21. `work/mailbox/tasks/TASK-276/handoff.md`（C-02 台账）

**零触碰红线**：`sql/`（本任务零 DDL）、全部 `pom.xml`、`web/package.json`、gateway/verify/mapmatch 全部 src/test、`scripts/`、`docker-compose*.yml`、`.github/`、`web/src/pages/index.page.vue`、`typed-router.d.ts`（无新路由）、既有 Redis 键常量值（`leaderboard:club:overall` / `leaderboard:club:weekly:` / `leaderboard:club:names` 仅可引用不可改名）、状态机参数与 MQ 参数。

## 四、门禁要求（七门禁全绿）

| # | 门禁 | 命令 / 口径 | 达标标准 |
| :- | :--- | :--- | :--- |
| 1 | offline 全量单测 | `scripts/verify/mvn-verify.sh --mode=offline test` | BUILD SUCCESS，基线 1164 测（63/79/241/392/203/129/57）只增不减，0 败 0 错 0 跳 |
| 2 | 静态门禁 | `scripts/verify/mvn-verify.sh --mode=offline --static=leaderboard-service` | Checkstyle 0 违规，SpotBugs/PMD 通过 |
| 3 | 前端三件套 | `pnpm --dir web test` / `type-check` / `run e2e` | vitest 90+ 全绿、vue-tsc 0 错、E2E 14+ 全绿 |
| 4 | 契约门 | `scripts/verify/mailbox-contract.sh --open=TASK-276 --baseline=<立项笔>` | rc=0，判据 A 两件套齐，判据 B 白名单双向全等 |
| 5 | token 门 29 项 | `git grep -cF` 三树比对 | 基线树与 C-01 树逐位持平零命中；C-02 树 = 基线 + 29（handoff 自枚举） |
| 6 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex268.txt`（等效手跑 + 阳性对照） | 四 locale 全 ZERO_HIT |
| 7 | diff 净与行尾 | `git diff <baseline> HEAD --check` + blob 字节扫描 | 输出空、CR=0、纯 LF、无 BOM |

## 五、提交纪律

- C-01：`feat(record,user,leaderboard,web): ...（TASK-276）`——恰 20 文件（本 spec §三 1–20 项），不得多不得少；
- C-02：`docs(mailbox): TASK-276 实施 handoff 回填（TASK-276）`——仅 handoff.md 1 文件；
- 封盘前 `origin/main..HEAD` 恰 3 笔线性提交（立项笔 + C-01 + C-02），零推送；
- 提交信息含中文描述，`git diff --check` 提交前为空。

## 六、停止条件（触发即停并汇报）

1. 白名单挂点类名/行号与实际不符需扩界（如 statusCallback 迁移分支结构差异过大）；
2. 需要触碰 pom/api 依赖新坐标（jakarta.validation 之外的新依赖）；
3. 入账链路需要 DDL 或 MQ 参数变更；
4. offline 基线回退或 Checkstyle 新增违规无法在白名单内消除；
5. 单测无法纯 JVM 落地（需真实 Redis/MySQL 之外的容器）。

## 七、风控与回滚

- 入账链路 best-effort 旁路设计：主流程（状态回调）零阻断风险；失败场景仅日志告警，跑团里程榜短时少记可接受（对账兜底可后续课题补）；
- 移交端点纯 DB 双行更新，同事务原子；
- 贡献 ZSet 与 memberCount 均降级安全（缺名/缺数回退占位），前端零崩溃路径；
- 回滚：revert C-01+C-02 即回到 TASK-275 终态，无 schema 变更需要回滚。
