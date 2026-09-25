# 当前 HEAD 瓶颈归因（TASK-138，只度量不优化）

> 对应变更：`spec/changes/measure-head-bottleneck-attribution/`。本文回答一个问题：
> 在当前 HEAD、同一负载（100 并发 × 2000 请求）下，提交路径与校验路径的时间主要耗在六类
> （业务规则 / 数据库 / 远程调用 / 锁 / CPU / GC）中的哪一类。本任务不实施任何优化。

**结论（top_class，只选一类）：数据库。**
提交路径（唯一被 QPS/P95 直接测量的路径）的同步阻塞全部落在 DB 段：事务内 4 条 SQL +
提交刷盘 + 连接池排队（池 10、并发 100）；该路径成功分支没有任何同步远程调用（MQ 在
事务提交后异步发送），GC 占墙钟约 0.5%，DB 行锁累计 0ms。校验路径的 15 秒级延迟是
消费吞吐不足造成的排队（到达 121 条/s > 消费约 60 条/s），其每条消息的同步链由
3 次内部 HTTP + ≤10 条 SQL 组成——每跳的 handler 仍以 SQL 为底座，是第二因素，不改变本结论。

---

## 1. 环境快照

| 项 | 值 |
| --- | --- |
| HEAD | `58cd1041bac14c24cc801c2d3e383b606d8cc91b`（main，开工基线） |
| JDK | Oracle JDK 21.0.9（`D:/develop1/jdk21`，四个服务为宿主机进程；命令行 PATH 上的 java 是 8，脚本自动探测 21） |
| Maven | 3.9.4，仓库口径 `.mvn-settings.xml`（localRepository `.m2-repo`），离线 `package` BUILD SUCCESS（跳过测试，仅为起栈产物，不是验收门槛） |
| MYSQL_PORT | 3307（容器 `sport-verify-mysql`，MySQL 8.0.46） |
| 中间件 | Nacos v2.3.2 / Redis 7.2-alpine（宿主 6379）/ RocketMQ 5.2.0 namesrv+broker（宿主 9876/10911，brokerIP1=127.0.0.1）；均为本次随栈启动 |
| 服务 | gateway:8080 / user:8081 / record:8082 / verify:8083，`start-services` 退出码 0，8081-8083 健康检查 200 |
| 连接池 | record 侧 `maximumPoolSize: ${MYSQL_POOL_SIZE:10}` = **10**（`sharding.yaml`，本次未注入 30 档） |
| 消费参数 | verify 消费线程 20/20、单批 1、pullInterval 0（库默认） |
| 环境对齐 | 演示库 `verify_db` 缺 `verify_event_outbox`，已按仓库既有 DDL（`sql/03-verify-db.sql` 内同名表）在演示库补建——环境同步，不涉及任何仓库文件改动 |
| 数据规模 | 演示库历史存量：sport_record ≈2.2 万条、track_point 16 分片（历史压测累计），本次 +2010 条 |

## 2. 运行时实测（head-c100，2000 请求全成功）

`bash scripts/perf/run-perf.sh load 100 2000 head`，原始数据在 `docs/perf/data/raw/head-c100-*`（gitignore，不入库）：

| 指标 | 实测 |
| --- | --- |
| QPS | **120.90**（wall 16.542s） |
| 延迟 | P50 **777.55ms** / P95 **1188.55ms** / P99 **1428.57ms** / MAX 1987.60ms |
| 成功/限流/错误 | 2000 / 0（429）/ 0，错误率 0.00%，状态码全 200 |

**校验链路（同一 run 的派生实测，非 quality 口径）**：本次落库 2010 条（含 10 条预热）全部到达终态，
无一条滞留 SUBMITTED/VERIFYING。以 `sport_record.created_at → verification_result.checked_at`
计算「提交 → 判定落库」延迟（n=2010）：

| 指标 | 实测 |
| --- | --- |
| 判定落库延迟 | **P50 15.0s / P95 17.0s / P99 18.0s / MAX 18.0s**（avg 15.2s） |

形态是**排队**而非计算：消费日志显示 2010 条集中在约 35 秒内消费完（≈60 条/s），低于提交峰值
121 条/s，消息在 MQ 侧积压后 20 个消费线程（单批 1 条）逐条排空，P50/P95/P99 挤在 15~18s
的窄带上是积压排空的典型形态。

**outbox 事件投递**：快照时 PENDING 1012 / SENT 998。relay 周期 5s、单轮上限 100 行
（`verify.outbox.batch-size`），即投递上限 20 行/s——判定事件的榜单侧到达延迟以分钟计；
判定回调本身走 Feign 直连，不受此排队影响（这就是记录已全部终态而 outbox 仍积压的原因）。

**R5（mapmatch）**：mapmatch-service 不在 start-services 栈内且 PostGIS 未启动，2010 条全部
走「匹配服务熔断降级」不命中（日志 2010 条 warn，其中仅 9 次 connect 超时，其余熔断打开后快速失败）。
本次判定全部由 R1-R4 产出。

**GC（record-service，`logs/gc-record.log`）**：服务在线约 3.5 分钟内 221 次暂停、合计 960.5ms
（占墙钟 ≈0.5%），单次 1.7~2.3ms，堆峰值 542M→103M(738M)——G1 默认自适应堆，无显著压力。

**MySQL 侧**：`Innodb_row_lock_time` = 0（本次无行锁等待），`Threads_connected` 22，
`Innodb_data_fsyncs` 23827（含历史；本次 2000+ 提交各伴随刷盘）。

## 3. 静态调用表（代码路径点数，与是否压测无关）

### 3.1 提交路径

入口：`gateway-service` `AuthGlobalFilter`（白名单匹配，无 Redis/DB）→ 转发 →
`record-service` `RecordController` → `SportRecordService.submit`
（`record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`，`@Transactional`）：

| # | 步骤（方法） | SQL | Redis | HTTP | MQ | 事务范围 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 幂等前置 `selectByRequestId` | 1 SELECT | | | | 事务内 |
| 2 | 主记录 `sportRecordMapper.insert`（冲突时 +1 SELECT） | 1 INSERT | | | | 事务内 |
| 3 | 轨迹 `insertPointsBatch`（300 点 → ⌈300/500⌉ = 1 条多值 INSERT；ShardingSphere 按 user_id 路由单分片） | 1 INSERT | | | | 事务内 |
| 4 | 状态机 `updateStatus`（乐观锁 SUBMITTED→VERIFYING） | 1 UPDATE | | | | 事务内 |
| 5 | 事件 `RecordEventProducer.publishSubmitted` → `rocketMQTemplate.asyncSend` | | | | 1（异步） | **事务外**（afterCommit 注册） |
| 6 | 降级分支 `fallbackTriggerVerify`：`verifyApi.triggerVerify`（Feign）→ 仍败 `verifyDegradeService.degradeToManualReview` | | | 0（成功路径不触发） | | 事务外（回调线程） |

**合计：SQL 4 条（单事务覆盖）/ Redis 0 / HTTP 0 / MQ 1 次异步**。提交响应不等 MQ Broker ACK。

### 3.2 校验路径

入口：`VerifyEventConsumer.handleMessage`（原生 `DefaultMQPushConsumer`，SUBMITTED Tag）→
`VerifyService.verify`（`verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java`）：

| # | 步骤（方法） | SQL（verify 库） | SQL（record 库） | Redis | HTTP | 事务范围 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 事件去重 `bucket.trySet`（SETNX，24h TTL；失败补 1 次 DEL） | | | 1~2 | | 无 |
| 2 | 结果缓存 `readCachedResult`：Caffeine 命中 0 外部调用；未命中 `verificationResultMapper.selectById` | 0~1 | | | | 无 |
| 3 | 占位 `initVerifying`（INSERT IGNORE） | 1 | | | | 单语句 |
| 4 | 拉记录 `recordApi.getRecord`（Feign） | | 1（selectById） | | 1 | 事务外 |
| 5 | 拉轨迹 `recordApi.listPoints`（Feign；record 侧先解析 user_id 再路由单分片） | | 2（selectById + selectList） | | 1 | 事务外 |
| 6 | 规则集 `RuleVersionService.getActiveRulesForUser` → `RuleCacheService` 二级缓存：Caffeine 命中 0 外部调用；未命中 Redis 1 + DB 1 | 0~1 | | 0~1 | | 无 |
| 7 | R1-R4 规则（`VerifyEngine`，匀速/加速度/停留/折返） | 0 | 0 | 0 | 0 | 纯内存计算 |
| 8 | R5 `R5OffRoadRule.evaluate` → `mapMatchApi.match`（HTTP 到 mapmatch-service） | | | | 1（缺失/故障时熔断降级不命中，本次 2010 次全降级） | 事务外 |
| 9 | 落库 `VerifyOutboxService.persistResultAndEvent`：结果 upsert + outbox PENDING 行 | 2（同事务） | | | | **单事务（仅这 2 条本地写，ADR-0009）** |
| 10 | 回调 `callbackStatus` → `recordApi.statusCallback`（Feign） | | 2（selectById + 乐观锁 UPDATE） | | 1 | 事务外 |
| 11 | 结果写 Caffeine（1min TTL） | 0 | 0 | 0 | 0 | 本地 |
| 12 | 事件投递 `VerifyOutboxRelay.relay`（每 5s）：Redisson `tryLock` + `selectPendingBatch(≤100)` + 每行 `syncSend` + `markSent`/`incrRetry` | 1 + 每行 1 | | 1（锁） | | 无事务包裹（逐行自动提交） |

**合计每条消息：verify 侧 SQL ≤5、record 侧 SQL 5、HTTP 3（+R5 1，降级即 0 次实际出网）、
Redis 1~2、MQ 0（判定事件一律经 relay 异步投递）**。除 `persistResultAndEvent` 的 2 条写外
无本地事务包裹远程调用（ADR-0009 口径）。

## 4. 归因：为什么只选「数据库」

排除法 + 正向证据：

- **远程调用（排除为第一）**：提交路径成功分支同步远程调用为 0（MQ 异步、afterCommit 之后），
  P50 777ms 不可能由远程调用贡献；校验路径的 R5 已熔断快速失败（2010 次降级仅 9 次真实超时），
  3 次内部 HTTP 各自的 handler 时间同样以 SQL 为主。
- **GC（排除）**：全程暂停合计 960.5ms、单次 ≤2.3ms、占墙钟 ≈0.5%，与 P50 777ms 差两个数量级。
- **锁（排除）**：DB 行锁累计 0ms（各请求写不同行）；Redisson 锁只在 relay 单线程每 5s 一次；
  无 synchronized 竞争路径。连接池等待在本文口径下计入「数据库」段（等待的是 DB 访问资源，
  与 SQL 执行、提交刷盘同属 DB 足迹），不归「锁」。
- **业务规则（排除）**：R1-R4 为纯内存微秒级计算，且全部发生在校验路径（异步），不在提交延迟上。
- **CPU（排除为第一）**：300 点 JSON 序列化/解析与 ShardingSphere 对多值 INSERT 的解析是
  真实存在的 CPU 项，但没有独立证据表明其份额超过 DB 段；它计入下方 T_tx 的构成而非独立主因
  （见 §5 假设，属下一步要插桩量清的对象，本次不下结论）。
- **数据库（正向证据）**：
  1. 提交路径的同步阻塞 100% 由 DB 段构成：事务内 4 条 SQL + 提交刷盘 + 连接池排队；
  2. 排队模型自洽：池 10、并发 100，实测 P50 777.5ms ≈ 等待约 9 个前序事务 × T_tx + 自身 T_tx，
     反推 T_tx ≈ 80ms（4 条 SQL 的执行 + ShardingSphere 解析 + 提交刷盘）；
  3. 校验路径 15s 级延迟的根因是每条消息 ~300ms 的同步链跟不上 121 条/s 的到达率，而该链上
     每一跳（含 3 次 HTTP 的 handler）都在执行 SQL——DB 是吞吐上限的共同底座。

**top_class = 数据库**（六个候选中唯一选中；「一次只改一类」约束下的下一步对象即此类）。

> TASK-140 订正（2026-09-25）：§4 的「池 10 排队 / T_tx ≈80ms」为排队模型**推断**口径；
> 请求级物理连接获取等待已于 TASK-140 直接测得（同负载 connWait P50 527.1ms，内层池
> 获取点计时），提交段应表述为 `beforeCommit→afterCommit` 区间而非 fsync 单项。历史
> 原始数据不改动，详见 `docs/perf/复测-db-wait-evidence.md`。

## 5. 下一步假设（一句话，本任务不实施）

若下一步只在「数据库」一类内动手：先对提交事务插桩量出 4 条 SQL 各自耗时、ShardingSphere
多值 INSERT 解析份额与提交刷盘份额，再缩短单请求 DB 足迹（事务长度/连接占用），预期 P50 随
连接池排队倍数同步下降——本任务不实施、不改任何代码与参数。

## 6. 旧报告数字（全部来自旧环境，不得当作本 HEAD 实测）

`docs/perf/压测报告.md`（执行日期 2026-09-12）中的以下数字均来自**旧环境**，且该环境的优化
项（轨迹批量插入、连接池扩至 30、组合索引、`-Xms1g -Xmx1g`）在本次 HEAD 快照中除批量插入外
**均未启用**（池为默认 10、JVM 为默认 G1 自适应堆），与本文 §1/§2 数字**不可直接比较**：

| 旧报告数字 | 旧环境口径 |
| --- | --- |
| 35.5 → 136.8 QPS（3.9×） | 旧环境优化两轮后的提交吞吐 |
| P95 3.76s → 1.60s | 旧环境优化两轮后的提交 P95 |
| 541ms | 旧环境校验链路端到端 P95 的突发尾部分（后经消费调度调优收敛） |
| 28~63ms | 旧环境调优后校验链路稳态 P95 波动带 |
| 端到端 P50 20.3ms | 旧环境 200 条验收集口径 |

本 HEAD 实测（120.9 QPS / P50 777.5ms / P95 1188.6ms）是**当前环境**（池 10、默认 JVM、
mapmatch 缺席）的独立快照，本文不做新旧优劣结论。

## 7. 未覆盖项（如实记账，不编造）

| 项 | 状态 | 原因 |
| --- | --- | --- |
| quality 验收集（拦截率/通过率/校验 P95） | **未覆盖** | start-services 栈不含 mapmatch-service、PostGIS 未启动，R5 必然全程降级，验收集的拦截率/通过率失真；TASK-138 spec 明确「mapmatch 缺失则本项未覆盖」。未执行、未编造 |
| 校验 P95（quality 100ms 轮询口径） | 未覆盖 | 同上；本文以「提交→判定落库」延迟（§2）作替代性运行时证据 |
| 500/1000 并发档 | 未覆盖 | 任务禁止改档 |
| MQ 故障注入/熔断转人工演练 | 未覆盖 | 非本任务范围 |

---

*产物：`docs/perf/data/attr-head-summary.json`（机器可读摘要）；原始压测数据 `docs/perf/data/raw/`（不入库）。
本任务未修改任何业务 Java、SQL、服务 yml/properties、Mapper、过滤器，未调 JVM，未加索引，未改连接池。*
