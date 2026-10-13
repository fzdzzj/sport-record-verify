# TASK-218: GPX export (E13)

Tier: MEDIUM (pure frontend + unit tests on TASK-215 vitest baseline; zero backend, zero deps, zero CI). Review: guidance-side verification only, CI as external final gate. Escalation: none expected (no security semantics; data already authorized client-side via TASK-204 ownership gate on the points endpoint).

## S0 hard constraints
- ZERO changes to: all six backend services (src/main/resources/pom), api/common modules, docker-compose*, ci.yml, mvn-verify.sh, mailbox-contract.sh, scripts/, sql, web/package.json (no deps — vitest/happy-dom already present), web/vite.config.ts, typed-router.d.ts (no new routes).
- Whitelist only (S4).

## S1 verified context
- record-detail.page.vue (TASK-213): loads points via getRecordPoints(id, 1, 500) into `points` ref<TrackPointDTO[]>; page has loading/error/empty/perm states; footer note block at points.length.
- TrackPointDTO (web/src/api/client.ts:11-19): { seq: number; lat: number; lng: number; ts: number; speed?: number; userId?: number } — no elevation (GPX `<ele>` omitted; legal).
- GPX 1.1 essentials: `<gpx version="1.1" creator="..." xmlns="http://www.topografix.com/GPX/1/1">`, metadata/name+time, trk/trkseg/trkpt (lat/lon required attrs, `<time>` ISO8601).
- vitest baseline (TASK-215): 2 suites / 13 tests; `pnpm test` = `vitest run`; happy-dom env; TMP redirect to .trae/tmp needed on this machine.
- offline baseline: 698 = 52/50/165/166/159/80/26. token baseline reading: 2034.

## S2 preregistered design
- web/src/utils/gpx.ts (new):
  - `export function generateGpx(points: TrackPointDTO[], opts?: { name?: string; creator?: string }): string`
  - Header: `<?xml version="1.0" encoding="UTF-8"?>` + `<gpx version="1.1" creator="{creator|sport-verify-web}" xmlns="http://www.topografix.com/GPX/1/1" ...>` + metadata (name default `record-track`, time = first point ts ISO8601).
  - Body: one trk (name) with one trkseg; one trkpt per point in original order: lat/lon attrs fixed to 6 decimals; `<time>` = new Date(ts).toISOString().
  - XML escaping helper for text nodes/attr values (& < > " '); empty points array throws Error (page button gated on points.length > 0 anyway — defensive).
- web/src/utils/__tests__/gpx.test.ts (new): R2a single point exactly 1 trkpt with lat/lon; R2b multi-point count & order; R2c time ISO8601 from epoch; R2d name XML escaping (& < >); R2e empty array throws.
- record-detail.page.vue: "导出 GPX" a-button (v-if points.length, placed in footer note row or toolbar); handler: generateGpx(points, { name: `record-${recordId}` }) -> new Blob([xml], { type: 'application/gpx+xml' }) -> URL.createObjectURL -> programmatic a[download=`record-${recordId}.gpx`] click -> URL.revokeObjectURL.
- web/README.md: register the feature in the appropriate section (one short block).

## S2.5 discriminants
- R1: gpx.ts pure function semantics — GPX 1.1 structure complete (gpx/metadata/trk/trkseg/trkpt), trkpt count == points count, lat/lon 6-decimal precision, time ISO8601, name escaped, empty throws.
- R2: gpx.test.ts all green (vitest).
- R3: page integration — button rendered only when points.length > 0; download filename `record-{id}.gpx`; blob type application/gpx+xml; verification carried by type-check + build + code review (real-browser click-through registered uncovered, TASK-213 posture).
- G1: offline 698 per-digit unchanged (zero backend).
- G2: changed set == exactly S4 whitelist; backend/api/package.json/vite.config/typed-router zero diff.
- G3: web gates — pnpm test (now 3 suites incl. gpx suite), type-check, build, typed-router diff clean; standard MEDIUM gates (contract dual-state, token 2034 two-point, word-check ZERO_HIT + probe, diff --check, line endings: new files pure LF + trailing newline, page preserves baseline).

## S3 stop conditions
1. TrackPointDTO/gpx semantics contradict preregistration (e.g. ts not epoch ms) — stop, report, no silent adaptation.
2. Any required change spills outside whitelist (package.json/vite.config/routes) — stop.
3. vitest/type-check/build red after implementation — diagnose root cause, no blind retries.

## S4 whitelist (target C-01 = exactly 4 files)
web/src/utils/gpx.ts (new), web/src/utils/__tests__/gpx.test.ts (new), web/src/pages/record-detail.page.vue, web/README.md.

## S6 gates (MEDIUM tier)
- offline full per-digit unchanged: 698 = 52/50/165/166/159/80/26 (entry: D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test; rc=3 not PASS).
- web local: pnpm test rc=0 (TMP redirect), type-check rc=0, build success, typed-router diff clean.
- contract gate dual-state: --open TASK-218 --baseline=<dispatch>; closing no-arg.
- token 29 items 2034 two-point; word-check CI-exact ZERO_HIT on changed set + synthetic probe; git diff --check; new files pure LF + trailing newline, no BOM; changed files preserve baseline endings.
- C-01 feat(web): ... (TASK-218); C-02 docs(mailbox): ... (TASK-218) ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = 70d9679; C-02 = self-referencing hash (沿 TASK-178/183/204/205/213/214/215/216/217 先例，以 git log 为准)
- web test results / offline / token final: offline 698=52/50/165/166/159/80/26 逐位不变 rc=0；web pnpm test rc=0（3 套 18 测：token 6 + gpx 5 + client 7）、type-check rc=0、build 成功（3283 modules）、typed-router diff 干净；token 29 项两点 2034/2034 只增不减
- discriminant results: R1 绿（GPX 1.1 完整结构/trkpt 计数与序/lat-lon 6 位/time ISO8601/name 转义/空抛错）；R2 绿（vitest 5 测全过）；R3 绿（按钮 v-if points.length、a[download=record-{id}.gpx]、blob application/gpx+xml，type-check+build+代码审查承载，真实浏览器点击未覆盖）；G1 绿（offline 698 逐位）；G2 绿（改动集恰白名单 8 文件）；G3 绿（契约双态 rc=0、token 2034、词面门 ZERO_HIT+探针、diff --check、行尾合规）
- deviations: D1 C-02 自指哈希（沿先例）；D2 词面门/探针模式文件落 gitignored .trae/tmp 不入库；D3 token 29 项字面量不枚举入新文档（沿 TASK-188 N1 教训）

- 补记（指导侧封盘笔，2026-10-13）：推送 1cb4383..2e5cbf3（4 笔=TASK-217 封盘笔+本课题派发/C-01/C-02）后第 57 次 CI（run 38371556173，head=2e5cbf3）conclusion=success 全 job 绿——gpx 新套件在 CI web job 真实通过（3 套 18 测）。MEDIUM 档三环节终裁全过（指导亲核 PASS：gpx.ts 全文审读转义/结构/精度全对、页面 diff 零夹带；回传零矛盾），TASK-218 封盘（E13 收官）。
