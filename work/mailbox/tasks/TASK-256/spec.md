# TASK-256 任务书（spec）

> **TASK**：E 系批4b-1——后端治理八项（E15 登录锁定兜底阈值回归 + E18 补偿 MQ 假成功 + E19 批量好友自调用绕事务 + E20 STOMP token type 缺校验 + E22 终判 DTO pass 必填 + E25 R2 首区间伪尖峰 + E28 X-Request-Id 净化 + E30 昵称整行回写，FULL 档）
> **开工基线**：`<立项笔哈希——C-02 回填>`（main，立项笔后工作树干净）
> **方案**：2026-10-16 E 系批4b 指导侧立项侦察（12 项逐项亲验全部坐实），用户拍板「批4b 测试可观测」立项；批4b 切分为 4b-1 后端八项（本课题）+ 4b-2 前端三项（TASK-257，串行第二批）+ E13 拆出（真实 PostGIS 库复验另立课题，拟 TASK-258）
> **档位**：FULL（E15/E20 触碰认证锁定与 WS 会话凭证安全语义、E22 触碰 api 模块契约面与终判不可逆路径、E28 触碰网关+common 全链路追踪头、E19 触碰好友关系两写原子性——禁降级；多模块 api/common/gateway/user/record/verify；离线全量逐位门禁 + 独立复核五块）
> **状态**：已立项待派发（2026-10-16 立项笔落库，两件套齐，实施派发包已生成待用户派发）

## 1. 背景与侦察结论（2026-10-16 指导侧亲验，八项全部坐实）

- **E15 坐实（配置回归）**：TASK-193 已将 `app.auth.lock.threshold` 语义升级为「跨 IP 累计全账号兜底阈值，默认 5→20」并新增 `ip-threshold`（默认 5，单 (phone,ip) 组合锁）；`AuthService` L84 `@Value("${app.auth.lock.threshold:20}")` 代码默认 20。但 `user-service/src/main/resources/application.yml` L78 仍显式 `threshold: 5`（L77 注释「对齐 AuthService 原 FAIL_THRESHOLD」为 TASK-193 前旧口径）——yml 覆盖把兜底阈值打回 5：任意来源 5 次失败即锁全账号 15min，F11 账号 DoS 回归。`AuthServiceTest` L115 显式 `lockThreshold = 20` 对齐兜底口径；README L223 仍为旧单维度表述「达阈值（5）」（L222 双维度表述正确无数字）。
- **E18 坐实（补偿假成功）**：`RecordEventProducer.publishSubmitted(recordId, userId)` boolean 变体（L37-42）以 `boolean[] accepted` 承载结果，`asyncSend` 的 onFailure 在 RocketMQ 回调线程晚于 return 求值——Broker 不可达时返回值仍 true（仅同步提交异常经 catch 置 false）。`VerifyDegradeService` L119 与 L146 两处补偿决策点依该返回值记 `republished++`：前置探活 `verifyApi.health()` 只覆盖 verify 服务可用性，MQ Broker 独立故障时滞留 VERIFYING 记录被记「已重发」、永不 `degradeToManualReview`，仅回调线程 log.error 无告警计数。
- **E19 坐实（自调用绕事务）**：`FriendService.accept`（L170）有 `@Transactional(rollbackFor = Exception.class)` 覆盖「申请状态更新 + friendship 插入」两写（L167-168 Javadoc 自证）；`batchTransition`（L292-312）循环内直调 `accept(id, caller)/reject(id, caller)`——同类自调用不经 Spring 代理，批量路径两写失原子（全仓无 exposeProxy/AopContext）。
- **E20 坐实（token type 缺校验）**：`JwtUtil.parse`（L109-111）仅验签名与时效，type 校验只在 `parseRefresh`（L117-123，REFRESH 方向）；`StompConnectAuthInterceptor` L63 用 `parse` 建会话——refresh token 可替代 access 建 WS 会话（凭证窗口放大，对照网关 AuthGlobalFilter 强制 type=ACCESS）。
- **E22 坐实（终判静默默认）**：`AppealReviewDTO`（api 模块）L26 `private boolean pass` 原生类型，漏传默认 false 静默走「维持拒绝」终判——verdict 回写 + outbox 推送不可逆且无参数错误提示；operator 已有 @NotBlank 而 pass 无校验（`VerifyController` L62 已 @Valid，加注解即激活）。调用点 `VerifyService` L257/L258/L290 三处 `dto.isPass()`。
- **E25 坐实（首区间伪尖峰）**：`TrackPreprocessor` L67-68 自 i=1 起回填 dtSeconds/speed（首点保持缺省 0 哨兵）；`R2AccelRule.evaluate` L41 循环自 i=1 以首点为 prev——`a=(speed[1]-0)/dt[1]` 高频采样必超阈（默认 3.0 m/s²），伪尖峰计入 count 使 minCount=3 有效门槛降为 2 且证据计数被污染（既有 R2 断言在 `VerifyEngineTest`）。
- **E28 坐实（追踪头零校验）**：common `TraceIds.resolveOrCreate`（L33-39）与 gateway `RequestIdGlobalFilter.resolveOrCreate`（L44-50，网关私有镜像实现）均仅 null/trim/空判——客户端可控任意长度/任意字符的 X-Request-Id 经网关透传至全部 MVC 服务、MQ userProperty 与全链日志（挂他人链路/放大日志/日志注入面）；既有契约测试固化「原样复用」。
- **E30 坐实（整行回写丢失更新）**：`UserController.updateProfile` L85-90 `selectById → setNickname → updateById(user)`——实体全字段非空触发整行 UPDATE 回写 password_hash/role/status 等，与改密/撤销管理员并发的毫秒级窗口可回写旧密码哈希或旧 ADMIN（无 @Transactional 无 @Version，失败模式为安全语义回退）。

## 2. 改动文件白名单（22 项，双向全等硬约束）

业务/测试 20 项：

1. user-service/src/main/resources/application.yml（E15 R1）
2. README.md（E15 R1 文档口径同步 L223）
3. user-service/src/test/java/com/sportverify/user/auth/service/LoginLockoutConfigTest.java（E15 R1 资源断言，新建）
4. record-service/src/main/java/com/sportverify/record/mq/RecordEventProducer.java（E18 R2 同步变体）
5. record-service/src/main/java/com/sportverify/record/service/VerifyDegradeService.java（E18 R2 决策点替换）
6. record-service/src/test/java/com/sportverify/record/service/VerifyDegradeServiceTest.java（E18 R2 扩展）
7. user-service/src/main/java/com/sportverify/user/service/FriendService.java（E19 R3 自代理）
8. user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java（E19 R3 适配+扩展）
9. user-service/src/main/java/com/sportverify/user/auth/util/JwtUtil.java（E20 R4 parseAccess）
10. user-service/src/main/java/com/sportverify/user/config/StompConnectAuthInterceptor.java（E20 R4 改调 parseAccess）
11. user-service/src/test/java/com/sportverify/user/config/WebSocketNotificationConfigTest.java（E20 R4 扩展；如另立 StompConnectAuthInterceptorTest.java 新文件则以其替换本项——条件项，C-01 前定档登记）
12. api/src/main/java/com/sportverify/api/verify/dto/AppealReviewDTO.java（E22 R5）
13. verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java（E22 R5 三调用点）
14. verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java（E22 R5 扩展）
15. verify-service/src/main/java/com/sportverify/verify/algorithm/rule/R2AccelRule.java（E25 R6）
16. verify-service/src/test/java/com/sportverify/verify/algorithm/VerifyEngineTest.java（E25 R6 既有 R2 断言适配+扩展）
17. common/src/main/java/com/sportverify/common/trace/TraceIds.java（E28 R7 净化）
18. common/src/test/java/com/sportverify/common/trace/TraceIdFilterTest.java（E28 R7 适配+扩展）
19. gateway-service/src/main/java/com/sportverify/gateway/trace/RequestIdGlobalFilter.java（E28 R7 网关镜像同规则）
20. gateway-service/src/test/java/com/sportverify/gateway/trace/RequestIdGlobalFilterTest.java（E28 R7 适配+扩展）
21. user-service/src/main/java/com/sportverify/user/controller/UserController.java（E30 R8 单列更新）
22. user-service/src/test/java/com/sportverify/user/controller/UserControllerTest.java（E30 R8，新建）

台账 2 项（C-02 阶段）：work/mailbox/tasks/TASK-256/spec.md、work/mailbox/tasks/TASK-256/handoff.md。

零触碰声明（白名单外全仓零 diff）：**AuthService.java（E15 仅 yml/文档/新测试，代码默认 20 已正确）**、AuthController 与其余七个含局部 BizException handler 的 controller、FriendController（批量端点签名不变）、FriendService.reject 方法本体（单写无事务需求现状保留）、RecordEventProducer 既有异步 publishSubmitted 两变体签名与行为（主提交路径 best-effort 约定不动）、VerifyEngine 规则链与其余规则（R1/R3/R4/R5）、TrackPreprocessor.java（E25 修在 R2 消费侧，预处理零改动）、WebSocketNotificationConfig.java（端点/Origin 白名单/心跳零改动）、NotificationController/FriendController/UserController 其余端点、verify/leaderboard/mapmatch 服务业务代码、全部 pom.xml、web/、sql/、scripts/、docker-compose*、.github/、PLAN.md、HANDOVER.md、BACKLOG.md（子 agent 禁改三看板）。

## 3. 实现要求（判别式）

### R1（E15）yml 兜底阈值回归修复

- `application.yml` L78 `threshold: 5` → `threshold: 20`；L77 注释订正为全账号兜底阈值口径（跨 IP 累计达此数锁全账号，TASK-193 语义与代码默认 20 对齐；单 (phone,ip) 组合阈值 ip-threshold 默认 5，yml 未显式覆盖由代码默认生效）；
- `README.md` L223 由「窗口（15min）内连续登录失败达阈值（5）」订正为双维度数值口径（组合阈值 5 / 跨 IP 兜底 20），与 L222 双维度表述及 ADR-0007 对齐；
- 新建 `LoginLockoutConfigTest`（沿 GatewayProbeTest 资源断言先例）：直读 classpath `application.yml` 解析属性树，断言 `app.auth.lock.threshold` 为 20（防 yml 再度覆盖回归）；不断言 ip-threshold（yml 未显式登记，走代码默认路径）。

### R2（E18）补偿路径同步发送

- `RecordEventProducer` 新增 `publishSubmittedSync(Long recordId, Long userId)` boolean：事件构造与 traceId header 装配抽私有共用段（与异步变体共享，防重复），`rocketMQTemplate.syncSend` try/catch——成功 true、异常 log.error 后 false（不抛出）；
- `VerifyDegradeService` L119/L146 两决策点改调同步变体——返回值真实反映提交结果，false 走 `degradeToManualReview`/继续等待既有分支不变；
- 既有异步 `publishSubmitted` 两变体签名与行为零改动（主提交路径异步 best-effort 约定不动；补偿路径同步化为有界偏差，§5 登记）。

### R3（E19）批量路径经代理

- `FriendService` 注入 @Lazy 自代理（实现形态二选一：显式构造器替换 @RequiredArgsConstructor + @Lazy 自参数；或保留 @RequiredArgsConstructor 另加 @Autowired @Lazy 非 final 自字段——C-01 前定档并在 handoff 登记），`batchTransition` 循环内 accept/reject 改经 `self.` 调用——每条独立事务、半成功语义保持，Javadoc 写明自代理原因（同类直调不过事务代理）；
- `FriendServiceTest`：构造适配（纯 JVM 单测将 self 置为 service 本体，等价今日直调语义）+ 新增结构断言用例：self 置为 mock，`batchAccept` 逐条验证经 `self.accept` 委托（证明代理路径存在）；既有断言不弱化。

### R4（E20）STOMP 强制 ACCESS

- `JwtUtil` 新增 `parseAccess(String token)`：parse + type==ACCESS 校验（非 ACCESS 抛 JwtException，与 parseRefresh 对称），返回 userId（sub）；
- `StompConnectAuthInterceptor` L63-64 改调 `parseAccess`（异常仍走既有 MessagingException 拒绝分支），Javadoc 注明与网关同口径拒绝 refresh 建会话；
- 测试三用例：refresh token 建会话被拒 / access token 通过且 Principal 正确 / 无效 token 拒绝（落 WebSocketNotificationConfigTest 扩展或新建拦截器测试——白名单第 11 条条件项）。

### R5（E22）pass 必填

- `AppealReviewDTO` `private boolean pass` → `private Boolean pass` + `@NotNull(message = "终判结论 pass 不能为空")`，Javadoc 注明漏传 400 而非静默维持拒绝；
- `VerifyService` L257/L258/L290 三处 `dto.isPass()` → `Boolean.TRUE.equals(dto.getPass())`；
- `VerifyController` L62 已 @Valid 零改动（校验失败通道与既有 operator @NotBlank 同路径）；前端 reviewAppeal 恒显式传 pass，web 零改动；
- 测试：VerifyServiceTest 既有 true/false 方向断言保持 + 新增 null 防御语义（service 层 null→false 仅为防御兜底，权威拦截在 @Valid）；VerifyControllerTest 新增缺 pass → 400 用例。

### R6（E25）R2 跳过首区间

- `R2AccelRule.evaluate` 循环改自 i=2 起：首区间（i=1）的 prev 为首个采样点，其 speed/dt 为未计算哨兵——以哨兵参与加速度计算伪造必超阈尖峰；Javadoc 写明边界语义（minCount 恢复真实门槛、证据计数净化）；
- 实施侧开工先复验 TrackPreprocessor 首点不回填（读码确认）；若首点存在真实回填则与判别式前提不符，触发停止条件汇报；
- `VerifyEngineTest`：既有 R2 用例适配（如有依赖首尖峰计数的黄金值订正——断言语义不得弱化）+ 新增三判别：首区间超阈不计入 / 三真实尖峰仍命中 / 两真实尖峰 + 一首区间尖峰不命中（门槛恢复）。

### R7（E28）X-Request-Id 净化

- `TraceIds.resolveOrCreate` 与 `RequestIdGlobalFilter.resolveOrCreate`（网关私有镜像）同规则：入站 ID 仅当长度 1..64 且字符限于字母/数字/下划线/连字符时沿用，否则 `newId()` 重新生成——不拒绝请求、不打断链路（净化正则字面量落代码实现，本任务书与台账以文字描述承载）；
- 两实现保持镜像口径（网关零 common 依赖边界不变，各自注释注明双实现同规则）；
- MQ 消费续链（消费者侧 resolveOrCreate 调用面）同受益零改动；
- 测试：超长（65 字符）/非法字符（空格、中文、控制字符）→ 重新生成；边界 64/1 合规沿用；合法既有形态（如 32 位十六进制）沿用；既有「原样复用」断言中不合规入参用例翻转为「重新生成」断言（TraceIdFilterTest 与 RequestIdGlobalFilterTest 双侧对称）。

### R8（E30）昵称单列更新

- `UserController.updateProfile` L85-90 改为：selectById 判存在（保留 USER_NOT_FOUND 前置）→ `userMapper.update(null, new LambdaUpdateWrapper<User>().eq(User::getId, userId).set(User::getNickname, nickname))` 单列 UPDATE → DTO 构造用局部变量携带新昵称（不整行回写、不引 @Transactional/@Version——单 UPDATE 天然原子）；
- 新建 `UserControllerTest`：昵称单列更新断言（LambdaUpdateWrapper 断言前需 TableInfoHelper.initTableInfo 注册 User 实体——沿 TASK-221 类序差异教训先例）+ 昵称校验（空/超 32）与 USER_NOT_FOUND 既有行为保持。

## 4. 测试与门禁要求

新增/扩展单测判别式（全部纯 JVM，零 Testcontainers/零新依赖）：

- E15：LoginLockoutConfigTest 资源断言（yml threshold==20）；
- E18：VerifyDegradeServiceTest——同步变体 true → republished 计数与日志分支 / false → degradeToManualReview 被调（既有 mock 面从 publishSubmitted 迁移到 publishSubmittedSync，断言语义不弱化）；
- E19：FriendServiceTest——self mock 委托结构断言 + 既有批量用例构造适配；
- E20：三用例（refresh 拒 / access 过 / 无效拒）；
- E22：VerifyServiceTest null 防御 + VerifyControllerTest 缺 pass 400；
- E25：VerifyEngineTest 三判别（首区间不计 / 三真实命中 / 两真实+一伪不命中）；
- E28：双侧四类用例（超长/非法/边界/合法沿用）对称；
- E30：UserControllerTest 单列更新 + 校验保持。

七门禁全绿（唯一入口 scripts/verify/mvn-verify.sh；契约门 --open=TASK-256 --baseline=&lt;开工基线&gt;；token 门 29 项只增不减；词面门模式文件法 + 探针阳性对照）；surefire 只增不减（开工基线 59/75/205/302/195/108/35——本课题增量预期：common +3~6、gateway +3~6、user +8~12、record +2~4、verify +4~7，leaderboard/mapmatch 持平凭白名单外零 diff 论证不变）；checkstyle delta=0（record-service 4 项 pre-existing 于零触碰 SportRecordService 基线持平）。

## 5. 预注册受限与授权

1. E15 Nacos 运行时覆盖未核（本机无 Nacos 环境）——修复以仓库内配置面为界，运行时覆盖面如实登记。
2. E18 syncSend 阻塞为有界偏差：补偿为后台定时任务（非请求主路径），阻塞上限为 Producer 自身发送超时；主提交流路径保持异步 best-effort 零改动。
3. E19 事务性修复为结构性（经代理调用），纯 JVM 单测无法验证真实回滚——以结构断言（mock self 委托验证）+ @Transactional 注解保持承载，真实事务回滚未实测登记。
4. E20 修复后 WS 会话凭证窗口与网关同口径收敛，真实浏览器 STOMP 流转未跑（无既有 E2E 覆盖可回归）。
5. E28 净化规则对既有合法调用方零影响（前端未自设 X-Request-Id，静态核对）；上游注入方（如未来 ingress）超长 ID 会被重生成——行为面如实登记。
6. E25 高频采样误拒改善不做比率/延迟声明（行为修复不量化）。

## 6. 纪律

- 双提交：C-01 实施（恰上述 20 项业务/测试文件，条件项按 C-01 前定档执行）+ C-02 台账（spec/handoff 纯追加回填，含开工基线占位符回填）；提交消息 type(scope): description（TASK-256）。
- 新文件纯 LF + 完整末尾换行；改文件保持既有行尾；git diff --check 净。
- 禁 stash / add -A / 改归档 / 动 .codex 与 .trae；临时件放 .trae/tmp/ 用后删。
- Bash 脚本仅经 D:\git\Git\bin\bash.exe 执行；无数据库操作（本课题零 SQL 改动）。
- 停止条件：任一判别式无法按本任务书落地、需触碰零触碰面（尤其 AuthService/TrackPreprocessor/既有异步变体）、TrackPreprocessor 首点实况与 R6 前提不符、offline 基线回退、checkstyle 违规增加——立即停止修改并汇报，不得自行变通。
