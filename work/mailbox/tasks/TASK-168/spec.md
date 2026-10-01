# TASK-168 spec：有界分块标记 SENT 对 outbox relay 锁内吞吐的判别（条件式落地）

## 1. 唯一问题
在 TASK-163 已落地的 `verify.outbox.relay-interval-ms=500` 与串行基线 `relay-send-concurrency=1` 生产态之上，把有界分块标记开关 `verify.outbox.relay-batch-mark-enabled` 由 `false` 改为 `true`（采用默认分块大小 `relay-batch-mark-chunk-size=25`），在 100x2000 满载下，**诊断口径的单行锁内墙钟 `T_proc = lockProcessingMs / rows`（ms/行）** 相对落地态基线改善是否 ≥1.5（即 `T_proc(A) / T_proc(B) >= 1.5`，等价于锁内行吞吐提升 ≥1.5×）？三支裁决：落地支 / 未定支 / 反证支（定义见 §7）。
**硬性红线**：只判别这一项开关，不叠加并发（concurrency 保持 1）、不叠加 batch-size、不换档 interval、**彻底禁用排空采样器（不采 drain.csv、不估端点斜率，全面改用纳秒级批次诊断日志）**。

## 2. 两臂定义（唯一差异＝注入参数；两臂均带同一诊断开关，消除诊断自身开销的对比偏差）
- **A 臂（落地态基线 / 关闭态）**：
  `--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=false`
  （保持 chunk-size=25，N=1，interval=500；显式注入保证语义完全对称）
- **B 臂（候选态 / 分块标记开启态）**：
  `--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=true --verify.outbox.relay-batch-mark-chunk-size=25`
  （保持 N=1，interval=500）

每臂切换后必须规范重启 verify-service（同一 jar，A/B 全程不得换 jar），启动前 `export MYSQL_PORT=3307`，显式指定 `JAVA_BIN`，unset 代理并设 `no_proxy="*"`。

## 3. 轮次编排与预算
- 交错次序：`A1 → B1 → A2 → B2`（4 个计数轮）。
- 预热轮 ≤2，全部丢弃但留档：起栈首轮 W0（A 臂，100x2000）＋ 每次切臂首轮 W1（B 臂，100x200 小样本）。
- 计数轮负载统一 100x2000（`run-perf.sh load 100 2000 task168-<label>`），要求 `ok=2000 / errors=0 / limited429=0`。
- 替换轮 ≤1，且仅当某轮 M4 语义或健康红时可用同臂同参数替换；失败轮照占预算，不许为凑结论加跑。预算耗尽 ⇒ 未定支。

## 4. 指标与估计量（全面改用纳秒批次诊断口径，彻底弃用 ~2s 粗粒度排空采样器）
- 数据源：各轮 `docs/perf/data/raw/task168-<label>-verify.log` 中的批次诊断行：
  `outbox relay 诊断（批次）：rows=... lockProcessingMs=... lockHoldMs=... markMs=... sendMs=...`
- 提取该轮负载所触发的所有非空批次（过滤丢弃无数据的空轮），汇总计算：
  - `total_rows = sum(rows)`（该轮处理总行数）
  - `total_lockProcessingMs = sum(lockProcessingMs)`（该轮锁内处理总墙钟）
  - `total_markMs = sum(markMs)`（该轮标记操作总耗时）
  - `total_sendMs = sum(sendMs)`（该轮 MQ 发送总耗时）
  - `T_proc = total_lockProcessingMs / total_rows`（单行锁内墙钟，ms/行，**核心判别指标**）
  - `T_mark = total_markMs / total_rows`（单行标记耗时，ms/行，机制确证量化）
  - `R_lock = total_rows / (total_lockProcessingMs / 1000)`（锁内行吞吐，行/s，辅助指标）
- 诊断指标源自纳秒时钟在占锁期间的高精度累加，完全消除排空采样器 2s 粗采样、sleep 抖动与网络轮询误差带来的约 2× 跨会话方差。
- 每轮同时归档：`*-diaglines.txt`（过滤提取的诊断行）、`*-summary.json`（LoadTest 结果）、`*-stats.txt`（MySQL Com 前后值）、`*-hikari-peak.txt`（连接池峰值）、宿主共变量（非 sports 的 `java.exe` 进程数 + CPU 采样）、四服务 health 状态。

## 5. 门（预注册，不得放宽；任一不符按 §7 归支）
- **M0 环境门**（起栈前一次）：Docker Desktop 运行，5 个演示容器 healthy；数据库连接余量充足（`max_connections - Threads_connected ≥ 30`）。
- **M1 机制门**（逐轮硬门）：
  - A 臂：`verify.log` 严禁出现 `outbox 事件分块批量标记成功`，且诊断日志中不得出现 `markBatchCalls`（或为 0）；逐行投递日志正常输出。
  - B 臂：必须出现 `outbox 事件分块批量标记成功：rows=`，诊断日志中必须出现 `markBatchCalls > 0` 且 `markBatchRows = total_rows`；逐行投递日志被 chunk 日志取代（逐行投递日志 ZERO_HIT）。
- **M2 效应门**（核心判别门）：
  - `M2_1 = T_proc(A1) / T_proc(B1) >= 1.5` 且 `M2_2 = T_proc(A2) / T_proc(B2) >= 1.5`（即单行锁内墙钟缩短 ≥33.3%，锁内行吞吐提升 ≥1.5×）。
  - 理论预期区间：根据 TASK-164 串行臂基线，`markMs/row` 占 72~77%（约 7.8~10.0 ms），分块后 25 行合并单条 UPDATE，`markMs/row` 预期降至 0.5~1.5 ms，单行锁内墙钟 `T_proc` 预期由 10.5~13.0 ms 降至 3.5~5.5 ms，改善比 `M2` 预计在 2.0~3.0 区间。
- **M3 排序控制门**：
  - `|T_proc(A2) - T_proc(A1)| / mean(T_proc(A1), T_proc(A2)) <= 20%`（A 对照臂偏差 ≤ 20%）。
  - B 候选臂两轮偏差同样记录，要求 ≤ 25%。
- **M4 健康与语义门**（逐轮硬门）：
  - `ok=2000 / errors=0 / limited429=0`。
  - 该轮 outbox 行数 = SENT 增量 = 2010（含预热/历史隔离），最终 PENDING=0。
  - `retry_count>0` 行数=0；耗尽行增量=0；`uk_event_id` 零重复；零 `RECONSUME_LATER`；relay 失败/耗尽日志零行；零锁异常行；零批量标记降级告警（`affected < count` 为 0）。
- **M5 代价门**：
  - `Com_select` 增量比 B/A 处于 `[0.8, 1.2]` 范围内（轮次 tick 数未变）。
  - `Com_update` 增量验证：A 臂每行单行写，增量恒为 4020（2×2010）；B 臂由于 25 行分块，批量 UPDATE 增量约为 2010/25 ≈ 81 次，总增量预期在 2010 + 81 ≈ 2091 左右（要求 B/A 增量比 ≤ 0.65，显著降低数据库写负载）。
- **M6 资源门**（逐轮硬门）：
  - `hikaricp_connections_timeout_total` 增量全 0；Hikari `_pending` 与 `_active` 峰值照实披露；消费侧无积压饿死。

## 6. 条件式落地（仅落地支）与确认轮 C
- **落地动作**：在 `verify-service/src/main/resources/application.yml` 的 `verify.outbox:` 块下纯新增配置（不得改动任何既有行）：
  ```yaml
    # outbox relay 分块标记 SENT 默认开启（TASK-168 落地，详见 docs/perf/判别-outbox-relay-分块标记吞吐.md）：
    # 实测 chunk-size 25 下锁内墙钟缩短 ≥ 1.5 倍（markSent/row 显著降低），且过 M1~M6 各门。
    relay-batch-mark-enabled: true
  ```
  落地前备份原文件，`git diff --stat` 必须纯新增（numstat N/0），`^spring:` 与 `^verify:` 根键各自仍恰好 1 个，总行数 196 → 196+N。
- **新增纯 JUnit 5 绑定测试类**：
  放于 `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkDefaultTest.java`（照既有 `VerifyOutboxRelayIntervalDefaultTest` 模板，零容器依赖）：
  ① 用 `YamlPropertySourceLoader` 断言 `verify.outbox.relay-batch-mark-enabled` 为 `true`；
  ② 反射断言 `VerifyOutboxRelay` 的 `@Value` 字面默认值仍为 `${verify.outbox.relay-batch-mark-enabled:false}`（证明生产代码未改默认值）；
  ③ 断言根键各恰 1 个；
  ④ verify-service offline 测试数 137 → 137+3 = **140** 全绿。
- **确认轮 C**：用 `--mode=offline package` 重建 jar（记录 jar sha256），无任何命令行注入裸起 verify-service，跑 100x2000；必须满足：`markBatchCalls > 0`、`T_proc` 与 B 臂一致（比值对 A 均值 ≥1.5）、M4/M5/M6 全过。
- 确认轮失败 ⇒ 按备份字节回滚 `application.yml`（`cmp` rc=0）、删除新测试类、复跑 offline 证明测试数回到 137，台账写「落地失败已回滚」，裁决改判未定支。

## 7. 三支裁决
- **落地支**：4 个计数轮全部有效（逐轮 M1/M4/M5/M6 过）＋ M2 两比值均 ≥1.5 ＋ M3 控制门 ≤20% ＋ 确认轮 C 全过。
- **反证支**：M2 两比值均 ≤1.0，或出现可归因于分块标记的 M4/M6 红 ⇒ 记录反证与最小反例，不落地，并写明「分块标记未带来吞吐收益或打破可靠语义」。
- **未定支**：其余一切情形（有效轮不足、预算耗尽、1.0 < M2 < 1.5、排序漂移、确认轮失败）⇒ 只报实测读数，不凑结论、不外推。

## 8. 开工读数（指导侧亲跑值；逐项一致后才许动文件，任一不符 ⇒ 停手回报原文，不要自行解释）
- HEAD = `b5e6f850e999c926b4b63eb78a0e6dfb64d966c3`；`origin/main` = `cd6734e07812a147d170ddfbe6bdf70dfcf61e2e`；`git rev-list --left-right --count origin/main...main` = `0 1`。
- 工作树脏项仅 `?? spec/changes/add-verify-degrade-status-index/`（既有，零触碰）＋ 本任务目录。
- `bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0，七模块 **36/41/33/103/137/59/10**，Skipped 全 0。
- `--mode=offline --static=verify-service` → rc=1，Checkstyle violations 严格为 **867**（门槛 ≤867）。
- `verify-service/src/main/resources/application.yml`：**196 行**、CR=0、`^verify:` 根键 1 个、`^spring:` 根键 1 个、`relay-interval-ms: 500` 在 L123；全文件 `relay-batch-mark` 命中 **0**。
- `VerifyOutboxRelay.java`：786 行；L68 `batch-size:100`、L72 `max-retry:16`、L79 `relay-diagnostics-enabled:false`、L96 `relay-send-concurrency:1`、L105 `relay-batch-mark-enabled:false`、L114 `relay-batch-mark-chunk-size:25`、L126 `@Scheduled` 默认 5000/10000。
- 主规格 `spec/specs/sport-record-verify/spec.md`：**3568 行 / 161 个 `### Requirement:`**，零触碰。
- 头部提案清单 **58**，归档目录 **60**，在途目录 **7**。
- `work/mailbox/PLAN.md`：**1388 行**、CR=0（纯追加，既有行含 L4 零改动）。
- 词面门：正则从 `.github/workflows/ci.yml` 现场提取，四形态（原样 / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）全 **ZERO_HIT rc=1**；正向对照探针 rc=0。
- 契约门：本任务 spec.md 落盘后在途无参 rc=1（预期），`--open TASK-168 --baseline=b5e6f850e999c926b4b63eb78a0e6dfb64d966c3` rc=0；收口后无参必须 rc=0。
- PLAN.md 19 个受保护数字 token 的新基线计数（本任务结束时不得减少）：
  `13.4=13`、`18.0=15`、`73.93=14`、`68.8=10`、`6315=11`、`1.8612=10`、`3.3066=10`、`5.7056=10`、`9.408=10`、`36525962432=10`、`36586847965=9`、`36438897772=10`、`36399582548=9`、`36098038547=9`、`2806=16`、`598=9`、`36736221648=8`、`36808102571=3`、`36821040708=1`。

## 9. 只改清单（超出即红）
- 允许改动与新增路径：
  - `work/mailbox/tasks/TASK-168/spec.md`（本任务书）
  - `work/mailbox/tasks/TASK-168/handoff.md`（交付物台账）
  - `work/mailbox/PLAN.md`（纯追加验收记录）
  - `docs/perf/判别-outbox-relay-分块标记吞吐.md`（性能判别报告）
  - `docs/perf/data/exp-outbox-relay-batch-mark.json`（判别数据机器摘要）
  - `docs/perf/data/raw/task168-*`（原始证据文件）
  - （仅当落地支时允许）：`verify-service/src/main/resources/application.yml`（纯新增配置项）
  - （仅当落地支时允许）：`verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkDefaultTest.java`（新增绑定测试）
- 严禁触碰任何生产 Java 代码、SQL DDL/DML、Mapper XML、现有测试用例、`pom.xml` 或任何已有脚本文件。
- 严禁触碰既有脏项 `spec/changes/add-verify-degrade-status-index/`。
