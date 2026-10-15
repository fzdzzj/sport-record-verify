# TASK-235: 核心链路缺陷修复与社交闭环——榜单结算时间窗口矫正、申诉判定回写、打卡时间对齐与待审申请一键处理（MEDIUM 档）

> **开工基线**：ef838b4（工作树干净）
> **任务级别**：MEDIUM（核心业务链路缺陷修复与社交闭环：合理性、边界条件、数据一致性）

## 1. 目标与背景

- **背景**：主链路四处「缺陷/断环」：
  1. **榜单结算时间窗口失真（根治今日/周/月榜单）**：`LeaderboardContributionMapper.markSettled` 按 `status=ACTIVE` 批量盖 `settled_at`，重复结算会**覆盖既有历史结算时间**——历史行被反复盖戳后按 `settled_at` 查询的「今日/本周/本月」窗口（`selectRangeSummaries`）把历史行卷进错误周期，榜单长期失真；
  2. **申诉终判不写回判定主表**：`VerifyService.reviewAppeal` 终审只动 appeal 表与 record 状态回调，`verification_result` 仍留初判旧值且本地 Caffeine 缓存可能命中旧结果——前端详情与判定查询接口（`getVerificationResult`）长时间不同步；
  3. **打卡/每日统计时间口径错位**：`myStreak` 自然日归集与 `myStats` 每日里程窗口均按 `createdAt`（上传时间）断签——离线补传/跨零点上传记录被误判到错误自然日；
  4. **好友待办无线上闭环**：前端好友页只有「输入 requestId 手工敲数字」的处理方式，无「收到的好友申请」列表与一键同意/拒绝。
- **目标**：
  1. `markSettled` 仅对 `settled_at IS NULL` 的新增贡献行盖戳，绝不覆盖历史结算时间，恢复今日/周/月窗口自洽；
  2. 终判改判通过（RE_PASSED）或维持原判（RE_CONFIRMED）时显式回写 `verification_result.verdict` 并主动失效本地缓存，判定查询接口实时同步；
  3. `myStreak`/`myStats` 统一按 `startTime` 归自然日（null 回退 `createdAt`），与 TASK-234 目标服务 `COALESCE(start_time, created_at)` 口径一致；
  4. user-service 新增 `GET /api/friends/requests`（当前用户收到的 PENDING(0) 申请列表），web 好友页新增「收到的好友申请」卡片支持一键同意/拒绝。

## 2. 修改白名单

严格限定在以下 13 个文件内：

- 后端服务（src/main，共 5 文件）：
  - `leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java`
  - `verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java`
  - `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`
  - `user-service/src/main/java/com/sportverify/user/controller/FriendController.java`
  - `user-service/src/main/java/com/sportverify/user/service/FriendService.java`
- 后端单测（共 4 文件，其中 FriendControllerTest 为新建）：
  - `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
  - `verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java`
  - `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`
  - `user-service/src/test/java/com/sportverify/user/controller/FriendControllerTest.java`
- 前端（共 2 文件）：
  - `web/src/api/client.ts`
  - `web/src/pages/friends.page.vue`
- 台账两件套：
  - `work/mailbox/tasks/TASK-235/spec.md`
  - `work/mailbox/tasks/TASK-235/handoff.md`

> 红线：`api/`、`SportRecordMapper.java`、`VerificationResultMapper.java`、`VerificationResult.java`、pom、`package.json`、`playwright.config.ts`、`work/mailbox/PLAN.md` / `work/mailbox/HANDOVER.md` 及白名单外一切文件零触碰；零新依赖。

## 3. 核心设计与红线要求

### 3.1 榜单结算时间窗口矫正（LeaderboardContributionMapper.markSettled）

- `markSettled` SQL 追加 `AND settled_at IS NULL`：仅对尚未结算的 ACTIVE 贡献行盖戳，绝不覆盖既有历史结算时间；
- 判别式：反射读取 `@Update` 注解 SQL 文本，断言含 `settled_at IS NULL`（SQL 契约测试，先例：`VerifyEventOutboxMapperSqlContractTest`）。

### 3.2 申诉终判判定主表回写闭环（VerifyService.reviewAppeal）

- 终判（pass=true → RE_PASSED / pass=false → RE_CONFIRMED）且 appeal 乐观更新成功（rows>0）后：
  - 显式回写 `verification_result.verdict`（`UpdateWrapper` 按 `record_id` 主键定位，`set("verdict", PASSED/REJECTED)`）——判定主表不再留初判旧值；
  - 主动失效本地 Caffeine 缓存 `RESULT_CACHE_PREFIX + recordId`（`invalidate`）——缓存不再命中旧判定；
- **表结构实证与取舍**：`sql/03-verify-db.sql` 实证 `verification_result` 表**无 `recheck_result` 列**（该列只在 `appeal` 表，`updateAppealAndEvent` 已写），故回写覆盖 verdict；复核详细结论仍由 appeal 表承载（登记为 D1 偏差，见 handoff §7）。

### 3.3 打卡/每日统计时间口径对齐（SportRecordService.myStreak / myStats）

- `myStreak`：单列投影改为 `(start_time, created_at)`，自然日归集 `startTime != null ? startTime : createdAt`（双 null 防御性跳过）→ 离线补传/跨零点上传按运动开始时间断签；
- `myStats`：日粒度窗口由 mapper SQL（`created_at >= ?`，白名单外）改为服务层 `selectList` + `apply("COALESCE(start_time, created_at) >= {0}", windowStart)`，按 `COALESCE(start_time, created_at)` 落入自然日汇总——窗口下界与日键与 TASK-234 目标服务 `COALESCE` 口径统一；全量指标仍由 `selectAggByUserId` DB 单行聚合承担（下推保持），日粒度仍时间窗收窄（非全量拉取）。

### 3.4 好友待办一键处理闭环（FriendController / FriendService / client.ts / friends.page.vue）

- `FriendService.listPendingRequests(userId, page, size)`：`to_user = 本人 AND status = PENDING(0)`，`createdAt` 倒序分页；
- `FriendController GET /api/friends/requests`（可选 `userId` 参数，身份认定同 listFriends 两态）；
- `client.ts` 新增 `listFriendRequests(page, size)` 契约方法；
- `friends.page.vue`：「处理申请 (输入 request id)」手工卡片**移除**，新增「收到的好友申请」卡片（刷新/表格/一键同意/拒绝，操作后自动刷新申请与好友列表）——终结线下报 requestId 的荒诞交互。

### 3.5 红线要求

- 后端 Maven 单测 275+ 测全绿；前端 `type-check` / `pnpm test` / `pnpm build` / `pnpm e2e` 12 passed 全绿。
- 台账两件套按规范落入 `work/mailbox/tasks/TASK-235/`，按 C-01（实施）与 C-02（台账）分别提交。

## 4. 验证命令

- 后端单测：`bash scripts/verify/mvn-verify.sh --mode=offline test`（本机 bash 不可用时的等价直调：`mvn -B -ntp -o -s .mvn-settings.xml clean test`，见 handoff §7 D2）
- 前端单测与构建：`pnpm --dir web type-check && pnpm --dir web test && pnpm --dir web build`（Windows 下 `TMP/TEMP` 重定向至仓内 `.trae/tmp`，沿 TASK-224/231/232/233 同款处置）
- 前端 E2E：`pnpm --dir web e2e`（同上 TMP/TEMP 重定向）
- 契约门禁：`bash scripts/verify/mailbox-contract.sh --open=TASK-235 --baseline=ef838b4`（判据 A/B 判读见 handoff §7）

---

### 回填（2026-10-15，C-02 台账笔）

**判别式红绿**

- R1 榜单结算时间窗口：**绿**。`markSettled` 的 `@Update` SQL 反射断言含 `settled_at IS NULL`（且 `SET settled_at`、`WHERE status` 保留）——只盖未结算行、绝不覆盖历史结算时间。
- R2 申诉判定回写闭环：**绿**。终判改判通过 → `verificationResultMapper.update(null, UpdateWrapper)` 回写 `verdict=PASSED(1)` + `caffeineCache.invalidate("verify:result:5")`；维持原判 → 回写 `verdict=REJECTED(2)` + 缓存失效；乐观锁冲突（0 行）→ 不回写、不失效缓存、不排事件行。
- R3 打卡/统计时间口径对齐：**绿**。`myStreak` 跨零点（活动 23:50 昨日、上传 00:10 今日）与离线补传（活动前日、上传今日）均按 `startTime` 归自然日（`current=4/longest=4`）；`myStats` 日粒度窗口 `COALESCE(start_time, created_at) >= 今日-29 00:00`（captor 断言 SQL 片段含 COALESCE/start_time/created_at 与下界参数），跨零点归昨日、补传归前日、startTime null 回退 createdAt；全量指标仍由 `selectAggByUserId` 恰一次承担。
- R4 好友待办闭环：**绿**。`GET /api/friends/requests` 判别式：`to_user=本人 AND status=PENDING(0)`、`createdAt` 倒序（wrapper captor 断言）；auth 两态（降级显式携带 / X-User-Id 为准、不一致 403、缺头 401）；`friends.page.vue` 新增待办卡片一键同意/拒绝，type-check 0 错误。
- R5 红线：**绿**。白名单外零 diff（`git diff --name-only ef838b4` + untracked 恰 13 文件）、零新依赖。

**门禁留证**

- 后端：`mvn -B -ntp -o -s .mvn-settings.xml clean test`（全 reactor 9 模块）→ **BUILD SUCCESS**：模块级 `Tests run`：common 52 / gateway 50 / user 178 / record 277 / verify 161 / leaderboard 86 / mapmatch 26，**Failures: 0, Errors: 0**（record 275 → 277 = +2：SportRecordServiceTest；user 173 → 178 = +5：FriendControllerTest 新建；verify 159 → 161 = +2；leaderboard 85 → 86 = +1）。输出落 `.trae/tmp/mvn-verify-235-final.log`。
- 前端 type-check：`pnpm --dir web type-check` → rc=0（vue-tsc 0 错误）。
- 前端单测：`pnpm --dir web test` → rc=0：`Test Files 4 passed (4)` / `Tests 32 passed (32)`。
- 前端构建：`pnpm --dir web build`（TMP/TEMP 重定向 `.trae/tmp`）→ rc=0：`✓ built in 41.12s`（chunk >500 kB 为既有非阻断 advisory）。
- 前端 E2E 全量：`pnpm --dir web e2e`（同 TMP/TEMP 重定向）→ **`12 passed (59.0s)`，rc=0**。
