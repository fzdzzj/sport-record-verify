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
- commit hashes: C-01 = ___; C-02 = ___
- offline / token final: ___
- discriminant results: ___
- deviations: ___

