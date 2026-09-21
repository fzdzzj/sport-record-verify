# TASK-109 派发—回传契约的可校验化 · 回传

## 只改清单

| 文件 | 动作 | 位置 |
| --- | --- | --- |
| `scripts/verify/mailbox-contract.sh` | 新建 | 契约校验入口（判据 A 两件套 + 判据 B 清单比对），全文件 |
| `scripts/verify/README.md` | 修改 | L9 文件表补行 · L81-114 新增「派发—回传契约校验」节 |
| `spec/changes/add-mailbox-contract-check/proposal.md` | 新建 | 三件套之提案，全文件 |
| `spec/changes/add-mailbox-contract-check/tasks.json` | 新建 | 三件套之任务拆解，全文件 |
| `spec/changes/add-mailbox-contract-check/specs/sport-record-verify/spec-delta.md` | 新建 | 三件套之规范差异（EARS·判据 A/B），全文件 |
| `work/mailbox/tasks/TASK-109/spec.md` | 新建 | 本任务契约，全文件 |
| `work/mailbox/tasks/TASK-109/handoff.md` | 新建 | 本回传，全文件 |
| `work/mailbox/PLAN.md` | 修改 | L210-220 追加本变更验收记录 |

## 红绿取证

- **红 A**（临时目录 `trialA/RTN`，仅 spec 未声明 `--open`）→ `[contract] 契约校验失败（退出码 1）：判据 A=1 判据 B=0`，退出 **1**。
- **红 B**（`trialB/RTN`，`--diff-file` 含清单未声明项）→ `[contract] 判据 B 失败…改动集未声明（工作树改动未进只改清单）：ghost-undeclared.md`，退出 **1**。
- **绿**（`trial108/RTN` 拷贝 TASK-108，足迹不在工作树视为已收口）→ `[contract] 契约校验通过（退出码 0）`，退出 **0**。
- **全量绿**（`--ledger=work/mailbox/tasks --open=TASK-018,TASK-106`）→ 退出 **0**，25 目录两件套齐（TASK-018/106 列待办放行）、判据 B 全绿。

## 实跑结论

- `mailbox-contract.sh`：上述四组如实，退出码分别 1/1/0/0，判据 A/B 均达成红绿对。红 pair 在系统临时目录构造，已验证删除，不污染台账与工作树。
- `scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS**，模块用例数汇总：common 17 / gateway 19 / user 31 / record 78 / verify 81 / leaderboard 49 / mapmatch 6 = **281**（相对基线 281 只增不减，未碰坏任何模块）。

## 台账

PLAN.md `## 验收记录：add-mailbox-contract-check` 位于 **L210-220**，按开头收口清单写法：绑定修订（未 commit）、门槛来源（mailbox-contract 退出 0 + mvn offline 281）、**未达外部门槛**、open 集合（TASK-018/TASK-106）、不作伪造、未覆盖、归档与后续（不自行归档）。

## 未决

- 无停止边界触发项（开工时工作树仅有 `?? .trae/` untracked IDE 残留，与本次改动可隔离）。
- 本任务不自行归档：`spec/changes/add-mailbox-contract-check/` 三件套待本变更验收通过后并入主规格并移入 `archive/`，另行派发。
- 临时红绿产物（`.contract-*.log/.exit`、`.mvn-regress.log/.exit`、`contract-trials`、`dbg-contract.sh`）待清理确认（未污染仓库跟踪文件）。