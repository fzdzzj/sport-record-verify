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
- commit hashes: C-01 = TBD; C-02 = self-referencing hash (precedent)
- offline / web / token final: TBD
- discriminant results: TBD
- deviations: TBD
