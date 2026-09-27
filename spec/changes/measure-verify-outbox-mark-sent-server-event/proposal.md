# 提案：同负载测量 outbox markSent 的 MySQL 语句事件成本（TASK-150）

## Why

TASK-146 在真实 relay 有限突发中测得满批 `markSent` 外层混合墙钟约占锁内处理段 80.1%，但内部归因未知。TASK-147/148 分别证明目标限定的 `StatementHandler.update` 探针在手工工厂与受限 Spring 切片中可配对，均**未**在真实 relay 负载下测量内部构成，且不应直接接入生产插件链。

TASK-149 后续另有一次隔离 scratch Spring 切片的外层/内层逐次配对实验（业务提交 `158dcb3`、台账 `b658b01`）；该实验未启动真实 relay、未查询演示库 Performance Schema，不能替代本提案。`TASK-150` 保留原待证因素。

下一步只回答一个问题：同一负载、同一时间窗内，MySQL Performance Schema 对 `verify_db.verify_event_outbox` 的 `markSent` UPDATE 记录的**服务端语句事件总墙钟**是多少，相对于 relay `markMs` 的同窗总墙钟是什么量级？此值并非纯 SQL 计算、纯 fsync 或逐请求配对读数。scratch MySQL 预检见 `performance_schema=ON`、`statement/sql/update` 已启用计时、`statements_digest` 已启用，且对应 SQL 在 scratch schema 有独立 digest；演示栈当前未起，演示库/负载可用性**尚未验证**。

## What Changes

1. 冻结 HEAD、既有脏项、进程与数据状态；只读预检演示 MySQL 的 Performance Schema、目标 digest 唯一性、计数器可读性、服务栈与负载前可投递/耗尽 PENDING。已存在且停止的演示中间件容器可按需依赖顺序启动：先 MySQL 核对现有卷、verify_db 的待投递/耗尽分账与仪器，再启动 Nacos/Redis/RocketMQ，最后仅在健康且无冲突进程时启动本任务四个 Java 服务；不得新建/重建/清理数据卷、`down -v` 或启动无关服务。任一步不可用时停止并报告未覆盖，不为测量改服务器 instrumentation 或清理数据。
2. 在健康的**单实例**四服务局部栈上，保持当前 jar、默认 relay-interval=5000、batch/maxRetry、MQ/池/JVM/SQL/索引不变，只开启仓库已有的 relay 有界诊断。执行最多**一次** `run-perf.sh load 100 2000`，记录前/后同一 digest 的 `COUNT_STAR`、`SUM_TIMER_WAIT`、匹配摘要；同窗收集 relay 非空批次原始摘要、cohort/重试/排空、提交负载 QPS/P50/P95 与 MySQL/JVM 资源快照。采样窗口必须覆盖 cohort 全部投递并排空，不用独立中位数作差。
3. 只有 digest 精确唯一、目标 count 增量与本轮成功 `markSent` 次数一致、无第二实例/并发同 SQL 干扰、外层批次覆盖完整时，才计算 `serverEventTotal / outerMarkTotal` 的**同窗聚合比值**，并注明时钟来源、毫秒舍入与服务端事件包含范围。不能配平或来源不可信则只列原始数并判不可归因；不得把差额自动称为池等待、网络或应用 CPU。
4. 结果仅为单轮归因证据与后续假设，不是前后优化对照；不改运行默认值、不优化逐行标记、不新增生产 MyBatis 插件。完成报告、机器摘要、三件套、TASK-150 台账、PLAN/总览、契约及必要本地提交；不 push/PR。

## Impact

- 规范：`spec/specs/sport-record-verify/spec.md` 的 relay 成本归因测量增量，先写本变更的 spec-delta，不直接修改总稿。
- 预期文件：`docs/perf/` 报告及 `docs/perf/data/` 有界机器摘要、三件套、TASK-150 spec/handoff、PLAN/机会总览；原始查询输出/批次日志存 gitignored `docs/perf/data/raw/`。**原则上零 Java/SQL/YAML/脚本业务改动**。
- 无 API/迁移/运行默认值变更；不记录 SQL 参数、eventId、payload、凭证。负载将给演示库新增记录与轨迹，需在报告如实记数，禁止为对照清库或删除数据卷。
- 保留现有 archive 移名、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`；不 stash，不 `git add -A`。

## 判定与停止条件

仅在真实演示栈可用且所有匹配条件成立时，才给**聚合服务端语句事件份额**；单轮不得宣称可复现收益、纯 SQL/fsync 或生产通用占比。若 Performance Schema 停用/溢出、digest 混杂、计数不符、服务不健康、积压未排空或其他实例争用，停止归因并列出原始证据/未覆盖；不得重跑负载以凑结论、修改 instrumentation 或改用第二种测量办法。下一项只依据有效测量决定，不能直接叠调参数。
