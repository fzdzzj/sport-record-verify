## ADDED Requirements

### Requirement: Outbox relay 调度延迟候选必须按同条件重复验证
WHEN 对 `verify.outbox.relay-interval-ms` 提出投递延迟改善结论,
系统 SHALL 在同一 jar、相同批次及重试配置、可关联负载下，区分批间调度空档与批内投递跨度，并以重复 A-B-B-A 结果比较同 run 的 `callback→SENT` 延迟。

#### Scenario: 两轮候选均稳定改善
GIVEN 四轮均健康、无失败/重试混入、每轮前可投递 PENDING 为零、四轮提交 QPS 均在四轮中位数 ±15% 内且 outbox 创建形态可比，并且同 run 的 callback/SENT 可以配对
WHEN 唯一调整 relay fixedDelay 从 5000ms 到 500ms
THEN 两轮候选 SHALL 分别与两轮基线中较好者对照 P50/P95、有效投递速率、提交 QPS 及资源占用
AND 只有两轮候选的 P50 均改善至少 20%、P95 不劣且语义/资源无回归，才允许更改 verify-service 默认配置
AND 不把四服务局部实验说成完整榜单/R5 端到端收益

#### Scenario: 候选不稳定、环境不可比或负载异常
GIVEN 两轮 B 改善方向相反、到达速率不可比、关联样本缺失，或出现 MQ/DB/服务健康/容量问题
WHEN 判断是否调整默认参数
THEN 系统 SHALL 保留原 5000ms 默认值，报告未定/不推荐及停止原因
AND SHALL NOT 顺手调整批量、并发发送、SQL、索引、连接池或 JVM 参数

### Requirement: Outbox 积压与可靠投递语义保持不变
WHEN 进行单一调度间隔实验或更新默认配置,
系统 SHALL 保持既有取批资格、锁、多实例互斥、eventId 复用、失败递增重试、成功标记 SENT 与耗尽行人工处理语义不变。

#### Scenario: 可投递与耗尽行混合
GIVEN PENDING 行中既有 `retry_count < maxRetry` 也有耗尽行
WHEN relay 在任一调度间隔下取批
THEN 耗尽行 SHALL 不占发送批次且仍保留
AND 可投递事件使用原 eventId 发送，成功标记 SENT、失败递增 retry_count
AND 报告分账显示可投递与耗尽待人工两类积压，不用总 PENDING 冒充可排空队列

#### Scenario: 缺少可靠样本或只剩部分服务
GIVEN callback 和 SENT 缺少可关联时间，或 leaderboard/mapmatch 未启动
WHEN 出具实验报告
THEN 系统 SHALL 把相应指标记为未覆盖或降级到四服务局部口径
AND SHALL NOT 用独立 P50 相加、相减或求比值描述逐请求贡献
