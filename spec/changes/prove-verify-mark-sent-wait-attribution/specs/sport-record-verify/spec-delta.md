## ADDED Requirements

### Requirement: markSent 内部等待归因须先验证单线程计数对应
WHEN 真实负载只得到了目标 UPDATE 的服务端语句事件总墙钟,
测量作业 SHALL 在不改变生产 MySQL instrumentation 与 relay 行为的前提下，先用独立 scratch 连接验证目标语句、线程身份与等待计数能否对应。

#### Scenario: 受控目标更新
GIVEN 目标 JDBC 连接对应已核实的 P_S THREAD_ID，观察连接独立于目标连接
WHEN 目标连接仅执行真实 `markSent` 条件 UPDATE
THEN 作业 SHALL 对照目标 digest 增量、更新行数、最终状态及该线程 waits 汇总前后快照
AND SHALL 明确哪些等待属于直接观测、哪些可能由后台线程承担或根本未采集

#### Scenario: 负对照或混杂
GIVEN 同一线程可执行非目标 SQL 且部分文件等待可能在后台线程计入
WHEN 负对照与目标计数无法分开，或消费者关闭致逐事件嵌套关系不可得
THEN 作业 SHALL 判该方法不能支持完整单语句等待拆项，不得用全局计数差额或独立分位数命名 fsync、锁、CPU 或纯 SQL 份额
AND SHALL NOT 为取得漂亮数据而开关 Performance Schema 或重置计数器

### Requirement: 受限 GO 不等于性能收益
WHEN 隔离环境的客户端连接线程等待增量可以稳定对应到目标 UPDATE,
作业 SHALL 将结论限定为该仪器设置下的可观测性，而非完整服务端事务成本或生产优化收益。

#### Scenario: 可观测性通过
GIVEN 目标语句身份、线程映射、重复样本与负对照均闭合
WHEN 形成受限 GO
THEN 作业 MAY 另立同负载归因提案，但本任务 SHALL NOT 改业务代码、SQL、索引、池、JVM、MQ 或运行默认值
AND SHALL NOT 把 TASK-152 的单轮 73.9% 当作可回收收益或据此实施批量标记
