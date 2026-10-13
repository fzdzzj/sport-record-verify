# TASK-219: Pace analysis (P2)

Tier: MEDIUM (pure frontend + unit tests on TASK-215 vitest baseline; zero backend, zero deps, zero CI). Review: guidance-side verification only, CI as external final gate. Escalation: none expected (pure display of already-authorized data).

## S0 hard constraints
- ZERO changes to: all six backend services, api/common modules, docker-compose*, ci.yml, mvn-verify.sh, mailbox-contract.sh, scripts/, sql, web/package.json, web/vite.config.ts, typed-router.d.ts (no new routes), all other web/src files.
- Whitelist only (S4).

## S1 verified context
- TrackPointDTO (web/src/api/client.ts): { seq, lat, lng, ts, speed?, userId? }; speed unit = m/s (verify-service convention, optional non-negative, client-reported — server recomputes for validation but display uses reported value).
- record-detail.page.vue (TASK-218 state): points ref loaded via getRecordPoints(id, 1, 500); track SVG with hover circles + GPX export button in footer note row; summary header shows distance/status.
- Pace convention: sec-per-km = 1000 / speed(m/s); display "m'ss\"" (e.g. 500 s/km -> 8'20").
- vitest baseline (TASK-218 state): 3 suites / 18 tests.
- offline baseline: 698 = 52/50/165/166/159/80/26. token baseline reading: 2034.

## S2 preregistered design
- web/src/utils/pace.ts (new):
  - `export interface PacePoint { distKm: number; paceSec: number | null; speed: number | null }`
  - `export function computePaceSeries(points: TrackPointDTO[]): PacePoint[]` — haversine (private, R=6371km) cumulative distance from point i-1 to i (first point distKm=0); paceSec = (speed != null && speed > 0) ? 1000/speed : null (pause point); original order preserved; empty input -> [].
  - `export function formatPace(paceSec: number | null): string` — null -> '--'; <60 -> "0'ss\""; else "m'ss\"" with 2-digit seconds; e.g. 500 -> 8'20", 45 -> 0'45".
  - Drawing rule (page-side): paceSec null OR > 3600 (>= 60 min/km) treated as pause -> line break; this clamp lives in page logic but is unit-tested via exported helper `isDrawablePace(paceSec)` in pace.ts.
- web/src/utils/__tests__/pace.test.ts (new): R2a haversine distance on a known short segment (~0.1 km class, assert with tolerance); R2b pace conversion speed=2 -> 500, speed=4 -> 250; R2c speed=0/null -> paceSec null; R2d formatPace boundaries (null -> '--', 45 -> 0'45", 500 -> 8'20", 3659 -> 60'59"); R2e empty -> []; R2f isDrawablePace (null false, 0 false, 600 true, 3601 false).
- record-detail.page.vue: "配速趋势" section below track SVG — line chart X=cumulative km, Y=paceSec INVERTED (faster on top, pace-chart convention), pause points break the polyline (segment splitting), hover circle shows distKm + formatted pace + time (reuse existing hover pattern); section renders only when points.length > 1 and at least one drawable pace exists; axis labels min/max pace + total km.
- web/README.md: register the feature (one short block).

## S2.5 discriminants
- R1: pace.ts pure function semantics — cumulative distance monotonic non-decreasing, first point 0; pace = 1000/speed exactly; zero/null speed -> null; empty -> []; format boundaries correct.
- R2: pace.test.ts all green (vitest).
- R3: page integration — section gating (points>1 + >=1 drawable), Y inverted, pause breaks, hover shows dist+pace+time; verification carried by type-check + build + code review (real-browser interaction registered uncovered, TASK-213/218 posture).
- G1: offline 698 per-digit unchanged (zero backend).
- G2: changed set == exactly S4 whitelist; backend/api/package.json/vite.config/typed-router/other web src zero diff.
- G3: web gates — pnpm test (now 4 suites incl. pace suite), type-check, build, typed-router diff clean; standard MEDIUM gates (contract dual-state, token 2034 two-point, word-check ZERO_HIT + probe, diff --check, line endings).

## S3 stop conditions
1. speed/TrackPointDTO semantics contradict preregistration (e.g. speed not m/s) — stop, report.
2. Any required change spills outside whitelist — stop.
3. vitest/type-check/build red after implementation — diagnose root cause, no blind retries.

## S4 whitelist (target C-01 = exactly 4 files)
web/src/utils/pace.ts (new), web/src/utils/__tests__/pace.test.ts (new), web/src/pages/record-detail.page.vue, web/README.md.

## S6 gates (MEDIUM tier)
- offline full per-digit unchanged: 698 = 52/50/165/166/159/80/26 (entry: D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test; rc=3 not PASS).
- web local: pnpm test rc=0 (TMP redirect), type-check rc=0, build success, typed-router diff clean.
- contract gate dual-state: --open TASK-219 --baseline=<dispatch>; closing no-arg.
- token 29 items 2034 two-point; word-check CI-exact ZERO_HIT on changed set + synthetic probe; git diff --check; new files pure LF + trailing newline, no BOM; changed files preserve baseline endings.
- C-01 feat(web): ... (TASK-219); C-02 docs(mailbox): ... (TASK-219) ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = TBD; C-02 = self-referencing hash (precedent)
- web test results / offline / token final: TBD
- discriminant results: TBD
- deviations: TBD
