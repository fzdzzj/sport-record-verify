# TASK-158：恢复「归档 ⇔ delta 已并入主规格 ⇔ 已列入头部清单」不变式（仅 2 个已被移入 archive 的提案；纯文档，零代码）

## 为何派执行 agent（与 TASK-157 相反）

TASK-157 由指导侧自执行，是因为其核心约束是判断性的（区分「现在时陈述」与「历史陈述」），廉价执行方极易批量改写历史造成不可逆审计污染。
本任务相反：**全部判断已由指导侧完成并落成可脚本化判据**（合并顺序、插入位置、逐字比对基准行号、集合不变式、门禁命令与期望退出码），执行侧只剩「机械文本搬运 + 门槛复跑 + 台账据实填写」。故派执行 agent，收口授权下放、指导侧不复跑，但保留全部判据的事后可核性。

## 触发事实（指导侧 2026-09-28 亲跑一手，执行侧须自行复核后方可采信）

1. 主规格 `spec/specs/sport-record-verify/spec.md`（2699 行，CRLF=2698 / bareLF=0 / 无 BOM）对 `outbox|relay|markSent` **0 命中**（`git grep -c -i` rc=1）。即：已在代码中实现、并经 TASK-131~156 反复度量的整个 outbox 能力，在权威规范里**完全不存在**。
2. 主规格 L47 断言「各提案的 spec-delta 中 ADDED 需求已全部合并进本规范」。实测：`spec/changes/archive/` 磁盘 **42** 个目录、tracked **40** 个、头部清单（L8–L45）**38** 条；差集 4 项：
   - `add-microservice-skeleton`：**合法例外**（主规格 L3 明示本规范由其落地生成，属基线生成而非并入）。
   - `add-sharding-host-parameterization`：**合法例外**（TASK-115 冲突停手未并入，PLAN L356 有案；后由 `add-sharding-host-env-override` 以独立 ADDED 并入，PLAN L379/TASK-117 有案，该目录仅作历史存档）。
   - **`wire-verify-outbox`（未跟踪）**：已被移入 archive，但 delta **未并入**、清单**未登记**、移名**未提交**（工作树 3 个删除侧文件）。
   - **`adopt-native-mq-retry`（未跟踪）**：同上。
   ⇒ **L47 断言当前为假**。本任务目标是把它变**真**（禁止用弱化措辞绕过）。
3. 两个 delta 的实质内容已逐条对代码核实为真（不是照抄旧文档）：
   - `VerifyOutboxRelay.java:83` `@Scheduled(fixedDelayString = "${verify.outbox.relay-interval-ms:5000}"` ⇒ delta「relay 按周期（默认 5s）扫描」成立；
   - `VerifyOutboxRelay.java:54` `${verify.outbox.max-retry:16}` ⇒ delta「超过阈值（默认 16）仅记录告警并保留行」成立；
   - `LeaderboardEventConsumer.java:132` `setMaxReconsumeTimes(MAX_RECONSUME_TIMES)`、:142 注释 `%DLQ%`、判别式 `LeaderboardEventConsumerTest:83 buildConsumer_enablesNativeMaxReconsumeTimes3`（:86 断言 =3）⇒ adopt delta 的「重试上限走 MQ 原生 + 内建死信 + 不自建计数」成立；
   - 反例已查：`VerifyEventOutboxMapper.java:23-25` 的 `retry_count < #{maxRetry}`（TASK-142 取批资格修复）**已在代码**但其 delta 未并入 ⇒ 本轮**不并入**，只在欠账登记里点名（见下），避免范围膨胀。
4. **合并顺序不可颠倒**，且这不是指导侧偏好而是 delta 自带口径：`archive/adopt-native-mq-retry/specs/sport-record-verify/spec-delta.md` L3–L7 原文写明「本 delta 的 MODIFIED 目标态已包含在途变更 wire-verify-outbox 对同一需求（「校验事件与幂等」）的修改」「建议先归档 wire-verify-outbox，避免中间态里出现引用尚不存在需求的场景」。
5. 门禁预检（指导侧已亲跑；执行侧收口时须复跑并回传 rc）：
   - CI 同款词面自检（正则见 `.github/workflows/ci.yml` Public docs wording self-check，三处排除 `spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）在 HEAD `779a293` → **0 命中**；
   - 同一正则对**两个待并入 delta 文件** → **0 命中**（grep rc=1）⇒ 并入主规格（非排除路径）不会新增词面命中；
   - 无参数 `bash scripts/verify/mailbox-contract.sh` 在 HEAD → **rc=0**；
   - `.trae/` 已被 `.gitignore:64` 忽略 ⇒ 临时脚本与证据日志一律落 `.trae/tmp/`，不进 git status。

## 起点与基线

- 起点 HEAD = `779a293835ada6f20f0db24066d0d75a25f8f06f`。开工先 `git rev-parse HEAD` 核对，不一致**即停手回报**，不得自行改基线。
- `git rev-list --left-right --count origin/main...main` = `0	2`（`0f62dbf`、`779a293` 未推送）。**本任务不得 push**（上次授权只覆盖已推送的那批，不延伸到新提交）。
- **已知但本轮不修的陈旧断言（指导侧 2026-09-28 实测）**：`work/mailbox/PLAN.md` L4 仍写「本地 `main` 已 push 至 `origin/main`（`58cd104..de81b59`）… = `0 0`」。该句在写下时为真，但其后的 `0f62dbf`/`779a293` 两笔提交使其**当前为假**（实测 `0 behind / 2 ahead`）。本轮 PLAN.md 只允许**纯追加**（见只改清单第 5 项），**不得改 L4**；但 `handoff.md` 第 6 项必须把这条列为「已知陈旧断言，留待下一次推送批次连同新 CI run 证据一并订正」，且**不得**把 L4 当作「已推送」来推断任何结论。
- 开工脏项快照（只允许其中「6 个删除侧文件 + 2 个 archive 未跟踪目录」被本任务收编，其余原样不动）：
  ` D spec/changes/{adopt-native-mq-retry,wire-verify-outbox}/{proposal.md,tasks.json,specs/sport-record-verify/spec-delta.md}`（6 项）、`?? spec/changes/archive/{adopt-native-mq-retry,wire-verify-outbox}/`、`?? spec/changes/add-verify-degrade-status-index/`。
- 归档移名的内容差异已核清（指导侧 `git hash-object` vs `git rev-parse HEAD:` 逐文件比对）：6 个文件中 **5 个 blob 与 HEAD 逐字一致**，唯一不同的是 `adopt-native-mq-retry/tasks.json`（+9/−5：task 5 四个 step 追加了 HEAD=58cd104 的收口复跑实测证据、`passes` false→true）。⇒ 该差异是**真实证据补录，必须随移名一并提交，不得回退成 HEAD 版本**。

## 只改清单（严格 7 项，全为文档；零 `.java`/`.sql`/`.yml`/`.properties`/pom/`scripts/**` 改动）

1. `spec/specs/sport-record-verify/spec.md` —— **恰 4 个编辑点**（见「并入细则」）。
2. `spec/changes/archive/wire-verify-outbox/tasks.json` —— 追加 1 个「归档阶段」task（2 steps，全 `completed: true`、`passes: true`），照 TASK-127 先例（PLAN L630）。
3. `spec/changes/archive/adopt-native-mq-retry/tasks.json` —— 同上（**在已补录的收口复跑版本之上追加**，不得覆盖那段证据）。
4. git 索引：把 6 个删除侧路径与 2 个 archive 目录（6 文件）逐路径 `git add`（**禁 `-A`/`add .`**），使移名在提交中成形。
5. `work/mailbox/PLAN.md` —— **纯追加** 1 个「## 验收记录：TASK-158 …」节（表头照 L350–L351 或 L623–L624 的两列格式），追加位置在文件末尾；不得改动任何既有行。
6. `work/mailbox/tasks/TASK-158/spec.md` —— 本文件，指导侧已写入，**执行侧不得改写一个字**（如发现事实错误，停手回报，不得自行修正）。
7. `work/mailbox/tasks/TASK-158/handoff.md` —— 执行侧新建（内容要求见「交回物」）。

## 并入细则（逐字判据；行号均为起点 HEAD 的实测值，仅作定位，**以结构规则为准**）

### 第 1 步：并入 `wire-verify-outbox`（→ 提交 C1）

- **ADDED**：把 `archive/wire-verify-outbox/…/spec-delta.md` **L5–L44**（`### Requirement: 判定事件可靠投递` 至 `AND 超过阈值（默认 16）…SHALL NOT 静默丢弃`，含 5 个 Scenario）作为新需求块，插入「## 校验引擎」分组内、紧接合并后的「### Requirement: 校验事件与幂等」块之后、`### Requirement: 规则阈值可配置`（原 L923）之前，块间保留 1 个空行。
- **MODIFIED**：用同文件 **L50–L83**（`### Requirement: 校验事件与幂等` 4 场景版）整段替换主规格 **L897–L921**（L922 空行、L923 下一需求，均不动）。
- 头部清单：在 L45（`add-strict-secret-fail-fast`）之后新增 1 行 `- wire-verify-outbox（判定事件事务内 outbox 与 relay 唯一投递）`。
- 变更历史：在末条（原 L2699 `add-strict-secret-fail-fast`）之后新增 1 条 `- **wire-verify-outbox**：…`，内容取自该提案 proposal/delta 的实质口径（同事务落 PENDING 行、relay 唯一出口、延迟上界=relay 周期、失败保留行不静默丢弃、eventId 写入时生成使重试间幂等键稳定）。

### 第 2 步：并入 `adopt-native-mq-retry`（→ 提交 C2）

- **MODIFIED**：用 `archive/adopt-native-mq-retry/…/spec-delta.md` **L11–L55**（`### Requirement: 校验事件与幂等` 5 场景终态版）整段替换第 1 步刚写入的 4 场景版。**这是同一需求的终态**，替换后主规格该块须与 delta L11–L55 逐字一致。
- 头部清单：在 `wire-verify-outbox` 行之后新增 1 行 `- adopt-native-mq-retry（消费重试与死信改走 RocketMQ 原生）`。
- 变更历史：新增 1 条 `- **adopt-native-mq-retry**：…`，**必须**含：`maxReconsumeTimes=3`（连同首次共最多消费 4 次）、超次进 `%DLQ%<consumerGroup>`、不再投递自建死信 topic `record-verify-events-dlq`、业务代码不自建重试计数键；并记录 delta L3–L7 的合并口径提示（本 delta 按目标态整段书写、建议先归档 wire-verify-outbox）。

### 第 3 步：头部例外说明（随 C2 一并提交）

- 在 L47 断言句**之后新增 1 行**（纯追加，**不改 L47 原文**）：说明 `add-microservice-skeleton`（基线生成提案）与 `add-sharding-host-parameterization`（TASK-115 冲突停手、由 `add-sharding-host-env-override` 独立并入后仅存档）为清单外的两个合法例外。目的：让「archive 目录集合 − 头部清单集合 = 恰这 2 项」成为**自解释**的不变式，后续轮次不再重复发现。

### 硬性顺序与冲突即停

- 顺序固定 wire → adopt（理由见触发事实 4）。若任一步发现 MODIFIED 的目标态与主规格当前文本无法对应（例如「校验事件与幂等」块已被其他改动挪位或改写），**按 TASK-115 冲突即停规则整体停手**（PLAN L356 先例）：不并入、不归档、不提交，把冲突明细回传指导侧。

## 欠账登记（必须原样进 PLAN 验收记录的「未覆盖/后续」栏；执行侧须自行复跑下方脚本核对，不得只抄指导侧数字）

起点 HEAD 下 `spec/changes/` 仍有 **19** 个未归档目录（本任务不动其中任何一个）。指导侧实测状态（steps 完成度 / `passes` 是否全绿 / tracked 文件数）：

| 目录 | steps | passes 全绿 | tracked |
| --- | --- | --- | --- |
| add-verify-degrade-status-index | 0/6 | 否 | 0（未跟踪，从未启动） |
| fix-verify-outbox-poison-head-of-line | 10/10 | 是 | 3 |
| measure-head-bottleneck-attribution | 14/15 | 是 | 3 |
| measure-submit-db-wait-evidence | 13/13 | 是 | 3 |
| measure-submit-pool-capacity | 13/13 | 是 | 3 |
| measure-verify-event-stage-lag | 11/11 | 是 | 3 |
| measure-verify-mark-sent-admin-window | 9/9 | 是 | 3 |
| measure-verify-mark-sent-spring-paired-cost | 10/10 | 是 | 3 |
| measure-verify-outbox-mark-sent-cost | 10/10 | 是 | 3 |
| measure-verify-outbox-mark-sent-server-event | 5/10 | 否 | 3 |
| measure-verify-outbox-relay-cost | 11/11 | 是 | 3 |
| prove-verify-mark-sent-wait-attribution | 10/10 | 是 | 3 |
| prove-verify-outbox-batch-mark-safety | 10/10 | 是 | 3 |
| prove-verify-outbox-mark-sent-attribution | 10/10 | 是 | 3 |
| prove-verify-outbox-mark-sent-spring-wiring | 10/10 | 是 | 3 |
| prove-verify-outbox-relay-concurrency-scaling | 10/10 | 是 | 3 |
| resume-verify-outbox-mark-sent-server-event | 4/9 | 否 | 3 |
| shorten-submit-db-footprint | 17/17 | 是 | 3 |
| update-verify-outbox-relay-delay | 7/8 | 否 | 3 |

即：**15 个已完成但从未并入主规格、从未归档**（含 `fix-verify-outbox-poison-head-of-line`、`shorten-submit-db-footprint` 这类已改代码的实质变更），3 个未全绿，1 个未启动。下一轮合并的两个已知难点必须一并登记：

- **3 深 MODIFIED 链**：需求「relay 可选诊断不得改变可靠投递语义」由 `measure-verify-outbox-relay-cost` ADDED，再被 `measure-verify-outbox-mark-sent-cost` MODIFIED，再被 `prove-verify-outbox-mark-sent-attribution` MODIFIED ⇒ 后续必须按此顺序合并、终态取最后一个 MODIFIED（对应代码事实：`VerifyOutboxRelay.java:61/:65` 的 `relay-diagnostics-enabled:false` 与 `relay-diagnostics-window-ms:10000` 确已实现）。
- **对既有基线需求的 MODIFIED**：`shorten-submit-db-footprint` MODIFIED「轨迹提交幂等」（主规格 L735 起），属提交路径而非 outbox，须单独一轮。
- **下一轮首候选**：`fix-verify-outbox-poison-head-of-line`（10/10 全绿、纯 ADDED 2 条需求、零 MODIFIED ⇒ 冲突风险最低，且能把 outbox 章节补齐到与当前代码一致）。

## 明确不做（红线）

- 不合并上表 19 个目录中**任何一个**的 delta；不动 `spec/changes/add-verify-degrade-status-index/`；不删除任何未跟踪文件。
- **不改 L47 断言句原文**（目标是让它变真）；不改写主规格任何其他需求块；不改任何历史 PLAN 验收记录、不改任何历史 `handoff.md`、不改写 TASK-157 的两处订正。
- 不改任何生产文件与构建/脚本文件；不改 `.gitignore`；不跑 `--mode=online`；不建 PR；**不 push**；不 `git stash`；不 `git add -A`/`git add .`。
- Maven 只经唯一入口 `& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh`，**不得裸用 `mvn`**；本任务无 IT，**不得**创建或改动仓库根 `.mvn/maven.config`（该路径不在 .gitignore，会污染 git status）。
- **命令行全程不得出现中文**（含 grep/sed 正则与提交信息；本机 PowerShell 会 exit 127 或静默剥离）。做法：把中文正则/中文提交信息写进 `.trae/tmp/` 下的 UTF-8 无 BOM 文件再引用，提交信息走 `git commit -F <文件>`。
- 不得把未跑的门禁写成已跑；环境缺失一律如实记「未覆盖」。

## 关键陷阱（指导侧已逐个实测，照做即可，不要自行发明写法）

1. **主规格 worktree 是 CRLF、index 是 LF**（`git ls-files --eol` 实测 `i/lf w/crlf`，`core.autocrlf=true`）。而你**要复制进去的两个 delta 文件是纯 LF**（wire delta：CR=0 / LF=83）。⇒ 从 delta 搬块进主规格时**必须把每行行尾转成 CRLF**，否则 G4 会抓到 bareLF>0（TASK-157 已踩过同一个坑）。
2. **主规格末行 L2699 没有行尾换行符**（末 6 字节 `73 74 2f e3 80 82`，无 `0a`）。追加「变更历史」两条时若直接 append，会把新条粘到 L2699 尾部。按 G3 的口径处理：让文件改为**以换行符结尾**，并把由此产生的 1 行末行伪影在 handoff 里说明。
3. `PLAN.md` 相反：**纯 LF**（CR=0 / LF=1023，`i/lf w/lf`），追加行必须是 LF，不得带 CR。
4. 头部清单条目格式为 `- <变更目录名>（<中文短说明>）`，全角括号；G2 的提取器靠 `^- ` 前缀 + 全角括号截断工作，**不要用半角括号**，否则 G2 计数会错。清单是 L8 起的**连续 bullet 块**（L45 后是空行 L46、断言句 L47），新条目插在 L45 之后、空行之前。
5. 主规格 2699 行里 `### Requirement: 校验事件与幂等` **只出现 1 次**（实测 L897）。若你的编辑让它出现 2 次（例如误把 delta 块整段贴进去而没替换旧块），G1b 的 `occurrences` 会立刻抓到。
6. `spec/changes/archive/` 下这两个目录是**未跟踪**的，工作树对应的 6 个旧路径是**未暂存的删除**。要一次 `git add` 两侧（逐路径）才能让移名在提交中成形；只 add 一侧会留下半截状态。

## 收口门槛（每条都要回传实测退出码/数字，不接受散文）

- **G1 逐字判据（硬）**：主规格「### Requirement: 判定事件可靠投递」整块与 `archive/wire-verify-outbox/…/spec-delta.md` L5–L44 逐字一致；「### Requirement: 校验事件与幂等」整块与 `archive/adopt-native-mq-retry/…/spec-delta.md` L11–L55 逐字一致（用脚本提取比对，回传 rc）。
- **G2 集合不变式（硬）**：头部清单条数 38 → **40**；`comm -23 <(ls -1 spec/changes/archive|sort) <(清单条目|sort)` 恰为 **2 行** = `add-microservice-skeleton`、`add-sharding-host-parameterization`；`comm -13` 输出为**空**。
- **G3 无误删（硬，最强判据）**：`git diff -U0 <起点SHA> HEAD -- spec/specs/sport-record-verify/spec.md` 中，**所有含删除的 `@@` 旧行号区间必须完全落在 [897,921]**（即只有被替换的「校验事件与幂等」旧块可被删），其余 2674 行一字不动。**唯一允许的例外**：主规格末行 L2699 在起点 HEAD 下**没有行尾换行符**（实测末 6 字节 `73 74 2f e3 80 82`，无 `0a`），本任务要求改为**以换行符结尾**（POSIX 合规，且避免以后每次追加都产生伪影），因此 diff 会额外出现 1 行「删除 + 重加」的末行伪影 —— 该行文本必须与新增侧对应行**逐字一致**（只是补了换行符），除此之外不得有任何其他删除。回传全部 `@@` 行原文 + 该末行伪影的说明。
- **G4 行尾完整性（硬）**：用无歧义计数法 `cr=$(tr -cd '\r' < f | wc -c); lf=$(tr -cd '\n' < f | wc -c)`。**不要用 `grep -c $'\r$'`**（指导侧实测该写法在 git-bash 下匹配了全部行，给出 `bareLF=-1`/`-1023` 的假读数）。判据：主规格 `CR == LF`（bareLF=**0**，起点实测 CR=2698/LF=2698）；`work/mailbox/PLAN.md` `CR == 0`（起点实测 CR=0/LF=1023）。两文件均**无 BOM**。
- **G5 offline 双跑零扰动（硬）**：开工基线跑一次、收口跑一次 `bash scripts/verify/mvn-verify.sh --mode=offline test`，两次 rc=**0** 且**逐模块用例数逐位一致**、Failures/Errors/Skipped 全 0；回传两次的模块数字串。
- **G6 词面自检**：CI 同款正则 + 三排除，`LC_ALL=C` 与默认 locale 各跑一次，均 **0 命中**（grep rc=1）。
- **G7 空白与提交**：`git diff --check` rc=**0**；三笔提交各自 `git show --check` 干净。
- **G8 契约**：在途 `bash scripts/verify/mailbox-contract.sh --open TASK-158 --baseline=<起点SHA>` 记录 rc（若 rc=1，须逐条说明过冲**仅**来自 `?? spec/changes/add-verify-degrade-status-index/` 这类既有脏项，不得吞掉其他原因）；三笔提交后**无参数复跑 rc=0**。
- **G9 外部门槛**：不 push ⇒ PLAN 验收记录「是否到达外部门槛」栏必须写「**未达外部门槛**（本次不 push，待下次授权由 CI 复验）」。
- **提交结构**：C1 = 并入 wire-verify-outbox（主规格 3 处 + 其 tasks.json + 移名 3 文件）；C2 = 并入 adopt-native-mq-retry（主规格 3 处 + 其 tasks.json + 移名 3 文件 + 头部例外说明行）；C3 = 台账（`PLAN.md` + `TASK-158/spec.md` + `TASK-158/handoff.md`）。每笔 `git diff --cached --name-only` 只含预期路径，回传三份清单。

## 可直接使用的自检脚本（写到 `.trae/tmp/task158-verify.sh`，UTF-8 无 BOM，用 bash 跑；不要贴到 PowerShell 命令行）

```bash
#!/usr/bin/env bash
# 用法： bash .trae/tmp/task158-verify.sh <起点SHA>
cd /d/code/sports || exit 9
BASE="${1:?need base sha}"
SPEC=spec/specs/sport-record-verify/spec.md
W=spec/changes/archive/wire-verify-outbox/specs/sport-record-verify/spec-delta.md
A=spec/changes/archive/adopt-native-mq-retry/specs/sport-record-verify/spec-delta.md
mkdir -p .trae/tmp
# 需求块提取器：从精确标题行起，遇下一个 "## " / "### " 标题或 "---" 分隔行止，再去掉尾部空行。
# 指导侧已在起点 HEAD 实测其正确性：wire ADDED=40 行、wire MODIFIED=34 行、adopt MODIFIED=45 行、主规格现状=25 行。
blk() { awk -v t="$2" '
  index($0,t)==1 { f=1; print; next }
  f && ($0 ~ /^## / || $0 ~ /^### / || $0 ~ /^---[ \t]*$/) { exit }
  f { print }
' "$1" | awk 'NF{n=NR} {l[NR]=$0} END{for(i=1;i<=n;i++) print l[i]}'; }
R1='### Requirement: 判定事件可靠投递'
R2='### Requirement: 校验事件与幂等'
echo '== G1a ADDED 块（并入后主规格应 = wire delta L5-L44，40 行）=='
blk "$W" "$R1" > .trae/tmp/g1a.txt; blk "$SPEC" "$R1" > .trae/tmp/g1b.txt
echo "G1a delta=$(wc -l < .trae/tmp/g1a.txt)/40 spec=$(wc -l < .trae/tmp/g1b.txt)/40"
cmp .trae/tmp/g1a.txt .trae/tmp/g1b.txt; echo "G1a_cmp_rc=$? expect=0"
echo '== G1b MODIFIED 终态（并入后主规格应 = adopt delta L11-L55，45 行；标题只出现 1 次）=='
blk "$A" "$R2" > .trae/tmp/g1c.txt; blk "$SPEC" "$R2" > .trae/tmp/g1d.txt
echo "G1b delta=$(wc -l < .trae/tmp/g1c.txt)/45 spec=$(wc -l < .trae/tmp/g1d.txt)/45 occurrences=$(grep -c "$R2" "$SPEC")/1"
cmp .trae/tmp/g1c.txt .trae/tmp/g1d.txt; echo "G1b_cmp_rc=$? expect=0"
echo '== G2 集合不变式 =='
awk 'NR<8{next} /^- /{print;next} {exit}' "$SPEC" | sed -e 's/^- //' -e 's/（.*//' | sort > .trae/tmp/g2list.txt
ls -1 spec/changes/archive | sort > .trae/tmp/g2arch.txt
echo "G2 list=$(wc -l < .trae/tmp/g2list.txt)/40 arch=$(wc -l < .trae/tmp/g2arch.txt)/42"
echo 'G2 arch-minus-list (expect exactly 2: add-microservice-skeleton / add-sharding-host-parameterization):'
comm -23 .trae/tmp/g2arch.txt .trae/tmp/g2list.txt
echo 'G2 list-minus-arch (expect empty):'; comm -13 .trae/tmp/g2arch.txt .trae/tmp/g2list.txt
echo '== G3 无误删（含删除的 @@ 旧区间须落在 [897,921]；末行换行符伪影见 spec）=='
git diff -U0 "$BASE" HEAD -- "$SPEC" | grep '^@@'
echo '== G4 行尾（spec 须 CR==LF；PLAN 须 CR==0）=='
for f in "$SPEC" work/mailbox/PLAN.md; do
  cr=$(tr -cd '\r' < "$f" | wc -c); lf=$(tr -cd '\n' < "$f" | wc -c)
  echo "G4 $f CR=$cr LF=$lf bareLF=$((lf-cr))"
done
echo '== G4b EOF 换行符（spec 末字节应为 0a）=='
tail -c 2 "$SPEC" | od -An -tx1
echo '== G6 CI 词面自检双 locale（均须 ZERO_HIT）=='
RE='面试|弹药|大厂|八股|简历|求职|突击|附录 ?A'
for loc in C zh_CN.UTF-8; do
  if LC_ALL=$loc git grep -n -I -iE "$RE" -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml' > .trae/tmp/g6-$loc.txt 2>&1; then
    echo "G6_$loc=HITS lines=$(wc -l < .trae/tmp/g6-$loc.txt)"
  else echo "G6_$loc=ZERO_HIT"; fi
done
echo '== G7 / G8 =='
git diff --check; echo "G7_diffcheck_rc=$? expect=0"
bash scripts/verify/mailbox-contract.sh > .trae/tmp/g8.log 2>&1; echo "G8_contract_noarg_rc=$? expect=0"; tail -2 .trae/tmp/g8.log
echo '== 欠账复核（19 个目录 steps/passes）=='
for d in spec/changes/*/; do n=$(basename "$d"); [ "$n" = archive ] && continue
  python - "$d/tasks.json" "$n" <<'PY' 2>/dev/null || node -e "const j=require('./'+process.argv[1]);let t=0,d=0,p=true;for(const x of j){for(const s of x.steps){t++;if(s.completed)d++}if(x.passes!==true)p=false}console.log(process.argv[2]+' '+d+'/'+t+' allPass='+p)" "$d/tasks.json"
import json,sys
j=json.load(open(sys.argv[1],encoding='utf-8'))
t=sum(len(x['steps']) for x in j); d=sum(1 for x in j for s in x['steps'] if s['completed'])
print(f"{sys.argv[2]} {d}/{t} allPass={all(x.get('passes') is True for x in j)}")
PY
done
```

注 1：`blk()` 已实测正确（40/34/45/25 四个行数与指导侧人工核对的 delta 行号区间逐一对上）。若 cmp 报红，先 `diff` 看差异再判定，**不得为了让 cmp 变绿而改写需求原文**；确属提取器缺陷时改用 `sed -n 'X,Yp'` 行号区间直接比对，并在 handoff 写明所用区间。
注 2：末段「欠账复核」优先用 python，无 python 时回退 node（两者都无则用 `grep -c '"completed": true'` 与 `grep -c '"passes": false'` 粗核并在 handoff 声明口径降级）。**目的是让你自己核出那 19 个目录的状态，不得直接抄指导侧的表**。

## 交回物（`handoff.md` 必含，缺一项即视为未收口）

1. 结论一句话（不变式是否恢复、L47 断言是否已为真）。
2. 起点 SHA 与三笔提交 SHA；每笔的 `git diff --cached --name-only` 原文。
3. 只改清单逐项对齐（7 项，含「未改动」的显式声明）。
4. G1–G9 全部实测输出/退出码原文（含 G3 的 `@@` 行、G5 两次的模块数字串、G2 的 comm 输出）。
5. 欠账登记表的**自行复核结果**（19 个目录的 steps/passes 是否与指导侧数字一致；不一致须列出差异并说明）。
6. 未覆盖项与不得推出的结论（至少：未 push 未过 CI；未合并其余 19 个 delta；主规格仍缺 TASK-142 取批资格与 relay 诊断相关需求；本轮不构成任何性能结论）。

## 指导侧复验收方式

收口授权下放（指导侧不复跑）。指导侧将以 G1–G4 的脚本判据 + 三笔提交的逐字 diff 事后核验；G5 的两次数字串与 G3 的 `@@` 原文是主要抓手。若发现主规格有任何超出 [897,921] 的删除、或清单/例外说明与 archive 目录集合不一致，判定为**契约红**并要求回滚重做。
