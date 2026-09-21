# TASK-114 Handoff

**实现方：执行 agent（收口授权下放，指导侧不再复跑）。未 push、未建 PR。** 开工基线 `86024eb`。

## 只改清单

- scripts/verify/mailbox-contract.sh
- scripts/verify/README.md
- work/mailbox/tasks/TASK-114/spec.md
- work/mailbox/tasks/TASK-114/handoff.md
- work/mailbox/PLAN.md

## 改动内容

- **白名单补 `example`**：`mailbox-contract.sh` L126 `extract_claims` 扩展名正则末尾追加
  `|example`（与既有 `d\.ts` 同款多段扩展风格），其余契约判定逻辑零改动。修掉 TASK-110 遗留的
  已知盲区——handoff 声明 `scripts/verify/env.example` 却提取不到，脏树复跑被误记"改动集未声明"。
- **README 同步**：核对 `--open=TASK-018,TASK-106` 与台账当前待办一致（无待改）；判据 B 段补
  一句白名单含 `.example` 的说明，使文档与脚本行为同步。

## 红绿取证（判据脚本落盘执行，命令行无中文）

- **红（修正前）**：`echo 'scripts/verify/env.example' | grep -oE '<原白名单>'` → `old_hit_count=0`
  （提取漏实证）。
- **绿（修正后）**：同式 → `new_hit_count=1`、命中串 `scripts/verify/env.example`、`eq=1`。
  提取层管道（`extract_claims` 同款 grep/sed/sort）跑 TASK-110 `handoff.md` → 命中唯一目标
  `scripts/verify/env.example`、`env_example_extracted=1`。
- 临时判据脚本位于 `.trae/tmp/`（`.trae/` 属基线允许、契约已排除），已随收口清理。

## 实跑结论（唯一入口 `mvn-verify.sh`）

- `bash scripts/verify/mvn-verify.sh --mode=offline test`：**RC=0 / BUILD SUCCESS**，模块合计
  **17/19/31/80/81/49/6 = 283**，0 失败 0 错误 0 跳过（与基线一致，纯白名单一行 + README 说明零扰动），
  Total time 01:58。

## 词面自检

- CI 原版口径（`git grep` pathspec 三排除 `:!spec/changes/archive/**` `:!docs/internal/**`
  `:!.github/workflows/ci.yml`，禁用词以 `-f` 落盘规避命令行中文）：`wording_rc=1`、`hit_lines=0`
  → ZERO-HIT。

## 契约自证

- 收口前脏树 `bash scripts/verify/mailbox-contract.sh --open=TASK-018,TASK-106` 退出 **1** 属预期：
  TASK-114 自身判据 B 只改清单与实际改动集 5=5 一致；残余为历史 TASK-110/109 对 PLAN.md 等公共
  文件及 env.example 的过冲。
- 收口提交后 ACTUAL 空（仅 `.trae/` 排除，临时判据脚本已删）→ 契约退出 **0**。

## 台账

- `work/mailbox/PLAN.md` 追加《验收记录：契约提取盲区微变更（TASK-114...）》，起始行 **L280**
  （至 L293）。

## 未决 / 分期

- 停止边界内仅白名单一行 + README；不动契约判定逻辑（分支/比对/退出码），无其他未覆盖。
- 未达外部门槛：修订未 push，无 CI run 编号可绑。