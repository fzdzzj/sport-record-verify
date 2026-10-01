## MODIFIED Requirements

### Requirement: relay 可选诊断不得改变可靠投递语义
WHEN `verify.outbox` relay 诊断开启或关闭,
系统 SHALL 以有界、低基数的摘要提供实际经过的取批、发送、标记和失败处理墙钟及结果，SHALL 保持原取批、锁、eventId、SENT、重试、异常传播与解锁处理语义；关闭时 SHALL 不增加 DB/MQ 调用或逐事件诊断日志。

#### Scenario: 解锁后的计时边界
GIVEN 一轮批次已处理，准备调用 `unlock()`
WHEN 汇报 `lockProcessingMs` 与 `lockHoldMs`
THEN `lockProcessingMs` SHALL 只指处理段且不含解锁
AND `lockHoldMs` SHALL 只描述终点在解锁调用之后取得的经过时间，摘要日志若在终点之后输出 SHALL 明示其未被包含
AND `unlock()` 抛错或未证实释放时 SHALL NOT 把经过时间当作已释放锁的确证

#### Scenario: 失败与默认关闭
GIVEN 发送、标记、失败计数或解锁可能抛错
WHEN relay 执行本轮
THEN 诊断 SHALL 不重复归集已计的发送/标记墙钟
AND SHALL 不改变原有发送、SENT、retry_count 和异常处理分支
AND 开关关闭时 SHALL 不增加额外 DB/MQ 调用、诊断日志或逐行计时采样

## ADDED Requirements

### Requirement: markSent 内部测量先经隔离真实装配证明
WHEN 需要拆解 `markSent` 的外层混合墙钟,
系统 SHALL 先在隔离真 MySQL 和本仓真实 Mapper 装配中验证一条测试专用、目标调用限定的候选接线，SHALL 明示每层计时的客户端边界及不能观察的段；在证明配对与语义不变前 SHALL NOT 接入生产路径或运行大负载。

#### Scenario: 同调用配对可行
GIVEN scratch schema 中存在 PENDING outbox 行且测试探针启用
WHEN 调用真实 `VerifyEventOutboxMapper.markSent` 一次
THEN 目标行 SHALL 按原 SQL 从 PENDING 变 SENT 且更新行数/事务结果与无探针一致
AND 测量 SHALL 能将目标 Mapper 与其可安全观测的下层调用在同一次调用中配对
AND 非目标 Mapper、凭证、eventId 与 payload SHALL 不进入测量摘要
AND 数据库服务端 SQL 时间、连接获取或提交若未直接观测 SHALL 明记未知

#### Scenario: 关闭、失败、连续调用及不可行
GIVEN 探针关闭、目标调用失败或连续更新多行
WHEN 用同一 scratch 真实装配执行
THEN 关闭状态 SHALL 不产出探针读数，失败后作用域 SHALL 清理，后续调用 SHALL 不串样本
AND 异常、连接释放、更新行数及状态 SHALL 与无探针基线一致
AND 若环境不可用或任何语义/隔离反例成立，系统 SHALL 停止该候选、保留反证并报告未覆盖或不可行，不改生产配置

### Requirement: 有限突发与采样峰值不得冒充已完成归因
WHEN 汇报 TASK-146 的积压证据或 TASK-147 的可行性试验,
系统 SHALL 将晚于负载结束的首次采样最大值记作观测峰值，真实峰值标未知且不低于观测值；SHALL 将小样本接线证明与生产吞吐收益区分。

#### Scenario: 只读证据订正
GIVEN 首次 PENDING 采样晚于负载结束且最大观测值为 1571
WHEN 更新报告与机器摘要
THEN 文案 SHALL 保留 1571 原值并说明真实峰值未知、至少 1571
AND SHALL NOT 把该值称为覆盖整个负载窗口的真实峰值
AND 本任务 SHALL NOT 为补峰值重跑 c100×2000 或更改 relay 默认值