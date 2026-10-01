# TASK-171 handoff：分块标记落地态之上的批内并发（N=2）稳态判别与落地

> 状态：**已收口（落地支 GO，条件式落地已完成，确认轮 C 全过）**。§1 为偏差登记；§2–§11 为裁决、读数、逐门、落地、交付与收口终检。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。

## 0. 开工规程与门禁

- **开工硬性门禁 1–6 逐位核验全过**：HEAD `898db2b0c4b92e27e4fcf7eaffa752ee88352d76`、`origin/main` `964871c17b7b9d707fa3a2b550b61a181a764db3`、`git rev-list --left-right --count origin/main...main = 0 1`；工作树脏项仅既有 `?? spec/changes/add-verify-degrade-status-index/`（零触碰）+ 本任务目录；offline `36/41/33/103/140/59/10` rc=0（Skipped 全 0）；static `--static=verify-service` rc=1 且 Checkstyle 严格 **862**；词面门四形态（default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）全 ZERO_HIT rc=1；契约门 `--open TASK-171 --baseline=898db2b0…` rc=0。
- 规程：① 记偏差入本文件；② M0 起栈，依 §4 执行 W1 → A1 → B1 → A2 → B2 → A3 → B3；③ 全面从 `verify.log` 批次诊断行提取 `T_proc`，核算 M1~M6；④ 达成落地支且确认轮通过后才改 `application.yml` 与加绑定测试类；⑤ 收尾清理与 offline 复跑，交付本 handoff 与原始物料。
- 任务书 SHA256 `bd09de7b503d56d0738b8a8929fb5da7b36303d68b56b2362e8f1c10a6622fe3` 逐位一致。

## 1. 偏差登记

### 1.1 执行侧偏差（非任务书缺陷，未触及被测代码/参数）

| 项 | 现象 | 处置 |
| --- | --- | --- |
| git-bash stdout 不被宿主 Shell 捕获 | `D:\git\Git\bin\bash.exe <script>` 执行成功但 stdout 为空、退出码常为 1 | 全部脚本改为**写文件 + Read 读回**；不使用 `bash.exe -c "..."` |
| harness 脚本 `cd` 相对路径错位 | 脚本置于 `docs/perf/data/raw/`，`cd "$(dirname "$0")/../.."` 落到 `docs/` | 统一改为 `cd /d/code/sports` |
| Hikari 浮点触发 shell 算术崩溃 | `hikaricp_connections_timeout_total` 返回 `0.0`，`$((A-B))` 报 `invalid arithmetic operator`，首版轮次中断 | 改为 `${v%%.*}` 整数化 + 非数字兜底 0 后重跑 |
| `diag.sh` M5 解析 bug | `awk -F= '$1==k'` 在同一行多 `key=value` 时取不到 | 改为 `grep -o "$k=[0-9-]" ... cut -d=` 后 M5 通过 |
| 落地后重建 jar 首跑 BUILD FAILURE | `maven-clean-plugin` 无法删除 `gateway-service/target/…jar`（4 个运行中 Java 服务占用，Windows 文件锁） | 先停四服务再重跑 → rc=0、BUILD SUCCESS（非用例红） |

### 1.2 预登记判据的如实披露（非偏差）

- **M3 clause ① 未达、clause ② 达成**：`RSD(A) = 33.14% > 20%`（A3 单轮受宿主抖动上抬至 6.26 ms/行），但平稳基准对 A1A2 展布 14.25% ≤ 20% 且该对 B1/B2 改善比 1.7918 / 1.5763 均 ≥1.5 ⇒ **M3 overall PASS**。`RSD(B) = 4.35% ≤ 25%`。按 §5 预注册综合判定，不放宽。
- **落地后诊断开关**：确认轮 C 为「命令行零注入裸起」，此时 `relay-diagnostics-enabled` 生产默认 `false`，故 C 轮无诊断行；「`sendConcurrency=2` 默认生效」由 `sendConcurrency>1` 时必然输出的 INFO 日志 `outbox relay 已创建批内并发发送线程池：sendConcurrency=2` 确证（该日志不受诊断开关影响）。未放宽任何门。

### 1.3 §8 其余各项（逐位一致）

`application.yml` 200 行；`VerifyOutboxRelay.java` 762 行、L96 `@Value("${verify.outbox.relay-send-concurrency:1}")` 逐位一致；22 个受保护 token 在 PLAN.md 的基线计数（`13.4=15` … `36880083885=3`）与 §8 列值**逐位一致**（本任务 §8 无 TASK-169 那类基线偏低的抄录问题）。

## 2. 一句话裁决与三支归属

**归预注册「落地支（GO）」并已完成条件式落地**：6 个计数轮（A1/B1/A2/B2/A3/B3）逐轮 M1/M4/M5/M6 全过；**M2 效应门通过**（`M2_mean = 2.1274 ≥ 1.5`，且 3/3 对 ≥1.5）；**M3 抗漂移排序控制门通过**（clause ② 平稳对 A1A2 成立；`RSD(B) = 4.35%`）；确认轮 C 全过。落地件：`application.yml` 纯新增 `relay-send-concurrency: 2`、新增绑定测试类 `VerifyOutboxRelaySendConcurrencyDefaultTest`（3 用例）；verify-service offline 测试数 **140 → 143 全绿**。
**反证支检查**：三个改善比全 ≤1.0 ⇒ NO。**预算**：预热轮 2/2（W0/W1，留档丢弃）、计数轮 6/6、替换轮 0/1（未动用）。

## 3. 只改清单

本任务落地支形态下实际改动的 7 条路径（相对开工基线 `898db2b0`）：

```
verify-service/src/main/resources/application.yml
verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelaySendConcurrencyDefaultTest.java
docs/perf/判别-outbox-relay-并发分块标记吞吐.md
docs/perf/data/exp-outbox-relay-concurrency-batch-mark.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-171/spec.md
work/mailbox/tasks/TASK-171/handoff.md
```

显式零触碰：既有脏项 `spec/changes/add-verify-degrade-status-index/`（开工即存在）；`task131-scratch-mysql` 容器与演示库；一切 `src/main` 生产 Java（含 `VerifyOutboxRelay.java`，零改动）、SQL DDL/DML、Mapper XML、`pom.xml`、既有脚本（`scripts/**` 未改，仅执行）、既有测试用例（含 `VerifyOutboxRelayBatchMarkDefaultTest` / `VerifyOutboxRelayBatchMarkConfigTest`，零改动）；主规格 `spec/specs/sport-record-verify/spec.md` 零改动。原始物料 `docs/perf/data/raw/task171-*` 为 gitignored 运行证据。

## 4. 两臂与轮次编排

- **A 臂（当前生产落地态基线 / 串行分块态，N=1）**：`--verify.outbox.relay-diagnostics-enabled=true --verify.outbox.relay-batch-mark-enabled=true --verify.outbox.relay-batch-mark-chunk-size=25 --verify.outbox.relay-send-concurrency=1`。
- **B 臂（候选态 / 并发分块标记态，N=2）**：同上但 `--verify.outbox.relay-send-concurrency=2`。
- **唯一差异＝注入参数**（send-concurrency 1 vs 2）；两臂均带同一诊断开关以消除诊断自身开销的对比偏差；chunk-size=25、batch-size=100、interval=500 不变。
- 两臂同一 jar：`556e75658655aa402bdb15902925d8a2754ae9e3f9743d019c69b41ba8b024fa`（A/B 全程不换 jar，`raw/task171-g2-jarsha.txt`）。
- 编排：**W0**（A，100×2000，丢弃留档）→ **W1**（B，100×200，丢弃留档）→ **A1 → B1 → A2 → B2 → A3 → B3**（各 100×2000）。每臂切换均规范重启 verify-service；启动前 `export MYSQL_PORT=3307`、显式 `JAVA_BIN="D:/develop1/jdk21/bin/java"`、unset 代理并设 `no_proxy="*"`。
- 预算使用：预热轮 **2/2**、计数轮 **6/6**、替换轮 **0/1**（六轮无一红，未动用替补）。
- 每轮负载由 `run-perf.sh load 100 2000 task171-<label>` 驱动（内含 10 条预热请求 ⇒ cohort = 2010 行）。

## 5. 核心读数与 M2/M3（批次诊断口径）

数据源：各轮 `docs/perf/data/raw/task171-<label>-verify.log` 经**行偏移切分**（`mark_before` 之后为该轮纯贡献）后的批次诊断行。
**并发口径注记**：B 臂 `sendConcurrency=2`，诊断行 `markMs / sendMs / incrRetryMs` 为「各线程墙钟的聚合和（线程时间）」，`residualMs` 可能为负；`lockProcessingMs` 仍为锁内墙钟，`T_proc = ΣlockProcessingMs / Σrows` 可跨臂比较。

| 轮 | 臂 | 批次数 | rows | ΣlockProcessingMs | ΣmarkMs | ΣsendMs | **T_proc(ms/行)** | T_mark(ms/行) | R_lock(行/s) | markBatchCalls | markBatchRows |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | A | 35 | 2010 | 7067 | 3052 | 3242 | **3.5159** | 1.5184 | 284.42 | 92 | 2010 |
| B1 | B | 35 | 2010 | 3944 | 3505 | 3283 | **1.9622** | 1.7438 | 509.63 | 108 | 2010 |
| A2 | A | 34 | 2010 | 6127 | 2758 | 2993 | **3.0483** | 1.3721 | 328.06 | 91 | 2010 |
| B2 | B | 35 | 2010 | 3887 | 3420 | 3247 | **1.9338** | 1.7015 | 517.11 | 108 | 2010 |
| A3 | A | 37 | 2010 | 12582 | 8107 | 3005 | **6.2597** | 4.0333 | 159.75 | 93 | 2010 |
| B3 | B | 35 | 2010 | 4285 | 4057 | 3265 | **2.1318** | 2.0184 | 469.08 | 106 | 2010 |
| W0（丢弃） | A | 39 | 2010 | 9495 | 3878 | 4874 | 4.7239 | 1.9294 | 211.69 | 95 | 2010 |
| W1（丢弃） | B | 4 | 210 | 535 | 272 | 597 | 2.5476 | 1.2952 | 392.52 | 11 | 210 |

```
M2_mean = mean(T_proc A)/mean(T_proc B) = 4.274627/2.009287 = 2.1274  (>=1.5) PASS
M2_1 = 3.515920/1.962189 = 1.7918   M2_2 = 3.048259/1.933831 = 1.5763   M2_3 = 6.259701/2.131841 = 2.9363
M2 逐对 >=1.5 的个数 = 3/3（需 >=2）=> PASS
RSD(A) = 33.14%  (M3 clause 1 <=20%) FAIL       RSD(B) = 4.35% (<=25%)
M3 clause 2：|T_proc(A1)-T_proc(A2)|/mean = 14.25% <=20%（平稳对 A1A2），对应 B1/B2 改善比 1.7918/1.5763 均 >=1.5 => 成立
  （|A1-A3|/mean=56.14%、|A2-A3|/mean=69.00% 均超限）
M3 overall（clause1 或 clause2）=> PASS      反证支检查（三比值全 <=1.0）=> NO
```

机制确证量化：`R_lock` 由 A 臂 159.75~328.06 行/s 升至 B 臂 469.08~517.11 行/s（B 臂三轮高度一致，`RSD(B)=4.35%`）；`T_proc` 由 A 臂 3.05~6.26 ms/行 降至 B 臂 1.93~2.13 ms/行。B 臂 `T_mark` 为线程聚合和，不随 N 单向下降。

## 6. 逐门 M0–M6（六轮逐轮硬门全过；M2/M3 亦过）

- **M0 环境门**：Docker daemon up；5 个演示容器（mysql/namesrv/broker/redis/nacos）全部 `Up ... (healthy)`；`max_connections=151`、`Threads_connected` 起栈后 22 ⇒ 余量 129 ≥ 30 ✓（`raw/task171-m0-docker.txt`、`raw/task171-g2-stack.txt`）。
- **M1 机制门（逐轮硬门）**：
  - A 臂（A1/A2/A3）：诊断行 `sendConcurrency=1` 命中数=诊断行数（35/34/37）、`sendConcurrency=2` ZERO_HIT、并发聚合注记 ZERO_HIT、线程池创建日志 ZERO_HIT；批量标记日志 seg 92/91/93 行、`markBatchCalls>0`（92/91/93）且 `markBatchRows` 累计=2010=`total_rows`；逐行投递日志 ZERO_HIT ✓。
  - B 臂（B1/B2/B3）：诊断行 `sendConcurrency=2` 命中数=诊断行数（35）、并发聚合注记 35/35/35、线程池创建日志 1 行；批量标记日志 seg 108/108/106、`markBatchCalls>0`（108/108/106）且 `markBatchRows` 累计=2010；逐行投递日志 ZERO_HIT ✓。
- **M2 效应门**：见 §5，`M2_mean = 2.1274 ≥ 1.5`，逐对 1.7918/1.5763/2.9363（3/3）✓。
- **M3 抗漂移排序控制门**：见 §5，clause ① 33.14% 未达，clause ② 平稳对 A1A2（14.25%）成立且对应 B 比值均 ≥1.5 ⇒ M3 overall PASS；`RSD(B) = 4.35% ≤ 25%`（记录）✓。
- **M4 健康与语义门（逐轮硬门，六轮全过）**：`ok=2000 / errors=0 / limited429=0`；该轮 outbox 行数=2010=SENT 增量=2010、`sent_at` 非空=2010、最终 PENDING=0；`retry_count>0` 行数=0；耗尽行=0；`uk_event_id` 零重复；零 `RECONSUME_LATER`；relay 失败/耗尽日志 0 行；零锁异常行；零批量标记降级告警；批量标记异常 0；零 worker 单行异常隔离告警；并发夹取告警 0 ✓。
- **M5 代价门**：`Com_select` 增量 B/A = 18371/18356 = 1.0008（三轮 18373/18371/18369 vs 18352/18349/18367，均 ∈[0.8,1.2]）✓；`Com_update` 增量 B/A = 2117.3/2102.0 = 1.0073（三轮 2118/2118/2116 vs 2102/2101/2103，均 ∈[0.8,1.2]）✓。
- **M6 资源门（逐轮）**：`hikaricp_connections_timeout_total` 增量六轮全 0 ✓；Hikari `_active` 峰值=10、`_pending` 峰值 A1/B1/A2/B2/A3/B3 = 22/16/13/14/20/15（2s 轮询，照实披露，无阈值）；消费侧无饿死（cohort=SENT=2010、排空末 PENDING=0）✓。

## 7. 落地动作与确认轮

### 7.1 配置落地

- 备份原文件至 `docs/perf/data/raw/task171-application.yml.orig`（orig_bytes=8984 / orig_lines=200）。
- 在 `verify.outbox:` 块下**纯新增**（块内容从 spec.md §6 的 YAML 围栏**现场提取**以保证逐字一致，另加 1 行空行做分隔）：2 行注释 + `relay-send-concurrency: 2`。
- 核验：`git diff --numstat` = `4 0`（纯新增，无删除）；`^verify:` 与 `^spring:` 根键各恰 1 个；总行数 **200 → 204**；`git diff --check` rc=0（`raw/task171-appyml-diff.txt`）。

### 7.2 新增绑定测试类

`verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelaySendConcurrencyDefaultTest.java`（照 `VerifyOutboxRelayBatchMarkDefaultTest` 模板，3 用例，零容器依赖、零 `@SpringBootTest`）：① 用 `YamlPropertySourceLoader` 断言 `verify.outbox.relay-send-concurrency` 为 `2`；② 反射断言 `VerifyOutboxRelay#relaySendConcurrency` 的 `@Value` 字面默认值仍为 `${verify.outbox.relay-send-concurrency:1}`（证生产代码未改默认值）；③ 断言 `^verify:` 与 `^spring:` 根键各恰 1 个。

### 7.3 确认轮 C

- 用 `--mode=offline package` 重建 jar：verify-service sha256 由 `556e7565…b024fa` 变为 **`8f0a05d061f30949d4ca5da282f4b654b093c106e2978341cfed819e0cba4fa3`**（`raw/task171-g3-jarsha.txt`、`raw/task171-g3-package.log`：`BUILD SUCCESS`）。
- offline 测试数：七模块 `36/41/33/103/143/59/10`、Skipped 全 0，verify-service **140 → 143**（新类 3 用例全绿，`raw/task171-g3-testcount.txt`）。
- **C（命令行零注入裸起）**：进程命令行仅 `-jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar`（`raw/task171-C-cmdline.txt`）。verify.log 出现 INFO `outbox relay 已创建批内并发发送线程池：sendConcurrency=2` ⇒ 证明 `application.yml` 默认值已使 `relay-send-concurrency=2` 生产生效；`ok=2000 / errors=0 / limited429=0`；批量标记日志 118 行、逐行投递日志 ZERO_HIT；M4（cohort 2010/2010、末 PENDING=0、`retry_count>0`=0、耗尽=0、零重复、零锁异常、零批量标记降级告警、零 worker 隔离告警）、M5（cs 增量 18411、cu 增量 2128）、M6（timeout 增量 0、`_active` 峰 10、`_pending` 峰 19）全过。
- 说明：任务书 §6「无任何命令行注入」与「核 `sendConcurrency=2`」在本实现下可同时满足（线程池创建 INFO 不受诊断开关影响），故无需补跑仅诊断轮。

## 8. 假设与预登记判据的触碰情况

1. 诊断口径可用，A/B 同口径对称；**彻底弃用 ~2s 排空采样器**：未采 drain.csv、未估端点斜率，`scripts/perf/relay-round-sampler.sh` 全程未执行。
2. 只判别批内并发这一项开关：batch-size 未动、interval 未换档、chunk-size 固定 25。
3. **M3 漂移说明**：A 对照臂三轮 `T_proc` 为 3.52 / 3.05 / 6.26 ms/行，A3 明显上抬（RSD 33.14%）；同期共变量 `sports_java = 4` 恒定、`nonsports_java = 0`、无第三方容器在跑、负载 QPS 两臂同量级（A 200.73~222.18、B 214.46~219.17）。判据按 §5 的 clause ② 兜住（平稳对 A1A2），**不对机理做外推**。
4. **替换轮未动用**（六轮无一 M4 红）；预热轮 2 个按预算全用。
5. 落地后 `relay-send-concurrency` 生产默认由 `1` 变为 `2`；回滚＝删除该 YAML 键（`@Value` 默认值仍为 `1`）或注入 `--verify.outbox.relay-send-concurrency=1`。

## 9. 未覆盖项与不得推出的结论

1. spotbugs/pmd 未覆盖（checkstyle 以既有 862 门槛形态 rc=1）；`--mode=online` 与 CI 未跑 ⇒ **未达外部门槛**（本任务不 push）。
2. **不得**把 `T_proc` 改善比（均值 2.1274）读作端到端吞吐/延迟收益：判别量是**锁内墙钟**口径。
3. **不得**把 B 臂 `markMs/sendMs` 聚合和读作标记/发送耗时上升：那是 N=2 线程时间聚合口径，`residualMs` 可能为负。
4. **不得**把 `Com_update` 增量读作端到端改善：两臂均为 chunk-size 25 分块标记，语句数同量级（≈2100~2118）。
5. 四服务局部栈；池默认 10、单实例、无多实例竞争；未覆盖 RocketMQ 重投/端到端。
6. 不翻案 TASK-144/161/162/163/164/168/169/170 任何数字；不得与 TASK-164 排空斜率口径并列成优化前后。
7. 诊断开关 `relay-diagnostics-enabled` 生产默认仍为 `false`，属测量装置而非落地项。

## 10. 交付物与原始物料

- 判别报告：`docs/perf/判别-outbox-relay-并发分块标记吞吐.md`（裁决 + 两臂轮次 + 逐轮读数 + M2/M3 + 逐门 + 落地与确认轮 + 历史 + 未覆盖）。
- 机器摘要：`docs/perf/data/exp-outbox-relay-concurrency-batch-mark.json`（JSON 语法校验通过，`verdict = LANDED`）。
- 本 handoff：`work/mailbox/tasks/TASK-171/handoff.md`；任务书 `work/mailbox/tasks/TASK-171/spec.md`（零修改）。
- 验收记录：`work/mailbox/PLAN.md`（纯追加 TASK-171 节；22 个受保护 token 只增不减）。
- 落地件：`verify-service/src/main/resources/application.yml`（纯新增 4 行）、`verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelaySendConcurrencyDefaultTest.java`（新增）。
- 原始物料（gitignored，`docs/perf/data/raw/task171-*`）：每轮 `-verify.log` / `-verify.crlf.txt` / `-diaglines.txt` / `-record.log` / `-c100-summary.json` / `-c100-raw.csv` / `-stats.txt` / `-hikari-peak.txt` / `-round.txt`（label ∈ {W0,W1,A1,B1,A2,B2,A3,B3,C}）、`-cmdline.txt`、`task171-m0-{sh,docker,run}.txt`、`task171-g2-{jarsha,stack}.txt`、`task171-g3-{jarsha,package.log,package.rc,testcount}.txt`、`task171-diag-summary.txt`、`task171-appyml-diff.txt`、`task171-application.yml.orig`、harness 脚本与 pat 文件。

## 11. 收尾清理与复跑（收口终检）

- **标准清理**：停四个 Java 服务；5 个演示容器保持 `Up (healthy)`；`task131-scratch-mysql` 与演示库零触碰。无遗留 `docker-compose.override.yml`。
- **offline 复跑**：`bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0、BUILD SUCCESS，七模块 `36/41/33/103/143/59/10`、Skipped 全 0（verify-service **143** ⇒ 证落地生效）。
- **契约门**：开工 `--open TASK-171 --baseline=898db2b0…` rc=0；收口后无参 **rc=0**（两件套齐 + 判据 B 清单一致）。
- **受保护 token**：22 个 token 在 PLAN.md 行命中数 base vs 收口无一减少（base 与任务书 §8 逐位一致）。
- **提交**（逐路径 add；全程无 `git add -A` / `git add .`、无 `git stash`、无 push、无 PR）：见 §12。

## 12. 提交回填

（收口提交哈希见 PLAN.md 验收记录末节与 `git log --oneline`。）