# 判别：有界分块标记 SENT 对 outbox relay 锁内吞吐（TASK-169 三对交错复判 → 落地支，已落地）

> 判别问题（任务书 §1）：在已落地的 `verify.outbox.relay-interval-ms=500` 与串行基线 `relay-send-concurrency=1` 生产态之上，把 `verify.outbox.relay-batch-mark-enabled` 由 `false` 改 `true`（chunk 25），诊断口径单行锁内墙钟 `T_proc = lockProcessingMs / rows`（ms/行）相对落地态基线的改善是否**稳定 ≥1.5**，并在宿主 CPU 竞争下满足 M3 抗漂移排序控制门？
> 方法论：彻底弃用 ~2s 粗粒度排空采样器，全程改用纳秒级**批次诊断日志**（`relay-diagnostics-enabled=true`）；扩展为 **3 对交错轮次 A1→B1→A2→B2→A3→B3**（各 100×2000）以平抑宿主抖动；预热轮 W0/W1 留档丢弃。

## 1. 一句话裁决

**归预注册「落地支（GO）」并已完成条件式落地**：6 个计数轮逐轮 M1/M4/M5/M6 全过；M2 效应门通过（`M2_mean = 4.8691 ≥ 1.5`，且 3/3 对 ≥1.5）；M3 抗漂移排序控制门通过（`RSD(A) = 14.76% ≤ 20%`）；确认轮全过。落地件：`application.yml` 纯新增 `relay-batch-mark-enabled: true`（numstat `4 0`、根键 `^verify:`/`^spring:` 各 1、196→200 行），新增纯 JUnit 5 绑定测试类 `VerifyOutboxRelayBatchMarkDefaultTest`（3 用例），verify-service offline 测试数 137 → **140 全绿**。

## 2. 两臂与轮次（唯一差异＝注入参数）

- **A 臂（落地态基线 / 关闭态）**：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=false`（chunk=25，N=1，interval=500）。
- **B 臂（候选态 / 分块标记开启态）**：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=true --verify.outbox.relay-batch-mark-chunk-size=25`（N=1，interval=500）。
- 两臂同一 jar（sha256 `AF966F4EC14E9D85680275C613C4772E97FCE64B56E3E6170D07BC9BE10AEC28`），A/B 全程不换 jar。
- 轮次：**W0**（A，100×2000，丢弃留档）＋ **W1**（B，100×200，丢弃留档）；计数 **A1 → B1 → A2 → B2 → A3 → B3**（各 100×2000）。
- 每轮负载由 `run-perf.sh load 100 2000 task169-<label>` 驱动（内含 10 条预热请求 ⇒ 每轮 cohort = 2010 行）。
- 每臂切换均规范重启 verify-service（同 jar）；启动前 `export MYSQL_PORT=3307`、显式 `JAVA_BIN`、unset 代理并设 `no_proxy="*"`。

## 3. 核心读数（批次诊断口径，逐轮汇总）

数据源：`docs/perf/data/raw/task169-<label>-verify.log` 经**行偏移切分**（`mark_before` 之后为纯计数轮贡献，剔出同进程预热贡献）后的批次诊断行 `outbox relay 诊断（批次）：rows=… lockProcessingMs=… lockHoldMs=… markMs=… sendMs=…`。

| 轮 | 臂 | 批次 | rows | ΣlockProcessingMs | ΣmarkMs | ΣsendMs | **T_proc(ms/行)** | T_mark(ms/行) | R_lock(行/s) | markBatchCalls | markBatchRows |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | A | 33 | 2010 | 46924 | 41325 | 5124 | **23.3453** | 20.5597 | 42.84 | 0 | 0 |
| B1 | B | 33 | 2010 | 7636 | 2795 | 4227 | **3.7990** | 1.3905 | 263.23 | 90 | 2010 |
| A2 | A | 32 | 2010 | 36594 | 31247 | 4926 | **18.2060** | 15.5458 | 54.93 | 0 | 0 |
| B2 | B | 41 | 2010 | 8749 | 3334 | 4757 | **4.3527** | 1.6587 | 229.74 | 97 | 2010 |
| A3 | A | 32 | 2010 | 33448 | 27805 | 5219 | **16.6408** | 13.8333 | 60.09 | 0 | 0 |
| B3 | B | 36 | 2010 | 7637 | 2924 | 4143 | **3.7995** | 1.4547 | 263.19 | 93 | 2010 |
| W0（丢弃） | A | 35 | 2010 | 35805 | 30093 | 5131 | 17.8134 | 14.9716 | 56.14 | 0 | 0 |
| W1（丢弃） | B | 4 | 210 | 954 | 227 | 640 | 4.5429 | 1.0810 | 220.13 | 10 | 210 |
| C（裸起） | C | — | — | — | — | — | — | — | — | 见 §6 | — |
| Cd（仅诊断） | D | 40 | 2010 | 8713 | 2915 | 4038 | **4.3348** | 1.4502 | 230.69 | 96 | 2010 |

**M2 效应门（核心判别门）**
```
M2_mean = mean(T_proc A)/mean(T_proc B) = 19.397347/3.983748 = 4.8691  (>=1.5) PASS
M2_1 = T_proc(A1)/T_proc(B1) = 23.345274/3.799005 = 6.1451  (>=1.5) PASS
M2_2 = T_proc(A2)/T_proc(B2) = 18.205970/4.352736 = 4.1826  (>=1.5) PASS
M2_3 = T_proc(A3)/T_proc(B3) = 16.640796/3.799502 = 4.3797  (>=1.5) PASS
```
**M3 排序控制门（抗漂移综合判定）**
```
RSD(A) = std(T_proc A)/mean(T_proc A) = 14.76%  (clause 1: <=20%) PASS
RSD(B) = 6.55%  (recorded, disclosed threshold <=25%)
A 平稳对：|T_proc(A2)-T_proc(A3)|/mean = 8.98% <= 20%，且该对 B2/B3 改善比 4.1826 / 4.3797 均 >=1.5（clause 2 亦成立）
反证支检查（三个改善比全 <=1.0）=> NO
```
**机制确证量化**：单行标记耗时 `T_mark` 由 A 臂 13.83~20.56 ms 降至 B 臂 1.39~1.66 ms（缩短 88%~93%），锁内行吞吐 `R_lock` 由 42.84~60.09 行/s 提升到 229.74~263.23 行/s。

## 4. 逐门实测（M0–M6）

- **M0 环境门**：Docker daemon up；5 个演示容器（mysql/namesrv/broker/redis/nacos）全部 `running=true / health=healthy`；`max_connections=151`、`Threads_connected` 起栈前 1、起栈后 22 ⇒ 余量 150 / 129 ≥ 30。
- **M1 机制门（逐轮硬门）**：
  - A 臂（A1/A2/A3）：`verify.log` 零 `outbox 事件分块批量标记成功`、诊断行零 `markBatchCalls`、逐行投递日志正常输出（seg=2010 行 `outbox 事件投递成功`）。
  - B 臂（B1/B2/B3）：出现 `outbox 事件分块批量标记成功：rows=`（seg 90/97/93 行）；诊断行 `markBatchCalls > 0`（33/41/36 行命中）且 `markBatchRows` 累计=2010=`total_rows`；逐行投递日志 ZERO_HIT。
- **M2 效应门**：见 §3，`M2_mean = 4.8691`，3/3 对 ≥1.5。
- **M3 控制门**：见 §3，`RSD(A) = 14.76% ≤ 20%` 通过（TASK-168 该门为 45.31% 失效，本轮 3 对交错后收敛）。
- **M4 健康与语义门（逐轮硬门，六轮全过）**：`ok=2000 / errors=0 / limited429=0`；cohort 行数=2010 / SENT=2010 / `sent_at` 非空=2010 / 最终 PENDING=0；`retry_count>0`=0；耗尽行=0；`uk_event_id` 零重复；零 `RECONSUME_LATER`；relay 失败/耗尽日志 0 行；零锁异常；批量标记降级告警 0；批量标记异常 0。
- **M5 代价门**：`Com_select` 增量 B/A = 18352/20318 = 0.9032、18364/20310 = 0.9042、18362/20307 = 0.9042（均 ∈[0.8,1.2]）；`Com_update` 增量 B/A = 2100/4020 = 0.5224、2107/4020 = 0.5241、2103/4020 = 0.5231（均 ≤0.65）。
- **M6 资源门（逐轮）**：`hikaricp_connections_timeout_total` 增量六轮全 0；Hikari `_active` 峰值=10、`_pending` 峰值 15~19（2s 轮询，照实披露）；消费侧无饿死（cohort=SENT=2010、排空末 PENDING=0）。

## 5. 落地动作与确认轮

- **配置落地**：`verify-service/src/main/resources/application.yml` 在 `verify.outbox:` 块下纯新增（注释 2 行 + `relay-batch-mark-enabled: true` + 1 空行）：`git diff --numstat` = `4 0`（纯新增），`^verify:` 与 `^spring:` 根键各恰 1 个，总行数 196 → 200，`git diff --check` rc=0。
- **绑定测试类**：新增 `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkDefaultTest.java`（3 用例：① `YamlPropertySourceLoader` 断言 `verify.outbox.relay-batch-mark-enabled` 为 `true`；② 反射断言 `VerifyOutboxRelay#relayBatchMarkEnabled` 的 `@Value` 字面默认值仍为 `${verify.outbox.relay-batch-mark-enabled:false}`；③ `^verify:`/`^spring:` 根键各 1 个）。零容器依赖、零 `@SpringBootTest`。
- **确认轮**：用 `--mode=offline package` 重建 jar（sha256 由 `AF966F4E…AEC28` 变为 `240149FC171D63958630674A69C3EE529AF49F805540F3937A9C9A375EA1EC70`）。
  - **C（命令行零注入裸起）**：命令行仅 `-jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar`（无任何注入）。`ok=2000 / errors=0 / limited429=0`；批量标记日志 103 行（`outbox 事件分块批量标记成功：rows=`）、逐行投递日志 ZERO_HIT ⇒ 证明 `application.yml` 的默认值已使生产默认开启；M4/M5/M6 全过（cohort 2010/2010、PENDING=0、cu 增量 2113 ⇒ 比值 0.5256、timeout 增量 0）。
  - **Cd（仅带诊断开关，不带分块标记注入）**：`--verify.outbox.relay-diagnostics-enabled=true`，分块标记仍由 `application.yml` 默认值提供。`markBatchCalls > 0`（诊断行 40 条命中 `markBatchCalls`，批量标记成功日志 96 行，`markBatchRows` 累计=2010）、`T_proc = 4.3348 ms/行`、`T_mark = 1.4502 ms/行`、`R_lock = 230.69 行/s`；对 A 臂均值改善比 `19.397347/4.334826 = 4.4748 ≥ 1.5`，对 B 臂均值偏差 8.81%（一致）；M4/M5/M6 全过。
  - 说明：任务书 §6 既要求「无任何命令行注入」又要求核 `markBatchCalls`/`T_proc`，而诊断开关默认关闭、`markBatchCalls` 只出现在诊断行中；故按字面跑 C（裸起，验证默认已开启）并按 §4 的诊断口径补跑 Cd（仅诊断注入）以取得判别量，二者均纳入结论。

## 6. 历史与复判关系（TASK-168 → TASK-169）

- **TASK-168（前序，未定支）**：同一两臂、同一诊断口径，但只有 2 对（A1/B1/A2b/B2）。M1/M4/M5/M6 全过、M2 两比值 2.0497 / 4.6493，但 **M3 控制门失效**（`|T_proc(A2b)-T_proc(A1)|/mean = 45.31% > 20%`，B 臂两轮偏差 35.41% > 25%），判未定支、未落地。漂移共变量：宿主 CPU 竞争下 MySQL 单行 fsync 翻倍，负载 QPS 由 137 单调降到 71~75。
- **TASK-169（本轮）**：对照点扩到 **3 对交错**（A1→B1→A2→B2→A3→B3）。A 臂 `T_proc` = 23.35 / 18.21 / 16.64 ms/行（RSD 14.76%），B 臂 = 3.80 / 4.35 / 3.80 ms/行（RSD 6.55%）⇒ M3 门由 45.31% 收敛到 14.76%，逐对改善比 6.15 / 4.18 / 4.38 与 TASK-168 同量级且方向一致。
- 两轮均未使用 ~2s 排空采样器、未估端点斜率；`scripts/perf/relay-round-sampler.sh` 全程未执行。

## 7. 未覆盖项与不得推出的结论

1. spotbugs/pmd 未覆盖（checkstyle 仍以既有 867 门槛形态 rc=1）；`--mode=online` 与 CI 未跑 ⇒ 未达外部门槛（本任务不 push）。
2. **不得**把 `T_proc` 改善比读作端到端吞吐/延迟收益：本轮判别量是**锁内墙钟**口径；负载 QPS 仍受宿主抖动（A 臂 166~178，B 臂 149~182，C 裸起 108）。
3. **不得**把 `Com_update` 语句数下降（≈5 折）读作端到端改善，它是批量 UPDATE 的语句数效应。
4. 四服务局部栈；连接池默认 10、单实例、无多实例竞争；未覆盖 RocketMQ 重投/端到端，也未覆盖「崩溃重复投递窗口由 1 行扩大到 chunk-size（25）」等授权语义变化的独立验证。
5. 不翻案 TASK-144/162/163/164/168 任何数字；不得与 TASK-164 排空斜率口径并列成优化前后。
6. 落地后 `relay-batch-mark-enabled` 生产默认由 `false` 变为 `true`；回滚方式＝删除该 YAML 键（`@Value` 默认值仍为 `false`）或注入 `--verify.outbox.relay-batch-mark-enabled=false`。
7. 诊断开关 `relay-diagnostics-enabled` 生产默认仍为 `false`；本轮 A/B/Cd 均显式开启以取纳秒级批次口径，属测量装置而非落地项。
