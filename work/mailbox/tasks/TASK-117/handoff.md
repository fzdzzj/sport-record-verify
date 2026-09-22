# TASK-117 handoff：sharding host 环境变量覆盖独立 ADDED 并入并闭合停手项

## 只改清单（contract 判据 B，与实际改动集一致）

- spec/specs/sport-record-verify/spec.md
- spec/changes/archive/add-sharding-host-env-override/proposal.md
- spec/changes/archive/add-sharding-host-env-override/tasks.json
- spec/changes/archive/add-sharding-host-env-override/specs/sport-record-verify/spec-delta.md
- spec/changes/archive/add-sharding-host-parameterization/proposal.md
- spec/changes/archive/add-sharding-host-parameterization/tasks.json
- spec/changes/archive/add-sharding-host-parameterization/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-117/spec.md
- work/mailbox/tasks/TASK-117/handoff.md
- work/mailbox/PLAN.md

开工基线 `726cf63`（origin/main 已同步）；未 push。`.trae/`（工作树未跟踪残留，契约排除）
不属交付载体。

## 归档动作（2 个 spec commit + 收口 commit，逐变更可回滚）

| # | commit | 内容 | 暂存清单核对 |
| --- | --- | --- | --- |
| 1 | `85252df` | docs(spec): 归档 add-sharding-host-env-override 并入主规格 | `git diff --cached --name-only` 仅 4 文件（spec.md + archive 内三件套），196 行纯插入 0 删除 |
| 2 | `466f7b1` | docs(spec): 归档 add-sharding-host-parameterization 闭合 TASK-115 停手项 | 仅 3 文件，全部 R100 重命名（历史原样保留，MODIFIED 不再并入） |
| 3 | 收口提交 | docs(spec): 收口 TASK-117 台账两件套 | TASK-117 两件套 + PLAN.md |

## 红绿取证（文档判据，判据输出原文）

- **红（并入前，基线 `726cf63`）**：
  - 主规格 `grep -c SHARDING_MYSQL_HOST` → `0`；
  - 主规格需求标题（临时模式文件精确匹配 `### Requirement: sharding 数据源 host 可由环境变量覆盖`）
    `grep -cF -f` → `0`；
  - `ls spec/changes/` → `add-sharding-host-parameterization/  archive/`（原变更仍在，非 archive）；
  - `ls spec/changes/archive/ | grep -i sharding` → 无命中。
- **绿（并入后）**：
  - 主规格需求标题计数 → `1`；`SHARDING_MYSQL_HOST` 计数 → `3`（需求正文 1 + 两场景 GIVEN）；
  - 头部列表 L42 = `- add-sharding-host-env-override（sharding 数据源 host 环境变量覆盖）`；
  - `git diff --cached --name-only` 逐 commit 仅含预期（见上表）；
  - `ls spec/changes/` → 仅 `archive/`；archive 含 `add-sharding-host-env-override/` 与
    `add-sharding-host-parameterization/`。

## 并入内容核对（零 MODIFIED 自证）

- spec-delta ADDED 段与主规格新增需求块逐字比对（临时 diff 脚本，正文两段各 `VERBATIM_*_OK`）；
- 主规格总 diff `22 insertions(+), 0 deletions(-)`，仅两处追加（头部列表 1 行 + 需求块 21 行）；
- 「真库端到端测试有确定路径」保持 `4be7929` 并入后的 middleware 泛化版原文未动（并入前复核）。

## 实跑结论（唯一入口 `scripts/verify/mvn-verify.sh`）

- `/d/git/Git/bin/bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 /
  BUILD SUCCESS**，Reactor 9 模块（含 parent）全 SUCCESS，模块测试合计
  **17 / 19 / 31 / 80 / 81 / 49 / 6 = 283**，与基线 `726cf63` 零扰动（纯 spec 文档改动），
  Failures 0 / Errors 0 / Skipped 0；
- 生效模式 `offline`、`localRepository D:/code/sports/.m2-repo`（依赖来源可判定，未触发退出码 3）。

## 词面自检

CI 原版口径（`git grep -n -I -iE` 禁用词集，三排除 `:!spec/changes/archive/**`
`:!docs/internal/**` `:!.github/workflows/ci.yml`）→ **ZERO-HIT**（rc=1 即无命中）。
禁用词模式含中文，经 `printf` 转义构造，命令行保持纯 ASCII。

## 契约自证（`mailbox-contract.sh --open=TASK-018,TASK-106`）

- 收口提交前脏树退出 **1** 属预期：TASK-117 只改清单声明全量 10 文件，而其中 7 文件已随
  `85252df` / `466f7b1` 落库、工作树仅剩两件套 + PLAN.md（清单多报=已收口部分，在途口径）；
- 收口提交后 ACTUAL 空（仅 `.trae/` 排除）→ TASK-117 足迹不在工作树视为已收口，契约退出 **0**。

## 停手项闭合核对

- TASK-115 停手项（add-sharding-host-parameterization 冲突未归档）自此**闭合**：
  - 独立 ADDED「sharding 数据源 host 可由环境变量覆盖」已以零 MODIFIED 变更单开并入主规格
    （校验引擎分区，与既有 ShardingSphere 数据源表述同节）；
  - 原变更整体移入 `archive/` 保留历史，其 MODIFIED 不再并入——「真库端到端测试有确定路径」
    的容器口径判据已由 middleware 泛化版（可按清单覆盖多条 + 未覆盖记账）承载，与 TASK-115
    停手判定一致；
- `spec/changes/` 仅剩 `archive/` 判据达成（TASK-115 完成定义中唯一未达成项，本次补齐）。

## 完成定义核对

- ✅ 新建 `add-sharding-host-env-override` 三件套（仅 ADDED 一条，零 MODIFIED）；
- ✅ ADDED 逐字并入主规格对应分区 + 头部归档列表 +1；
- ✅ 原变更整体 `git mv` 入 archive（R100，历史保留）；
- ✅ `spec/changes/` 仅剩 archive/；archive 含两个 sharding 变更；
- ✅ offline 283 零扰动；✅ 词面 ZERO-HIT；✅ 契约收口后退出 0；
- ✅ 台账两件套 + PLAN.md 验收记录；✅ 逐变更 commit 可回滚；✅ 未 push。
