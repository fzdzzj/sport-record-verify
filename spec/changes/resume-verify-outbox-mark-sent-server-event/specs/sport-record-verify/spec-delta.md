## ADDED Requirements

### Requirement: 宿主 Redis 端口冲突仅可经可逆操作消除
WHEN 同一演示栈测量被宿主 Windows `Redis` 服务占用 6379 阻断,
测量作业 SHALL 先记录该服务及依赖、监听客户端与原状态；仅在明确可逆且不会中断已知其他使用者时 MAY 暂停该服务，并在结束时恢复原状态。

#### Scenario: 可安全恢复后继续
GIVEN 宿主 Redis 服务归属与依赖已核对，暂时停止及恢复可执行
AND 既有演示 Redis 容器使用相同宿主端口，未修改其镜像或数据
WHEN 测量作业临时停止宿主服务并启动演示容器
THEN 作业仅运行一次符合 TASK-150 前提的同窗实验
AND 作业结束时 SHALL 先停止本轮 Java 服务与演示 Redis，再恢复宿主服务并验证端口及启动类型

#### Scenario: 端口所有权或恢复不可确认
GIVEN 宿主服务有归属不明的使用者、依赖或停止/恢复条件不足
WHEN 作业检查 6379 冲突
THEN 作业 SHALL 不停止服务、不起冲突容器、不运行负载
AND SHALL 将本轮记为未覆盖并保留原始证据

### Requirement: 真实 relay 的服务端语句事件份额只在同窗配平时报告
WHEN 演示单实例四服务及 MySQL Performance Schema 前提均成立,
测量作业 SHALL 沿用 TASK-150 的目标 UPDATE 与至多一次 c100×2000 负载，记录前后目标 digest 计数和全部 relay 批次原始摘要。

#### Scenario: 有效的聚合归因
GIVEN 目标 digest 唯一、仪器已计时且无溢出或其他实例干扰
WHEN cohort 全部投递并排空且 COUNT_STAR 增量与成功 markSent 次数及批次成功行数相等
THEN 作业 SHALL 仅计算服务端语句事件总墙钟与客户端外层 markMs 总墙钟的同窗聚合比值
AND SHALL 注明单位、时钟与舍入边界，不从独立 P50 相减或把差额命名为池等待、网络、SQL 计算或 fsync

#### Scenario: 负载或计数未闭合
GIVEN 演示栈不健康、积压未知、负载失败、目标 digest 混杂或计数不符
WHEN 作业判定结果
THEN 作业 SHALL 记为不可归因，保留原始证据且不重跑负载凑结论
AND SHALL 执行宿主 Redis 与本轮服务的恢复，不修改生产 SQL/索引、relay 默认值、JVM、MQ 或 MySQL instrumentation
