## ADDED Requirements

### Requirement: 批末标记候选须先证明可靠投递语义
WHEN 逐行 `markSent` 的数据库语句事件成本引出减少调用次数的候选,
系统 SHALL 在生产行为改变前以真实 SQL 与现行规范对照发送、标记、失败与重扫边界；消费者幂等不能自动视为扩大重复投递窗口的授权。

#### Scenario: 成功发送后进程中断
GIVEN 第一条事件已同步发送、第二条尚未发送或批末尚未标记
WHEN 处理进程中断并在下轮重扫
THEN 判别实验 SHALL 分别记录现行逐行标记和候选批末标记的数据库状态、额外重复投递行数与原 eventId
AND 若差异未经规范授权 SHALL 判 NO-GO，不实施生产批量更新

#### Scenario: 部分成功与失败
GIVEN 一批同时包含发送成功、发送失败与达到重试上限的事件
WHEN 候选尝试聚合标记或数据库更新部分失败
THEN 判别实验 SHALL 检查各行 `status`、`retry_count`、影响行数、`sent_at` 与下轮资格
AND SHALL NOT 将失败发送行错误标 SENT、丢弃事件或假设单条成功等于整批成功

### Requirement: 语义判别与性能验收须分开
WHEN 只在隔离 scratch MySQL 上比较逐行与批末标记,
系统 SHALL 将结果限定为候选语义 GO/NO-GO；SHALL NOT 把 TASK-152 单轮服务端事件占比推导为批量化吞吐收益。

#### Scenario: 有未授权差异
GIVEN 真库或既有规范证明更长的 PENDING/重复投递窗口、延迟的 SENT 可见性或改变的 `sent_at` 无法保持
WHEN 形成裁决
THEN 系统 SHALL 报告最小反例并保留现行逐行生产路径
AND SHALL NOT 为凑性能结论启动演示负载、修改生产 Mapper/relay 或运行默认值

#### Scenario: 未发现差异
GIVEN 既有可靠投递、状态、重试、eventId、锁与时间语义均由可复现证据保持
WHEN 形成 GO 裁决
THEN GO 仅授权另立单因素同负载性能提案
AND SHALL NOT 在本任务中声称已优化 P99/吞吐或改动运行默认值
