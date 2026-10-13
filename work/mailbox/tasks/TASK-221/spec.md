# TASK-221: Record Visibility Three-State (P3)

Tier: FULL (security semantics: per-record read-path authorization + schema + cross-service friend check; independent review subagent applies). Review: guidance-side spot check + independent review five-block rerun + CI as external final gate.

User adjudications (2026-10-13, AskUserQuestion): leaderboard credit stays orthogonal to visibility (no leaderboard changes in this topic; linkage deferred to its own future topic). No post-hoc visibility PATCH endpoint (deferred; record editing absent site-wide, E14-class).

## S0 hard constraints
- ZERO changes to: state machine / batch size / cycle / MQ parameters / RecordEvent DTO (hard repo rule), leaderboard-service (all), ci.yml, docker-compose*, mvn-verify.sh, mailbox-contract.sh, scripts/, gateway-service, web/package.json, web/vite.config.ts, typed-router.d.ts (no new routes), existing sql/migrations/* files (new file only), sql/01,03,04,05.
- No new index anywhere (visibility gate is PK-lookup + in-memory; idx red line untouched).
- No new table (column add on existing sport_record), no new Maven dependency, no new SQL form beyond MyBatis-Plus wrappers.
- sql/02-record-db.sql: only the `visibility` column line added to `sport_record` CREATE TABLE (dual-write convention, migrations README + TASK-108 precedent); all other DDL byte-identical.

## S1 verified context (guidance-side recon, 2026-10-13)
- sql/02-record-db.sql:13-30 — sport_record 13 cols, no visibility; uk_request_id, idx_user_time, idx_status_created.
- sql/migrations/ — descriptive naming (add-*.sql), picked up by scripts/db/migrate.sh in filename order, idempotent, never mounted into initdb.d; add-user-role.sql:15-21 = idempotent pattern (information_schema COUNT + PREPARE/EXECUTE, MySQL 8.0 lacks ADD COLUMN IF NOT EXISTS); README "01~04 already contain all columns required by current code" = new-install dual-write convention.
- RecordController (/api/records): submit POST /; GET /{id}/verify-result (NO caller check today — anyone can read any verdict; THE open exposure this topic closes); GET /my, GET /my/streak (ownership-as-identity); POST /{id}/appeal (caller==owner, TASK-204); GET /{id}/points (caller==owner else 403, TASK-204). resolveUserId: true → X-User-Id (missing→1001, mismatch→1002), false → claimed value (degrade, caller=null downstream).
- LikeController /api/records/{id}/like POST/DELETE/GET — all three pass resolveUserId result into service as the acting user; RecordLikeService gates on PASSED/RE_PASSED only (no visibility premise today), Redis-set idempotency.
- UserApi (api module, @FeignClient user-service /internal, fallbackFactory UserApiFallback): health + friend-domain 4 endpoints + listUsersByIds; listFriends is paginated (too heavy for a pairwise check) — no pairwise exists-friends contract yet.
- user-service: Friendship normalized storage user_low<user_high (FriendConcurrencyTest:191 confirms "1001_1002" key form); FriendshipMapper.selectCount pattern in tests; InternalFriendController (/internal/friends) has requests + list endpoints.
- RecordSubmitDTO: requestId/userId/sportType(@Min1@Max3)/startTime/endTime/distance/duration/points(@Size 20000) — visibility slot absent. RecordSummaryDTO: id/sportType/distance/status/createdAt — visibility absent.
- SportRecord entity: 10 fields incl. status/version/createdAt; no visibility.
- web: record-submit.page.vue (form; L74 imports, L160 preview via getRecordPoints), record-detail.page.vue (L287 getRecordPoints, L291 getVerifyResult), verdict.page.vue (L103 getVerifyResult, L137-139 like trio), index.page.vue (my-records list, RecordSummaryDTO consumer), client.ts (submitRecord/getVerifyResult/like trio/getRecordPoints).
- offline baseline: 52/50/165/178/159/80/26 (=710; record=178, user=165). token baseline: 2034.
- Production posture: TASK-191 strict linkage forbids auth.enabled=false in production (startup failure) → degrade-mode caller=null unreachable in prod; degrade path is local-benchmarks-only.

## S2 preregistered design
- api module:
  - New enum com.sportverify.api.record.RecordVisibility: PUBLIC(0)/FRIENDS(1)/PRIVATE(2), @Getter code, RecordStatus/SportType precedent form; javadoc carries the full gate semantics.
  - RecordSubmitDTO: + Integer visibility, optional, @Min(0) @Max(2) with messages (out-of-range → 400 before service).
  - RecordSummaryDTO: + Integer visibility.
  - UserApi: + `Result<Boolean> areFriends(@RequestParam("userId") Long userId, @RequestParam("otherUserId") Long otherUserId)`.
  - UserApiFallback: + areFriends → false (fail-safe privacy; friends-leaderboard empty-board precedent, UserApiFallback javadoc decision).
- sql:
  - New migrations/add-record-visibility.sql — add-user-role.sql pattern verbatim: information_schema COUNT on sport_record.visibility, ALTER TABLE `sport_record` ADD COLUMN `visibility` TINYINT NOT NULL DEFAULT 0 COMMENT '可见性：0 公开（默认）/1 好友可见/2 仅自己（TASK-221）' AFTER `status`, else SELECT skip-message; USE record_db.
  - 02-record-db.sql: same column line inside sport_record CREATE TABLE (fresh-install path), positioned after `status`, comment text byte-identical to the migration.
- user-service:
  - FriendService: + `public boolean areFriends(Long userId, Long otherUserId)` — null/equals args → userId.equals(otherUserId)?false handling preregistered as: either null → false; equal → true (owner short-circuit never reaches here in record-service, but contract stands alone); normalized user_low<user_high selectCount>0.
  - InternalFriendController: + GET /internal/friends/exists → Result<Boolean> (params userId, otherUserId), pure delegation.
- record-service:
  - SportRecord entity: + `private Integer visibility;`
  - SportRecordService.submit: resolveVisibility(dto.getVisibility()) — null→PUBLIC(0) fallback; non-null outside enum → BizException (resolveSportType precedent).
  - SportRecordService: + private assertCanView(SportRecord record, Long caller): caller==null → pass (degrade-zero-perturbation, TASK-204 precedent; prod unreachable); caller.equals(record.getUserId()) → pass; visibility PRIVATE → FORBIDDEN(1002); FRIENDS → userApi.areFriends(record.getUserId(), caller) ? pass : FORBIDDEN; PUBLIC or null visibility (pre-migration rows in fresh-DB test contexts) → pass. UserApi exception/soft-degrade false → FORBIDDEN (fail-safe).
  - pagePoints: replace caller==owner assertion with assertCanView (owner/stranger semantics unchanged for PRIVATE-equivalent strictness? NO — preregistered: PUBLIC records become readable by any authenticated caller; FRIENDS by friends; PRIVATE owner-only. This is the intended product change.)
  - getVerifyResult: signature + caller param; controller passes resolveUserId(request, null) (true→header, false→null); assertCanView before verify-api call. Closes open-verdict exposure.
  - RecordLikeService: like/unlike/getLike — after record lookup and existing status gate, assertCanView(record, caller) (reuse via injected collaborator or service-level static helper — implementation free, but the gate matrix must be test-identical across the trio).
  - LikeController: zero changes (already passes acting user into service).
- web:
  - client.ts: RecordSubmitDTO/RecordSummaryDTO types + visibility?: number.
  - record-submit.page.vue: visibility selector (radio/three options: 公开/好友可见/仅自己), default 公开(0), included in submit payload.
  - index.page.vue: my-records list item visibility badge (好友可见/仅自己 tags; 公开 shows none), driven by RecordSummaryDTO.visibility.
  - web/README.md: one-line registration.
- Out of scope (preregistered): leaderboard (user adjudication), visibility PATCH (user adjudication), record-detail/verdict visibility hints (data source lacks visibility — would require verify-result DTO change; deferred), areFriends caching (single pairwise call per detail view; register as later optimization if profiled).

## S2.5 discriminants
- R1 enum+schema: RecordVisibility codes 0/1/2 golden values; migration re-runnable (second execution hits skip-branch); 02-record-db.sql diff = exactly one column line inside sport_record CREATE.
- R2 gate matrix (core, 13 cases): owner×{PUBLIC,FRIENDS,PRIVATE} → pass; stranger×PUBLIC → pass; friend×FRIENDS → pass; stranger×FRIENDS → 403; anyone-non-owner×PRIVATE → 403; caller=null (degrade) → pass unchanged; UserApi false/exception → 403.
- R3 verify-result closure: authenticated non-owner on PRIVATE → 403 (HTTP 403 body 1002); owner → 200 unchanged; degrade mode → behavior byte-identical to today.
- R4 submit path: null visibility → 0 persisted; explicit 1/2 persisted; out-of-range → 400 (DTO) and service-side reject for direct calls.
- R5 like trio: matrix same as R2 applied to like/unlike/getLike (private record like by stranger → 403, friend on FRIENDS record → 200, etc.).
- R6 web: selector defaults 0 and submits chosen value; list badge renders for 1/2; type-check+build+code review carry (real-browser interaction registered uncovered, TASK-213/220 precedent).
- G1 offline: record and user net-add only; common/gateway/verify/leaderboard/mapmatch per-digit unchanged (expected record 178→~192, user 165→~168; exact = execution reality).
- G2 changed set == exactly S4 whitelist.
- G3 FULL gates: offline full + static + contract dual-state + token 29 items ≥2034 + word-check CI-exact on changed set + synthetic probe + git diff --check + line endings + web trio (pnpm test unchanged count OK — no new web pure function required; type-check/build/typed-router clean).

## S3 stop conditions
1. Existing pagePoints/getVerifyResult/like code shape contradicts recon (e.g. gate not insertable at preregistered point) — stop, report.
2. Existing-test adaptation forces offline count below baseline in ANY module — stop (CI floor red line).
3. UserApiFallback degrade semantics conflicts with friends-leaderboard empty-board precedent — stop, report.
4. Any additional unguarded exposure discovered on record read paths beyond S1 list (new endpoint in RecordApi internal face counts as out-of-scope, internal trust boundary) — stop, report.
5. visibility column collides with existing row data (schema conflict on scratch replay) — stop, report.

## S4 whitelist (target C-01 = exactly 21 files)
sql/migrations/add-record-visibility.sql (new),
sql/02-record-db.sql,
api/src/main/java/com/sportverify/api/record/RecordVisibility.java (new),
api/src/main/java/com/sportverify/api/record/dto/RecordSubmitDTO.java,
api/src/main/java/com/sportverify/api/record/dto/RecordSummaryDTO.java,
api/src/main/java/com/sportverify/api/user/UserApi.java,
api/src/main/java/com/sportverify/api/user/UserApiFallback.java,
user-service/src/main/java/com/sportverify/user/service/FriendService.java,
user-service/src/main/java/com/sportverify/user/controller/InternalFriendController.java,
user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java,
record-service/src/main/java/com/sportverify/record/entity/SportRecord.java,
record-service/src/main/java/com/sportverify/record/service/SportRecordService.java,
record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java,
record-service/src/main/java/com/sportverify/record/controller/RecordController.java,
record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java,
record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java,
record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java,
web/src/api/client.ts,
web/src/pages/record-submit.page.vue,
web/src/pages/index.page.vue,
web/README.md.

## S6 gates (FULL tier)
- offline full via D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test: rc=0, per-digit 52/50/165/~192/159/80/26 shape (record/user net-add only, other five per-digit unchanged; rc=3 not PASS).
- static (checkstyle etc.) rc=0; web pnpm test rc=0, type-check rc=0, build success, typed-router diff clean.
- contract gate dual-state: --open TASK-221 --baseline=<dispatch commit>; closing no-arg rc=0.
- token 29 items two-point ≥2034; word-check CI-exact (git grep -n -I -iE with explicit -E, pattern file in .trae/tmp, zero Chinese on command lines) ZERO_HIT on changed set + synthetic probe positive control; git diff --check; new files pure LF + trailing newline no BOM; changed files preserve baseline endings.
- Migration must be proven idempotent: executed twice on scratch (second run hits skip branch); scratch db used for any db verification.
- C-01 feat(record): 记录可见性三态与读取面访问门（TASK-221）; C-02 docs(mailbox): 登记TASK-221可见性三态验收与台账闭环（TASK-221）, 4 ledger files (tasks.json勾选 / spec.md §7回填 / handoff.md 新建 / PLAN.md 纯追加). No push, no PR.
- Independent review subagent (user-dispatched): five-block rerun — git state/commit chain, red-line spot check, both-side gate rerun, evidence chain, changed-set equality; zero writes, self-attest porcelain empty at end.

## S7 backfill (after C-02)
- (to be filled by executor: commit hashes, offline/web/token final, discriminant red/green, deviations, handoff.md path)
