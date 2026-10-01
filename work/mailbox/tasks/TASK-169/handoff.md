# TASK-169 handoff：分块标记稳态判别与最终落地（平抑 M3 漂移）

> 状态：**已收口（落地支 GO，条件式落地已完成，确认轮 C/Cd 全过）**。§1 为偏差登记；§2–§11 为裁决、读数、逐门、落地、交付与收口终检。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。

## 0. 指导侧裁定与开工规程

- **裁定一（开工）**：`origin/main` 以实测 `df4a56f6fcf361a0abade58ad1db0a8d74c81390` 为权威值，任务书 §8 系后 32 位抄录笔误，无需修改 spec.md，解除停手。
- **裁定二（落地，采纳甲方案）**：正式授权修改既有用例 `VerifyOutboxRelayBatchMarkConfigTest` 的用例 2，解除任务书 §6 落地动作与该守卫断言的冲突。
- 规程：① 记偏差入本文件；② 复跑剩余前置门禁；③ 启动 Docker 与 5 演示容器，依 §3 执行 A1→B1→A2→B2→A3→B3；④ 全面从 verify.log 批次诊断行提取 `T_proc`，核算 M1~M6；⑤ 达成落地支且确认轮通过后才改 `application.yml` 与加绑定测试类；⑥ 收尾清理与 offline 复跑，交付本 handoff 与原始物料。

## 1. 偏差登记（§8 开工读数对照）

开工前对任务书 §8 逐项亲跑复核（Level A）。共 3 项偏差，均**不影响实验参数与判别结论**。

### 1.1 (A) `origin/main` 哈希抄录笔误（指导侧已裁定）

| 项 | §8 原文 | 实测 | 判定 |
| --- | --- | --- | --- |
| `origin/main` | `df4a56f6ba3a64b971a81dcff14e30018ff9f57a` | **`df4a56f6fcf361a0abade58ad1db0a8d74c81390`** | **§8 后 32 位抄录笔误** |

对比依据：任务书值在本仓**不是任何对象**（`git cat-file -t` → `could not get object info`；`git rev-list --all` 的 466 个提交与 `origin/main` 的 29 条 reflog 中均无该哈希）；两者仅前 8 位十六进制 `df4a56f6` 相同。实测值与 §8 同行的 `git rev-list --left-right --count origin/main...main = 0 1` **完全自洽**（该提交即 HEAD `77cc8862…` 的父提交，提交信息 `docs(mailbox): 登记 TASK-168 验收记录与任务两件套`）。指导侧裁定：实测值为真实权威值，无需修改 spec.md。

### 1.2 (B) 受保护 token 基线：8/20 项 §8 值低于实测（Level A）

§8 列出的 20 个 PLAN.md 受保护数字 token 中，12 项与实测逐位一致，**8 项实测高于 §8**（两种独立计数法一致：`grep -oF | wc -l` 与 Python 正则计数）：

| token | §8 | 实测 | token | §8 | 实测 |
| --- | --- | --- | --- | --- | --- |
| `36525962432` | 11 | **12** | `2806` | 17 | **23** |
| `36586847965` | 10 | **11** | `36736221648` | 9 | **10** |
| `36808102571` | 4 | **5** | `36821040708` | 2 | **3** |
| `36845152965` | 1 | **2** | | | |

成因（B 级推断）：§8 似由 TASK-168 handoff §1.4 的 19 项计数「+1」推得，而 TASK-168 追加到 PLAN.md 的真实增量随 token 而异。**该门是单向门（本任务结束时不得减少）**，20 项实测**均 ≥ §8**，且本任务对 PLAN.md **纯追加**，故单向门满足且留有裕量。本 handoff 据实登记，不修改 spec.md。

### 1.3 (C) 任务书 §6 落地动作与既有守卫测试互斥（指导侧已裁定并授权）

| 项 | 内容 |
| --- | --- |
| 冲突 | §6 要求 `application.yml` 新增 `verify.outbox.relay-batch-mark-enabled: true`；而既有用例 `VerifyOutboxRelayBatchMarkConfigTest#testClasspathApplicationYmlDoesNotContainBatchMarkKeys` 断言该键**必须不存在**（`assertNull`）。二者不可同时成立 |
| 实证 | 按 §6 原文从 spec.md 现场提取 YAML 块临时插入后亲跑该测试类：`Tests run: 4, Failures: 1`，失败原文 `application.yml 不得声明 relay-batch-mark-enabled ==> expected: <null> but was: <true>`；随后按字节还原（`cmp` rc=0、sha256 `bc0c84c3…` 前后一致、`git status` 干净）。留档 `raw/task169-conflict-probe.log` |
| 连带矛盾 | §6④「137 → 137+3 = 140 全绿」只有在**翻转该既有断言**（该类仍占 4 例）时才成立，指向指导侧本意即为翻转，但 §9 未放行 |
| 裁定 | 指导侧正式授权「甲方案」：翻转既有用例 2。执行见 §7 |

### 1.4 §8 其余各项（逐位一致）

HEAD `77cc8862a270a1dd0fbcc8d73601d63694a8a291`；`rev-list --left-right --count` = `0 1`；脏项仅 `?? spec/changes/add-verify-degrade-status-index/` + 本任务目录；offline `36/41/33/103/137/59/10` rc=0（Skipped 全 0）；static rc=1 且 Checkstyle 严格 **867**；`application.yml` 196 行、blob CR=0、工作树 CR=6、`^verify:`=1/`^spring:`=1、`relay-interval-ms: 500` 在 L123、`relay-batch-mark` 命中 0；`VerifyOutboxRelay.java` 785 行且 L68/L72/L79/L96/L114/L123/L150 逐位一致；主规格 3568 行/161 个 `### Requirement:`；PLAN.md 1411 行/CR=0；词面门四形态 ZERO_HIT rc=1 且正向探针 rc=0；契约门无参 rc=1、`--open TASK-169 --baseline=77cc8862…` rc=0。

## 2. 一句话裁决与三支归属

**归预注册「落地支（GO）」并已完成条件式落地**：6 个计数轮（A1/B1/A2/B2/A3/B3）逐轮 M1/M4/M5/M6 全过；**M2 效应门通过**（`M2_mean = 4.8691 ≥ 1.5`，且 3/3 对 ≥1.5）；**M3 抗漂移排序控制门通过**（`RSD(A) = 14.76% ≤ 20%`，另有平稳对 A2/A3 = 8.98%）；`RSD(B) = 6.55% ≤ 25%`；确认轮 C 与 Cd 全过。落地件：`application.yml` 纯新增 `relay-batch-mark-enabled: true`、新增绑定测试类 `VerifyOutboxRelayBatchMarkDefaultTest`（3 用例）、授权翻转既有守卫用例 2；verify-service offline 测试数 **137 → 140 全绿**。
**反证支检查**：三个改善比全 ≤1.0 ⇒ NO。**预算**：预热轮 2/2（W0/W1，留档丢弃）、计数轮 6/6、替换轮 0/1（未动用）。

## 3. 只改清单

本任务落地支形态下实际改动的 8 条路径（相对开工基线 `77cc8862`）：

```
verify-service/src/main/resources/application.yml
verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkDefaultTest.java
verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkConfigTest.java
docs/perf/判别-outbox-relay-分块标记吞吐.md
docs/perf/data/exp-outbox-relay-batch-mark.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-169/spec.md
work/mailbox/tasks/TASK-169/handoff.md
```

显式零触碰：既有脏项 `spec/changes/add-verify-degrade-status-index/`（4 个文件，工作树开工即存在）；`task131-scratch-mysql` 容器与演示库；一切 `src/main` 生产 Java、SQL DDL/DML、Mapper XML、`pom.xml`、既有脚本（`scripts/**` 未改，仅执行）；除上表两处测试文件外无任何既有测试用例被改动；主规格 `spec/specs/sport-record-verify/spec.md` 零改动。原始物料 `docs/perf/data/raw/task169-*` 为 gitignored 运行证据。

## 4. 两臂与轮次编排

- **A 臂（落地态基线 / 关闭态）**：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=false`（chunk=25，N=1，interval=500）。
- **B 臂（候选态 / 分块标记开启态）**：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=true --verify.outbox.relay-batch-mark-chunk-size=25`（N=1，interval=500）。
- **唯一差异＝注入参数**；两臂均带同一诊断开关以消除诊断自身开销的对比偏差。
- 两臂同一 jar：`af966f4ec14e9d85680275c613c4772e97fce64b56e3e6170d07bc9be10aec28`（A/B 全程不换 jar，`raw/task169-g2-jarsha.txt`）。
- 编排：**W0**（A，100×2000，丢弃留档）→ **W1**（B，100×200，丢弃留档）→ **A1 → B1 → A2 → B2 → A3 → B3**（各 100×2000）。每臂切换均规范重启 verify-service；启动前 `export MYSQL_PORT=3307`、显式 `JAVA_BIN="D:/develop1/jdk21/bin/java"`、unset 代理并设 `no_proxy="*"`。
- 预算使用：预热轮 **2/2**、计数轮 **6/6**、替换轮 **0/1**（六轮无一红，未动用替补）。
- 每轮负载由 `run-perf.sh load 100 2000 task169-<label>` 驱动（内含 10 条预热请求 ⇒ cohort = 2010 行）。

## 5. 核心读数与 M2/M3（批次诊断口径）

数据源：各轮 `docs/perf/data/raw/task169-<label>-verify.log` 经**行偏移切分**（`mark_before` 之后为该轮纯贡献）后的批次诊断行。

| 轮 | 臂 | 批次数 | rows | ΣlockProcessingMs | ΣmarkMs | ΣsendMs | **T_proc(ms/行)** | T_mark(ms/行) | R_lock(行/s) | markBatchCalls | markBatchRows |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | A | 33 | 2010 | 46924 | 41325 | 5124 | **23.3453** | 20.5597 | 42.84 | 0 | 0 |
| B1 | B | 33 | 2010 | 7636 | 2795 | 4227 | **3.7990** | 1.3905 | 263.23 | 90 | 2010 |
| A2 | A | 32 | 2010 | 36594 | 31247 | 4926 | **18.2060** | 15.5458 | 54.93 | 0 | 0 |
| B2 | B | 41 | 2010 | 8749 | 3334 | 4757 | **4.3527** | 1.6587 | 229.74 | 97 | 2010 |
| A3 | A | 32 | 2010 | 33448 | 27805 | 5219 | **16.6408** | 13.8333 | 60.09 | 0 | 0 |
| B3 | B | 36 | 2010 | 7637 | 2924 | 4143 | **3.7995** | 1.4547 | 263.19 | 93 | 2010 |
| W0（丢弃） | A | 35 | 2010 | 35805 | 30093 | 5131 | 17.8134 | 14.9716 | 56.14 | 0 | 0 |
| W1（丢弃） | B | 4 | 210 | 954 | 227 | 640 | 4.5429 | 1.0810 | 220.13 | 10 | 210 |
| Cd（确认·仅诊断） | D | 40 | 2010 | 8713 | 2915 | 4038 | **4.3348** | 1.4502 | 230.69 | 96 | 2010 |

```
M2_mean = mean(T_proc A)/mean(T_proc B) = 19.397347/3.983748 = 4.8691  (>=1.5) PASS
M2_1 = 23.345274/3.799005 = 6.1451   M2_2 = 18.205970/4.352736 = 4.1826   M2_3 = 16.640796/3.799502 = 4.3797
M2 逐对 >=1.5 的个数 = 3/3（需 >=2）=> PASS
RSD(A) = 14.76%  (M3 clause 1 <=20%) PASS     RSD(B) = 6.55% (<=25%)
M3 clause 2：|T_proc(A2)-T_proc(A3)|/mean = 8.98% <=20%，A2/A3 对应 B2/B3 改善比 4.1826/4.3797 均 >=1.5 => 亦成立
M3 overall => PASS       反证支检查（三比值全 <=1.0）=> NO
```

机制确证量化：`T_mark` 由 A 臂 13.83~20.56 ms/行 降至 B 臂 1.39~1.66 ms/行（缩短 88%~93%）；`R_lock` 由 42.84~60.09 行/s 升至 229.74~263.23 行/s。

## 6. 逐门 M0–M6（六轮逐轮硬门全过；M2/M3 亦过）

- **M0 环境门**：Docker daemon up（client/server 29.6.2）；5 个演示容器 `running=true / health=healthy`；`max_connections=151`、`Threads_connected` 起栈前 1 / 起栈后 22 ⇒ 余量 150 / 129 ≥ 30 ✓（`raw/task169-m0-docker.txt`、`raw/task169-g2-stack.txt`）。
- **M1 机制门（逐轮硬门）**：
  - A 臂（A1/A2/A3）：`verify.log` 零 `outbox 事件分块批量标记成功`、诊断行零 `markBatchCalls`/零 `markBatchRows`、逐行投递日志 seg=2010 行 `outbox 事件投递成功` ✓。
  - B 臂（B1/B2/B3）：出现 `outbox 事件分块批量标记成功：rows=`（seg 90/97/93 行）；诊断行 `markBatchCalls > 0`（33/41/36 行命中）且 `markBatchRows` 累计=2010=`total_rows`；逐行投递日志 ZERO_HIT（seg=0）✓。
- **M2 效应门**：见 §5，`M2_mean = 4.8691 ≥ 1.5`，逐对 6.1451/4.1826/4.3797（3/3）✓。
- **M3 抗漂移排序控制门**：见 §5，`RSD(A) = 14.76% ≤ 20%`（①通过），平稳对 A2/A3 8.98% 且对应 B 比值均 ≥1.5（②亦通过）；`RSD(B) = 6.55% ≤ 25%`（记录）✓。**对比 TASK-168：同口径 M3 为 45.31% 失效，本轮 3 对交错后收敛到 14.76%**。
- **M4 健康与语义门（逐轮硬门，六轮全过）**：`ok=2000 / errors=0 / limited429=0`；该轮 outbox 行数=2010=SENT 增量=2010、`sent_at` 非空=2010、最终 PENDING=0；`retry_count>0` 行数=0；耗尽行增量=0；`uk_event_id` 零重复；零 `RECONSUME_LATER`；relay 失败/耗尽日志 0 行；零锁异常行；零批量标记降级告警（`affected < count`）；批量标记异常 0 ✓。
- **M5 代价门**：`Com_select` 增量 B/A = 18352/20318 = 0.9032、18364/20310 = 0.9042、18362/20307 = 0.9042（均 ∈[0.8,1.2]）✓；`Com_update` 增量 A 臂恒 4020（2×2010）、B 臂 2100/2107/2103（≈2010 行 + 81 次 25 行批量 UPDATE），B/A = 0.5224/0.5241/0.5231（均 ≤0.65）✓。
- **M6 资源门（逐轮）**：`hikaricp_connections_timeout_total` 增量六轮全 0 ✓；Hikari `_active` 峰值=10、`_pending` 峰值 A1/B1/A2/B2/A3/B3 = 19/15/17/18/16/17（2s 轮询，照实披露，无阈值）；消费侧无饿死（cohort=SENT=2010、排空末 PENDING=0）✓。

## 7. 落地动作与确认轮（指导侧授权「甲方案」）

### 7.1 配置落地

- 备份原文件至 `.trae/tmp/t169-appyml-backup.bin`（sha256 `bc0c84c3dcb7b2821276090ad43cacac61b8e48431685b70e43c953c10245b0f`），插入前校验工作树与该备份逐字节一致。
- 在 `verify.outbox:` 块下**纯新增**（块内容从 spec.md §6 的 YAML 围栏**现场提取**以保证逐字一致，另加 1 行空行做分隔）：2 行注释 + `relay-batch-mark-enabled: true`。
- 核验：`git diff --numstat` = `4 0`（纯新增，无删除）；`^verify:` 与 `^spring:` 根键各恰 1 个；总行数 **196 → 200**；`relay-batch-mark` 命中 1；`git diff --check` rc=0；工作树 CR 数仍为 6（新增行用 LF）。

### 7.2 既有守卫用例的授权翻转（用例 2）

`VerifyOutboxRelayBatchMarkConfigTest` 用例 2：DisplayName 改为「2. classpath application.yml 声明 relay-batch-mark-enabled=true，chunk-size 仍不声明（走 @Value 默认 25）」；方法名改为 `testClasspathApplicationYmlDeclaresBatchMarkEnabled`；新增断言 `relay-batch-mark-enabled` 的值等于 `"true"`；**保留** `relay-batch-mark-chunk-size` 仍为 `null` 的断言。用例 **1/3/4 一字不动**（`git diff` numstat `16 6`，hunk 仅落在用例 2）。

### 7.3 新增绑定测试类

`verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkDefaultTest.java`（76 行，3 用例，零容器依赖、零 `@SpringBootTest`）：① 用 `YamlPropertySourceLoader` 断言 `verify.outbox.relay-batch-mark-enabled` 为 `true`；② 反射断言 `VerifyOutboxRelay#relayBatchMarkEnabled` 的 `@Value` 字面默认值仍为 `${verify.outbox.relay-batch-mark-enabled:false}`（证生产代码未改默认值）；③ 断言 `^verify:` 与 `^spring:` 根键各恰 1 个。

### 7.4 确认轮

- 用 `--mode=offline package` 重建 jar：sha256 由 `af966f4ec14e9d85680275c613c4772e97fce64b56e3e6170d07bc9be10aec28` 变为 **`240149fc171d63958630674a69c3ee529af49f805540f3937a9c9a375ea1ec70`**（`raw/task169-g3-jarsha.txt`）。
- **C（命令行零注入裸起）**：进程命令行仅 `-jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar`（`raw/task169-C-cmdline.txt`）。`ok=2000 / errors=0 / limited429=0`；**批量标记日志 103 行**、逐行投递日志 ZERO_HIT ⇒ 证明 `application.yml` 的默认值已使生产默认开启分块标记；M4 全过（cohort 2010/2010、PENDING=0、`retry_count>0`=0）、M5（cu 增量 2113 ⇒ B/A 0.5256；cs 增量 18414 ⇒ 0.9066）、M6（timeout 增量 0、`_active` 峰 10、`_pending` 峰 23）全过。
- **Cd（仅带诊断开关，不带分块标记注入）**：`--verify.outbox.relay-diagnostics-enabled=true`，分块标记仍由 `application.yml` 默认值提供。`markBatchCalls > 0`（诊断行 40 条命中，批量标记成功日志 96 行，`markBatchRows` 累计=2010）；`T_proc = 4.334826 ms/行`、`T_mark = 1.4502 ms/行`、`R_lock = 230.69 行/s`；对 A 臂均值改善比 `19.397347/4.334826 = 4.4748 ≥ 1.5`、对 B 臂均值偏差 8.81%（一致）；M4/M5/M6 全过（cu 增量 2106 ⇒ 0.5239，cs 增量 18363 ⇒ 0.9040，timeout 增量 0）。
- **口径说明**：任务书 §6 既要求「无任何命令行注入」又要求核 `markBatchCalls`/`T_proc`，而 `relay-diagnostics-enabled` 生产默认 `false`、`markBatchCalls` 只出现在诊断行中，二者不可同时满足；故按字面跑 **C（裸起，验证默认已开启）**，并按 §4 强制的诊断口径补跑 **Cd（仅诊断注入）**以取得判别量，二者均纳入结论、均留档。

## 8. 假设与预登记判据的触碰情况

1. 诊断口径可用，A/B/Cd 同口径对称；**彻底弃用 ~2s 排空采样器**：未采 drain.csv、未估端点斜率，`scripts/perf/relay-round-sampler.sh` 全程未执行。
2. 只判别分块标记这一项开关：concurrency 保持 1、batch-size 未动、interval 未换档、chunk-size 固定 25。
3. **M3 漂移说明与收敛**：TASK-168 因 A 对照臂宿主 CPU 竞争下 MySQL 单行 fsync 翻倍导致 M3（45.31%）失效。本轮 A 臂 `T_proc` 为 23.35 / 18.21 / 16.64（RSD 14.76%），仍可见单调下行趋势但已收敛到门内；同期共变量：`sports_java = 4` 恒定、`nonsports_java` 2→0、CPU 5%~46% 波动、第三方容器 `gsproj_mysql_measure`/`gsproj_redis_measure` 部分时段在跑、负载 QPS 149~182 波动。**不对机理做外推**。
4. **替换轮未动用**（六轮无一 M4 红）；预热轮 2 个按预算全用。
5. **执行侧偏差登记（非任务书缺陷）**：Windows PTY 会在交互式写盘时把 LF 变 CRLF，故落盘脚本统一做 `\r\n → \n` 归一；`application.yml` 的插入块从 spec.md **现场提取**（我手工重敲的一版把 `≥ 1.5` 写成 `≥1.5`，比对发现后改用现场提取，保证逐字一致）。上述均为执行工具链问题，未触及被测代码/参数。
6. 任务书 §6 的「零注入裸起」与「核 `markBatchCalls`/`T_proc`」互斥（诊断默认关闭），处置见 §7.4 口径说明，未放宽任何门。

## 9. 未覆盖项与不得推出的结论

1. spotbugs/pmd 未覆盖（checkstyle 以既有 867 门槛形态 rc=1）；`--mode=online` 与 CI 未跑 ⇒ **未达外部门槛**（本任务不 push）。
2. **不得**把 `T_proc` 改善比（4.87 均值）或 `T_mark` 下降读作端到端吞吐/延迟收益：判别量是**锁内墙钟**口径；本轮负载 QPS 仍受宿主抖动（C 裸起 108.07 明显偏低）。
3. **不得**把 `Com_update` 语句数下降（≈5 折）读作端到端改善。
4. 四服务局部栈；池默认 10、单实例、无多实例竞争；未覆盖 RocketMQ 重投/端到端；**未独立验证**落地后「崩溃重复投递窗口由 1 行扩大到 chunk-size（25）」、`sent_at` 批内同值、SENT 独立连接可见性推迟等授权语义变化的运行时后果（仅由既有 TASK-165/166 的上界证明与代码逻辑支撑）。
5. 不翻案 TASK-144/162/163/164/168 任何数字；不得与 TASK-164 排空斜率口径并列成优化前后。
6. 落地后 `relay-batch-mark-enabled` 生产默认由 `false` 变为 `true`：回滚方式＝删除该 YAML 键（`@Value` 默认值仍为 `false`）或注入 `--verify.outbox.relay-batch-mark-enabled=false`；回滚会同时使 `VerifyOutboxRelayBatchMarkConfigTest` 用例 2 与 `VerifyOutboxRelayBatchMarkDefaultTest` 用例 1 变红，需一并回退。
7. 诊断开关 `relay-diagnostics-enabled` 生产默认仍为 `false`，属测量装置而非落地项。

## 10. 交付物与原始物料

- 判别报告：`docs/perf/判别-outbox-relay-分块标记吞吐.md`（TASK-169 复判与落地版：裁决 + 两臂轮次 + 逐轮读数 + M2/M3 + 逐门 + 落地与确认轮 + 历史 + 未覆盖）。
- 机器摘要：`docs/perf/data/exp-outbox-relay-batch-mark.json`（JSON 语法校验通过，`verdict = LANDED`）。
- 本 handoff：`work/mailbox/tasks/TASK-169/handoff.md`；任务书 `work/mailbox/tasks/TASK-169/spec.md`（零修改）。
- 验收记录：`work/mailbox/PLAN.md`（纯追加 TASK-169 节；20 个受保护 token 只增不减）。
- 落地件：`verify-service/src/main/resources/application.yml`（纯新增 4 行）、`verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkDefaultTest.java`（新增）、`verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayBatchMarkConfigTest.java`（授权翻转用例 2）。
- 原始物料（gitignored，`docs/perf/data/raw/task169-*`）：每轮 `-verify.log` / `-verify.crlf.txt` / `-diaglines.txt` / `-record.log` / `-c100-summary.json` / `-c100-raw.csv` / `-stats.txt` / `-hikari-peak.txt` / `-round.txt`（label ∈ {W0,W1,A1,B1,A2,B2,A3,B3,C,Cd}）、`-cmdline.txt`（进程命令行）、`task169-m0-docker.txt`、`task169-g2-jarsha.txt`、`task169-g2-package.log`、`task169-g2-stack.txt`、`task169-g3-jarsha.txt`、`task169-g3-package.log`、`task169-g3-package.rc`、`task169-diag-summary.txt`、`task169-wording-*.txt`、`task169-contract-{noarg,open}.txt`、`task169-conflict-probe.log`、`task169-close-offline.log`、`task169-close-docker.txt`。
