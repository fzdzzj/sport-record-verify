## ADDED Requirements

### Requirement: relay 可选诊断不得改变可靠投递语义
WHEN `verify.outbox.relay` 诊断开关开启,
系统 SHALL 以有界、低基数的批次摘要提供取批、发送、标记与失败处理的耗时和结果，同时 SHALL 保持默认关闭及原来的取批、锁、eventId、SENT、重试和异常传播语义。

#### Scenario: 成功批与失败行
GIVEN 一个包含成功与发送失败事件的合格批次，且诊断已开启
WHEN relay 投递该批次
THEN 摘要 SHALL 提供所选/成功/失败行数与各同步环节的非负墙钟时间
AND 成功行 SHALL 原样发送 eventId 并标 SENT
AND 失败行 SHALL 按既有规则递增 retry_count
AND 摘要 SHALL NOT 输出 payload、eventId、用户标识或密钥

#### Scenario: 空轮、竞争与诊断关闭
GIVEN relay 正常调度，可能读到空批或拿不到锁
WHEN 诊断开启或关闭
THEN 空轮/竞争 SHALL 以有界频率记录或汇总，不能形成每事件无限日志
AND 诊断关闭时 SHALL 不输出批次诊断日志或引入额外 DB/MQ 调用
AND 原有中断、异常、锁释放路径 SHALL 保持不变

### Requirement: relay 成本结论必须保留周期与事件两种口径
WHEN 对 relay 间隔及批内工作作性能归因,
系统 SHALL 在同一 jar、同一诊断开关下，以真实周期的单调时钟数据及同 run 事件配对数据分别记录周期构成、有限突发积压、可靠投递与资源状态，SHALL NOT 从不同分位数做逐请求占比或从有限突发外推持续吞吐。

#### Scenario: 周期成本闭合
GIVEN 诊断已开启且观测到非空批次
WHEN 汇总每个周期的取批、发送、标记、失败处理和锁持有耗时
THEN 报告 SHALL 核查各段与总时长是否闭合，并把未覆盖的残差单列
AND SHALL 将 `syncSend` 记为混合墙钟而非纯 MQ 网络耗时
AND SHALL 将 Mapper 耗时记为混合墙钟而非纯 SQL 执行耗时

#### Scenario: 资源缺测与有限突发
GIVEN A=5000ms、B=500ms 的至多两轮同 jar 诊断负载，或一轮提前停止
WHEN 决定是否更改运行默认值
THEN 报告 SHALL 分别记载提交/事件关联、可投递及耗尽积压、资源可观测范围、失败与配对缺口
AND SHALL 将采不到的资源标记未知，将失败轮保留为实际样本
AND SHALL 保持 `relay-interval-ms=5000` 默认值不变
AND SHALL NOT 宣称两轮证明可复现的延迟收益、稳定吞吐或榜单端到端完成
