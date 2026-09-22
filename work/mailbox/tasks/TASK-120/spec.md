# TASK-120 契约提取盲区第二次复现的修复（.editorconfig 白名单 + README 同步）

## 背景与目标

`scripts/verify/mailbox-contract.sh` 的 `extract_claims` 扩展名白名单（L126）不含
`.editorconfig`。TASK-018 handoff 已在「改动清单」声明 `leaderboard-service/.editorconfig`
却提取不到，在途窗口里被判据 B 记作"改动集未声明"——与 TASK-114 修过的 `.example` 盲区
同源（同一个坑的第二次）。本任务照 TASK-114 的手法补白名单并同步 README 说明。

## 约束（硬边界）

- 只改白名单 5 文件；`git diff --name-only HEAD` + untracked（排除 `.trae/`）与之完全一致。
- **不动契约判定逻辑**（判据 A/B 的分支、比对、退出码一律不改），仅改提取正则白名单一行。
- 不动 `--open` 机制；不 push、不建 PR。
- 红绿取证承 TASK-114 手法：先证明原白名单提取不到（红），再证修正后命中且管道可出该路径（绿）。

## 只改清单

1. `scripts/verify/mailbox-contract.sh`
2. `scripts/verify/README.md`
3. `work/mailbox/tasks/TASK-120/spec.md`
4. `work/mailbox/tasks/TASK-120/handoff.md`
5. `work/mailbox/PLAN.md`

## 改动内容

- 白名单正则补 `editorconfig`（追加到扩展名列表末尾，与既有 `example` 同款风格）。
- README：判据 B 段的白名单说明补 `.editorconfig`；原「已知局限」段改写为"已修 + 两次同源
  盲区（`.example`、`.editorconfig`）的判别样本"，并给出后续新载体类型的自检方法。

## 红绿判据

- **红**：修正前 `echo 'leaderboard-service/.editorconfig' | grep -oE '<原白名单>'` → 0 命中；
  提取层管道（awk 截节 + grep/sed/sort）对 TASK-018 handoff 跑一遍，6 个声明路径只出 5 个。
- **绿**：修正后同式命中 1 且串一致；同管道对 TASK-018 handoff 出全 6 个路径（含
  `leaderboard-service/.editorconfig`）；变异验证（临时回退复现红、还原 sha256/cmp 逐位一致）。
- `bash -n` 语法自检通过；契约脚本改后全量自跑（无参数）退出 0。

## 完成定义

`handoff.md` 写入：只改清单 / 红绿取证（含 grep 命中数与管道前后对比）/ 变异验证 /
`bash -n` / offline 全量汇总（纯脚本+文档，零扰动）/ 契约退出码 / 台账（PLAN.md 追加验收记录）。
临时判据脚本与副本用完即删（`.trae/tmp/task120/`）。
