## ADDED Requirements

### Requirement: outbox relay 批内并发投递默认关闭且串行路径与引入前等价
WHEN 配置 outbox relay 的批内并发投递开关 `verify.outbox.relay-send-concurrency`,
系统 SHALL 默认取 1（串行），取值为 1 时不创建任何线程池或线程、在锁内逐行串行处理，
行为（取批、投递、标记、重试、耗尽行处理、异常传播、日志）SHALL 与引入本能力前的路径逐字等价；
取值小于 1 时 SHALL 钳到 1 并告警一次，不得因配置非法导致服务启动失败；
未设上限，但实用上界受连接池约束，未经同负载测量不得调大。

#### Scenario: 默认值不创建线程
GIVEN 未显式配置 `verify.outbox.relay-send-concurrency`
WHEN relay 执行一轮含待投递行的投递
THEN 不创建任何执行器服务或池线程
AND 按 ID 顺序串行投递，逐行语义与引入前完全一致

#### Scenario: 非法取值钳位告警且不抛异常
GIVEN `verify.outbox.relay-send-concurrency` 配置为 0 或负数
WHEN relay 启动后进入首轮投递
THEN 生效并发数钳到 1 并打一条 WARN 告警
AND 服务正常投递，不因配置非法抛异常或启动失败

### Requirement: 并发投递不改变逐行可靠投递语义
WHEN `verify.outbox.relay-send-concurrency` 大于 1,
系统 SHALL 仅在已取到的批次内并行投递多行，且对每行保持既有不变式：
先恰好一次 `syncSend`；成功后恰好一次 `markSent`；`syncSend` 抛错恰好一次
`incrRetry` 后告警并跳过该行；`markSent` 抛错恰好一次 `incrRetry` 后告警；
耗尽行（retry_count 达到上限）不投递、不标记、仅告警保留；
eventId 永不重新生成，topic/tag/payload/traceId 原样透传；
单行异常绝不逃出 worker 循环体（被捕获并告警）。
串行与并发路径 SHALL 共用同一个单行处理实现，语义只有一份。

#### Scenario: 每行发送与标记各恰好一次
GIVEN 一批 100 条 PENDING 行且并发数为 3
WHEN relay 取批并发投递
THEN 每行恰好经 `syncSend` 发送一次、成功行恰好 `markSent` 一次
AND 全部行的 id 集合与原批次 id 集合相等，不重不漏

#### Scenario: 发送失败仍逐行隔离
GIVEN 并发数大于 1 且某行 `syncSend` 抛错
WHEN relay 投递该批
THEN 该行恰好一次 `incrRetry`、不标 SENT
AND 其余行继续按既有语义投递与标记，计数正确，异常不外逃

#### Scenario: 标记失败仍逐行隔离
GIVEN 并发数大于 1 且某行 `markSent` 抛错
WHEN relay 投递该批
THEN 该行恰好一次 `incrRetry`、不重复投递
AND 其余行继续按既有语义投递与标记，计数正确，异常不外逃

#### Scenario: 耗尽行不参与并发投递
GIVEN 并发数大于 1 且批次中混有重试已耗尽的行
WHEN relay 处理该批
THEN 耗尽行不投递、不标记、仅告警保留供人工处理
AND 其余可投递行正常投递与标记

#### Scenario: 单行意外异常不废掉整个子列表
GIVEN 并发数大于 1 且某行处理抛出意外异常（如 `incrRetry` 失败）
WHEN worker 执行该行
THEN 异常被 worker 循环体捕获并告警，同子列表其余行继续处理
AND 无异常逃出 worker 破坏解锁与汇总流程

### Requirement: 并发只作用于已取批次且取批锁周期不变
WHEN relay 的批内并发数大于 1,
系统 SHALL 仍每轮恰好一次 `selectPendingBatch(batchSize, maxRetry)`（SQL、
`ORDER BY id`、批次上限、重试上限全部不变），并把已取到的列表按列表下标
`i % N` 划分为 N 个互不相交、并集完整的子列表；一轮恰好提交 N 个任务并全部
join 后才解锁（整批仍在防重锁内完成）。系统 SHALL NOT 以 `id % N` 谓词做 SQL
分区，SHALL NOT 增加取批次数、投递尝试次数或锁持有次数。

#### Scenario: 划分不触碰取批 SQL
GIVEN 并发数为 2 且一批按 ID 顺序返回的行
WHEN relay 切分该批并发投递
THEN 取批查询仍只执行一次且参数与引入前一致
AND 每个子列表内行保持 ID 相对顺序，子列表大小之和等于批次大小、id 集合互不相交

#### Scenario: 锁语义不变
GIVEN 并发数大于 1 且非空批次
WHEN 本轮执行
THEN 整批的投递与标记在防重锁内完成，全部任务 join 后才解锁
AND 期间其他实例仍被互斥跳过本轮

### Requirement: 并发诊断必须标明线程时间聚合口径
WHEN 并发数大于 1 时输出批次摘要诊断,
系统 SHALL 在摘要中输出本批生效的并发数字段 `sendConcurrency`，并在 javadoc
与摘要日志中写明：并发下 sendMs/markMs/incrRetryMs 是各线程墙钟的聚合和
（线程时间），不是单条时间轴上的墙钟，因此 `residualMs` 可能为负、不得读作
「未归因的墙钟」；`lockWaitMs/selectMs/lockProcessingMs/lockHoldMs` 的主线程
单点计时口径 SHALL 不变。

#### Scenario: 并发摘要含并发数与口径说明
GIVEN 并发数为 2、非空批次且诊断开启
WHEN relay 输出批次摘要
THEN 摘要含 sendConcurrency=2 与「段值为线程时间聚合、residualMs 可能为负、
不得读作未归因墙钟」的措辞
AND lockWaitMs/selectMs/lockProcessingMs/lockHoldMs 计时口径与引入前一致

#### Scenario: 并发下 residual 如实为负而不钳零
GIVEN 并发数为 2 且各线程的发送与标记段聚合和超过锁内墙钟
WHEN 计算摘要残差
THEN 摘要如实给出负的 residualMs
AND 不钳成 0 伪装成未归因的墙钟剩余量
