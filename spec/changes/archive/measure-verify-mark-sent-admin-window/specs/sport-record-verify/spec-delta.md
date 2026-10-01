## ADDED Requirements

### Requirement: 负载测量前必须证实提权与构建产物
WHEN 宿主 Windows 服务占用演示 Redis 固定端口且普通权限停止失败,
测量作业 SHALL 在任何服务中断前验证管理员服务控制权限，并用仓库统一入口补齐缺失的 verify-service jar。

#### Scenario: 前置失败时零中断退出
GIVEN 当前会话没有服务控制权限或离线 package 失败
WHEN 作业检查测量前提
THEN 作业 SHALL 不停止宿主服务、不启动负载、不修改服务或数据卷
AND SHALL 即时回报而不是提交重复的未覆盖报告或将 TASK-150/151 改为通过

#### Scenario: 前置满足时可逆切换
GIVEN 当前会话已提升、jar 可用且宿主 Redis 无已知其他使用者
WHEN 作业在受控窗口通过服务管理器临时暂停宿主 Redis 并启动既有演示容器
THEN 作业 SHALL 记录原状态/启动类型与切换后状态
AND 作业结束或中途失败时 SHALL 先停本轮 Java 和演示 Redis，再恢复宿主服务及 6379 原监听

### Requirement: 同窗聚合读数须与实际 relay 负载计数闭合
WHEN 真实单实例四服务和 Performance Schema 的测量前提成立,
测量作业 SHALL 至多运行一次 c100×2000，并在同窗记录目标 UPDATE digest 事件总量与 relay 非空批次 markMs 总量。

#### Scenario: 计数闭合
GIVEN 目标 digest 唯一、已启用计时且无溢出和其他实例干扰
WHEN cohort 投递并排空且 COUNT_STAR 增量等于成功 markSent 次数和批次成功行数
THEN 作业 SHALL 仅报告服务端语句事件总墙钟与外层标记总墙钟的同窗聚合比值和单位/舍入边界
AND SHALL 不从独立 P50 相减、不将聚合差额命名为池等待、网络、纯 SQL 或 fsync

#### Scenario: 负载或仪器不可用
GIVEN 服务不健康、积压不明、负载失败、digest 混杂、计数不符或恢复失败
WHEN 作业收口
THEN 作业 SHALL 保留实际原始证据并记不可归因，不重跑凑结论或改运行默认值
AND SHALL 优先恢复宿主服务及本轮环境，恢复失败须置于回报首位
