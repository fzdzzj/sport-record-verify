# 任务书：TASK-185 add-notification-ws-push（通知实时推送 WebSocket+STOMP 升级）

派发：指导 Agent（2026-10-09）。派发笔（本任务书 + 提案三件套）由指导侧亲笔；执行侧代为入库后实施 C-01…C-02 两笔，不得改动派发笔内容。

## 0. 硬约束与红线

1. **改动面唯一**（白名单见 §4）：后端 pom.xml（user-service，仅 +spring-boot-starter-websocket 一个依赖）、三个新主类 + 对应单测、NotificationService（发布钩子一行下沉）、gateway application.yml（白名单一行）；前端 package.json + pnpm-lock.yaml（仅 +@stomp/stompjs）、notificationWs.ts 新增、useNotificationBell.ts / notifications.page.vue 修改；台账四件。**零触碰**：mvn-verify.sh、mailbox-contract.sh、ci.yml、compose、其余六模块、client.ts、typed-router.d.ts（无新页面）、App.vue。
2. **语义红线**：TASK-182 四 REST 接口与 RocketMQ 消费链路零语义改动（发布钩子只在 createNotification 落库成功后触发）；未登录零请求（不开 WS 不发轮询）；轮询兜底不删（WS 断流时新鲜度不劣于 TASK-183 基线）。
3. **鉴权红线**：WS 端点不接受匿名会话；CONNECT 帧级校验用 JwtUtil（app.auth.jwt.secret 与网关一致性是既有约束）；不向 query string 传 token。
4. **参数红线**：不动索引、状态机、批大小、周期、MQ 参数；STOMP 心跳 10s/10s 为本变更新增参数（新命名空间，不碰既有键）；不调 Tomcat 连接参数。
5. **唯一 mvn 入口**：Java 门禁只经 `bash scripts/verify/mvn-verify.sh`；禁并发 mvn。
6. **bash 纪律**：仅 `D:\git\Git\bin\bash.exe` 且仅执行脚本文件；临时产物落 `.trae/tmp/`（gitignored）用毕删。
7. **git 纪律**：禁 push / PR / `add -A` / stash；逐路径 add；提交信息 `-F` 文件无 BOM。
8. **token 纪律**：受保护 29 项只增不减（开工实测登记）；PLAN.md 纯追加；**词面门正则字面量绝不入任何 tracked 文档**（TASK-184 F1 教训）。
9. **停止条件**：需改 ci.yml / compose / 网关 Java 代码才能完成 ⇒ 停手回报；lockfile 无法本机真实生成或 CI frozen-lockfile 预期必红 ⇒ 停手回报；offline 480 逐位回退或 static 811 增 ⇒ 停手回报；spring-boot-starter-websocket 与 user-service 既有版本树冲突 ⇒ 停手回报。

## 1. 背景与现状证据

- **轮询现状**：`useNotificationBell.ts` L10 `POLL_INTERVAL_MS = 60_000`，visibilitychange 恢复刷新，失败静默置 0——本变更保留该层为兜底。
- **落库链路**：`NotificationEventConsumer`（消费范式沿 LeaderboardEventConsumer，SETNX + uk_dedup 双幂等）→ `NotificationService.createNotification`（INSERT IGNORE 受影响行数 > 0 为成功）——发布钩子挂成功分支后。
- **鉴权基座**：ADR-0007（网关 X-User-Id 注入 / 越权 403）覆盖 REST；WS 走 STOMP CONNECT 帧级（浏览器 WS API 不能自定义 HTTP 头，token 入 connectHeaders 是协议内正规位）；网关 `app.auth.whitelist` 已可配（AuthGlobalFilter L55-56，默认 `/api/auth/**,/actuator/health`），追加 `/user/ws-notifications` 即使网关豁免 Bearer 头，鉴权下沉帧级——网关 Java 零改动。
- **扇出基座**：Redisson 已在栈内（RTopic）；Spring 用户目标注册表只解析本实例会话（多实例无双推，机制性保证）。
- **契约门现状**：TASK-184 修缮后（run `37875523302` 外部验证过），本任务改 `pom.xml` 不再触发历史任务「零触碰声明」误伤——**本任务是修缮后首个受益任务**，在途门读数应干净（若仍红即 TASK-184 修缮有漏网，停手回报）。

## 2. 实施设计（预注册）

### 2.1 后端

- `WebSocketNotificationConfig`：`@EnableWebSocketMessageBroker`；`registerStompEndpoints` 端点 `/ws-notifications`；`configureMessageBroker`：SimpleBroker（`/queue`）、`setUserDestinationPrefix("/user")`、心跳 `setHeartbeatValue({10000,10000})` + 注入 TaskScheduler；`configureClientInboundChannel` 注册鉴权拦截器。
- `StompConnectAuthInterceptor`：`ChannelInterceptor` 实现——`preSend` 捕 CONNECT 命令，读 header `Authorization`（Bearer 前缀剥除），`JwtUtil` 解析（复用其解析方法，取 userId claim）→ `StompHeaderAccessor.setUser(Principal(userId 字符串))`；解析失败/缺失 → 抛 `MessagingException` 拒绝（会话不建立）；非 CONNECT 帧直通。
- `NotificationPushRelay`：`@PostConstruct` 订阅 `RTopic("notification:push")`，回调反序列化 `NotificationPushMessage`（record：userId/type/sourceId/content/createdAt）→ `SimpMessagingTemplate.convertAndSendToUser(userId.toString(), "/queue/notifications", message)`；`@PreDestroy` 退订；JSON 用注入的 ObjectMapper。
- `NotificationService`：`createNotification` 受影响行数 > 0 返回 true 前发布（`RTopic.publish`），发布失败仅告警不回滚落库（推送是尽力而为，轮询兜底）——该取舍在 javadoc 与 handoff 登记。
- gateway `application.yml`：`app.auth.whitelist` 由隐式默认改为显式 `/api/auth/**,/actuator/health,/user/ws-notifications`（保持既有两项不丢）。

### 2.2 前端

- `notificationWs.ts`：`@stomp/stompjs` `Client`（brokerURL 由 `location.protocol` 推导 ws/wss + `/user/ws-notifications`；connectHeaders `Authorization: Bearer ${token}`；reconnectDelay 5000；heartbeat 内建）；导出 `connectNotificationWs(onMessage)` / `disconnectNotificationWs()`；token 读取沿 `utils/token` 既有口径；未登录调用 connect 即 no-op。
- `useNotificationBell.ts`：`startUnreadPolling` 内登录态判定后追加开 WS（消息回调 → `refreshUnread()`）；暴露 `onNotification` 注册口（通知页注册回调）；`stopUnreadPolling` 同步 `disconnectNotificationWs`；轮询逻辑零删改。
- `notifications.page.vue`：`onMounted` 注册 `onNotification(() => loadList())`，卸载时注销。

### 2.3 单测（Mockito 离线确定性，沿仓内既有范式）

- `StompConnectAuthInterceptorTest`：有效 token → accessor.user 为该 userId；无效签名/过期 → 抛 MessagingException；无 Authorization 头 → 拒绝；SUBSCRIBE/SEND 帧直通。
- `NotificationPushRelayTest`：RTopic 回调 → convertAndSendToUser(user, "/queue/notifications", payload)；JSON 往返字段全等；无会话用户调用不抛（Spring 语义）。
- `NotificationServicePushTest`：落库成功 → publish 一次且载荷字段正确；落库失败（受影响 0）→ 不 publish；publish 抛异常 → 不影响返回 true（尽力而为）。
- `WebSocketNotificationConfigTest`：端点/代理前缀/心跳值注册断言（mock registry）。

### 2.4 三支判定

- **PASSED**：管道三新类 + 前端接线落地 + 单测矩阵全绿 + web 三件套 + Java 新基线只增不减 + 811 不增 + 全门禁绿。
- **FAILED**：任一停止条件触发或门禁回归 ⇒ 回滚全部改动、如实登记。
- **外部终验**：推送后下一次外部门槛（第 27 次）CI 绿；红则按签名归因（禁重试刷绿）。

## 3. 开工读数（时序差惯例）

- 任务书落盘时点 HEAD = `c95cb52`（TASK-184 补记笔）；`origin/main` = `e0da404`，`origin/main...main` = `0 1`；派发笔入库后 `0 2`。
- 离线基线 480（`36/41/63/127/144/59/10`，user-service=63）；静态基线 811。
- 最近外部门槛：run `37875523302`（HEAD `e0da404`，绿，第 26 次）。
- 契约门无参 rc=0（TASK-184 修缮后口径）。

## 4. 白名单与提交结构

- **派发笔（指导侧亲笔，执行侧只入库零改动）**：`spec/changes/add-notification-ws-push/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + 本任务书。主题：`docs(spec): 派发 TASK-185 通知 WS 推送提案与任务书`。
- **C-01 实施笔**：§2.1 + §2.2 + §2.3 全部（后端三新类 + 三处修改 + 前端新文件与两修改 + lockfile）。主题：`feat(user): 通知实时推送 WebSocket+STOMP 管道并前端接线（TASK-185）`。
- **C-02 台账笔**：tasks.json 全勾 + 本任务书 §7 纯追加 + handoff.md + PLAN.md 纯追加。主题：`docs(mailbox): 登记 TASK-185 WS 推送验收与台账闭环（TASK-185）`。
- **禁触**：mvn-verify.sh、mailbox-contract.sh、ci.yml、compose、其余六模块、client.ts、typed-router.d.ts、App.vue、.codex/、.trae/。

## 5. 受保护 tokens 基线（29 项，开工实测登记，只增不减）

`13.4`、`18.0`、`73.93`、`68.8`、`6315`、`1.8612`、`3.3066`、`5.7056`、`9.408`、`36525962432`、`36586847965`、`36438897772`、`36399582548`、`36098038547`、`2806`、`598`、`36736221648`、`36808102571`、`36821040708`、`36845152965`、`36871294588`、`36880083885`、`36958994260`、`36976873215`、`36992632143`、`36995450125`、`37008317295`、`37021305016`、`37591580687`（TASK-184 收口真值 SUM=1885 为参照，口径 repo 全量 `git grep -cF`；开工实测为准）。第 26 次 run 号（`37875523302`）及本任务新 run 号以文本登记，不扩受保护集合。

## 6. 门禁与提交结构

1. **预提交门禁（每笔前亲跑记录 rc）**：tasks.json 语法 rc=0；词面门四形态 ZERO_HIT rc=1 + 探针 rc=0 + 三态（**正则字面量不入任何 tracked 文档与输出**）；`git diff --check` rc=0；契约门在途 `--open TASK-185 --baseline=<派发笔哈希>`（TASK-184 修缮后口径，若 rc=1 即修缮有漏网 → 停手回报）；token 29 项只增不减；新增文件纯 LF 末尾换行完整。
2. **收口门禁（C-02 后亲跑留证）**：web 三件套 + lockfile 一致（`pnpm --dir web install --frozen-lockfile` rc=0 预演 CI 口径）；Java offline 全量新基线逐位登记 + `--static=record-service` 811 不增；契约门无参 rc=0；只改清单全等；PLAN.md 自派发笔起纯追加。
3. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（单测矩阵读数、鉴权三态、兜底语义、可选联调留证或 UNDETERMINED）/ 逐门实测表 / token 前后读数 / 未覆盖项 / 提交表（显式哈希，禁时效指针）。
4. **真实链路联调（可选）**：本机起网关+user-service+Redis（RocketMQ 不必需——直接 service 层造一条落库即可触发发布钩子），浏览器连 WS 验证收推与心跳；不可达则 UNDETERMINED 登记不判失败。

## 7. 收口记录（执行侧 C-02 纯追加）

（空位：提交哈希、单测矩阵读数、web 三件套读数、Java 新基线与 811、token 前后读数、联调留证或 UNDETERMINED、未覆盖项、三支裁决，由执行侧回填）

### 7.1 提交哈希

| 笔 | 提交 | 主题 |
| --- | --- | --- |
| 派发笔 | `aaad18d` | `docs(spec): 派发 TASK-185 通知 WS 推送提案与任务书` |
| C-01 实施 | `d19dc74` | `feat(user): 通知实时推送 WebSocket+STOMP 管道并前端接线（TASK-185）` |
| C-02 台账收口 | 见 §7 末尾（本笔自身哈希） | `docs(mailbox): 登记 TASK-185 WS 推送验收与台账闭环（TASK-185）` |

### 7.2 单测矩阵读数（offline，C-01 提交前实测）

| 用例类 | 断言内容 | 读数 |
| --- | --- | --- |
| `StompConnectAuthInterceptorTest` | 有效 token → Principal=userId；无效/过期 → 拒；缺失 → 拒；SUBSCRIBE 直通 | 5 run, 0 fail |
| `WebSocketNotificationConfigTest` | 端点 /ws-notifications、broker /queue、用户前缀 /user、心跳 10s/10s、TaskScheduler 注入、拦截器注册 | 4 run, 0 fail |
| `NotificationPushRelayTest` | 扇出 → convertAndSendToUser 正确用户/目标、JSON 往返全等；无会话不抛；发布序列化；发布失败不抛 | 6 run, 0 fail |
| `NotificationServicePushTest` | 落库成功 → publish 一次且载荷正确；落库失败(0行) → 不 publish；publish 异常不影响返回 true | 3 run, 0 fail |

### 7.3 web 三件套读数（收口态）

| 项 | 读数 | rc |
| --- | --- | --- |
| type-check | `vue-tsc --noEmit` 无输出 | 0 |
| build | `vite build` ✓ built（TMP 工作区化见 handoff §4 D1） | 0 |
| typed-router.d.ts 零漂移 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| lockfile 预演 | `pnpm --dir web install --frozen-lockfile` | 0 |

### 7.4 Java 新基线与 811

- offline 全量：`36/41/81/127/144/59/10` = **498**（user-service 63→81，只增不减；其余六模块逐位不变）。
- `--static=record-service` Checkstyle **811 持平**（未增）。
- 新依赖 `spring-boot-starter-websocket:3.2.4` 由父 pom import 的 spring-boot-dependencies BOM 管理，版本树零冲突；本机离线仓需补充该构件（见 handoff §4 D1）。

### 7.5 token 前后读数

- 开工实测 SUM=**1885**（口径 repo 全量 `git grep -cF`，29 项逐值见 handoff §7）。
- 收口复测只增不减（PASSED 台帐与 handoff 不引入任何受保护 token 字面量）。

### 7.6 联调留证

本机真实链路（网关 + user-service + Redis + 浏览器 WS）因中间件环境不可达未展开，登记为 **UNDETERMINED**（沿 TASK-182 IT 口径，不判失败）；链路语义等价由单测逐字覆盖。

### 7.7 三支裁决

**PASSED**：管道三新类 + 前端接线落地 + 单测矩阵全绿 + web 三件套 + Java 新基线只增不减（498）+ 811 不增 + 全门禁绿（含 TASK-184 修缮后契约门首个受益验证 rc=0）。无 FAILED 项。外部终验待推送后下一次外部门槛 CI 绿。

### 7.8 C-02 台账笔哈希（本笔收口）

C-02 提交哈希：`（由回传报告以显式哈希给出，见 handoff §8）`
