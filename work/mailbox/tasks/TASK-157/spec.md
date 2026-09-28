# TASK-157：订正台账的「外部门槛」口径（push 已达成 + CI 双绿；覆盖面有界；不改写历史 caveat）

## 为何指导侧自执行（不派执行 agent）

本任务只有 2 处文本落点、零代码零构建，但核心约束是**判断性**的：必须区分「当前状态陈述」（可订正）与「各任务收口当时的历史陈述」（**一律不得改写**，改写即伪造审计链）。廉价执行 agent 极易把 `PLAN.md` 10 处 + `机会总览` 6 处历史「未达外部门槛」一并批量替换，造成不可逆的审计污染。故由指导侧自执行，并接受「无独立执行方」这一验证弱化（以契约脚本 rc + 逐字 diff 自证补偿）。

## 触发事实（指导侧实测，2026-09-28）

- 用户显式授权 push（单字「授权」，紧接指导侧上一轮明示的 push 红线与风险量化）。
- `git push origin main` → `58cd104..de81b59  main -> main`，**rc=0**；事后 `git rev-list --left-right --count origin/main...main` = **`0	0`**；`git log -1 origin/main` = `de81b59`。
- GitHub Actions run **`36399582548`**（HEAD `de81b59a2614f93d696e71bd84e93c666e1d766b`，trigger=push，branch=main）→ `gh run view --json status,conclusion` = **`{status: completed, conclusion: success}`**；jobs：`web` ✓ **26s**（ID 108854003738）、`build` ✓ **2m23s**（ID 108854003911）。
- 这是 **TASK-138~156 约 40 笔提交首次通过外部门槛**（上一次 main 的 CI 为 2026-09-25 TASK-137 期，run `36098038547` success）。
- CI 覆盖面据 `.github/workflows/ci.yml` 逐行查实：`build` = `bash scripts/verify/mvn-verify.sh --mode=online verify` + placeholder env + compose 解析检查 + 代表性服务镜像构建 + `bash scripts/verify/mvn-verify.sh --static=leaderboard-service`（checkstyle/spotbugs/pmd）+ 公开文档措辞自检 + JaCoCo 上传；`web` = Node 22 + pnpm frozen-lockfile 安装 + 类型检查 + 构建 + 生成路由类型与已提交一致。
- **CI 不覆盖**（必须写明，防过度宣称）：任何真库 IT（CI 无 MySQL service；`*IT` 不被 Surefire 默认收集；`--it` 分支未被调用且其硬编码清单只含 leaderboard-service 三类）；`verify-service`/`common` 的静态三件套（`--static` 只对 `leaderboard-service` ⇒ TASK-156 新增的 598 行 IT 从未过静态门）；`--mode=offline` 口径；四服务端到端；**一切性能/容量结论**（TASK-152 的 18.0 ms/行 与 73.93%、TASK-156 的 S(N) 仍只有本机 scratch 证据）。

## 只改清单（严格 2 处，均为台账文本）

1. `work/mailbox/PLAN.md` **L4 整行替换**（该行是现在时状态陈述，push 后已在 4 个点上变假：领先 42 笔 / 无 CI / 未推送 / 无异地副本）。新行须含：push 区间与 rc、`0 0` 复核、run ID 与 conclusion、两 job 耗时、**覆盖面清单**、**不覆盖清单**、既有技术叙事（13.4 行/s、37 行/s、59~96 行/s、18.0 ms/行、73.93%、TASK-153/154 NO-GO、TASK-156 S(2)/S(4)/S(8) 与四臂 ms/行 中位、仅必要条件）、以及「逐条验收记录最近一条为 TASK-154，TASK-155~157 详见各自 handoff」。
2. `work/mailbox/后端优化机会总览-2026-09-26.md` **§5「仓库状态与更新约定」末尾追加 1 条 bullet**（纯追加）：记录 push + run 双绿 + 覆盖面有界 + **历史「未达外部门槛」一律保留不改**并说明理由，指明本条为唯一权威订正入口。

## 明确不做（红线）

- **不改写**任何历史条目里的「未达外部门槛」字样（`PLAN.md` 10 处、`机会总览` 6 处，合计 16 处，全部原样保留）——它们是各任务收口当时的真话。
- **不改写**任何历史 `handoff.md`、不改任何 `spec/changes/*`、不触碰既有脏项（6 个归档删除侧文件、`spec/changes/archive/*`、`spec/changes/add-verify-degrade-status-index/`、两个 0/5 在途目录）——属后续规格合并轮。
- 不改任何生产 `.java`/`.sql`/`.yml`/`.properties`，不改任何默认值，不跑 Maven（本任务无代码改动，无需构建门槛），不建 PR，不 `git stash`，不 `git add -A`（逐文件 `git add`），命令行全程无中文（提交信息走 UTF-8 文件 `-F`）。
- **不再次 push**：本次授权只覆盖已推送的 42 笔；TASK-157 自身两笔提交是否推送需用户另行明确授权。

## 收口门槛

- `git diff --check` **rc=0**；两笔本地提交（(1) 订正：PLAN + 机会总览；(2) 台账：TASK-157 spec/handoff）。
- `git diff --name-only <起点>..HEAD` 恰为 4 个文件（PLAN.md、机会总览、TASK-157/spec.md、TASK-157/handoff.md），无任何生产路径。
- caveat 计数取证（脚本化计数，命令行不出现中文）：`PLAN.md` 的「未达外部门槛」由 **10 → 9** —— 被替换掉的 L4 那一处属**现在时状态陈述**（原文「全部『未达外部门槛』、无 CI、未推送、无异地副本」），push 后已变假，属**合法订正**；其余 **9 处历史条目一字不改**。`机会总览` 由 **6 → 7** —— 新增的权威订正条**自身引用**该词（用于声明历史条目保留不改），原有 **6 处历史条目一字不改**。判据是「历史条目零改写」，不是「总计数不减」。
- 两笔提交后无参数 `bash scripts/verify/mailbox-contract.sh` **rc=0**（提交前在途可用 `--open TASK-157`，rc=1 属预期口径）。
- 起点 HEAD = `de81b59a2614f93d696e71bd84e93c666e1d766b`。
