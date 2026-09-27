# TASK-151 回传：恢复 TASK-150 未覆盖的真实 relay 同负载窗口服务端语句事件测量

## 结论

开工 HEAD `02a16af312aed8792a44eb64d7ba57ad0c00067d`（与任务书一致，作业期间未换基线、未被他会话推进）。**结论等级：不可归因（UNCOVERED，测量未执行）**。原因：唯一被本任务授权的端口释放手段——以服务管理器**临时暂停宿主 Windows 服务 `Redis`**——在本机**无管理员权限**下**不可执行**（`Stop-Service` → `Cannot open Redis service on computer '.'`；`sc.exe stop Redis` → `[SC] OpenService FAILED 5: Access is denied`；`IsAdmin=False`；`net session` → `System error 5`）。既有演示容器 `sport-verify-redis`（`redis:7.2-alpine`，`PortBindings=6379`）仍无法绑定被占用的 6379，故**未启动 Nacos/RocketMQ/Redis 与四个 Java 服务、未执行任何 `c100×2000` 负载、未产生任何服务端语句事件 / 外层标记比值**。**不编造比值、不以 scratch 或 TASK-149 数字冒充本任务结果、不为凑结论重跑、不把 TASK-150 改写为测量成功。**

## 前提预检（只读）

- **起点核对**：HEAD `02a16af` 匹配；既有脏项（两个 archive 移名的删除侧与 `spec/changes/archive/*` 新路径侧、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`）与 TASK-150 结束时一致，**原样保留，未 stash、未 `git add -A`、未触碰**；本任务未跟踪三件套 `spec/changes/resume-verify-outbox-mark-sent-server-event/` 为交付输入。
- **宿主 `Redis` 服务原状态**：`Name=Redis`、`State=Running`、`StartMode=Auto`、`PathName="D:\develop1\Redis-x64-3.0.504\redis-server.exe" --service-run redis.windows.conf`、`StartName=NT AUTHORITY\NetworkService`、`ProcessId=6684`（父 `services.exe` PID 1996）。6379 由该进程以 `::`+`0.0.0.0` 监听。**到 6379 的已建立连接：无。**
- **依赖归属（已排查，不阻断宿主 Redis 暂停）**：另一活跃会话仓库 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（`mvn -o -B -ntp clean verify` + surefire + Testcontainers）全文检索 `redis/6379` 仅 4 处偶然命中（文档 / 测试夹具），**无应用配置引用**，且走 Testcontainers 随机端口 → 不依赖宿主 6379。该会话的 Java 进程 / 容器属其自身，本任务未启停。
- **演示容器 / MySQL 起点**：`sport-verify-redis` `Running=false`、`RestartPolicy=no`；`sport-verify-mysql` `Up (healthy)`、宿主 3307（TASK-150 启动后保持运行）；`sport-verify-{nacos,rocketmq-namesrv,rocketmq-broker}` 均 `Exited (255)`。
- **jar 起点**：`record/user/gateway` 三 jar 在位；**`verify-service` jar 缺失**（`target/` 被 `mvn-verify.sh` 的 `clean` 清空，只剩 `classes/`、`test-classes/`、`surefire-reports/`）。因测量未执行，**未据此起栈、未为此构建 jar**。
- **未采用任何被禁替代**：未 `Stop-Process`、未改启动类型、未改端口 / compose、未重建容器 / 数据卷、未以宿主 Redis 3.0.504 代替演示 Redis 7.2。

## 未覆盖

- **未执行负载**：无 `run-perf.sh load 100 2000`；**负载退出码 = N/A（未运行）**；无目标 digest 前 / 后 `COUNT_STAR`+`SUM_TIMER_WAIT`；无 relay 非空批次原始摘要、成功 / 失败 / 重试 / 锁跳过；无提交 QPS / P50 / P95；无资源读数；无演示库数据增长。
- **无配平、无比值**：因未产生任何 `markSent` 语句事件，不存在可配平计数，**不报告**「服务端语句事件总墙钟 / 外层标记总墙钟」同窗聚合比值，也不换算 `SUM_TIMER_WAIT` 单位、不逐批求和 `markMs`。
- 未覆盖：真实 relay 下服务端语句事件份额；P_S 时间窗覆盖预热 + 主体的验证；其他实例 / 并发同 SQL 干扰排查；本轮 `verify_db` PENDING 分账复核；演示栈健康 / 同一 jar / 默认 relay=5000ms / 既有有界诊断开关的起栈核对；完整生产上下文（Nacos/MQ/Redis/Feign/调度/Web）；多实例锁竞争；`--mode=online`/CI；未达外部门槛。
- **不得由本任务推出**：不得称纯 SQL / fsync / 池等待 / 网络 / CPU / MyBatis，不得从独立 P50 相减，不得宣称延迟 / 吞吐改善，不得改 relay 默认 5000ms 或优化 markSent。

## 环境恢复

被授权的 `Stop-Service` 尝试**失败且无副作用**；除此之外**未启动任何容器、未启动任何 Java、未改任何服务 / 端口 / 卷 / 配置**。核验：宿主 `Redis` **Running / Auto**（未变）、6379 仍 PID 6684（未变）、`sport-verify-redis` **仍 `Running=false`**、本任务启动的 Java **无**、既有脏项**一致**。**无恢复失败项**；未清库、未删卷。原始证据 `docs/perf/data/raw/task151-02-restore.txt`。

## 验收与退出码口径

- **纯文档 / 数据交付**：本任务**未改任何 Java / SQL / YAML / 脚本** → **未运行 Maven**、**不把任何测试写为已跑 / 通过**；无 `mvn-verify.sh` 退出码可报。
- JSON 校验：`tasks.json`、`exp-outbox-relay-mark-sent-server-event-resume.json` 均 `ConvertFrom-Json` 通过（rc=0）。`git diff --check` rc=0。无参数 `mailbox-contract.sh` 提交前 / 后状态见 `docs/perf/data/raw/task151-*` 与下方清单。

## 工作树如实说明（必须保留）

- 未跟踪目录 `spec/changes/resume-verify-outbox-mark-sent-server-event/` 为本任务三件套目录，随业务证据提交。
- 既有脏项（`spec/changes/adopt-native-mq-retry`、`spec/changes/wire-verify-outbox` 的归档移名删除侧与 `spec/changes/archive/*` 新路径侧、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`）**非本任务产物、未暂存、原样保留**。
- 收尾仅停本轮启动的 Java 服务（本轮**未启动任何 Java 服务**）、演示 Redis 容器（本轮**未启动**）；演示 MySQL 保持运行，中间件状态如实回传，**未触碰其他会话进程 / 容器**。

## 实际改动清单（只改）

- **业务证据提交 `8088bd2fdbd8306d1b0b69117e679a6afbe2e812`（5 文件）**：`docs/perf/复测-outbox-markSent-服务端语句事件总墙钟.md`（新）、`docs/perf/data/exp-outbox-relay-mark-sent-server-event-resume.json`（新）、`spec/changes/resume-verify-outbox-mark-sent-server-event/proposal.md`（新）、`spec/changes/resume-verify-outbox-mark-sent-server-event/tasks.json`（新）、`spec/changes/resume-verify-outbox-mark-sent-server-event/specs/sport-record-verify/spec-delta.md`（新）。
- **台账提交（4 文件）**：`work/mailbox/tasks/TASK-151/spec.md`（新）、`work/mailbox/tasks/TASK-151/handoff.md`（新）、`work/mailbox/PLAN.md`（改）、`work/mailbox/后端优化机会总览-2026-09-26.md`（改）。
- 既有脏项原样保留；未用 `git stash`、未 `git add -A`、未 push、未建 PR。