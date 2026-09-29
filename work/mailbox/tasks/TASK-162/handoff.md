# TASK-162 handoff：复测 relay 调度间隔（可重复性口径）并条件式落地（未定支，未落地）

## 1. 结论一句话

**落预注册「未定支」**：预算 6/6 计数轮耗尽，A cell 只拿到 1 个有效计数轮（A1；A2/A3/A4 因 V6 可比性失败）⇒「A cell ≥2 有效轮」不成立，改善门（要求「两轮有效 A 中较好者」）**无法计算** ⇒ **不改任何默认值、未落地**（`application.yml` 一字未动、无新测试类、无 C 确认轮、无回滚动作；`relay-send-concurrency` 全程 = 1）。

四个关键数字：A1 `callback→SENT` P50 = **55120 ms**；B1 P50 = **19259 ms（−65.06%）**、B2 P50 = **17902 ms（−67.52%）**（相对 A1）；两轮 B 的 P95（31743 / 32340.4 ms）均优于 A1 的 106582.7 ms；四轮 A 提交 QPS 相对基准池 {A1,B1,B2} 中位 136.230 的偏差 = **−12.47% / +37.30% / +24.39% / +50.03%**（限 ±15%）⇒ A2/A3/A4 全部 V6 无效。

**V6 口径歧义不改变裁决**：报告对「全部有效计数轮中位数」枚举 4 种可辩护读法（R1 基准池 / R2 全量中位 / R3 最大不动点 / R4 到达序滚动），**四种读法全部收敛未定支**（明细见报告 §6.1 与本文件 §5 G3）。方向性读数（−65%/−67%）是**未过门观测**，不得作为推荐或落地依据（任务书 §14 反例 1 原样发生并被照章处理）。

## 2. 起点 SHA、三笔提交与 shortstat

- 起点 HEAD = `121273d6cf46fd1e8968d4817f8134d71edffda0`（开工 `git rev-parse HEAD` 逐位核对一致；开工 `git rev-list --left-right --count origin/main...main` = `0	5`；**本任务不得 push**）。
- C1 = `47b5deba9a48cac6ac983f4cb2d1466ff7a4482f`（报告 + 机器摘要）；C2 = `6371c0cf35cfa34e5bf94168ffaf15d02cd6b6ca`（三件套纯 ADDED）；**C3 = 本文件落库时的提交**（按 TASK-159/160/161 先例，本文件无法预含自身哈希；`git log --oneline -1` 与执行回复可见）。
- 每笔入册文件清单——**原文**（提交后 `git diff-tree --no-commit-id --name-only -r <SHA>` 复采，与提交时刻 `git diff --cached --name-only` 的索引快照逐字一致）：

```
C1：
docs/perf/data/exp-outbox-relay-interval-repeatable.json
"docs/perf/\345\244\215\346\265\213-outbox-relay-\350\260\203\345\272\246\351\227\264\351\232\224-\345\217\257\351\207\215\345\244\215\346\200\247.md"

C2：
spec/changes/prove-verify-outbox-relay-interval-repeatable/proposal.md
spec/changes/prove-verify-outbox-relay-interval-repeatable/specs/sport-record-verify/spec-delta.md
spec/changes/prove-verify-outbox-relay-interval-repeatable/tasks.json
```

（C1 第 2 行为 `core.quotepath=true` 下 git 对非 ASCII 路径的 C 式引用转义输出，即报告 `docs/perf/复测-outbox-relay-调度间隔-可重复性.md`；下同。）

- `git diff --shortstat 121273d6..HEAD`（C1+C2 后实测）：` 5 files changed, 592 insertions(+)`；逐文件 numstat：JSON 274/0、报告 178/0、proposal 45/0、spec-delta 33/0、tasks.json 62/0（全部新增、0 删除）。C3 后含台账的全量 shortstat 见文末补记。
- C1/C2 `git show --check` 均 rc=0（原文 log：`.trae/tmp/t162-c1-showcheck.log` / `.trae/tmp/t162-c2-showcheck.log`）。
- 全程未 push、未建 PR、未 `git stash`、未 `git add -A`/`add .`（逐路径 add）。

## 3. 只改清单逐项对齐（未定支：8 条实际改动路径）

```
docs/perf/复测-outbox-relay-调度间隔-可重复性.md
docs/perf/data/exp-outbox-relay-interval-repeatable.json
spec/changes/prove-verify-outbox-relay-interval-repeatable/proposal.md
spec/changes/prove-verify-outbox-relay-interval-repeatable/specs/sport-record-verify/spec-delta.md
spec/changes/prove-verify-outbox-relay-interval-repeatable/tasks.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-162/spec.md
work/mailbox/tasks/TASK-162/handoff.md
```

### 3b. 零修改声明（显式）与逐项说明

任务书 §3 只改清单 7 项逐项对齐：

1. `docs/perf/复测-outbox-relay-调度间隔-可重复性.md`（178 行）—— **新增**（C1）。对齐第 1 项。
2. `docs/perf/data/exp-outbox-relay-interval-repeatable.json`（274 行）—— **新增**（C1）。对齐第 2 项。
3. `verify-service/src/main/resources/application.yml` —— **未产生**（任务书限定「仅当落地支成立」；未定支 ⇒ 未落地，该文件零改动）。对齐第 3 项（条件未触发）。
4. `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayIntervalDefaultTest.java` —— **未产生**（同上，条件未触发，无新测试类）。对齐第 4 项（条件未触发）。
5. `spec/changes/prove-verify-outbox-relay-interval-repeatable/`（proposal 45 行 + spec-delta 33 行 + tasks.json 62 行）—— **新增，纯 ADDED**（C2；delta 只有 `## ADDED Requirements` 两个需求五个场景、无任何 MODIFIED；tasks.json 落地步 `completed=false/passes=false` 不伪绿）。对齐第 5 项。
6. `work/mailbox/PLAN.md` —— **纯追加 1 节**（C3；`git diff --numstat 121273d6..HEAD -- work/mailbox/PLAN.md` 实测 **21 insertions / 0 deletions**，与附录 LF 行数逐位相等；追加前 blob `1e3e8f679e7fcbdca177f1335f55d954ca848fb3` → 追加后 `5ca05de9b827177f85a45a90ccd96a16839aa804`；既有行含 L4 零改动）+ `work/mailbox/tasks/TASK-162/spec.md`（指导侧任务书**原样首次入库，执行侧一个字未改**）+ `work/mailbox/tasks/TASK-162/handoff.md`（本文件新建）。对齐第 6 项。
7. `docs/perf/data/raw/task162-*` 与 `.trae/tmp/*` —— 全部落在 `.gitignore` 既有忽略目录（第 46/64 行口径），**不入库**。对齐第 7 项。

**显式未改动声明（全部零触碰）**：任何生产代码（`VerifyOutboxRelay.java` / `RelayDiagnostics.java` / `VerifyEventOutboxMapper.java` / `VerifyEventProducer.java` / 消费者；`git diff --numstat 121273d6..HEAD -- 'verify-service/src/main/java/**'` 空输出为证）；`application.yml`（含 hikari/rocketmq/任何默认值；`git diff --numstat 121273d6..HEAD -- verify-service/src/main/resources/application.yml` 空输出）；任何 SQL/索引/schema/迁移；任何 pom；`scripts/**`；`.github/**`；`docker-compose*.yml`；主规格 `spec/specs/sport-record-verify/spec.md`（2806 行零改动）；`spec/changes/archive/**`；`spec/changes/` 下其余在途目录（含既有的 `add-verify-degrade-status-index/`，保持未跟踪原样）；其他任务的信箱目录；`docs/perf/**` 既有全部文件（TASK-143/144/145/152/156/161 的报告与 JSON 数字与文字均未改写）；既有测试类（含 `VerifyOutboxRelayTest` 19 用例与 `VerifyOutboxRelayConcurrencyTest`）与既有 10 个 `*IT`。未用 `MAVEN_OPTS`、未建 `.mvn/maven.config`、未改 `mvn-verify.sh`、未裸用 mvn；`relay-send-concurrency` 全程 = 1。

## 4. 逐轮表（含丢弃预热轮）

### 4.1 计数轮（6/6 预算用尽；A1/B1/B2 有效，A2/A3/A4 因 V6 无效）

| # | label | cell | 注入 | QPS | wall(s) | load P50/P95/P99(ms) | 排空(s) | c→S P50 | c→S P95 | c→S P99 | n | 净投递(行/s) | >1s空档/中位(ms) | V1–V7 结论 |
| - | ----- | ---- | ---- | --- | ------- | -------------------- | ------- | ------- | ------- | ------- | - | ------------ | ---------------- | ---------- |
| 1 | A1 | A | 无 | 119.24 | 16.773 | 827.32/1113.22/1314.52 | 126 | 55120 | 106582.7 | 112238.1 | 2010 | 14.40 | 21/5020 | **有效**（V1–V5 过；V6 −12.47% 过） |
| 2 | B1 | B | 500 | 141.56 | 14.128 | 678.46/928.66/1281.52 | 48 | 19259 | 31743 | 33061.8 | 2010 | 33.66 | 1/1056 | **有效**（V6 +3.91% 过） |
| 3 | B2 | B | 500 | 136.23 | 14.681 | 687.20/1106.87/1292.69 | 48 | 17902 | 32340.4 | 34208.8 | 2010 | 33.99 | 1/1065 | **有效**（V6 0.00% 过） |
| 4 | A2 | A | 无 | 187.04 | 10.693 | 520.67/637.30/974.31 | 129 | 59272 | 110685.2 | 116226.6 | 2010 | 14.88 | 21/5017 | **无效（V6 +37.30%）** |
| 5 | A3 | A | 无 | 169.46 | 11.802 | 558.11/782.65/1014.47 | 129 | 54387.5 | 110021.1 | 110492.1 | 2010 | 14.94 | 21/5017 | **无效（V6 +24.39%）** |
| 6 | A4 | A | 无 | 204.39 | 9.785 | 475.97/567.75/872.40 | 129 | 59744.5 | 108346 | 113403.1 | 2010 | 15.27 | 20/5018 | **无效（V6 +50.03%）** |

每轮 6 步协议缺一不可，全部 6 轮齐备（原文 `docs/perf/data/raw/task162-<label>-round.txt`）；每轮 `ok=2000 / errors=0 / limited429=0`（V1 全过）；每轮 `callbackToSent.n = 2010`、`cohort.records = cohort.outboxLinked = 2010`（V5 全过）。

### 4.2 丢弃预热轮（不进统计、不进中位数，仅留档）

| label | cell | 注入 | QPS | wall(s) | 排空(s) | callback | 净投递(行/s) | >1s空档/中位(ms) | 备注 |
| ----- | ---- | ---- | --- | ------- | ------- | -------- | ------------ | ---------------- | ---- |
| W0 | A | 无 | 95.13 | 21.024 | 134 | 2007/2010 | 13.69 | 21/5021 | 全局首轮：本窗 7 次 `RecordApiFallback` + 重投（3 条窗内无 callback 线） |
| W_B | B | 500 | 108.01 | 18.517 | 55 | 2010/2010 | 28.78 | 1/2627 | 干净 |
| W_A | A | 无 | 84.09 | 23.784 | 131 | 2004/2010 | 13.58 | 23/5018 | 本窗 254 次 `RecordApiFallback`（`op=getRecord` Feign 读超时）+ 重投（见 §7.2） |

## 5. G0–G13 逐项实测输出与退出码原文

三档退出码与门槛汇总（原文形态，逐项明细见下）：

```
offline 全量 rc=0（G1c：36/41/33/103/120/59/10、Skipped 全 0）
静态门 rc=1（G1d：checkstyle 867 违规；spotbugs/pmd 未覆盖）
词面门 四形态 ZERO_HIT rc=1 + 正向对照 rc=0（G9）
git diff --check rc=0
在途契约 rc=1（自身清单多报 0 条；逐条归因见 G10）
收口后无参契约 rc=0（见文末补记）
--mode=online / CI 未跑 —— 未达外部门槛
```

### G0 起点

```
HEAD = 121273d6cf46fd1e8968d4817f8134d71edffda0（== 任务书，逐位一致）
origin/main...main = 0	5
工作树：仅 ?? spec/changes/add-verify-degrade-status-index/（既有脏项，零触碰）
        与 ?? work/mailbox/tasks/TASK-162/（本任务书目录）
```

### G1a–G1l 开工读数（按任务书 §15 订正口径）

```
G1a 配置开工态：git grep -c "verify.outbox"/"relay-interval-ms"/"maximum-pool-size"
    于 application.yml 三 grep 全 rc=1（0 命中）；grep -c '^verify:' = 1；wc -l = 190
G1b 代码开工态：VerifyOutboxRelay.java L68 batch-size:100、L72 max-retry:16、
    L96 relay-send-concurrency:1、L126–127 @Scheduled 两个默认值 5000/10000、
    L157 单次 selectPendingBatch、无排空循环；文件 493 行
G1c offline 全量：rc=0、BUILD SUCCESS、七模块 36/41/33/103/120/59/10、Skipped 全 0（2:59 min）
G1d 静态门：rc=1（第 2/2 段 checkstyle FAILURE）+ "You have 867 Checkstyle violations"；
    spotbugs/pmd 被阻断在前 = 未覆盖
G1e 词面门：正则现场从 ci.yml 提取 len=26、pipes=7（8 分支）；四形态 ZERO_HIT rc=1；
    正向对照 rc=0 命中探针行；探针已删、status 复原
G1f 契约（订正口径）：
  无参 rc=1 原文（预期，本任务书自身落盘造成）：
    [contract] 进行中（仅 spec）：TASK-162
    [contract]   - 未声明（--open 缺 TASK-162），判据 A 失败
    [contract] 待办进行中任务 1 个：TASK-162
    [contract] 契约校验失败（退出码 1）：判据 A=1 判据 B=0
  在途 --open TASK-162 --baseline=121273d6 rc=0 末行原文：
    [contract] 契约校验通过（退出码 0）：判据 A 两件套齐（含 1 个待办进行中）+ 判据 B 清单一致
  （两种形态全文：.trae/tmp/t162-g1f-open.txt 与 .trae/tmp/t162-g1-summary.md）
G1g git diff --check rc=0
G1h 主规格：2806 行 / CR=2806 / LF=2806 / 末 2 字节 0d 0a / '^### Requirement:' = 121；本任务零改动
G1i PLAN：1194 行 / CR=0 / LF=1194（开工）→ 追加后 1215 行 / CR=0 / LF=1215（收口见补记）
G1j delta 目录：ls -1d spec/changes/*/ | grep -v archive | wc -l = 20；archive = 43（开工）
    → 收口 21 / 43（只新增 prove-verify-outbox-relay-interval-repeatable，收口见补记）
G1k 磁盘（动态量口径）：指导侧 234738380800（20:15）→ 执行侧 234727989248（G1c 前）
    → 234710654976（G1c 后）字节（≈218.6 GiB，门槛 Free ≥100 GB 远未触及）；收口值见补记
G1l 容器开工态：mysql Exited(255)、broker/namesrv Exited(137)、redis Exited(0)、
    nacos Exited(143)、postgis Exited(255)、task131-scratch-mysql Up；只 docker start 前五个
```

### G2 起栈与生效配置

```
容器（21:06:43 只 docker start 五个；未 recreate / 未改配置 / 未删卷；原文 raw/task162-g2-containers.txt）：
  sport-verify-mysql（Up healthy、宿主 3307）、rocketmq-namesrv、rocketmq-broker、redis、nacos；
  sport-verify-postgis 未起；mysqladmin ping = "mysqld is alive"、redis PING = PONG
健康检查（21:11:15，原文 raw/task162-g2-services.txt）：
  8080 → {"status":"UP",...}、8081/8082/8083 → {"status":"UP"}；gateway 路由烟测 1002「无权限访问」（预期）
Nacos 无覆盖（原文 raw/task162-g2-nacos.txt）：
  WARN [Nacos Config] config[dataId=verify-service.yml, group=DEFAULT_GROUP] is empty
四 jar sha256（raw/task162-g2-jarsha.txt）：
  verify  7E45A93FE90B94324749279E86B42D930E5CB03EAB32CFD5A1D33C47B9EDE0CD
  record  C7AF32EF2A359150503EFAEF818D74107B7B5913A1A5998621431C7A39E36970
  user    B1816EC0AD51E4E2D240BE33F597CCA3CA0207D501AEBE1B427B05C544462162
  gateway D2363BEF587869A5F61ED9A9C8577F01BF0499EA36AB78F11678AFA4B9CDC6B9
  A/B 计数轮全程同一 verify jar、sha256 不变、未重建
```

### G3 逐轮 V1–V7（含 V6 四读法）

判据原文（任务书 §5）：V1 `ok=2000/errors=0/limited429=0`；V2 零 `RECONSUME_LATER`、零 `RecordApiFallback`；V3 relay 失败行 = 0 且耗尽行 = 0；V4 轮前可投递/耗尽 PENDING 均 = 0 且轮后排空到 0；V5 `cohort.records == cohort.outboxLinked == callbackToSent.n`；V6 提交 QPS 相对全部有效计数轮（A+B 合并）中位数偏差 ±15% 内；V7 创建形态可比（10 s 桶）。

| label | V1 | V2 | V3 | V4 | V5 | V6（基准池 136.230） | V7 原始桶 | 结论 |
| ----- | -- | -- | -- | -- | -- | -------------------- | --------- | ---- |
| A1 | 过 | 过（本窗 0；字面 7 行属 W0 残留，见 §7.3） | 过（0/0） | 过（前 0/0，126 s 排空） | 过（2010/2010/2010） | **过（−12.47%）** | 75/402/1533 | **有效** |
| B1 | 过 | 过（0/0） | 过（0/0） | 过（前 0/0，48 s 排空） | 过（2010/2010/2010） | **过（+3.91%）** | 96/705/1209 | **有效** |
| B2 | 过 | 过（0/0） | 过（0/0） | 过（前 0/0，48 s 排空） | 过（2010/2010/2010） | **过（0.00%）** | 88/829/1093 | **有效** |
| A2 | 过 | 过（本窗 0；字面 254 行属 W_A 残留） | 过（0/0） | 过（前 0/0，129 s 排空） | 过（2010/2010/2010） | **失败（+37.30%）** | 114/1677/219 | **无效（V6）** |
| A3 | 过 | 过（本窗 0；字面 254 行属 W_A 残留） | 过（0/0） | 过（前 0/0，129 s 排空） | 过（2010/2010/2010） | **失败（+24.39%）** | 107/1394/509 | **无效（V6）** |
| A4 | 过 | 过（本窗 0；字面 254 行属 W_A 残留） | 过（0/0） | 过（前 0/0，129 s 排空） | 过（2010/2010/2010） | **失败（+50.03%）** | 139/1709/162 | **无效（V6）** |

- 基准池 = 替换前完成的前三轮 {A1, B1, B2}（中位 136.230），±15% 带 = [115.795, 156.664]；替换轮与基础第 4 轮一律对同一预注册池判定，不因替换轮加入而重算池（保守读法）。
- V6 自指定义（有效集依赖中位数、中位数依赖有效集）→ 四种读法全算（脚本 `.trae/tmp/t162-v6.py`）：

| 读法 | 规则 | 有效轮结果 | A cell 有效数 | B cell 有效数 | 分支 |
| ---- | ---- | ---------- | ------------- | ------------- | ---- |
| R1（主读法） | 基准池 {A1,B1,B2}（中位 136.230），所有轮对同一池判定 | {A1 −12.47%, B1 +3.91%, B2 0.00%}；A2 +37.30 / A3 +24.39 / A4 +50.03 失败 | 1 | 2 | **未定** |
| R2 | 全部 6 轮一次性中位 155.510 | {B1 −8.97%, B2 −12.40%, A3 +8.97%}；A1 −23.32 / A2 +20.28 / A4 +31.43 失败 | 1 | 2 | **未定** |
| R3 | 最大自洽不动点（子集内所有轮互相 ≤±15%） | 恰两个三元集：{A1,B1,B2}（136.230）与 {A2,A3,A4}（187.040） | 1 或 3 | 2 或 0 | **未定**（取任一集都有一 cell <2） |
| R4 | 按到达序滚动池（每轮对「此前有效轮」判定） | A1 参照；B1 +18.72% 失败、B2 +14.25% 过；A2 +46.43 / A3 +32.67 / A4 +60.01 失败 | 1 | 1 | **未定** |

- V7 通过/失败口径按任务书交指导侧复核（本文件只给原始三桶；已观测桶形与当轮 QPS 同向变化 ⇒ V7 与 V6 失败共因，非独立缺陷）。
- 每轮日志窗口干净性以 `RecordApiFallback`/DUP 的 recordId 与时间戳归属验证：254 行全部落 W_A 窗（21:32:59.820–21:33:15.097，recordIds 68792–69080）；W0 的 7 行落 W0 窗（58777–58871）。

### G4 语义门

| 项 | 9 轮实测 | 结论 |
| -- | -------- | ---- |
| `retry_count > 0` 行数（轮范围 / 全表） | 全 0 / 全 0 | 零重试 |
| 耗尽行增量（`retry_count >= 16` 且 PENDING，轮前 0 → 轮后 0） | 全 0 | 零耗尽 |
| `uk_event_id` 重复报错（`Duplicate entry`） | 全 0 | 零重复 |
| markSent 计数 = 该轮 cohort 行数 | 每计数轮 2010 | 完全闭合 |
| `RECONSUME_LATER` 字面 | 全 0 | 无重投标记 |
| relay 失败行 / 耗尽行 | 全 0 / 全 0 | V3 全过 |

### G5 资源门

```
① hikaricp_connections_timeout_total 增量 = 0：9 轮 before/after 全 0.0
   （另 A3/A4 各 20 个轮内样本亦全 0.0）
② 锁异常 = 0：verify.log 零「锁释放异常」、零「锁获取被中断」（9 轮全 0）
③ pending/active 与净投递并列：规定 before/after 瞬时样本 9 轮全 pending=0.0/active=0.0；
   补充轮内采样（非规定门、仅 A3/A4，各 20 样本）峰值 pending=19.0 / active=10.0（=池上限）
   各出现 1 次（mid3 洪峰期），其余 0–3；A3 netRate 14.94 / A4 netRate 15.27 行/s 并列披露
④ Com_select 增量：A1 20182 / B1 20181 / B2 20186 / A2 20184 / A3 20195 / A4 20193
   （预热 W0 20219 / W_B 20198 / W_A 20958）；B/A 倍数 ≈1.001，与任务书预告「tick 约 10× ⇒ 预期上升」
   不符——如实登记 + 结构性解释：tick 的 select 约每 tick 一次（A≈26 次 vs B≈260 次/窗，差 ~234 次），
   而每轮总增量 ~20180 由负载主路径绝对主导，234 次差异淹没在 ~1.2% 内；10× 是 relay 空扫频率事实
   （空档形态 5020→1056 ms 佐证），不是全局计数器上的可见倍数
⑤ 磁盘：开工序列见 G1k；收口值见文末补记（≈218.6 GiB，≥100 GB）
```

### G6 改善门

- 单轮对照（不进裁决）：B1 P50 19259 ms 较 A1 55120 ms **−65.06%**；B2 P50 17902 ms 较 A1 **−67.52%**；两轮 B 的 P95（31743 / 32340.4 ms）均优于 A1 的 106582.7 ms。
- 预注册改善门要求「两轮有效 A 中较好者」为基准 —— 只有 1 个有效 A 轮 ⇒ **无法计算** ⇒ 改善门未成立（不是「不达标」而是「不可计算」）。
- 四轮 A 的 P50 相对 A1 位移 −1.33%..+8.39%，在 QPS 漂移 +71% 幅度下仍稳定；但 V6 预注册在 QPS 上，不得用「延迟读数对漂移不敏感」豁免 V6。

### G7/G8 落地实现与 C 确认轮

**未执行（未定支）**：任务书 §7.1（YAML 纯新增）/7.2（绑定测试类）/7.3（C 确认轮与回滚）**全套仅落地支执行** ⇒ 本任务不产生 YAML numstat、不产生新测试类、无 C 轮判据、无回滚证据（回滚条款亦无从触发，不存在被写入又需撤销的默认值）。

### G9 词面门

正则现场从 ci.yml 提取（长度 26、字面量不内嵌任何入库文件）；4 形态（ci-exact / `C` / `zh_CN.UTF-8` / `C.UTF-8`）全 ZERO_HIT rc=1；正向对照 rc=0 命中探针后删除。**新登记 harness 事实**：`git grep --untracked` 不搜 ignored 文件（`.trae/**`），正向对照探针必须植在未忽略路径（仓库根），否则对照恒空（执行侧已踩并修正，见 §7.8）。三件套/报告/JSON/TASK-162 目录（`--untracked` 形态）预检 ZERO_HIT。收口态（C1–C3 入库后）重跑见文末补记。

### G10 空白与契约

- `git diff --check` rc=0（工作树与暂存）。
- `git show --check`：C1 `47b5deb` rc=0、C2 `6371c0c` rc=0；C3 见文末补记。
- 在途（C1/C2 已提交、PLAN 已追加、本文件已建、C3 未提交）：`bash scripts/verify/mailbox-contract.sh --open TASK-162 --baseline=121273d6`（权威解释器）→ **rc=1**，末行 `[contract] 契约校验失败（退出码 1）：判据 A=0 判据 B=1`；判据 A=0 原文为「两件套齐全：TASK-162」（本任务已两件套齐、0 个待办进行中）。自身判据 B：**「清单多报」0 条**；「改动集未声明」**5 条** = ① 既有脏项 `?? spec/changes/add-verify-degrade-status-index/` 的未跟踪 4 文件（verification.md / tasks.json / proposal.md / specs/sport-record-verify/spec-delta.md；任务书明令不得触碰，原样保留）② 报告 `docs/perf/复测-outbox-relay-调度间隔-可重复性.md` 1 条——该路径在改动集侧以 `core.quotepath=true` 的 C 式引用转义形态出现（`"docs/perf/\345…\246.md"`），而契约工具的清单 token 类 `[A-Za-z0-9_./-]+\.(ext)` 无法表达非 ASCII 路径 ⇒ 恒无法进入「只改清单」侧；此条系契约 tool 的表达边界（同一文件恰为任务书第 1 项必交件、已在 C1 入库），**非工作树额外改动**。其余判据 B 失败 54 条为历史任务（公共文件 `work/mailbox/PLAN.md` 交叠的既知模式，TASK-127/159/160/161 已登记），另有 21 条「视为已收口」、2 条「未解析到改动清单」跳过（TASK-002/004）；全场判据 A=0（78 个目录全「两件套齐全」，0 个待办/0 个仅 spec）；过冲无一条来自本任务超范围改动（原文 1186 行存 `docs/perf/data/raw/task162-contract-inflight.txt`）。
- 收口后无参复跑 rc=0（原文见文末补记）。

### G11 范围核对与受保护数字

- `git diff --name-only 121273d6..HEAD`（C1+C2 后实测）：5 条 = 报告、JSON、proposal.md、spec-delta.md、tasks.json；收口全量（含 PLAN 与两件套）见文末补记，预期恰为 §3 的 8 条。
- 受保护数字基线计数（BASE = `121273d6` 的 PLAN.md，开工实测）：`13.4`=4、`18.0`=4、`73.93`=5、`68.8`=1、`6315`=1、`1.8612`=1、`3.3066`=1、`5.7056`=1、`9.408`=1、`36525962432`=2、`36438897772`=1；收口 HEAD 计数表见文末补记（纯追加 ⇒ 不得减少）。

### G12 提交

C1/C2 `git show --check` 均 rc=0；各笔 `git diff --cached --name-only` 原文见 §2 清单（提交时刻索引快照）。C3 见文末补记。

### G13 外部门槛

**未达外部门槛**：`--mode=online` 与 CI 未跑（本次不 push，待下次授权由 CI 复验）；offline 绿不得表述为外部门槛绿。

## 6. 未覆盖项与不得推出的结论（照任务书 §11 逐条）

- 四服务局部栈（leaderboard/mapmatch/postgis 未起）⇒ 榜单消费与真实 R5 **未覆盖**，A/B/C 全部在 R5 降级下跑，不得声称全链路。
- 不得把 B 档收益外推到更高到达率、更长时间窗或生产多实例：本轮只证明「四服务局部、单实例、每轮 2010 行、本观测窗」。
- **不得声称任何并发收益**：`relay-send-concurrency` 全程 = 1；不得用 TASK-161 的 `S_prod(N)` 换算本轮任何数字。
- 不得把本轮 P50 与 TASK-152 的 `18.0 ms/行`、TASK-156/161 的 `S(N)`/`S_prod(N)` 并列成「优化前后」（不同实例/装配/窗口）。
- 不得改写 TASK-143/144/145/152/156/161 的任何数字或结论；**TASK-144 的 UNDETERMINED 不被翻案**（本轮是新实验、新预注册、新预算，裁决同样是未定）。
- spotbugs/pmd **未覆盖**（被 checkstyle 阻断在前，G1d rc=1 + 867 violations）；`--mode=online`/CI 未跑 ⇒ **未达外部门槛**。
- 落地未发生 ⇒ 「500 ms 是更好的默认值」**未被本轮证明**；方向性读数（−65%/−67%）是未过门的观测，不得作为推荐或落地依据。
- 演示库新增约 1.3 万行记录与相应轨迹点（含预热轮），是运行证据，**不得清理**。

## 7. 异常、自踩与口径披露（如实，不静默）

1. **MYSQL_PORT 自踩（执行侧，已闭环）**：21:20 首次重启 verify-service 时脚本漏 `export MYSQL_PORT=3307`，新进程连到宿主自带 MySQL 3306（`Access denied for user 'root'@'localhost'`），8083 健康检查 000，进程 PID 20548 于 21:24 被诊断后杀掉（证据 `docs/perf/data/raw/task162-diag-mysql.txt`）。修复：重启脚本固定 `export MYSQL_PORT="${MYSQL_PORT:-3307}"`，21:25:25 重启成功（`task162-restart-b.txt`）。**影响面**：失败的进程从未连上演示库（Access denied），无数据污染；B1/B2 计数轮全部发生在修复之后。
2. **W_A 的 254 次 `getRecord` Feign 读超时 + 重投（被预热设计吸收）**：W_A 负载窗内（21:32:59.8 起）record-service 的 `getRecord` 出现 254 次 `Read timed out` → `RecordApiFallback - record-service 不可用（不可软降级）` → MQ 重投，表现为 254 个 recordId 各多 1 条消费线、6 条窗内无 callback。**全部落在 W_A 预热轮内，未污染任何计数轮**。
3. **A1 的 `fallbackLines=7` 双口径**：A1 复制日志字面含 7 行 `RecordApiFallback`，全部属 W0 窗（recordIds 58777–58871，时间 21:13:48–59 ⊂ W0 负载期）；A1 自身窗口 0 行。报告同时保留两口径。同类：A2/A3/A4 字面 254 行 ⊂ W_A 窗。
4. **日志累积口径**：`logs/verify.log` 只在 verify-service 重启时被 `>` 截断；A1 与 W0 同进程、A2/A3/A4 与 W_A 同进程 ⇒ 复制件含前序轮残留，已逐轮归属。每轮排空后立即复制（6 轮全部满足）。
5. **补充采样不对称**：轮内 `relay-round-sampler` 补充采样只在 A3/A4 做了（各 20 样本）；A1/B1/B2/A2 只有规定的 before/after 瞬时样本。资源门②③的数值范围据此不对称披露。
6. **V6/V7 口径**：V6 自指、V7 交复核——两者的原始数字与多读法结果全部列出，不做单方裁量。
7. **A cell QPS 时漂移未归因**：A2/A3/A4 的 QPS（169–204）显著高于 A1（119），幅度 +42%..+72%；本实验未采样 record-service/宿主侧资源，**归因未取证**（时序上 W_A 拖慢后 A2 立即变快，JIT/缓存回暖是最简猜但未被测量支持）。任何「原因」表述都必须标注为未取证。
8. **工具口径（两条 harness 事实）**：① 权威 bash 的 grep 3.1 在 CRLF 行尾 `$` 锚点上失配（任务书 §0 第 7 条已采纳），所有日志数字提取先 `tr -d '\r'` 规范化；② `git grep --untracked` 不搜 ignored 文件（`.trae/**`）——执行侧第一版正向对照探针植在 `.trae/tmp/` 导致对照恒空（rc=1/hits=0），改为仓库根探针后 rc=0/hits=1 方使词面门有效；该事实已登记，供后续任务复用。
9. **`git add` 的 LF→CRLF warning**：新增文件为纯 LF；`git add` 时 autocrlf 打印 "LF will be replaced by CRLF" 常规提示（工作区保持 LF），提交 blob 实测 CR=0（5 文件全 CR=0）。

## 补记（C3 提交后终检实测，随执行回复回传）

- C3 `git show --check` rc=**0**（初始 C3 = `d4d0f28e37b965b89c9e88d4a167ea5d70a71798`；本补记经 `--amend --no-edit` 并入 C3，最终哈希以 `git log --oneline -1` 为准）；三笔入册清单原文与终版 `git diff --shortstat 121273d6..HEAD` 随执行回复回传指导侧（本文件提交时无法预含自身哈希，按 TASK-159/160/161 先例以补记形式登记）。
- 收口后无参 `bash scripts/verify/mailbox-contract.sh` rc=**0**，末两行原文：`[contract] TASK-162：足迹不在工作树，视为已收口，不重审` / `[contract] 契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`（全文 157 行存 `docs/perf/data/raw/task162-contract-closure.txt`）。
- 收口 G11：`git diff --name-only 121273d6..HEAD` 恰 8 条、与 §3 清单逐条对应（报告 1 条为 quotepath 转义形态）；受保护数字 base→HEAD 全不减（13.4 4→5、18.0 4→6、73.93 5→6、68.8 1→2、6315 1→2、1.8612 1→2、3.3066 1→2、5.7056 1→2、9.408 1→2、36525962432 2→3、36438897772 1→2；右值为含本补记文本的终版计数）。
- 收口词面门 4 形态 ZERO_HIT + 正向对照 rc=0（探针已删）；`git diff --check` rc=0；磁盘收口 Free = 232684974080 字节（≈216.7 GiB，门槛 Free ≥100 GB 远未触及）；同目录 `spec.md` 为任务书原样入库（237 行 / 34268 字节 / sha256 `91c9b95d…0b519`）。
