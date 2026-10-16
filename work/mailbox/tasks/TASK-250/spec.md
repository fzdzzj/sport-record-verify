# TASK-250 任务书（spec.md）

> **课题**：D 系卫生与纵深批——D4 改密吊销 KEYS→SCAN + D5 WS STOMP Origin 纵深 + D7 点赞对账分页化 + D8 R5 连续降级可观测告警  
> **级别**：FULL 档（D4 改密吊销为 auth 凭证路径生产代码 + D5 WebSocket 访问控制安全语义 + 多模块 user/record/verify + compose 基建 → 独立复核 + 全量逐位离线门禁；安全语义改动禁止降级）  
> **责任服务**：`user-service`、`record-service`、`verify-service`、`compose`  
> **开工基线**：`3b56032`（main，工作树干净；占位符由实施侧 C-02 回填为实际哈希）  
> **状态**：已实施

---

## 1. 背景与缺口（2026-10-16 批 D 系侦察，指导侧证据抽查 8/8 坐实）

1. **D4 改密吊销全 keyspace 阻塞扫描**：`AuthService.changePassword`（L417-426）用 `stringRedisTemplate.keys(auth:refresh:{userId}:*)` 全 keyspace 扫描——Redis KEYS 为 O(N) 阻塞命令，N 为**全库键数**（非前缀命中数），大集群下每次改密触发一次主线程阻塞；
2. **D5 WS STOMP 端点缺 Origin 白名单**：`WebSocketNotificationConfig.registerStompEndpoints`（L46）`registry.addEndpoint(STOMP_ENDPOINT)` 未 `setAllowedOrigins`——缺省对所有 Origin 开放握手（CSWSH 纵深缺失；CONNECT 帧级 JWT 鉴权兜底使其实际风险为纵深加固项而非数据泄露）；
3. **D7 点赞对账全表拉取**：`RecordLikeService.reconcileLikeCounts`（L514）`selectRecordLikePairs()` 单次批量取全表 (record_id, user_id) 对拉进 JVM 内存——点赞量增长后单轮对账内存峰值与 I/O 无上界；
4. **D8 R5 降级窗口无突变告警**：`R5OffRoadRule.evaluate`（L66-76）mapmatch 熔断/空结果时降级「不命中」为有意 tradeoff（规范「服务不可用降级」），但仅有逐次 warn——降级窗口内悬浮伪造轨迹不产生离路证据属预期行为，缺「连续降级」聚合告警信号，易被误当 bug 排查或长期被忽视。

## 2. 严格改动文件白名单（恰 15 文件）

实施侧仅允许在以下白名单内修改或新增文件，禁止超出范围：

1. `user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java`（D4）
2. `user-service/src/main/java/com/sportverify/user/config/WebSocketNotificationConfig.java`（D5）
3. `user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java`（D4 测试改写）
4. `user-service/src/test/java/com/sportverify/user/config/WebSocketNotificationConfigTest.java`（D5 新增）
5. `record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java`（D7）
6. `record-service/src/main/java/com/sportverify/record/mapper/RecordLikeMapper.java`（D7 两个新方法）
7. `record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java`（D7 单测改写）
8. `record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTraceIdTest.java`（D7 单测适配——分页化改造其既有 reconcile 桩；用户授权追加项）
9. `record-service/src/test/java/com/sportverify/record/service/RecordLikeReconcilePipelineIT.java`（D7 IT 适配，如需；本次未触碰）
10. `record-service/src/test/java/com/sportverify/record/service/RecordLikeWritePathCapacityIT.java`（D7 IT 适配，如需）
11. `verify-service/src/main/java/com/sportverify/verify/algorithm/rule/R5OffRoadRule.java`（D8）
12. `verify-service/src/test/java/com/sportverify/verify/algorithm/rule/R5OffRoadRuleTest.java`（D8 新用例）
13. `docker-compose.services.yml`（D5 环境变量注入）
14. `work/mailbox/tasks/TASK-250/spec.md`
15. `work/mailbox/tasks/TASK-250/handoff.md`

> 白名单计 15 项（标题「恰 15 文件」与本节逐项一致）：1-13 为业务/测试/基建文件（文件 9、10 为条件触碰项——真实 DB 的 IT 若因分页化需微调断言才触碰，无需改动则零触碰不违规；文件 8 为经用户授权追加的 `RecordLikeServiceTraceIdTest`，因 D7 分页化改造其既有 `reconcileLikeCounts` 桩而必须同步适配），14-15 为台账两件套。

**零触碰红线声明**：
- `gateway-service`、`leaderboard-service`、`mapmatch-service` Java 代码绝对零触碰；
- 根与各子模块 `pom.xml`、`web/package.json` 绝对零触碰（零新外部依赖）；
- `work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 绝对禁止子 Agent 触碰；
- `StompConnectAuthInterceptor`（CONNECT 帧鉴权，D5 的兜底层）、`sql/` 目录、`application.yml` 各服务资源文件零触碰；
- 既有迁移脚本、`scripts/verify/*` 门禁脚本零触碰。

## 3. 详细判别式与实现契约

### 3.1 R1（D4）改密吊销 KEYS→SCAN
- `AuthService.changePassword` 第 5 步吊销段重写：`keys()` 替换为 `stringRedisTemplate.scan(ScanOptions.scanOptions().match(refreshUserPrefix(userId) + "*").count(SCAN_BATCH).build())` 游标迭代（try-with-resources，`Cursor<String>`），累积 `liveKeys` 后统一 `delete`；`SCAN_BATCH` 常量定档 500（Javadoc 说明：SCAN 渐进迭代不阻塞 Redis 主线程，语义与 KEYS 快照枚举一致）；
- **安全语义逐字保持**：空集 no-op；扫描/删除任一异常 → `log.error` + `throw new BizException(ResultCode.SYSTEM_ERROR)`（「DB 已改 h 但吊销失败遗留可用旧 refresh 属安全缺口：如实报错不虚假成功」注释原样保留）；
- 受限声明（handoff §4 登记）：SCAN 与 KEYS 同为快照枚举，扫描窗口内并发新增的 refresh 键存在与 KEYS 相同的竞态遗漏面（非本课题引入的回归，最终由 TTL 与下次改密收敛）；
- 测试改写（`AuthServiceTest`）：R1a/G1 的 `when(redis.keys(...))` 桩改为 `when(redis.scan(any()))` 返回 mock `Cursor<String>`（含目标键）；R1b/R1c 的 `verify(redis, never()).keys(anyString())` 改为 `verify(redis, never()).scan(any())`；既有断言语义零删弱（改密成功/失败路径行为不变）。

### 3.2 R2（D5）WS STOMP Origin 纵深
- `WebSocketNotificationConfig` 构造器新增 `@Value("${user.ws.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}") List<String> allowedOrigins` 参数（逗号分隔自动拆分为 List）；
- `registerStompEndpoints`：`registry.addEndpoint(STOMP_ENDPOINT).setAllowedOrigins(allowedOrigins.toArray(new String[0]))`；
- Javadoc 补充：CSWSH 纵深（握手层 Origin 白名单）与既有 CONNECT 帧级 JWT 鉴权（`StompConnectAuthInterceptor`）构成双层防线；生产经环境变量 `USER_WS_ALLOWEDORIGINS`（Spring relaxed binding → `user.ws.allowed-origins`）覆盖；
- `docker-compose.services.yml` user-service `environment` 节新增 `USER_WS_ALLOWEDORIGINS: "${CORS_ALLOWED_ORIGINS:-http://127.0.0.1:5173,http://localhost:5173}"`——**复用网关 CORS_ALLOWED_ORIGINS 单一事实源**（沿 TASK-247 H2 先例口径）；
- 新增 `WebSocketNotificationConfigTest`（纯 Mockito）：mock `StompEndpointRegistry` + `StompEndpointRegistration`，断言 `addEndpoint(STOMP_ENDPOINT)` 后 `setAllowedOrigins` 恰以入参列表调用；默认值列表构造断言（构造器注入的 List 透传）。

### 3.3 R3（D7）点赞对账分页化
- `RecordLikeMapper` 新增两方法（键集分页，PK `(record_id, user_id)` 前缀索引覆盖）：
  - `selectDistinctRecordIdsAfter(lastRecordId, limit)`：`SELECT DISTINCT record_id FROM record_like WHERE record_id > #{lastRecordId} ORDER BY record_id LIMIT #{limit}`；
  - `selectPairsByRecordIds(recordIds)`：`SELECT record_id, user_id FROM record_like WHERE record_id IN (...)`（IN 构造沿仓内既有 `<script>`/`foreach` 注解模式）；
- `reconcileLikeCounts` 重写数据获取段：`RECONCILE_RECORD_BATCH_SIZE = 500`（常量，Javadoc 说明）；循环「取一批 distinct record_id → IN 取该批完整 pairs → 按现有构造段/提交段逻辑生成 pipeline 命令 → cursor = 本批最大 record_id」；空批或短批（`recordIds.size() < RECONCILE_RECORD_BATCH_SIZE`）即本轮对账完成；
- **语义保持**：一次调度运行覆盖全表（分页仅降内存峰值不缩覆盖面）；per-record 纠偏命令三元组（SET 计数 + DEL 成员集 + SADD 成员）与 pipeline 分批提交逻辑不变；对账锁 `tryLock(3, -1)`（watchdog 续期）适配更长运行无锁过期风险；`selectRecordLikePairs()` mapper 方法保留（IT 断言直读用途）；
- **终止保证**：cursor 严格递增（`record_id > cursor` 且 cursor 取本批最大值），无跨页记录劈裂（两查询法天然按 distinct record 完整分组），无死循环；
- 单测改写（`RecordLikeServiceTest`）：`reconcile_singleBatchQuery_fixesRedisFromDb` 改写为分页口径——单批返回全部 distinct ids 时恰 1 次 `selectDistinctRecordIdsAfter` + 1 次 `selectPairsByRecordIds`，Redis 纠正结果与旧断言一致；新增多批用例（两批，断言 cursor 推进参数序列与 2 次 IN 查询、两批纠偏结果合并正确）；空表用例（首查空批 → 零 IN 查询零 Redis 写）；`reconcile_lockNotAcquired_skip` 的 never 断言改为新方法；IT（真实 DB）按需微调（文件 9/10 条件触碰项）；`RecordLikeServiceTraceIdTest` 两条对账桩同步改为分页口径（文件 8）。

### 3.4 R4（D8）R5 连续降级可观测告警
- `R5OffRoadRule` 新增 `final AtomicInteger degradationStreak = new AtomicInteger()`（包内可见供测试断言）+ `static final int DEGRADATION_ALERT_THRESHOLD = 5`（Javadoc 说明定档依据：5 次连续评估失败 ≈ 熔断窗口量级，避免逐次告警噪音）；
- 两个降级分支（catch 异常 / result==null）：`int streak = degradationStreak.incrementAndGet()`，warn 日志附带 streak 计数；`streak == DEGRADATION_ALERT_THRESHOLD` 恰一次升级 `log.error`（固定可 grep 告警语「R5 连续降级告警」+ 说明降级窗口内悬浮轨迹不产生离路证据，请核查 mapmatch 健康度）——`==` 而非 `>=` 防重复告警刷屏；
- 成功评估路径：`int prev = degradationStreak.getAndSet(0)`，`prev >= DEGRADATION_ALERT_THRESHOLD` 时 warn「R5 降级恢复」（跨阈值降级后的恢复可见性）；
- 语义不变：降级分支返回 `null` 不抛异常（既有「mapmatch不可用_降级不命中_不抛异常」用例保持绿）；既有用例零删弱；
- 新增用例：连续 3 次降级 streak=3 且每次返回 null；连续 5 次跨阈值不抛异常且 streak=5；成功评估后 streak 归零；阈值内降级→成功→再降级 streak 正确重置。

## 4. 本地门禁要求（FULL 档全量逐位）

1. **后端单测（全模块）**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test` → rc=0，全仓测试数只增不减（user 200 基线 + WS 新测；record 298 基线；verify 175 基线 + R5 新测）；
2. **Compose 校验**（触碰 docker-compose.services.yml）：`docker compose -f docker-compose.yml -f docker-compose.services.yml config` 退出码 0；
3. **前端回归**：`cd web && pnpm run type-check && pnpm test` 全绿通过；
4. **契约门禁**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-250 --baseline=3b56032` → 退出码 0；
5. **token 门**：29 项受保护 token 字面量 `git grep -cF` tracked 全量只增不减（改动集零命中，清单勿枚举字面量）；
6. **词面门**：CI 权威口径 `git grep -n -I -iE` 零命中（本机须显式 `-E` + 合成探针阳性对照证明模式有效）；
7. **`git diff --check`**：提交前净。

## 5. 双提交规范

- **C-01 实施笔**：`fix(user,record,verify): 改密吊销SCAN化与WS-Origin纵深及点赞对账分页与R5降级告警（TASK-250）`——仅文件 1-13（含条件触碰项实际触碰者；文件 9 未触碰）；
- **C-02 台账笔**：`docs(mailbox): 登记TASK-250实施完成与门禁回填（TASK-250）`——仅文件 14、15，回填 handoff 五节（实施摘要/只改清单全等声明/门禁读数/受限声明/双提交哈希），并把两件套 `<立项笔哈希>` 占位符替换为实际哈希；
- 已知受限（handoff §4 须登记）：D4 SCAN 快照竞态遗漏面（与 KEYS 同口径非回归）；D5 Origin 白名单为纵深加固非唯一防线（CONNECT 帧鉴权兜底不变）、生产同源部署未真实浏览器联调；D7 单记录点赞成员集仍为原子载入单元（分页降全表峰值非单记录峰值）；D8 告警为日志载体（log.error 可 grep），未接 metrics/告警平台。
