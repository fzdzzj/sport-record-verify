# TASK-157 回传（handoff）

## 结论

台账的「外部门槛」口径已订正：`PLAN.md` L4 整行替换（1+/1−），`后端优化机会总览-2026-09-26.md` §5 末尾追加 1 条**权威订正条**（纯追加）。**16 处历史「未达外部门槛」条目中，15 处一字未改**；唯一被替换的是 L4 那一处**现在时状态陈述**（push 后已变假）。零代码改动、零构建、未再次 push。

执行方：**指导侧主 agent 自执行**（理由见 spec「为何指导侧自执行」）。验证弱化披露：本任务无独立执行方，以「契约脚本 rc + 逐字 diff + 计数取证」自证补偿。

## 起点与触发

- 起点 HEAD（全 SHA）= `de81b59a2614f93d696e71bd84e93c666e1d766b`，分支 `main`，与 `origin/main` 起点一致（`git rev-list --left-right --count` = `0\t0`）。
- 触发：用户显式授权 push（单字「授权」，紧接指导侧上一轮明示的 push 红线与风险量化）。
- push 实测：`git push origin main` → `58cd104..de81b59  main -> main`，**rc=0**；`git fetch origin` 无更新（origin 未移动，纯 fast-forward）。
- CI 实测：run **`36399582548`**（trigger=push、branch=main、HEAD `de81b59`）→ `gh run view --json status,conclusion` = `{"status":"completed","conclusion":"success"}`；jobs：`web` ✓ **26s**（ID `108854003738`）、`build` ✓ **2m23s**（ID `108854003911`）。上一次 main 的 CI 为 2026-09-25 run `36098038547`（success，TASK-137 期）⇒ **本次是 TASK-138~156 约 40 笔提交的首次外部评判**。

## 指导侧对 TASK-156 的独立复核（push 前完成，不认散文）

| 复核项 | 实测 | 判 |
|---|---|---|
| HEAD / 领先笔数 | `de81b59a2614f93d696e71bd84e93c666e1d766b` / **42** | ✓ |
| TASK-156 生产文件改动 | `git diff --name-only 7eb6095..HEAD` 过滤 main/java、*.sql、*.yml、*.properties → **空** | ✓ 零生产改动 |
| 文件清单 | 恰 10 个（业务 6：IT 598 行、JSON、报告、三件套 3；台账 4：PLAN、handoff、spec、机会总览） | ✓ 与回传一致 |
| `.mvn/maven.config` | `git ls-files .mvn` **空**、`Test-Path` **False** | ✓ 未入库、已删除 |
| 默认套件复跑 | `mvn-verify.sh --mode=offline --pl verify-service` → verify-service **110/0/0/0**、BUILD SUCCESS、**rc=0**；输出中**无** `ConcurrencyScaling` | ✓ 新 IT 确未被默认收集 |
| 契约 | 无参数 `mailbox-contract.sh` → **rc=0**（判据 A 两件套齐含 0 个待办进行中 + 判据 B 清单一致） | ✓ |
| `git diff --check` | **rc=0** | ✓ |
| 硬判据（JSON） | 四臂 `comUpdateDeltaPerRound` 均 `[2000,2000,2000]`、`sentCountFinalPerRound`=2000、`pendingCountFinalPerRound`=0、`comUpdateDeltaExact2000AllWindows`=true | ✓ 12 窗口全过 |
| S 值自洽 | 190.40/102.30=1.8612、338.26/102.30=3.3066、583.67/102.30=5.7056 | ✓ |
| 既有脏项 | `git status --porcelain` 仅剩 6 个 ` D spec/changes/{wire-verify-outbox,adopt-native-mq-retry}/*` + 3 个 `??`（`archive/*`、`add-verify-degrade-status-index/`） | ✓ 原样未触 |

**未独立复跑**（如实披露）：真库 IT 的三档退出码（变异红 rc=1 / 复绿 rc=0 / 缺变量 skipped rc=0）与 12 窗口的 `SHOW GLOBAL STATUS` 原始读数——指导侧只核了 JSON 与 raw 日志中的汇总值，未重跑 scratch 真库实验。common 模块 36 个测试未在本次复跑的 tail 输出中直接读到（本任务与 TASK-156 均未触碰 `common/` 任何路径，据文件清单推定不受影响，**等级为推定非实测**）。

## 只改清单（逐文件）

1. `work/mailbox/PLAN.md` —— **L4 整行替换**（`git diff --stat` = `2 +-`，即 1 插入 1 删除）。旧行 487 字符 → 新行 1296 字符。文件行尾为**纯 LF**（CRLF=0、bareLF=1023），写入保持 LF，未引入混合行尾。
2. `work/mailbox/后端优化机会总览-2026-09-26.md` —— §5「仓库状态与更新约定」**末尾追加 1 条 bullet**（`git diff --stat` = `1 +`，纯追加）。该文件为 **CRLF 原生**；首次追加误用裸 LF（实测 CRLF=189/bareLF=1），**已修正**为 CRLF（修正后 CRLF=190/bareLF=0，bytes 47106→47107）。此为指导侧自查发现并自修，如实记录。
3. `work/mailbox/tasks/TASK-157/spec.md` —— 新增（LF，34 行）。
4. `work/mailbox/tasks/TASK-157/handoff.md` —— 新增（本文件）。

## 判据与退出码

- `git diff --check` → **rc=0**（实测）。
- caveat 计数（脚本化计数，命令行未出现中文）：`PLAN.md`「未达外部门槛」**10 → 9**（被替换的 L4 属现在时陈述，合法订正；其余 9 处历史条目零改写）；`机会总览` **6 → 7**（新增订正条自身引用该词；原有 6 处历史条目零改写）。起点值取自 `git show de81b59:<path>`，与实测一致。
- `git diff --stat` 确认仅 2 个台账文件被改（PLAN `2 +-`、机会总览 `1 +`），另有 6 个**起点即存在**的归档删除侧脏项，本任务未 stage、未触碰。
- **未跑 Maven**：本任务零代码改动，无构建门槛可跑（不为凑门槛而空跑）。
- 契约：提交前在途 rc=1 属预期口径（可 `--open TASK-157`）；两笔提交后无参数 `mailbox-contract.sh` 期望 **rc=0**（见下方补记）。
- **未再次 push**：本次用户授权只覆盖已推送的 42 笔；TASK-157 自身提交是否推送**需用户另行明确授权**。

## 未覆盖与不得推出

- **CI 绿不升级任何证据等级**：本文与 `PLAN.md` 中的性能/容量数字（18.0 ms/行、73.93%、13.4 行/s、37 行/s、S(2)/S(4)/S(8)）**仍只有本机 scratch 证据**，不因 run `36399582548` 双绿而变为「外部复核通过」。
- **CI 未覆盖**：任何真库 IT（CI 无 MySQL service；`*IT` 不被 Surefire 默认收集；`--it` 未被调用，且其 L36/L190 硬编码 `-pl leaderboard-service` + 三个 leaderboard/redis/mq 类并**忽略 `--pl`**）；`verify-service`/`common` 的静态三件套（`--static` 只对 `leaderboard-service` ⇒ TASK-156 新增的 598 行 IT **从未过 checkstyle/spotbugs/pmd**）；`--mode=offline` 口径（CI 用 `--mode=online`）；四服务端到端；任何生产运行时行为。
- **不得推出**：不得据 CI 绿宣称「TASK-152/153/154/156 的真库判别已被外部验证」；不得据 S(8)=5.7056 宣称生产吞吐收益或授权实施分区 relay（TASK-156 明确为**必要非充分**，且未测并发 `syncSend`/RocketMQ、未测 relay 锁改造、未测多实例竞争）；不得翻案 TASK-153；不得把本任务当作「规格合并」或「脏项清理」已完成（二者均未做）。
- **遗留缺口（未做，需另立任务）**：(1) `--it` 无法覆盖 verify-service IT ⇒ TASK-152/153/154/156 的真库 IT 运行无法从台账记载命令复现（TASK-154 用带外 `.mvn/maven.config` 且零记载，TASK-156 已补记载）；(2) 规格合并轮未做：主规格仍**无 outbox 章节**，`wire-verify-outbox`/`adopt-native-mq-retry`/`fix-verify-outbox-poison-head-of-line` 三处 delta 未并回，6 个归档删除侧脏项与 `add-verify-degrade-status-index`、两个 0/5 在途目录原状留存；(3) `verify-service`/`common` 静态三件套从未在 CI 跑过。

## 补记（收口实测，提交后补录）

- 两笔本地提交：`0f62dbf`（订正：`PLAN.md` + 机会总览，2 files changed, 2 insertions(+), 1 deletion(-)）与本笔台账提交（TASK-157 spec/handoff，2 files changed, 88 insertions(+)；其自身 SHA 无法自引，以 `git log -1` 事后核对）。
- `git diff --check` **rc=0**；`git show --check` **rc=0**。
- `git diff --name-only de81b59..HEAD` = 恰 **4** 个路径，全在 `work/mailbox/` 下，**无任何生产路径**。
- `git status --porcelain` 收口后仅剩起点即存在的 **9** 项脏项（6 个 ` D spec/changes/{wire-verify-outbox,adopt-native-mq-retry}/*` + 3 个 `??`：`archive/adopt-native-mq-retry/`、`archive/wire-verify-outbox/`、`add-verify-degrade-status-index/`），本任务未 stage、未触碰。
- `git rev-list --left-right --count origin/main...main` = `0` behind / `2` ahead ⇒ 本任务两笔**未推送**，等用户另行明确授权。
- 无参数 `bash scripts/verify/mailbox-contract.sh` → **rc=0**（判据 A 两件套齐、含 0 个待办进行中；判据 B 清单一致；TASK-157 足迹不在工作树视为已收口）。
- caveat 计数收口后复核：`PLAN.md` = **9**、`机会总览` = **7**，与 spec 订正后的门槛逐字一致。
