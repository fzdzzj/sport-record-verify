## ADDED Requirements

### Requirement: markSent 服务端语句事件成本须在同一负载窗口受控归因
WHEN relay 的逐行 `markSent` 外层混合墙钟被识别为待拆解因素,
系统 SHALL 在不改生产 Mapper/数据源/插件链的前提下，先核对 MySQL Performance Schema 的目标语句 digest、计时开关、单实例和基线空积压；只对实际运行的同一负载窗口记录目标 `COUNT_STAR`、`SUM_TIMER_WAIT` 与 relay 外层批次 `markMs` 总量。

#### Scenario: 有效的同窗聚合对照
GIVEN Performance Schema 启用计时、目标 digest 唯一且无其他实例或并发同 SQL 调用
WHEN 一次 c100×2000 的 cohort 已全部终态、成功投递并排空
THEN 目标 digest 的 count 增量 SHALL 与本轮 `markSent` 成功次数和外层批次覆盖行数一致
AND 仅在计数配平与前后原始快照齐全时 SHALL 报告服务端语句事件总墙钟、外层标记总墙钟及其同窗聚合比值
AND 提交 QPS/P50/P95、重试/耗尽/锁跳过与资源快照 SHALL 同时记录，以解释负载有效性而非声称优化

#### Scenario: 仪器或 cohort 不可信
GIVEN digest 缺失/混合、计时关闭、overflow、其他实例竞争、负载失败或 PENDING 未排空
WHEN 尝试汇总本轮
THEN 系统 SHALL 保留原始快照与不匹配项，SHALL 将聚合归因标为不可判定
AND SHALL NOT 修改 MySQL 仪器配置、重置 Performance Schema/演示库计数器、清理数据卷或为了凑结论重跑负载

### Requirement: 服务端语句事件与客户端混合墙钟须分清
WHEN 报告 MySQL Performance Schema 与 relay 诊断的计时,
系统 SHALL 将前者称为服务端**语句事件**墙钟，后者称为客户端外层混合墙钟；SHALL 说明两者是同窗总量而非逐请求配对，服务端语句事件不得被称作纯 SQL、纯 fsync 或完整事务成本。

#### Scenario: 单轮证据边界
GIVEN 只有一次诊断开启的本地四服务局部负载
WHEN 形成 TASK-150 结论
THEN 系统 SHALL NOT 从独立 P50 求差或把聚合差额直接归因为连接池、网络、MyBatis、CPU 或 GC
AND SHALL NOT 宣称延迟/吞吐改善、修改 relay 默认 5000ms 或优化 markSent
AND SHOULD 将未覆盖的完整榜单/R5、online/CI、跨实例和持续负载分别标明
