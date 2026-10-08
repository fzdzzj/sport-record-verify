# Sport Record Verify 变更规格：impl-like-reconcile-pipeline

## MODIFIED Requirements

### Requirement: 对账纠偏以 DB 为权威源批量重建 Redis 计数与成员集

`reconcileLikeCounts` 每轮以 `record_like` 全表行分组结果为权威，重建每 record 的 `like:count:{id}` 与 `like:record:{id}:users`。往返优化 SHALL 以 pipeline 分批提交等价命令序实现，且 SHALL 满足：

#### Scenario: 命令序等价

- WHEN 对账以 pipeline 方式执行
- THEN 每 record 的命令三元组（SET 计数 / DEL 成员集 / SADD 重建，空成员只 DEL）SHALL 与逐 record 基线逐条等价，仅提交分组不同；record 处理顺序 SHALL 与分组遍历序一致。

#### Scenario: 语义门复验

- WHERE 生产方法直测（IT 沿判别轮装配）
- THEN (a) 对账完成后 Redis 计数/成员集与 DB 权威逐 record 相等、(d) 全部 Redis 写在对账锁保护内、(e) pipeline 实际命令序与构造序一致 SHALL 复验通过；(b)(c) SHALL 沿判别轮证据边界登记为未在生产路径重验（继承 Notice），验收 SHALL NOT 依赖连接占用与 CLIENT KILL 中断维度。

#### Scenario: 批参数默认值

- WHERE pipeline 分批大小 `PIPELINE_BATCH_SIZE` 默认 500
- THEN 其取值 SHALL 在任务执行前实测两档（500/1000）定档并登记依据；实施 SHALL NOT 改动调度周期、锁键、批外任何参数。

#### Scenario: 验收口径

- WHEN 同负载前后对账耗时被登记
- THEN SHALL 只登记绝对数字与本机隔离环境限定词，SHALL NOT 写改善百分比、SHALL NOT 外推生产收益、SHALL NOT 声称延迟改善。
