# adopt-native-mq-retry

## Why

findings F09（TASK-128 核实「仍在」）：`verify-service` 的 SUBMITTED 消费者与 `leaderboard-service` 的
VERIFIED/REJECTED 消费者各维护一套**同构的自建重试轨**——消费失败时先 `getAtomicLong(retryKey)`
自计数，超过 3 次再用 `RocketMQTemplate.syncSend` 投**自建死信 topic** `record-verify-events-dlq`。
而 broker 在收到 `RECONSUME_LATER` 时本来就会按退避重投、并在超次后把消息转入 `%DLQ%<consumerGroup>`。
TASK-102 spec:34 早已写明目标（`setMaxReconsumeTimes(3)` + 删双轨），从未落地（同 TASK-128 结论）。

三处具体缺陷：

1. **自建计数键无 TTL**：`verify:retry:{eventId}` / `leaderboard:retry:{eventId}` 的 `AtomicLong`
   全仓零 `expire` 调用，随事件量无限堆积。
2. **自建死信投递会静默丢消息**：`sendToDlq` 的发送异常只 `log.error` 吞掉，且调用方随即
   「视为处理完成」返回——消息既不重投、也不在任何队列里。
3. **两套上限口径并存**：broker 原生重投（默认 16 次 + `%DLQ%`）与本地自建计数（3 次 + 自建 topic）
   同时生效，死信入口有两个、排查要跑两处。

## What Changes

- 两侧消费者统一 `setMaxReconsumeTimes(3)`：重投与超次入死信交回 RocketMQ 原生。
- 删除自建轨全套代码：`MAX_RETRY` 常量、`retryKey(...)`、`markRetryAndExceed(...)`、`sendToDlq(...)`，
  以及**仅为自建死信投递而注入**的 `RocketMQTemplate` 字段与构造参数。
- 失败分支收敛为一条：删去重键放行 → `rethrow`（监听器返回 `RECONSUME_LATER`）。
- 为让「原生重试参数」可离线断言，把 `startConsumer` 的行为中性部分抽成 `buildConsumer(ns)`
  （建实例 + 配置 + 订阅 + 注册监听，不 `start()`）；`startConsumer` 只负责 `start()` 与字段赋值。
- 测试：撤销 3+3 条只对自建轨成立的判别式（逐条清单与理由见 `work/mailbox/tasks/TASK-132/handoff.md`），
  以「原生参数=3」「订阅表达式未变」「零自建计数键」等判别式等量补齐，两侧模块用例数不低于基线。
- 新增本变更三件套：**MODIFIED**「校验事件与幂等」（重试上限与死信载体改口径）。

## Impact

- **重试上限逐数等价**：原生 `reconsumeTimes >= 3` 转 DLQ ⇔ 自建 `count > 3` 转 DLQ，
  两者都是「首次消费 + 3 次重投，第 4 次失败进死信」。
- **退避不变**：两套都走 broker 的 `messageDelayLevel`（自建轨同样返回 `RECONSUME_LATER`）。
- **死信载体变化**：`record-verify-events-dlq`（自建普通 topic，任何消费者都能订）→
  `%DLQ%verify-consumer-group` / `%DLQ%leaderboard-consumer-group`（broker 内建，
  与消费组绑定，经 `mqadmin` / 控制台按组查询）。
- **计数载体变化**：Redis 无 TTL 键 → broker 的消息属性 `reconsumeTimes`；
  Redis 故障不再影响重试上限判定，多实例也不再各判一次。
- **消除静默丢失缺口**：死信由 broker 转投，不存在「业务侧发送失败即丢」的路径。
- **无兼容期**：`RecordVerifyEvents.DLQ_TOPIC` 常量无仓外调用方，且本仓无消费该 topic 的消费者；
  常量本身不在本次改动集内（见 handoff「未决」）。
- **未触及**：eventId SETNX 幂等（含失败后删去重键放行）、TASK-131 的 outbox 与 relay 链路、
  broker / producer 配置、record→verify 降级链路。
