# 提案：恢复 TASK-150 未覆盖的真实 relay 同窗测量（TASK-151）

## Why

TASK-150 在 HEAD `b658b01` 上完成演示 MySQL 预检，但因宿主 Windows 服务 `Redis` 占用 6379、既有演示 Redis 容器固定发布 `6379:6379` 而停止；它没有运行负载，也没有服务端语句事件占比。TASK-149 的 scratch Spring 切片配对读数不能替代真实 relay 的同窗聚合证据。下一步仍只验证 TASK-150 的同一瓶颈假设，不实施优化。

## What Changes

1. 以 HEAD `02a16af312aed8792a44eb64d7ba57ad0c00067d` 冻结仓库与环境，复核 TASK-150 报告和原始证据、宿主 `Redis` 服务/客户端/依赖、演示容器及端口占用。**本提案仅授权在确认不会中断其他明确可见的使用者、可通过服务管理器恢复原状态时，临时停止名为 `Redis` 的 Windows 服务**；不改其启动类型，不结束进程树，不修改端口、compose 或持久数据。若依赖归属不明、缺权限、停止失败或恢复路径不可靠，停止测量并记录未覆盖。不得拿宿主 Redis 3.0.504 替代演示 Redis 7.2。
2. 在可逆停止宿主服务后启动**既有**演示 Redis 7.2 容器，按依赖启动必要中间件及单实例 gateway/user/record/verify；核对原 MySQL 卷、`verify_db` 的 PENDING 分账、Performance Schema 开关、目标 digest 身份/溢出、Java 健康、诊断开关与默认 relay 间隔。不得重建容器/卷、清库或改 instrumentation；任一前提失败立即停止，不跑负载。
3. 沿用 TASK-150 的**一次** c100×2000（预热 10）同窗设计：负载前/排空后记录 `verify_db` 的目标 UPDATE digest 的 `COUNT_STAR`、`SUM_TIMER_WAIT` 和所有可能混淆 digest；逐批留 relay 原始 `markMs`、成功行/失败/重试/锁跳过及资源、QPS/P50/P95。仅在单实例、完整窗口、cohort 排空、digest 唯一且 `COUNT_STAR` 增量与成功标记次数及批次成功行数配平时，才计算服务端语句事件总墙钟／外层标记总墙钟的**同窗聚合比值**。不从独立 P50 相减，不将差额归为某个单项。
4. 无论测量成功或中途失败，收尾均先停止本轮启动的 Java 服务和演示 Redis 容器，再恢复宿主 `Redis` 服务并核对原端口/启动类型；其他容器只恢复本轮确知的起点状态，不停止其他会话接管的进程。恢复失败必须立即报告，不以测量结论掩盖。归档原始证据于 ignored `docs/perf/data/raw/task151-*`，提交报告、低基数 JSON、TASK-151 台账及本三件套据实勾选；不 push/PR。

## Impact

- 规范：仅新增 `sport-record-verify` 的测量与环境恢复约束；不修改主规范总稿。
- 文件：本变更三件套、`docs/perf/` 报告及 `docs/perf/data/` 摘要、`work/mailbox/tasks/TASK-151/`、PLAN/机会总览；原则上无生产 Java/SQL/YAML/脚本变更。
- 环境：实验期间**临时中断宿主 Windows `Redis` 服务**并占用 6379；这是对其他本机应用可能有影响的操作，须先排查依赖并保证恢复。演示库会新增记录和轨迹，不清除原数据；宿主服务与演示容器状态必须记录前后。
- 不触碰既有 archive 移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`；不 stash、不 `git add -A`。TASK-150 的未覆盖报告原样保留，不回填为成功。

## 判定与停止条件

主判据和负载上限完全沿用 TASK-150：一次诊断开启的局部四服务负载只能提供一轮聚合归因，不能证明优化收益、纯 SQL/fsync 或生产通用占比。宿主服务依赖不明、恢复不可保证、演示栈不健康、积压不明、P_S 不可信、窗口/计数不闭合时不得计算比值或重跑凑结论；即使无法测量也必须执行可逆恢复并据实记为未覆盖。
