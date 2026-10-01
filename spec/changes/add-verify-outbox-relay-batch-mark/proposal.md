# 变更提案：outbox relay 可选有界分块标记 SENT

## 1. Why

在现行生产架构中，`VerifyOutboxRelay` 负责定时扫描 `verify_event_outbox` 表中的 PENDING 记录并通过 RocketMQ 可靠投递。现行实现采用逐行投递、发送成功后立即逐行条件更新 `markSent`（`UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() WHERE id = ? AND status = 'PENDING'`）。

在 TASK-153 语义判别中，将「逐行标记」改为「整批批末统一标记」在现行规格下被裁定为 **NO-GO**，原因是引发了未经授权的 4 项语义变化：重复投递窗口从至多 1 行放大至整批（至多 100 行）、SENT 对独立连接可见性延迟、`sent_at` 语义漂移且批内同值、批末单条标记失败导致整批重投且归因丢失；且判别报告明确指明「消费端幂等不能自动视作投递侧扩大重复窗口的授权」。

2026-09-30，用户经评估后作出明确产品决策授权（原话「都允许」，所回答的问题为「能否接受崩溃时多发若干条重复事件」）。为了在规格上显式、有界地授权相关语义变化，同时避免整批标记的过大风险，本提案提出「有界分块标记 SENT（chunk 默认 25，钳位在 `[1, batch-size]`）」的新规格授权与默认关闭实现。

已逐字核对主规格 `spec/specs/sport-record-verify/spec.md` 相关需求（L922「该事件先以 PENDING 行落 verify_event_outbox、再由 relay 按行内 topic/tag 投递并标 SENT」、L1008「成功行仍按原有条件标为 SENT，失败行仍按原有条件增加 retry_count」），既有文字未逐字强制「每行投递成功后立即标记 SENT」，故**无需 MODIFIED**，仅以 **ADDED Requirement** 形式增补规格。

---

## 2. What Changes

1. **增量规格授权**：
   - 增加 `ADDED Requirement: relay 可选有界分块标记 SENT`：当 `verify.outbox.relay-batch-mark-enabled=true` 时，成功发送行的标记允许按 chunk（`relay-batch-mark-chunk-size`，默认 25，钳位 `[1, batch-size]`）合并为一条批量条件 UPDATE，条件保持 `status = 'PENDING'`（保证幂等）。
   - 逐条授权四项语义变化及其新上界：
     ① 崩溃后重复投递窗口上界收窄为 **chunk-size**（逐行路径为 1，TASK-153 候选为 batch-size=100）；
     ② SENT 对独立连接的可见性延迟上界为一个 chunk 的发送时长；
     ③ `sent_at` 为 chunk 标记时的 `NOW()`，同 chunk 内同值，显式授权其偏离 DDL 注释「投递成功时间」；
     ④ chunk 批量标记 SQL 真失败时，该 chunk 内已投递行留 PENDING、下轮整块重投，且必须对 chunk 内每个 id 各调用一次 `incrRetry` 维持失败计数语义。
   - 增加 `ADDED Requirement: 默认关闭等价性`：开关默认关闭（`false`），关闭时行为与原有逐行处理逐字等价；生产配置文件 `application.yml` 不显式配置此两键（仅保留在代码 `@Value` 默认值中）。

2. **生产代码实现（默认关闭）**：
   - `VerifyEventOutboxMapper`：纯新增 `int markSentBatch(@Param("ids") List<Long> ids);`，使用 MyBatis `<script><foreach>` 和 `#{id}` 占位符进行安全参数化更新。
   - `VerifyOutboxRelay`：新增 `@Value("${verify.outbox.relay-batch-mark-enabled:false}")` 和 `@Value("${verify.outbox.relay-batch-mark-chunk-size:25}")`；非法 chunk-size 自动钳位至 `[1, batch-size]` 并只记录一条 WARN；关闭路径调用序列逐字保持；开启路径在 worker 局部列表中累积 id 并按 chunk 刷出；`affected < ids.size()` 仅记 WARN 不抛错不 incrRetry；标记抛错时对 chunk 内每个 id 逐一补偿 `incrRetry` 且异常不外逃；诊断纯追加分量 `markBatchCalls` 与 `markBatchRows`。

---

## 3. Impact

- **生产行为**：本轮零生产行为变化（新键默认关闭且不写入 `application.yml`，不修改现有三个 Mapper 方法及 SQL，不合并任何 delta 进主规格）。
- **运维影响**：显式登记并接受 TASK-153 指出的消费端去重无法消除的三条运维影响：
  1. 重复网络投递与消费端 SETNX 命中日志；
  2. 下轮重选已送达行的额外负载；
  3. 崩溃恢复后库内不可区分已送达/未送达（运维无法单凭 outbox 状态判断真实投递进度）。
- **性能口径纪律**：本提案与 delta 中**严禁出现任何性能收益数字**，未测性能，不得把成本占比写成可回收收益。
- **日志观测面（TASK-166 F1 补录的被授权变化）**：开启态下成功行不再有逐行 INFO 日志（原 `outbox 事件投递成功：id=…, eventId=…, tag=…` 由每 chunk 一条汇总日志取代）；失败行的逐行 WARN 日志保持不变。两点影响登记：① 运维无法再按 `eventId` 从日志定位单条投递时刻；② TASK-163/164 的 M1 空档机制门依赖逐行日志时间戳，**在开启态不可用**，后续任何判别必须改用诊断口径的锁内吞吐。

---

## 4. 判定与停止条件

1. 默认关闭等价性破坏（单测或配置测试失败）⇒ **停止**。
2. 保护件测试（`VerifyOutboxRelayTest`、`VerifyOutboxRelayConcurrencyTest`、`VerifyEventOutboxMapperSqlContractTest`）任一报错或代码被改动 ⇒ **停止**。
3. `VerifyEventOutboxMapper` 既有 SQL 或方法被篡改 ⇒ **停止**。
4. `application.yml` 被写入新键 ⇒ **停止**。
5. 未经授权开启 `verify.outbox.relay-batch-mark-enabled=true` 或私自合并 delta ⇒ **停止**。

---

## 5. 预登记反例 5 条与触碰说明

① **分块扩大崩溃后重复投递窗口（上界 chunk-size）**：已触碰并由新规格显式授权。开启开关时，由于先发送后分块标记，若发送前 k 行后崩溃（k <= chunk-size），该 k 行已到达 broker 但在 DB 中仍为 PENDING，下轮重选重投；其上界由 TASK-153 候选的 batch-size(100) 收窄到 chunk-size(默认 25)。在真库 IT 中已通过模拟崩溃测试证实上界恰为 chunk-size，且行内 eventId 保持稳定供消费端幂等去重；关闭状态下上界保持为既有 1 行不变。
② **`sent_at` chunk 内同值，偏离 DDL 注释「投递成功时间」**：已触碰并由新规格显式授权。分块批量标记时同一 chunk 内所有行由一条 UPDATE 统一标记，`sent_at = NOW()` 为该 chunk 标记执行时刻，chunk 内所有行的 `sent_at` 同值，偏离 DDL 注释「投递成功时间」。新规格显式授权该偏差，在 Mapper、Relay 代码与日志中予以披露，并在真库 IT 中实测验证；关闭状态下逐行单独标时间，无同值偏差。
③ **chunk UPDATE 真失败 ⇒ 整块已投递行留 PENDING、下轮整块重投（大于逐行）**：已触碰并由新规格显式授权。若 chunk 的批量 UPDATE 抛出 SQL 异常（例如 DB 瞬时故障、语法或连接中断），该 chunk 内已发送成功的行均保持 PENDING，下轮 relay 整块重投；实现中对 chunk 内每个 id 逐一调用一次 `incrRetry` 以维持失败计数与重试耗尽语义，并在单测与真库 IT 中实测验证；关闭状态下仅单行受影响。
④ **与 `relay-send-concurrency>1` 叠加时多 worker 并发 UPDATE 可能加剧 fsync 争用（TASK-164 已见 markSent 线程时间每行膨胀）⇒ 本轮不测、不开并发，不得据此开启 concurrency>1**：未触碰（主动规避）。本轮零生产行为变化，默认关闭（false），且不开启并发（concurrency 保持 1，不开四服务、不跑负载），代码中 chunk 为 worker 局部结构以保证正交性，但本轮未测组合，明令禁止据此开启 concurrency > 1。
⑤ **MyBatis `<foreach>` 若用字符串拼接会引入 SQL 注入面 ⇒ 必须 `#{id}` 占位并在单测/IT 验证参数化**：已触碰并从实现机制上规避。`VerifyEventOutboxMapper.markSentBatch` 严格采用 MyBatis `<script><foreach>` 配合 `#{id}` 占位符进行预编译参数绑定，严禁任何字符串拼接，且在纯单测与真库 IT 中验证 SQL 语法与参数化执行。
