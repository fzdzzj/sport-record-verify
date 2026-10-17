# TASK-262 立项任务书（批 G-4：统一性对齐群 G6+G12+G14+G15+G16+G17）

> 状态：**已封盘（ACCEPT 通过）**（2026-10-17 指导侧终裁；封盘笔哈希见 handoff §1；FULL 档——指导亲核三块 + 独立复核五块双 PASS 零阻断，七门禁实测全绿，外部终验待推送后 CI 监督）

## 1. 证据链（指导侧亲验坐实，2026-10-17，TASK-261 补记笔 54e3c50 后实测行号）

1. **G6 主项·8 处局部 handler 遮蔽公共层**：common GlobalExceptionHandler L51-63 已含 BizException→HTTP 映射（TASK-255 E21 落地：1001→401 / 1002→403 / 3001→404，其余 200）；但 8 处局部 @ExceptionHandler(BizException) 优先级遮蔽公共层——record：RecordController L334-343 / LikeController L120-129 / GoalController L131-142；user：UserController L130-137 / NotificationController L235-244 / FriendController L178-187 / AuthController L246-253；leaderboard：LeaderboardController L124-133。映射集参差坐实：同一 3001（RECORD_NOT_FOUND）record 路径→200（局部恒 200）、goal 路径→404、verify 路径→404；
2. **G6 公共层激活前提**：五服务 Application `scanBasePackages = "com.sportverify"`（record L19 / user L14 / verify L19 / leaderboard L21 / mapmatch L19）——common 包在组件扫描范围内，删局部副本后公共层必然接管（gateway 为 WebFlux 体系无关本项）；
3. **G6 契约 delta 恰两类（1002/3001 主代码抛点 45 行全量核对）**：①record 双控制器 3001 路径（RecordController L323 / LikeController L109 抛点）现状局部恒 200 → 收敛后公共层 404；②AuthController 路径 1002（AuthService L160 账号锁定 / L171 禁用）现状 200 → 收敛后公共层 403；其余五控制器零 delta（Goal 映射全同 / Leaderboard L113 抛 1002 已被公共层同值覆盖 / User·Notification·Friend 映射已覆盖或控制器零抛点）；
4. **G6 前端透明性**：web 响应拦截器（client.ts L123-181）成功分支只看 body.code、error 分支透传 body.code/message（err.status 仅附注不参与分支判定）——403/404 HTTP 状态变化对前端零影响；401 refresh 逻辑不受影响；
5. **G6 测试适配面**：4 测试类 11 个局部 handler 方法级用例——RecordControllerTest L334-359（3）/ LikeControllerTest L127-152（3）/ GoalControllerTest L154-172（3，含 404 形态）/ NotificationControllerTest L199-211（2）；全仓 standaloneSetup / setControllerAdvice / WebMvc 切片测试仅 1 处（LeaderboardControllerDailyTest L41，需适配）；common GlobalExceptionHandlerTest L136-168 既有公共层断言先例可循；
6. **G12**：leaderboard CacheConfig L49 `CACHE_TTL = Duration.ofMinutes(5)` 恒定（L77 Caffeine L1 与 L87 Redis L2 共用同一值）；jitter 范式先例 RuleCacheService L222-231（base + ThreadLocalRandom.nextLong(-amp, amp+1)）；Boot 3.2.4 → spring-data-redis 3.2.x，TtlFunction API 可用；
7. **G14**：api PageResult L12-14 Javadoc 明示「跨服务分页统一使用本类型替代 MP 的 Page；各服务在 Controller/Service 层做一次薄转换」；RecordController 三处外抛 MP Page——L111-127 `/my`（Result<Page<RecordSummaryDTO>>）/ L138-145 `/feed`（Result<Page<FeedItemDTO>>）/ L253-260 `/{id}/points`（Result<Page<TrackPointDTO>>）；前端早已按 PageResult 契约消费（client.ts L76-81 接口定义 + L225 / L298 / L327 三端点消费类型），web 源码 `.pages` 字段零引用——纯后端薄转换零前端影响；
8. **G15**：user LikeEventConsumer L213-216 与 NotificationEventConsumer L216-219 两处私有 dedupKey 重复定义 `"notification:event:"` 前缀；verify VerifyEventOutboxMapper L30-100 状态字面量 SQL 内联六处（'SENT'/'PENDING' L32-33、'EXHAUSTED' L47-48、markSentBatch L61-69、selectExhausted L77-78、replayExhausted L88-89、cleanSent L100）；
9. **G16**：CacheConfig L89 `serializeValuesWith(...JdkSerializationRedisSerializer)` vs RuleCacheService L196 `objectMapper.writeValueAsString` JSON 字符串序列化——同仓 Redis 值序列化双轨分裂；
10. **G17 死面链（四处）**：api RecordApi L52-53 listPoints 契约声明 + RecordApiFallback L43-44 降级实现 + InternalRecordController L47-51 内部端点 + SportRecordService L493-503 方法实现（L494 注释「用户端读路径」已陈旧）——主代码零调用；活契约 getRecord 零触碰（VerifyService L163 / L293 + AppealReconcileService L118 三处活调用坐实）；VerifyService L118-124 注释自证 getRecordWithPoints 取代 getRecord+listPoints 两次 Feign；TASK-173 handoff L111「不得据此推断旧接口可移除」先例突破说明——该约束时点为 getRecordWithPoints 刚落地兼容观察期，现况主代码零调用 + 注释自证取代完成 + 前端零消费，突破依据充分。

## 2. 改动白名单（仅限下列文件，双向全等）

1. `record-service/src/main/java/com/sportverify/record/controller/RecordController.java`（R1 删局部 handler + R3 三处 PageResult 薄转换）
2. `record-service/src/main/java/com/sportverify/record/controller/LikeController.java`（R1）
3. `record-service/src/main/java/com/sportverify/record/controller/GoalController.java`（R1）
4. `record-service/src/main/java/com/sportverify/record/controller/InternalRecordController.java`（R6 删 listPoints 端点）
5. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（R6 删 listPoints 方法）
6. `record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java`（R1 用例改写 + R3 形态断言 + R6 端点用例处置）
7. `record-service/src/test/java/com/sportverify/record/controller/LikeControllerTest.java`（R1）
8. `record-service/src/test/java/com/sportverify/record/controller/GoalControllerTest.java`（R1）
9. `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`（R6 listPoints 用例处置与等量补位）
10. `user-service/src/main/java/com/sportverify/user/controller/UserController.java`（R1）
11. `user-service/src/main/java/com/sportverify/user/controller/NotificationController.java`（R1）
12. `user-service/src/main/java/com/sportverify/user/controller/FriendController.java`（R1）
13. `user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java`（R1）
14. `user-service/src/main/java/com/sportverify/user/mq/LikeEventConsumer.java`（R4 前缀常量收拢）
15. `user-service/src/main/java/com/sportverify/user/mq/NotificationEventConsumer.java`（R4）
16. `user-service/src/test/java/...`（R1 控制器测试适配，文件由执行侧按既有测试布局选定并在 C-01 前登记增补）
17. `leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java`（R1）
18. `leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java`（R2 jitter + R5 JSON 序列化）
19. `leaderboard-service/src/test/java/...`（R1 LeaderboardControllerDailyTest 适配 + R2/R5 断言落点，执行侧选定登记）
20. `verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java`（R4 状态常量收拢）
21. `verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java`（R6 never() 断言处置）
22. `api/src/main/java/com/sportverify/api/record/RecordApi.java`（R6 删 listPoints 声明）
23. `api/src/main/java/com/sportverify/api/record/RecordApiFallback.java`（R6 删降级实现）
24. 台账：`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md`、`work/mailbox/HANDOVER.md`、`work/mailbox/tasks/TASK-262/spec.md`、`work/mailbox/tasks/TASK-262/handoff.md`

零触碰：common 模块（GlobalExceptionHandler 零改动——公共层 TASK-255 已收口，本笔只删局部副本）、gateway/mapmatch 服务 src/test、全部 pom.xml、package.json、sql/、scripts/、web/、docker-compose*.yml、.github/、状态机/MQ/批量/索引参数、RecordApi 其余契约方法（getRecord / getRecordWithPoints 等零改动）、user/record 服务层业务方法（FriendService / NotificationService / RecordLikeService / GoalService 零改动；SportRecordService 仅 R6 删除项）、AuthService（1002 抛点在服务层属既有语义零改动）。

## 3. 修法要求

- **R1（G6 主项·删 8 处局部 handler 收敛公共层）**：
  - 删除 8 处局部 @ExceptionHandler(BizException)（§1 第 1 条全列），异常翻译全部收敛 common GlobalExceptionHandler；
  - 契约 delta 恰两类（§1 第 3 条）：record 双控制器 3001 路径 200→404、AuthController 1002 路径 200→403；两类 delta 均为语义修复（资源不存在应 404、凭证/权限拒绝应 403），非回归；
  - 测试适配：4 测试类 11 用例改写为公共层等价断言——standaloneSetup 增 setControllerAdvice(GlobalExceptionHandler) 形态，断言从「局部 handler 恒 200」升级为「公共层映射后 HTTP 状态 + body 双断言」，语义升级非删弱；LeaderboardControllerDailyTest L41 既有 advice 注册适配本笔映射；两类 delta 各新增 ≥1 条契约用例（record 3001→404 / Auth 1002→403）；
  - 日志域化前缀消失（局部 handler 内 log.warn 域化文案随删除收敛公共层统一文案）登记为有意收敛；
- **R2（G12·L2 Redis TTL jitter）**：CacheConfig L2 Redis entryTtl 引入 jitter 对齐 RuleCacheService L222-231 范式（TtlFunction 形态或等价实现，执行侧选定并在 C-01 前登记）；L1 Caffeine 保持恒定 5min（本地缓存无集中过期雪崩面）；jitter 幅度建议 ±60s 量级（值域须覆盖 5min 基值，执行侧定档登记）；TTL 值域断言 ≥1 条（多次取值均落在 [base-amp, base+amp] 且不恒定）；
- **R3（G14·三处 PageResult 薄转换）**：RecordController `/my`、`/feed`、`/{id}/points` 三处返回类型 MP Page → api PageResult，控制器层一次薄转换（new PageResult(current, size, total, records) 形态，字段语义对齐）；service 层零改动；三端点各 ≥1 条 PageResult 形态断言（records/total/current/size 字段 + 序列化无 `pages` 字段）；
- **R4（G15·常量收拢）**：`"notification:event:"` 前缀收拢单一常量（同包既有常量类或消费侧公共常量落点，零新增文件优先，执行侧选定登记）；VerifyEventOutboxMapper 六处状态字面量收拢为编译期常量拼接（'SENT'/'PENDING'/'EXHAUSTED' 三值常量化，SQL 语义逐字不变——以测试或既有 IT 断言 SQL 文本等价）；
- **R5（G16·L2 序列化统一 JSON）**：CacheConfig L89 JdkSerializationRedisSerializer → JSON 序列化（对齐 RuleCacheService L196 范式；GenericJackson2JsonRedisSerializer 或等价执行侧选定登记）；**缓存名/键前缀升版**（如 `leaderboard:overall` → `leaderboard:overall:v2` 形态，执行侧定档登记）规避旧 JDK 二进制条目 JSON 反序列化读失败穿透——旧条目 5min TTL 自然过期孤儿化；序列化往返断言 ≥1 条（写读等价）；
- **R6（G17·listPoints 死面删除）**：删四处死面（RecordApi 声明 + Fallback 实现 + InternalRecordController 端点 + SportRecordService 方法）；getRecord / getRecordWithPoints 零触碰；测试处置：VerifyServiceTest L186 never() 断言（针对已删契约的 mock 断言）随删除处置、SportRecordServiceTest 既有 listPoints 用例删除——**record surefire 删减须等量补位**（R1/R3 新增用例须 ≥ 删除数，保只增不减红线）；InternalRecordController 端点删除后如控制器测试有用例一并处置。

## 4. 门禁与验证要求（FULL 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（经 `D:\git\Git\usr\bin\bash.exe --login`），跨 api/record/user/leaderboard/verify 五模块 → 七模块全量逐位验证，surefire 基线 **63/78/218/318/202/109/35**（TASK-261 新基线，common/gateway/user/record/verify/leaderboard/mapmatch）只增不减；
- 契约门（`--open=TASK-262 --baseline=<开工基线>`，handoff §2 逐行列全改动集）；token 门 29 项只增不减（期望值口径：**C-02 树 = 基线 + 29**，handoff 自枚举效应，TASK-261 教训直写）；词面门模式文件法 + 探针阳性对照；checkstyle delta=0（本笔多服务主类有 diff，静态门以纯净 worktree 双向坐实）；`git diff --check` 净；mapmatch 照跑（本笔零 mapmatch 文件）；
- 双提交 C-01（业务/测试）/ C-02（台账）；`git status --porcelain` 收口为空。

## 5. 受限声明（预登记）

1. G6 前端透明性为静态核对（client.ts 消费面代码走读），无真实浏览器回归验证（沿 TASK-255 E21 先例）；
2. G12 jitter 收益（防集中过期雪崩）为范式对齐推论——overall 缓存单 key 无批量过期面，实效有限，如实登记；
3. G16 旧条目孤儿化依赖 5min TTL 自然过期，无真实 Redis 混存实测（JDK 旧条目与 JSON 新条目共存窗口内旧 key 读失败路径未实测）；
4. G17 死面判定为静态 grep 依据（主代码零调用 + 注释自证），无运行时调用观测；
5. Nacos 运行时配置未核（沿 G 系口径）。

## 6. 验收判据

C-01+C-02 落库、七门禁全绿、白名单双向全等、R1 两类 delta 契约用例（record 3001→404 / Auth 1002→403）+ 11 用例公共层等价改写全绿、R2 TTL 值域断言、R3 三端点 PageResult 形态断言、R4 SQL 等价断言、R5 序列化往返断言、R6 record surefire 等量补位只增不减；独立复核五块 PASS 后指导侧终裁；CI 外部终验读数由补记笔登记（随下一批次推送）。
