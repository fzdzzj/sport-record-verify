# TASK-132 adopt-native-mq-retry——消费重试去双轨（RocketMQ 原生重试替换自建计数 + 自建 DLQ）

## 目标

findings F09（TASK-128 核实「仍在」）：verify 侧 `VerifyEventConsumer` 与 leaderboard 侧
`LeaderboardEventConsumer` 各有一份同构的「自建重试双轨」——消费失败时先用
`redissonClient.getAtomicLong(retryKey)` 自计数（**无 TTL**，随事件量无限堆积），
超 3 次再用 `RocketMQTemplate.syncSend(DLQ_TOPIC, …)` 投自建死信 topic；
而 broker 本身在返回 `RECONSUME_LATER` 时就按退避重投、超次自动进 `%DLQ%<consumerGroup>`。
TASK-102 spec:34 早已写明「`setMaxReconsumeTimes(3)` + 删双轨」，从未落地（同 TASK-128 结论）。

本任务把两侧消费者统一为 broker 原生重试：`setMaxReconsumeTimes(3)`，
删除 retryKey 计数与自建 DLQ 投递代码，死信语义交由 `%DLQ%<consumerGroup>` 承担。

## 口径裁定（本任务设计基线）

1. **重试上限**：`setMaxReconsumeTimes(3)`——与自建口径「失败第 4 次进 DLQ」逐数等价
   （原生：`reconsumeTimes >= 3` 时 broker 转 DLQ；自建：计数 `> 3` 时本地转 DLQ）。
   两侧 consumerGroup 不同 → 两条原生死信 topic：
   `%DLQ%verify-consumer-group`、`%DLQ%leaderboard-consumer-group`。
2. **保留不动**：eventId SETNX 去重（含失败后删去重键放行重投）、
   `RECONSUME_LATER` 重投路径、消费失败 rethrow、traceId 还原、TASK-131 的 outbox 链路。
3. **删除**：`MAX_RETRY` 常量、`retryKey(...)`、`markRetryAndExceed(...)`、`sendToDlq(...)`，
   以及仅为自建 DLQ 而存在的 `RocketMQTemplate` 依赖（字段 + 构造参数）。
4. **判别式缝**：原生重试参数只能从**未启动的消费者实例**上读（`getMaxReconsumeTimes()`），
   故把 `startConsumer` 的行为中性部分抽成 `buildConsumer(ns)`（建实例 + 配置 + 订阅 + 注册监听，
   不 `start()`），`startConsumer` 只负责 `start()` 与字段赋值。该抽取不改变运行时行为，
   是「配置断言」唯一可行的观测面。

## 红绿取证

- 红（改前）：新增判别式①消费者配置 `getMaxReconsumeTimes()==3`（基线默认 `-1`）→
  断言级红 `expected: <3> but was: <-1>`；判别式②消费失败路径零 `getAtomicLong`（retryKey）
  且零 `syncSend`（自建 DLQ）→ 断言级红，Mockito 指名真实调用点。
- 绿（改后）：同两条断言绿；既有用例零回退；模块用例数不低于基线（删 N 增 M 重排，不允许净减）。
- 变异验证：摘除 `setMaxReconsumeTimes(3)` → 复现配置断言红 → 还原 `sha256sum -c` OK + `cmp` 零差异。

## 只改清单

- verify-service/src/main/java/com/sportverify/verify/consumer/VerifyEventConsumer.java
- verify-service/src/test/java/com/sportverify/verify/consumer/VerifyEventConsumerTest.java
- leaderboard-service/src/main/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumer.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/mq/LeaderboardEventConsumerTest.java
- spec/changes/adopt-native-mq-retry/proposal.md
- spec/changes/adopt-native-mq-retry/tasks.json
- spec/changes/adopt-native-mq-retry/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-132/spec.md
- work/mailbox/tasks/TASK-132/handoff.md
- work/mailbox/PLAN.md

## 停止边界

- 不动消费幂等逻辑（eventId SETNX 去重保留）
- 不动 TASK-131 刚接好的 outbox 链路（verify 侧 `VerifyOutbox*` / `VerifyEventProducer` 一行不碰）
- 不动 record→verify 降级链路、不动既有表结构与 broker 配置
- 不 push

## 复跑口径

- 定向：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`
  与 `--pl leaderboard-service test`（先红后绿 + 变异）
- 静态检查（leaderboard 唯一接入模块）：`--static=leaderboard-service`（删除未被读的字段后须不新增违规）
- offline 全量：`bash scripts/verify/mvn-verify.sh --mode=offline test`（逐模块用例数）
- 契约 rc=0（在途 `--baseline=<开工基线>` + 收口后无参数）
- 词面自检双 locale

## 完成定义

- 两侧消费者均 `setMaxReconsumeTimes(3)`，自建计数与自建 DLQ 代码全删（编译面即证明）
- 语义等价说明（3 次上限逐数等价 + 退避/载体差异如实登记）写进 handoff
- 撤销用例逐条列清单与理由；模块用例数不低于基线
- 红/绿/变异三段取证齐备，既有用例零回退
- 规范三件套 + 台账两件套 + PLAN 收口记录（绑定 commit 与门槛来源）
