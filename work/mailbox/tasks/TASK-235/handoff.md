# TASK-235 handoff：核心链路缺陷修复与社交闭环——榜单结算时间窗口矫正、申诉判定回写、打卡时间对齐与待审申请一键处理（MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-235`（外约：`work/mailbox/tasks/TASK-235/spec.md`；本文件为回传）。
- 分级：**MEDIUM**（核心业务链路缺陷修复与社交闭环：合理性、边界条件、数据一致性）。
- 开工基线：`ef838b4`（工作树干净；开工前仅本任务 `spec.md` 未跟踪）。
- C-01 实施笔：`feat/refactor 见 git log`（提交信息 `feat(...,web): TASK-235 ...`，恰 11 文件 = 白名单实施面 11 文件）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/224/231/232/233/234 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门禁（CI / 契约脚本 bash 执行）待指导侧推动后补记（见 §7 D2/D3）。

## 2. 修改清单（相对开工基线 ef838b4，恰 13 文件）

C-01（实施笔，恰 11 文件 = 白名单实施面全等）：

- leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java
- verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java
- verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
- user-service/src/main/java/com/sportverify/user/controller/FriendController.java
- user-service/src/main/java/com/sportverify/user/service/FriendService.java
- user-service/src/test/java/com/sportverify/user/controller/FriendControllerTest.java
- web/src/api/client.ts
- web/src/pages/friends.page.vue

C-02（`docs(mailbox)` 台账笔，恰 2 文件）：

- work/mailbox/tasks/TASK-235/spec.md
- work/mailbox/tasks/TASK-235/handoff.md

> 白名单外零触碰：`api/`、`SportRecordMapper.java`、`VerificationResultMapper.java`、`VerificationResult.java`、pom、`package.json`、`playwright.config.ts`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 均零 diff；零新依赖。临时验证日志落 gitignored `.trae/tmp/`，不入库。

## 3. 一句话结论

四处「缺陷/断环」一次收口：① `markSettled` 追加 `AND settled_at IS NULL`，结算只盖未结算行、绝不覆盖历史结算时间，今日/周/月榜单时间窗口自洽（根治失真）；② `reviewAppeal` 终审显式回写 `verification_result.verdict` + 主动失效 Caffeine 缓存（`RESULT_CACHE_PREFIX + recordId`），前端详情与判定查询实时同步；③ `myStreak`/`myStats` 时间口径统一为 `startTime` 优先、null 回退 `createdAt`（与 TASK-234 目标服务 `COALESCE` 口径一致），离线补传/跨零点记录不再误判断签；④ user-service 新增 `GET /api/friends/requests`（to_user=本人、PENDING(0)、createdAt 倒序），web 好友页新增「收到的好友申请」卡片一键同意/拒绝，终结手工敲 requestId。后端全 reactor 830 测 0 失败（record 277 ≥ 275）、前端 type-check/32 测/build/e2e 12 passed 全绿；生产代码仅白名单 5 个服务/控制器/页面文件，测试仅白名单 4 个测试文件（FriendControllerTest 为新建）。

## 4. 判别式与验收要点

- **R1 榜单结算时间窗口矫正（绿）**：`LeaderboardServiceTest.markSettled_sqlOnlyStampsUnsettledRows` 反射读取 `markSettled` 的 `@Update` 注解 SQL，断言含 `settled_at IS NULL`（且 `SET settled_at`、`WHERE status` 保留）——只对尚未结算的 ACTIVE 行盖戳，绝不覆盖既有历史结算时间（否则 `selectRangeSummaries` 的今日/周/月窗口把历史行卷进错误周期）。
- **R2 申诉判定回写闭环（绿）**：`VerifyServiceTest` 新增两用例——终判改判通过（pass=true）：`update(null, UpdateWrapper)` 回写 `verdict=PASSED(1)`（captor 断言 SET 含 verdict、参数含 PASSED 码、WHERE 含 record_id）+ `caffeineCache.invalidate("verify:result:5")`；维持原判（pass=false）：回写 `verdict=REJECTED(2)` + 缓存失效；既有乐观锁冲突用例补断言：0 行时**不回写、不失效缓存、不排事件行**。
- **R3 打卡/统计时间口径对齐（绿）**：`myStreak` 跨零点（活动 23:50 昨日、上传 00:10 今日）与离线补传（活动前日、上传今日）按 `startTime` 归自然日 → current=4/longest=4；`myStats` 日粒度窗口 captor 断言 SQL 片段含 `COALESCE(start_time, created_at)` 与下界参数恰为「今日-29 00:00」，跨零点归昨日、补传归前日、startTime null 回退 createdAt；全量指标仍由 `selectAggByUserId` 恰一次承担（下推保持，无全量行拉取）。
- **R4 好友待办闭环（绿）**：`FriendControllerTest`（新建，真实 FriendService 装配）断言 `listPendingRequests` 查询判别式（to_user=本人、status=PENDING(0)、ORDER BY created_at DESC）；`GET /api/friends/requests` 身份认定两态（auth=false 显式携带 / auth=true X-User-Id 为准、显式不一致 403、缺头 401、空列表透传）；`friends.page.vue` 移除手工 requestId 卡片、新增待办卡片一键同意/拒绝（操作后自动刷新申请与好友列表），type-check 0 错误。
- **R5 红线（绿）**：`git diff --name-only ef838b4` + untracked 恰 13 文件全等于 §2 清单，无多报无漏报；零新依赖。

## 5. 验证命令与输出片段

1. **后端单测**：`mvn -B -ntp -o -s .mvn-settings.xml clean test`（全 reactor 9 模块，等价 `scripts/verify/mvn-verify.sh --mode=offline test`，bash 不可用见 §7 D2）→ **BUILD SUCCESS**（输出落 `.trae/tmp/mvn-verify-235-final.log`）：
   - 模块级：common 52 / gateway-service 50 / user-service **178** / record-service **277** / verify-service **161** / leaderboard-service **86** / mapmatch-service 26，合计 **830**，`Failures: 0, Errors: 0, Skipped: 0`；
   - 本任务增量：record-service 275 → 277（+2：SportRecordServiceTest）、user-service 173 → 178（+5：FriendControllerTest 新建 5 用例）、verify-service 159 → 161（+2：VerifyServiceTest）、leaderboard-service 85 → 86（+1：LeaderboardServiceTest）。
2. **前端 type-check**：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，0 错误）。
3. **前端单测**：`pnpm --dir web test` → rc=0：`Test Files 4 passed (4)` / `Tests 32 passed (32)`（vitest）。
4. **前端构建**：`pnpm --dir web build`（`TMP/TEMP` 重定向 `.trae/tmp`，沿 TASK-224/231/232/233 同款处置）→ rc=0：`✓ built in 41.12s`（chunk >500 kB 为既有非阻断 advisory）。
5. **前端 E2E 全量**：`pnpm --dir web e2e`（同 TMP/TEMP 重定向）→ **`12 passed (59.0s)`，rc=0**（chromium 单 worker，vite preview 生产产物 + route-mock）。
6. **契约门禁**：`bash scripts/verify/mailbox-contract.sh --open=TASK-235 --baseline=ef838b4` 本机 bash 不可执行 → 按脚本源码口径人工核算（§7 D2）：判据 A 两件套齐备（spec.md + handoff.md 均在，C-02 后）；判据 B 以 `git diff --name-only ef838b4` + untracked 核算实际改动集 = 本 handoff §2 清单 13 文件全等。

## 6. 未覆盖、遗留风险与不得推出的结论

- **`verification_result` 无 `recheck_result` 列**：`sql/03-verify-db.sql` 实证判定主表仅 record_id/verdict/score/rule_hits/checked_at 五列，`recheck_result` 只在 appeal 表（`updateAppealAndEvent` 已写）；故终判回写覆盖 verdict + 缓存失效，复核详细结论由 appeal 表承载（§7 D1）。**不得**推出「verification_result 存有复核结论」的结论。
- **`myStats` 日粒度查询形态变化**：窗口投影由 mapper SQL（`created_at >= ?`，白名单外不可改）改为服务层 `selectList` + `apply("COALESCE(start_time, created_at) >= ?")`；全量指标仍 DB 单行聚合，日粒度仍时间窗收窄（非全量拉取），但 `COALESCE` 表达式下界不再命中 `idx_user_time (user_id, created_at)` 纯范围扫描（演示规模可接受，生产索引课题同 TASK-209 settled_at 先例待独立 perf 立项）。既有 TASK-232 下推判别式测试同步改写（`never().selectList` 断言更新为「窗口收窄投影 + 全量聚合恰一次」）。
- **回写与终判非同一本地事务**：`updateAppealAndEvent`（appeal+outbox，VerifyOutboxService @Transactional）提交后，verification_result 回写为独立写；回写异常会让终判请求失败（appeal 已提交，重试将命中「仅 PENDING 可终判」→ 3006/5002 语义），与既有「回调失败后 appeal 已提交」残余窗口同性质，不引入分布式事务（ADR-0009 承续）。
- **Caffeine 失效为单实例本地**：多实例部署各实例缓存独立，本实例失效不能广播其他实例（既有声明接受行为，规范「判定缓存」同款）。
- **榜单 SQL 契约测试只守注解文本**：`settled_at IS NULL` 真实 SQL 行为（库表约束、结算任务集成）未在真实 MySQL 实证（与 TASK-142 outbox SQL 契约测试同口径：纯 JVM 单测守卫契约，真实 SQL 由库表实证待独立立项）。
- **前端待办卡片昵称降级**：`FriendRequestDTO` 契约不含申请人昵称（api 模块不在白名单），卡片展示 fromUser ID，不报错。
- **`--mode=online` / `--static`（Checkstyle/SpotBugs/PMD）CI 全流程未跑**（未 push、未建 PR），外部门禁待指导侧推动后补记。

## 7. 偏差登记

- **D1（requirement 口径 vs 表结构）**：任务书要求终判回写 `verification_result` 的 `verdict` 与 `recheck_result`；实证 `sql/03-verify-db.sql` 该表无 `recheck_result` 列（该列属 appeal 表）。处置：回写 verdict + 失效缓存达成「前端详情与判定查询实时同步」目标；recheck_result 仍落 appeal 表（`updateAppealAndEvent` 已写）。非代码缺陷。
- **D2（bash 不可用，验证命令等价直调 + 契约脚本人工核算）**：`C:\Windows\System32\bash.exe` 为 WSL 入口，其 Ubuntu 发行版 `ext4.vhdx` 缺失（`ERROR_FILE_NOT_FOUND`），且未装 Git Bash → `scripts/verify/mvn-verify.sh` 与 `mailbox-contract.sh` 无法以 bash 执行。处置：后端以脚本同参等价直调 `mvn -B -ntp -o -s .mvn-settings.xml clean test`；外约判据按脚本源码口径（`extract_claims`/判据 A/B 逻辑）以 git 核算。非代码缺陷。
- **D3（行尾规约）**：本机 `core.autocrlf=true`，写入编辑器产生 bare LF，`git add` 时归一 CRLF（warning 属预期）；`git diff --numstat ef838b4` 复核全为行级小 diff（如 SportRecordServiceTest +151/-16、friends.page.vue +53/-45），无整文件重写残迹。
- **D4（e2e 首轮 webServer 超时风险规避）**：沿 TASK-234 D3 先例，Maven 全量与 e2e **串行**执行（CPU 抢占会导致 `pnpm build && pnpm preview` 未在 180s webServer 窗口内起服）；本次 Maven 结束后再跑 build/e2e，`12 passed (59.0s)`。

## 8. 停止条件

白名单内实施与测试改动完成，验证命令全绿（后端全 reactor 830 测 0 失败、record 277 ≥ 275；前端 type-check + 32 测 + build + e2e 12 passed），台账两件套落盘；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需推进 CI push、外约脚本 bash 实跑留证、真实连库验证 `settled_at IS NULL` 与 COALESCE 窗口、前端待办卡申请人昵称补齐、`verification_result` 增列复核结论（schema 变更），须新开任务，不在本任务扩大范围。
