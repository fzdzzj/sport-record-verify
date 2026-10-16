# TASK-250 台账（handoff）

> **TASK**：D 系卫生与纵深批——D4 改密吊销 KEYS→SCAN + D5 WS STOMP Origin 纵深 + D7 点赞对账分页化 + D8 R5 连续降级可观测告警（FULL 档）  
> **开工基线**：`3b56032`（main，工作树干净；本占位符随 C-02 回填为实际哈希）  
> **方案**：2026-10-16 批 D 系只读立项侦察产出，用户拍板 D4+D5+D7+D8 合并立项（剩余候选全量收口）  
> **状态**：已实施；独立复核五块全 PASS（零阻断，N1-N5 非阻断登记），指导侧终裁 ACCEPT 予以封盘（2026-10-16）

## 1. 实施摘要

R1/R2/R3/R4 四判别式逐项落地（多模块 FULL 档）：

- **R1（D4 改密吊销 KEYS→SCAN）**：`AuthService.changePassword` 第 5 步吊销段由 `stringRedisTemplate.keys(...)` 重写为 `stringRedisTemplate.scan(ScanOptions.scanOptions().match(refreshUserPrefix(userId) + "*").count(SCAN_BATCH).build())` 游标迭代（try-with-resources `Cursor<String>`，新增 `SCAN_BATCH = 500L`），累积 `HashSet<String> liveKeys` 后统一 `delete`；空集 no-op、扫描/删除任一异常 → `log.error` + `throw new BizException(ResultCode.SYSTEM_ERROR)` 的安全语义与「DB 已改 h 但吊销失败遗留可用旧 refresh 属安全缺口：如实报错不虚假成功」注释逐字保持。
- **R2（D5 WS STOMP Origin 纵深）**：`WebSocketNotificationConfig` 构造器新增 `@Value("${user.ws.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}") List<String> allowedOrigins` 参数；`registerStompEndpoints` 由 `addEndpoint(STOMP_ENDPOINT)` 改为 `.setAllowedOrigins(allowedOrigins.toArray(new String[0]))`；Javadoc 补 CSWSH 双层防线说明（握手层 Origin 白名单 + 既有 CONNECT 帧级 JWT 鉴权，`StompConnectAuthInterceptor` 零触碰）。`docker-compose.services.yml` user-service `environment` 新增 `USER_WS_ALLOWEDORIGINS: "${CORS_ALLOWED_ORIGINS:-http://127.0.0.1:5173,http://localhost:5173}"`（复用网关 `CORS_ALLOWED_ORIGINS` 单一事实源，沿 TASK-247 H2 先例）。
- **R3（D7 点赞对账分页化）**：`RecordLikeMapper` 新增键集分页两方法 `selectDistinctRecordIdsAfter(Long lastRecordId, int limit)`（`SELECT DISTINCT record_id FROM record_like WHERE record_id > #{lastRecordId} ORDER BY record_id LIMIT #{limit}`，PK `(record_id,user_id)` 前缀索引覆盖）与 `selectPairsByRecordIds(List<Long>)`（`<script>` + `foreach` IN 构造）；`selectRecordLikePairs()` 保留供 IT 直读。`RecordLikeService.reconcileLikeCounts` 数据获取段由单次全表 `selectRecordLikePairs()` 重写为键集分页循环（新增 `RECONCILE_RECORD_BATCH_SIZE = 500`）：取一批 distinct record_id → 按批 IN 取完整 pairs → 内存按 record_id 分组生成命令三元组（SET 计数 + DEL 成员集 + SADD 成员）→ cursor = 本批最大 record_id 严格递增 → 空批或短批（`size < 500`）即本轮完成；pipeline 分批提交逻辑抽为私有 `flushReconcileChunk`（`PIPELINE_BATCH_SIZE` 语义不变）；per-record 纠偏三元组与对账锁 `tryLock(3, -1)`（watchdog 续期）不变。
- **R4（D8 R5 连续降级可观测告警）**：`R5OffRoadRule` 新增包内可见 `final AtomicInteger degradationStreak` 与 `static final int DEGRADATION_ALERT_THRESHOLD = 5`；两个降级分支（catch 异常 / `result == null`）改调新增私有 `recordDegradation(String cause)`——逐次 `log.warn` 附带 streak 计数，`streak == DEGRADATION_ALERT_THRESHOLD` 恰一次升级 `log.error`（固定可 grep 语「R5 连续降级告警」，说明降级窗口内悬浮轨迹不产生离路证据、请核查 mapmatch 健康度），用 `==` 而非 `>=` 防重复告警刷屏；成功评估路径 `int prevStreak = degradationStreak.getAndSet(0)`，`prevStreak >= threshold` 时 warn「R5 降级恢复」。降级分支仍返回 `null` 不抛异常，既有语义零删弱。

测试同步：`AuthServiceTest`（R1a/G1 的 `keys` 桩改 `scan` + mock `Cursor<String>` 携带目标键，R1b/R1c 的 never 断言改 `scan`）；新增 `WebSocketNotificationConfigTest`（纯 Mockito：mock `StompEndpointRegistry`/`StompEndpointRegistration`，断言 `addEndpoint(STOMP_ENDPOINT)` 后 `setAllowedOrigins` 恰以入参列表调用 + 默认值列表透传）；`RecordLikeServiceTest` 改写分页口径（单批恰 1 次 distinct + 1 次 IN、多批断言 cursor 推进序列与两批纠正结果合并、空表零 IN 零 Redis 写）；`RecordLikeServiceTraceIdTest` 两条对账桩同步分页化；`RecordLikeWritePathCapacityIT` 断言按需微调；`R5OffRoadRuleTest` 新增连续 3 次降级 streak=3、跨阈值 5 次不抛异常且恰一次 error、成功评估归零、阈值内降级→成功→再降级重置等用例。

## 2. 修改清单（实际改动集）

实际改动集 = `git diff --name-only 3b56032` + 未跟踪文件（`.trae/*` 已按契约排除）= 恰 14 条路径，与 spec §2 白名单（15 项，文件 9 为条件项未触碰）逐项双向全等声明：

- `user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java`
- `user-service/src/main/java/com/sportverify/user/config/WebSocketNotificationConfig.java`
- `user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java`
- `user-service/src/test/java/com/sportverify/user/config/WebSocketNotificationConfigTest.java`
- `record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java`
- `record-service/src/main/java/com/sportverify/record/mapper/RecordLikeMapper.java`
- `record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java`
- `record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTraceIdTest.java`
- `record-service/src/test/java/com/sportverify/record/service/RecordLikeWritePathCapacityIT.java`
- `verify-service/src/main/java/com/sportverify/verify/algorithm/rule/R5OffRoadRule.java`
- `verify-service/src/test/java/com/sportverify/verify/algorithm/rule/R5OffRoadRuleTest.java`
- `docker-compose.services.yml`
- `work/mailbox/tasks/TASK-250/spec.md`
- `work/mailbox/tasks/TASK-250/handoff.md`

除上述 14 条外无任何其他改动：条件触碰项 `RecordLikeReconcilePipelineIT.java` 本次未触碰；`gateway-service`/`leaderboard-service`/`mapmatch-service` Java、根与子模块 `pom.xml`、`web/package.json`、`work/mailbox/PLAN.md`、`work/mailbox/HANDOVER.md`、`StompConnectAuthInterceptor`、`sql/`、各服务 `application.yml`、既有迁移脚本、`scripts/verify/*` 全部零触碰。

## 3. 门禁读数

读数时点为收口态工作树（C-02 提交前）：本节为「C-01 待提交（或已提交）+ 两件套待提交」快照，收口后工作树新增 C-02 台账提交会使部分读数（如 `git diff --check` 的对象集）不可逐字复现，语义等价口径见本节末。

1. **后端全模块离线门禁（FULL 档全量逐位）**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test` → 退出码 **0**，BUILD SUCCESS，全模块逐位通过。各模块 surefire 测试数：common **52** / gateway-service **62** / user-service **202** / record-service **300** / verify-service **182** / leaderboard-service **104** / mapmatch-service **29**，Failures=0 / Errors=0。基线 `3b56032` = 52/62/200/298/175/104/29；改动集只触及 user/record/verify 三模块：user **200 → 202**（+2，D5 新增 `WebSocketNotificationConfigTest`）、record **298 → 300**（+2，D7 用例）、verify **175 → 182**（+7，D8 新用例），其余模块凭白名单外零 diff 论证不变。全仓测试数只增不减。
2. **Compose 校验**（触碰 docker-compose.services.yml）：`docker compose -f docker-compose.yml -f docker-compose.services.yml config` → 退出码 **0**。
3. **前端回归**（零前端改动，照跑）：`cd web; pnpm run type-check` → 退出码 **0**；`pnpm test` → **4 文件 61 tests 全 pass**，退出码 **0**。
4. **契约门**（Git Bash）：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-250 --baseline=3b56032` → 退出码 **0**，判据 A 两件套齐 + 判据 B 只改清单与实际改动集一致（详见本节末时点说明）。
5. **token 门**（29 项 tracked 全量 `git grep -cF` SUM，权威口径，不枚举字面量以免全仓计数自增失准）：基线 `3b56032` = **2015**，收口态工作树 = **2015**，29 项逐项与基线持平（base=cur）、只增不减；`TOKEN_VIOLATIONS=0`。
6. **词面门**（CI 权威口径 `git grep -n -I -iE "..."`，本机显式 `-E` 以免 `|` 退化为字面量）：真实扫描（排除 `spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）**零命中**（rc=1）；**探针阳性对照**对自含禁用词的合成 fixture（`.trae/tmp/`，`--no-index` 扫描）命中 1 行 rc=0 → 正则引擎有效、探针阳性成立；探针已删除（`PROBE_GONE=yes`）。词面门通过。
7. **`git diff --check`**：C-01 改动集（12 文件）提交前 **rc=0 净**（无空白错误；输出中的 LF→CRLF 为 git 换行转换提示，非空白违规）。两件套台账笔含仓内既有的 blockquote 硬换行约定行（行尾两空格，全部 TASK 台账同理），`git diff --check` 对该两行报 trailing-whitespace 提示——系修订既有硬换行行（仅替换哈希）的固有形态、非新增空白违规，未改其行尾约定；修改的 12 个代码/基建文件保持既有 CRLF 行尾，两件套 `.md` 保持仓内台账 LF 惯例。

## 4. 已知受限与偏差声明

- **D4 SCAN 快照竞态遗漏面（spec §5 预注册）**：SCAN 与 KEYS 同为快照枚举，扫描窗口内并发新增的 refresh 键存在与 KEYS 相同的竞态遗漏面——属既有口径的延续、非本课题引入的回归，最终由 TTL 与下次改密收敛。
- **D5 Origin 白名单为纵深加固非唯一防线（spec §5 预注册）**：握手层 Origin 白名单与既有 CONNECT 帧级 JWT 鉴权（`StompConnectAuthInterceptor`，零触碰）构成双层防线，缺白名单不会直接导致数据泄露；且本课题仅做单测（mock registry/registration），**未做真实浏览器跨源联调**，生产同源部署下的实际握手行为留作运行期观测。
- **D7 单记录点赞成员集仍为原子载入单元（spec §5 预注册）**：分页仅降低「全表拉取」的单轮内存峰值与单次 I/O 上界，**单条记录的成员集重建仍为一次原子载入**（单 record 的 `DEL`+`SADD`），超大单记录仍受单记录峰值约束。
- **D8 告警为日志载体（spec §5 预注册）**：连续降级告警以 `log.error` 固定语承载（可 grep），**未接入 metrics/告警平台**；仅提供可观测信号，不含自动熔断/恢复动作。
- **白名单扩充（授权偏差登记）**：D7 分页化改写了 `reconcileLikeCounts` 数据获取段，连带使 `RecordLikeServiceTraceIdTest` 的两条对账桩编译/断言失效，故经**用户显式授权**将该测试文件追加为第 15 项白名单（已在 C-02 的 spec §2 item 8 同步登记，属授权范围内扩充、非越界触碰）；条件触碰项 `RecordLikeReconcilePipelineIT.java` 本次未触碰。
- 其余无受限项：未 push、未建 PR、未 stash、未 `git add -A`；未触碰 `PLAN.md`/`HANDOVER.md`、任一 `pom.xml`、`web/package.json`、`gateway-service`/`leaderboard-service`/`mapmatch-service` Java、`StompConnectAuthInterceptor`、`sql/`、各服务 `application.yml`、既有迁移脚本、`scripts/verify/*`；未删弱既有断言、未引新外部依赖；无新增未跟踪文件（`.trae/tmp/*` 为被忽略的工作区）。

## 5. 双提交记录

- **C-01 实施笔** `32c1d8f`（`32c1d8f361b31f2a613d02404cc9420c5ac88099`）：
  `fix(user,record,verify): 改密吊销SCAN化与WS-Origin纵深及点赞对账分页与R5降级告警（TASK-250）` — 仅文件 1-13 中实际触碰的 **12** 条（条件触碰项文件 9 `RecordLikeReconcilePipelineIT.java` 未触碰、文件 10 `RecordLikeWritePathCapacityIT.java` 已触碰）。
- **C-02 台账笔**（收口最终 HEAD）：
  `docs(mailbox): 登记TASK-250实施完成与门禁回填（TASK-250）` — 仅文件 14（spec.md：替换两处 `<立项笔哈希>` 占位符为 `3b56032`、白名单对齐 15 项、状态改「已实施」）与文件 15（handoff.md：本台账五节回填）。工作树于收口日净（`git status --short` 为空）。
