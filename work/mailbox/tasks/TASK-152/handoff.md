# TASK-152 回传：具备管理员权限时完成 markSent 同窗语句事件测量

## 结论

开工 HEAD `9cf6d174f4a1ae2c2ded644ba757d758873261b8`（与任务书一致，作业期间未换基线）。**结论等级：已执行一次、计数闭合的同窗聚合比值（单轮 / 本机 / 未达外部门槛）**——服务端语句事件总墙钟 **26,750.519526 ms**（`SUM_TIMER_WAIT=26,750,519,526,000` ps，单位经 `SELECT SLEEP(1)` 实证标定为皮秒）/ 外层 `markMs` 同窗总墙钟 **36,183 ms** = **0.739312（73.9312%）**；算术差额 **9,432.480474 ms 未归因**（不命名任何单项）。窗口：`FIRST_SEEN 23:06:38.610287` → `LAST_SEEN 23:09:06.542151`，其后稳定性复读不变。**TASK-150/151 的「不可归因（测量未执行）」结论原样保留、未改写。**

## 硬门槛与构建

- **提权实证**：`IsAdmin=True`；`BUILTIN\Administrators` `S-1-5-32-544` **已启用**；`Mandatory Label\High Mandatory Level` `S-1-16-12288`（原始 `task152-00-env-start.txt`）。
- **HEAD / 脏项**：HEAD 与任务书一致；既有脏项（归档移名删除侧 6、`spec/changes/archive/*`、`.codex/`、`.trae/`、`add-verify-degrade-status-index/`）+ 本任务未跟踪三件套为交付输入，**未 stash、未 `git add -A`、未触碰**。
- **构建（唯一入口）**：`bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service package` → **EXIT_CODE=0**、`Tests run: 36` / `Tests run: 110` 全绿、`BUILD SUCCESS`、53.155 s；四 jar 哈希：gateway `887c9cb4…`、user `94464e83…`、record `197978fc…`、verify `d4aea3c4…`。另在普通会话先行同一入口预跑一次（rc=0、146 用例全绿）以提前排除构建失败。

## 受控切换与前置

- **宿主 Redis 原状态（只读）**：`Running / Auto`、`StartName=NT AUTHORITY\NetworkService`、PID 6684、路径 `D:\develop1\Redis-x64-3.0.504\redis-server.exe --service-run redis.windows.conf`；6379 仅其监听；**已建立连接：无**；**依赖服务：无**；其他活跃会话仓库走 Testcontainers 随机端口、无应用级 6379 依赖 → 归属清晰、可逆。
- **切换**：`Stop-Service -Name Redis`（**启动类型保持 Automatic**、未 `Stop-Process`、未改配置）→ `docker start sport-verify-redis`（**既有容器**，v7.2.16，`Created=2026-09-21`、`RestartPolicy=no` 未变）→ 6379 由容器监听。Nacos / RocketMQ namesrv / broker 按既有容器启动，全部 healthy；MySQL 沿用已在运行的 `sport-verify-mysql`（宿主 3307，原卷 `sport-verify_mysql-data`，未重建 / 未清库 / 未删卷）。
- **前置核对**：outbox 36400 行全 `SENT`、**两类 PENDING=0**；`performance_schema=1`、`statement/sql/update` `ENABLED+TIMED`、`statements_digest` 启用、`digest_lost=0`；`verify_db` 下**无** UPDATE digest（目标基线 **0**，未重置计数器）；单实例四服务（8080–8083）各一、`8080/actuator/health` 200 UP；起栈用同一 jar；`relay-interval` 默认 **5000ms**、batch/maxRetry/池/MQ/JVM/SQL 未改；额外参数**只有** `--verify.outbox.relay-diagnostics-enabled=true`（既有有界诊断）。

## 一次负载与配平

- **命令**：`bash scripts/perf/run-perf.sh load 100 2000 task152`（含预热 10），**只跑一次**；**退出码 0**；负载窗口 23:06:24–23:06:51。同期提交侧 2000/2000 成功、0 错误 0 限流；QPS 105.76、P50 852.46 ms、P95 1468.51 ms、P99 1883.20 ms、MAX 3422.00 ms。排空至两类 PENDING=0（`maxid=38410`）。
- **配平闭合**：目标 digest `6b07036b…12b05b7` `COUNT_STAR` 增量 **2010** = 成功 `markSent` **2010** = 22 个非空批次成功行数 **2010** = outbox 行增长 **2010**（36400→38410）；`failed/exhausted/lockSkips` 全 0；目标 digest 是 `verify_db` 下**唯一** UPDATE digest；单实例 verify-service；MySQL processlist 无外部客户端。
- **比值**：26,750.519526 ms / 36,183 ms = **0.739312（73.9312%）**；批次日志量化边界：22 批各向下取整，真实外层累计 [36,183, 36,205) ms，固定 P_S 读数时比值 (0.738862575, 0.739311818]；不包含运行混杂等其他不确定性。差额 9,432.480474 ms **未归因**。
- **披露**：另一会话 Maven（PID 2492，23:07:35 创建）与 surefire（PID 40072，23:08:15 创建）在**负载结束后、排空期间**出现，可能抬高排空阶段外层墙钟；其数据库为 Testcontainers 随机端口、**不使用演示实例 / `verify_db`**，无同 SQL 干扰、不影响计数闭合；负载窗口内本机只有本任务四个 Java 进程。

## 强制恢复（先于文档与提交）

- 停本轮 Java（PID 4216 / 42332 / 21072 / 33796，**先打印命令行再终止**）；停本轮容器（redis / nacos / rocketmq-broker / rocketmq-namesrv）回到原 `Exited` 状态；`sport-verify-mysql` 保持 `Up (healthy)`；其他会话进程 / 容器未触碰。
- `Start-Service` 恢复宿主 `Redis` → **Running / Automatic**（启动类型未变）；6379 由宿主 3.0.504 重新监听（`redis_version:3.0.504`、`os:Windows`）；演示容器 `sport-verify-redis` `Exited (0)`。**恢复失败项：无。**

## 验收与退出码口径

- **本任务运行过 Maven**：唯一入口离线 package → **退出码 0**（提权窗口一次；普通会话预跑一次同为 0）。**未改任何生产代码**，无红绿用例需补；`--mode=online`/CI 未跑，**未达外部门槛**。
- JSON 校验：`spec/changes/measure-verify-mark-sent-admin-window/tasks.json`、`docs/perf/data/exp-outbox-relay-mark-sent-server-event-admin-window.json`、`docs/perf/data/raw/task152-c100-summary.json` 均解析通过（rc=0）。`git diff --check` rc=0（无新增空白错误）。
- 无参数 `mailbox-contract.sh`：**提交前** rc=1（本任务在途清单与既有脏项同现，属预期口径）；**提交后** rc=0（足迹不在工作树，视为已收口）。原始日志 `docs/perf/data/raw/task152-15-mailbox-contract-precommit.log`、`task152-16-mailbox-contract-postcommit.log`。

## 工作树如实说明（必须保留）

- 既有脏项（归档移名删除侧与 `spec/changes/archive/*` 新侧、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`）**非本任务产物、未暂存、原样保留**。
- 本任务 raw 证据在 ignored `docs/perf/data/raw/task152-*`（未入库）；演示负载新增了 `verify_db.verify_event_outbox` 行与相关业务数据，**未清库、未删卷**。
- 收尾仅停**本轮启动**的四个 Java 与四个演示容器；演示 MySQL 保持运行；宿主 Redis 已复位；未 push、未建 PR。

## 实际改动清单（只改）

- **业务证据提交 `a10f4764857c7c213dd39e38b5467eb81bb75dc7`（5 文件）**：`docs/perf/复测-outbox-markSent-服务端语句事件总墙钟-管理员窗口.md`（新）· `docs/perf/data/exp-outbox-relay-mark-sent-server-event-admin-window.json`（新）· `spec/changes/measure-verify-mark-sent-admin-window/proposal.md`（新）· `spec/changes/measure-verify-mark-sent-admin-window/tasks.json`（新）· `spec/changes/measure-verify-mark-sent-admin-window/specs/sport-record-verify/spec-delta.md`（新）。
- **台账提交（4 文件）**：`work/mailbox/tasks/TASK-152/spec.md`（新）· `work/mailbox/tasks/TASK-152/handoff.md`（新）· `work/mailbox/PLAN.md`（改）· `work/mailbox/后端优化机会总览-2026-09-26.md`（改）。
- 既有脏项原样保留；未用 `git stash`、未 `git add -A`、未 push、未建 PR。

## 未覆盖 / 不得推出

- **未覆盖**：差额的成本组成分解；多实例锁竞争；完整生产上下文（Nacos/MQ/Redis/Feign/调度/Web 全链）；`--mode=online`/CI 外部门槛；其他负载档位（仅 c100×2000 一轮）；跨机 / 跨天可比性。
- **不得推出**：不得称已定位瓶颈、不得宣称延迟 / 吞吐改善、不得把 73.93% 当作生产通用比例、不得把差额命名为池等待 / 网络 / 纯 SQL / fsync / CPU / MyBatis、不得改 relay 默认 5000ms 或优化 `markSent`、不得改写 TASK-150/151 未覆盖结论。
