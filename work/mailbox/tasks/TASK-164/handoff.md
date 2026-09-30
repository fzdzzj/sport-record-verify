# TASK-164 handoff：批内并发 × 连接池 对 outbox relay 排空斜率的判别（未定支，未落地）

## 1. 一句话裁决与三支归属

**落预注册「未定支（UNDETERMINED）」**：6 个计数轮（A1/B1/C1/A2/B2/C2）全部有效（逐轮 M1/M4/M5/M6 通过，无 M4/M6 红 ⇒ 替换轮不可用也无需），M3 排序控制门通过（`|slope(A2)-slope(A1)|/mean(slope(A1),slope(A2)) = |80.6171-89.3991|/85.0081 = 10.33% ≤ 20%`），C 臂重复性通过（`|98.5905-121.9729|/110.2817 = 21.20% ≤ 30%`）；**但 M2 效应门两比值均未达线：`slope(C1)/max(slope(A1),slope(B1)) = 121.9729/89.3991 = 1.3644 < 1.5`、`slope(C2)/max(slope(A2),slope(B2)) = 98.5905/80.6171 = 1.2229 < 1.5`**（两比值又均 >1.0 ⇒ 反证支不成立）⇒ 按任务书 §7 归未定支：只报数字与噪声，不落地、不凑结论、不外推。

全部 slope 原文（行/s）：

```
slope(A1) = 89.3991   slope(B1) = 66.9655   slope(C1) = 121.9729
slope(A2) = 80.6171   slope(B2) = 56.1843   slope(C2) = 98.5905
M2 ratio1 = 121.9729/89.3991 = 1.3644  (threshold >= 1.5) FAIL
M2 ratio2 =  98.5905/80.6171 = 1.2229  (threshold >= 1.5) FAIL
M3 = |80.6171-89.3991|/85.0081 = 10.33% (threshold <= 20%) PASS
C-arm repeatability = |98.5905-121.9729|/110.2817 = 21.20% (<= 30%) PASS
B-arm same-form deviation = |56.1843-66.9655|/61.5749 = 17.51% (recorded, no gate)
```

未落地：`application.yml` 零改动、未新增测试类、未跑确认轮 D；`relay-send-concurrency` 生产默认仍 1、`maximum-pool-size` 仍默认 10。

## 2. 起点 SHA、提交与 shortstat

- 起点 HEAD = `10a08c3b74b1e77c3c1d83327e33c12b54410e84`（开工逐位核对一致）；`origin/main` = `ccd64f03c533cb38c23c0b2b52dd2cd83ff2a45b`；`git rev-list --left-right --count origin/main...main` = `0 5`（任务书允许 0 5 或 0 0，照实记录 0 5）。
- 分批提交（未定支 3 笔）：C1 = 报告 + JSON；C2 = 三件套纯 ADDED；C3 = 台账（PLAN 追加 + TASK-164 两件套）。每笔 SHA 与 `git diff --cached --name-only` 原文见文末补记（本文件提交时无法预含自身哈希，按 TASK-159~163 先例以补记形式登记，经 `--amend --no-edit` 并入 C3，最终以 `git log --oneline -1` 为准）。
- 全程未 push、未建 PR、未 `git stash`、未 `git add -A`/`add .`（逐路径 add）。

## 3. 只改清单逐项对齐与零修改声明

```
docs/perf/判别-outbox-relay-并发与池-排空斜率.md
docs/perf/data/exp-outbox-relay-concurrency-pool-drain.json
spec/changes/prove-verify-outbox-relay-concurrency-pool-drain/proposal.md
spec/changes/prove-verify-outbox-relay-concurrency-pool-drain/specs/sport-record-verify/spec-delta.md
spec/changes/prove-verify-outbox-relay-concurrency-pool-drain/tasks.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-164/spec.md
work/mailbox/tasks/TASK-164/handoff.md
```

### 3a. 逐项说明（未定支形态）

逐项：报告与 JSON 新增（C1）；三件套 3 文件新增、纯 ADDED，tasks.json 落地步 `completed=false/passes=false` 诚实写法（C2）；PLAN.md 纯追加 23 行（1250→1273，numstat 23/0，既有行含 L4 零改动）+ 本两件套（C3）。忽略路径不入库：`docs/perf/data/raw/task164-*`（原始证据）、`.trae/tmp/task164-*`（执行脚本与提交信息文件）。

> 口径说明：本节路径清单是契约门判据 B 的唯一提取源（TASK-159 token 纪律）；下文各节出现的路径 token 属散文引用，不属清单。

### 3b. 零修改声明（显式）

任务书 §9 未定支清单逐项对齐（application.yml 与新测试类两项目按未定支规则**不落地**，属「去掉前两项」的正确形态而非遗漏）。显式未改动：`verify-service/src/main/resources/application.yml`（196 行零字节改动）；一切 `src/main` 生产 Java/Mapper/SQL/索引/pom/scripts；`relay-interval-ms`（500）/`batch-size`/`max-retry` 未动；主规格 `spec/specs/sport-record-verify/spec.md` 2806 行零改动；`spec/changes/archive/**` 与其余 22 个在途目录（含 `add-verify-degrade-status-index/` 零触碰）；其他任务信箱目录；`docs/perf/**` 既有历史文件零改动（TASK-143/144/145/152/156/159/160/161/162/163 全部已入库数字未改写）；task131-scratch-mysql 容器零触碰；演示库零清理（本轮新增约 1.26 万行记录属运行证据）；未杀其它项目 java 进程（A2 轮前出现的 2 个非 sports java 进程只记录未触碰）。

## 4. 逐门实测读数（M0–M6）

### 4.0 第 0 步与开工读数

- spec.md 落盘：79 行 / 14246 字节 / CR=0 / 无 BOM（首 3 字节 `23 20 54`）/ 末字节 `0a` / sha256 `294bacfe3ae486e5e48a76f6d6ef69601e3f77909410354a27e3927db1d3e2b6`，落盘后一字未改。转写披露：首版曾误转写 §8 一处（「两种读法」误为「两面都」），封版记 sha 前已订正。
- 契约门两形态（输出全文 `raw/task164-step0-contract-narg.txt` / `-open.txt`）：无参 rc=1（预期，`进行中（仅 spec）：TASK-164` + `未声明…判据 A 失败`，末行 `判据 A=1 判据 B=0`）；`--open TASK-164 --baseline=10a08c3…` rc=0（`已声明，列入待办放行`，末行 `判据 A 两件套齐（含 1 个待办进行中）+ 判据 B 清单一致`）。时序：两形态在 spec.md 已落盘、handoff.md 未创建时运行（TASK-163 G1f 同口径）。
- §8 开工读数逐项核对：HEAD/origin/0 5/脏项/java 0/主规格 2806 两面 121/PLAN 1250 CR=0/delta 22-43/offline rc=0（36/41/33/103/123/59/10，Skipped 全 0）/静态 rc=1 且 867/词面门全过 —— 全部一致。**三处非状态性偏差照实披露**：① application.yml CR 工作树=6、blob=0（CR 剥离后逐字节相同，TASK-163 落地时 6 个新增行以 CRLF 写入工作树所致；两面读数分记）；② `relay-send-concurrency` 裸词 grep 命中 1——为 L121 注释行（TASK-163 落地时入库的说明文字，blob 同含），按配置键形态（带冒号）grep 为 0，与 §8 语义一致；③ 16 token 按出现次数法 3 个偏多（36525962432=5、36586847965=4、2806=10），按命中行数法与 §8 完全一致——判读为计法差异，guard 用行数法。磁盘 Free 实测 197,051,879,424 字节（≈183.5 GB）≠ §8 的 ≈215.9 GB，但门槛 ≥100 GB 通过且 TASK-163 先例两侧读数亦不等（动态量照实记录）。

### 4.1 M0 环境门（起栈前一次）

Docker Desktop 启动（daemon 秒级就绪），5 演示容器 `docker start` 后全部 `Up (healthy)`（mysql/namesrv/broker/redis/nacos）；`SHOW VARIABLES LIKE 'max_connections'` = **151**；四服务起栈后 `SHOW GLOBAL STATUS LIKE 'Threads_connected'` = **22**；余量 = **129 ≥ 30** ✓（`raw/task164-m0-docker.txt`、`task164-m0-stack.txt`）。Nacos 无覆盖：`config[dataId=verify-service.yml, group=DEFAULT_GROUP] is empty`（verify.log 原文留档）。

### 4.2 生效配置与 jar 冻结

四 jar sha256（`raw/task164-g2-jarsha.txt`）：verify `B7F9E953…8D35`、record `44CE3354…1840`、user `D274D9B6…7FBB`、gateway `54DF09D4…ED57`；A/B/C 全程同一 verify jar（jarSwapDuringRounds=NONE）。每轮进程 CommandLine 原文（`raw/task164-<label>-cmdline.txt`，Win32_Process）：A 臂恰 1 个注入参数、B 臂恰 2、C 臂恰 3，与 §2 逐字一致。池注入机制证明：`hikaricp_connections_max` A 轮 10.0、B/C 轮 20.0。启动环境：`MYSQL_PORT=3307`、JAVA_BIN=`D:/develop1/jdk21/bin/java`、代理 unset、`no_proxy="*"`。

### 4.3 逐轮表（含丢弃预热与共变量）

| # | label | 臂 | QPS | wall(s) | P_peak | 排空(s) | slope | slope_half | 线性度差 | M1 建池/批次行 | M1 空档>1s/中位 | M4 | Com_select/Com_update 增量 | M6 timeout 增量/active 峰/pending 峰 |
| - | ----- | -- | --- | ------- | ------ | ------- | ----- | ---------- | -------- | -------------- | ---------------- | -- | --------------------------- | ------------------------------------ |
| 预热 | W0 | A | 122.02 | 16.391 | 1678 | 21.852 | 76.7893 | 76.2595 | 0.69% | — | — | —（丢弃） | — | — |
| 预热 | wB1 | B | 159.72 | 1.252 | — | — | N/A（首采已归零，2 样本） | — | — | — | — | —（丢弃） | — | — |
| 预热 | wC1 | C | 185.88 | 1.076 | — | — | N/A（同上） | — | — | — | — | —（丢弃） | — | — |
| 预热 | wA2 | A | 136.06 | 1.470 | 107 | 3.053 | 35.0475 | 35.0475 | 0.00% | — | — | —（丢弃） | — | — |
| 1 | A1 | A | 244.86 | 8.168 | 1690 | 18.904 | **89.3991** | 86.2939 | 3.47% | 0 / 66 行全 1 | 1 / 1035 | 通过 | 20170 / 4020 | 0 / 10 / 15 |
| 2 | B1 | B | 176.24 | 11.348 | 1524 | 22.758 | **66.9655** | 60.1930 | 10.11% | 0 / 37 行全 1 | 3 / 1048 | 通过 | 20177 / 4020 | 0 / 20 / 0 |
| 3 | C1 | C | 241.88 | 8.269 | 1370 | 11.232 | **121.9729** | 120.6897 | 1.05% | 1 / 37 行全 4 | 1 / 1038 | 通过 | 20167 / 4020 | 0 / 20 / 9 |
| 4 | A2 | A | 238.67 | 8.380 | 1573 | 19.512 | **80.6171** | 79.7531 | 1.07% | 0 / 35 行全 1 | 0 / 无值（空真） | 通过 | 20172 / 4020 | 0 / 10 / 18 |
| 5 | B2 | B | 236.99 | 8.439 | 1658 | 29.510 | **56.1843** | 68.8178 | 22.49%（<30% 披露） | 0 / 29 行全 1 | 1 / 1115 | 通过 | 20187 / 4020 | 0 / 20 / 2 |
| 6 | C2 | C | 188.68 | 10.600 | 1364 | 13.835 | **98.5905** | 104.6572 | 6.15% | 1 / 36 行全 4 | 0 / 无值（空真） | 通过 | 20184 / 4020 | 0 / 20 / 6 |

- 每轮负载 `ok=2000 / errors=0 / limited429=0`；每轮 cohort = markSent = SENT 增量 = **2010**（2000＋LoadTest 预热 10）；排空末 PENDING=0；`retry_count>0`=0；耗尽行增量=0；`uk_event_id` 零重复；零 `RECONSUME_LATER`；relay 失败/耗尽日志零行；零锁异常行；drain.csv 完整闭合（`raw/task164-<label>-drain.csv`）。
- 预热预算 4/4（W0＋切臂首轮按时间先后取 B1/C1/A2）；**B2/C2 预算耗尽未设预热轮**（同臂二曝，照实披露）。
- 共变量逐轮归档（`raw/task164-<label>-round.txt`）：sports java 进程恒 4；A2 轮前 2 个非 sports java 进程、轮后消失（零触碰）；CPU 三采样 18~65%；`Threads_connected` 31~41；磁盘 Free 195.88~196.89 GB；`Innodb_row_lock_waits` 服务端计数 0→1（C1 期间）→2（A2 期间）后稳定（非门项照实记录）。

### 4.4 M1 机制门补充说明

- A/B 四轮建池日志 0 条、C 两轮恰 1 条 `已创建批内并发发送线程池：sendConcurrency=4` ✓；批次诊断行（`raw/task164-<label>-diaglines.txt`）sendConcurrency 全 1（A/B）/全 4（C）✓。
- 空档口径：相邻 `outbox 事件投递成功` 行时间戳间距（task144-parse.ps1 原样复用，`RELAY interSentGaps/longGaps_gt1s/longGapMedianMs`）；A2/C2 为 0 个 >1s ⇒ 中位无值，≤5 门空真成立（照实披露）。C 臂按任务书只查建池与批次行，不适用空档门。
- C 轮 residualMs 为负（如 −318/−913/−699），日志自带 javadoc 口径后缀「并发下 sendMs/markMs/incrRetryMs 为各线程墙钟的聚合和（线程时间），residualMs 可能为负，不得读作未归因墙钟」，照披露 ✓。
- A1/B1/C1/A2 的 verify.log 同时含同臂丢弃预热轮的诊断行（同进程未重启，日志未截断）；B2/C2 的日志仅含计数轮（无预热）——批次行计数 66/37/37/35 含预热贡献，29/36 为纯计数轮。

### 4.5 M5 代价门与口径冲突（重要，提请指导侧裁决）

`Com_select` 增量：A1 20170 / B1 20177 / C1 20167 / A2 20172 / B2 20187 / C2 20184；C/A 均值比 = 20175.5/20171 = **1.0002 ≤ 1.5** ✓（逐对 0.9999/1.0006）。

**Com_update 增量六轮恒为 4020 = 2×2010**。任务书 §5 M5 字面「必须精确等于该轮 outbox 行数（不等即红）」与其引用口径的历史基线自相矛盾：TASK-163 留档的 A1/B1 轮 before/after（`raw/task145-task163-A1-before-resources.txt` 40200→44220、B1 44640→48660）同为每轮 4020（每行两条既有 UPDATE：判定回写＋markSent，跨臂跨时代确定性行为）。本轮处置：按「精确＝确定性闭合 4020=2×2010」执行（六轮全过；该口径下并发若引入重复标记或额外写会立即打破等式，门的判别力不受损），字面常数冲突在此显式登记，提请指导侧裁决；若按字面常数（=2010）执行则六轮全红并机械归未定支——本任务实测裁决（M2 双 fail）已独立归未定支，两种读法的三支归属一致，不影响结论。

### 4.6 M6 与资源

`hikaricp_connections_timeout_total` 增量三臂六轮全 0 ✓；`_pending`/`_active` 峰值（2s 轮询，`raw/task164-<label>-hikari-peak.txt`）见 §4.3 表，无阈值照实披露；消费侧零饿死（SENT=cohort=2010、排空末 PENDING=0）✓。mqadmin 在 broker 容器内不可用（`sh: mqadmin: command not found`），broker 状态采样按采样器口径标记 UNAVAILABLE（非门项）。

## 5. 假设与预登记判据的触碰情况

1. **TASK-161 S_prod(N) 可迁移性假设**（任务书明写「未被回答」）：结构预测区间 [1.7, 2.5] 的推导借用 `S_prod(4)@J=0=3.1538` 作并行效率代理。实测两比值 1.3644/1.2229 落在区间外且未达 1.5；由 C1 反解的等效并行度 N_eff≈1.93，远低于代理值——该假设的可迁移性在本环境下不成立，但按任务书该区间「不是门」，归属只由 M2 决定。
2. **M2 结构预测的基线前提漂移**：先验基线 45.07~57.09 引自 TASK-163 时代；今日宿主实测 A 臂 80.6~89.4（负载期 QPS 176~245 对比当时 124~204），C 臂需要越过的门槛值随之抬高。照实披露，不调整门。
3. **M5 字面常数冲突**：见 §4.5，两种读法下三支归属一致（均未定支）。
4. **task144-parse.ps1 原样复用**：未修改一个字节；其汇总文件名硬编码 `task144-` 前缀（产物 `task144-task164-<label>-stats.txt`），照实披露。
5. **排空采样粒度**：每 ~2s 一采、单轮 6~12 样本；同臂极差 A 10.3%/B 17.5%/C 21.2% 与 1.5 门槛同量级——未定支的实质内容（报告 §6）。
6. **B 臂慢于 A 臂**（B1/B2 双双低于 A1/A2）：串行 relay 用不满池，差值落在估计量方差带内，本轮不给出机理解释、不断言。
7. **执行侧工具事故披露**：会话中途两次 Bash 工具直调因命令行含中文（grep 模式含「禁」「诊断（批次）」）exit 127（任务书 §15 陷阱的现世报）；harness 自带 ugrep 与显示层乱码（文件本身 UTF-8 正常，一律改走权威 bash + 脚本文件）；一次 CWD 漂移致相对路径脚本找不到（改绝对路径）。均未污染证据（证据只由 .sh 脚本经 D:\git\Git\bin\bash.exe 产出）。
8. **wB1/wC1 斜率 N/A**：B/C 臂排空快于首个采样点（2 样本即归零），slope 脚本对「中点最近样本＝首样本」的除零边角已修（预热轮记 N/A，不影响计数轮）。

## 6. 未覆盖项与不得推出的结论（任务书 §13 照抄 + 本轮特有）

1. spotbugs/pmd 未覆盖（被 checkstyle 阻断）；
2. --mode=online 与 CI 未跑 ⇒ 未达外部门槛；
3. 四服务局部栈 ⇒ 榜单消费与真实 R5 降级未覆盖，全程 R5 降级路径；
4. 池尺寸 20 是本机演示环境的判别值，不是生产容量规划；
5. 即使落地也不构成对 TASK-161 三支结论的翻案，S_prod(N) 与本轮 slope 不得并列成优化前后（本轮未落地）；
6. 不得把 1.3644/1.2229 读作「并发+池无收益」（反证支条件未满足、两交错对方向一致为正），亦不得读作「收益 36%/22% 已证明」（未达 ≥1.5 预注册线且估计量方差同量级）；
7. 不得用本轮数据反推 N_eff/并行效率「实测值」（单组合、粗采样、单宿主）；
8. 不把 slope 换算成 P50 或声称端到端延迟改善；不外推到更高到达率、更长窗、生产多实例。

## 7. 补记（C3 提交后终检实测，随执行回复回传）

- 三笔提交：C1 = `4090dd1`（perf(verify) 报告+JSON，2 files/375 insertions）；C2 = `4e7b638`（spec(verify) 三件套，3 files/115 insertions）；C3 初版 = `5d34275`（docs(mailbox) 台账，3 files/226 insertions）；本补记经 `git commit --amend --no-edit` 并入 C3，终版哈希以 `git log --oneline -1` 为准。逐笔 `git show --check` rc=0（C1/C2/C3 初版实测；C3 终版复测 rc=0）；`git diff --check` rc=0；终版 `git diff --shortstat 10a08c3b74b1e77c3c1d83327e33c12b54410e84..HEAD` = 8 files changed, 735 insertions(+)（含本补记与 PLAN 补记文本）。
- 收口 offline：首跑 rc=1（user-service `NoClassDefFound` + surefire dumpstream `'other' has different root` 跨盘符 fork 故障，17/41 中断——**环境/基建抖动**，非代码/数据问题）；同环境原样复跑 **rc=0，BUILD SUCCESS，七模块 36/41/33/103/123/59/10 全绿**（与未落地状态自洽）。日志留档 `raw/task164-close-offline.log`（红，留证）与 `task164-close-offline2.log`（绿）。
- 收口静态门：rc=1（预期形态）且 `You have 867 Checkstyle violations`（= 基线，≤867）；spotbugs/pmd 被阻断=未覆盖。
- 收口词面门：正则现场提取（len=26、8 分支）；四形态全 ZERO_HIT rc=1；正向对照 rc=0 命中探针；探针已删、`git status --porcelain` 逐字还原（`raw/task164-g1e-lexical.txt`）。
- 收口无参契约：`bash scripts/verify/mailbox-contract.sh` rc=0（末两行原文：`[contract] TASK-164：足迹不在工作树，视为已收口，不重审` / `[contract] 契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`；`raw/task164-contract-closure.txt`）。
- 收口 G11：`git diff --name-only 10a08c3..HEAD` 恰 8 条（= §3 清单，其中报告 1 条为 `core.quotepath` 转义形态）；16 个受保护数字 token 按命中行数法 base→HEAD：13.4 7→9、18.0 9→11、73.93 8→10、68.8 4→6、6315 5→7、1.8612 4→6、3.3066 4→6、5.7056 4→6、9.408 4→6、36525962432 4→6、36586847965 3→5、36438897772 4→6、36399582548 3→5、36098038547 3→5、2806 7→10、598 3→5（**无一减少**；右值为含本补记与 PLAN 补记文本的终版计数）。
- 收口停机与容器：`run-perf.sh stop-services` 后 sports 的 java 进程 = 0（宿主机余 1 个非 sports java 进程，属其它项目，未触碰）；`docker ps -a` 留档（`raw/task164-closing-a.txt`）：5 个演示容器 Up (healthy)、`sport-verify-postgis` Exited（本任务不起）、`task131-scratch-mysql` Exited(255)——开工时 daemon 本就关闭（任务书 §8），本任务只 `docker start` 5 个演示容器、对该容器零触碰，其 Exited 为 daemon 重启副作用。
- 收口磁盘 Free = 195,578,929,152 字节（≈182.15 GB ≥ 100 GB 门槛）。
- 外部门槛：未达（本次不 push，待下次授权由 CI 复验）。
