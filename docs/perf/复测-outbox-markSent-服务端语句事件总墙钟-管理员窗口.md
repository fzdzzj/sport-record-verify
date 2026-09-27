# 复测：同负载窗口内 verify_db markSent UPDATE 的 MySQL 服务端语句事件总墙钟（TASK-152，管理员窗口，已执行）

- **任务**：在 TASK-150 的同一问题与判据下，回答同一真实 relay 负载、**同一时间窗**内，MySQL Performance Schema 对 `verify_db.verify_event_outbox` 的 `markSent` UPDATE 记录的**服务端语句事件总墙钟**，相对于 relay 外层 `markMs` 同窗总墙钟是什么量级。**只测量、不优化**。该值**不是**逐请求配对读数，**不是**纯 SQL / 纯 fsync / 完整事务成本，差额**不得**命名。
- **开工 HEAD**：`9cf6d174f4a1ae2c2ded644ba757d758873261b8`（与任务书一致；作业期间未换基线）。
- **日期**：2026-09-27。
- **机器摘要**：`docs/perf/data/exp-outbox-relay-mark-sent-server-event-admin-window.json`。
- **原始证据（gitignore，未入库）**：`docs/perf/data/raw/task152-*`。
- **结论**：**测量已执行一次，计数闭合，比值如实报出**——服务端语句事件总墙钟 **26,750.519526 ms**（26,750,519,526,000 ps）/ 外层 `markMs` 总墙钟 **36,183 ms** = **0.739312（73.93%）**；算术差额 **9,432.480474 ms 未归因**。适用边界：**单轮、本机、只开既有有界 relay 诊断**的**同窗聚合**比值，**未达外部门槛**，不构成生产通用比例或优化收益。TASK-150/151 的「不可归因（测量未执行）」结论**原样保留、未改写**。

## 0. 结论摘要

1. **硬门槛成立**：本会话已提升（`IsAdmin=True`；`BUILTIN\Administrators` `S-1-5-32-544` 已启用；`High Mandatory Level` `S-1-16-12288`）；`verify-service` jar 经唯一入口 `mvn-verify.sh --mode=offline --pl verify-service package` 补齐，**退出码 0**、`Tests run: 36 / 110`、0 失败 0 错误 0 跳过、`BUILD SUCCESS`（53.155 s）；四个服务 jar 哈希记录在案。
2. **受控切换可逆且已恢复**：经**服务管理器**临时 `Stop-Service Redis`（启动类型保持 `Automatic`，未 `Stop-Process`、未改配置），启动既有 `sport-verify-redis`（`redis:7.2-alpine`，v7.2.16，原容器 Created 2026-09-21、`RestartPolicy=no` 未变）；作业结束后 `Start-Service` 恢复宿主服务 → **Running / Automatic**、6379 由宿主 3.0.504 重新监听。**无恢复失败项**。
3. **前置全部成立**：演示 MySQL 原卷 `sport-verify_mysql-data`；`verify_db.verify_event_outbox` 负载前 36400 行全 `SENT`、两类 PENDING=0（无未解释积压）；`performance_schema=1`、`statement/sql/update` `ENABLED+TIMED`、`statements_digest` 启用、`digest_lost=0`；目标 digest 在演示库**不存在（基线 0）**；单实例四服务健康（`8080/actuator/health` UP）、同一批 jar、默认 `relay-interval=5000ms`、仅开启**既有有界** relay 诊断。
4. **负载只跑一次**：`run-perf.sh load 100 2000 task152`（含预热 10）→ **退出码 0**，2000/2000 成功、0 错误 0 限流；wall 18.911 s、QPS 105.76、P50 852.46 ms、P95 1468.51 ms、P99 1883.20 ms、MAX 3422.00 ms；cohort 排空至两类 PENDING=0。
5. **配平闭合**：目标 digest `COUNT_STAR` 增量 **2010** = 本轮成功 `markSent` 次数 **2010** = relay 22 个非空批次成功行数 **2010** = outbox 行增长 **2010**（36400→38410）；`failed/exhausted/lockSkips` 全 0；目标 digest 是 `verify_db` 下**唯一** UPDATE digest 且末次事件后稳定性复读不变。
6. **比值**：`SUM_TIMER_WAIT` = 26,750,519,526,000（单位经 `SELECT SLEEP(1)` 标定为**皮秒**）→ 26,750.519526 ms；逐批 `markMs` 合计 **36,183 ms** → **0.739312（73.9312%）**；差额 9,432.480474 ms **不命名**任何单项；仅考虑 22 批 `markMs` 各自向下取整时，真实外层累计落在 [36,183, 36,205) ms，所测比值落在 (0.738863, 0.739312]；此区间不包含运行条件混杂等不确定性。
7. **披露一处混杂因素**：另一会话的 Maven（PID 2492，23:07:35 创建）与 surefire JVM（PID 40072，23:08:15 创建）在**负载窗口之后、cohort 排空期间**启动（负载窗口 23:06:24–23:06:51 内只有本任务四个 Java 进程），可能抬高排空阶段的外层墙钟读数；其数据库走 Testcontainers 随机端口，**不使用演示实例/verify_db**，**无同 SQL 干扰**，不影响计数闭合。
8. **零业务改动**：未改任何 Java / SQL / YAML / 脚本 / relay 默认值 / MQ / 池 / JVM / MySQL instrumentation；未清库、未删卷、未重置 P_S 计数器；未 push、未建 PR。

## 1. 硬门槛：提权与构建（中断服务之前）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 会话提权 | `IsAdmin=True`（`WindowsPrincipal.IsInRole(Administrator)`）；`BUILTIN\Administrators` `S-1-5-32-544` **已启用**；强制完整性 `Mandatory Label\High Mandatory Level` `S-1-16-12288` | `task152-00-env-start.txt` |
| HEAD 核对 | `9cf6d174f4a1ae2c2ded644ba757d758873261b8`，与任务书一致；未换基线 | 同上 |
| 既有脏项 | porcelain 12 项：归档移名删除侧 6、未跟踪 `spec/changes/archive/{adopt-native-mq-retry,wire-verify-outbox}/`、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/`，以及本任务未跟踪三件套 `spec/changes/measure-verify-mark-sent-admin-window/`（交付输入） | 同上 |
| 构建（提权窗口） | `mvn-verify.sh --mode=offline --pl verify-service package` → `mvn -B -ntp -o -s .mvn-settings.xml clean package -pl verify-service -am`；**EXIT_CODE=0**、`Tests run: 36` 与 `Tests run: 110` 全绿、`BUILD SUCCESS`、53.155 s | `task152-02-build-verify-service.log`、`task152-03-build-and-jar-hashes.txt` |
| 四 jar 哈希 | gateway `887c9cb4…` / user `94464e83…` / record `197978fc…` / verify `d4aea3c4…` | `task152-03-build-and-jar-hashes.txt` |
| 非提权预跑（先行排除构建失败） | 同一入口在普通会话先跑一次：`EXIT_CODE=0`、01:20 min、common 36 + verify 110、总计 146、0 失败 0 错误 0 跳过；证明构建不依赖提权 | `task152-10-preflight-build-verify-service.log`、`task152-11-preflight-jar-state.txt` |

**起点进程/容器**：作业开始时**无本任务 Java 进程**；演示容器 `sport-verify-{mysql,…}` 中 MySQL `Up (healthy)`（TASK-150 启动后保持），`redis/nacos/rocketmq-*` 均 `Exited (255)`；其他会话的 `task131-scratch-mysql`（Up，宿主 13318）仅只读记录。既有脏项**未 stash、未 `git add -A`、未触碰**。

## 2. 受控端口切换与中间件起栈

**宿主 Redis 原状态（只读，中断前）**：`Name=Redis`、`State=Running`、`StartMode=Auto`、`PathName="D:\develop1\Redis-x64-3.0.504\redis-server.exe" --service-run redis.windows.conf`、`StartName=NT AUTHORITY\NetworkService`、`ProcessId=6684`；6379 由 6684 以 `0.0.0.0` + `[::]` 监听；**到 6379 的已建立连接：无**；**依赖服务：无**（`--required--` 空）；其他活跃会话仓库（`crmAndRag-merge-add-knowledge-admin-api`）本轮检索 `redis/6379` 无应用配置命中（沿用 TASK-151 结论：走 Testcontainers 随机端口，不依赖宿主 6379）。→ 归属清晰、可逆，满足切换前提。

| 步骤 | 结果 |
| --- | --- |
| 服务管理器暂停 | `Stop-Service -Name Redis` → `Status=Stopped`，**`StartType` 保持 `Automatic`**；6379 监听消失；无 `redis-server` 进程 |
| 启动既有演示 Redis | `docker start sport-verify-redis`（**既有容器，未重建**）→ `Up (health: starting→healthy)`；`Redis server v=7.2.16`、`PONG`；6379 由容器进程监听 |
| 容器未变性 | `Created=2026-09-21T15:49:31Z`、`RestartPolicy=no`、`RestartCount=0`、`Image=redis:7.2-alpine`（未改端口 / compose / 卷） |
| 其余中间件（既有容器） | `sport-verify-nacos`（8848/9848）、`sport-verify-rocketmq-namesrv`（9876）、`sport-verify-rocketmq-broker`（10911/10909）依次启动，全部 `Up (healthy)`；MySQL 沿用已在运行的 `sport-verify-mysql`（宿主 3307） |

原始证据：`task152-01-redis-pre.txt`、`task152-04-redis-switch.txt`、`task152-05-middleware.txt`。

## 3. 前置核对（不满足则不跑负载）

| 检查 | 结果 |
| --- | --- |
| 演示 MySQL 原卷 | `Mounts=volume/sport-verify_mysql-data->/var/lib/mysql`；容器 `Created=2026-09-25`、`RestartCount=0`（未重建、未 `down -v`、未清库） |
| `verify_db` 分账（负载前） | 表 `appeal / rule_version / verification_result / verify_event_outbox`；outbox `SENT 36400`（id 1..36400）；**`deliverable_pending=0`、`exhausted_pending=0`** → 无未解释积压，无需先排空 |
| Performance Schema | `@@performance_schema=1`、`digests_size=10000`、`max_digest_length=1024`；`consumer:statements_digest=YES`；`instr:statement/sql/update=YES YES`；`global_status:digest_lost=0`；仪器开关**未做任何修改**、**未重置计数器** |
| 目标 digest 基线 | `verify_db` 下**无任何** UPDATE digest 命中 outbox；目标 digest 行**不存在 → 基线 0**（实例当日 11:11 启动，P_S 为内存态） |
| 单实例四服务 | `record`(8082,PID 4216)、`verify`(8083,PID 42332)、`user`(8081,PID 21072)、`gateway`(8080,PID 33796) 各一；`MYSQL_PORT=3307`；端口 8080–8083 监听核验；`8080/actuator/health` → 200 `{"status":"UP",…}`（网关根路径 `/health` 为 404，属既有口径） |
| 同一 jar / 默认值 | 起栈用的即第 1 节构建产物；`verify-service` 额外参数**只有** `--verify.outbox.relay-diagnostics-enabled=true`（既有有界诊断）；`relay-interval` 默认 **5000ms**、batch 100 / maxRetry 16、池 / MQ / JVM / SQL / 索引均未改 |

全部成立 → 运行负载。原始证据：`task152-06-db-precheck.txt`、`task152-07-services.txt`、`task152-08-ps-pre.txt`。

## 4. 一次同窗负载

- **命令**：`bash scripts/perf/run-perf.sh load 100 2000 task152`（phase/out-prefix `task152`，含预热 10；`--warmup 10 --user-total 16`）。**只跑一次，未重跑**。
- **负载窗口**：23:06:24 → 23:06:51（wall 18.911 s）。**退出码 0**。
- **提交侧**：2000/2000 成功（`{200=2000}`）、0 错误、0 限流；QPS 105.76；P50 852.46 ms、P95 1468.51 ms、P99 1883.20 ms、MAX 3422.00 ms。
- **cohort 排空**：`sent` 36400→38410（23:07:01→23:09:12 观测），**两类 PENDING 归 0**（`pending=0, maxid=38410`）。
- **负载期本机进程**：仅本任务四个 Java 进程（无其他会话干扰正在运行的任务）。

原始证据：`task152-09-load.log`、`task152-c100-summary.json`、`task152-c100-raw.csv`、`task152-10-drain.txt`。

## 5. 配平与比值（同窗聚合）

**目标 digest**（与 TASK-150 scratch 辅助识别一致，且本轮在演示库直接读到）：

```
6b07036bdfeae6b8f12d602eb37af86bfe73e3f9dac3600006dfd8cde12b05b7
UPDATE `verify_event_outbox` SET STATUS = ? , `sent_at` = NOW ( ) WHERE `id` = ? AND STATUS = ?
```

| 配平项 | 值 |
| --- | --- |
| 负载前 `COUNT_STAR` | 0（行不存在） |
| 排空后 `COUNT_STAR` | **2010** |
| 增量 = 成功 `markSent` 次数 | 2010（`failed=0`、`exhausted=0`、`incrRetryMs` 每批 0） |
| = 批次成功行数合计 | 2010（22 个非空批次 `rows=success` 合计） |
| = outbox 行增长 | 2010（36400 → 38410） |
| digest 唯一性 | `verify_db` 下**唯一** UPDATE digest；`digest_lost=0`；单实例 verify-service（仅 PID 42332 载入 verify jar）；MySQL processlist 全部为四服务连接（`user_db/verify_db/record_db`），无外部客户端 |
| 窗口完整性 | digest `FIRST_SEEN=23:06:38.610287`（负载开始之后）、`LAST_SEEN=23:09:06.542151`（排空末批之后）；末次事件后于 23:10:56 稳定性复读 `COUNT_STAR` 仍为 2010 → 窗口外无同 SQL 事件 |

**单位标定（实证，非文档推断）**：本实例**无** `performance_schema.setup_timers` 表，故以 `SELECT SLEEP(1)` 的 digest `TIMER_WAIT = 1,004,053,480,000`（≈1.004 s）标定 → 语句事件计时单位为**皮秒**（1 ms = 10⁹）。

| 量 | 值 |
| --- | --- |
| 服务端语句事件总墙钟 | 26,750,519,526,000 ps = **26,750.519526 ms** |
| 外层 `markMs` 同窗总墙钟 | **36,183 ms**（22 个非空批次的 `markMs` 逐批求和；批次行窗口 23:06:40.630–23:09:06.544） |
| **同窗聚合比值** | **0.739311818 → 0.739312（73.9312%）** |
| 算术差额 | **9,432.480474 ms（未归因）** |
| 批次日志量化边界 | `TimeUnit.NANOSECONDS.toMillis(markNanos)` 在**每个非空批次**各向下取整一次；22 批合计的外层真实纳秒计时折合毫秒落在 **[36,183, 36,205)**。固定 P_S 所报 26,750.519526 ms 时，比值在 **(0.738862575, 0.739311818]**，约 **73.8863%～73.9312%**。这是日志量化边界，不是运行条件、P_S 计时器或并发干扰的完整误差区间。 |

**差额（26.07%）不命名任何单项**：既不得称「池等待」「网络」「纯 SQL」「fsync」「CPU」「MyBatis」，也不得由独立 P50 相减得出。本比值只回答「同窗聚合的服务端语句事件墙钟相对外层标记墙钟的量级」。

辅助观察（不构成第二次测量、不单独出比值）：负载窗口内完成的批次为 2 个（`rows=100`、`markMs=4555`），其余 20 个批次（`rows=1910`、`markMs=31628`）发生在排空阶段。

原始证据：`task152-11-ps-post.txt`、`task152-12-relay-batches.txt`。

## 6. 披露、反例排查与未覆盖

- **混杂因素（如实披露）**：另一会话的 Maven（PID 2492，创建 23:07:35）与 surefire JVM（PID 40072，创建 23:08:15）在**负载结束之后、排空进行中**启动；其 MySQL 走 Testcontainers 随机端口，**不使用演示实例 / `verify_db`**，故**不构成同 SQL 干扰**、不影响计数闭合；但排空阶段的外层 `markMs` 是宿主墙钟，可能被该并发负载抬高（方向与幅度**不可量化**）。负载窗口（23:06:24–23:06:51）内本机只有本任务四个 Java 进程。
- **已排除的反例**：digest 溢出（`digest_lost=0`、`null_digest_rows=0`）；其他实例（单实例、单 jar）；其他 UPDATE 语句（`verify_db` 无第二条 UPDATE digest）；窗口外尾随事件（稳定性复读不变）；计数器人为重置（未重置，基线为「行不存在」）；以宿主 Redis 3.0.504 冒充演示 Redis（未发生，容器 v7.2.16 实际承载 6379）。
- **未覆盖**：真实生产上下文（Nacos/MQ/Redis/Feign/调度/Web 全链）、多实例锁竞争、`--mode=online`/CI 外部门槛、差额的成本组成分解、跨天/跨机可比性、其他负载档位（本任务只跑 c100×2000 一次）。
- **不得由本任务推出**：不得称已定位瓶颈、不得宣称延迟 / 吞吐改善、不得改 relay 默认 5000ms 或优化 `markSent`、不得把 73.93% 当作生产通用比例、不得把差额命名为任何单一成本项、不得改写 TASK-150/151 的未覆盖结论。

## 7. 强制恢复（先于文档与提交）

| 步骤 | 结果 |
| --- | --- |
| 停本轮 Java | 精确 PID（4216 / 42332 / 21072 / 33796）**先打印命令行再终止**；复读后仅剩其他会话的 Maven 进程（13884），本任务 4 进程消失 |
| 停本轮容器 | `sport-verify-{redis,nacos,rocketmq-broker,rocketmq-namesrv}` 停止，回到原 `Exited` 状态；`sport-verify-mysql` 保持 `Up (healthy)`（与起点一致）；`task131-scratch-mysql` 未触碰 |
| 恢复宿主 Redis | `Start-Service` → `Status=Running`、**`StartType=Automatic`（未变）**；6379 由宿主服务进程（PID 44128）以 `0.0.0.0`+`[::]` 监听；`redis_version:3.0.504`、`os:Windows` → 宿主服务确已复位；演示容器 `sport-verify-redis` `Exited (0)` |
| 归属核对 | 全程未 `Stop-Process`、未改服务启动类型 / 端口 / compose、未重建容器或数据卷；其他会话进程与其容器未启停 |

**恢复失败项：无。** 原始证据：`task152-13-restore.txt`。

## 8. 判据、退出码与契约口径

- **本任务运行过 Maven**（构建 jar 属任务门槛）：唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service package` → **退出码 0**（提权窗口一次；普通会话预跑一次同为 0）。**未改任何生产代码**，因此无红绿用例需要补。`--mode=online`/CI 未跑，**未达外部门槛**。
- JSON 解析、`git diff --check`、无参数 `mailbox-contract.sh` 的实际退出码与提交前/后状态见 `docs/perf/data/raw/task152-*` 与 TASK-152 handoff。

## 9. 证据文件（原始，gitignore 未入库）

| 文件 | 内容 |
| --- | --- |
| `task152-00-env-start.txt` | 提权探针、HEAD、工作树脏项、容器与进程起点 |
| `task152-01-redis-pre.txt` | 宿主 Redis 原状态 / 6379 / 客户端 / 依赖 / 其他会话检索 |
| `task152-02-build-verify-service.log`、`task152-03-build-and-jar-hashes.txt` | 提权窗口构建日志（rc=0、53.155 s）与四 jar 哈希 |
| `task152-10-preflight-build-verify-service.log`、`task152-11-preflight-jar-state.txt` | 非提权会话预跑构建日志与 jar 状态（先行排除构建失败；编号与本轮 10/11 已用 `-preflight` 区分） |
| `task152-04-redis-switch.txt` | `Stop-Service` → 演示 Redis 启动的受控切换（含容器未变性核对） |
| `task152-05-middleware.txt` | 中间件起栈（nacos/namesrv/broker） |
| `task152-06-db-precheck.txt` | 原卷、`verify_db` 分账、P_S 开关与 pre digests |
| `task152-07-services.txt` | 四服务单实例、端口、健康、诊断开关 |
| `task152-08-ps-pre.txt` | 负载前目标 digest（不存在=0）与资源快照 |
| `task152-09-load.log`、`task152-c100-summary.json`、`task152-c100-raw.csv` | 负载日志、摘要与逐请求原始 CSV |
| `task152-10-drain.txt` | cohort 排空观测 |
| `task152-11-ps-post.txt` | 排空后 digest 快照、唯一性与干扰排查、资源快照 |
| `task152-12-relay-batches.txt` | 22 个非空批次原始诊断行、求和、窗口、单位标定与比值算式 |
| `task152-13-restore.txt` | 强制恢复与核验（Java/容器/宿主 Redis/6379） |
