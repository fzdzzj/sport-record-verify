# TASK-112 Handoff

**实现方：执行 agent（收口授权下放，指导侧不再复跑）。未 push、未建 PR。** 开工基线 `75ec1dd`。

## 只改清单

- spec/specs/sport-record-verify/spec.md
- spec/changes/archive/update-spec-module-enum/proposal.md
- spec/changes/archive/update-spec-module-enum/tasks.json
- spec/changes/archive/update-spec-module-enum/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-112/spec.md
- work/mailbox/tasks/TASK-112/handoff.md
- work/mailbox/PLAN.md

## 红绿取证（文档判据，判据脚本落盘 .sh 执行）

- **红（修正前）**：从「多模块工程结构」标题到下一个 `### Requirement:` 之间，节内
  `mapmatch-service` 计数 **0**；需求写"8 个"（命中 1）；节内模块枚举计数 **7**；父 pom
  `grep -c "<module>" pom.xml` = **8** → 7≠8 矛盾成立，`RED_OK` 退出 0。
- **绿（修正后）**：节内 `mapmatch-service` 计数 **1**；节内模块枚举计数 **8** = 父 pom **8**；
  枚举名与 pom 模块名排序 diff 为空（逐名一致），`GREEN_OK` 退出 0。
- 判据脚本 `logs/task112-redgreen.sh`（`--red` / `--green` 两模式）已按铁律落盘执行并用完即删，
  命令行不带中文（WSL bash 不可用，改调 `D:/git/Git/bin/bash.exe`）。

## 实跑结论（唯一入口 `mvn-verify.sh`）

- `bash scripts/verify/mvn-verify.sh --mode=offline test`：**BUILD SUCCESS**，全 8 模块 SUCCESS
  （Reactor Summary），模块合计 **17/19/31/80/81/49/6 = 283**（与基线一致，纯 spec 改动零扰动）。
- 归档后目录状态：`spec/changes/archive/update-spec-module-enum/` 在位（proposal/tasks.json/spec-delta
  三件套齐全）；`spec/changes/update-spec-module-enum` 不存在（无同名未归档目录）。
- 归档动作：变更文件先 `git add` 再 `git mv spec/changes/update-spec-module-enum spec/changes/archive/`
  （git mv 只认已跟踪文件）；`git diff --cached --name-only` 仅含预期 3 文件
  （proposal.md / tasks.json / spec-delta.md，均为 archive 路径）。
- 主规格改动两处：L35 枚举括号内补 `mapmatch-service`（"8 个"与列表一致）；头部归档列表末尾追加
  `- update-spec-module-enum（规格模块枚举补正）`。

## 契约自证

- 收口提交前 `bash scripts/verify/mailbox-contract.sh --open=TASK-018,TASK-106` 脏树退出 **1** 属预期：
  历史任务清单共占公共文件（PLAN.md 等）过冲；TASK-112 自身判据 B 只改清单与改动集 7=7 一致。
- 收口提交后 ACTUAL 空（仅 `.trae/` 排除）→ 各任务足迹不在工作树、视为已收口，契约退出 **0**。
  （行号以回传时实际输出为准；如实记录退出码与残余项。）

## 台账

- PLAN.md 已按收口清单五条追加 `规格模块枚举补正并归档（TASK-112，事项 4/5）` 验收记录（行号见回传）。
- `spec/changes/archive/update-spec-module-enum/tasks.json` 各阶段 `completed:true` / `passes:true`。

## 未决 / 分期

- 无遗留项。其余 14 个存量未归档变更（web 系列、perf 系列、gateway-browser-cors 等）的整体归档另行派发。
- 未达外部门槛：修订仅本地、未 push，无 CI run 编号可绑；结论均来自本地唯一入口实跑。
