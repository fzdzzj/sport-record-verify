# TASK-146 回传：校正 relay 诊断口径并测量逐行 markSent 调用成本（一轮 c100×2000、默认 5000ms、诊断开启，只测量不优化）

## 回传概要

- **开工 HEAD**：`f1c9b6e46da641ab0079dba983aa2bf200d382f4`（与任务书一致，核对后开工）。既有脏项
  archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、`.trae/`、
  `spec/changes/add-verify-degrade-status-index/` 未触碰；未 stash、未 add -A、未清库。
- **提交**：两笔本地提交——诊断计时修正（2 主 + 2 测）+ 报告 + 机器摘要 + 规范三件套为**业务提交
  `9adb94555cc5824cf4fd787930632b6ab798f00c`**（9 文件）；TASK-146 两件套 + PLAN + 总览为**台账提交**
  （哈希由任务回传承载）。未 push、未建 PR。
- **结论**：**只测量、只修诊断**。修正两处会误导后续归因的计时反例；**未实施 markSent 优化、未改任何
  运行默认值、不宣称 TASK-146 优化吞吐**。本轮为**不同 jar / 不同诊断实现**，**不得**与 TASK-145 数字
  作优化前后收益对照。
- **本任务直接测得的外边界**：满批（rows=100）`markSent` P50 **1053.5ms = 锁内处理段 80.1%**（`syncSend`
  255.5ms 19.4%）；周期 P50 **≈6320ms** = 5000ms 固定等待 + ~1315ms 轮次。
- **下一步唯一待证因素**：**`markSent` 每行内部成本构成**（连接获取 / 客户端 JDBC 执行 / 提交 / 服务端 SQL /
  残差）。本轮**无法安全配对**，内部构成**记未知**（见下）。

## 本任务修的两个诊断反例（含行为红）

**(a) 失败分支从 `sendStart` 重算 → 发送/标记重复归集**。旧实现单一 `try/catch`，`catch` 里从 `sendStart`
重新累计 `sendNanos`，`markSent` 抛错时把**已成功的发送墙钟**连同**失败的标记过程墙钟**重复计入发送。
**(b) `lockHoldMs` 原在摘要输出与 `unlock()` 之前截取**，只是锁内处理段，不能叫完整占锁。

**行为红（真实行为断言失败，非编译/环境红）** `docs/perf/data/raw/task146-red.log`：

```
[ERROR] VerifyOutboxRelayTest.relay_markSentFailure_doesNotAttributeMarkTimeToSend:378
        发送很快返回，失败的标记耗时不得错归到发送：sendMs=303 / ... markMs=0 ...
[ERROR] VerifyOutboxRelayTest.relay_lockHold_reportsCompleteHoldIncludingUnlock:407
        完整占锁须在解锁后取终点，应含解锁耗时：lockHoldMs=0 ...
[ERROR] Tests run: 110, Failures: 2, Errors: 0, Skipped: 0
[verify-entry] Maven 以退出码 1 结束（模式 offline）
```

判别测试 `markSent` sleep 300ms 后抛错，断言 `sendMs < 100 && markMs >= 200`；旧实现**行为失败**
（`sendMs=303`、`markMs=0`）——303ms 标记耗时被错归发送。修复后转绿。

**修正口径**：发送段与标记段各自 `try/catch`，成功与失败都**只累计本段墙钟一次**；锁口径分名
`lockProcessingMs`（取锁成功→批次处理结束，不含摘要与解锁）与 `lockHoldMs`（取锁成功→`unlock()` 返回
之后取终点，含摘要与解锁）。默认关闭时 `diagEnabled=false` → **零 `nanoTime` 采样、零额外 I/O**；
未新增逐事件日志/高基数标签/敏感字段；原 eventId/SENT/重试/异常传播/锁释放语义未改。

## MyBatis→JDBC→Hikari 接线审查与插桩判定

- relay `relay()` **无 `@Transactional`**；逐行 `markSent`/`incrRetry` 为各自独立自动提交调用。
- `markSent` 是 MyBatis-Plus `BaseMapper` 的 `@Update` 注解 SQL
  （`UPDATE verify_event_outbox SET status='SENT', sent_at=NOW() WHERE id=#{id} AND status='PENDING'`），
  经自动装配 `HikariDataSource` + `SqlSessionTemplate` 执行 → 单次墙钟 = 「连接获取 + 客户端 JDBC
  prepare/execute 与网络往返 + 服务端 UPDATE + 提交 + MyBatis 映射」的**混合墙钟**。
- **可安全配对的下层插桩 → 判定不可行**：Micrometer Hikari 为**池级聚合**（跨整进程全调用，无法配对
  到某一次 markSent，spec 亦禁止全局差值推断）；包装 DataSource/Connection 会**改变连接/事务/异常
  语义**；MyBatis Interceptor（按 statement id 过滤）仍无法分离连接获取/提交且**改全局插件链**。
  → 按 spec **停止插桩**，`markSent` 内部构成**记未知**，**不扩范围**。**未增加任何测量接线**。

## 覆盖与门槛（安全门槛满足 → 跑唯一一轮）

- 四服务栈 `gateway:8080 / user:8081 / record:8082 / verify:8083` 起栈；verify `/actuator/health` **200 UP**；
  gateway `POST /record/api/records` 可达（空体 400 = 路由正常）。**leaderboard / mapmatch 不在栈内**、
  PostGIS 未运行 → 榜单段与真实 R5 **未覆盖**（四服务局部口径）。
- MySQL 8.0.46（宿主 3307）/ RocketMQ 5.2.0 / Redis 7.2 / Nacos 2.3.2 四容器 **healthy**。
- verify jar `sha256=B067BE288DB153C043190B80444D792DB5AA1B7990A58EBA9FA085145C9DC482`
  （**与 TASK-145 的 `95CC9207…` 不同 jar**）。
- **实际生效配置**：`application.yml` **无** `verify.outbox.*` 键；Nacos `verify-service.yml`
  **不存在**（`GET /nacos/v1/cs/configs?dataId=verify-service.yml` → **404**）→ `@Value` 默认
  `batch-size=100`、`relay-interval-ms=5000`、`relay-initial-delay-ms=10000`、`max-retry=16`、
  `relay-diagnostics-window-ms=10000`；`@Scheduled(fixedDelayString=…)` 为 **fixedDelay 语义**。
  **唯一改动 = `--verify.outbox.relay-diagnostics-enabled=true`（默认 false）**；`MYSQL_PORT=3307`。
- **负载前 PENDING 分账**（`00:36:25.604`）：可投递 `retry_count<16` = **0**、耗尽待人工 `retry_count>=16`
  = **0**（SENT=34390）。

## 单轮有限突发（唯一一轮，预算已用尽）

| 成功 | 限流429 | 错误 | wall(s) | QPS | P50 | P95 | P99 | MAX |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2000 | 0 | 0 | 14.387 | 139.02 | 651.93 | 1106.03 | 1470.39 | 1721.59 |

（延迟单位 ms；`statusHistogram={200:2000}`）

- 同 run 关联：cohort = 主体 2000 + 内置预热 10 = **2010**；`sent = success = 2010`，`failed=0`、`exhausted=0`，
  覆盖率 100%。
- **单实例佐证**：诊断批次行 `rows` 合计 = `success` 合计 = **2010** = cohort，`lockSkips` 全程 **0**
  → 全部 2010 行由**同一实例**投递。
- 原个体：**单一运行实例 PID 36564**（创建 `00:35:07`，独占 8083）。

## 批内分段与周期闭合（核心）

| 批次类型 | n | rows | lockWait | select | syncSend | **markSent** | incrRetry | 残差 | lockProcessing | lockHold |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 全部批次 | 22 | 100 | 0 | 6 | 253 | **1048** | 0 | 10.5 | 1308 | 1308.5 |
| 满批(=100) | 18 | 100 | 0 | 5.5 | 255.5 | **1053.5** | 0 | 10.5 | 1314.5 | 1315.5 |
| 短批(<100) | 4 | 52.5 | 1 | 9.5 | 181 | 964.5 | 0 | 10.5 | 1164.5 | 1166 |

（单位 ms，P50）

- 满批 `markSent` 占锁内处理段 **80.1%**、`syncSend` 19.4%、`select` 0.4%、残差 0.8%。
- **锁口径分名**：`lockHold − lockProcessing ≈ 1ms`（摘要输出 + 解锁）→ 两口径可区分、不再混称。
- **周期闭合**：fixedDelay 语义；实测相邻批次行周期 P50 **≈6320ms** ≈ 5000ms 固定等待 + ~1315ms 轮次；
  锁内 `markSent` 占整周期 **≈16.7%**。残差为**余项** → `lockProcessing = select+send+mark+incrRetry+residual`
  是**恒等式**；有信息的是**占比**；各段独立中位数之和 **非**逐周期求和。
- **口径**：`syncSend`/`markSent`/`lockHold` 均为**混合墙钟**；**不得**称纯 MQ / 纯 SQL / 纯池等待。

## 积压与排空

- 可投递 PENDING 峰值（**观测**）**1571** @`00:38:08.935`；`00:37:41`（负载结束）~`00:38:08` 之间的**真实峰值
  未知**（≥1571）。**耗尽待人工 PENDING 全程 0**；`retry_count>0` 全程 **0**、`retry_count` 最大 **0**
  → 每行**首次尝试即成功**、无重发、无耗尽。
- 排空至 0 @`00:39:54.379`；SENT `34390 → 36400`（+2010）。
- relay 首末 SENT `00:37:27.484 → 00:39:50.070`，跨度 **142.586s**，净投递 **14.10 行/s**。

## 资源读数（可得项 + 采集缺口）

- 来源 `/actuator/prometheus`，**排空后快照**（**非**运行中采样）；累计值自进程启动 `00:35:07` 起**覆盖**本轮：
  Hikari `max/min=10`、`acquire` count 8131 / sum 135.5576s / max 0.0065646s、`usage` count 8131 / sum 135.743s；
  年轻代 GC 28 次 / 暂停合计 0.088s；堆 Eden 20.0MB / Survivor 5.3MB / Old 102.6MB。
- **口径纪律**：Hikari `acquire`/`usage` 是**池级聚合**（含消费与回调等），**不可**配对到某一次 `markSent`，
  也不可 sum/count（count/sum 累计、max 滚动窗口）；**不得**称纯 SQL / 纯 pool wait / 纯 fsync。
- **缺口（记未知）**：verify 未开 `-Xlog:gc`（`start-services` 只为 record-service 开）→ **逐轮 GC 日志未知**；
  **运行中** JVM/GC/Hikari 采样未做；MySQL 全局累计计数（`Queries=631915`、`Innodb_data_fsyncs=226941`、
  `Innodb_os_log_fsyncs=103353`）与 record_db 共用 → **不可归因**到 outbox；MQ 容器内无 `mqadmin`
  → **`MQ_BROKER_STATUS_UNAVAILABLE`**（不以「无异常」代替 broker 证据）。

## 异常如实记录（不影响本轮测量）

启动时有**第二个 JVM** 争抢 8083，于 `00:35:51` 以 `Web server failed to start. Port 8083 was already in use.`
**失败退出**。该失败实例**在负载窗口（`00:37:23–00:37:38`）之前已退出**，且全程 `lockSkips=0`、批次
`rows` 合计=2010 → **对本轮测量无影响**（两写者 interleaving 见 `docs/perf/data/raw/task146-verify.log`）。

## 实际改动清单

业务提交 `9adb9455`（9 文件）：

- verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java（改）
- verify-service/src/main/java/com/sportverify/verify/mq/RelayDiagnostics.java（改）
- verify-service/src/test/java/com/sportverify/verify/mq/VerifyOutboxRelayTest.java（改）
- verify-service/src/test/java/com/sportverify/verify/mq/RelayDiagnosticsTest.java（改）
- docs/perf/拆解-outbox-relay-markSent-调用成本.md（新）
- docs/perf/data/exp-outbox-relay-mark-sent-cost.json（新）
- spec/changes/measure-verify-outbox-mark-sent-cost/proposal.md（新）
- spec/changes/measure-verify-outbox-mark-sent-cost/tasks.json（新）
- spec/changes/measure-verify-outbox-mark-sent-cost/specs/sport-record-verify/spec-delta.md（新）

台账提交（4 文件）：

- work/mailbox/tasks/TASK-146/spec.md（新）
- work/mailbox/tasks/TASK-146/handoff.md（新，本文件）
- work/mailbox/PLAN.md（追加验收记录）
- work/mailbox/后端优化机会总览-2026-09-26.md（更新 P2 节、决策项与仓库状态）

**未触碰**：archive 移名（adopt-native-mq-retry / wire-verify-outbox）、`.codex/`、`.trae/`、
`spec/changes/add-verify-degrade-status-index/`；未用 git stash、未 add -A、未清库。

## 本地门槛与退出码

- **离线测试（唯一入口）** `bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test`
  → **rc=0**，`Tests run: 110, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
  （`RelayDiagnosticsTest` 6、`VerifyOutboxRelayTest` 19；**CI 基线 104→110，只增不减**）。
- **package** → **rc=0**，`Tests run: 110, Failures: 0`，BUILD SUCCESS（jar 指纹见上）。
- **红证据分类（不混称）**：
  - **行为红**：保留新判别测试对旧码运行 → `Tests run: 110, Failures: 2`、**rc=1**，正是
    `relay_markSentFailure_doesNotAttributeMarkTimeToSend:378`（`sendMs=303/markMs=0`）与
    `relay_lockHold_reportsCompleteHoldIncludingUnlock:407`（`lockHoldMs=0`）。
  - **编译红 / 环境红 / 契约红**：本轮**未出现**（不冒充）。
- 负载：wrapper 产出完整 `task146-c100-summary.json`（`ok=2000 / errors=0`）；wrapper exit code 受
  Git Bash 经 PowerShell 管道的假象影响，**以 summary.json + 排空至两类 PENDING=0 为凭**。
- 机器摘要 JSON `ConvertFrom-Json`：**rc=0**；`git diff --check`：**rc=0**；
  无参数 `bash scripts/verify/mailbox-contract.sh`：**收口提交后 rc=0**。

## 未覆盖/缺口

- `markSent` **内部**构成（连接获取 / JDBC 执行 / 提交 / 服务端 SQL / 残差）= **未知**（无可安全配对插桩）。
- **运行中** JVM/GC/Hikari 采样与 verify **逐轮** GC 日志（未开 `-Xlog:gc`）；积压**真实**峰值。
- 多实例锁竞争（单实例、`lockSkips=0`）；`maxRetry<=0`、非法 `retry_count`、大规模耗尽行扫描成本。
- 榜单消费 / `SENT→榜单` 段（leaderboard 未启动）；R5 质量/离路率/mapmatch CPU（PostGIS + mapmatch 未启动）。
- `--it`、`--mode=online`、CI 未跑；500/1000 并发档（禁止）。
- 本轮为**四服务局部**口径，**不外推**端到端或持续负载；**不改默认值、不宣称优化收益**。