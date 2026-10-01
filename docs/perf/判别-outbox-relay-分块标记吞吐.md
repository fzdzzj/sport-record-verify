# 判别：有界分块标记 SENT 对 outbox relay 锁内吞吐（TASK-168，条件式落地 → 未定支，未落地）

> 判别问题（任务书 §1）：在已落地的 `verify.outbox.relay-interval-ms=500` 与串行基线 `relay-send-concurrency=1` 生产态之上，把 `verify.outbox.relay-batch-mark-enabled` 由 `false` 改 `true`（chunk 25），诊断口径单行锁内墙钟 `T_proc = lockProcessingMs / rows`（ms/行）相对落地态基线改善是否 ≥1.5（`T_proc(A)/T_proc(B) >= 1.5`）。
> 方法论：彻底弃用 ~2s 粗粒度排空采样器，全程改用纳秒级**批次诊断日志**（`relay-diagnostics-enabled=true`）— 消除跨会话 ~2× 采样方差。

## 1. 一句话裁决与三支归属

**归预注册「未定支（UNDETERMINED）」**：四个计数轮（A1/B1/A2b/B2）逐轮 M1/M4/M5/M6 全过、M2 效应门**两比值均 ≥1.5**（`T_proc(A1)/T_proc(B1)=2.0497`、`T_proc(A2b)/T_proc(B2)=4.6493`），**但 M3 控制门未过**：`|T_proc(A2b)-T_proc(A1)|/mean = 45.31% > 20%`（A 对照臂自身在两轮间漂移近一倍），且 B 候选臂两轮偏差 `35.41% > 25%`（该臂预注册只记录、不设硬门，但同样越线）。按任务书 §7，M3 控制门是落地支的必要条件，其失效属**排序漂移** ⇒ 只报实测读数，不落地、不凑结论、不外推。

**未落地**：`application.yml` 零改动、未新增绑定测试类、未跑确认轮 C；`relay-batch-mark-enabled` 生产默认仍 `false`。**零代码改动**。

## 2. 两臂与轮次（唯一差异＝注入参数）

- **A 臂（落地态基线 / 关闭态）**：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=false`（chunk=25，N=1，interval=500）。
- **B 臂（候选态 / 分块标记开启态）**：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=true --verify.outbox.relay-batch-mark-chunk-size=25`（N=1，interval=500）。
- 两臂同一 jar（sha256 `8FE7D6EFADAD9E4722567BF078D9B359F6EAA9C3AFCFD35C9EC01D5356BF831D`），A/B 全程不换 jar。
- 轮次：起栈首轮 **W0**（A 臂，100x2000，丢弃留档）＋ 首次切 B 臂首轮 **W1**（B 臂，100x200，丢弃留档）；计数轮 **A1 → B1 → A2 → B2**（各 100x2000）。
  - **A2 首跑因执行侧排空等待逻辑缺陷（异步建档使首个轮询误读 PENDING=0）提前退出，M4 红（cohort 405/2010，余 618 未排空）**，按 §3 用**同臂同参数替换轮 A2b**（100x2000）替换；失败轮 A2 照占预算、原文留档（`raw/task168-A2-*`）。故 M3/M2 的第二对照点取 A2b。
  - 每轮负载由 `run-perf.sh load 100 2000` 驱动（内含 10 条预热请求 ⇒ 每轮 cohort = 2010 行）。

## 3. 核心读数（批次诊断口径，逐轮汇总）

数据源：各轮 `docs/perf/data/raw/task168-<label>-verify.log` 经**行偏移切分**（`mark_before` 之后为纯计数轮，剔出同进程预热轮 W0/W1 贡献）后的批次诊断行 `outbox relay 诊断（批次）：rows=… lockProcessingMs=… lockHoldMs=… markMs=… sendMs=…`。

| 轮 | 臂 | 批次数 | rows | ΣlockProcessingMs | ΣmarkMs | ΣsendMs | **T_proc(ms/行)** | T_mark(ms/行) | R_lock(行/s) | markBatchCalls | markBatchRows |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | A | 36 | 2010 | 32899 | 24715 | 7542 | **16.3677** | 12.2960 | 61.10 | 0 | 0 |
| B1 | B | 38 | 2010 | 16051 | 6307 | 8496 | **7.9856** | 3.1378 | 125.23 | 97 | 2010 |
| A2b | A | 30 | 2010 | 52174 | 42536 | 9017 | **25.9572** | 21.1622 | 38.52 | 0 | 0 |
| B2 | B | 33 | 2010 | 11222 | 3532 | 6871 | **5.5831** | 1.7572 | 179.11 | 91 | 2010 |
| W0（丢弃） | A | 35 | 2010 | 29420 | 21660 | 6992 | 14.6368 | 10.7761 | 68.32 | 0 | 0 |
| W1（丢弃） | B | — | — | — | — | — | — | — | — | — | — |

**M2 效应门（核心判别）**
```
M2_1 = T_proc(A1)/T_proc(B1)  = 16.367662/7.985572 = 2.0497  (>=1.5) PASS
M2_2 = T_proc(A2b)/T_proc(B2) = 25.957214/5.583085 = 4.6493  (>=1.5) PASS
```
**M3 控制门（落地支必要条件）**
```
M3  = |T_proc(A2b)-T_proc(A1)|/mean(T_proc(A1),T_proc(A2b)) = |25.957214-16.367662|/21.162438 = 45.31%  (<=20%) FAIL
B 候选臂两轮偏差 = |T_proc(B2)-T_proc(B1)|/mean = |5.583085-7.985572|/6.784329 = 35.41%  (>25%，该臂仅记录)
反证支检查（两比值均 <=1.0）=> NO
```

## 4. 逐门实测（M0–M6）

- **M0 环境门**：Docker daemon up；5 个演示容器（mysql/namesrv/broker/redis/nacos）全部 `running=true`、`health=healthy`；`max_connections=151`、`Threads_connected=1`（起栈前）、`22`（起栈后）⇒ 余量 150/129 ≥ 30 ✓（`raw/task168-m0-docker.txt`、`raw/task168-m0-stack.txt`）。
- **M1 机制门（逐轮硬门）**：
  - A 臂（A1/A2b）：`verify.log` 零 `outbox 事件分块批量标记成功`（seg 计数 0）、诊断行零 `markBatchCalls`、逐行投递日志正常输出（A1 seg=2010、A2b seg=2010 行 `outbox 事件投递成功`）✓。
  - B 臂（B1/B2）：出现 `outbox 事件分块批量标记成功：rows=`（seg 97/91 行）；诊断行 `markBatchCalls > 0`（38/33 行命中）且 `markBatchRows` 累计=2010=total_rows；逐行投递日志 ZERO_HIT（seg=0）✓。
- **M2 效应门**：见 §3，两比值 2.0497 / 4.6493 均 PASS。
- **M3 控制门**：见 §3，45.31% **FAIL**（A 对照臂两轮不可比）。
- **M4 健康与语义门（逐轮硬门，四轮全过）**：
  - `ok=2000 / errors=0 / limited429=0`（四轮 load summary）。
  - outbox cohort 行数=2010 / SENT=2010 / sent_at 非空=2010（= SENT 增量 2010=2010）；最终 PENDING=0。
  - `retry_count>0` = 0；耗尽(PENDING,retry≥16)=0；`uk_event_id` 零重复（dup=0）；零 `RECONSUME_LATER`；relay 失败/耗尽日志 0 行；零锁异常行；`affected < count` 降级告警=0；批量标记异常=0。
- **M5 代价门**：
  - `Com_select` 增量：A1=20251 / B1=20111 / A2b=22382 / B2=19504。B/A 比值：B1/A1=0.9931、B2/A2b=0.8713 ⇒ **均落在 [0.8, 1.2]** ✓。
  - `Com_update` 增量：A1=4020 / A2b=4020（=2×2010，A 臂每行单行写+状态回写）；B1=2107 / B2=2101（≈2010 行 + 81 次 25 行批量 UPDATE，理论值 2010+81=2091，实测略高来自预热/历史）。B/A 比值：2107/4020=0.5241、2101/4020=0.5226 ⇒ **均 ≤0.65** ✓（B 臂显著降低数据库写语句数）。
- **M6 资源门（逐轮）**：`hikaricp_connections_timeout_total` 增量四轮全 0 ✓；Hikari `_active` 峰值=10、`_pending` 峰值 16~19（2s 轮询，`raw/task168-<label>-hikari-peak.txt`，无阈值照实披露）；消费侧无饿死（cohort=SENT=2010、排空末 PENDING=0）。

## 5. 假设与预登记判据的触碰情况

1. **诊断口径可用性成立**：`relay-diagnostics-enabled=true` 下批次诊断行逐轮可提取（A/B 臂同一口径），彻底取代了 ~2s 排空采样器。
2. **分块标记的机制收益（T_mark 单行标记耗时）被直接量化**：A 臂 markSent/行 12.30~21.16ms，B 臂降至 1.76~3.14ms —— 但这是**机制确证**，不构成吞吐/延迟收益的结论（M3 未过、估计量随会话漂移）。
3. **M3 控制门失效的机制披露**：A 对照臂 T_proc 在两轮间由 16.37 升至 25.96（+58.7%），B 候选臂由 7.99 降至 5.58（−30.1%）——两臂**反向漂移**。同期宿主共变量：`sports_java=4` 恒定，`nonsports_java` 由 2（A1/B1/A2b）降为 0（B2），CPU 采样 5%~93% 波动，另有 `gsproj_mysql_measure`/`gsproj_redis_measure` 第三方容器在跑；并发压测 QPS 由 A1 的 137 单调降到 B1/B2 的 71~75。**漂移方向与幅度无法在预注册预算内归因/消除** ⇒ 按 §7 归未定支，不给出机理解释。
4. **替换轮使用**：A2 首跑 M4 红（执行侧排空等待缺陷，非分块标记所致），动用 §3 允许的唯一一次同臂替换（A2b）。失败轮 A2 照占预算并留档。
5. **执行侧偏差（非任务书缺陷）**：`task168-*` 脚本以 ASCII 写盘致中文 `grep` 模式被替换（首轮 A1 的 M1 中文计数误报 0），已改用 UTF-8 模式文件 `.trae/tmp/t168-pat-*.txt` 并**从留档 verify.log 重算**（§4 M1 读数为重算值）；另修正了排空等待（见 4）与 git-bash 下 `/d/git/Git/bin` 置于 PATH 首部会破坏 `docker --format` 模板渲染（改 `usr/bin`+`cmd`）。

## 6. 未覆盖项与不得推出的结论

1. spotbugs/pmd 未覆盖（被 checkstyle 阻断）；`--mode=online` 与 CI 未跑 ⇒ 未达外部门槛。
2. **不得**把 `M2_1=2.0497`/`M2_2=4.6493`/`T_mark` 下降读作「分块标记已证明带来 ≥1.5× 锁内吞吐收益」：M3 控制门 45.31% 失效、B 臂两轮 35.41% 离差，估计量跨会话方差与效应同量级，**本轮只报读数**。
3. **不得**把 `Com_update` 语句数下降（≈5 折）读作端到端延迟/吞吐改善（提交路径 QPS 反而由 137 漂到 71~75，属宿主共变量）。
4. 四服务局部栈（无 leaderboard 消费、真实 R5 降级路径全程）；池默认 10、单实例、无多实例竞争；未覆盖 RocketMQ 重投/端到端。
5. 不翻案 TASK-144/162/163/164 任何数字；本判别与 TASK-164 的排空斜率口径（~2s 采样）**不得**并列成优化前后。
6. 未落地 ⇒ `relay-batch-mark-enabled` 生产默认仍 `false`；后续若要落地需重做判别（先解决 A 对照臂稳定性）。

## 7. 原始物料清单（docs/perf/data/raw/task168-*）

- 每轮：`task168-<label>-verify.log` / `-verify.crlf.txt` / `-diaglines.txt` / `-record.log` / `-c100-summary.json` / `-c100-raw.csv` / `-stats.txt` / `-hikari-peak.txt` / `-round.txt`（label ∈ {W0,W1,A1,B1,A2,A2b,B2}）。
- 起栈与环境：`task168-m0-docker.txt`、`task168-m0-stack.txt`、`task168-g2-jarsha.txt`、`task168-g2-package.log`、`task168-g1-offline.log`、`task168-g1-static.log`。
- 汇总：`task168-diag-summary.txt`、`task168-A1-cmdline.txt`、`task168-B1-cmdline.txt`、`task168-A2-cmdline.txt`、`task168-A2b-cmdline.txt`、`task168-B2-cmdline.txt`、`task168-W0A-cmdline.txt`。
