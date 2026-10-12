# TASK-210: notification preference UI (settings page, pure frontend)

Tier: MEDIUM (pure frontend, zero core-logic/security-semantics change). Review: guidance audit + CI external only (no independent review subagent).

## S0 hard constraints
- ZERO changes to: all backend (user-service and every other service), api module, gateway, common, parent pom, sql, ci.yml, mvn-verify.sh, mailbox-contract.sh, compose, web pages other than settings.page.vue, web files other than client.ts (+ optional README one line).
- typed-router.d.ts unchanged (no page add/remove).
- No new dependencies; interaction follows existing page paradigms (a-form/a-switch/message from antdvue as used in codebase).

## S1 verified context (backend contract, zero-change)
- NotificationController (user-service/controller/NotificationController.java:120-143): GET /api/notifications/preferences -> Result<List<NotificationPreferenceView>> (type/enabled/updatedAt nullable; missing rows default-on; resolveUserId ADR-0007); PUT same path, body UpdateNotificationPreferenceRequest {preferences: [{type, enabled}]} batch upsert -> returns updated view list.
- Preference surface types (backend DTO doc): RECORD_VERIFIED / RECORD_REJECTED / FRIEND_ACCEPTED. Frontend MUST render dynamically from GET response, never hardcode the type list.
- settings.page.vue exists (TASK-208): max-w-md card, a-form vertical, script setup TS, imports from @/api/client.

## S2 preregistered design
- client.ts: getNotificationPreferences(): GET notifications/preferences path per existing client base/route conventions (align with existing notification calls in client.ts); updateNotificationPreferences(preferences: {type,enabled}[]): PUT same path with {preferences}.
- settings.page.vue: new section below password form (same card or sibling card per page layout taste) - load on mounted via GET; render one a-switch row per returned view (label = type, humanized if desired); on toggle -> PUT [{type, enabled}] -> success message + replace local state with response; load failure -> non-fatal hint, empty section.
- README (optional single line): settings page now exposes notification preferences.

## S2.5 red/green discriminants
- R1: client functions hit the verified contract paths/methods/body shape (code review evidence).
- R2: page renders switch list from GET response dynamically (no hardcoded type array in source); toggle issues PUT single-item batch; success feedback + state refresh from response.
- G1: vue-tsc rc=0 (or CI web job authoritative per TASK-205/208/209 precedent).
- G2: backend diff 44edabc..HEAD (or dispatch..HEAD range adjusted to this task's baseline) empty for all backend paths - audit evidence.

## S3 stop conditions
1. Any backend change appears needed (contract mismatch) - stop and report.
2. Existing client/page paradigms conflict (e.g. no notification api precedent) - report before inventing new conventions.
3. vue-tsc type errors not attributable to the two whitelisted files.

## S4 whitelist
web/src/pages/settings.page.vue, web/src/api/client.ts, README.md (optional). Target C-01 = 2-3 files.

## S6 gates (MEDIUM tier)
- offline full per-digit unchanged 675=52/50/162/157/159/70->80: i.e. 52/50/162/157/159/80/15 all per-digit identical (entry: bash scripts/verify/mvn-verify.sh --mode=offline test via D:\git\Git\bin\bash.exe; rc=3 not PASS).
- contract gate dual-state: --open TASK-210 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 (two-point: post-C01 + close; no token literals in new docs).
- word-check CI-exact (-n -I -iE explicit -E) on changed set ZERO_HIT; probe positive control (batch reuse).
- git diff --check; line endings per-file baseline; modified files keep their existing endings.
- web: local build if env permits else vue-tsc rc=0 + CI web job authoritative (precedent).
- C-01 feat(web): ... (TASK-210) 2-3 files; C-02 docs(mailbox): ... (TASK-210) ledger 4 files (tasks.json/spec.md S7/handoff.md/PLAN.md pure-append). No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = ___; C-02 = ___
- offline final / token final: ___ (expect unchanged 675 / 2030)
- discriminant results R1/R2, G1/G2: ___
- deviations: ___

