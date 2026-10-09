# TASK-186 add-notification-read-receipt 任务书（通知已读回执·跨端已读同步）

## 0. 红线（违任一条即 FAILED 停手回报）

1. REST 已读接口契约零改动：不改 Controller / 路径 / 参数 / 返回语义；不新增任何 REST 端点
2. 不引入 client→server STOMP SEND；帧通道只承载事件扇出
3. 不改 DB schema；不加新依赖（后端零 pom 改动、前端零 lockfile 改动）
4. 零触碰：gateway-service（Java 与 yml）、compose、ci.yml、mvn-verify.sh、mailbox-contract.sh、client.ts、typed-router.d.ts、App.vue、useNotificationBell.ts、notifications.page.vue、其余六模块
5. markRead / markAllRead 返回语义零改动（回执钩子是服务层内部副作用）；publish 失败仅告警
6. markAllRead 只发一条 kind=all 回执，禁逐条发（回执风暴防护，载荷级 O(1)）
7. 词面门正则字面量不入任何 tracked 文档与输出（TASK-184 F1 / TASK-185 D3 教训）
8. offline 全量基线 498（36/41/81/127/144/59/10）只增不减；--static=record-service 811 不增
9. 停止条件：需触碰上述任一零触碰面才能完成 / 新基线回退 / 契约门在途 rc=1 无合理解释 / 发现回执语义与本任务书冲突

## 1. 背景与侦察实证（指导侧已核）

- relay 实物：单组件单 topic（`notification:push` → `/queue/notifications`），PostConstruct 订 / PreDestroy 退，publish/handle 均尽力而为（[NotificationPushRelay.java](file:///d:/code/sports/user-service/src/main/java/com/sportverify/user/ws/NotificationPushRelay.java)）
- service 实物：`markRead`（归属校验+is_read=0 前置，影响行数即首写判定）、`markAllRead`（返回影响行数）、`createNotification` 的 publishPush 钩子先例（[NotificationService.java](file:///d:/code/sports/user-service/src/main/java/com/sportverify/user/service/NotificationService.java)）
- 前端实物：`connectNotificationWs(onMessage)` 单订阅，onConnect 内注册（[notificationWs.ts](file:///d:/code/sports/web/src/api/notificationWs.ts)）；回执与新推反应同构（useNotificationBell.handleNotificationMessage → refreshUnread + listeners；页监听器 → 重载列表）

## 2. 预注册实施设计

### 2.1 后端（user-service）

- `ws/NotificationReadReceipt.java`：record `(Long userId, String kind, Long notificationId, LocalDateTime readAt)`；静态工厂 `single(userId, notificationId, readAt)` / `all(userId, readAt)`（kind 常量 `KIND_SINGLE="single"` / `KIND_ALL="all"`）；javadoc 载明 kind 语义与不持久化（瞬时事件）
- `NotificationPushRelay`：常量 `READ_TOPIC = "notification:read"`、`READ_QUEUE = "/queue/notification-read"`；subscribe() 内第二 addListener → handleRead(payload)；destroy() 内 removeListener 双退对称；`publishRead(receipt)`（序列化失败仅告警不抛）；`handleRead` 反序列化 → convertAndSendToUser(userId, READ_QUEUE, receipt)，非法载荷告警丢弃
- `NotificationService`：`markRead` 影响行数>0 → `publishReceipt(NotificationReadReceipt.single(...))` 后返回；`markAllRead` 行数>0 → `publishReceipt(all)`；`publishReceipt` 私有方法 null-relay 跳过 + try/catch 告警（对称 publishPush 先例）；javadoc 增已读回执段（尽力而为、轮询兜底、零行不发）

### 2.2 前端（web）

- `notificationWs.ts`：onConnect 内增 `stomp.subscribe('/user/queue/notification-read', ...)` 与既有订阅同构（JSON.parse try/catch 透传），调用**同一** onMessage 回调；头注释更新（两队列两种载荷形态、反应同构为预注册决策）
- 其余全部零触碰（回执反应链路已存在：handleNotificationMessage → refreshUnread + listeners → 页面重载）

### 2.3 单测矩阵（offline 确定性，预计 +8）

| 类 | 断言 |
| --- | --- |
| NotificationPushRelayTest（+3~4） | 回执 JSON → convertAndSendToUser 正确用户与 READ_QUEUE 目标、载荷往返全等；非法 JSON 丢弃告警不抛；publishRead 失败不抛；READ_TOPIC/READ_QUEUE 常量断言 |
| NotificationServicePushTest（+4~5） | markRead 成功 → 恰一条 single（含 id 与 readAt）；零行（非本人/不存在/已读）→ 零回执；markAllRead N>0 → 恰一条 all；零行 → 零回执；publishRead 异常 → 返回值不变 |

## 4. 白名单（只改清单与主题串）

- 派发笔：`spec/changes/add-notification-read-receipt/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + `work/mailbox/tasks/TASK-186/spec.md`（四件套零改动入库），主题：`docs(spec): 派发 TASK-186 已读回执提案与任务书`
- C-01：`NotificationReadReceipt.java`（新增）、`NotificationPushRelay.java`、`NotificationService.java`、`NotificationPushRelayTest.java`、`NotificationServicePushTest.java`、`web/src/api/notificationWs.ts`，主题：`feat(user): 通知已读回执跨端扇出并前端订阅第二队列（TASK-186）`
- C-02：tasks.json 全勾 + 本 spec §7 纯追加 + `work/mailbox/tasks/TASK-186/handoff.md` + `work/mailbox/PLAN.md` 纯追加，主题：`docs(mailbox): 登记 TASK-186 已读回执验收与台账闭环（TASK-186）`

## 5. 受保护 token（29 项，口径 repo 全量 git grep -cF）

开工实测 SUM 为准（TASK-185 收口实测 1943 参照）；收口读数以**收口态实测**为准（TASK-185 N2 立固定纪律）。只增不减。

## 6. 门禁结构

1. **预提交门禁（每笔前亲跑记录 rc）**：tasks.json 语法 rc=0；词面门四形态 ZERO_HIT rc=1 + 探针 rc=0 + 三态（正则字面量不入任何 tracked 文档与输出）；`git diff --check` rc=0；契约门在途 `--open TASK-186 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新增文件纯 LF 末尾换行完整
2. **收口门禁（C-02 后亲跑留证）**：Java offline 全量新基线逐位登记 + `--static=record-service` 811 不增；web 三件套（type-check rc=0 / build rc=0〔TMP 指仓内 .trae/esbuild-tmp，TASK-183 D1 口径〕/ typed-router 零漂移）+ `pnpm --dir web install --frozen-lockfile` rc=0；契约门无参 rc=0；只改清单全等；PLAN.md 自派发笔起纯追加
3. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（单测矩阵读数、回执三态、风暴防护、同端幂等双刷说明项）/ 逐门实测表 / token 前后读数 / 未覆盖项 / 提交表（显式哈希，禁时效指针）
4. **真实链路联调（可选不判失败）**：本机起网关+user-service+Redis，双浏览器标签验证跨端已读同步；中间件不可达则 UNDETERMINED 登记（沿 182/185 口径）

## 7. 收口记录（执行侧 C-02 纯追加）

（空位：提交哈希、单测矩阵读数、Java 新基线与 811、web 三件套读数、token 前后读数、联调留证或 UNDETERMINED、未覆盖项、三支裁决，由执行侧回填）

### 7.1 提交哈希

| 笔 | 提交 | 主题 |
| --- | --- | --- |
| 派发笔 | `38bbcad` | `docs(spec): 派发 TASK-186 已读回执提案与任务书` |
| C-01 实施 | `d4ebd92` | `feat(user): 通知已读回执跨端扇出并前端订阅第二队列（TASK-186）` |
| C-02 台账收口 | 见 §7.8（本笔自身哈希） | `docs(mailbox): 登记 TASK-186 已读回执验收与台账闭环（TASK-186）` |

### 7.2 单测矩阵读数（offline，C-01 实施态实测）

| 用例类 | 断言内容 | 读数 |
| --- | --- | --- |
| `NotificationPushRelayTest` | 扇出 → convertAndSendToUser 正确用户/目标、JSON 往返全等；非法载荷丢弃；发布失败不抛；常量定义断言；生命周期双订双退对称 | 14 run, 0 fail |
| `NotificationServicePushTest` | 落库成功 → publish 一次；落库失败 → 不发布；publish 异常不影响返回 true；markRead 成功发 single（含 id/readAt）；markRead 零行不发；markRead 异常不影响返回；markAllRead N>0 发 all；markAllRead 零行不发；markAllRead 异常不影响返回 | 9 run, 0 fail |

### 7.3 web 三件套读数（收口态）

| 项 | 读数 | rc |
| --- | --- | --- |
| type-check | `vue-tsc --noEmit` 无输出 | 0 |
| build | `vite build` ✓ built（TMP 工作区化） | 0 |
| typed-router.d.ts 零漂移 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| lockfile frozen 预演 | `pnpm --dir web install --frozen-lockfile` | 0 |

### 7.4 Java 新基线与 811

- offline 全量：`36/41/95/127/144/59/10` = **512**（user-service 81→95，只增不减；其余六模块逐位不变，总数较 498 净增 14）。
- `--static=record-service` Checkstyle **811 持平**（未增，rc=1 预期）。
- 本任务零新依赖（后端零 pom 改动、前端零 lockfile 改动）。

### 7.5 token 前后读数

- 开工实测 SUM=**1943**（口径 repo 全量 `git grep -cF`，29 项逐值见 handoff §7）。
- 收口实测只增不减（收口态实测 SUM=**1972**，见 handoff §7）。

### 7.6 联调留证

本机真实链路（网关 + user-service + Redis + 双浏览器标签）因中间件环境不可达未展开，登记为 **UNDETERMINED**（沿 TASK-182/185 口径，不判失败）；链路语义等价由单测逐字覆盖。

### 7.7 三支裁决

**PASSED**：后端三处 + 前端一处订阅落地 + 单测矩阵全绿 + web 三件套 + Java 新基线只增不减（512）+ 811 不增 + 全门禁绿。无 FAILED 项。外部终验待推送后下一次外部门槛（第 28 次）CI 绿。

### 7.8 C-02 台账笔哈希（本笔收口）

C-02 提交哈希：`（由回传报告以显式哈希给出，见 handoff §8）`

## 8. 开工读数（指导侧派发时基线）

- 派发笔基线 HEAD `4007fc5`（TASK-185 第 27 次门槛补记笔）；`origin/main...main` = `0 1`；工作树仅本任务在途派发材料
- offline 498（36/41/81/127/144/59/10）；static 811；token SUM 开工实测为准；契约门/词面门开工清
