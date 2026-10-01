## MODIFIED Requirements

### Requirement: relay 可选诊断不得改变可靠投递语义
WHEN `verify.outbox` relay 诊断开关开启,
系统 SHALL 以有界、低基数的批次摘要提供取批、发送、标记与失败处理的真实计时边界，同时 SHALL 保持默认关闭及原来的取批、锁、eventId、SENT、重试、异常传播与锁释放语义；关闭时 SHALL 不产生额外 DB/MQ 调用或逐事件诊断日志。

#### Scenario: 发送失败与标记失败不可重计
GIVEN 一批含成功行及 `syncSend` 或 `markSent` 抛错的行
WHEN relay 执行同一行的发送、标记和失败处理
THEN 每次调用实际经过的阶段 SHALL 只累计该阶段发生的墙钟一次
AND `markSent` 抛错前已成功的发送 SHALL NOT 被再次计入发送时间
AND 无法完成的标记 SHALL NOT 记作成功标记
AND 原有的失败递增和异常传播行为 SHALL 不变
AND 摘要 SHALL 不包含 eventId、payload、用户标识、SQL 参数或密钥

#### Scenario: 完整占锁与处理段区分
GIVEN relay 拿到防重锁并完成一轮投递
WHEN 诊断报告锁内处理时长或完整占锁时长
THEN 锁内处理时长 SHALL 明示其截点是否包含摘要输出与解锁
AND 只有实际在解锁之后取终点的计时 SHALL 称为完整占锁时长
AND 任一成功、空批或异常路径 SHALL 保持原锁释放行为

#### Scenario: 空轮、竞争与默认关闭
GIVEN relay 正常调度，可能读到空批或拿不到锁
WHEN 诊断开启或关闭
THEN 空轮和竞争 SHALL 以有界频率汇总
AND 关闭时 SHALL 不输出诊断日志、不调用额外 DB/MQ
AND 中断、异常与 finally 路径 SHALL 维持原来的投递语义

## ADDED Requirements

### Requirement: markSent 归因以同一次调用的嵌套证据为准
WHEN 对逐行 `markSent` 成本作归因,
系统 SHALL 将 Mapper 调用、可安全观测的客户端下层操作及剩余墙钟按同一次调用/同一批次配对、低基数有界汇总；未观测层 SHALL 明记未知，不得以独立分位数相减、全局累计值或有限突发净速率冒充纯 SQL、纯连接池排队、纯 fsync 或持续吞吐结论。

#### Scenario: 成功路径有可配对读数
GIVEN 诊断开启且安全插桩已通过测试
WHEN `markSent` 成功更新一行
THEN 报告 SHALL 给出实际覆盖的每层边界、配对样本数、同批合计及未覆盖残差
AND 任何连接获取或 JDBC 读数 SHALL 按客户端混合墙钟命名
AND 原有 UPDATE SQL、事务、状态及 eventId SHALL 不变

#### Scenario: 插桩无效或资源缺测
GIVEN 下层计时无法与 relay 调用可靠配对、插桩影响语义或外部资源采集缺失
WHEN 形成 TASK-146 结论
THEN 系统 SHALL 停止有风险的插桩或负载，将对应构成记为未知并说明验证缺口
AND SHALL NOT 为满足结论而额外补跑、改写批次/间隔/索引/SQL/池/JVM/MQ 默认值

#### Scenario: 单轮有限突发
GIVEN test/package 通过、环境健康、同 jar/参数可确认且预算尚未使用
WHEN 执行唯一一轮默认间隔 5000ms、c100×2000 的诊断负载
THEN 报告 SHALL 分账同 run 可投递/耗尽待人工积压、成功/失败/重试与可用资源
AND SHALL 将该轮标成测量而非与 TASK-145 的同版本优化前后对照
AND SHALL NOT 外推到榜单端到端或持续负载收益