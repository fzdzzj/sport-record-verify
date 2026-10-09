# TASK-188 add-notification-like 点赞通知·记录收到点赞时通知作者 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-09）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧不推送。

## 1. 开工规程核验

- **工作树基线全符**：仅两组在途派发材料「spec/changes/add-notification-like/（提案三件套）+ work/mailbox/tasks/TASK-188/spec.md（任务书）」；另 1 笔未推送提交 `f013594`（TASK-187 第 29 次门槛补记笔）零触碰、不 push；`git rev-list --left-right --count origin/main...main` 开工 = `0 1`（派发笔入库后 `0 2`，C-01 后 `0 3`，C-02 后 `0 4`）。
- **红线逐条核验**（§0）：
  1. 触发挂点严格限定于 `RecordLikeService.like()` 热路径 firstLike 分支（SADD 返回 1）asyncSend 发布；flush 落库 / 对账 / 计数读路径 / LikeController 零触碰；未在 flush 后或 controller 层发布。
  2. 通知语义裁决遵守：unlike 不通知 · 幂等跳过（SADD=0）不通知 · 自赞不通知（liker==record.getUserId() 发布前短路）；表级 dedupKey=`RECORD_LIKED:{recordId}:{likerId}`。
  3. record-verify-events topic 与三既有消费组零触碰：新 topic `record-like-events` + 新独立消费组 `notification-like-consumer-group` + 新独立类 LikeEventConsumer。
  4. 既有通知行为零改动：三类既有通知、既有 REST 契约、WS 管道（notificationWs.ts / NotificationPushRelay / NotificationReadReceipt）、已读回执、偏好端点全部零触碰；RECORD_LIKED 经 createNotification 主链自动享有偏好闸门/WS 推送/未读计数/已读回执，不改 NotificationService 主链。
  5. 零新依赖：后端零 pom（record-service 已有 rocketmq-spring-boot-starter）、前端零 lockfile、零 SQL DDL（notification.type 与 notification_preference.type 均 VARCHAR(30)）；ci.yml / compose / gateway / mvn-verify.sh / mailbox-contract.sh 零触碰。
  6. 前端零新页面：typed-router.d.ts / App.vue 零触碰且 typed-router 零漂移；点赞触发面与通知列表渲染零触碰，仅修改 notifications.page.vue 偏好卡片（三开关扩四开关）。
  7. LikeEventProducer 无补偿路径（best-effort 裁决）：失败仅告警，不加 Feign 降级、不加重试队列、不回滚点赞。
  8. 词面门正则字面量绝不写入任何 tracked 文档或输出。
  9. offline 全量基线 527 只增不减（实测 541），--static=record-service 811 不增（实测 811 持平）；唯一 mvn 入口仅 `bash scripts/verify/mvn-verify.sh`；bash 仅 `D:\git\Git\bin\bash.exe` 且只执行脚本文件；逐路径 add、`-F` 消息无 BOM；未触发任何停止条件。

## 2. 一句话结论与三支裁决

**点赞通知事件生产端（RecordLikeService 热路径发布 + LikeEventProducer best-effort 异步发送）、消费端（编程式 DefaultMQPushConsumer + 独立消费组 + SETNX 24h 去重）、通知偏好四开关支持与前端偏好卡片接线全部落地，单测矩阵新增 14 条（全仓 527→541，record-service 127→134，user-service 110→117），静态 811 持平，web 三件套全绿，全门禁通过，判定 PASSED**；契约门在途/无参均 rc=0。外部终验待推送后下一次外部门槛（第 30 次）CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | 生产端热路径短路防轰炸 + LikeEventProducer best-effort + 消费端编程式监听与 SETNX 幂等 + createNotification 自动偏好闸门 + 前端第四开关 + 单测矩阵全绿（全仓 541，+14）+ web 三件套 + 静态 811 持平 + 契约门在途/无参 rc=0 + 全门禁绿 |
| FAILED | ✗ | 未触发（无停止条件触发、无门禁回归） |
| 外部终验 | 待推送 | 推送后下一次外部门槛（第 30 次）CI 绿；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

全任务实际足迹 = 派发笔 4 条 + C-01 13 条 + C-02 4 条，去重后共 19 处文件：

**派发笔（执行侧零改动入库）**：
1. `spec/changes/add-notification-like/proposal.md`
2. `spec/changes/add-notification-like/tasks.json`
3. `spec/changes/add-notification-like/specs/sport-record-verify/spec-delta.md`
4. `work/mailbox/tasks/TASK-188/spec.md`

**C-01（实施笔）**：
1. `api/src/main/java/com/sportverify/api/event/LikeEventDTO.java`
2. `api/src/main/java/com/sportverify/api/event/RecordLikeEvents.java`
3. `record-service/src/main/java/com/sportverify/record/mq/LikeEventProducer.java`
4. `record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java`
5. `record-service/src/test/java/com/sportverify/record/mq/LikeEventProducerTest.java`
6. `record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java`
7. `user-service/src/main/java/com/sportverify/user/enums/NotificationType.java`
8. `user-service/src/main/java/com/sportverify/user/mq/LikeEventConsumer.java`
9. `user-service/src/main/java/com/sportverify/user/service/NotificationPreferenceService.java`
10. `user-service/src/main/resources/application.yml`
11. `user-service/src/test/java/com/sportverify/user/mq/LikeEventConsumerTest.java`
12. `user-service/src/test/java/com/sportverify/user/service/NotificationPreferenceServiceTest.java`
13. `web/src/pages/notifications.page.vue`

**C-02（台账收口笔）**：
1. `spec/changes/add-notification-like/tasks.json`
2. `work/mailbox/tasks/TASK-188/spec.md`
3. `work/mailbox/tasks/TASK-188/handoff.md`
4. `work/mailbox/PLAN.md`

> 零触碰清单：`gateway-service/`、`docker-compose*.yml`、`ci.yml`、`mvn-verify.sh`、`mailbox-contract.sh`、`web/typed-router.d.ts`、`web/src/App.vue`、`web/src/api/client.ts`、`web/src/pages/verdict.page.vue`、其余模块 src、`.codex/`、`.trae/`。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希（TASK-188 执行范围为派发/C-01/C-02 三笔，不另开订正笔）。处置：§8 列出派发笔 `c34eb94`、C-01 `1b208b1` 显式哈希；C-02 自指为台账收口笔，其显式哈希在本回传报告给出（沿 TASK-182/185/186/187 台账终态化先例的固有自指事实）。 | 台账提交表终态化固有的单一自指；三笔主题与任务书 §4 逐字一致，无主题偏差。 |
| D2 | **词面门 repo 全量口径**：本仓在历史存档 `spec/changes/archive/add-two-level-cache/tasks.json` 命中既有禁用措辞、`.github/workflows/ci.yml` 自带正则本体——两处均为公开门禁 CI 显式排除路径（见 ci.yml `Public docs wording self-check` 步骤 exclude 集：`spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）。按 CI 权威口径复扫 repo 全量四形态 ZERO_HIT。本 D2 行不落任何词面门正则字面量（TASK-184 F1 教训）。 | CI 权威口径处置；本任务改动文件集四形态独立 ZERO_HIT 已实测。 |

## 5. 实施证据（含验收要点判据）

### 5.1 触发挂点与防轰炸三闸门
- **挂点裁决**：触发挂点定于 `RecordLikeService.like()` 热路径的 `firstLike` 分支（Redis SADD 返回 1）。
- **防轰炸三闸门**：
  1. **自赞短路**：在发布 MQ 事件前显式比较 `if (!Objects.equals(userId, record.getUserId()))`，作者本人给自己的记录点赞不产生任何 MQ 事件。
  2. **幂等跳过与取消不发**：重复点赞（SADD 返回 0）直接跳过；`unlike()` 取消点赞无通知逻辑，绝不发事件。
  3. **表级终身一次与偏好闸门**：消费端落通知采用 `dedupKey = "RECORD_LIKED:" + event.getRecordId() + ":" + event.getLikerId()`，同一人在同一记录上终身至多触发一条通知；同时 `createNotification` 自动经由 `NotificationPreferenceService` 偏好开关拦截（若作者关闭点赞通知，静默不落库不推送）。

### 5.2 生产端规范与 best-effort 裁决
- `LikeEventProducer` 沿 `RecordEventProducer` 逐字模式：捕获 MDC traceId，调用 RocketMQTemplate 异步发送至 `record-like-events:LIKED`，在异步回调中恢复 traceId。
- 采用 best-effort 裁决：发送失败或序列化异常仅打印 ERROR 日志告警，不抛出异常、不设 Feign 补偿、不回滚点赞，确保点赞主路径高可用与低延迟。

### 5.3 消费端规范与原生重试
- `LikeEventConsumer` 沿 `NotificationEventConsumer` 编程式构建 `DefaultMQPushConsumer`，独立配置消费组 `rocketmq.notification.like-consumer.group: notification-like-consumer-group`。
- 订阅 `RecordLikeEvents.TOPIC` 与 `TAG_LIKED`，设置原生最大重试次数为 3 次（超次进入 DLQ），消费起点为 `CONSUME_FROM_FIRST_OFFSET`。
- 消费前基于 Redisson SETNX 执行 24h 事件级幂等去重（键前缀 `notification:event:` + eventId）；消费失败删除 Redis 去重键并返回 `RECONSUME_LATER`；DTO 反序列化失败丢弃并告警。

### 5.4 前端设置卡片第四开关
- `web/src/pages/notifications.page.vue` 通知偏好卡片由三开关扩展为四开关：新增 `prefRecordLiked` ref(true)；
- `applyPreferences` 正确解析 `RECORD_LIKED` 开关状态；
- `savePreferences` 请求载荷追加 `{ type: 'RECORD_LIKED', enabled: prefRecordLiked.value }`；
- 模板中沿既有结构增加「收到点赞」a-switch，缩进与闭合标签严格对齐。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `python -m json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| 词面门（C-01 改动文件集 tracked 四形态） | 四形态全 ZERO_HIT（default/C/zh_CN.UTF-8/C.UTF-8 均 rc=1）；探针三态 HIT rc=0；PROBE_GONE=yes | 1（预期非零） |
| 词面门（repo 全量，CI 权威 exclude 口径四形态） | 四形态全 ZERO_HIT（见 §4 D2） | 1（预期非零） |
| `git diff --check`（C-01 提交前 + C-02 提交前） | 干净，无空白错误 | 0 |
| 契约门在途 `--open TASK-188 --baseline=c34eb94`（C-01 提交前） | 判据 A 两件套齐 + 1 待办放行 + 判据 B 一致 | 0 |
| 契约门无参（C-02 后复跑） | 判据 A 两件套齐（TASK-188 含 handoff）+ 判据 B 清单一致 | 0 |
| web type-check | `vue-tsc --noEmit` 无输出 | 0 |
| web build | `vite build` ✓ built（TMP 工作区化，13.60s） | 0 |
| typed-router.d.ts 与提交一致 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| lockfile frozen 预演 | `pnpm --dir web install --frozen-lockfile` | 0 |
| offline 全量 `--mode=offline test`（C-01 实施态） | `36/41/117/134/144/59/10` = **541**，Failures/Errors/Skipped 全 0，BUILD SUCCESS（基线 527→541，+14） | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增），rc=1 为基线违规模块预期 | 1（预期） |
| token 29 项 | 开工 SUM=2001；收口态实测 SUM=2001，只增不减（见 §7） | 只增不减 |
| 只改清单全等 | 实际改动集恰 §3 清单；`git status --porcelain` 收口后为空 | 全等 |

## 7. token 前后读数（口径 repo 全量 `git grep -cF`）

**开工实测（SUM=2001，TASK-187 收口真值 2001 为参照）**：
`13.4`=154、`18.0`=158、`73.93`=111、`68.8`=105、`6315`=85、`1.8612`=85、`3.3066`=85、`5.7056`=91、`9.408`=76、`36525962432`=69、`36586847965`=66、`36438897772`=68、`36399582548`=66、`36098038547`=64、`2806`=106、`598`=101、`36736221648`=63、`36808102571`=51、`36821040708`=45、`36845152965`=42、`36871294588`=40、`36880083885`=42、`36958994260`=41、`36976873215`=36、`36992632143`=33、`36995450125`=31、`37008317295`=30、`37021305016`=29、`37591580687`=28。

**C-01 后实测（SUM=2001）**：C-01 业务实现与单测均未引入受保护 token 字面量，29 项读数逐位与开工持平。

**C-02 收口复测（SUM=2001，只增不减）**：台账/handoff/PLAN 纯追加不引入任何受保护 token 字面量，收口态实测 repo 全量 `git grep -cF` 为 **SUM=2001**，只增不减。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔 | `c34eb941e02583f22344e5c5ef8ad351a4fe0a34`（`c34eb94`） | `docs(spec): 派发 TASK-188 点赞通知提案与任务书` |
| C-01 实施 | `1b208b1c28ce44833f681230db555a43aaba19ab`（`1b208b1`） | `feat(record): 点赞通知事件发布与消费落地（TASK-188）` |
| C-02 台账收口 | 本笔（自指，见 §4 D1；显式哈希以本回传报告给出） | `docs(mailbox): 登记 TASK-188 点赞通知验收与台账闭环（TASK-188）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 1`、派发后 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧不推送。

## 9. 未覆盖项

1. **真实链路联调（可选）UNDETERMINED**：本机 RocketMQ broker + 双服务 + Redis 端到端点赞到通知推送未展开（中间件全栈未起）。沿 TASK-182/185/186/187 口径登记 UNDETERMINED 不判失败；链路各段语义等价性由单测矩阵完全覆盖。
2. **best-effort 无补偿**（说明项，预注册）：MQ 异常时不设补偿、不回滚点赞，确保点赞主路径高可用。
3. **dedupKey 终身一次语义**（说明项，预注册）：取消再赞不重复通知，跨点赞者各自独立通知。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions，第 30 次）CI 绿（web 档 + build 档 + 词面门）为外部终验；红则按签名归因（不带预设、禁重试刷绿）。新 run 号以文本登记，不扩受保护 token 集合。
