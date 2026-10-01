# 变更增量规格：outbox relay 可选有界分块标记 SENT

## 1. 变更背景与授权来源

- **显式引用 TASK-153 NO-GO**：TASK-153 在现行规格（`wire-verify-outbox` 与 `fix-verify-outbox-poison-head-of-line`）下判 NO-GO（`docs/perf/判别-outbox-批末标记SENT-语义边界.md` §4），指出「批末标记 SENT」会导致重复投递窗口扩大（至整批）、SENT 独立连接可见性推迟、`sent_at` 语义漂移压平与标记失败整批重投四项语义变化，且消费端幂等不能自动视作投递侧扩大重复窗口的授权。
- **本轮规格授权依据**：本授权**不是**来自消费端 `eventId` SETNX 与业务锚点幂等现状，而来自产品决策（用户 2026-09-30 原话「都允许」，所回答的问题为「能否接受崩溃时多发若干条重复事件」）。
- **已接受的运维代价**：TASK-153 指出的消费端去重无法消除的三条运维影响，已在本授权中逐条登记为已接受的运维代价：
  1. 重复网络投递与消费端 SETNX 命中日志；
  2. 下轮重选已送达行的额外负载；
  3. 崩溃恢复后库内不可区分已送达/未送达（运维无法单凭 outbox 状态判断真实投递进度）。
- **性能口径纪律**：本 delta 内不得出现任何性能收益数字，不得声称任何延迟/吞吐改善，不得把成本占比写成可获得收益。

---

## 2. 需求增量（Delta）

### ADDED Requirement: relay 可选有界分块标记 SENT

WHEN `verify.outbox.relay-batch-mark-enabled=true`（默认 `false`）
THEN 已成功发送行的 SENT 标记 MAY 按 chunk（`relay-batch-mark-chunk-size`，默认 `25`，钳位 `[1, batch-size]`）合并为一条条件 UPDATE，条件 MUST 保持 `status = 'PENDING'`（幂等），并 MUST 逐条登记被授权的四项语义变化及其上界：

1. **重复投递窗口上界**：进程异常崩溃时，已由 broker 成功接收但尚未执行 chunk 标记的事件行数上界为 **chunk-size**（逐行路径为 1，TASK-153 候选为 batch-size=100）。下轮重投时 MUST 沿用行内原有 `eventId`，使消费端去重键稳定。
2. **SENT 可见性延迟上界**：独立连接可见已成功发送事件为 SENT 的延迟上界为一个 chunk 的发送时长（逐行路径为每行发送返回立即独立提交可见）。
3. **`sent_at` 时间语义**：同 chunk 内所有行的 `sent_at` 统一记录为该 chunk 批量 UPDATE 执行时的数据库时间（`NOW()`），**chunk 内同值**；该值与 DDL 注释「投递成功时间」的偏差被本规格显式授权，且代码 javadoc 与日志 MUST 予以披露。
4. **标记失败重投语义**：chunk 批量标记 SQL 真失败（抛出持久化异常）时，该 chunk 内已投递行**全部保留 PENDING**、下轮整块重投；系统 MUST 对该 chunk 内的每个 id 各调用一次 `incrRetry` 以保持失败计数与重试耗尽判定语义，且异常 MUST NOT 外逃打断整轮 relay。

#### Scenario: 成功行按 chunk 分块批量标记
GIVEN `verify.outbox.relay-batch-mark-enabled=true` 且 `relay-batch-mark-chunk-size=25`
WHEN relay 取出一批 100 行待投递事件且全部发送成功
THEN 恰好调用 4 次 `markSentBatch`，每次传入 25 个有序 ID，条件含 `status = 'PENDING'`
AND 每次批量更新返回后 chunk 内行状态在独立连接上可见为 SENT

#### Scenario: 进程崩溃后重复投递有界收窄
GIVEN `verify.outbox.relay-batch-mark-enabled=true` 且 `relay-batch-mark-chunk-size=25`
WHEN chunk 内前 k 行（k <= 25）发送成功后进程崩溃或退出
THEN 下轮 `selectPendingBatch` 重投行数上界为 25（收窄自 batch-size 100）
AND 重投行的 `eventId` 与崩溃前逐字一致

#### Scenario: 标记 SQL 异常补偿失败计数
GIVEN `verify.outbox.relay-batch-mark-enabled=true` 且某 chunk 包含 n 行已发送事件
WHEN 该 chunk 执行 `markSentBatch` 抛出 SQL 异常
THEN 该 chunk 全部 n 行保持 PENDING 状态
AND 系统对该 chunk 内每个 ID 逐一调用一次 `incrRetry`
AND totals.failed 增加 n，异常被隔离，后续 chunk 继续处理

---

### ADDED Requirement: 默认关闭等价性

WHEN `verify.outbox.relay-batch-mark-enabled=false`（默认）
THEN relay MUST NOT 调用 `markSentBatch`，MUST NOT 创建新分块集合对象，调用序列与引入前**逐字等价**；
`application.yml` MUST NOT 出现该键（默认值只存在于 `@Value` 注解）。

#### Scenario: 关闭状态保持逐行等价
GIVEN `verify.outbox.relay-batch-mark-enabled=false`（默认）
WHEN relay 处理一批待投递事件
THEN 行为与变更前逐字等价：每行发送成功立即调用 `markSent` 独立提交，崩溃重复投递窗口至多 1 行
AND `application.yml` 中无 `relay-batch-mark-enabled` 或 `relay-batch-mark-chunk-size` 键
