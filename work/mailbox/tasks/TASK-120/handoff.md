# TASK-120 Handoff

**实现方：执行 agent（收口授权下放，指导侧不再复跑）。未 push、未建 PR。** 开工基线 `ac2a8ff`。

## 只改清单

- scripts/verify/mailbox-contract.sh
- scripts/verify/README.md
- work/mailbox/tasks/TASK-120/spec.md
- work/mailbox/tasks/TASK-120/handoff.md
- work/mailbox/PLAN.md

## 改动内容

- **白名单补 `editorconfig`**：`mailbox-contract.sh` L126 `extract_claims` 扩展名正则末尾追加
  `|editorconfig`（与既有 `example` 同款风格），其余契约判定逻辑（判据 A/B 分支、比对、退出码）
  与 `--open` 机制零改动。修掉 TASK-018 遗留的第二次同源盲区——handoff 声明
  `leaderboard-service/.editorconfig` 却提取不到，在途窗口被误记"改动集未声明"。
- **README 同步**：判据 B 段白名单说明补 `.editorconfig`；原「已知局限」段改写为"已修 +
  两次同源盲区（`.example`→TASK-114、`.editorconfig`→本任务）的判别样本"，并给出后续新
  载体类型（如无扩展名文件）写进只改清单前先验证提取管道的自检方法。

## 红绿取证（承 TASK-114 手法，LC_ALL=C）

- **红（改前）**：`echo 'leaderboard-service/.editorconfig' | grep -oE '<原白名单>'`
  → 命中 **0**（grep 计数 0）。提取层管道（awk 截「改动清单」节 + grep/sed/sort，与
  `extract_claims` 同构）对 TASK-018 `handoff.md` 跑一遍：6 个声明路径只出 5 个，
  `leaderboard-service/.editorconfig` 缺席——"清单多报"假阳性的实证。
- **绿（改后）**：同式命中 **1** 且串一致（命中串逐字等于 `leaderboard-service/.editorconfig`，
  eq=1）；同管道对 TASK-018 handoff 出全 **6** 个路径（含 `leaderboard-service/.editorconfig`）。
- **变异验证**（TASK-106 手法）：`cp` 留底修复态 + `sha256sum` 记录 → 临时 sed 回退白名单
  → 复现红（命中 0）→ `cp` 还原 → `sha256sum -c` 报 `OK`、`cmp` 退出 0（逐位一致）。
- `bash -n scripts/verify/mailbox-contract.sh` 语法自检通过。

## 实跑结论（唯一入口 `mvn-verify.sh`）

- `bash scripts/verify/mvn-verify.sh --mode=offline test`：**RC=0 / BUILD SUCCESS**，模块合计
  **17/22/31/80/81/50/6 = 287**，0 失败 0 错误 0 跳过（与开工基线 `ac2a8ff` 逐位一致，
  纯脚本一行 + 文档零扰动），Total time 03:37，生效模式 offline、localRepository
  `D:/code/sports/.m2-repo`（依赖来源可判定，未触发退出码 3）。
- **锚点订正**：任务包原文写"offline 284"为 TASK-119 增量前的旧数（gateway 19→22 后应为
  287），实跑以 287 为准，差异已在 PLAN.md 验收记录登记。

## 词面自检

- CI 同款判据（`git grep -n -I -iE <禁用词表> -- ':!spec/changes/archive/**'
  ':!docs/internal/**' ':!.github/workflows/ci.yml'`，正则以 `-f` 落盘承载、命令行纯 ASCII）：
  `LC_ALL=C` 下本任务 5 个改动文件 **ZERO-HIT**；默认 locale 余 2 条为 TASK-118/119 已登记的
  `api/**/MapMatchResultDTO.java:17/36` 本机引擎伪影（只改清单外，未触碰，按未覆盖记账）。

## 契约自证

- 在途 `bash scripts/verify/mailbox-contract.sh --baseline=ac2a8ff`：TASK-120 判据 A 通过
  （两件套齐全）、判据 B 通过（只改清单 5 项与实际改动集逐项一致）；整体退出码 1 的成因
  **不在 TASK-120**——`PLAN.md` 进改动集后，历史 handoff 正文的公共文件 token
  （`PLAN.md`/`README.md` 等）与改动集交叠，触发既往台账已登记的"公共文件过冲"。
- 收口提交后 `bash scripts/verify/mailbox-contract.sh`（**无参数**）退出 **0**
  （工作树无迹 ⇒ 不重审）。

## 台账

- `work/mailbox/PLAN.md` 追加《验收记录：契约提取白名单补 .editorconfig（TASK-120，
  2026-09-22）》，L409–L420；绑定修订含 `14a2697`（脚本）/ `7c1f1ce`（README）。

## 未决 / 分期

- 停止边界内仅白名单一行 + README 说明 + 台账；不动契约判定逻辑与 `--open` 机制，无其他未覆盖。
- 未达外部门槛：修订未 push，无 CI run 编号可绑；待下次 push 由 CI 复验。
- 提取白名单属"新载体类型逐次补"的枚举式机制，若后续无扩展名文件（如 `Dockerfile`）要进
  只改清单，需另行扩展（README 已留自检方法）。
