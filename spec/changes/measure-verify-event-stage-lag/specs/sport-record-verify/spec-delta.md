## ADDED Requirements

### Requirement: 判定与榜单事件分段归因须有可关联样本
WHEN 对提交、校验消费、outbox 投递与榜单消费报告阶段延迟,
系统 SHALL 对每一段记录起止事件、关联键、时间精度、样本数及失败/重试口径，仅对可成对关联的样本计算差值。

#### Scenario: 可关联的完整链路
GIVEN record、verify、leaderboard、MQ 和数据库均健康，且某次运行的记录/事件可由 runId、recordId、eventId 关联
WHEN 收集阶段指标
THEN 分别报告提交→判定、判定→SENT 与 SENT→榜单处理的有效样本及缺失样本
AND 对每段列出精度与 P50/P95/P99，不把各段独立 P50 求和当单请求时长

#### Scenario: 部分服务或阶段时间缺失
GIVEN mapmatch、leaderboard 不可用，或某一段只有无关联的秒级时间戳
WHEN 出具报告
THEN 系统 SHALL 明确标出 R5 质量/榜单端到端或该段细分未覆盖
AND SHALL NOT 把整段差值说成纯 MQ 等待、纯 SQL 或毫秒级精度

### Requirement: 可投递与耗尽待人工 outbox 积压分开记账
WHEN 评估 outbox 队列长度、最老年龄及排空率,
系统 SHALL 用同一 maxRetry 配置分别统计可投递 PENDING、重试耗尽 PENDING 与 SENT，不将已耗尽行算作会自然排空的队列。

#### Scenario: 有历史耗尽行
GIVEN 存在 PENDING 且 retry_count 达到配置上限的旧行
WHEN 负载前后盘点
THEN 系统分别列出可投递 count/最老年龄与待人工 count/最老年龄
AND 报告该行仍需独立盘点/告警、不再每轮触发 relay 错误日志

#### Scenario: 查询或环境不可用
GIVEN 无法读到真实上限或数据库不可用
WHEN 尝试出具积压结论
THEN 系统把该指标记为未覆盖并说明原因
AND 不把 PENDING 总数直接填为可投递数

### Requirement: 一次负载的环境与覆盖边界必须可复核
WHEN 为当前 HEAD 进行分段归因负载,
系统 SHALL 在不改业务行为前检查服务拓扑、R5 路网、数据库/MQ 健康、存储容量与历史积压，最多运行一次 c100×2000，并保留原始数据和停止原因。

#### Scenario: 完整服务可用
GIVEN 完整服务栈健康、路网可用且磁盘足够
WHEN 执行唯一一轮 c100×2000
THEN 报告 HEAD、环境指纹、每段有效样本、错误率及真实 R5 是否参与
AND 根据直接证据只给出一个下一步瓶颈假设，不实施参数调整

#### Scenario: 仅四服务可用或不能安全运行
GIVEN 榜单/R5 缺席，或连四服务/MQ/容量都不能满足安全门槛
WHEN 评估本轮覆盖
THEN 在前者仅报告具备可靠证据的局部链路并标榜单/R5 未覆盖
AND 在后者不跑负载、只保留静态/只读证据与退出码，不编造实时吞吐
