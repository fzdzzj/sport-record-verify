# TASK-213: track view page (SVG projection)

Tier: MEDIUM (pure frontend, zero backend changes). Review: guidance-side verification only, CI as external final gate. Escalation: assertion failure or contradiction -> FULL.

## S0 hard constraints
- ZERO changes to: all six backend services, api module, common, parent pom, sql, ci.yml, mvn-verify.sh, mailbox-contract.sh, compose, web/package.json (zero new deps - map libs deferred by design decision).
- web whitelist: record-detail.page.vue (new), index.page.vue (entry links), client.ts (points call if absent), typed-router.d.ts (regen, tracked, must commit - engineering convention), README.md (one section).

## S1 verified context
- GET /api/records/{id}/points -> Result<Page<TrackPointDTO>>; ownership: caller != record.userId -> 403 code 1002 (TASK-204); page/size default 20, no explicit cap server-side (capped client-side at 500).
- TrackPointDTO fields: id/recordId/userId/seq/lat/lng/ts/speed.
- Executor self-check: verdict.page.vue query-param pattern for record id (align route convention), whether client.ts already has a points call, index recent-records row structure.

## S2 preregistered design
- record-detail.page.vue: reads recordId from route query (align verdict page convention). Summary header from GET /{id}/verify-result (existing endpoint) or minimal info from index navigation state; on 403 -> permission-denied error state (server-side ownership is the gate; frontend only renders).
- Track rendering: fetch points size=500 single call; project lat/lng via min-max normalization into SVG viewBox (fixed aspect, padding), polyline + start marker (green) + end marker (red) + per-point circle with title tooltip (seq/speed/ts). Empty -> empty state. Single point -> marker only.
- Entry: index recent-records rows add view-track link (router push with query); verdict page link optional (executor judgment, keep minimal).
- Known limitation: >500 points partial track (registered, same convention as stats).

## S2.5 discriminants
- R1: SVG projection correctness (normalization math, degenerate cases: 1 point, identical coords).
- R2: ownership chain: 403 from server renders error state (no bypass in frontend; no local userId check - server is authoritative).
- R3: pagination: size=500 single fetch; partial-track limitation rendered/disregistered.
- G1: vue-tsc rc=0.
- G2: package.json zero diff; backend diff empty; typed-router contains new route.

## S3 stop conditions
1. Change spills outside S0 whitelist.
2. Server-side change appears needed (e.g. points cap, new endpoint) - stop and report.
3. Route convention conflict requires infra changes.

## S4 whitelist (target C-01 = 4-5 files)
record-detail.page.vue (new), index.page.vue, client.ts, typed-router.d.ts, README.md.

## S6 gates (MEDIUM tier)
- offline full per-digit unchanged: 684=52/50/162/166/159/80/15 (entry: bash scripts/verify/mvn-verify.sh --mode=offline test via D:\git\Git\bin\bash.exe; rc=3 not PASS).
- contract gate dual-state: --open TASK-213 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 two-point; word-check CI-exact ZERO_HIT + probe (batch reuse); git diff --check; line endings per-file baseline; new files pure LF + trailing newline.
- web: local build if env permits else vue-tsc rc=0 + CI web job authoritative; package.json zero-diff as evidence.
- C-01 feat(web): ... (TASK-213); C-02 docs(mailbox): ... (TASK-213) ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = 7e4be36; C-02 = 本笔自指哈希（沿 TASK-178/183/204/205/206/207/208/209/210/211 先例，见 git log --oneline）
- offline / token final: offline 逐位 684 = 52/50/162/166/159/80/15（BUILD SUCCESS rc=0，纯前端七模块计数维持基线）；token 29 项 post-C01 2030 / close 2030（只增不减）
- discriminant results: R1 绿（min-max 归一化投影 + 单点/同坐标退化取 0.5 落中，代码审查留证）；R2 绿（403/1002 → 权限错误态，前端零绕过、零本地 userId 判断，代码审查留证）；R3 绿（getRecordPoints(id,1,500) 单次拉取，>500 点部分轨迹已渲染已知限制标注）；G1 绿（vue-tsc --noEmit rc=0，pnpm build 本机成功）；G2 绿（package.json 零 diff、后端 diff 全空、typed-router.d.ts 含新路由）
- deviations: D1 台账笔自指哈希（见上）；D2 client.ts 既有 getRecordPoints 满足 size 参数需求，未新增/改动（C-01 落 4 文件）；D3 本机 web build 成功（无 esbuild 阻断），typed-router.d.ts 由构建再生成 13 行；D4 台账笔 tasks.json 重写时补回末尾换行避免 "No newline at end of file"

