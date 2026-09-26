# 拆解 verify outbox relay 逐行 markSent 调用成本（TASK-146：校正诊断口径 + 一轮有限突发测量）

- 基线 HEAD：`f1c9b6e46da641ab0079dba983aa2bf200d382f4`（与任务书一致）
- 结论：**只测量、只修诊断口径，不实施 markSent 优化、不改任何运行默认值**
- 本轮是**不同 jar / 不同诊断实现**的测量轮，**不得**与 TASK-145 数字作优化前后收益对照
- 机器摘要：`docs/perf/data/exp-outbox-relay-mark-sent-cost.json`
- 原始证据（gitignored）：`docs/perf/data/raw/task146-*.log|csv|tsv|json`

## 1. 本任务修的两个诊断反例

TASK-145 成功路径数字（failed=0）不被前一缺陷推翻，但两处口径必须改正，否则会误导后续归因。

### 1.1 失败分支从 `sendStart` 重算，把已计过的发送与标记错归到发送

旧实现：发送段用单一 `try/catch`，`catch` 里从 `sendStart` 重新累计 `sendNanos`——一旦
`markSent` 抛错，**已经成功返回的发送墙钟**连同**失败的标记过程墙钟**被重复计入发送。

**红证据（真实行为红，非编译/环境红）**：`docs/perf/data/raw/task146-red.log`

```
[ERROR] VerifyOutboxRelayTest.relay_markSentFailure_doesNotAttributeMarkTimeToSend:378
        发送很快返回，失败的标记耗时不得错归到发送：sendMs=303 / ... markMs=0 ...
[ERROR] VerifyOutboxRelayTest.relay_lockHold_reportsCompleteHoldIncludingUnlock:407
        完整占锁须在解锁后取终点，应含解锁耗时：lockHoldMs=0 ...
[ERROR] Tests run: 110, Failures: 2, Errors: 0, Skipped: 0
[verify-entry] Maven 以退出码 1 结束（模式 offline）
```

判别测试 `markSent` sleep 300ms 后抛错，断言 `sendMs < 100 && markMs >= 200`。旧实现在
这条断言上**行为失败**（`sendMs=303`、`markMs=0`）——303ms 的标记耗时被错归到发送。修复后绿。

修复口径：发送段与标记段各自 `try/catch`，成功与失败都**只累计本段墙钟一次**，
失败分支**不再**从 `sendStart` 重算。

### 1.2 `lockHoldMs` 在诊断日志与 `unlock()` 之前截取，不是完整占锁

旧实现取终点在摘要输出之前、`unlock()` 之前，只是**锁内处理段**。修复后分两级：

- `lockProcessingMs` = 取锁成功 → 批次处理结束（**不含**摘要输出与解锁）
- `lockHoldMs` = 取锁成功 → `unlock()` **返回之后**取终点（**含**摘要输出与解锁）

只有后者才能叫「完整占锁」。红证据即上条 `relay_lockHold_reportsCompleteHoldIncludingUnlock:407`
（旧实现 `lockHoldMs=0`：`unlock()` 里的 300ms 未被计入）。

### 1.3 默认关闭零额外开销

开关关闭时 `diagEnabled=false`，**完全不做任何 `System.nanoTime()` 采样**、不产生摘要日志、
不增加任何 DB/MQ 调用。逐行日志仍为原有内容，**未新增逐事件日志、高基数标签或敏感字段**。

## 2. MyBatis→JDBC→Hikari 实际接线与事务边界（审查结论）

- relay `relay()` **无 `@Transactional`**：逐行 `markSent`/`incrRetry` 是各自独立的自动提交调用。
- `markSent` 是 MyBatis-Plus `BaseMapper` 的 `@Update` 注解 SQL
  （`UPDATE verify_event_outbox SET status='SENT', sent_at=NOW() WHERE id=#{id} AND status='PENDING'`），
  经 Spring Boot 自动装配的 `HikariDataSource` + `SqlSessionTemplate` 执行。
- 因此单次 `markSent` 墙钟 = 「连接获取 + 客户端 JDBC prepare/execute 与网络往返 + 服务端 UPDATE + 提交 + MyBatis 映射开销」的**混合墙钟**。

**可安全配对的下层插桩评估 → 判定不可行**：

| 候选 | 结论 |
| --- | --- |
| Micrometer Hikari 指标 | **池级聚合**（acquire/usage count/sum 跨整进程全调用），**无法**配对到某一次 markSent；spec 亦禁止用全局差值推断 |
| 包装 DataSource / Connection | 会改变连接、事务与异常语义 → **禁止** |
| MyBatis Interceptor（按 statement id 过滤） | 只给一个更粗的客户端墙钟，仍无法分离连接获取/提交，且改动**全局插件链** → 判定不安全 |

按 spec「插桩影响语义或无法安全配对 → 停止插桩，把内部构成记为未知，不扩范围」：
**`markSent` 内部构成 = 未知**。**不增加**任何测量接线。

> 口径纪律：本节所有读数均为**客户端混合墙钟**。**不得**称为「纯服务端 SQL」「纯 fsync」或「纯池等待」。

## 3. 单轮有限突发（同 jar、默认 5000ms、诊断开启）

### 3.1 门槛与参数

| 门槛 | 状态 |
| --- | --- |
| offline test（唯一入口） | rc=0，`Tests run: 110, Failures: 0, Errors: 0, Skipped: 0` |
| package | rc=0，同 110/0/0/0，BUILD SUCCESS |
| 接线 | 真实 MyBatis+Hikari+RocketMQ+Redisson（运行态实例），未用 Mockito 冒充 |
| 健康 | verify `/actuator/health` 200 UP；gateway `POST /record/api/records` 可达 |
| 精度 | verify jar sha256 `B067BE288DB153C043190B80444D792DB5AA1B7990A58EBA9FA085145C9DC482` |
| 参数 | `application.yml` 无 `verify.outbox.*` 键；Nacos `verify-service.yml` 404 → `@Value` 默认生效（batch 100 / interval 5000 / max-retry 16）；**唯一改动 = 诊断开关 true** |
| 进程存活 | 单一 relay 实例 PID 36564（创建 00:35:07，独占 8083） |
| 负载前 PENDING 分账 | 可投递 `retry_count<16` = **0**，耗尽待人工 `retry_count>=16` = **0** |

**同 run 关联佐证单实例**：诊断批次行 `rows` 合计 = `success` 合计 = **2010** = cohort，
`lockSkips` 全程 **0** → 全部 2010 行由**同一实例**投递，无第二实例竞争。

**环境瑕疵（如实登记）**：启动时有**第二个 JVM** 争抢 8083，于 `00:35:51` 以
`Web server failed to start. Port 8083 was already in use.` 失败退出；该失败实例**在负载窗口
（00:37:23–00:37:38）之前**已退出，**对本轮测量无影响**（interleaving 见 `task146-verify.log`）。

### 3.2 负载（c100×2000，唯一一轮，预算已用尽）

| 成功 | 限流429 | 错误 | wall(s) | QPS | P50 | P95 | P99 | MAX |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2000 | 0 | 0 | 14.387 | 139.02 | 651.93 | 1106.03 | 1470.39 | 1721.59 |

（延迟单位 ms；`statusHistogram = {200: 2000}`）

### 3.3 批内分段（P50，ms）

| 批次类型 | n | rows | lockWait | select | syncSend | **markSent** | incrRetry | 残差 | lockProcessing | lockHold |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 全部批次 | 22 | 100 | 0 | 6 | 253 | **1048** | 0 | 10.5 | 1308 | 1308.5 |
| 满批(=100) | 18 | 100 | 0 | 5.5 | 255.5 | **1053.5** | 0 | 10.5 | 1314.5 | 1315.5 |
| 短批(<100) | 4 | 52.5 | 1 | 9.5 | 181 | 964.5 | 0 | 10.5 | 1164.5 | 1166 |

- 满批 `markSent` 占锁内处理段 **80.1%**（1053.5/1314.5），`syncSend` 19.4%，`select` 0.4%，残差 0.8%。
- **锁内处理段 vs 完整占锁**：`lockHold − lockProcessing ≈ 1ms`（摘要输出 + 解锁）；两者本任务已分名。
- 残差是**余项**（`lockProcessing = select+send+mark+incrRetry+residual` 为恒等式）；有信息的是**占比**。
- 各段独立取中位数之和与 `lockProcessing` 中位数可差数毫秒，**不是逐周期求和**。

### 3.4 周期闭合

`@Scheduled(fixedDelayString=...)` 是 **fixedDelay**（本轮结束后再等待）。
实测相邻批次行周期 P50 **≈ 6320ms** ≈ 5000ms 固定等待 + ~1315ms 锁内轮次。
锁内 `markSent` 占整周期约 **16.7%**。

### 3.5 同 run 关联、积压与排空

- cohort = 主体 2000 + 内置预热 10 = **2010**；`sent = success = 2010`，failed=0、exhausted=0，覆盖率 100%。
- relay 首末 SENT：`00:37:27.484 → 00:39:50.070`，跨度 **142.586s**，净投递 **14.10 行/s**。
- 可投递 PENDING 峰值（**观测**）**1571** @ `00:38:08.935`；`00:37:41`~`00:38:08` 之间的真实峰值
  **未知**（≥1571）。耗尽待人工 PENDING 全程 **0**。
- 排空至 0 @ `00:39:54.379`；SENT `34390 → 36400`（+2010）。
- `retry_count > 0` 全程 **0**，`retry_count` 最大 **0** → 每行**首次尝试即成功**，无重发、无耗尽。

### 3.6 可得资源读数（采集缺口如实标注）

来源 `/actuator/prometheus`，**排空后快照**（非运行中采样）；累计值自进程启动 00:35:07 起，
**覆盖**本轮。

- Hikari：`max=10 / min=10`（未改）；`acquire` count=8131、sum=135.5576s、max=0.0065646s；
  `usage` count=8131、sum=135.743s、max=0.003s。**注**：count/sum 为累计、max 为滚动窗口，
  **不可** sum/count；这些是**池级聚合**（含消费与回调等），**不能**配对到某一次 `markSent`。
- JVM：年轻代 GC 28 次、暂停合计 0.088s；堆 Eden 20.0MB / Survivor 5.3MB / Old 102.6MB（排空后）。
  `process_cpu_usage` 为**瞬时 gauge**，不可相加。
- **verify GC 日志未知**：`start-services` 只为 record-service 开了 `-Xlog:gc`，verify 未开。
- MySQL：`Threads_connected=22`、`Queries=631915`、`Innodb_data_fsyncs=226941`、
  `Innodb_os_log_fsyncs=103353`（容器**全局累计**，与 record_db 共用 → **不可归因**到 outbox）。
- MQ：容器内无 `mqadmin` → `MQ_BROKER_STATUS_UNAVAILABLE`；**不以「无异常」代替 broker 证据**。

## 4. 直接测得 / 推断 / 未知

- **直接测得**：发送段与标记段各自一次计时的行为红/绿；锁内处理段与完整占锁的分名；
  满批 `markSent` 混合墙钟 P50 1053.5ms 及其占锁内处理段 80.1%；单轮 cohort 2010 全成功、
  failed=0、retry=0；观测积压峰值 1571 与排空时刻；池级 Hikari 聚合与 GC 累计计数。
- **推断（有界、低置信）**：`markSent` 是锁内最大段 → 是「下一步唯一待证因素」的**外边界**；
  周期闭合 `5000 + ~1315ms` 的分解。
- **未知**：`markSent` **内部**构成（连接获取 / JDBC 执行 / 提交 / 服务端 SQL / 残差）；
  运行中 JVM/GC/Hikari 采样；真实积压峰值；多实例锁竞争；榜单段与 R5。

## 5. 停止条件与下一步

- 本任务**不实施** markSent 优化、**不改**默认 `relay-interval-ms=5000`、批次、SQL/索引、
  连接池、MQ 或 JVM 参数；**不宣称**任何吞吐改善。
- **下一步唯一待证因素**：`markSent` 每行内部成本构成。需**另立单因素提案**，
  并以**可安全配对**的下层证据（不改变连接/事务/异常语义）在同负载下验收；
  在此之前**不得**据 10ms/行 去批量 UPDATE、调池或改调度。

## 6. 未覆盖

- 榜单消费 / `SENT→榜单` 段（leaderboard 未启动）；R5 离路质量 / mapmatch CPU（PostGIS+mapmatch 未启动）。
- `--it`、`--mode=online`、CI；500/1000 并发档（禁止）。
- `maxRetry<=0`、非法 `retry_count`、大规模耗尽行扫描成本；多实例锁竞争。
- 本轮为**四服务局部**口径，不外推端到端或持续负载。