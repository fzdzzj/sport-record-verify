# 判别：批内并发 N=2 对分块标记落地态 outbox relay 锁内吞吐（TASK-171 三对交错复判 → 落地支，已落地）

> 判别问题（任务书 §1）：在 TASK-169 已落地的分块批量标记（`relay-batch-mark-enabled: true`、chunk 25，单行标记耗时已由 16.65 ms 降至 1.50 ms）与串行投递 `relay-send-concurrency=1` 生产态之上，把 `verify.outbox.relay-send-concurrency` 由 `1` 改 `2`，诊断口径单行锁内墙钟 `T_proc = lockProcessingMs / rows`（ms/行）相对落地态基线的改善是否**稳定 ≥1.5**（即单行锁内墙钟 ≤ 2.65 ms/行），并在宿主 CPU 竞争下满足 M3 抗漂移排序控制门？
> 方法论：彻底弃用 ~2s 粗粒度排空采样器，全程改用纳秒级**批次诊断日志**（`relay-diagnostics-enabled=true`，两臂同开以消除诊断自身开销偏差）；扩展为 **3 对交错轮次 A1→B1→A2→B2→A3→B3**（各 100×2000）以平抑宿主抖动；预热轮 W0/W1 留档丢弃。
> 历史对照：TASK-161 / TASK-164 曾在**单行逐行 `markSent`** 模式下探索 `relay-send-concurrency: 4`，各 worker 争用数据库连接与行持久化锁，线程耗时膨胀 2.5 倍，改善比仅 1.22~1.36 未达 1.5。本轮前提是分块标记已把数据库写操作骤降（`Com_update` 语句数已降为 1/2），故在消除 DB 写串行化瓶颈后单独判别网络 RPC `syncSend`（~2.4 ms/行）能否被 N=2 有效并行化。

## 1. 一句话裁决

**归预注册「落地支（GO）」并已完成条件式落地**：6 个计数轮逐轮 M1/M4/M5/M6 全过；M2 效应门通过（`M2_mean = 2.1274 ≥ 1.5`，且 3/3 对 ≥1.5）；M3 抗漂移排序控制门通过（`RSD(A) = 33.14% > 20%` 但 clause ② 平稳基准对 A1A2 成立，`RSD(B) = 4.35% ≤ 25%`）；确认轮 C 全过。落地件：`application.yml` 纯新增 `relay-send-concurrency: 2`（numstat `4 0`、根键 `^verify:`/`^spring:` 各 1、200→204 行），新增纯 JUnit 5 绑定测试类 `VerifyOutboxRelaySendConcurrencyDefaultTest`（3 用例），verify-service offline 测试数 140 → **143 全绿**。

## 2. 两臂与轮次（唯一差异＝注入参数）

- **A 臂（当前生产落地态基线 / 串行分块态，N=1）**：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=true --verify.outbox.relay-batch-mark-chunk-size=25 --verify.outbox.relay-send-concurrency=1`（batch-size=100、interval=500 不变）。
- **B 臂（候选态 / 并发分块标记态，N=2）**：同上但 `--verify.outbox.relay-send-concurrency=2`。
- 两臂同一 jar（sha256 `556E75658655AA402BDB15902925D8A2754AE9E3F9743D019C69B41BA8B024FA`），A/B 全程不换 jar。
- 轮次：**W0**（A，100×2000，丢弃留档）＋ **W1**（B，100×200，丢弃留档）；计数 **A1 → B1 → A2 → B2 → A3 → B3**（各 100×2000）。
- 每轮负载由 `run-perf.sh load 100 2000 task171-<label>` 驱动（内含 10 条预热请求 ⇒ 每轮 cohort = 2010 行）。
- 每臂切换均规范重启 verify-service（同 jar）；启动前 `export MYSQL_PORT=3307`、显式 `JAVA_BIN`、unset 代理并设 `no_proxy="*"`。

## 3. 核心读数（批次诊断口径，逐轮汇总）

数据源：`docs/perf/data/raw/task171-<label>-verify.log` 经**行偏移切分**（`mark_before` 之后为纯计数轮贡献，剔出同进程预热贡献）后的批次诊断行 `outbox relay 诊断（批次）：rows=… lockProcessingMs=… lockHoldMs=… markMs=… sendMs=… residualMs=… sendConcurrency=…`。
**并发口径注记**：B 臂 `sendConcurrency=2`，诊断行 `markMs / sendMs / incrRetryMs` 为「各线程墙钟的聚合和（线程时间）」，`residualMs` 可能为负；`lockProcessingMs` 仍为锁内墙钟，`T_proc = ΣlockProcessingMs / Σrows` 可直接跨臂比较。

| 轮 | 臂 | 批次 | rows | ΣlockProcessingMs | ΣmarkMs | ΣsendMs | **T_proc(ms/行)** | T_mark(ms/行) | R_lock(行/s) | markBatchCalls | markBatchRows |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | A | 35 | 2010 | 7067 | 3052 | 3242 | **3.5159** | 1.5184 | 284.42 | 92 | 2010 |
| B1 | B | 35 | 2010 | 3944 | 3505 | 3283 | **1.9622** | 1.7438 | 509.63 | 108 | 2010 |
| A2 | A | 34 | 2010 | 6127 | 2758 | 2993 | **3.0483** | 1.3721 | 328.06 | 91 | 2010 |
| B2 | B | 35 | 2010 | 3887 | 3420 | 3247 | **1.9338** | 1.7015 | 517.11 | 108 | 2010 |
| A3 | A | 37 | 2010 | 12582 | 8107 | 3005 | **6.2597** | 4.0333 | 159.75 | 93 | 2010 |
| B3 | B | 35 | 2010 | 4285 | 4057 | 3265 | **2.1318** | 2.0184 | 469.08 | 106 | 2010 |
| W0（丢弃） | A | 39 | 2010 | 9495 | 3878 | 4874 | 4.7239 | 1.9294 | 211.69 | 95 | 2010 |
| W1（丢弃） | B | 4 | 210 | 535 | 272 | 597 | 2.5476 | 1.2952 | 392.52 | 11 | 210 |
| C（裸起） | C | — | — | — | — | — | — | — | — | 见 §5 | — |

**M2 效应门（核心判别门）**
```
M2_mean = mean(T_proc A)/mean(T_proc B) = 4.274627/2.009287 = 2.1274  (>=1.5) PASS
M2_1 = T_proc(A1)/T_proc(B1) = 3.515920/1.962189 = 1.7918  (>=1.5) PASS
M2_2 = T_proc(A2)/T_proc(B2) = 3.048259/1.933831 = 1.5763  (>=1.5) PASS
M2_3 = T_proc(A3)/T_proc(B3) = 6.259701/2.131841 = 2.9363  (>=1.5) PASS
```
**M3 排序控制门（抗漂移综合判定）**
```
RSD(A) = std(T_proc A)/mean(T_proc A) = 33.14%  (clause ①: <=20%) FAIL（A3 单轮受宿主抖动上抬）
RSD(B) = 4.35%  (记录, 门槛 <=25%) PASS
A 平稳对：|T_proc(A1)-T_proc(A2)|/mean = 14.25% <= 20%（其余 |A1-A3|=56.14%、|A2-A3|=69.00% 均超限）
  ⇒ clause ② 平稳基准对 A1A2 成立，且该对 B1/B2 改善比 1.7918 / 1.5763 均 >=1.5
M3 overall（clause ① 或 ②）=> PASS
反证支检查（三个改善比全 <=1.0）=> NO
```
**机制确证量化**：锁内行吞吐 `R_lock` 由 A 臂 159.75~328.06 行/s 提升到 B 臂 469.08~517.11 行/s（B 臂三轮高度一致，`RSD(B)=4.35%`）；锁内单行墙钟 `T_proc` 由 A 臂 3.05~6.26 ms/行降至 B 臂 1.93~2.13 ms/行。注意：B 臂 `T_mark` 为线程聚合和（N=2 时含两线程各自墙钟），故不随 N 单向下降；判别只看锁内墙钟 `T_proc`。

## 4. 逐门实测（M0–M6）

- **M0 环境门**：Docker daemon up；5 个演示容器（mysql/namesrv/broker/redis/nacos）全部 `Up ... (healthy)`；`max_connections=151`、`Threads_connected` 起栈后 `22` ⇒ 余量 129 ≥ 30。
- **M1 机制门（逐轮硬门）**：
  - A 臂（A1/A2/A3）：诊断行 `sendConcurrency=1` 命中数=诊断行数（35/34/37）、`sendConcurrency=2` ZERO_HIT、并发聚合注记 ZERO_HIT、线程池创建日志 ZERO_HIT；批量标记日志正常输出（seg 92/91/93 行）、诊断行 `markBatchCalls>0`（92/91/93）且 `markBatchRows` 累计=2010=`total_rows`；逐行投递日志 ZERO_HIT。
  - B 臂（B1/B2/B3）：诊断行 `sendConcurrency=2` 命中数=诊断行数（35）；并发聚合注记 35/35/35；线程池创建日志 1 行（`sendConcurrency=2`）；批量标记日志 seg 108/108/106、`markBatchCalls>0`（108/108/106）、`markBatchRows` 累计=2010；逐行投递日志 ZERO_HIT。
- **M2 效应门**：见 §3，`M2_mean = 2.1274`，3/3 对 ≥1.5（需 ≥2）。
- **M3 控制门**：见 §3，clause ① 33.14% 未达，clause ② 平稳对 A1A2 成立 ⇒ M3 overall PASS；`RSD(B)=4.35% ≤25%` PASS。
- **M4 健康与语义门（逐轮硬门，六轮全过）**：`ok=2000 / errors=0 / limited429=0`；cohort 行数=2010 / SENT=2010 / `sent_at` 非空=2010 / 最终 PENDING=0；`retry_count>0`=0；耗尽行=0；`uk_event_id` 零重复；零 `RECONSUME_LATER`；relay 失败/耗尽日志 0 行；零锁异常；批量标记降级告警 0；批量标记异常 0；worker 单行异常隔离告警 0；并发夹取告警 0。
- **M5 代价门**：`Com_select` 增量 B/A = 18371/18356 = 1.0008；`Com_update` 增量 B/A = 2117.3/2102.0 = 1.0073（均 ∈[0.8,1.2]，两臂均为 chunk-size 25 分块标记 ⇒ 语句数同量级）。
- **M6 资源门（逐轮）**：`hikaricp_connections_timeout_total` 增量六轮全 0；Hikari `_active` 峰值=10、`_pending` 峰值 13~22（2s 轮询，照实披露）；消费侧无饿死（cohort=SENT=2010、排空末 PENDING=0）。

## 5. 落地动作与确认轮

- **配置落地**：`verify-service/src/main/resources/application.yml` 在 `verify.outbox:` 块下纯新增（注释 2 行 + `relay-send-concurrency: 2` + 1 空行）：`git diff --numstat` = `4 0`（纯新增），`^verify:` 与 `^spring:` 根键各恰 1 个，总行数 200 → 204，`git diff --check` rc=0。
- **绑定测试类**：新增 `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelaySendConcurrencyDefaultTest.java`（3 用例：① `YamlPropertySourceLoader` 断言 `verify.outbox.relay-send-concurrency` 为 `2`；② 反射断言 `VerifyOutboxRelay#relaySendConcurrency` 的 `@Value` 字面默认值仍为 `${verify.outbox.relay-send-concurrency:1}`（证明生产代码未改默认值）；③ `^verify:`/`^spring:` 根键各 1 个）。零容器依赖、零 `@SpringBootTest`。
- **确认轮 C**：用 `--mode=offline package` 重建 jar（verify-service sha256 由 `556E7565…B024FA` 变为 `8F0A05D061F30949D4CA5DA282F4B654B093C106E2978341CFED819E0CBA4FA3`）；四模块 `BUILD SUCCESS`、verify-service 测试数 **143** 全绿（新类 3 用例）。
  - **C（命令行零注入裸起）**：命令行仅 `-jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar`（无任何注入）。verify.log 出现线程池创建 INFO `outbox relay 已创建批内并发发送线程池：sendConcurrency=2` ⇒ **证明 `application.yml` 默认值已使 `relay-send-concurrency=2` 生产生效**；`ok=2000 / errors=0 / limited429=0`；批量标记日志 118 行、逐行投递日志 ZERO_HIT；M4/M5/M6 全过（cohort 2010/2010、末 PENDING=0、`retry_count>0`=0/耗尽=0/零锁异常/零降级告警/零 worker 隔离告警；`Com_update` 增量 2128、`Com_select` 增量 18411；hikaricp timeout 增量 0、`_active` 峰 10、`_pending` 峰 19）。

## 6. 历史与复判关系（TASK-161/164 → TASK-169 → TASK-171）

- **TASK-161 / TASK-164（前序，反证）**：在**单行逐行 `markSent`** 模式下开 `relay-send-concurrency: 4`，DB 连接/行锁争用导致线程耗时膨胀，改善比仅 1.22~1.36，未过 1.5。本轮前提已变：TASK-169 落地分块标记（`Com_update` 语句数减半），DB 写串行化瓶颈消除后再判别 N=2。
- **TASK-169（前置落地）**：分块标记（`relay-batch-mark-enabled: true`，chunk 25）相对关闭态 `M2_mean = 4.8691`、`RSD(A)=14.76%` 过 M3；已落地，`T_proc` A 臂 16.64~23.35 → B 臂 3.80~4.35 ms/行。
- **TASK-171（本轮）**：在 TASK-169 落地态之上单因素改 `relay-send-concurrency` 1→2。A 臂 `T_proc` = 3.5159 / 3.0483 / 6.2597 ms/行（`RSD 33.14%`，A3 受宿主抖动上抬），B 臂 = 1.9622 / 1.9338 / 2.1318 ms/行（`RSD 4.35%`）⇒ 逐对改善比 1.79 / 1.58 / 2.94，`M2_mean = 2.1274`；M3 由 clause ② 平稳对 A1A2 兜住。两轮均未使用排空采样器、未估端点斜率；`scripts/perf/relay-round-sampler.sh` 全程未执行。

## 7. 未覆盖项与不得推出的结论

1. spotbugs/pmd 未覆盖（checkstyle 仍以既有 862 门槛形态 rc=1）；`--mode=online` 与 CI 未跑 ⇒ 未达外部门槛（本任务不 push）。
2. **不得**把 `T_proc` 改善比 2.1274 读作端到端吞吐/延迟收益：判别量是**锁内墙钟**口径；负载 QPS 两臂同量级（A1~A3=211.24/222.18/200.73，B1~B3=217.59/214.46/219.17），仅受宿主抖动。
3. **不得**把 B 臂 `markMs/sendMs` 聚合和读作标记/发送耗时上升：那是 N=2 线程时间聚合口径（诊断行并发聚合注记已声明），`residualMs` 可能为负。
4. `Com_update` 增量两臂同量级（≈2100~2118，即 2010 + 批量 UPDATE），**不得**把它读作端到端改善；两臂均为 chunk-size 25 分块标记。
5. 四服务局部栈；连接池默认 10、单实例、无多实例竞争；未覆盖 RocketMQ 重投/端到端，也未覆盖「崩溃重复投递窗口」等授权语义变化的独立验证。
6. 不翻案 TASK-144/161/162/163/164/168/169/170 任何数字；不得与 TASK-164 排空斜率口径并列成优化前后。
7. 落地后 `relay-send-concurrency` 生产默认由 `1` 变为 `2`；回滚方式＝删除该 YAML 键（`@Value` 默认值仍为 `1`）或注入 `--verify.outbox.relay-send-concurrency=1`。
8. 诊断开关 `relay-diagnostics-enabled` 生产默认仍为 `false`；本轮 A/B 均显式开启以取纳秒级批次口径，属测量装置而非落地项。