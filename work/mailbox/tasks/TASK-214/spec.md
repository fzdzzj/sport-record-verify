# TASK-214: test backfill (mapmatch contract + notification preference auth paths)

Tier: MEDIUM (pure test additions, zero production code). Review: guidance-side verification only, CI as external final gate. Escalation: assertion failure or contradiction -> FULL.

## S0 hard constraints
- ZERO changes to: all six backend services' src/main, api module, common, parent pom, sql, ci.yml, mvn-verify.sh, mailbox-contract.sh, compose, web (untouched entirely), any pom (mapmatch already has spring-boot-starter-validation at pom.xml:36-39 — verified, no pom edit allowed).
- Test whitelist only (S4). No new dependencies, no new plugins, no test-resource files.

## S1 verified context
- MapMatchRequestDTO (api): points @NotEmpty + @Size(max=20000) + @Valid; MapMatchPointDTO: seq @NotNull+@DecimalMin(0), lat @NotNull+[-90,90], lng @NotNull+[-180,180]. All constraints currently untested.
- MatchProperties: mapmatch.match.* prefix, defaults 200/50/25/300. MapMatchServiceTest golden values implicitly depend on searchRadiusMeters=300 (300/85000 radius conversion, GOLDEN_MAX=300 cap) and snapThresholdMeters=25 (matched-ratio golden split).
- MapMatchController: single POST /match, @Valid @RequestBody, Result.success wrapper; @RequiredArgsConstructor public ctor (manual construction OK).
- NotificationControllerTest (user): exists with 17 tests incl. TASK-187 preference tests (auth-disabled positive x2 + missing-header 401 x2). Missing: auth-enabled positive paths on preferences, claimed-mismatch 403 on preferences (resolveUserId shared with page endpoint which has 403 tested).
- Paradigms: AuthRequestDtoValidationTest (pure jakarta Validator, no Spring context), NotificationControllerTest (manual construction + ReflectionTestUtils for authEnabled), DataSourcePasswordEnvBindingTest (Binder with PropertySourcesPlaceholdersResolver, MutablePropertySources without StandardEnvironment system sources).
- offline baseline: 684 = 52/50/162/166/159/80/15 (common/gateway/user/record/verify/leaderboard/mapmatch).

## S2 preregistered design
- MapMatchRequestValidationTest (new, mapmatch test dto package): Validator-driven DTO contract tests. R1a empty points -> NotEmpty violation; R1b size boundary (20000 valid / 20001 violates @Size); R1c cascaded @Valid violations (point with seq=null / lat=90.5 / lng=null each violate); R1d fully valid request -> zero violations.
- MapMatchControllerTest (new, mapmatch test controller package): manual construction with mocked MapMatchService; R2 delegation + Result.success wrapper + request passthrough (same instance). Note: @Valid does not fire under manual construction — validation semantics are carried by R1 (Validator level); controller test scope is wrapper/delegation only.
- MatchPropertiesTest (new, mapmatch test config package): R3a defaults assertion (200/50/25/300 — the load-bearing contract for golden values); R3b Binder override via mapmatch.match.* prefix (MutablePropertySources, no StandardEnvironment system sources; new Binder(ConfigurationPropertySources.get(ps), new PropertySourcesPlaceholdersResolver(ps)) paradigm).
- NotificationControllerTest (additions to existing file): R4a/R4b auth-enabled positive paths (X-User-Id=42, claimed null -> getPreferences(42L)/updatePreferences(42L, body) invoked, SUCCESS result); R4c preferences claimed-mismatch (claimed=999 vs header=42) -> BizException FORBIDDEN (1002), service never invoked.

## S2.5 discriminants
- R1a empty points -> NotEmpty violation (property path "points").
- R1b 20001 points -> Size violation; 20000 points -> valid.
- R1c cascaded violations: seq=null / lat=90.5 / lng=null each produce violations with points[i]. prefixed property paths.
- R1d valid request -> zero violations.
- R2 delegation: controller returns Result.success wrapper, code SUCCESS, data = service return, same request instance passed through.
- R3a defaults: new MatchProperties() -> 200/50/25/300.
- R3b Binder: mapmatch.match.max-sampled-points=100 etc. -> bound values override defaults.
- R4a/R4b preferences auth-enabled positive: header identity used, service invoked with 42L.
- R4c preferences claimed-mismatch -> BizException FORBIDDEN, preference service never invoked.
- G1: existing tests zero regression (offline: mapmatch/user net-add only, other five modules per-digit unchanged).
- G2: production diff empty (six services src/main + api + common + pom + sql + ci + scripts + compose all zero diff); web untouched.

## S3 stop conditions
1. Change spills outside S4 whitelist (any non-test file touched).
2. Production/config change appears needed (e.g. missing public ctor, missing validation starter) — stop and report, do not edit pom.
3. Validation behavior contradicts preregistered expectations (e.g. cascaded violation not firing) — stop and report as potential production defect instead of weakening the test.

## S4 whitelist (target C-01 = exactly 4 files)
mapmatch-service/src/test/java/com/sportverify/mapmatch/dto/MapMatchRequestValidationTest.java (new),
mapmatch-service/src/test/java/com/sportverify/mapmatch/controller/MapMatchControllerTest.java (new),
mapmatch-service/src/test/java/com/sportverify/mapmatch/config/MatchPropertiesTest.java (new),
user-service/src/test/java/com/sportverify/user/controller/NotificationControllerTest.java (additions only).

## S6 gates (MEDIUM tier)
- offline full: mapmatch 15->(15+delta) and user 162->(162+delta) net-add only; common=52/gateway=50/record=166/verify=159/leaderboard=80 per-digit unchanged (entry: D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test; rc=3 not PASS).
- static gate if historically scoped to specific modules: run existing-scope static check per current repo practice (record-service if that is the standing scope); no new static-scope expansion.
- contract gate dual-state: --open TASK-214 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 two-point; word-check CI-exact (-n -I -iE explicit -E) ZERO_HIT on changed file set + synthetic probe positive control; git diff --check; new files pure LF + trailing newline, no BOM.
- C-01 test(mapmatch): ... (TASK-214); C-02 docs(mailbox): ... (TASK-214) ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = cb5c0f1（test(mapmatch): 契约校验与偏好鉴权路径测试补强（TASK-214），恰 4 文件 +321/−0）; C-02 = self-referencing hash (TASK-178/183/204/205/206/207/208/209/210/211/213 precedent)
- offline / token final: offline 698 = 52/50/165/166/159/80/26（mapmatch 15→26 净增 +11、user 162→165 净增 +3，common/gateway/record/verify/leaderboard 五模块逐位不变），BUILD SUCCESS rc=0；static 既有范围双跑 rc=0（--static=record-service、--static 缺省 leaderboard）；token 29 项两点读数 2030 持平（只增不减；改动文件无任何凭证字面量，词面门凭证自检 ZERO_HIT 佐证，权威 29 项清单由指导侧复测）
- discriminant results: R1a 绿（空 points 恰 1 条 NotEmpty，path=points）；R1b 绿（20000 合法零违规 / 20001 恰 1 条 Size）；R1c 绿（seq=null→points[0].seq、lat=90.5→points[0].lat、lng=null→points[0].lng 各恰 1 条级联违规）；R1d 绿（全合法零违规）；R2 绿（code=SUCCESS、data 与服务返回值同实例、请求实例透传 assertSame）；R3a 绿（默认值 200/50/25/300 逐位）；R3b 绿（mapmatch.match.* 四键 Binder 覆盖生效）；R4a/R4b 绿（authEnabled=true + X-User-Id=42 + claimed=null → getPreferences(42L)/updatePreferences(42L, body) 命中且 SUCCESS）；R4c 绿（claimed=999≠42 → BizException FORBIDDEN(1002)，偏好服务 never 调用）；G1 绿（七模块 Failures/Errors/Skipped 全 0，五不变模块逐位持平）；G2 绿（生产面零 diff，只改清单恰 8 文件全等）
- deviations: D1 C-02 台账笔自指哈希（沿先例）；D2 任务书 S1「NotificationControllerTest 既有 17 测」与实测 14 测存在措辞偏差——本课题严格仅追加预注册 3 测（R4a/R4b/R4c，既有 14 测零改动，diff +52/−0），追加后总数恰 17 与 offline 实测（Tests run: 17）一致，无语义影响；D3 其余无
