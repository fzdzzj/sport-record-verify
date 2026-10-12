# TASK-208: self-service password change with full refresh revocation

Tier: FULL (security semantics). Review: NEW 4-block regime (Git chain + only-changed-list merged / red-line spot check / gates rerun / evidence-chain consistency).

## S0 hard constraints
- api module (all DTOs incl. TokenDTO/Login/Refresh/Logout) ZERO changes. New ChangePasswordRequestDTO goes to user-service local dto package (pattern per RegisterRequestDTO field validation style).
- gateway/common/verify/leaderboard/mapmatch/record services, parent pom, sql, ci.yml, mvn-verify.sh, mailbox-contract.sh, docker-compose*: ZERO touch.
- web whitelist: settings.page.vue (new), index.page.vue (entry only), src/api/client.ts (or dedicated auth api module per existing convention), typed-router.d.ts (regen, tracked). No other web files.
- Redis: only pattern-delete of auth:refresh:{userId}:* for the target user. No index/state-machine/batch/cycle/MQ parameter changes (global constraint).
- No performance/latency claims in handoff or ledger.

## S1 verified context
- AuthController (user-service): register/login/refresh/logout only; refresh/logout take cookie-first body-fallback (TASK-205); auth.enabled=true paths inject X-User-Id (TASK-204 paradigm, cf. record pagePoints ownership).
- AuthService: refresh alive keys = auth:refresh:{userId}:{jti} (value 1, TTL=refresh lifetime); logout deletes single key; refresh rotation atomically consumes old jti (Lua GETDEL-equivalent); login BCrypt matches fail -> 1001 (no existence leak); refresh on banned user -> 401 code 1001.

## S2 preregistered design
- POST /api/auth/change-password; when auth.enabled=true read X-User-Id (self-ownership implicit); when disabled reject 401/403 per existing controller conventions.
- AuthService.changePassword(userId, oldPassword, newPassword):
  1. load user: null or status=0 -> BizException 1001 (banned/no-leak doctrine, cf. TASK-202 refresh ban check);
  2. BCrypt matches(old, hash) fail -> BizException 1001;
  3. old == new -> 400 PARAM (preregistered decision: change semantics require difference);
  4. else: encode new hash, update DB (mapper update), then stringRedisTemplate delete all keys matching refreshPrefix + userId + ':*' (java keys(pattern) acceptable: per-user key cardinality tiny; empty set = no-op).
- Error contract in README auth section updated; current access token remains valid up to 15min (TASK-202 timing doctrine, consistent wording).

## S2.5 red/green discriminants
- R1a: valid old+new -> 200; DB hash now matches new; ALL redis keys auth:refresh:{userId}:* gone.
- R1b: wrong old -> 1001; DB hash unchanged; existing keys preserved.
- R1c: old==new -> 400.
- R1d: status=0 user -> 401 (1001 doctrine).
- R1e: blank/short new password -> 400 @Valid.
- G1: after R1a, replaying any pre-change refresh -> 401; login with new password -> 200, with old -> 1001.
- G2: existing AuthServiceTest/AuthControllerTest cases zero regression.

## S3 stop conditions
1. Any required change spills outside S0 whitelist (esp. api module, gateway).
2. Redis pattern-delete needs infra beyond stringRedisTemplate (cluster/scan semantics issue).
3. Existing test conventions conflict with new cases in a way requiring convention changes.
4. Checkstyle/SpotBugs violations appear non-trivially attributable to the new code.
5. offline baseline drops or unrelated modules change per-digit.

## S4 whitelist (approx; executor finalizes within bounds, lead audits)
user-service: AuthController.java, AuthService.java, dto/ChangePasswordRequestDTO.java (new), AuthServiceTest.java, AuthControllerTest.java; web: pages/settings.page.vue (new), pages/index.page.vue, api client file, typed-router.d.ts; README.md. Target C-01 = 9-10 files.

## S6 gates (FULL tier, 4-block review)
- offline full per-digit: baseline 655=52/50/152/157/159/70/15, user net-increase only, other six modules per-digit unchanged (entry: bash scripts/verify/mvn-verify.sh --mode=offline test via D:\git\Git\bin\bash.exe; rc=3 not PASS).
- contract gate dual-state: --open TASK-208 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 (two-point: post-C01 + close; no token literals in new docs).
- word-check CI-exact (-n -I -iE explicit -E) on changed file set ZERO_HIT; probe positive control reused from batch (TASK-206/207 evidenced).
- git diff --check; line endings per-file baseline (new files pure LF + trailing newline); no BOM.
- web: local npm run build if env permits (TASK-205 D-esbuild precedent: vue-tsc rc=0 + CI web job authoritative fallback).
- C-01 fix(auth): ... (TASK-208) implementation commit; C-02 docs(mailbox): ... (TASK-208) ledger commit (tasks.json / spec.md S7 / handoff.md new / PLAN.md append-only). No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = 6bd3cd5eb509818f6288fd247bec6f1f1ffcb904; C-02 = <本台账笔所在提交，自指沿 D1 先例，以 git log 实测为准>
- offline final / token final: 665 = 52/50/162/157/159/70/15 (user 152→162 净增 +10，其余六模块逐位不变) / token 29 项 post-C01=2030、close=2030（只增不减）
- discriminant results R1a-R1e, G1-G2: 见 handoff.md §4（R1a 绿；R1b 1001 hash 不变键保留；R1c 400；R1d 401；R1e @Valid 400；G1 改密后旧 refresh 401 + 新密码登录 200 / 旧密码 1001；G2 既有用例零回归）
- deviations: 见 handoff.md §7（D1 自指哈希；D2 web 本机 build 因 esbuild Windows temp 访问被阻，fallback vue-tsc rc=0 + 登记 CI web job 权威）

- 补记（指导侧封盘笔，2026-10-12）：推送 beae7cd..aa1d7f6（4 笔=TASK-207 封盘笔+TASK-208 派发/实施/台账笔）后第 48 次 CI
  （run 38223686992，head=aa1d7f6）conclusion=success 全 job 绿（含 web build/type-check，D2 本机 esbuild 受阻项经 CI 权威补验）。
  四环节终裁全过（指导亲核/独立复核四块 PASS 零阻断——新口径首次 FULL 复核/两报告零矛盾/CI success），TASK-208 封盘。
  auth 域功能面至此全闭环（register/login/refresh/logout/ban/unban/grant/revoke/change-password）。
  N1=AuthService.java:373 javadoc「HMAC/BCrypt」措辞瑕疵（实为纯 BCrypt），非阻断，后续课题随手订正。
