# 提案：具备管理员权限时完成 markSent 同窗语句事件测量（TASK-152）

## Why

TASK-150 因 Windows `Redis` 服务占用宿主 6379 而未起栈；TASK-151 在普通权限会话中尝试可逆暂停该服务，`Stop-Service`/`sc.exe` 均因 Access denied 失败。两轮**均未运行负载**，未产生任何 `markSent` 服务端语句事件／relay 外层 `markMs` 同窗比值。继续在同一普通权限下重试没有证据价值。此外，TASK-151 发现 `verify-service` jar 缺失，不能只解决端口就宣称环境就绪。本提案仅在管理员会话可用时复用原同窗问题，不实施优化。

## What Changes

1. 在任何服务中断**之前**核对 HEAD、既有脏项与其他会话活动；确认当前进程属于管理员组且具备 Windows 服务控制权限；以仓库统一入口 `mvn-verify.sh --mode=offline --pl verify-service package` 构建缺失的 jar，记录退出码和测试数。若权限、构建或其他显见依赖不成立，立即停下口头回报，**不尝试 Stop-Service、不提交新的未覆盖报告**。
2. 仅在经用户授权的受控窗口内，复查宿主 `Redis` 服务、6379 监听、显见客户端及依赖、启动类型和其他会话进程；排除已知使用者后用服务管理器临时停止 `Redis`，启动既有演示 `redis:7.2-alpine` 容器。不得 `Stop-Process`、停其他会话、改启动类型/compose/端口、重建容器或数据卷、以宿主 Redis 3.0.504 代替。将环境恢复作为必须执行的 finally 优先于报告和提交；若恢复路径不可靠，不能中断服务。
3. 核对 `verify_db` PENDING 分账、原数据卷、Performance Schema 仪器/唯一目标 digest/溢出、四服务单实例健康、同一 jar、默认 relay=5000、已有诊断开关及其他 SQL 写入。只在前提全部成立时**最多一次** `run-perf.sh load 100 2000`（预热 10）。留前后同窗 `COUNT_STAR`/`SUM_TIMER_WAIT` 和全部非空 relay 批次 `markMs`、成功行、重试/锁跳过、cohort 排空、QPS/P50/P95 与资源快照。原始输出单独存 gitignored `docs/perf/data/raw/task152-*`。
4. 仅当 digest 唯一、无其他实例/同 SQL 干扰、窗口完整且 `COUNT_STAR` 增量＝成功 `markSent` 次数＝批次成功行数时，报告**同窗聚合**服务端语句事件总墙钟／外层标记总墙钟比值；不能配平则保留原始数并判不可归因，不重跑凑结论、不改仪器/参数、不从独立 P50 相减或将差额命名为单一成本。
5. 无论测量是否开始，先停本轮 Java 和演示 Redis，再恢复宿主 `Redis` 原状态/启动类型并核验 6379；其他中间件只恢复本轮确知改变且未被他人接管的状态。恢复失败优先报告。**仅当负载实际执行时**，完成报告/机器摘要、TASK-152 台账和本三件套据实回勾并本地提交（计数不闭合仍据实写不可归因）；若再次停在负载前，不写第二份空报告、不提交。TASK-150/151 的历史结论原样保留。

## Impact

- 规范：新增 `sport-record-verify` 的管理员窗口预检与测量恢复判据；不改主规范、业务 SQL、默认值、池/MQ/JVM、生产 Java/YAML/脚本。
- 文件：本三件套、实际测量成功或负载失败时的 `docs/perf/` 报告及 JSON、`work/mailbox/tasks/TASK-152/` 与 PLAN/机会总览；原始日志在 ignored raw。预检即失败时原则上只作即时回报，不新增任务提交。
- 环境：会临时中断宿主 Windows `Redis` 服务并将 6379 交给既有演示 Redis，可能影响其他本机应用；需管理员权限、显见依赖排查、可逆恢复和用户知情。演示负载会增加数据库记录，不清库/删卷。
- 保留 archive 移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`；不 stash、不 `git add -A`，不 push/PR。不得以 TASK-149 scratch 切片或 TASK-150/151 预检数字代替本轮负载证据。

## 判定与停止条件

普通权限、无法确认服务归属或恢复、缺失 jar 构建失败、服务不健康、未解释积压、P_S 仪器不可信、计数不符，均不得据此声称性能结果。管理员前置失败且负载未开始时不再形成一份“已收口的未覆盖”提交。即使完成一轮，只能作单轮聚合归因，不构成优化收益、纯 SQL/fsync 或生产通用比例。
