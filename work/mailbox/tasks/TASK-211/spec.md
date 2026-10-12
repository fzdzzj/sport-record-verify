# TASK-211: my-records endpoint + personal stats dashboard

Tier: FULL (new query endpoint + frontend dashboard). Review: 4-block regime. Sequencing: runs after TASK-210 closure; E2 doc-fix topic (TASK-212 candidate) follows this one in the same push batch.

## S0 hard constraints
- ZERO changes to: existing api module files (NEW RecordSummaryDTO file allowed - LeaderboardDTO precedent), other services (user/gateway/verify/leaderboard/mapmatch), common, parent pom, sql scripts, indexes (no new index - red-lined; register limitation), write paths / MQ / settlement, ci.yml, mvn-verify.sh, mailbox-contract.sh, compose.
- record-service whitelist: RecordController.java, RecordService.java (or dedicated new method area), record mapper file(s), RecordControllerTest/RecordServiceTest.
- web whitelist: index.page.vue, client.ts; typed-router.d.ts unchanged (no page add/remove). README one section.
- No new frontend dependencies (trend chart = pure CSS/SVG). No performance claims.

## S1 verified context
- RecordController current endpoints: POST / (submit), GET /{id}/verify-result, POST /{id}/appeal, GET /{id}/points (TASK-204 hardened ownership). No list endpoint exists.
- Identity paradigm in record: pagePoints/appeal use caller ownership assertions effective when auth.enabled=true; leaderboard resolveUserId paradigm: true -> header required 401, false -> explicit param degraded.
- index.page.vue is a static welcome console (links list); settings/leaderboard pages show the antdvue card/form paradigms to follow.
- Executor must self-verify: sport_record entity fields (id/userId/sportType/distance/status/createdAt etc.), whether mapper already has a by-userId query, page plugin in use (MyBatis-Plus IPage vs manual LIMIT), and RecordControllerTest existing style.

## S2 preregistered design
- GET /api/records/my?page=1&size=20 (page>=1, size 1..500 cap; default 20). Response Result<Page-like<RecordSummaryDTO>> following the codebase's existing pagination response convention (executor aligns with points endpoint's Page shape).
- Identity: auth.enabled=true -> X-User-Id header required, missing/blank -> 401 (1001); false -> explicit userId query param required, missing -> 400 PARAM. Records filtered strictly by that userId (ownership by construction - no per-row assertion needed since the filter IS the identity).
- RecordSummaryDTO (api module, new file): fields aligned to entity essentials (id, sportType, distance, status, createdAt) - executor finalizes against entity; follows existing api DTO style (LeaderboardDTO).
- Frontend: index.page.vue = welcome card + stat cards (total distance sum / record count / pass-rate over PASSED+RE_PASSED) + last-30-days daily-distance mini bar chart (pure CSS/SVG divs, no chart lib) + recent records list (latest 10 from the same fetch). Stats from a single size=500 fetch; >500 personal records = known limitation registered (partial stats). Unauthenticated state keeps the existing welcome links.
- client.ts: getMyRecords(page, size) per contract.

## S2.5 red/green discriminants
- R1a: my-records with X-User-Id returns only that user's rows (mapper filter verify).
- R1b: page/size boundaries (page>=1, size cap 500, default 20).
- R1c: auth.enabled=false without explicit userId -> 400; with -> filtered accordingly.
- R1d: empty result -> empty page shape (no error).
- R2a: stat cards aggregate correctly from fetched rows (sum/count/rate math).
- R2b: 30-day trend renders pure CSS/SVG with zero new deps (package.json diff empty).
- R2c: recent list shows latest rows.
- G1: existing record-service tests zero regression.
- G2: red-line diff empty (existing api files / other services / sql / index / write paths).

## S3 stop conditions
1. Change spills outside S0 whitelist.
2. Pagination convention conflicts require touching shared infra.
3. Checkstyle/SpotBugs violations attributable to new code need structural rework.
4. offline baseline drops or unrelated modules shift per-digit.

## S4 whitelist (target C-01 = 7-9 files)
record-service: RecordController.java, RecordService.java, mapper file, RecordSummaryDTO.java (api, new), RecordControllerTest.java (or ServiceTest per coverage point); web: index.page.vue, client.ts; README.md.

## S6 gates (FULL tier, 4-block review)
- offline full per-digit: baseline 675=52/50/162/157/159/80/15, record 157 net-increase only, other six modules per-digit unchanged (entry: bash scripts/verify/mvn-verify.sh --mode=offline test via D:\git\Git\bin\bash.exe; rc=3 not PASS).
- static gate record-service rc=0 if code style gates apply (TASK-194 regime).
- contract gate dual-state: --open TASK-211 --baseline=<dispatch>; closing no-arg.
- token 29 items 2030 (two-point: post-C01 + close).
- word-check CI-exact (-n -I -iE explicit -E) ZERO_HIT + probe positive control (batch reuse).
- git diff --check; line endings per-file baseline; new files pure LF + trailing newline.
- web: local build if env permits else vue-tsc rc=0 + CI web job authoritative (precedent). package.json zero-diff as R2b evidence.
- C-01 feat(record): ... (TASK-211); C-02 docs(mailbox): ... (TASK-211) ledger 4 files. No push, no PR.

## S7 backfill (after C-02)
- commit hashes: C-01 = 852e97b（feat(record): 我的记录端点与个人统计面板（TASK-211），8 文件 +444/−3）；C-02 = 本台账笔所在提交（自指沿 D1 先例，以 git log 实测为准）
- offline final / token final: 684 = 52/50/162/166/159/80/15（record 157→166 净增 +9，其余六模块 common/gateway/user/verify/leaderboard/mapmatch 逐位不变） / token 29 项 post-C01=2030、close=2030（只增不减，新文档零 token 字面量）
- discriminant results R1-R2, G1-G2: 见 handoff.md §4（R1a 仅返本人行 mapper 过滤 verify + X-User-Id 解析与提交；R1b 分页边界 page≥1/size cap500/默认20；R1c auth 开缺头 401/1001、auth 关缺 userId 400、显式 userId 放行；R1d 空结果空 Page 非错误 + DTO 五字段映射；R2a 统计卡 SUM/计数/通过率=PASSED(2)+RE_PASSED(5)；R2b 近30天纯 CSS 条图零新依赖、package.json 零 diff；R2c 最近记录前10条 createdAt 倒序；G1 record 既有用例零回归 offline 166 全绿 + vue-tsc rc=0；G2 红线 diff 空、api 仅新增 RecordSummaryDTO、未加 index）
- deviations: 见 handoff.md §8（D1 C-02 自指哈希；D2 pwsh 管道遮蔽 Maven 退出码以 Maven 明文输出为准；D3 未跑本机 web build、按 S6 口径 vue-tsc rc=0 + CI web job 权威）

