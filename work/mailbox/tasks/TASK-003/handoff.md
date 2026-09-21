【回传】TASK-003 构建修复完成，leaderboard-service 已恢复绿。另有 5 项主代码问题待拍板（均未自行改动）。

## 一、本轮改了什么

只改一个文件，符合范围约定：

- `leaderboard-service/src/test/java/com/sportverify/leaderboard/consumer/LeaderboardUpdateConsumerTest.java`（重写）

未改任何主代码、未加任何依赖、未删任何测试用例。

失败根因（复跑已确认，非推测）：`redissonClient.getBucket(...)` 没有被 stub，Mockito 返回 null，
`LeaderboardUpdateConsumer.checkAndSetDedupKey:103` 直接 NPE。原来 3 条用例里有 2 条因此失败，
第 3 条 `testConsumeWithException` 虽然"通过"，但它通过的正是同一条 NPE 路径 —— 属于用例外壳正确、
内核空转，本轮一并改掉。

## 二、复跑命令与结果

```
cd /d/code/sports && mvn -B -ntp -pl leaderboard-service -am test
```

| 模块 | 结果 |
|---|---|
| sport-verify-parent | SUCCESS |
| sport-verify-common | Tests run: 17, Failures: 0, Errors: 0 |
| sport-verify-api | SUCCESS |
| sport-verify-leaderboard-service | **Tests run: 34, Failures: 0, Errors: 0, Skipped: 0** |

`BUILD SUCCESS`。其中 `LeaderboardUpdateConsumerTest`：**Tests run: 4, Failures: 0, Errors: 0**。
（修复前：33 条用例、2 Failures；修复后 34 条，因为把死信阈值单独拆成了一条用例。）

## 三、四条断言各自测到什么

1. `firstTimeEventWritesDedupKeyAndRunsProcessing` —— `setIfAbsent` 返回 true；断言不抛异常，
   `getBucket("leaderboard:update:evt-first")` 恰好一次，且 TTL 被钉住为 `Duration.ofHours(24)`。
2. `duplicateEventIsSkippedWithoutProcessing` —— `setIfAbsent` 返回 false；断言命中"跳过"分支，
   且**没有**进入处理分支，重试计数 `getAtomicLong` 从未被调用。
3. `processingFailureIsRethrownAfterCountingRetry` —— 断言异常向上抛出，且
   `getAtomicLong("leaderboard:retry:evt-fail").incrementAndGet()` 被真实调用一次。
4. `retryOverThresholdStillRethrowsForDeadLetterRouting` —— 计数返回 4（> MAX_RETRY=3），
   覆盖死信阈值分支，断言仍向上抛出。

关于"断言未发生处理"需要如实说明一个约束：`processUpdate` 目前是 TODO 桩，只写日志、
不碰任何协作者，因此 Mockito 层面无法观测"处理有没有发生"。我改用 Logback `ListAppender`
捕获消费者自身 logger 的输出来断言。这不是空断言 —— 用例 1 用同一套机制**正向**断言到了
"执行榜单添加"，证明捕获链路有效，因此用例 2/3 的反向断言（不含"执行榜单"）一旦早退被移除就会失败。

用例 3/4 的异常注入点是 `operation=null` 时 `switch` 抛 NPE。这是桩体内唯一可控的失败注入点
（没有协作者可 stub）。我明确断言的是 `NullPointerException` 而不是笼统的 `RuntimeException`，
就是为了让"它为什么通过"这件事写在测试里、可被追溯，而不是靠一条意外 NPE 蒙过。

## 四、两套 MQ 栈并存：现状、是否重复、删除代价（未删任何一侧）

### RocketMQ 栈（本模块原有，`mq/LeaderboardEventConsumer.java`）
- 消费什么：Topic `record-verify-events`，Tag `VERIFIED || REJECTED`，独立消费组 `leaderboard-consumer-group`。
  生产方是 verify-service（判定通过/驳回后发出）。
- 是否可用：**是完整链路**。编程式 `DefaultMQPushConsumer`，解析 → eventId SETNX 去重 →
  分发到 `leaderboardService.applyVerified(recordId)` / `rollbackOnRejected(recordId)` →
  失败时删去重键 + 计数 → 超阈值用 `rocketMQTemplate.syncSend` 真投到 `record-verify-events-dlq`。
  namesrv 未就绪时不阻塞启动，后台 30s 重连。
- 基建：**docker-compose.services.yml 已 provision** rocketmq-namesrv + broker，三个服务都注入了
  `ROCKETMQ_NAME_SERVER`。
- 测试：`LeaderboardEventConsumerTest` 9 条用例全绿。

### RabbitMQ 栈（TASK-003 新建，`config/RabbitMQConfig.java` + `event/` + `consumer/`）
- 消费什么：队列 `leaderboard-update-queue`，消息体 `LeaderboardUpdateEvent`
  （eventId / userId / sportType / operation=ADD|REMOVE|UPDATE / pointsDelta / recordId）。
  语义是"榜单增量变更指令"，与上游"记录判定结果"是两种不同的东西。
- 是否可用：**目前收不到任何消息**。全模块没有 `RabbitTemplate` / `convertAndSend` / 任何生产方，
  队列是只读不写的空转。`processUpdate` 也没有业务逻辑。
- 基建：docker-compose.services.yml **没有** rabbitmq 服务，验收时得手工 `docker run`。
- 测试：本轮新写的 4 条用例只覆盖消费者去重/重试分支，没有覆盖真实收投。

### 是否功能重复
**当前不重复，但存在明确的收敛风险。** 两者输入语义不同（判定事件 vs 榜单增量指令），
所以现在不是同一件事做两遍。真正的重叠在**机制**上：两边各自实现了一遍"eventId SETNX 去重 +
Redis 重试计数 + 死信"，且用的是同一把键前缀 `leaderboard:retry:{eventId}`。
一旦 RabbitMQ 栈补上业务逻辑，同一个 recordId 可能被 RocketMQ 的 `applyVerified` 和
RabbitMQ 的 `ADD` 各加一次分 —— 那才是实质双写。机制重复、语义待分工，是这个决策的真实形状。

### 删哪一侧代价最小
**删 RabbitMQ 侧代价最小**，理由：
- 无生产方、无基建、无业务逻辑、无其他文件引用它，删掉不破坏任何现存链路；
- RocketMQ 侧删不掉 —— 它是当前唯一真正在驱动入榜/回滚的链路，且 compose 里有它的依赖。
- 影响面清单（若决定删）：`config/RabbitMQConfig.java`、`event/LeaderboardUpdateEvent.java`、
  `consumer/LeaderboardUpdateConsumer.java`、`consumer/LeaderboardUpdateConsumerTest.java` 四个新文件
  + `pom.xml` 的 `spring-boot-starter-amqp` + `application.yml` 第 55-72 行 rabbitmq 配置块
  + 我上一轮为它加进 `config/CacheConfig.java` 的 `objectMapper()` bean。约 350 行，全在 TASK-003 范围内。

保留 RabbitMQ 侧则需要补：生产方、compose 服务、`processUpdate` 业务接线，并想清楚它和
RocketMQ 的分工边界（建议：RocketMQ 承接跨服务领域事件，RabbitMQ 承接服务内部异步任务/削峰）。
**这条我没有拍板，等你定。**

## 五、发现但未修复的主代码问题（均超出"只改测试文件"范围，等拍板）

1. **`CacheConfig.objectMapper()` 是我上一轮为 TASK-003 加的，属于范围外改动，且是活的回归风险。**
   `JacksonAutoConfiguration` 带 `@ConditionalOnMissingBean`，这个裸 `new ObjectMapper()` 会**顶掉**
   Spring Boot 自动装配的那个已注册 JavaTimeModule 的 mapper。而 `LeaderboardUpdateEvent` 有
   `Instant timestamp` 字段 —— 裸 mapper 序列化 Instant 会抛 `InvalidDefinitionException`。
   全仓库其他服务都没有手写 ObjectMapper bean，这是唯一的例外。
   建议：**直接删掉这个方法**（消费者注入的 ObjectMapper 会由自动装配提供，功能更强）。
   它同时影响 Web 层 JSON 行为，且当前没有任何测试覆盖，所以本轮 BUILD SUCCESS 掩盖了它。

2. **`processUpdate` 是 TODO 桩，没接 `LeaderboardService`** —— 这就是"业务逻辑缺失"，
   按你的要求我停下来回传而不是自行补。补它需要注入 `LeaderboardService` 并决定 ADD/REMOVE/UPDATE
   分别映射到哪个既有方法（现成候选：`applyVerified` / `rollbackOnRejected`），涉及榜单写入语义，
   不该由测试修复顺带决定。

3. **`deleteDedupKey` 声明了但从未被调用**（`LeaderboardUpdateConsumer` 第 110 行）。后果是链路的：
   首次处理写进去重键 → 处理抛异常 → 键不删 → 重投时 `setIfAbsent` 返回 false → 被当成"重复事件"
   静默跳过 → 这条榜单更新**永久丢失，且永远不会进 DLQ**。RocketMQ 侧在 catch 里是调用了
   `deleteDedupKey` 的（`mq/LeaderboardEventConsumer:184`），RabbitMQ 侧漏了。
   顺带：这意味着我本轮的用例 3/4 只能断到"异常抛出 + 计数自增"，断不到"重投后能真正重试成功"。

4. **`application.yml` 的 `acknowledge-mode: manual` 与消费代码不匹配**（也是我上一轮加的）。
   监听方法签名里没有 `Channel` 参数、也没有任何 `ack()`/`nack()` 调用，manual 模式下消息永远不会被确认；
   同时 `simple.retry` 与 manual ack 是冲突配置。RabbitMQ 侧的"失败重投 / 进死信"这套语义在运行时
   并不成立 —— 本轮用例 4 断言到的只是那条日志，不是真实死信投递。

5. **`RabbitMQConfig` 的拓扑有两处误用**：
   - `x-max-length` 被设成 `MAX_RETRY`（3）—— 这是队列最大长度，不是重试次数。队列只留 3 条消息，
     超出的队头消息会被丢弃或直接死信，压测下会静默丢榜单更新。
   - `mainBinding` 把**主队列**绑到了**死信交换机**上（routing key = `leaderboard-update-queue`）。
     死信交换机的职责是把消息送进 DLQ，主队列反向绑上去会形成"死信又回到主队列"的回路。
     另外全程没有声明主业务交换机，生产方只能靠 default exchange 用队列名投递。

## 六、其他遗留

`leaderboard-service/src/test/java/com/sportverify/leaderboard/consumer/LeaderboardUpdateConsumerTest.java.bak`
是一个陈旧备份文件（本轮开始前就在，未纳入 git）。它不影响构建，但会长期误导后来人，
可以删，只是不在我本轮范围内，先报给你。

## 七、结论

- 构建：绿。`Tests run: 34, Failures: 0, Errors: 0` → `BUILD SUCCESS`，命令见第二节。
- 范围：仅测试文件。第五节 5 项一律未动，等你逐项拍板（其中第 1 项是我自己上一轮的越界改动，建议优先处理）。
- 未做集成验证：没有跑过真实 RabbitMQ broker，也没有 `@SpringBootTest` 加载过 Spring 上下文，
  所以"配置能起、消息能通"这句话我没有证据，不敢写。
