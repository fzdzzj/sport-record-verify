## ADDED Requirements

### Requirement: 重试耗尽事件不得阻塞后续可投递事件
WHEN relay 按 ID 顺序批量读取待投递判定事件,
系统 SHALL 仅将仍可重试的 PENDING 行计入本轮批次，并保留已耗尽行供人工处理；系统 SHALL NOT 因较小 ID 的耗尽行占满查询上限而永久阻止后续可投递行。

#### Scenario: 队首全部重试耗尽
GIVEN 前 100 条最小 ID 的 PENDING 行 `retry_count=16`、上限为 16
AND 第 101 条 PENDING 行 `retry_count=0`
WHEN relay 以批次上限 100 查询并发送
THEN 第 101 条进入本轮可投递批次并沿用原 eventId 发送
AND 前 100 条仍保留原数据供人工处理且不重投

#### Scenario: 达到阈值的边界
GIVEN 上限为 16，存在 `retry_count=15` 与 `retry_count=16` 的 PENDING 行
WHEN relay 取一批事件
THEN 前者仍有资格发送或失败后增加重试次数
AND 后者不发送、不重复增加重试次数但保留待人工处理

#### Scenario: 正常投递语义保持
GIVEN 没有达到重试上限的事件与可用的分布式锁
WHEN relay 发送成功或发送失败
THEN 成功行仍按原有条件标为 SENT，失败行仍按原有条件增加 retry_count
AND topic、tag、payload、eventId 与 traceId 的透传语义保持不变

### Requirement: 饥饿修复须验证真实取批条件
WHEN 验收 relay 批次资格条件的变更,
系统 SHALL 在真实执行的 SQL 及同一组隔离数据上证明旧查询遮挡和新查询放行，并说明过滤耗尽行的查询代价边界。

#### Scenario: SQL 前后对照
GIVEN 隔离库中先写入满批耗尽行、最后写入可投递行
WHEN 以相同 `limit` 和重试阈值分别执行旧查询与新查询
THEN 旧查询不返回可投递行而新查询返回
AND 验证仅作用于隔离数据，不修改演示库真实事件

#### Scenario: SQL 性能证据不足
GIVEN 只观察到选择结果正确或 EXPLAIN 访问路径改变
WHEN 出具结论
THEN 系统仅报告可靠投递修复与实际观察到的 SQL 计划/时间
AND 不将查询过滤声称为已证实的吞吐或延迟优化
