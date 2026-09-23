# wire-verify-outbox

## Why

findings F03（TASK-128 核实「仍在」，原提交 `a048745` 只落骨架）：判定事件的产生与投递之间没有原子性——
`VerifyService.verify`（`:105-109`）与 `reviewAppeal`（`:233-235`）先 upsert 判定结果、再同步直发 RocketMQ，
而 `VerifyEventProducer` 的发送失败只 `log.error` 吞掉（`:57-60`）。后果是「判定 PASSED 已落库、事件却永远没发出」，
榜单侧没有任何补偿入口 → 该记录里程永久不入榜。

仓库里已有本地消息表骨架（`VerifyEventOutbox` / `VerifyEventOutboxMapper` / `VerifyOutboxRelay`），
但全仓无任何代码写 outbox 行、`sql/03-verify-db.sql` 无 `verify_event_outbox` DDL：
relay 每个周期扫一张不存在的表并报 `BadSqlGrammarException`（`logs/verify.out:84`），
判定的直发路径照旧，相当于补偿机制空转。TASK-102 spec:33 已写明表结构与改造方向，本次把它落地。

## What Changes

- `sql/03-verify-db.sql` 追加 `verify_event_outbox` 建表（自增 id / event_id 唯一 / topic / tag / payload JSON /
  trace_id / status PENDING|SENT / retry_count / created_at / sent_at；`IF NOT EXISTS` 幂等）。
- `VerifyOutboxService` 从「upsert + 同步直发」改为真写 outbox 行：提供
  「判定结果 upsert + outbox insert」与「申诉终判行更新 + outbox insert」两个事务方法（`rollbackFor=Exception`），
  两者都只包本地 DB 写，不含远程调用（与 ADR-0009「无本地长事务」一致）。
- `VerifyEventProducer` 拆成写侧与投递侧：写侧 `newPendingRow(...)` 生成 eventId（UUID）与完整 payload、
  记录当前 MDC traceId，产出 PENDING 行；投递侧同步发送供 relay 调用，失败不再吞异常（交由 relay 记账重试）。
  原来的 `publish(...)`（同步直发 + catch-log）删除。
- `VerifyService` 两处发事件改为委托 `VerifyOutboxService`；类内不再持有 producer，判定主链路零同步发送。
- `VerifyOutboxRelay` 改为经 producer 投递（relay 是唯一出口，发送细节只此一处）。

## Impact

- **可靠性**：判定/终判事件与结果行同生共死，MQ 短暂故障由 relay 重试补偿，超阈值保留行供人工处理；
  不存在「判定落库但事件不存在」的行。
- **延迟语义变化（登记）**：事件从「判定线程同步毫秒级到达」变为「relay 周期（默认 5s）内到达」；
  榜单侧既有 10min 结算定时纠偏仍是最终一致的兜底，本次不改 leaderboard 消费端。
- **eventId 稳定性**：eventId 在 outbox 写入时生成一次并落行，relay 重发沿用行内 eventId——
  消费端 SETNX 幂等键在重试间稳定（此前每次 publish 都会生成新 UUID）。
- **无兼容期**：`publish(...)` 无仓外调用方（全仓唯一调用点即本次入选的两处），删除不产生外部破坏。
- **未触及**：record→verify 降级链路（`VerifyDegradeService`）、leaderboard 消费端（F09 归 TASK-132）、
  既有表结构。
