# TASK-114 契约提取盲区微变更（.example 白名单 + README 同步）

## 背景与目标

`mailbox-contract.sh` 的 `extract_claims` 扩展名白名单（L126）不含 `.example`。TASK-110
handoff 已在「改动清单」声明 `scripts/verify/env.example` 却提取不到 → 每次脏树复跑都会被
判据 B 记作"改动集未声明"，属已知盲区（PLAN.md TASK-110 验收记录"指导侧复验收 ①"已注明）。

另 `scripts/verify/README.md` 的 `--open` 示例值需与台账当前待办核对同步——当前台账仅
`TASK-018`、`TASK-106` 为"仅 spec 无 handoff"进行中任务。

## 约束（硬边界）

- 只改白名单 5 文件；`git diff --name-only HEAD` + untracked（排除 `.trae/`）与之完全一致。
- **不动契约判定逻辑**（判据 A/B 的分支、比对、退出码一律不改），仅改提取正则白名单一行。
- 不 push、不建 PR。
- Git Bash 调用（`D:\git\Git\bin\bash.exe`），判据脚本落盘执行以规避中文行 127；临时文件用完即删。

## 只改清单

1. `scripts/verify/mailbox-contract.sh`
2. `scripts/verify/README.md`
3. `work/mailbox/tasks/TASK-114/spec.md`
4. `work/mailbox/tasks/TASK-114/handoff.md`
5. `work/mailbox/PLAN.md`

## 改动内容

- 白名单正则补 `example`（与既有 `d\.ts` 同款多段扩展风格，追加到扩展名单列）。
- README：核对 `--open=TASK-018,TASK-106` 与台账一致（无待改）；判据 B 段补一句白名单含
  `.example` 的说明，使文档与脚本行为同步。

## 红绿判据

- **红**：修正前 `echo 'scripts/verify/env.example' | grep -oE '<原白名单>'` → 0 命中（提取漏实证）。
- **绿**：修正后命中 1 且 `--open=TASK-018,TASK-106` 脏树复跑时 TASK-110 的 `env.example`
  声明可被提取（判据 B 不再以 env.example 报"改动集未声明"）。
- 判据全绿后自行 commit，commit 后契约退出 0、offline 283 零扰动、词面 ZERO-HIT。

## 完成定义

`handoff.md` 写入：只改清单 / 红绿取证（含正则前的 grep 命中数）/ offline 283 零扰动 /
词面 ZERO-HIT / 契约（收口前脏树 1、收口后 0）/ 台账（PLAN.md 追加验收记录，起止行号）。
临时判据脚本用完即删。