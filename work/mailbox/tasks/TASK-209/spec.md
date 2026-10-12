# TASK-209: range-aggregated leaderboard (weekly/monthly views)

Tier: FULL (core query logic). Review: 4-block regime.

## S0 hard constraints
- ZERO changes to: write path (applyVerified/revert/compensation), settlement task, ZSet keys, indexes (no idx_settled_at - globally red-lined; registered as known limitation), sql scripts (no new table/view), api module (reuse LeaderboardDTO), gateway/common/user/record/verify/mapmatch services, parent pom, ci.yml, mvn-verify.sh, mailbox-contract.sh, compose.
- web whitelist: pages/leaderboard.page.vue, api client file only.
- No performance/latency claims in handoff or ledger.

## S1 verified context
- LeaderboardController: GET / (type=overall/friend, resolveUserId ADR-0007), GET /daily (public aggregate, no identity); local BizException handler maps 1001->401/1002->403, other codes HTTP200+body.
- LeaderboardService: dailyReport(date, topN) reads leaderboard_daily_summary (CUMULATIVE-to-date snapshot, not daily delta - MV comment line 2) and assembles LeaderboardDTO(rank from 1, nickname resolveNicknames fallback 'user'+id). top caps: DEFAULT_TOP_N, DAILY_REPORT_MAX_SIZE=500.
- leaderboard_contribution (record_db, sql/02-record-db.sql:49): record_id PK, user_id, distance, status TINYINT, settled_at DATETIME, KEY idx_user only. Authority source for settlement reconciliation.

## S2 preregistered design
- GET /api/leaderboard/range?start=YYYY-MM-DD&end=YYYY-MM-DD&size=50 (public aggregate like /daily - no resolveUserId).
- Mapper query (existing mapper paradigm - annotation or xml per current codebase): SELECT user_id, SUM(distance) total FROM leaderboard_contribution WHERE status = ACTIVE AND settled_at >= start.atStartOfDay() AND settled_at < end.plusDays(1).atStartOfDay() GROUP BY user_id ORDER BY total DESC LIMIT size. Status constant per existing ACTIVE semantics in the mapper/service.
- Service rangeReport(start, end, topN): cap 500, default 50; empty -> List.of(); assemble rank/nickname per dailyReport paradigm. javadoc: settled_at attribution doctrine (contribution counted in the period when settled, not when record created).
- Controller validation: start required (@RequestParam + explicit check), end optional default LocalDate.now(), start>end -> BizException 400 PARAM, span > 366 days -> 400 PARAM, size cap like daily.
- README leaderboard section: range semantics + natural-period composition (week = ISO Monday, month = 1st) + in-progress period up to today.

## S2.5 red/green discriminants
- R1a: multi-user multi-row ACTIVE contributions in window -> SUM desc, rank from 1, nicknames resolved (fallback user+id).
- R1b: window boundary half-open: settled_at on start-day 00:00+ counted; settled_at on end+1 day excluded.
- R1c: non-ACTIVE rows excluded from SUM.
- R1d: start>end -> 400; span>366 -> 400; start missing -> 400 (Spring param or explicit).
- R1e: size<=0 -> default 50; size>500 -> capped 500.
- G1: existing top/daily/friend/settlement tests zero regression.
- G2: write-path/settlement/ZSet/index/sql diff all empty (audit).

## S3 stop conditions
1. Required change spills outside S0 whitelist (esp. write path, index, sql).
2. Existing mapper/query paradigm conflicts require convention changes.
3. Checkstyle/SpotBugs violations non-trivially attributable to new code.
4. offline baseline drops or unrelated modules change per-digit.

## S4 whitelist (approx; executor finalizes within bounds, lead audits)
leaderboard-service: LeaderboardController.java, LeaderboardService.java, contribution mapper file (new query method), LeaderboardServiceTest.java, LeaderboardControllerTest.java; web: pages/leaderboard.page.vue, api client file; README.md. Target C-01 = 7-9 files.

## S6 gates (FULL tier, 4-block review)
- offline full per-digit: baseline 665=52/50/162/157/159/70/15, leaderboard net-increase only (70 grows), other six modules per-digit unchanged (entry: bash scripts/verify/mvn-verify.sh --mode=offline test via D:\git\Git\bin\bash.exe; rc=3 not PASS).
- contract gate dual-state: --open TASK-209 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 (two-point: post-C01 + close; no token literals in new docs).
- word-check CI-exact (-n -I -iE explicit -E) on changed file set ZERO_HIT; probe positive control (batch reuse).
- git diff --check; line endings per-file baseline; new files pure LF + trailing newline; no BOM.
- web: local build if env permits else vue-tsc rc=0 + CI web job authoritative (TASK-205/208 precedent).
- C-01 feat(leaderboard): ... (TASK-209); C-02 docs(mailbox): ... (TASK-209) ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = ___; C-02 = ___
- offline final / token final: ___
- discriminant results R1a-R1e, G1-G2: ___
- deviations: ___

