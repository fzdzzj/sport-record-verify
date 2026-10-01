# TASK-168 handoff：有界分块标记 SENT 对 outbox relay 锁内吞吐的判别（条件式落地）

> 状态：**已收口（未定支 UNDETERMINED，未落地，零代码改动）**。§1 为 §8 偏差登记；§2–§10 为裁决、读数、逐门、交付与收口终检。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。

## 0. 指导侧裁定与开工规程

指导侧裁定：**采纳 (甲) 方案**。认可 (A)/(B)/(C) 三处为 §8 **描述性笔误**与 **Git autocrlf 口径差异**，实质代码与实验参数完全成立，**无需修订 spec.md**。据此按下列规程立即开工：

1. 依 TASK-166 先例，把 (A)/(B)/(C) 三项实测原文与对比依据记入本文件「偏差登记」节（§1）；
2. 启动 Docker Desktop，确保 5 个演示容器 healthy，过 M0 环境门；
3. 严格依序执行预热轮 W0/W1 与四计数轮 A1 → B1 → A2 → B2（100x2000 负载）；
4. 从各轮 `verify.log` 提取批次诊断行，计算核心判别指标 `T_proc = lockProcessingMs / rows`，核算 M1~M6 门；
5. 仅当达成落地支且确认轮 C 通过时才改 `application.yml` 与加绑定测试类；反证或未定则零代码改动；
6. 收尾执行标准清理与 offline 复跑，交付本 handoff 与原始物料。

## 1. 偏差登记（§8 开工读数对照；依 TASK-166 §7「通道偏差登记」先例）

开工前对任务书 §8「开工读数」逐项亲跑复核（全部 Level A，除注明外）。**除下列 (A)/(B)/(C) 三处外，§8 其余各项逐位一致**（见 §1.2 全表）。三处均**不影响实质代码与实验参数**，与指导侧裁定一致：无需修订 spec.md，全程零代码改动。

### 1.1 (A)/(B)/(C) 三项实测原文与对比依据

| 项 | §8 声明原文 | 实测原文 | 判定 | 对比依据 |
| --- | --- | --- | --- | --- |
| **(A)** `verify-service/src/main/resources/application.yml` 的 CR | `CR=0` | 工作树 `CR=6` / `LF=196`；`git show HEAD:<file>` 的 blob `CR=0` / `LF=196` | **Git autocrlf 口径差异**（非缺陷） | 仓内 `.gitattributes` 仅对 `*.patch` 设 `-text`；其余文本 `core.autocrlf=true`（`git config --get core.autocrlf` → `true`）。§8 的 `CR=0` 是 **blob（LF）口径**，工作树因 autocrlf/工具写盘为混合行尾 ⇒ 实测工作树 `CR=6`。两口径下该文件**内容与行数（196）完全一致**，`^verify:`=1、`^spring:`=1、`relay-interval-ms: 500` 在 L123、`relay-batch-mark` 命中 0 均逐位成立。 |
| **(B)** `VerifyOutboxRelay.java` 行数 | `786 行` | 工作树 `wc -l` = **785**；`git show HEAD:<file> \| wc -l` = **785**；文件以 `}\n` 收尾 | **§8 描述性笔误**（+1 笔误） | 工作树与 blob 两种口径**同为 785**（非行尾/autocrlf 造成），故 §8 的 `786` 为笔误。行数非判别量、不参与任何门。 |
| **(C)** `VerifyOutboxRelay.java` 行锚点 | `L105 relay-batch-mark-enabled:false`、`L114 relay-batch-mark-chunk-size:25`、`L126 @Scheduled 默认 5000/10000` | 实测：`relay-batch-mark-enabled:false` 在 **L114**；`relay-batch-mark-chunk-size:25` 在 **L123**；`@Scheduled` 在 **L150** | **§8 描述性笔误**（行号漂移） | `grep -n` 现场取证（见 §1.3）。前两个锚点相对实测**整体 +9**（L105+9=114、L114+9=123，间距 9 保持），`@Scheduled` 锚点 +24（126→150）——同源笔误的不同漂移量，均为**行号标注错误**，所指代码实体（两 `@Value` 与 `@Scheduled`）**全部存在且语义逐位一致**（`relay-batch-mark-enabled:false` 默认关闭、`relay-batch-mark-chunk-size:25`、`@Scheduled` 默认 5000/10000）。 |
| （同组）| `L68 batch-size:100`、`L72 max-retry:16`、`L79 relay-diagnostics-enabled:false`、`L96 relay-send-concurrency:1` | 逐位一致（L68/L72/L79/L96 命中对应 `@Value`） | 一致（无偏差） | `grep -n` 现场取证。 |

**净影响**：(A) 纯口径；(B)(C) 纯标注。三者**均不触及** `application.yml` 落值、`VerifyOutboxRelay.java` 的 `@Value` 默认值、`@Scheduled` 参数、A/B 两臂注入参数（诊断开关、`relay-batch-mark-enabled`、`chunk-size=25`、N=1、interval=500）——故「实质代码与实验参数完全成立」，A1/B1/A2/B2 与确认轮 C 的编排不受影响。

### 1.2 §8 全项复核（除 (A)/(B)/(C) 外逐位一致）

| §8 项 | §8 声明 | 实测 | 一致？ |
| --- | --- | --- | --- |
| HEAD | `b5e6f850e999c926b4b63eb78a0e6dfb64d966c3` | 同 | 是 |
| origin/main | `cd6734e07812a147d170ddfbe6bdf70dfcf61e2e` | 同 | 是 |
| `rev-list --left-right --count origin/main...main` | `0 1` | `0	1` | 是 |
| 工作树脏项 | `?? spec/changes/add-verify-degrade-status-index/` ＋ 本任务目录 | 同（`git status --porcelain`） | 是 |
| offline 全量 | rc=0，七模块 36/41/33/103/137/59/10，Skipped 全 0 | rc=0，BUILD SUCCESS，同计数，Skipped 0 | 是 |
| 静态门 | rc=1，Checkstyle 严格 867 | rc=1，`You have 867 Checkstyle violations` | 是 |
| application.yml | 196 行 | 196 行 | 是 |
| application.yml 根键 | `^verify:` 1、`^spring:` 1 | 1 / 1 | 是 |
| `relay-interval-ms: 500` | L123 | L123 | 是 |
| `relay-batch-mark` 命中 | 0 | 0 | 是 |
| VerifyOutboxRelay.java | 786 行 | **785** | **否 (B)** |
| VerifyOutboxRelay.java L68/L72/L79/L96 | 四 `@Value` | 逐位一致 | 是 |
| VerifyOutboxRelay.java L105/L114/L126 | 三锚点 | **L114/L123/L150** | **否 (C)** |
| 主规格 | 3568 行 / 161 个 `### Requirement:` | 同 | 是 |
| 头部提案清单 / 归档目录 / 在途目录 | 58 / 60 / 7 | 58 / 60 / 7 | 是 |
| PLAN.md | 1388 行、CR=0 | 1388 行、CR=0 | 是 |
| 词面门（四形态） | 全 ZERO_HIT rc=1 | orig/C/zh_CN.UTF-8/C.UTF-8 全 rc=1 hits=0 | 是 |
| 契约门 | 无参 rc=1；`--open TASK-168 --baseline=b5e6f85…` rc=0 | 无参 rc=1；`--open` rc=0 | 是 |
| 19 个受保护数字 token | 逐项给定 | 全部精确匹配（见 §1.4） | 是 |

### 1.3 现场取证原文（grep -n）

```
$ grep -n -E "batch-size:100|max-retry:16|relay-diagnostics-enabled:false|relay-send-concurrency:1|relay-batch-mark-enabled:false|relay-batch-mark-chunk-size:25|@Scheduled" verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
68:    @Value("${verify.outbox.batch-size:100}")
72:    @Value("${verify.outbox.max-retry:16}")
79:    @Value("${verify.outbox.relay-diagnostics-enabled:false}")
96:    @Value("${verify.outbox.relay-send-concurrency:1}")
114:    @Value("${verify.outbox.relay-batch-mark-enabled:false}")
123:    @Value("${verify.outbox.relay-batch-mark-chunk-size:25}")
150:    @Scheduled(fixedDelayString = "${verify.outbox.relay-interval-ms:5000}",
```

```
$ wc -l verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
785 verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
$ git show HEAD:verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java | wc -l
785
```

```
$ tr -cd '\r' < verify-service/src/main/resources/application.yml | wc -c   # 工作树
6
$ git show HEAD:verify-service/src/main/resources/application.yml | tr -cd '\r' | wc -c   # blob
0
$ git config --get core.autocrlf
true
```

### 1.4 §8 受保护 token 实测（PLAN.md，出现次数法）

`13.4=13`、`18.0=15`、`73.93=14`、`68.8=10`、`6315=11`、`1.8612=10`、`3.3066=10`、`5.7056=10`、`9.408=10`、`36525962432=10`、`36586847965=9`、`36438897772=10`、`36399582548=9`、`36098038547=9`、`2806=16`、`598=9`、`36736221648=8`、`36808102571=3`、`36821040708=1` —— 与 §8 给定值**逐项一致**。

## 2. 一句话裁决与三支归属

**归预注册「未定支（UNDETERMINED）」**：四个计数轮（A1/B1/A2b/B2）逐轮 M1/M4/M5/M6 全过，**M2 效应门两比值均 ≥1.5**（`T_proc(A1)/T_proc(B1)=2.0497`、`T_proc(A2b)/T_proc(B2)=4.6493`），**但 M3 控制门未过**：`|T_proc(A2b)-T_proc(A1)|/mean = 45.31% > 20%`，B 候选臂两轮偏差 `35.41% > 25%`（该臂仅记录）。按任务书 §7，M3 是落地支必要条件，其失效属**排序漂移** ⇒ 只报实测读数，不落地、不凑结论、不外推。

**未落地 ⇒ 零代码改动**：`application.yml` 零改动、未新增绑定测试类、未跑确认轮 C；`relay-batch-mark-enabled` 生产默认仍 `false`。

## 3. 只改清单与零修改声明

只改/新增路径（严对齐任务书 §9 的**未定支形态**：去掉「仅落地支」两项）：
```
docs/perf/判别-outbox-relay-分块标记吞吐.md          (新增，判别报告)
docs/perf/data/exp-outbox-relay-batch-mark.json       (新增，机器摘要)
docs/perf/data/raw/task168-*                          (新增，原始物料，gitignored)
work/mailbox/PLAN.md                                  (纯追加验收记录)
work/mailbox/tasks/TASK-168/handoff.md                (本文件)
work/mailbox/tasks/TASK-168/spec.md                   (任务书，零修改)
```
显式未改动：`verify-service/src/main/resources/application.yml`（196 行零字节改动，`relay-batch-mark` 命中仍 0）；一切 `src/main` 生产 Java/Mapper/SQL/索引/pom/scripts；`relay-interval-ms`(500)/`batch-size`/`max-retry` 未动；未新增 `VerifyOutboxRelayBatchMarkDefaultTest`；主规格零改动；既有脏项 `spec/changes/add-verify-degrade-status-index/` 零触碰；`task131-scratch-mysql` 与演示库零清理（新增约 2.0 万行记录属运行证据）。

## 4. 两臂与轮次编排

- A 臂：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=false`；B 臂：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=true --verify.outbox.relay-batch-mark-chunk-size=25`。两臂 N=1、interval=500、同一 jar（sha256 `8FE7D6EFADAD9E4722567BF078D9B359F6EAA9C3AFCFD35C9EC01D5356BF831D`）。
- 编排：起栈首轮 **W0**（A，100x2000，丢弃）＋ 首次切 B 首轮 **W1**（B，100x200，丢弃）；计数 **A1(A) → B1(B) → A2(A) → B2(B)**（各 100x2000）。
- **A2 首跑 M4 红**（执行侧排空等待缺陷：异步建档使首个轮询误读 PENDING=0，提前退出；cohort 仅 405/2010，余 618 未排空）⇒ 按 §3 用**同臂同参数替换轮 A2b**；失败轮 A2 照占预算、原文留档。M3/M2 第二对照点取 **A2b**。
- 每轮批次诊断行按 **verify.log 行偏移 `mark_before`** 切分，剔出同进程预热轮贡献（详见报告 §3）。

## 5. 核心读数与 M2/M3（批次诊断口径）

| 轮 | 臂 | 批次数 | rows | ΣlockProcessingMs | ΣmarkMs | T_proc(ms/行) | T_mark(ms/行) | R_lock(行/s) | markBatchCalls | markBatchRows |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | A | 36 | 2010 | 32899 | 24715 | **16.3677** | 12.2960 | 61.10 | 0 | 0 |
| B1 | B | 38 | 2010 | 16051 | 6307 | **7.9856** | 3.1378 | 125.23 | 97 | 2010 |
| A2b | A | 30 | 2010 | 52174 | 42536 | **25.9572** | 21.1622 | 38.52 | 0 | 0 |
| B2 | B | 33 | 2010 | 11222 | 3532 | **5.5831** | 1.7572 | 179.11 | 91 | 2010 |

```
M2_1 = T_proc(A1)/T_proc(B1)  = 2.0497  (>=1.5) PASS
M2_2 = T_proc(A2b)/T_proc(B2) = 4.6493  (>=1.5) PASS
M3   = |T_proc(A2b)-T_proc(A1)|/mean = 45.31%  (<=20%) FAIL
B 候选臂两轮偏差 = 35.41% (>25%，仅记录)
反证支检查（两比值均 <=1.0）=> NO
```

## 6. 逐门 M0–M6（逐轮硬门全过；M3 控制门红）

- **M0**：5 容器 `running=true/health=healthy`；`max_connections=151`，余量 150（起栈前）/129（起栈后）≥30 ✓。
- **M1**：A 臂零批量标记日志、零 `markBatchCalls`、逐行投递日志正常（seg 2010）；B 臂批量标记日志出现（seg 97/91）、`markBatchCalls>0`（38/33）、`markBatchRows` 累计=2010=total_rows、逐行投递日志 ZERO_HIT ✓。
- **M2**：2.0497 / 4.6493 均 PASS。
- **M3**：45.31% **FAIL**（A 对照臂两轮不可比）⇒ 归未定支。
- **M4**（四轮全过）：`ok=2000/errors=0/limited429=0`；cohort 2010/SENT 2010/sent_at 2010；末 PENDING=0；retry>0=0；耗尽=0；dup=0；`RECONSUME_LATER`=0；relay 失败/耗尽 0；锁异常 0；`affected<count` 告警=0；批量标记异常=0 ✓。
- **M5**：`Com_select` B/A：0.9931 / 0.8713（均 ∈[0.8,1.2]）✓；`Com_update` A=4020/A=4020、B=2107/2101（理论 2091），B/A：0.5241 / 0.5226（均 ≤0.65）✓。
- **M6**：`hikaricp_connections_timeout_total` 增量四轮全 0 ✓；`_active` 峰值=10、`_pending` 峰值 16~19（照实披露）；零饿死 ✓。

## 7. 假设与预登记判据的触碰情况

1. 诊断口径可用、A/B 同口径对称成立；彻底弃用 ~2s 排空采样器。
2. 分块标记机制收益被直接量化（`T_mark` /行 由 12.30~21.16 降到 1.76~3.14ms），但这是**机制确证**，非吞吐/延迟结论。
3. **M3 失效机制披露**：A 臂 T_proc 由 16.37→25.96（+58.7%），B 臂由 7.99→5.58（−30.1%），**反向漂移**。同期共变量：`sports_java=4` 恒定，`nonsports_java` 2→0，CPU 5%~93% 波动，第三方容器 `gsproj_mysql_measure`/`gsproj_redis_measure` 在跑；负载 QPS 由 137 单调降到 71~75。漂移无法在预算内归因/消除 ⇒ 不改门、不解释机理。
4. **替补轮使用**：A2 首跑 M4 红（执行侧排空缺陷，非分块标记所致），动用唯一一次同臂替换（A2b）。
5. **执行侧偏差登记（非任务书缺陷）**：`t168` 辅助脚本以 ASCII 写盘致中文 `grep` 模式被替换（A1 的 M1 中文计数首轮误报 0），改用 UTF-8 模式文件 `.trae/tmp/t168-pat-*.txt` **从留档 verify.log 重算**；修正排空等待；并发现 git-bash 下把 `/d/git/Git/bin` 置于 PATH 首部会使 `docker --format` 模板渲染失效（改 `usr/bin`+`cmd` 修复）。上述均为**执行工具链**问题，未触及被测代码/参数。

## 8. 未覆盖项与不得推出的结论

1. spotbugs/pmd 未覆盖（checkstyle 867 阻断）；`--mode=online` 与 CI 未跑 ⇒ 未达外部门槛。
2. **不得**把 2.0497 / 4.6493 / `T_mark` 下降读作「≥1.5× 锁内吞吐收益已证明」：M3 控制门 45.31% 失效、B 臂 35.41% 离差，估计量跨会话方差与效应同量级。
3. **不得**把 `Com_update` 语句数下降（≈5 折）读作端到端改善（提交 QPS 反从 137 漂到 71~75）。
4. 四服务局部栈；池默认 10、单实例、无多实例竞争；未覆盖 RocketMQ 重投/端到端。
5. 不翻案 TASK-144/162/163/164 任何数字；不得与 TASK-164 排空斜率口径并列成优化前后。
6. 未落地 ⇒ `relay-batch-mark-enabled` 默认仍 `false`；后续落地需重做判别并先解决 A 对照臂稳定性。

## 9. 交付物与原始物料

- 判别报告：`docs/perf/判别-outbox-relay-分块标记吞吐.md`
- 机器摘要：`docs/perf/data/exp-outbox-relay-batch-mark.json`（JSON 语法校验通过）
- 本 handoff：`work/mailbox/tasks/TASK-168/handoff.md`；任务书 `work/mailbox/tasks/TASK-168/spec.md`（**零修改**）
- 验收记录：`work/mailbox/PLAN.md`（纯追加 TASK-168 节，19 受保护 token 只增不减）
- 原始物料（gitignored，`docs/perf/data/raw/task168-*`）：每轮 `-verify.log`/`-verify.crlf.txt`/`-diaglines.txt`/`-record.log`/`-c100-summary.json`/`-c100-raw.csv`/`-stats.txt`/`-hikari-peak.txt`/`-round.txt`（label ∈ {W0,W1,A1,B1,A2,A2b,B2}）、进程 `-cmdline.txt`、`task168-m0-docker.txt`、`task168-m0-stack.txt`、`task168-g2-jarsha.txt`、`task168-g2-package.log`、`task168-g1-offline.log`、`task168-g1-static.log`、`task168-diag-summary.txt`、`task168-close-offline.log`、`task168-close-static.log`、`task168-contract-*.txt`。

## 10. 收尾清理与复跑（收口终检）

- **标准清理**：`run-perf.sh stop-services` 停四个 Java 服务后 sports 的 java 进程 = 0；5 演示容器保持 Up(healthy)；`task131-scratch-mysql` 与演示库零触碰。
- **offline 复跑**：`bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0、BUILD SUCCESS，七模块 **36/41/33/103/137/59/10**、Skipped 全 0（verify-service 仍 **137** ⇒ 证零代码改动）；静态门 `--mode=offline --static=verify-service` → rc=1、Checkstyle **867**（≤867，预期形态）。
- **契约门**：第 0 步无参 rc=1（预期）、`--open TASK-168 --baseline=b5e6f85` rc=0；收口后无参 **rc=0**（`两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`，`raw/task168-contract-closure.txt`）。
- **提交**（逐路径 add，无 `git add -A`/`add .`）：C1 = 报告 + 机器摘要；C2 = PLAN.md + TASK-168 两件套。两笔 `git show --check` 均干净、`git diff --check` rc=0；`git diff --name-only b5e6f85..HEAD` 恰 5 路径（= §3 清单）。
- **起点与终态计数**：开工 `origin/main...main = 0 1`；本任务 2 笔 + 既有 `b5e6f85` ⇒ 收口 `0 3`（未 push）。
- **外部门槛**：**未达外部门槛**（本任务不 push；待下次显式授权由 CI 复验）。

**TASK-168 补记（奇偶校验）**：本 §9/§10 为提交后回填，经 `git commit --amend --no-edit` 并入 C2；C1 初版 `f67a401`、C2 初版 `8decd26`，**最终哈希以 `git log --oneline -1` 为准**。收口后重跑无参契约门 rc=0（见上）。