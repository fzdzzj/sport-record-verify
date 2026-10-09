# 提案：add-notification-ws-push（通知实时推送 WebSocket+STOMP 升级——替 60s 轮询的即时刷新）

提案人：指导 Agent（2026-10-09）。状态：待派发。

## Why（为什么做）

TASK-183 通知铃铛的未读数新鲜度由 60s 轮询承载（`useNotificationBell.ts` L10），TASK-182/183 提案均把「实时推送（WS/SSE）在范围外」登记为未覆盖项。判定事件到达后用户最长 60s 才看到角标变化；通知时效性是通知中心的核心体验指标，本期补齐。

## 选型裁定：WebSocket + STOMP（选型以工程实质为标准，非实施成本）

- **演进路线需要双向管道**：运营面候选中「已读回执」是 client→server 通信——WS 管道天然就绪；选 SSE 意味着二期另建通道或迁移。
- **协议层收编横切面**：STOMP 子协议内建心跳（heart-beat 协商）、确认（ACK）、会话管理；浏览器端 `@stomp/stompjs` 原生带重连（reconnectDelay）与帧解析。SSE 路线需手写 fetch 流解析器 + 手写重连退避 + 手写心跳——手造推送客户端的 70%，工程性更差。
- **鉴权走协议内正规做法**：浏览器 WS API 不支持自定义 HTTP 头；STOMP CONNECT 帧支持 connectHeaders 携带 Bearer token，服务端帧级校验（JwtUtil 同源密钥），不向 query string 泄漏 token。
- **Spring 生态一等公民**：spring-boot-starter-websocket + `@EnableWebSocketMessageBroker`（servlet 栈开箱即用）；Spring Cloud Gateway 原生支持同路由 ws upgrade（`Path=/user/**` 复用，无新增路由）。

## What（做什么）

### 后端（user-service）

1. **依赖**：user-service `pom.xml` + `spring-boot-starter-websocket`（唯一新后端依赖）。
2. **端点与代理**：`WebSocketNotificationConfig`（新）——`@EnableWebSocketMessageBroker`；STOMP 端点 `/ws-notifications`（经网关 `/user/ws-notifications`，StripPrefix 后到达）；SimpleBroker 用户目标 `/queue/notifications`；协议心跳 10s/10s（TaskScheduler bean）。
3. **CONNECT 帧鉴权**：`StompConnectAuthInterceptor`（新，入站通道拦截器）——读 CONNECT 帧原生 `Authorization` 头，JwtUtil 解析 access token 取 userId 置 Principal；无效/过期 → 拒绝连接（ERROR 帧）；未带 token → 拒绝（WS 端点不设匿名）。
4. **网关放行**：gateway `application.yml` `app.auth.whitelist` 追加 `/user/ws-notifications`（握手经网关时豁免 Bearer 头——鉴权下沉到 STOMP CONNECT 帧级；仅动配置一行，网关 Java 零改动）。
5. **跨实例扇出**：`NotificationPushRelay`（新）——Redisson RTopic `notification:push` 订阅；`NotificationService.createNotification` 成功落库后 publish `{userId, type, sourceId, content, createdAt}`；各实例收到后本地 `convertAndSendToUser(userId, "/queue/notifications", payload)`——Spring 用户目标注册表只解析本实例会话，天然无双推。
6. **会话管理零手写**：注册表/摘除逻辑由 Spring STOMP 会话管理承担（DISCONNECT/TCP 关闭自动摘除订阅）。

### 前端（web）

7. **依赖**：`@stomp/stompjs`（唯一新 npm 包，package.json + pnpm-lock.yaml 同笔入库）。
8. **客户端**：`web/src/api/notificationWs.ts`（新）——stompjs Client，brokerURL `/user/ws-notifications`（同源 wss/wss 自适应），connectHeaders Bearer token（utils/token 直读）；订阅 `/user/queue/notifications`；reconnectDelay 内建重连；断开清理 deactivate。
9. **composable 接线**：`useNotificationBell.ts`（改）——登录态开 WS，`notification` 消息 → `refreshUnread()`（单一数据源不变）；**60s 轮询保留为兜底不删**（跨端已读漂移自愈 + WS 断流时新鲜度不劣于现状）；未登录不开流不开轮询（既有行为）。
10. **通知页联动**：`notifications.page.vue`（改）——注册 composable 暴露的通知消息回调，页面打开期间消息触发当前页重载。App.vue 零改动。

## 明确不做（本变更边界）

- 不做已读回执的 client→server 消息（管道就绪，语义二期另提案）；
- 不做 SockJS 降级（现代浏览器 WS 普及；降级路径 = 60s 轮询兜底）；
- 不做外部 STOMP broker relay（RabbitMQ/ActiveMQ 不在栈内；跨实例扇出由 RTopic 承担）；
- 不做点赞通知等新通知类型（运营面其余候选另行提案）；
- 不动 TASK-182 四 REST 接口与 RocketMQ 消费链路（发布钩子挂 service 落库成功后，消费侧零改动）；
- 不动 ci.yml / compose / 镜像。

## 依赖增量与改动面

- **后端新增**：`WebSocketNotificationConfig` / `StompConnectAuthInterceptor` / `NotificationPushRelay`（含推送消息 record）+ 对应单测（Mockito 离线确定性）；**修改**：user-service `pom.xml`（+starter-websocket）、`NotificationService`（落库成功发布钩子一行接线下沉）、gateway `application.yml`（白名单一行）。预期 user-service 63 → 73±（+10 量级；只增不减红线由全量实测说话）。
- **前端改动**：+2 新文件（notificationWs.ts + 无新页面）、改 2 文件（useNotificationBell.ts / notifications.page.vue）、package.json + pnpm-lock.yaml（+@stomp/stompjs）；client.ts / typed-router.d.ts 零触碰。
- **台账**：提案三件套 + 任务书/handoff/PLAN.md。

## 验收要点

- 单测矩阵：拦截器（有效 token → Principal / 无效过期 → 拒绝 / 无 token → 拒绝）；relay（RTopic 消息 → convertAndSendToUser 正确用户与目标、JSON 往返）；发布钩子（落库成功才 publish、失败不 publish）；心跳配置可注入短周期验证。
- web 三件套：type-check rc=0 + build rc=0 + typed-router 零漂移 + lockfile 一致（CI frozen-lockfile 过）。
- Java 抽验：offline 全量新基线实测登记（≥480 逐位）+ `--static=record-service` 811 不增。
- 真实链路（网关 ws upgrade 透传 + 真 Redis 扇出 + 浏览器收推）：本机可选联调项——环境就绪留证，不可达 UNDETERMINED 登记不判失败（沿 TASK-182 真 broker IT 口径）。
- 词面门四形态 ZERO_HIT；token 29 项只增不减；PLAN.md 纯追加；契约门无参 rc=0（TASK-184 修缮后历史任务不再误伤——首个受益任务）。

## 风险

- **网关 ws upgrade 未实测**：Spring Cloud Gateway 原生支持，但本仓链路首次使用 → 缓解：真实链路联调列可选项；断流时 60s 轮询兜底保证不劣于现状；
- **CI web 档 frozen-lockfile**：@stomp/stompjs 必须与 package.json 同笔入库且 lockfile 由本机 pnpm 真实生成 → 缓解：C-01 收口前 `pnpm --dir web install` 实跑生成 + `pnpm --dir web build` 过；
- **多实例双推**：RTopic 广播全实例但用户目标注册表只解析本实例会话 → 无双推；单测覆盖扇出回调语义；
- **Checkstyle 811 红线**：新 Java 类按既有规则（javadoc/命名/final），收口 static 门实测说话；
- **STOMP 鉴权密钥一致性**：JwtUtil 与网关共用 `app.auth.jwt.secret`（既有约束「必须一致」已登记），无新增不一致面。
