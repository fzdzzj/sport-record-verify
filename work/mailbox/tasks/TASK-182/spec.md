# 任务书：TASK-182 add-notification-center（通知中心后端）

派发：指导 Agent（2026-10-08）。派发笔（本任务书 + 提案三件套）由指导侧亲笔；执行侧代为入库后实施 C-01…C-04 四笔，不得改动派发笔内容。

## 0. 硬约束与红线

1. **改动面唯一**：仅 user-service `src/**`、`user-service/pom.xml`、`user-service` 配置文件、`sql/01-user-db.sql`（幂等追加）。api 模块与其余五服务 `src/**`、`ci.yml`、`scripts/`、root pom 零触碰。
2. **MQ 纪律**：topic / tag / 既有消费组名 / 批次 / 周期 / relay 参数零改动；`notification-consumer-group` 为纯新增。消费范式逐字沿 `LeaderboardEventConsumer`（编程式 `DefaultMQPushConsumer`，规避 rocketmq-spring 2.3.1 监听容器不兼容点），不自建重试计数、不自建死信 topic。
3. **依赖纪律**：仅新增 `redisson-spring-boot-starter`、`rocketmq-spring-boot-starter` 两依赖（版本由 root `dependencyManagement` 既有锁定）；**不引入新 Maven 插件**。
4. **唯一 mvn 入口**：官方门禁只经 `bash scripts/verify/mvn-verify.sh`；禁并发 mvn。
5. **测试基线 450 只增不减**（36/41/33/127/144/59/10，user-service 41 只增，新总值逐位登记）；`--static=record-service` 811 不增。
6. **bash 纪律**：仅 `D:\git\Git\bin\bash.exe` 且仅执行脚本文件；临时 `*.tmp` 用毕删。
7. **git 纪律**：禁 push / PR / `add -A` / stash；逐路径 add；提交信息 `-F` 文件且无 BOM（Write 工具先例）；主题串先写入台账、再逐字用作 `-F` 消息。
8. **token 纪律**：受保护 29 项只增不减（开工实测登记，收口 ≥ 开工值）；PLAN.md 纯追加。
9. **措辞纪律**：新增文档零禁词、零词面门正则字面量；测量类数字只登记绝对值。
10. **停止条件**：发现需改 api 模块 / 既有消费者 / MQ 既有参数才能完成 ⇒ 停手回报；本地任何既有测试转红且非本任务新增断言 ⇒ 停手回报；`FriendService.accept()` 现场与派发笔假设（事务内两写）不符 ⇒ 停手回报。

## 1. 背景与史实（现状证据）

- 社交闭环断点：全仓零通知代码（Java / 配置 / Web 控制台检索零命中）；好友申请通过、判定出结果，收件方只能轮询列表。
- 事件流现成：判定事件走 verify outbox → RocketMQ `RecordVerifyEvents.TOPIC`（TAG_VERIFIED / TAG_REJECTED）→ leaderboard 独立消费组；`VerifyEventDTO` 已携带 `userId`（收件人无需回查）。
- 消费范式先例：`LeaderboardEventConsumer`（`leaderboard-service/.../mq/`）——编程式消费、SETNX 去重 + 锚点行双保险、原生重试与 DLQ、解析失败 ack 丢弃、后台 30s 重连、`buildConsumer` 拆缝给单测；其单测 `LeaderboardEventConsumerTest`（纯 Mockito）与 `RocketMqBrokerRoundTripIT`（真 broker 往返，环境变量前缀 `TASK110_IT_`）均可直接照抄。
- 好友接线点：`FriendService.accept(Long requestId)`（事务内「申请状态更新 + friendship 插入」，ADR-0009 边界）；`reject()` 不通知。
- Web 铃铛位列二期（TASK-183 候选），本任务不碰前端。

## 2. 实施设计（预注册）

### 2.1 notification 表（sql/01-user-db.sql 尾部幂等追加）

```sql
-- 通知表（TASK-182 add-notification-center）
CREATE TABLE IF NOT EXISTS `notification` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '通知ID',
    `user_id`    BIGINT       NOT NULL COMMENT '收件人',
    `type`       VARCHAR(30)  NOT NULL COMMENT '类型：RECORD_VERIFIED 记录通过 / RECORD_REJECTED 记录驳回 / FRIEND_ACCEPTED 好友通过',
    `source_id`  BIGINT       NOT NULL COMMENT '聚合根ID（recordId / requestId）',
    `title`      VARCHAR(100) NOT NULL COMMENT '标题',
    `content`    VARCHAR(255) DEFAULT NULL COMMENT '内容',
    `is_read`    TINYINT      NOT NULL DEFAULT 0 COMMENT '已读：0 未读，1 已读',
    `dedup_key`  VARCHAR(80)  NOT NULL COMMENT '幂等键：判定事件=MQ eventId；好友=FRIEND_ACCEPTED:{requestId}',
    `created_at` DATETIME     NOT NULL COMMENT '创建时间',
    `read_at`    DATETIME     DEFAULT NULL COMMENT '已读时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dedup` (`dedup_key`),
    KEY `idx_user_read` (`user_id`, `is_read`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='通知表';
```

标题/文案基调：客观陈述（如「你的运动记录已通过校验」「好友申请已被通过」），不携带判定细节与阈值（细节在业务列表里看）。

### 2.2 服务层与好友接线

- `NotificationService`：`createNotification`（幂等 `INSERT IGNORE`，以受影响行数判断是否首次）、`pageNotifications(userId, page, size)`（id 倒序）、`unreadCount(userId)`（COUNT + 索引）、`markRead(userId, id)`（UPDATE 带 `user_id` 归属条件，非本人 0 行）、`markAllRead(userId)`。
- `FriendService.accept()`：既有事务内追加 `createNotification`（收件人 = 申请 from_user）；`reject()` 不动。

### 2.3 判定事件消费者

- `NotificationEventConsumer`（user-service `mq/` 或 `consumer/` 包，随该服务既有包风格）：topic = `RecordVerifyEvents.TOPIC`，tags = `TAG_VERIFIED || TAG_REJECTED`，组 `notification-consumer-group`（`rocketmq.notification.consumer.group` 可配置）；范式逐字沿 `LeaderboardEventConsumer`（SETNX eventId 去重 24h TTL + 失败删键放行重投 + `uk_dedup` 兜底 + RECONSUME_LATER + `maxReconsumeTimes=3` + 解析失败 ack 丢弃 + 后台重连 + `buildConsumer` 拆缝）。
- yml：沿 leaderboard-service 的 rocketmq 配置块模式（name-server 占位 / 环境变量覆盖，IT 环境变量前缀 `TASK182_IT_`）。
- 单测沿 `LeaderboardEventConsumerTest` 范式（buildConsumer 配置断言 + handleMessage 分发 / 去重 / 失败路径，Mapper / Redisson mock、生产侧真实对象不用）。

### 2.4 REST 与 IT

- `NotificationController`：分页列表 / 未读数 / 单条已读 / 全部已读四接口；**路由前缀与取用户方式沿 user-service 既有 controller 内规**（`FriendController` / `AuthController` 先例），不新造入口形态。
- `NotificationConsumerRoundTripIT`：沿 `RocketMqBrokerRoundTripIT` 模式——真 broker 往返，投递 VERIFIED 事件断言通知落库、重复投递断言幂等（`*IT` 命名 + `TASK182_IT_` 环境变量前缀惯例）。

### 2.5 三支判定

- **PASSED**：功能四件套齐（表 / 消费者 / REST / IT）+ 450 只增不减逐位登记 + 811 不增 + 全门禁绿 + 双幂等证据（单测断言 + IT 断言）。
- **FAILED**：实施引入既有测试红或基线漂移 ⇒ 回滚本任务全部业务改动、如实登记。
- **外部终验**：推送后下一次外部门槛 CI 绿；红则按签名归因（不带预设，禁重试刷绿）。

## 3. 开工读数（时序差惯例）

- 任务书落盘时点 HEAD = `729de8d4cb6585687091d5960f26ebcee48a08ff`（origin/main = 本地，`0 0`）；派发笔入库后基线前移（`0 1`）。
- 离线基线 450（36/41/33/127/144/59/10）；静态基线 811。
- 上一次外部门槛：run `37769662324`（HEAD `729de8d`，**绿**，第 23 次——TASK-180 树 + surefire 加固一并终验达成）。

## 4. 白名单

- **派发笔（指导侧亲笔，执行侧只入库零改动）**：`spec/changes/add-notification-center/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + 本任务书。主题：`docs(spec): 派发 TASK-182 通知中心提案与任务书`。
- **C-01 存储与好友接线笔**：`sql/01-user-db.sql` + user-service 通知域基础件（entity / mapper / service）+ `FriendService` 接线 + 对应单测（含 FriendService 既有测试适配）。主题：`feat(user): 通知存储与服务层并接线好友通过通知（TASK-182）`。
- **C-02 消费者笔**：`user-service/pom.xml`（两依赖）+ user-service 配置文件 + `NotificationEventConsumer` + 单测。主题：`feat(user): 判定事件通知消费者接线 RocketMQ（TASK-182）`。
- **C-03 读取入口与 IT 笔**：`NotificationController` + controller 单测 + `NotificationConsumerRoundTripIT`。主题：`feat(user): 通知读取接口与真 broker 往返 IT（TASK-182）`。
- **C-04 台账笔**：`spec/changes/add-notification-center/tasks.json` 闭环 + 本任务书收口记录纯追加 + `handoff.md` + `PLAN.md` 纯追加（含第 23 次门槛绿读数折入登记，先例：门槛 21 折入 TASK-180 台账）。主题：`docs(mailbox): 登记 TASK-182 通知中心验收与台账闭环（TASK-182）`。
- **禁触**：api 模块、其余五服务 `src/**`、root pom、`ci.yml`、`scripts/`。

## 5. 受保护 tokens 基线（29 项，开工实测登记，只增不减）

`13.4`、`18.0`、`73.93`、`68.8`、`6315`、`1.8612`、`3.3066`、`5.7056`、`9.408`、`36525962432`、`36586847965`、`36438897772`、`36399582548`、`36098038547`、`2806`、`598`、`36736221648`、`36808102571`、`36821040708`、`36845152965`、`36871294588`、`36880083885`、`36958994260`、`36976873215`、`36992632143`、`36995450125`、`37008317295`、`37021305016`、`37591580687`（TASK-181 收口参照值：22/23/22/22/19/18/18/18/18/18/17/18/17/17/24/17/16/11/9/8/8/9/9/9/8/6/7/8/7；开工 `grep -cF` 实测为准）。新 run 号（如 `37769662324`）以文本登记，不扩受保护集合。

## 6. 门禁与提交结构

1. **预提交门禁（每笔前亲跑记录 rc）**：tasks.json 语法 rc=0；词面门四形态 ZERO_HIT rc=1 + 探针 rc=0 + 三态；`git diff --check` rc=0；契约门在途 `--open TASK-182 --baseline=<派发笔哈希>`（rc=1 时按 TASK-181 §1.2.4 登记的 extract_claims 盲区口径逐任务核对自身判据 B 原文行）；token 29 项只增不减；新增文件纯 LF 末尾换行完整。
2. **收口门禁（C-04 后亲跑留证）**：offline 全量新基线逐位（user-service 增量登记）；`--static=record-service` 811 不增；契约门无参 rc=0；只改清单全等；PLAN.md 自派发笔起纯追加。
3. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（双幂等断言原文、IT 往返读数）/ 逐门实测表 / token 前后读数 / 未覆盖项 / 提交表（显式哈希，禁时效指针）。
4. **IT 执行**：`NotificationConsumerRoundTripIT` 需真 RocketMQ，沿 `RocketMqBrokerRoundTripIT` 的环境准备模式；若本机 broker 不可用，按 UNDETERMINED 登记不得记通过（先例纪律）。
