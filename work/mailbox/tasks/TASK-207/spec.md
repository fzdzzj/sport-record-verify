# TASK-207: notification_preference scratch env sync + V1c-b reverification

Tier: MEDIUM (env-only sync + reverification, zero production code). Lead: guiding agent. Executor: single execution sub-agent.

## S0 hard constraints
- Zero production code / sql scripts / CI / compose / pom changes. Whitelist = 4 ledger files only (tasks.json / spec.md S7 / handoff.md new / PLAN.md append-only). No implementation commit.
- Shared middleware containers must not be stopped/restarted/modified. Scratch user_db IS the target of the idempotent replay (authorized scope: pipe the original 01-user-db.sql verbatim into the DB; no hand-crafted DDL variants).
- MySQL credentials: use the running container's actual credentials (per TASK-206 D4, runtime env overrides .env placeholder). Read from container env; do not write credentials anywhere.
- No docker-compose.override; temp files in .trae/tmp, cleaned after.

## S1 verified context
- CREATE TABLE IF NOT EXISTS notification_preference exists at sql/01-user-db.sql:69 (with user/notification/friend_request/friendship all IF NOT EXISTS).
- TASK-206 V1c-b: B accept -> ownership assertion (caller==toUser) passed, then 500 on missing notification_preference. Recorded as D2 env gap, not a product defect.
- TASK-206 scratch residue: users A/B, friend request, record 167365 are expected scratch side effects and may be reused; never touch dev-db data.

## S2 preregistered verification matrix
- V1a: pre-replay archive (SHOW TABLES; SHOW CREATE TABLE for each existing table).
- V1b: replay 01-user-db.sql verbatim (docker exec mysql container or mysql client; rc=0).
- V1c: post-replay evidence: notification_preference exists; per-table SHOW CREATE TABLE identical to archive for ALL pre-existing tables (zero drift proof).
- V2a: start gateway+user jars (scratch db + running middleware; --server.port if conflict; auth enabled per TASK-206 runtime-injection pattern).
- V2b: reverify V1c-b: B accept own received friend request -> HTTP 200 (reuse TASK-206 residue or construct fresh users/request).
- V2c: stop services with evidence (jps/tasklist, ports freed).

## S3 stop conditions (report, do not fix)
1. Replay fails (syntax/permission).
2. Any pre-existing table shows structural drift after replay (env damage signal).
3. V2b still 500 after table exists (upgrade to lead: more than a missing table).
4. Any production code/config/compose/CI change appears needed.
5. Shared container anomaly (restart/exit/health flip).

## S4 whitelist (exactly 4 ledger files, single ledger commit)
spec/changes/sync-notification-preference-env/tasks.json; work/mailbox/tasks/TASK-207/spec.md (S7); work/mailbox/tasks/TASK-207/handoff.md (new); work/mailbox/PLAN.md (append-only).

## S6 gates (MEDIUM tier)
- offline full per-digit unchanged 655=52/50/152/157/159/70/15 (only entry: bash scripts/verify/mvn-verify.sh --mode=offline test via D:\git\Git\bin\bash.exe; rc=3 not PASS; stop services BEFORE run to avoid jar-lock).
- contract gate dual-state: --open TASK-207 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 (two-point: post-ledger + close; no token literals in new docs).
- word-check CI-exact (-n -I -iE with explicit -E) ZERO_HIT on new ledger files; probe reuse from TASK-205/206 batch.
- git diff --check; new files pure LF + trailing newline; production code/config/CI/compose diff EMPTY.
- env cleanup evidence: services stopped, .trae/tmp empty, no override, porcelain empty.

## S7 backfill (after ledger commit)
- ledger commit hash: ___ (self-referential, D1 precedent) ___
- V1a/V1b/V1c/V2b results: ___
- offline/token final: ___
- TASK-206 D2 flip: closed with evidence.

