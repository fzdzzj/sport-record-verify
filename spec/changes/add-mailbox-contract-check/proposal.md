# 提案：派发—回传契约的可校验化（判据 A 两件套 + 判据 B 清单比对）

## Why

`work/mailbox` 的任务包契约（"只改白名单 + 指定复跑命令 + 停止边界"）目前只作为提示词文字存在：
子 agent 是否真的只改了白名单文件、是否真的回了 spec 与 handoff 两份，没有任何机械校验
（`docs/internal/项目概览-agent接手-2026-09-21.md` 的『可接手事项 1』明列 "唯一未闭环的评审发现"）。

已核实现状（本机 `git status` 干净，仅 `.trae/` untracked 工具残留）：

- 台账当前状态 `git status` 干净，仅 `.trae/` untracked 工具残留——可安全作为开工基线。
- `work/mailbox/tasks/` 下共 **25 个任务目录**，其中 `TASK-018` 与 `TASK-106` **只有 spec.md 无 handoff.md**（进行中），其余 23 个两件套齐全。`archive/`（历史闭口任务）不归本提案重审——历史措辞与台账素有约定不重写（与仓库既有边界一致：不动 `spec/changes/archive/**`、`docs/internal/**`）。
- 现有归档试点 `spec/changes/archive/add-controlled-verify-entrypoint/` 的三件套（proposal/tasks/spec-delta）可作为本项目"只改清单"口径的参照物。

**期望状态**：任务包契约由可机械执行的校验承接。子 agent 完成任务后，主 agent 可现场复跑一条命令，
确认 (A) 该任务目录两件套完备（或显式列为进行中）；(B) 回传声明的只改清单与工作树相对开工基线的实际改动集一致。

## 判据定义与三处必须论证的点

### a. 脚本落点 —— `scripts/verify/`，台账只追加记录不建并行资产

- 脚本落在 `scripts/verify/mailbox-contract.sh`，与 `mvn-verify.sh` 并列。理由：
  - `scripts/verify/` 已是"验收命令拼写的唯一定义处"目录（根 README 与 CI 均只引用这里），契约校验是验收行为的另一面，放同目录最自然。
  - `scripts/verify/mvn-verify.sh` 是 Maven 唯一入口，**不改其本体**；契约校验不经过 Maven 抽象线，二者职责正交（一个验依赖/用例，一个验台账契约）。
  - 硬约束"只在既有台账上扩展，别新建并行资产"落实到实现：新脚本只**读取并校验** `work/mailbox/tasks/`，回传登记只**追加**到既有 `work/mailbox/PLAN.md`，不新建任何台账类资产（如不建独立的 open-task 登记文件）。

### b. 判据 B 的比对基线如何确定

- 判据 B：回传声明"只改"文件集 与 工作树相对**开工基线**的实际改动集 比对，不一致即失败。
- **基线 = 开工 HEAD，通过 `--baseline <ref>` 传入，默认 `HEAD`**。实际改动集取自：
  - `git diff --name-only --diff-filter=ACMR <baseline>`（已跟踪文件的增改/重命名）
  - `git ls-files --others --exclude-standard`（未跟踪新增）再排除 `.trae/` 这一 IDE 工具残留目录。
- **排除"开工前已存在改动"的办法**（三层）：
  1. 硬边界前置：开工时对有跟踪文件的 `git status` 必须干净（本次 `git status` 实测满足），否则触发停止边界、基线快照原样回报——从源头保证 baseline..工作树 之间只有本任务引入的改动。
  2. `--exclude-standard` 沿 `.gitignore` 过滤掉不入库/本机物（`.m2-repo`、`.env`、`.mvn-settings.xml` 等），它们不进入改动集。
  3. 残余工具目录（`.trae/`）由脚本显式排除，不作为"改动"参与比对。
- 用 git 计算的改动集不可判定（非 git 仓库又未给 `--diff-file`、或 git 命令失败）→ 按退出码 **3** 处理（差异来源不可判定，不得记为通过与用例红），与 `mvn-verify.sh` 的码位语义一致。

### c. open 任务（缺 handoff）的处置口径 —— "进行中豁免 + 显式列待办"

- 结论：**"仅 spec 无 handoff" 视为进行中任务，经 `--open` 显式声明后豁免判据 A；未声明则判据 A 失败；已声明的 open 任务在输出中一律列出待办。** 理由是"豁免 + 显式列示"两半缺一不可：
  - 台账现实是**存在合法进行中任务**（TASK-018/TASK-106），若对"缺 handoff"一律硬判失败，台账会因在途工作而永久红，校验失去"可回归"的意义——这就是"视为 open 豁免"的一侧。
  - 但若只豁免不列示，"缺 handoff"就会被静默吞掉——这恰恰是评审发现里"回传契约无机械校验"的病灶。故 open 任务**必须显式列出待办**，且**必须逐项用 `--open` 声明**，从而把"这个任务回了 / 还没回"变成可见、可审计的状态。
  - 不伪造历史回传补齐：对 TASK-018/TASK-106 不补写 handoff，只列待办；补写行为属停止边界（涉嫌伪造记录），由指导侧另行决定。
- 判据 A 的完整口径：
  - spec + handoff 双全 → "已回传"，通过；
  - 仅 spec → "进行中"，在 `--open` 名单则列待办并放行，否则判据 A 失败；
  - 仅 handoff → "有回传无契约"，判据 A 失败（异常态）；
  - 两者皆无但目录非空 → 判据 A 失败；空目录不判。

## What Changes

- 新增 `scripts/verify/mailbox-contract.sh`（bash，`#!/usr/bin/env bash`）：
  - `--ledger <dir>`（默认 `work/mailbox/tasks`）、`--open <task,...>`、`--baseline <ref>`（默认 `HEAD`）、`--diff-file <path>`（判据 B 实际改动集的外部来源，供临时样例复演非 git 场景）。
  - 判据 A：对台账每个任务目录做两件套完备判定（含 open 豁免 + 列待办）。
  - 判据 B：从回传的「改动清单」节解析只改文件集，与工作树实际改动集比对：
    - 改动集与清单有交叠（本回传在途）→ 要求清单与改动集**完全一致**，多报或少报任一文件 → 失败；
    - 改动集与清单无交叠（历史已收口，足迹不在工作树）→ 视为已收口，不重审历史台账；
    - 差异来源不可判定 → 退出码 3。
  - 退出码：`0` 通过 / `1` 契约或结构不符 / `2` 参数错 / `3` 差异来源不可判定。
- 修改 `scripts/verify/README.md`：补 `mailbox-contract.sh` 用法、判据与退出码语义。
- 新建 `spec/changes/add-mailbox-contract-check/` 三件套：`proposal.md`、`tasks.json`、`specs/sport-record-verify/spec-delta.md`。
- 新建 `work/mailbox/tasks/TASK-109/spec.md` 与 `handoff.md`（沿用台账任务目录惯例；`tasks/` 与 `archive/` 下最大号现为 108，本任务沿用 109）。
- 修改 `work/mailbox/PLAN.md`：按开头收口清单格式追加本变更的验收记录 + open 任务当前集。

**明确不做**：

- 不改 `scripts/verify/mvn-verify.sh`、`.github/workflows/ci.yml`、任何 Java/前端/SQL/compose/Dockerfile。
- 不补写、不修改任何历史任务的 handoff（涉嫌伪造记录）。
- 不新建台账资产（open-task 登记不单独建文件，由脚本 `--open` 参数与 PLAN.md 记录携带）。
- 不 commit、不 push、不建 PR。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — ADDED「派发—回传契约两件套完备」「回传只改清单与工作树比对」。

### 受影响的代码
- 新增 `scripts/verify/mailbox-contract.sh`（契约校验入口，与 `mvn-verify.sh` 并列）。
- 修改 `scripts/verify/README.md`
- 修改 `work/mailbox/PLAN.md`

### 用户影响
- 子 agent 回传后，主 agent 用一行 `bash scripts/verify/mailbox-contract.sh --open=...` 现场复核契约，替代人工比对"只改清单 vs git diff"。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（scripts/verify/README.md、PLAN.md 验收记录）

## 时间线评估

短：纯台账/校验脚本/文档面，单次往返可完成。

## 风险

- **open 任务集合会随台账演进漂移** → 缓解：校验强制"未声明即判 A 失败"，使 open 集变化必须显式更新 `--open` 调用与 PLAN.md，漂移在下次校验中现形。
- **判据 B 文本解析把正文里的仓库路径误当只改文件** → 缓解：只解析「改动清单」节（以 `#+ 只改|改动|文件清单` 为首、下一 `#` 级标题为止），正文引用不进清单；仍误配时以红绿对为准当场调。
- **对历史已收口任务误红** → 缓解：无交叠即跳过重审（契约校验守卫"当下回传在途"，不改为重审史账的工具），规避对既有 handoff 误判与伪造争议。
- **脚本带 `\r` 在 Git Bash 下执行异常** → 缓解：与 `mvn-verify.sh` 同保持磁盘 CRLF 行尾（`core.autocrlf=true`），且实现避免依赖行尾敏感的分词。

## 备注

- 公开文本不写口径禁用词本身、不写本机绝对路径；词面自检判据在本地按 CI 同款 pathspec 复跑，目标范围内 0 命中。
- 脚本只做"两件套 + 清单比对"，失败即停，不吞输出。
- 本变更不触碰 `.gitignore` 的既有三条不入库决定。