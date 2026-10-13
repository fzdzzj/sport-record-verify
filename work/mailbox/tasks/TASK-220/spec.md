# TASK-220: Streak (P1)

Tier: MEDIUM (standard flow: backend read-only aggregate endpoint + frontend card; zero security-semantics change — ownership posture identical to existing GET /my). Review: guidance-side verification only, CI as external final gate.

## S0 hard constraints
- ZERO changes to: index/state machine/batch size/cycle/MQ parameters (hard repo rule), sql migrations, docker-compose*, ci.yml, mvn-verify.sh, mailbox-contract.sh, scripts/, web/package.json, web/vite.config.ts, typed-router.d.ts (no new routes).
- No new table, no new Maven dependency, no new SQL form (mapper uses MyBatis-Plus selectList single-column projection only).

## S1 verified context
- RecordStatus (api): SUBMITTED → VERIFYING → PASSED / REJECTED; appeal chain REJECTED → APPEALING → RE_PASSED / RE_CONFIRMED; MANUAL_REVIEW terminal. Terminal-PASSED set = { PASSED, RE_PASSED }.
- GET /api/records/my (RecordController:91-101): X-User-Id ownership pattern, Result<Page<RecordSummaryDTO>>, MyBatis-Plus pagination, MAX_MY_RECORDS_SIZE=500.
- SportRecord entity: userId, status, createdAt (LocalDateTime, server timezone).
- Test patterns: SportRecordServiceTest (Mockito mock mapper), RecordControllerTest (mock service + header).
- web index.page.vue: TASK-211 personal dashboard with stat cards (record count / total km / verified count styles to reuse).
- offline baseline: 698 = 52/50/165/166/159/80/26 (record=166). token baseline: 2034.

## S2 preregistered design
- api module: new MyStreakDTO { Integer current; Integer longest; } (@Data, package com.sportverify.api.record.dto) with javadoc stating semantics.
- record-service new StreakCalculator (com.sportverify.record.service, final class + private ctor, static):
  - `public static int[] compute(List<LocalDate> distinctDates, LocalDate today)` -> {current, longest}; input need not be sorted/distinct (method handles both); null/empty -> {0, 0}.
  - Current: if set contains today, count back from today; else if contains yesterday, count back from yesterday (streak alive until day ends); else 0.
  - Longest: max consecutive-day run over the whole set.
- SportRecordService:
  - `public MyStreakDTO myStreak(Long userId)` — sportRecordMapper.selectList(single-column projection: select(SportRecord::getCreatedAt), eq userId, in status {PASSED, RE_PASSED}) → map createdAt.toLocalDate() → distinct → StreakCalculator.compute(dates, LocalDate.now()) → DTO. No pagination (personal volume bounded; single small column).
- RecordController: `GET /my/streak` (mapped BEFORE the `/{id}`-style paths if ambiguity could arise — follow existing /my mapping placement, line ~91) — ownership/Result identical to myRecords; service delegation only.
- Tests:
  - StreakCalculatorTest (pure): empty→{0,0}; single day today→{1,1}; single day yesterday→{1,1}; today missing + yesterday present→current counts from yesterday; gap breaks; unsorted+duplicate input tolerated; longest = historical max across gaps.
  - SportRecordServiceTest addition: myStreak passes status filter {PASSED, RE_PASSED} (captor), maps dates, delegates to calculator (or asserts on real calculator output for fixed dates).
  - RecordControllerTest addition: GET /my/streak with X-User-Id returns Result SUCCESS with service-provided DTO; missing header behaves exactly as existing /my posture (401 path per current interceptor semantics — follow existing test precedent for /my).
- web: client.ts `getMyStreak(): Promise<MyStreakDTO>` (GET /record/api/records/my/streak); index.page.vue dashboard stat card "连续打卡"（当前 N 天 · 最长 M 天）placed with existing stat cards; README one-line registration.

## S2.5 discriminants
- R1: StreakCalculator semantics — 7 scenarios above, exact expected values.
- R2: service layer — status filter is exactly {PASSED, RE_PASSED} (captor-verified), distinct dates fed to calculator, today = LocalDate.now() (server clock).
- R3: controller — Result.success wrapper + ownership posture identical to /my (same header handling; no new auth surface).
- R4: web — streak card renders current/longest; carried by type-check + build + code review (real-browser interaction registered uncovered).
- G1: offline — record module net-add (StreakCalculatorTest + 2 test additions), other six modules per-digit unchanged (expected record 166→~172, exact count = execution reality).
- G2: changed set == exactly S4 whitelist.
- G3: standard MEDIUM gates + web trio (pnpm test 4 suites unchanged count unless web test added — web tests optional here, no new web pure function required; type-check/build/typed-router clean).

## S3 stop conditions
1. Existing /my ownership posture contradicts preregistration (e.g. no header-based ownership) — stop, report.
2. Any required change spills outside whitelist (SQL migration, index, pom) — stop.
3. Test failures reveal calculator semantics ambiguity — stop and report the concrete case, no silent reinterpretation.

## S4 whitelist (target C-01 = exactly 10 files)
api/src/main/java/com/sportverify/api/record/dto/MyStreakDTO.java (new),
record-service/src/main/java/com/sportverify/record/service/StreakCalculator.java (new),
record-service/src/main/java/com/sportverify/record/service/SportRecordService.java,
record-service/src/main/java/com/sportverify/record/controller/RecordController.java,
record-service/src/test/java/com/sportverify/record/service/StreakCalculatorTest.java (new),
record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java,
record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java,
web/src/api/client.ts,
web/src/pages/index.page.vue,
web/README.md.

## S6 gates (MEDIUM tier)
- offline full: record net-add only; common=52/gateway=50/user=165/verify=159/leaderboard=80/mapmatch=26 per-digit unchanged (entry: D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test; rc=3 not PASS).
- web local: pnpm test rc=0, type-check rc=0, build success, typed-router diff clean.
- contract gate dual-state: --open TASK-220 --baseline=<dispatch>; closing no-arg.
- token 29 items 2034 two-point; word-check CI-exact ZERO_HIT on changed set + synthetic probe; git diff --check; new files pure LF + trailing newline, no BOM; changed files preserve baseline endings.
- C-01 feat(record): 连续打卡聚合端点与个人仪表盘卡片（TASK-220）; C-02 docs(mailbox): ... ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = `8853079`（`feat(record): 连续打卡聚合端点与个人仪表盘卡片（TASK-220）`，恰 10 文件 = S4 白名单全等）；C-02 = 本笔 `docs(mailbox): 登记TASK-220连续打卡验收与台账闭环（TASK-220）`，自指哈希沿先例（实际以 `git log` 为准）
- offline / web / token final: offline 逐位 **52/50/165/178/159/80/26**（record 166→178 恰 +12 = StreakCalculatorTest 7 + SportRecordServiceTest 1 + RecordControllerTest 4；六模块逐位不变）BUILD SUCCESS rc=0；web `pnpm test` rc=0（4 套 24 测不变）、type-check rc=0、build 成功（3283 modules）、typed-router diff rc=0；token 29 项两点 2034/2034 只增不减
- discriminant results: R1a/b 绿（MyStreakDTO 有效打卡语义；StreakCalculator 7 场景全过）；R2 绿（status 过滤恰 {PASSED, RE_PASSED}——captor 参数值集精确断言 + createdAt 映射自然日去重 + 输出断言）；R3 绿（/my/streak 归属/Result 包装/错误语义与 /my 全同，controller 四测）；R4 绿（streak 卡渲染当前/最长，type-check+build+代码审查承载，真实浏览器交互未覆盖）；R5 绿；G1 绿（offline 逐位）；G2 绿（改动集恰白名单 10+4 文件）；G3 绿（契约双态 rc=0、token 2034、词面门 ZERO_HIT+探针、diff --check、行尾合规）
- deviations: D1 台账笔自指哈希（沿先例）；D2 词面门/探针/token 模式文件落 gitignored `.trae/tmp` 不入库（命令行零中文）；D3 offline Nacos 连接噪音对计数零影响；D4 web build chunk 体积告警为既有非阻断 advisory（与课题正交）。明细见 handoff.md §8
- 补记（指导侧封盘笔，2026-10-13）：执行回报亲核 PASS（StreakCalculator 全文审读：anchor 语义/longest 断档重置/HashSet 归一；服务层 myStreak 抽查：单列投影 + status 恰 {PASSED, RE_PASSED}）；推送 96e9777..43598e8（含 TASK-219 收口批次 4 笔）；第 59 次 CI（run 38387682776，head=43598e8）conclusion=success，build/web 双 job 全绿；offline 698→710（record 166→178），token 2034 持平；停止条件无一触发。TASK-220 封盘（P1 收官）
