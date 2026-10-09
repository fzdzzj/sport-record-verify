# TASK-185 add-notification-ws-push 通知实时推送 WebSocket+STOMP 升级 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-09）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧不推送。

## 1. 开工规程核验

- **工作树基线全符**：仅两组在途派发材料「spec/changes/add-notification-ws-push/（提案三件套）+ work/mailbox/tasks/TASK-185/spec.md（任务书）」；另 1 笔未推送提交 `c95cb52`（TASK-184 补记笔）零触碰、不 push；`git rev-list --left-right --count origin/main...main` 开工 = `0 1`（派发笔入库后 `0 2`，C-01 后 `0 3`，C-02 后 `0 4`）。
- **红线逐条核验**（§0）：改动面唯一（白名单 §4）；TASK-182 四 REST + RocketMQ 消费链路零语义改动（发布钩子只挂 createNotification 落库成功后）；未登录零请求（不开 WS 不发轮询）；鉴权走 CONNECT 帧级 JwtUtil、不向 query string 传 token；参数红线（心跳 10s/10s 新命名空间、未动 TASK-182 参数）；唯一 mvn 入口仅 `bash scripts/verify/mvn-verify.sh`；bash 仅 `D:\git\Git\bin\bash.exe` 且只执行脚本文件；逐路径 add、`-F` 消息无 BOM；词面门正则字面量不入任何 tracked 文档（临时脚本仅存 `.trae/tmp/task185/`，gitignored）。
- **零触碰核验**：mvn-verify.sh、mailbox-contract.sh、ci.yml、compose、其余六模块 src、`web/client.ts`、`web/typed-router.d.ts`（无新页面）、`App.vue`、`.codex/`、`.trae/`。`git status --porcelain` 收口后为空。
- **未触发停止条件**：无 ci.yml/compose/网关 Java 代码改动诉求；lockfile 本机 pnpm 真实生成且 frozen-lockfile 预演绿；offline 新基线 498 只增不减、811 不增；starter-websocket 版本树零冲突（BOM 管理 3.2.4，详见 §4）。

## 2. 一句话结论与三支裁决

**后端三新类 + 前端接线 + 单测矩阵全绿 + 网关白名单显式化落地，web 三件套 + Java offline(498) + static(811) + 全门禁绿，判定 PASSED**；契约门在途/无参都 rc=0，并实证了 TASK-184 修缮后首个受益任务（改 pom.xml 不再触发历史任务「零触碰」误伤）。外部终验待推送后下一次外部门槛 CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | 管道三新类 + 前端接线 + 单测矩阵全绿 + web 三件套 + Java 新基线只增不减（36/41/81/127/144/59/10=498，user-service 63→81）+ static 811 不增 + 契约门在途 rc=0 + 全门禁绿 |
| FAILED | ✗ | 未触发（无停止条件、无门禁回归） |
| 外部终验 | 待推送 | 推送后下一次外部门槛（第 27 次）CI 绿；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

全任务实际足迹 = 派发笔 4 条 + C-01 16 条 + C-02 4 条，分笔如下：

**派发笔（执行侧零改动入库）**：
1. `spec/changes/add-notification-ws-push/proposal.md`
2. `spec/changes/add-notification-ws-push/tasks.json`
3. `spec/changes/add-notification-ws-push/specs/sport-record-verify/spec-delta.md`
4. `work/mailbox/tasks/TASK-185/spec.md`

**C-01（实施笔）**：
1. `gateway-service/src/main/resources/application.yml`（白名单追加 `/user/ws-notifications`，保持既有两项）
2. `user-service/pom.xml`（+ `spring-boot-starter-websocket`，BOM 管理版本）
3. `user-service/src/main/java/com/sportverify/user/config/WebSocketNotificationConfig.java`（新）
4. `user-service/src/main/java/com/sportverify/user/config/StompConnectAuthInterceptor.java`（新）
5. `user-service/src/main/java/com/sportverify/user/ws/NotificationPushMessage.java`（新记录）
6. `user-service/src/main/java/com/sportverify/user/ws/NotificationPushRelay.java`（新）
7. `user-service/src/main/java/com/sportverify/user/service/NotificationService.java`（发布钩子）
8. `user-service/src/test/java/com/sportverify/user/config/StompConnectAuthInterceptorTest.java`（新）
9. `user-service/src/test/java/com/sportverify/user/config/WebSocketNotificationConfigTest.java`（新）
10. `user-service/src/test/java/com/sportverify/user/ws/NotificationPushRelayTest.java`（新）
11. `user-service/src/test/java/com/sportverify/user/service/NotificationServicePushTest.java`（新）
12. `web/package.json`（+ `@stomp/stompjs: ^7.3.0`）
13. `web/pnpm-lock.yaml`（本机 pnpm 真实生成）
14. `web/src/api/notificationWs.ts`（新）
15. `web/src/composables/useNotificationBell.ts`（接线）
16. `web/src/pages/notifications.page.vue`（注册消息回调刷新当前页）

**C-02（台账收口笔）**：
1. `spec/changes/add-notification-ws-push/tasks.json`（闭环全勾）
2. `work/mailbox/tasks/TASK-185/spec.md`（§7 收口记录纯追加）
3. `work/mailbox/tasks/TASK-185/handoff.md`（本文件）
4. `work/mailbox/PLAN.md`（纯追加 §验收记录）

> 零触碰清单：`web/client.ts`、`web/typed-router.d.ts`（无新页面）、`web/src/App.vue`、`mvn-verify.sh`、`mailbox-contract.sh`、`ci.yml`、`docker-compose*.yml`、其余六模块 src、`.codex/`、`.trae/`。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **新后端依赖离线仓补充**：`spring-boot-starter-websocket:3.2.4`（含传递 `spring-websocket`/`spring-messaging` 6.1.5）在项目离线仓 `.m2-repo` 中缺失（`git rev-list` 开工基线无此构件），`--mode=offline test` 首次必红（Version tree 零冲突，3.2.4 由父 pom import 的 spring-boot-dependencies BOM 管理；仅是本机 offline 仓未缓存该新构件）。处置：先经 `mvn-verify.sh --mode=online test` 解析并入库，再把三构件（pom/jar/sha1，剔 `_remote.repositories`）补入 gitignored 的 `.m2-repo`，随后 `--mode=offline test` 全绿（498）——这是新增依赖的标准离线仓布源，非版本冲突、非门禁回归。 | 环境/依赖布源处置，登记不进 FAILED；offline 498 为权威读数。 |
| D2 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希（TASK-185 执行范围为派发/C-01/C-02 三笔，不另开订正笔）。处置：§8 列出派发笔 `aaad18d`、C-01 `d19dc74` 显式哈希；C-02 自指为台账收口笔，其显式哈希在本回传报告给出（沿 TASK-182/183 台账终态化先例的固有自指事实）。 | 台账提交表终态化固有的单一自指；三笔主题与任务书 §4 逐字一致，无主题偏差。 |
| D3 | **词面门 repo 全量口径**：本仓 `git grep -iE`（词面门正则）在历史存档 `spec/changes/archive/add-two-level-cache/tasks.json` 命中既有禁用措辞、`.github/workflows/ci.yml` 自带正则本体——两处均为公开门禁 CI 显式排除路径（见 ci.yml `Public docs wording self-check` 步骤 exclude 集：`spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）。按 CI 权威口径复扫 repo 全量四形态 ZERO_HIT。本 D3 行不落任何词面门正则字面量（TASK-184 F1 教训：此红线由复核侧全仓复扫）。 | CI 权威口径处置；本任务改动文件集四形态独立 ZERO_HIT 已实测。 |

## 5. 实施证据（含验收要点判据）

### 5.1 鉴权三态（StompConnectAuthInterceptor）
- 有效 access token → `preSend` 置会话 `Principal` 为该 userId（JwtUtil 同源密钥解析 subject）。
- 无效签名 / 已过期 token → 抛 `MessagingException` 拒绝连接（会话不建立）。
- 缺失 `Authorization` 头 → 抛 `MessagingException` 拒绝（WS 端点不设匿名）。
- 非 CONNECT 帧（SUBSCRIBE/SEND）→ 原帧直通不校验。
- 浏览器 WS 不能自定义 HTTP 头，token 入 CONNECT 帧 `connectHeaders`（协议内正规位），不向 query string 传 token（红线 §0.3）。

### 5.2 扇出与无双推（NotificationPushRelay）
- `@PostConstruct` 订阅 `RTopic("notification:push")`（StringCodec），回调反序列化 `NotificationPushMessage` → `convertAndSendToUser(userId, "/queue/notifications", msg)`；`@PreDestroy` 退订。
- 多实例无双推：Spring 用户目标注册表只解析本实例会话，收件人会话不在此实例时投递静默跳过——机制性保证（单测覆盖「正确用户与目标 + JSON 往返全等」）。

### 5.3 发布钩子尽力而为（NotificationService）
- `createNotification` 首插落库（受影响行数>0）成功后发布推送；落库失败（受影响 0，幂等跳过）不发布。
- 发布失败仅告警不回滚落库（推送尽力而为，前端 60s 轮询兜底保证新鲜度不劣于纯轮询基线）。取舍写于 `NotificationService` javadoc 与本 §5.3。
- 注入方式：`@Autowired(required=false)` 可选注入 relay，旧构造/旧单测不破坏（`new NotificationService(mapper)` 仍成立）。

### 5.4 前端接线与兜底
- `notificationWs.ts`：stompjs `Client`，brokerURL 由 `location.protocol` 推导 ws/wss + `/user/ws-notifications`；connectHeaders Bearer；订阅 `/user/queue/notifications`；reconnectDelay 5000 内建重连；deactivate 清理；未登录 connect 即 no-op。
- `useNotificationBell.ts`：登录态 `startUnreadPolling` 开 WS，消息 → `refreshUnread()`；暴露 `onNotification` 注册口；`stopUnreadPolling` 同步 `disconnectNotificationWs`；**60s 轮询保留为零删改**兜底（WS 断流时新鲜度不劣于 TASK-183 基线）；未登录不开流不开轮询（红线 §0.2）。
- `notifications.page.vue`：`onMounted` 注册 `onNotification(() => loadList())`，卸载注销。

### 5.5 网关白名单显式化
- `gateway-service/.../application.yml` `app.auth.whitelist`：`/api/auth/**,/actuator/health` → `/api/auth/**,/actuator/health,/user/ws-notifications`（保持既有两项不丢；握手经网关豁免 Bearer 头，鉴权下沉 CONNECT 帧级；网关 Java 零改动）。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| 词面门（C-01 改动文件集 tracked 四形态） | 四形态全 ZERO_HIT（default/C/zh_CN.UTF-8/C.UTF-8 均 rc=1）；探针三态 HIT rc=0；PROBE_GONE=yes | 1（预期非零） |
| 词面门（repo 全量，CI 权威 exclude 口径四形态） | 四形态全 ZERO_HIT（见 §4 D3） | 1（预期非零） |
| `git diff --check`（C-01 提交前 + C-02 提交前） | 干净，无空白错误 | 0 |
| 契约门在途 `--open TASK-185 --baseline=aaad18d`（C-01 提交前） | 判据 A 两件套 + 1 待办放行 + 判据 B 一致 + **TASK-184 修缮后首个受益验证**（改 pom.xml 不再触发历史「零触碰」误伤） | 0 |
| 契约门无参（C-02 后复跑） | 判据 A 两件套齐（TASK-185 含 handoff）+ 判据 B 清单一致 | 0 |
| web type-check | `vue-tsc --noEmit` 无输出 | 0 |
| web build | `vite build` ✓ built（TMP 工作区化见 §4 D1-TMP/任务书 D1） | 0 |
| typed-router.d.ts 与提交一致 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| lockfile frozen 预演 | `pnpm --dir web install --frozen-lockfile` | 0 |
| offline 全量 `--mode=offline test`（C-01 提交前） | `36/41/81/127/144/59/10` = **498**，Failures/Errors/Skipped 全 0，BUILD SUCCESS（user-service 63→81） | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增），rc=1 为基线违规模块预期 | 1（预期） |
| token 29 项 | 开工 SUM=1885；C-01 后 SUM=1914；收口只增不减（见 §7） | 只增不减 |
| 只改清单全等 | C-02 实际改动集恰 §3 四条；`git status --porcelain` 收口后为空 | 全等 |

## 7. token 前后读数（口径 repo 全量 `git grep -cF`）

**开工实测（SUM=1885，TASK-184 收口真值为参照）**：
`13.4`=150、`18.0`=154、`73.93`=107、`68.8`=101、`6315`=81、`1.8612`=81、`3.3066`=81、`5.7056`=87、`9.408`=72、`36525962432`=65、`36586847965`=62、`36438897772`=64、`36399582548`=62、`36098038547`=60、`2806`=102、`598`=97、`36736221648`=59、`36808102571`=47、`36821040708`=41、`36845152965`=38、`36871294588`=36、`36880083885`=38、`36958994260`=37、`36976873215`=32、`36992632143`=29、`36995450125`=27、`37008317295`=26、`37021305016`=25、`37591580687`=24。

**C-01 后读数（SUM=1914，每项较开工 +1，均只增不减）**：逐项见本笔实际 `git grep -cF`（C-01 添加的 proposal/spec-delta/spec.md §5 各引用 token 集合，故 29 项齐 +1）。

**C-02 收口复测（SUM=1914）**：台账/handoff/PLAN 追加不引入任何受保护 token 字面量，收口实测 = C-01 后读数 **SUM=1914**，只增不减（开工 1885 → 1914，29 项齐 +1）。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔 | `aaad18df...`（`aaad18d`） | `docs(spec): 派发 TASK-185 通知 WS 推送提案与任务书` |
| C-01 实施 | `d19dc74f...`（`d19dc74`） | `feat(user): 通知实时推送 WebSocket+STOMP 管道并前端接线（TASK-185）` |
| C-02 台账收口 | 本笔（自指，见 §4 D2；显式哈希以本回传报告给出） | `docs(mailbox): 登记 TASK-185 WS 推送验收与台账闭环（TASK-185）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 1`、派发后 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧不推送。

## 9. 未覆盖项

1. **真实链路联调（可选）UNDETERMINED**：本机网关 + user-service + Redis + 浏览器 WS 收推/心跳未展开（中间件环境不可达）。沿 TASK-182 IT 口径登记 UNDETERMINED 不判失败；链路语义等价（CONNECT 鉴权、用户目标投递、心跳、无双推、断流兜底）由单测逐字覆盖。
2. **SockJS 降级**（明确不做）：现代浏览器 WS 普及，断流降级路径 = 60s 轮询兜底（沿用 TASK-183 基线）。
3. **已读回执 client→server 消息**（明确不做）：管道就绪，语义二期另提案。
4. **外部 STOMP broker relay**（明确不做）：跨实例扇出由 RTopic 承担，无双推。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions，第 27 次）CI 绿（web 档 + build 档 + 词面门）为外部终验；红则按签名归因（不带预设、禁重试刷绿）。新 run 号以文本登记，不扩受保护 token 集合。
