# TASK-171 spec：分块标记落地态之上的批内并发（N=2）稳态判别与落地

## 1. 唯一问题
在 TASK-169 确证分块批量标记收益显著并默认落地（`relay-batch-mark-enabled: true`，默认 chunk 25）、且 TASK-170 将单行投递逻辑完全收敛至单一 `sendRow` 的背景下，单行标记耗时已由 16.65 ms 降至 1.50 ms（缩短 91.0%），锁内单行耗时由 19.40 ms 降至 3.98 ms。
此时，逐行网络 RPC 投递 `syncSend`（~2.4 ms/行）成为锁内耗时的绝对瓶颈（占 60% 左右）。
历史 TASK-161 / TASK-164 探索批内并发（`relay-send-concurrency: 4`）时，由于并发运行在单行逐行 `markSent` 模式下，各 worker 严重争用数据库连接与行持久化锁，导致线程耗时膨胀 2.5 倍，并发收益被吞噬（改善比仅 1.22~1.36，未达 1.5 门槛）。
现在，随着分块批量标记将数据库写操作骤降 96%，在消除数据库写串行化瓶颈的前提下，通过 **3 对交错轮次（A1 → B1 → A2 → B2 → A3 → B3）**，验证开启**批内适度并发 N=2**（`relay-send-concurrency: 2`）是否能在真实负载下将网络 RPC 耗时有效并行化，使单行锁内墙钟 `T_proc = lockProcessingMs / rows`（ms/行）在 3.98 ms 基础上稳定再改善 **≥ 1.5 倍**（即降至 ≤ 2.65 ms/行，锁内行吞吐突破 375~500 行/s），且满足抗漂移排序控制门与资源语义门？

三支裁决：落地支（条件式落地 `application.yml` 并补齐绑定测试类与确认轮 C） / 未定支 / 反证支（定义见 §7）。
**硬性红线**：只判别批内并发度这一项开关（A 臂 N=1 vs B 臂 N=2），chunk-size 保持 25，batch-size 保持 100，interval 保持 500 ms，**全面采用纳秒级批次诊断日志，彻底禁用粗粒度排空采样器（不采 drain.csv、不估端点斜率）**。

## 2. 两臂定义（两臂均带同一诊断开关，消除诊断自身开销的对比偏差）
- **A 臂（当前生产落地态基线 / 串行分块态）**：
  `--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=true --verify.outbox.relay-batch-mark-chunk-size=25 --verify.outbox.relay-send-concurrency=1`
  （保持 chunk-size=25，interval=500；显式注入保证语义完全对称）
- **B 臂（候选态 / 并发分块标记态，N=2）**：
  `--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=true --verify.outbox.relay-batch-mark-chunk-size=25 --verify.outbox.relay-send-concurrency=2`
  （保持 chunk-size=25，interval=500）

每臂切换后必须规范重启 verify-service（同一 jar，A/B 全程不得换 jar），启动前 `export MYSQL_PORT=3307`，显式指定 `JAVA_BIN`，unset 代理并设 `no_proxy="*"`。

## 3. 轮次编排与预算
- 交错次序：`A1 → B1 → A2 → B2 → A3 → B3`（6 个计数轮）。
- 预热轮 ≤ 2，全部丢弃但留档：起栈首轮 W0（A 臂，100x2000）＋ 首次切 B 臂首轮 W1（B 臂，100x200 小样本）。
- 计数轮负载统一 100x2000（`run-perf.sh load 100 2000 task171-<label>`），要求 `ok=2000 / errors=0 / limited429=0`。
- 替换轮 ≤ 1，且仅当某轮 M4 语义或健康红时可用同臂同参数替换；失败轮照占预算，不许为凑结论加跑。预算耗尽 ⇒ 未定支。

## 4. 指标与估计量（全面采用纳秒批次诊断口径）
- 数据源：各轮 `docs/perf/data/raw/task171-<label>-verify.log` 中的批次诊断行：
  `outbox relay 诊断（批次）：rows=... lockProcessingMs=... lockHoldMs=... markMs=... sendMs=...`
- 提取该轮负载所触发的所有非空批次，汇总计算：
  - `total_rows = sum(rows)`（该轮处理总行数）
  - `total_lockProcessingMs = sum(lockProcessingMs)`（该轮锁内处理总墙钟）
  - `total_sendMs = sum(sendMs)`（该轮 MQ 发送总耗时）
  - `total_markMs = sum(markMs)`（该轮标记操作总耗时）
  - `T_proc = total_lockProcessingMs / total_rows`（单行锁内墙钟，ms/行，**核心判别指标**）
  - `R_lock = total_rows / (total_lockProcessingMs / 1000)`（锁内行吞吐，行/s，辅助指标）
- 每轮同时归档：`*-diaglines.txt`、`*-summary.json`、`*-stats.txt`、`*-hikari-peak.txt`、宿主共变量、四服务 health 状态。

## 5. 门禁定义（预注册，不得放宽；任一不符按 §7 归支）
- **M0 环境门**（起栈前一次）：Docker Desktop 运行，5 个演示容器 healthy；数据库连接余量充足（`max_connections - Threads_connected ≥ 30`）。
- **M1 机制门**（逐轮硬门）：
  - A 臂：诊断日志中输出 `sendConcurrency=1`，且无并发聚合注记；批量标记日志正常输出，`markBatchCalls > 0` 且 `markBatchRows = total_rows`；逐行投递日志 ZERO_HIT。
  - B 臂：诊断日志中输出 `sendConcurrency=2`，且明确输出并发聚合注记（`为各线程墙钟的聚合和`）；批量标记日志正常输出，`markBatchCalls > 0` 且 `markBatchRows = total_rows`；逐行投递日志 ZERO_HIT。
- **M2 效应门**（核心判别门）：
  - 全轮次均值改善比：`M2_mean = mean(T_proc(A1,A2,A3)) / mean(T_proc(B1,B2,B3)) >= 1.5`。
  - 且 3 对交错对中至少 2 对满足：`M2_i = T_proc(A_i) / T_proc(B_i) >= 1.5`（即单行锁内墙钟缩短 ≥33.3%，锁内吞吐翻 ≥1.5×）。
- **M3 排序控制门（抗漂移综合判定）**：
  - 满足以下任意一条即算排序控制门通过：
    ① A 对照臂 3 轮相对标准差 `RSD(A) = std(T_proc(A)) / mean(T_proc(A)) <= 20%`；
    ② A 臂 3 轮中存在任意两轮满足 `|T_proc(A_j) - T_proc(A_k)| / mean(T_proc(A_j), T_proc(A_k)) <= 20%`（即存在平稳基准对），且该平稳对下对应 B 轮改善比均 ≥ 1.5。
  - B 候选臂 3 轮相对标准差 `RSD(B)` 照实记录，要求 ≤ 25%。
- **M4 健康与语义门**（逐轮硬门）：
  - `ok=2000 / errors=0 / limited429=0`。
  - 该轮 outbox 行数 = SENT 增量 = 2010（含预热/历史隔离），最终 PENDING=0。
  - `retry_count>0` 行数=0；耗尽行增量=0；`uk_event_id` 零重复；零 `RECONSUME_LATER`；relay 失败/耗尽日志零行；零锁异常行；零批量标记降级告警（`affected < count` 为 0）；零 worker 单行异常隔离告警。
- **M5 代价门**：
  - `Com_select` 增量比 B/A 处于 `[0.8, 1.2]` 范围内。
  - `Com_update` 增量比 B/A 处于 `[0.8, 1.2]` 范围内（两臂均为 chunk-size 25 分块标记）。
- **M6 资源门**（逐轮硬门）：
  - `hikaricp_connections_timeout_total` 增量全 0；Hikari `_pending` 与 `_active` 峰值照实披露；消费侧无积压饿死。

## 6. 条件式落地（仅落地支）与确认轮 C
- **落地动作**：在 `verify-service/src/main/resources/application.yml` 的 `verify.outbox:` 块下纯新增配置（不得改动任何既有行）：
  ```yaml
    # outbox relay 批内并发投递默认开启为 2（TASK-171 落地，详见 docs/perf/判别-outbox-relay-并发分块标记吞吐.md）：
    # 实测分块标记下 N=2 时逐行 syncSend 网络 RPC 并行化，锁内单行耗时稳定再降低 ≥ 33.3%，吞吐翻 ≥ 1.5 倍，过 M1~M6 各门。
    relay-send-concurrency: 2
  ```
  落地前备份原文件，`git diff --stat` 必须纯新增（numstat N/0），`^spring:` 与 `^verify:` 根键各自仍恰好 1 个，总行数 200 → 200+N。
- **新增纯 JUnit 5 绑定测试类**：
  放于 `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelaySendConcurrencyDefaultTest.java`（照既有 `VerifyOutboxRelayBatchMarkDefaultTest` 模板，零容器依赖）：
  ① 用 `YamlPropertySourceLoader` 断言 `verify.outbox.relay-send-concurrency` 为 `2`；
  ② 反射断言 `VerifyOutboxRelay` 的 `@Value` 字面默认值仍为 `${verify.outbox.relay-send-concurrency:1}`（证明生产代码未改默认值）；
  ③ 断言根键各恰 1 个；
  ④ verify-service offline 测试数 140 → 140+3 = **143** 全绿。
- **确认轮 C**：用 `--mode=offline package` 重建 jar，无任何命令行注入裸起 verify-service，跑 100x2000；必须满足：`sendConcurrency=2`、`T_proc` 与 B 臂一致（比值对 A 均值 ≥1.5）、M4/M5/M6 全过。
- 确认轮失败 ⇒ 按备份字节回滚 `application.yml`、删除新测试类、复跑 offline 证明测试数回到 140，台账写「落地失败已回滚」，裁决改判未定支。

## 7. 三支裁决
- **落地支**：6 个计数轮全部有效（逐轮 M1/M4/M5/M6 过）＋ M2 效应门通过 ＋ M3 抗漂移排序控制门通过 ＋ 确认轮 C 全过。
- **反证支**：M2_mean ≤ 1.0，或出现可归因于并发分块标记的 M4/M6 红 ⇒ 记录反证与最小反例，不落地。
- **未定支**：其余一切情形（有效轮不足、预算耗尽、M2 或 M3 未过、确认轮失败）⇒ 只报实测读数，不凑结论、不外推。

## 8. 开工读数（指导侧亲跑基线；任一不符停手回报原文）
- HEAD = `898db2b0c4b92e27e4fcf7eaffa752ee88352d76`
- origin/main = `964871c17b7b9d707fa3a2b550b61a181a764db3`
- `git rev-list --left-right --count origin/main...main` = `0 1`
- 工作树脏项仅 `?? spec/changes/add-verify-degrade-status-index/`（既有，零触碰）＋ 本任务目录
- 全量 offline 测试：`bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0，七模块 `36/41/33/103/140/59/10`，Skipped 全 0
- 静态门：`--mode=offline --static=verify-service` → rc=1，Checkstyle violations 严格为 `862`（门槛 ≤862）
- 契约门：在途无参 rc=1（预期），`--open TASK-171 --baseline=898db2b0c4b92e27e4fcf7eaffa752ee88352d76` rc=0；收口后无参必须 rc=0
- `VerifyOutboxRelay.java`：762 行（wc -l）；L114 `relay-batch-mark-enabled:false`、L123 `relay-batch-mark-chunk-size:25`、L189 `sendRow`、L331 `sendRow` 主方法、L410 `sendRow` 重载、L479 `sendRow`、L502 `sendRow`、L519 `flushBatchMark`、L632 `sendRow`
- `application.yml`：200 行，CR=6（工作树视角）/ CR=0（blob 视角），`relay-batch-mark-enabled: true`
- 主规格 `spec/specs/sport-record-verify/spec.md`：3568 行 / 161 个 `### Requirement:`，头部清单 58，归档 60，在途 7
- `work/mailbox/PLAN.md`：1457 行，CR=0
- 词面门：从 `.github/workflows/ci.yml` 现场提取正则（PAT_LEN=26），四形态全 ZERO_HIT rc=1
- 22 个受保护 tokens 基线（在 PLAN.md 中行命中数）：
  `13.4=15`、`18.0=17`、`73.93=16`、`68.8=12`、`6315=13`、`1.8612=12`、`3.3066=12`、`5.7056=12`、`9.408=12`、`36525962432=12`、`36586847965=11`、`36438897772=12`、`36399582548=11`、`36098038547=11`、`2806=18`、`598=11`、`36736221648=10`、`36808102571=5`、`36821040708=3`、`36845152965=2`、`36871294588=2`、`36880083885=3`

## 9. 只改清单（超出即红）
- 允许改动与新增路径：
  - `work/mailbox/tasks/TASK-171/spec.md`（本任务书）
  - `work/mailbox/tasks/TASK-171/handoff.md`（交付报告）
  - 条件式落地（仅落地支）：
    - `verify-service/src/main/resources/application.yml`
    - `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelaySendConcurrencyDefaultTest.java`
    - `docs/perf/判别-outbox-relay-并发分块标记吞吐.md`
    - `docs/perf/data/exp-outbox-relay-concurrency-batch-mark.json`
  - `work/mailbox/PLAN.md`（登记验收记录）
