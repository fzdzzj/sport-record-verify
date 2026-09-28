# TASK-155 回传：口径与规格一致性批（修陈旧/失效断言 + 止未跟踪噪声；不改运行行为）

## 结论

开工 HEAD `6016d920ac473d5ccfbd1e862ef481701ba0233e`（开工 `git rev-parse HEAD` 实核一致）。四件（b）(c1)(c2)(d) 全部按 spec 完成，无一处超范围；**未改任何运行行为**——唯一生产文件改动是 `VerifyOutboxRelay.java` 的**类注释文字**（L29~30 两行 → 三行，无代码语句/签名/注解/默认值改动）。强制 Maven 门槛一次通过：`scripts/verify/mvn-verify.sh --mode=offline --pl verify-service` **rc=0**（verify-service 110/0/0/0、common 36/0/0/0，与既有基线同数）。两笔本地提交（提交 1 `6cd428d8`、提交 2 为台账两件套），**未 push、未建 PR、未 stash、未 `git add -A`**；既有脏项（归档移名 6 文件删除侧、`spec/changes/archive/*`、`spec/changes/add-verify-degrade-status-index/`、两个 0/5 在途目录 `resume-verify-outbox-mark-sent-server-event` / `measure-verify-outbox-mark-sent-server-event`）**一律未触碰**，属 TASK-156。

## 起点核对

- 开工 HEAD `6016d920ac473d5ccfbd1e862ef481701ba0233e` 与任务书逐字一致；收口时本地 `main` 顶端两笔为 `6cd428d8`（本批提交 1）→ 其父即开工 HEAD（`git log --format='%H %P'` 实核）。
- 索引复核：开工时 `git diff --cached --name-status` 为空（无预暂存项）；6 个归档删除侧全部只在下工作树（未暂存）→ 逐文件 `git add` 无被卷入风险。
- 环境：Maven 与契约脚本均走项目约定 Git Bash 入口 `D:\git\Git\bin\bash.exe` 调 `scripts/verify/*`；未裸用 `mvn`。

## 判据与退出码口径（摘要）

- **(b) PLAN.md 一句话**：Grep 复跑 `撤回本会话此前` → **0 命中**；新句含「38 笔」「13.4 行/s」「TASK-154」三词（逐词复跑均命中同一行 L4）；事实复核 `git rev-list --count origin/main..main` = **38**（与句内数字一致）。
- **(c1) relay 类注释**：offline 强制门槛一次通过 → **rc=0 / BUILD SUCCESS / verify-service `Tests run: 110, Failures: 0, Errors: 0, Skipped: 0` / common `36/0/0/0`**，与既有基线同数（注释级改动未改变任何用例数）；日志 `docs/perf/data/raw/task155-01-mvn-verify.log`。
- **(c2) 机会总览**：组合复跑 `18.0 ms/行|73.93%|并发标度` → **4 命中行（L38、L40、L120、L179，≥3）**；既有历史数字计数只增不减：`13.4 行/s` 2→**3**、`68.8s` 3→**3**、`6315ms` 2→**2**；两处追加逐字对照 spec（§3-P2 末尾一条 + §4 第 3 条末尾一句），未改第 1/2/4 条、未删改任何历史数字。
- **(d) .gitignore**：`git check-ignore -v .codex/ .trae/` → 命中 `.gitignore:63`（`.codex/`）与 `.gitignore:64`（`.trae/`）；`git status --short` 中 `?? .codex/`、`?? .trae/` 两行**消失**。
- **白空格/提交卫生**：`git diff --check` **rc=0**（日志 `docs/perf/data/raw/task155-00-diff-check.log`）；提交 1 内容 `git show --check 6cd428d8` 无白空格告警（rc=0）。add 时三文件打印 autocrlf「LF will be replaced by CRLF」提示，非报错、diff 为最小改动（无整文件行尾翻改）。
- **契约**：`--open TASK-155`（提交 1 后、写本文件前）**rc=0**（判据 A 两件套齐 + 1 个待办进行中放行）；同一时点无参数 **rc=1**（仅因 TASK-155 spec=进行中未声明，判据 A；预期口径）；**两件套提交后无参数 rc=0**（足迹不在工作树，视为已收口），日志 `docs/perf/data/raw/task155-02/03/04-mailbox-contract-*.log`。
- `--mode=online`/CI 未跑，**未达外部门槛**。

## 实际改动清单（只改）

- **提交 1 `6cd428d88a6406860c015f9398f39c4ae88453c5`（4 文件，+7/−2）**：
  `work/mailbox/PLAN.md`（改：`## 当前进度` 一句话 1 行换 1 行，其余不动）·
  `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java`（改：仅类注释 2 行 → 3 行）·
  `work/mailbox/后端优化机会总览-2026-09-26.md`（改：§3-P2 末尾 +1 条、§4 第 3 条末尾 +1 句，纯追加）·
  `.gitignore`（改：Agent 工作记忆段 +`.codex/`、`.trae/` 两行）。
- **提交 2（台账两件套）**：`work/mailbox/tasks/TASK-155/spec.md`（新）· `work/mailbox/tasks/TASK-155/handoff.md`（本文件，新）；提交哈希由任务回传补充。
- 提交信息经 UTF-8 文件 `git commit -F`（`docs/perf/data/raw/task155-commit-msg-fix.txt` / `…-ledger.txt`，忽略路径）；原始日志同在 `docs/perf/data/raw/task155-*.log`（忽略路径，不入库）。
- 除上述 6 个文件外本批未写任何受版本控制的路径；既有脏项未暂存、未提交、未触碰。

## 未覆盖 / 不得推出

- **未覆盖**：`--mode=online`、CI 与任何外部门槛；未复跑任何性能实验（本批为文档/注释一致性订正，不产生、也不复核任何吞吐/延迟读数）；未合并任何提案 spec-delta（属 TASK-156）。
- **不得推出**：不得把本批读成性能或行为变更（relay 周期/批次/并发/SQL/索引/事务/池/JVM/MQ/`innodb_flush_log_at_trx_commit` 均未动）；不得以本批翻案 TASK-153/154 的 NO-GO；不得把「注释订正」当作对 TASK-143 读数的复测；`VerifyOutboxRelay.java` 仅是注释文字改动，其行为与 TASK-143~154 所测版本一致。
