# TASK-188 add-notification-like 任务书（点赞通知·记录收到点赞时通知作者）

## 0. 红线（违任一条即 FAILED 停手回报）

1. 触发挂点已裁决：`RecordLikeService.like()` 热路径 firstLike 分支（SADD 返回 1）asyncSend 发布；flush 落库 / 对账 / 计数读路径 / LikeController 零触碰；不得改为 flush 落库后发布或 controller 层发布
2. 通知语义已裁决：unlike 不通知 · 幂等跳过（SADD=0）不通知 · 自赞不通知（liker==record.getUserId() 发布前短路）；表级 dedupKey=`RECORD_LIKED:{recordId}:{likerId}`（同一点赞者对同一记录终身至多一条，取消再赞不重复通知）
3. record-verify-events topic 与三既有消费组（verify / leaderboard / notification）零触碰：新 topic `record-like-events` + 新消费组 `notification-like-consumer-group` + 新独立类 LikeEventConsumer（沿 NotificationEventConsumer 范式逐字）
4. 既有通知行为零改动：三类既有通知、既有 REST 契约、WS 管道（notificationWs.ts / NotificationPushRelay / NotificationReadReceipt）、已读回执、偏好端点全部零触碰；RECORD_LIKED 经 createNotification 主链自动享有偏好闸门/WS 推送/未读计数/已读回执，**不改 NotificationService 主链**
5. 零新依赖：后端零 pom（record-service 已有 rocketmq-spring-boot-starter 与 producer 配置）、前端零 lockfile、零 SQL DDL（notification.type 与 notification_preference.type 均 VARCHAR(30) 容纳 RECORD_LIKED）；ci.yml / compose / gateway / mvn-verify.sh / mailbox-contract.sh 零触碰
6. 前端零新页面：typed-router.d.ts / App.vue 零触碰（typed-router 应零漂移）；点赞触发面（verdict.page.vue / client.ts 既有 like 函数）与通知列表渲染零触碰，只改 notifications.page.vue 偏好卡片（三开关扩四开关）
7. LikeEventProducer 无补偿路径（best-effort 已裁决）：失败仅告警，不加 Feign 降级、不加重试队列、不回滚点赞
8. 词面门正则字面量不入任何 tracked 文档与输出（TASK-184 F1 / TASK-185 D3 教训）
9. 停止条件：需触碰上述任一零触碰面才能完成 / offline 基线 527（36/41/110/127/144/59/10）回退或 811 增 / 契约门在途 rc=1 无合理解释

## 1. 背景与侦察实证（指导侧已核）

- record-service 已有 rocketmq-spring-boot-starter（pom L103-104）与生产端配置（application.properties：`rocketmq.name-server=127.0.0.1:9876`、`rocketmq.producer.group=record-service-group`）——生产端零 pom 零配置
- 生产端先例：[RecordEventProducer.java](file:///d:/code/sports/record-service/src/main/java/com/sportverify/record/mq/RecordEventProducer.java)（asyncSend + traceId 捕获/异步线程恢复 + onFailure 回调；SUBMITTED 有 Feign 补偿——点赞**不沿**补偿，best-effort）
- 触发挂点：[RecordLikeService.java L162-186](file:///d:/code/sports/record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java) like() —— firstLike 分支 record 对象已含作者 userId（requireRecord 查回）；unlike L198-217 无通知语义；类为显式构造器注入（加 LikeEventProducer 需扩构造参数，既有单测同步扩展）
- broker `autoCreateTopicEnable=true` 随发随建（TASK-110 先例）——新 topic 零运维
- 消费端范式：[NotificationEventConsumer.java](file:///d:/code/sports/user-service/src/main/java/com/sportverify/user/mq/NotificationEventConsumer.java)（编程式 DefaultMQPushConsumer + SETNX 24h + maxReconsumeTimes=3 + %DLQ% + 后台 30s 重连 + buildConsumer 拆出供单测观测）；消费组配置键先例 `rocketmq.notification.consumer.group`（user-service application.yml L37-41）
- createNotification 签名与偏好闸门（TASK-187）：`createNotification(userId, type, sourceId, title, content, dedupKey)`，首行偏好闸门对 RECORD_LIKED 自动生效——只需扩常量与 SUPPORTED_TYPES
- [NotificationPreferenceService.java L34](file:///d:/code/sports/user-service/src/main/java/com/sportverify/user/service/NotificationPreferenceService.java) SUPPORTED_TYPES 三类 → 四类（GET 视图 / PUT 校验 / 闸门判定同源生效）
- dedupKey 先例：FRIEND_ACCEPTED:{requestId}（FriendService 本地直写）；点赞采用 RECORD_LIKED:{recordId}:{likerId}（业务维度终身一次）
- web 偏好卡片三开关：[notifications.page.vue](file:///d:/code/sports/web/src/pages/notifications.page.vue) L62-73（模板三行）/ L188-231（pref refs + applyPreferences + savePreferences payload）；点赞触发面 verdict.page.vue L130-140 零触碰
- 测试先例：RecordLikeServiceTest / LikeControllerTest（record-service）；NotificationEventConsumerTest + NotificationConsumerRoundTripIT（user-service）；**record-service 无生产端单测先例**——LikeEventProducerTest 为新立（Mockito mock RocketMQTemplate，登记为先例）
- 事件体先例：VerifyEventDTO（eventId/recordId/userId/eventType/occurredAt）+ RecordVerifyEvents 常量类（api 模块 event 包）

## 2. 预注册实施设计

### 2.1 api 模块（event 包，沿 VerifyEventDTO / RecordVerifyEvents 模式）

- `RecordLikeEvents`（新）：TOPIC=`record-like-events`、TAG_LIKED=`LIKED`、EVENT_LIKED=`LIKED`（点赞域事件常量；无自建死信 topic——超次进 `%DLQ%notification-like-consumer-group`，沿 adopt-native-mq-retry 口径）
- `LikeEventDTO`（新）：eventId（UUID，MQ 幂等键）/ recordId / likerId（点赞者）/ recordOwnerId（记录作者=收件人，冗余在事件体不回查）/ occurredAt；Serializable + @Data + 注释风格沿 VerifyEventDTO

### 2.2 record-service（生产端）

- `mq/LikeEventProducer`（新，沿 RecordEventProducer 逐字模式）：
  - `publishLiked(Long recordId, Long likerId, Long recordOwnerId)`：构造 LikeEventDTO（eventId=UUID.randomUUID）→ objectMapper 序列化 → asyncSend(`record-like-events:LIKED`, payload, SendCallback)——成功 on 线程恢复 traceId 记 info，失败记 error **仅告警**（无 onFailure 参数、无补偿；序列化/提交异常同步 catch 仅告警不抛）
- `RecordLikeService`：构造器追加 LikeEventProducer（第 8 参数）；like() firstLike 分支 pushPending 之后：`if (!userId.equals(record.getUserId())) likeEventProducer.publishLiked(recordId, userId, record.getUserId())`——自赞短路在发布前；幂等跳过分支与 unlike 零发布

### 2.3 user-service（消费端 + 常量扩展）

- `mq/LikeEventConsumer`（新，沿 NotificationEventConsumer 范式逐字，差异点仅四处）：
  - 消费组：`@Value("${rocketmq.notification.like-consumer.group:notification-like-consumer-group}")`（键风格沿既有 notification.consumer.group）
  - 订阅：`subscribe(RecordLikeEvents.TOPIC, RecordLikeEvents.TAG_LIKED)`
  - 处理：解析 LikeEventDTO → SETNX（dedupKey 前缀同 `notification:event:` + eventId，24h）→ `createNotification(event.getRecordOwnerId(), NotificationType.RECORD_LIKED, event.getRecordId(), "你的运动记录收到新的点赞", null, "RECORD_LIKED:" + event.getRecordId() + ":" + event.getLikerId())`
  - 其余逐字：MAX_RECONSUME_TIMES=3、CONSUME_FROM_FIRST_OFFSET、解析失败 ack 丢弃告警、失败删键 RECONSUME_LATER、PostConstruct 后台 30s 重连、PreDestroy 关闭、buildConsumer 包私有供单测
- `NotificationType`：追加 `RECORD_LIKED = "RECORD_LIKED"` + javadoc（三类→四类表述同步）
- `NotificationPreferenceService`：SUPPORTED_TYPES 追加 NotificationType.RECORD_LIKED（GET 四类视图 / PUT 校验 / 闸门判定同源）
- `application.yml`：rocketmq.notification 段新增 like-consumer.group 键（注释一句注明独立组语义）

### 2.4 前端（web）

- `notifications.page.vue` 偏好卡片三开关扩四开关：新增 `prefRecordLiked` ref(true) + applyPreferences 的 RECORD_LIKED 分支 + savePreferences payload 追加 `{ type: 'RECORD_LIKED', enabled: prefRecordLiked.value }` + 模板新增一行「收到点赞」a-switch（沿既有三行结构）
- 通知列表 type 列对 RECORD_LIKED 沿既有渲染零改动；client.ts / verdict.page.vue / typed-router.d.ts / notificationWs.ts / useNotificationBell.ts 零触碰

### 2.5 单测矩阵（offline 确定性，预计 +14 上下）

| 类 | 断言 |
| --- | --- |
| RecordLikeServiceTest（既有扩展） | firstLike 且非自赞 → publishLiked 以 (recordId, liker, owner) 调用一次；自赞（liker==owner）→ 零调用；幂等跳过（SADD=0）→ 零调用；unlike → 零调用 |
| LikeEventProducerTest（新立先例） | asyncSend 目的地=record-like-events:LIKED 且 payload 含 eventId/recordId/likerId/recordOwnerId（mock RocketMQTemplate + ObjectMapper 真序列化断言 JSON 字段）；提交异常 → 不抛出仅告警（沿 Mockito 口径） |
| LikeEventConsumerTest（新，沿 NotificationEventConsumerTest 模式） | buildConsumer 订阅参数（topic/tag/组/maxReconsumeTimes=3）；SETNX 已存在 → createNotification 零调用；LIKED → createNotification(recordOwnerId, RECORD_LIKED, recordId, 标题, null, RECORD_LIKED:{recordId}:{likerId})；解析失败 → ack 丢弃零调用；未知 eventType 跳过 |
| NotificationPreferenceServiceTest（既有扩展） | getPreferences 返回四类视图（缺行 RECORD_LIKED 补默认 true）；RECORD_LIKED 偏好关闭 → isNotificationEnabled false（闸门生效） |

## 4. 白名单（只改清单与主题串）

- 派发笔：`spec/changes/add-notification-like/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + `work/mailbox/tasks/TASK-188/spec.md`（四件套零改动入库），主题：`docs(spec): 派发 TASK-188 点赞通知提案与任务书`
- C-01：`api/src/main/java/com/sportverify/api/event/RecordLikeEvents.java`（新）、`api/src/main/java/com/sportverify/api/event/LikeEventDTO.java`（新）、`record-service/src/main/java/com/sportverify/record/mq/LikeEventProducer.java`（新）、`record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java`、`record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java`（扩展）、`record-service/src/test/java/com/sportverify/record/mq/LikeEventProducerTest.java`（新）、`user-service/src/main/java/com/sportverify/user/mq/LikeEventConsumer.java`（新）、`user-service/src/main/java/com/sportverify/user/enums/NotificationType.java`、`user-service/src/main/java/com/sportverify/user/service/NotificationPreferenceService.java`、`user-service/src/main/resources/application.yml`、`user-service/src/test/java/com/sportverify/user/mq/LikeEventConsumerTest.java`（新）、`user-service/src/test/java/com/sportverify/user/service/NotificationPreferenceServiceTest.java`（扩展）、`web/src/pages/notifications.page.vue`，主题：`feat(record): 点赞通知事件发布与消费落地（TASK-188）`
- C-02：tasks.json 全勾 + 本 spec §7 纯追加 + `work/mailbox/tasks/TASK-188/handoff.md` + `work/mailbox/PLAN.md` 纯追加，主题：`docs(mailbox): 登记 TASK-188 点赞通知验收与台账闭环（TASK-188）`

## 5. 受保护 token（29 项，口径 repo 全量 git grep -cF）

开工实测 SUM 为准（TASK-187 收口实测 2001 参照）；收口读数以**收口态实测**为准。只增不减。

## 6. 门禁结构

1. **预提交门禁（每笔前亲跑记录 rc）**：tasks.json 语法 rc=0；词面门四形态 ZERO_HIT rc=1 + 探针 rc=0 + 三态（正则字面量不入任何 tracked 文档与输出）；`git diff --check` rc=0；契约门在途 `--open TASK-188 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新增文件纯 LF 末尾换行完整（.java/.vue/yml 均新文件 LF；修改文件保持既有行尾）
2. **收口门禁（C-02 后亲跑留证）**：Java offline 全量新基线逐位登记（527 只增不减，增量预计落 record-service 与 user-service）+ `--static=record-service` 811 不增；零依赖增量核验（git diff 确认零 pom / 零 lockfile / 零 sql）；web 三件套（type-check rc=0 / build rc=0〔TMP 指仓内 .trae/esbuild-tmp〕/ typed-router 零漂移）+ `pnpm --dir web install --frozen-lockfile` rc=0；契约门无参 rc=0；只改清单全等；PLAN.md 自派发笔起纯追加
3. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（发布四态读数、消费端五态读数、偏好四类读数）/ 逐门实测表 / token 前后读数 / 未覆盖项 / 提交表（显式哈希，禁时效指针）
4. **真实链路联调（可选不判失败）**：本机起 broker + 双服务后真实点赞→通知往返；不可达则 UNDETERMINED（沿 182/185/186/187 口径）

## 7. 收口记录（执行侧 C-02 纯追加）

### 7.1 提交记录

- 派发笔：`c34eb941e02583f22344e5c5ef8ad351a4fe0a34`（`c34eb94`） `docs(spec): 派发 TASK-188 点赞通知提案与任务书`
- C-01 实施笔：`1b208b1c28ce44833f681230db555a43aaba19ab`（`1b208b1`） `feat(record): 点赞通知事件发布与消费落地（TASK-188）`
- C-02 台账笔：`（由回传报告以显式哈希给出，见 handoff §8）` `docs(mailbox): 登记 TASK-188 点赞通知验收与台账闭环（TASK-188）`

### 7.2 单测矩阵读数（offline，C-01 实施态实测）

- RecordLikeServiceTest：Tests run: 32, Failures: 0, Errors: 0, Skipped: 0（扩 4 条：firstLike 非自赞发布一次、自赞零发布、幂等跳过零发布、unlike 零发布）
- LikeEventProducerTest：Tests run: 3, Failures: 0, Errors: 0, Skipped: 0（新立先例 3 条：destination=record-like-events:LIKED、JSON 五字段真序列化、asyncSend 异常不外抛仅告警）
- LikeEventConsumerTest：Tests run: 6, Failures: 0, Errors: 0, Skipped: 0（新立 6 条：buildConsumer 订阅参数四断言、SETNX 已存在零调用、LIKED 落库调用与 dedupKey、解析失败 ack 丢弃、未知 Tag 跳过、失败重试删键）
- NotificationPreferenceServiceTest：Tests run: 9, Failures: 0, Errors: 0, Skipped: 0（扩 1 条：RECORD_LIKED 偏好关闭闸门断言 isNotificationEnabled 返回 false）

### 7.3 web 三件套读数（收口态）

- type-check：`vue-tsc --noEmit` rc=0（零错误）
- build：`vite build` rc=0（工作区 TMP=.trae/esbuild-tmp，耗时 13.60s，产物正常构建）
- typed-router：`git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零漂移）
- lockfile frozen：`pnpm --dir web install --frozen-lockfile` rc=0

### 7.4 Java 新基线与 811

- offline 全量新基线：`36/41/117/134/144/59/10` = **541**（基线 527 -> 541，净增 14），Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS
  - record-service：127 → 134（+7：LikeEventProducerTest 3 + RecordLikeServiceTest 扩 4）
  - user-service：110 → 117（+7：LikeEventConsumerTest 6 + NotificationPreferenceServiceTest 扩 1）
- static 静态检查：`--static=record-service` Checkstyle **811**（完全持平未增），rc=1 为基线违规模块预期
- 零依赖增量：git diff 确认后端零 pom 变更、前端零 lockfile 变更、零 SQL DDL

### 7.5 token 前后读数

- 开工实测：SUM=2001（29 项 repo 全量）
- 收口态实测：SUM=2001（只增不减，见 handoff §7）

### 7.6 联调留证

- 真实链路联调：中间件（RocketMQ broker、Redis）及双服务全栈未在本机拉起，真实链路往返留证登记为 **UNDETERMINED**（沿 TASK-182/185/186/187 口径，不判失败）；生产端、消费端与偏好闸门由离线 Mockito 单测矩阵与 Spring 容器加载完整覆盖。

### 7.7 未覆盖项与说明项

1. **best-effort 无补偿**（说明项，预注册）：MQ 不可达时点赞事件丢失仅告警——与 SUBMITTED 的 Feign 降级不同，点赞无下游闭环，丢失不损业务数据；broker 恢复后新点赞正常。
2. **dedupKey 终身一次语义**（说明项，预注册）：取消再赞不重复通知（同一人同一记录至多一条）；跨点赞者不合并（不同人各一条，由作者偏好开关兜底）。
3. **真实链路联调 UNDETERMINED**：中间件不可达时沿 182/185/186/187 口径登记。

### 7.8 三支裁决

- 裁决：**PASSED**（主支）
- 依据：API 规范事件与常量 + 生产端与消费端落地 + 偏好服务与前端开关扩展 + 单测矩阵 4 类全绿（全仓 527→541，+14）+ web 三件套全绿 + 静态 811 持平 + 契约门在途/无参 rc=0 + 全门禁通过。外部终验待推送后下一次外部门槛（第 30 次）CI 绿。

## 8. 开工读数（指导侧派发时基线）

- 派发笔基线 HEAD `f013594`（TASK-187 第 29 次门槛补记笔）；`origin/main...main` = `0 1`（f013594 待随本课题批推送）；工作树仅本任务在途派发材料
- offline 527（36/41/110/127/144/59/10）；static 811；token SUM 开工实测为准（TASK-187 收口态参照 2001）；契约门/词面门开工清
