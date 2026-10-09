# TASK-184 fix-contract-extract-claims 契约门提取与在途判定盲区修缮 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-08）；执行：执行侧。结论见 §2，证据见 §5；提交表见 §8（显式哈希）。

## 1. 开工规程核验

- **工作树基线核对全符**：仅两组在途文件「spec/changes/fix-contract-extract-claims/（提案三件套）+ work/mailbox/tasks/TASK-184/spec.md（任务书）」；另 1 笔未推送提交 `f3305a5`（TASK-183 补记登记笔）零触碰、不 push；`git rev-list --left-right --count origin/main...main` 开工 = `0 1`（派发笔入库后 `0 2`，C-01 后 `0 3`，C-02 后 `0 4`）。
- **契约门现状基线核对**：开工无参 rc=1（TASK-184 spec-only 未声明 `--open`，中间态预期）；`--open TASK-184 --baseline=f3305a5` rc=0（判据 A 放行 + 判据 B 一致）。
- **工具面**：bash / git / awk / grep / sed 既有工具面，未引入新解释器；脚本仍 `set -uo pipefail`；`mvn-verify.sh`、`ci.yml`、Java 侧、api/web/sql/pom/compose 全部零触碰。
- **未触发任何停止条件**：无需改判据 A / 退出码 / `mvn-verify.sh`；合成例⑤⑥修复后仍红（鉴别力不降）；offline 480 / static 811 不回归；GNU Awk 行为与设计相符。

## 2. 一句话结论与三支裁决

**提取层（否定句式不入声明、删除全文回退）+ 在途判定层（handoff 基线变动门槛）两层修缮落地，八例矩阵前后对照齐（⑤⑥仍红），真实回归三连 + Java 抽验 480/811 不回归，全门禁绿，判定为 PASSED**（三支见 §2.1）。外部终验待推送后下一次外部门槛 CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | 提取层（列表行 token + 否定句式丢弃 + 删全文回退）+ 在途判定层（基线变动门槛 + `--diff-file` 来源解耦 + 非 git 降级共用并注明）落地；八例矩阵前后对照齐（⑤⑥仍红 = 鉴别力不降）；真实回归三连读数齐；Java offline 480 逐位全绿、static 811 持平；全门禁绿 |
| FAILED | ✗ | 未触发（⑤⑥未变绿，无门禁回归，无基线漂移） |
| 外部终验 | 待推送 | 推送后下一次外部门槛 CI 绿；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

全任务实际足迹 = 派发笔 4 条 + C-01 2 条 + C-02 4 条，分笔如下：

**派发笔（执行侧零改动入库）**：
1. `spec/changes/fix-contract-extract-claims/proposal.md`
2. `spec/changes/fix-contract-extract-claims/tasks.json`
3. `spec/changes/fix-contract-extract-claims/specs/sport-record-verify/spec-delta.md`
4. `work/mailbox/tasks/TASK-184/spec.md`

**C-01（修缮笔）**：
1. `scripts/verify/mailbox-contract.sh`（提取层 + 在途判定层 + 头注释）
2. `scripts/verify/README.md`（契约门专节同步）

**C-02（台账收口笔）**：
1. `spec/changes/fix-contract-extract-claims/tasks.json`（闭环全勾）
2. `work/mailbox/tasks/TASK-184/spec.md`（§7 收口记录纯追加）
3. `work/mailbox/tasks/TASK-184/handoff.md`（本文件）
4. `work/mailbox/PLAN.md`（纯追加 §验收记录）

> 零触碰清单：`mvn-verify.sh`、`ci.yml`、Java 侧一切、api 模块、web、sql、root pom、compose、`.codex/`、`.trae/`（临时区产物不入库）。`git status --porcelain` 收口后为空（临时产物用毕删于 `.trae/tmp/task184/`）。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **`--baseline=1a92f26` 字面 TASK-183 开工态复演不可原样复现**：`1a92f26` 是 TASK-183 派发笔哈希，先于该任务自身 handoff.md 落盘；且本任务收口演进后工作树含 TASK-184 足迹，以该旧基线做 `--diff` 会把整个 TASK-184 工作树计为「实际改动」，TASK-183 handoff 被误判在途而比对失败。 | 语义等价的清洁态复演：`--open TASK-183,TASK-184 --baseline=HEAD` 复演绿（TASK-183「已收口（无基线变动），不重审」+ TASK-184 声明放行），rc=0。这是「清洁工作树 + 声明进行中任务 → 绿」的忠实回归，登记不另开订正笔。 |
| D2 | **词面门正则长度登记口径**：任务书 §5 语义与历史文档各记 26 / 58 字节两种长度；实采 `.github/workflows/ci.yml` 现行为 **58 字节**——八词 alternation，字面量以 `.github/workflows/ci.yml` 现行为准、不在文档复述（沿 TASK-158 教训：入库文档不内嵌词面门正则字面量）。 | 以实采 58 字节为准（本执行沿 CI 现场提取，未硬断言 26）。**注（实时序）**：原「四形态全 ZERO_HIT rc=1…门绿」读数为 D2 行落盘前时点读数，门扫时点早于本行落盘、且文件集未含 handoff，提交态经复核实为红，由本订正笔（C-03）去字面量修复。 |
| D3 | **C-01 修改文件行尾为 CRLF（仓库既存状态）**：`mailbox-contract.sh` 与 `README.md` 在 f3305a5 起即 CRLF（原 .sh blob 101 CR、README 86 CR）；本执行以 `edit` 保留既有行尾，git 以 autocrlf=true 同尾存 blob，未引入行尾回归。 | 既有仓库行尾惯例保持；diff 仅含本次逻辑改动（.sh +65/-26，README +23/-1）；脚本 `bash -n` 过 + 八例矩阵实测过，CRLF 不影响 GNU Awk 提取。 |
| D4 | **阻断项 F1（独立复核发现）**：偏差表 D2 行把词面门正则字面量逐字写进 tracked 文档，而 CI 词面门（未排除 `work/mailbox/**`）在提交态实为红；且逐门实测表声称「四形态全 ZERO_HIT 门绿」与实物矛盾——根因为门扫时点早于 D2 行落盘、或文件集未含 handoff。 | 本订正笔（C-03）处置：删除 D2 行括号内正则字面量，改以「八词 alternation、字面量以 `.github/workflows/ci.yml` 现行为准、不在文档复述」表述；同格「门绿」改述为实时序说明；§7 逐门实测表同步注明实时序。 |

## 5. 实施证据（含验收要点判据）

### 5.1 提取层（`extract_claims` 重写）
- 图层只认清单节**列表行** token：awk 过滤 `cap==1 && /^[[:space:]]*(-|([0-9]+\.))[[:space:]]/{print}`；
- 否定句式整行丢弃：`grep -vE '零触碰|未碰|未触碰|不改|禁触'`；
- **删除全文回退**：删除原 L124 `[ -n "$block" ] || block="$(cat "$hf")"`；小节缺失 → claims 空 → 调用方输出「警示：无清单小节，未整档扫描」并跳过；
- 扩展名白名单正则原样保留（`.example` / `.editorconfig` 等）。

### 5.2 在途判定层（判据 B 前置门）
- 对每个 `returned_dirs` 任务，先判 `handoff.md` 基线变动：`git diff --name-only "$BASELINE" -- "$hf"` 非空 **或** `git ls-files --others --exclude-standard -- "$hf"` 命中 → 在途；两者皆无 → 输出「已收口（回传文件无基线变动），不重审」并 `continue`（**连共占计算都不做**）；
- 在途回传才做既有 both/only_claims/only_actual 比对，失败输出原文保留；
- `--diff-file` 模式：改动集来源用 `--diff-file`，在途判定仍用 git 双查（来源解耦）；非 git 上下文 + `--diff-file`：在途降级为旧共占判定（both>0 即在途）并输出「非 git 上下文：在途判定降级为共占口径」注明行；
- 判据 A 循环、`--open` 解析、退出码聚合（0/1/2/3）原文不动。

### 5.3 合成 fixture 八例矩阵（前后对照，fixture 全落 `.trae/tmp/task184/` 用毕删）
构造选择预注册：例①③④⑤⑥⑦用「工作树内真实临时 handoff」在非忽略路径 `task184-fixture-ledger/`（gitignored 之外的临时根，用毕删）构造在途态（untracked → git ls-files --others 命中 → 新脚本判在途）；例②用真实台账 + 手工 `--diff-file` 复演 TASK-182 C-02 假阳性场景。旧版对照脚本 `old.sh` 以 `git show f3305a5:scripts/verify/mailbox-contract.sh` 提取直跑，禁 stash / checkout。

## 6. token 前后读数

受保护 29 项开工实测 SUM=**1652**（逐项 `git grep -cF`，29 项全在）；C-01 后 repo 全量 SUM=**1681**（只增不减；增量来自头注释与 README 契约门节扩展行）。第 24/25 次 run 号（`37779966984` / `37789544509`）以文本登记，不扩受保护集合。**收口真值订正（N2，本订正笔 C-03）**：原记 1681 为 C-01 后时点读数误标为收口；HEAD 实测 repo 全量 `git grep -cF` 为 **1885**（≥C-01 后读数，只增不减），以 1885 为收口真值。

## 7. 逐门实测表

| 门 | 读数 | 结论 |
| --- | --- | --- |
| 词面门四形态（ci-exact / C / zh_CN / C.UTF-8） | 该项原读数为 D2 行落盘前时点读数（门扫早于字面量落盘、文件集未含 handoff），提交态经复核实为**红**；由本订正笔（C-03）去字面量后复跑（四形态 ZERO_HIT rc=1 + 探针 HIT rc=0），见 D4 与 C-03 复跑留证。 | C-03 修复后过 |
| tasks.json 语法 | rc=0（TASKS_JSON_OK tasks=2） | 过 |
| git diff --check | C-01 提交前 cached-check rc=0 | 过 |
| 契约门开工无参 | rc=1（TASK-184 spec-only 未声明 `--open`，中间态预期） | 过（预期） |
| 契约门 `--open TASK-184` 修复前口径 | rc=0（gate-preC01.log） | 过 |
| 契约门 `--open TASK-184` 修复后口径 | rc=0（gate-preC02.log） | 过 |
| TASK-183 开工态复演 | `--baseline=HEAD --open TASK-183,TASK-184` rc=0 | 过 |
| Java offline 全量 | `36/41/63/127/144/59/10` = **480** 逐位全绿 rc=0 | 过（不回归） |
| Java static | `--static=record-service` Checkstyle **811 持平** rc=1 预期 | 过（不回归） |
| token 只增不减 | 1652 → 1681 | 过 |
| 新增文件纯 LF 末尾换行 | handoff.md（本文件）EOL_OK | 过 |
| 收口 `git status --porcelain` | 空（临时产物已删） | 过 |

## 8. 提交表（显式哈希）

| 笔 | 哈希 | 主题 |
| --- | --- | --- |
| 派发笔 | `0010d6c58fcb86a9085e6b42445217dcb5404991` | `docs(spec): 派发 TASK-184 契约门盲区修缮提案与任务书` |
| C-01 修缮笔 | `1e1ee967d391f9ea0a7d5dc72299efc400404a23` | `fix(scripts): 契约门提取层收紧与在途判定重构（TASK-184）` |
| C-02 台账笔 | （本笔，C-02 提交后显式哈希见 spec §7.1 回填值） | `docs(mailbox): 登记 TASK-184 契约门修缮验收与台账闭环（TASK-184）` |

父锚定：`git rev-list --left-right --count origin/main...main` 派发后 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`，最终以本回传报告的对外显式计数为准。本表 C-02 自身哈希因自指（handoff 含本表）无法在写文件时预知，以 C-02 提交后的显式哈希为终态（沿 TASK-182/183 手尾先例：回传报告表以最终态为准）。

## 9. 未覆盖项

1. **`--baseline=1a92f26` 字面 TASK-183 开工态复演**：本任务收口演进后工作树不可原样复现（见 D1），以 `--baseline=HEAD` 清洁态等价复演绿登记。
2. **非 git 上下文 + `--diff-file` 降级口径实跑**：本仓工作流不发生在非 git 上下文（仓内均 git），该降级分支仅以静态可读性 + 代码路径登记（登边界不拒跑），未作为主判据。
3. **本机真 broker 往返 / 外部 CI 门**：Java 抽验为回归抽验性质（证脚本改动不回归 480/811），不上真 broker；外部门槛 CI 为外部终验（待推送）。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions）CI 绿为外部终验；红则按签名归因（不带预设、禁重试刷绿）。第 26 次外部门槛的新 run 号以文本登记，不扩受保护 token 集合。
