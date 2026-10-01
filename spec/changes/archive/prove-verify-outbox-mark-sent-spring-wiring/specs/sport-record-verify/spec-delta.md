## ADDED Requirements

### Requirement: markSent 探针应在 Spring 管理的真实 Mapper 路径接受隔离判别
WHEN TASK-147 的手工工厂可配对结论需要用于规划 verify-service 成本归因,
系统 SHALL 先在隔离真 MySQL 上验证同一种测试专用、目标调用限定的探针，SHALL 核实 Spring 注入的真实 Mapper、MyBatis-Plus 自动装配、`SqlSessionTemplate`、事务工厂和 Hikari 接线，SHALL 列明与完整生产上下文的差异；SHALL NOT 将直接 `openSession` 的结果冒充 Spring 路径。

#### Scenario: 无显式事务的目标调用
GIVEN scratch DDL 有 PENDING outbox 行，Spring 注入真实 Mapper 且调用者无业务 `@Transactional`
WHEN 开启测试探针并经 Spring Mapper 调用 `markSent` 一次
THEN 状态、更新行数及独立连接可见性 SHALL 与无插件基线一致
AND 此目标调用 SHALL 恰配一条低基数 `StatementHandler.update` 样本，样本 SHALL NOT 含 eventId、payload、凭证或 SQL 参数
AND 报告 SHALL 区分实际验证的 Spring 切片与被排除的外部服务

#### Scenario: 关闭、非目标、连续、失败及连接释放
GIVEN 测试探针关闭、调用非目标 Mapper、连续目标调用或 scratch 中发生真实 SQL 失败
WHEN 对照无插件基线执行并关闭会话
THEN 关闭与非目标 SHALL 零样本，连续/失败后 SHALL 不串样本
AND 返回值、状态、异常类型、自动提交可见性和连接归还 SHALL 与无插件基线一致
AND 若真库/关键 Spring 接线/无插件基线不可用或任一语义反例成立，系统 SHALL 停止并报告未覆盖或不可行，不换第二探针、不改生产配置

### Requirement: 下层计时的边界和结论等级应准确
WHEN 汇报 TASK-147/148 的探针样本,
系统 SHALL 定义其为整个 `StatementHandler.update` 调用墙钟（可含 JDBC 执行、取更新行数、MyBatis 后处理及隐式提交相关等待），SHALL 将连接获取、prepare/绑定、显式提交、服务端 SQL 和网络往返拆分中未直接观测的部分标为未知。

#### Scenario: 小样本不冒充生产归因
GIVEN 仅有隔离 Spring 切片的小样本可配对结果
WHEN 形成 TASK-148 结论
THEN 系统 SHALL 仅报告该切片的接线可行/不可行，SHALL NOT 将其称为生产负载的内部占比、纯 SQL/fsync/池等待或吞吐收益
AND SHALL NOT 从独立 P50 相减、跑 c100×2000、修改 relay 默认值、Mapper SQL、索引、MQ/池/JVM 参数
