# 提案：outbox relay 批内并发投递（默认关闭，逐行语义不变）

## Why

relay 净投递吞吐已撞墙（TASK-143/144/145 实测：默认 5000ms 净投递约 13.4 行/s、调 500ms 约 37 行/s，而 outbox 创建速率约 59~96 行/s，积压只增不减）；锁内最大段是逐行 `markSent`（TASK-145：满批占锁段 A 79.7% / B 71.4%，另一轮 80.1%，B 臂 `markSent` 约 53.4%，`syncSend` 仅 18.8%/22.3%）；每行一次自动提交即一次持久化往返（TASK-152 同窗 18.0 ms/行，MySQL 服务端语句事件占 73.93%）。批末统一标 SENT（TASK-153）与 `markSent` 线程等待归因（TASK-154）两条替代路径已判 NO-GO。TASK-156 实测并发标度（每线程独立自动提交连接、完全无池）：S(2)=1.8612、S(4)=3.3066、S(8)=5.7056，随 N 单调不减——并发是唯一尚未被否掉的杠杆。

**本轮默认关闭：零生产行为变化、零已测收益。** 本轮只把杠杆做成可开关的代码，不声称任何吞吐/延迟改善；收益须由 TASK-161 的同负载对照来定。

## What Changes

1. 新增配置键 `verify.outbox.relay-send-concurrency`，`@Value` 默认 1，不写入 `application.yml`（与既有 `batch-size`/`max-retry`/`relay-interval-ms` 一致，保持已登记事实 `applicationYmlHasVerifyOutboxKeys: false` 不变）。取值小于 1 时钳到 1 并告警一次，不抛异常导致启动失败；不设上限，但 javadoc 写明连接池默认 10、消费线程 32~40，实用上界受池约束，未经同负载测量不得调大。
2. 并发数为 1 时不创建任何线程池/线程、串行执行，行为与引入前逐字等价；判据＝既有 19 个 relay 单测一字不改且全绿 + 新用例断言执行器字段为 null。
3. 并发数大于 1 时懒建固定大小 daemon 线程池（名字前缀 `verify-outbox-relay-`，`volatile` 持有，不得每轮新建；`@PreDestroy`：shutdown → 有界 awaitTermination → 超时 shutdownNow，关闭异常只告警不抛）；把已取到的批次按列表下标 `i % N` 切成不重不漏的 N 份，一轮恰好提交 N 个任务、全部 join 后才解锁。串行与并发共用同一个单行处理体，逐行七条不变式只有一份实现。
4. 并发只作用于已取批次，取批仍是单次 `selectPendingBatch(batchSize, maxRetry)`。**不用 `id % N` 的 SQL 分区，两条独立理由**：(1) `id % N` 谓词不可用索引，N 个 worker 各扫一遍同一段，引入 TASK-156 明确列为未测的「(c) 分区感知选取成本」，本轮不制造新的未测项；(2) 主规格 L989 要求原文是「WHEN relay **按 ID 顺序批量读取**待投递判定事件」，改 SQL 分区会与该已并入的权威需求冲突、需要 MODIFIED delta；按下标切分不触碰读取语义，故三件套保持纯 ADDED。
5. 诊断：`BatchSummary` 增加 `sendConcurrency` 分量并在摘要日志输出；并发下 sendMs/markMs/incrRetryMs 是各线程墙钟的聚合和（线程时间），residualMs 可能为负，javadoc 与日志措辞写明「不得读作未归因的墙钟」，防 TASK-146 同类归因误判复发；`selectNanos/lockWaitMs/lockProcessingMs/lockHoldMs` 主线程单点计时口径不变。

## 预登记反例（5 条逐条收录；本轮默认关闭、不构成收益）

1. **连接池会吃掉一部分标度**：`hikari` 未设 `maximum-pool-size` ⇒ 生效 **10**；而 `consume-thread-min/max = 32/40` 的消费者线程也要连接。N 个 relay worker 与消费者**争同一个 10 连接的池**。TASK-156 的 S(N) 用的是**每线程独立 `DriverManager` 自动提交连接（完全无池）**，故**生产 S(N) 必然低于 IT 的 S(N)**；TASK-156 自己的 boundary 原文就写了「scratch 真库 + test-only DriverManager IT ≠ 生产 relay（Spring/Hikari + Redisson 全局 tryLock(0) 单跑）」。
2. **不得靠调大池来兑现 S(N)**：TASK-141 已实测「仅调池容量 10→20 **不存在稳定收益**」。
3. **批内发送顺序不再按 id 升序**（跨 worker 交错）。依据是 TASK-156 的只读核查（`VerifyEventConsumer`/`LeaderboardEventConsumer` 无同 recordId 顺序依赖、本就 `MessageListenerConcurrently` 并发消费、`RECONSUME_LATER` 重投天然重排），**但「消费端幂等不自动等于授权改 relay」** ⇒ 必须以 spec delta 明示，不得当作既成事实。
4. **「按 recordId 分区」在当前 schema 下不可直接实现**：`verify_event_outbox` **没有 record_id 列**，recordId 只在 `payload` JSON 里。本轮用**已取批列表的下标取模**，既不碰 SQL 也不碰 schema。若将来确需按 recordId 保序分区，须另立**含 schema 变更**的提案。
5. **默认关闭 ⇒ 本轮零已测收益**：不得引用 S(8)=5.7056、18.0 ms/行、13.4/37 行/s 中任何一个当作**本轮**的收益；那些是历史测量，证据等级不因本轮而升级。

## Impact

- 规范：`sport-record-verify` 能力新增「outbox relay 批内并发投递」相关需求；见 spec-delta（纯 ADDED，不含 MODIFIED——诊断相关需求尚未并入主规格，它属 `measure-verify-outbox-relay-cost` 起的在途 3 深链）。
- 预计代码：`VerifyOutboxRelay.java`、`RelayDiagnostics.java` 与测试（`VerifyOutboxRelayConcurrencyTest.java` 新建、`RelayDiagnosticsTest.java` 仅补实参）。无新依赖、不改 SQL/索引/schema/pom/`application.yml`。
- 兼容：默认关闭，零生产行为变化；`BatchSummary` record 增加一个分量属编译级改动，仓内使用方仅 relay 与其测试。
- 风险：并发路径未经生产验证（本轮零已测收益）；批内发送顺序跨 worker 交错（反例 3）；并发诊断是线程时间（反例 1 与 D6 措辞），不得据其回推墙钟结论。

## 停止条件

若发现单行处理体无法在并发下保持七条逐行不变式，或实现必须改动任何既有 relay 语义（取批、锁、eventId、SENT、重试、耗尽行处理），立即停手回报，不得自行弱化不变式。若既有 19 个 relay 单测需要任何改动才能通过，视为契约红，回滚重做。
