# TASK-112 规格模块枚举笔误修正并归档

## 背景与目标

主规格 `spec/specs/sport-record-verify/spec.md`「多模块工程结构」需求（L32-42）写
"8 个可编译模块"但枚举只列了 7 个——缺 `mapmatch-service`（概览坑 9）。父 pom 实际 8 个模块
（common、api、gateway-service、user-service、record-service、verify-service、
leaderboard-service、mapmatch-service）。该需求已被 `add-controlled-verify-entrypoint`
MODIFIED 过，改动走新变更而非直接编辑——新变更 `update-spec-module-enum` 的 spec-delta 以
MODIFIED 表达修正，验证通过后本变更自行归档并入主规格（并入后主规格文本才真正修正）。

同文档「服务划分」需求（L1096）已正确含 6 服务与 mapmatch-service——笔误仅此一处，不动其他需求。

## 约束（硬边界，同主规格）

- 只改白名单 7 文件；`git diff --name-only HEAD` + untracked 与之完全一致（`.trae/` 基线允许）。
- 不 push、不建 PR；不动其他 14 个存量未归档变更、不动主规格其他需求；不引入任何代码/脚本改动。
- 认可唯一 `scripts/verify/mvn-verify.sh`；退出码 3 = 依赖来源不可判定，不得记通过。
- Git Bash 调用（PATH 含 `/usr/bin:/bin:/mingw64/bin` 与 Docker bin）；命令行不带中文；输出重定向日志文件再读。

## 只改清单

1. `spec/specs/sport-record-verify/spec.md`（L35 枚举补 mapmatch-service + 头部归档列表追加）
2. `spec/changes/archive/update-spec-module-enum/proposal.md`
3. `spec/changes/archive/update-spec-module-enum/tasks.json`
4. `spec/changes/archive/update-spec-module-enum/specs/sport-record-verify/spec-delta.md`
5. `work/mailbox/tasks/TASK-112/spec.md`
6. `work/mailbox/tasks/TASK-112/handoff.md`
7. `work/mailbox/PLAN.md`

## 执行步骤

1. 建变更三件套 `spec/changes/update-spec-module-enum/`（proposal.md / tasks.json /
   specs/sport-record-verify/spec-delta.md）。spec-delta 以 EARS 格式对「多模块工程结构」做
   MODIFIED：正文为修正后的完整需求文本（保留 L34-42 结构，仅枚举括号内补 `mapmatch-service`）。
2. 红绿取证（文档判据，判据脚本落盘为 .sh 执行）：
   - 红（修正前）：需求节 `mapmatch-service` 计数 = 0，需求写"8 个"、父 pom 模块计数 = 8，
     枚举 7 项 → 7≠8 矛盾成立；
   - 绿（修正后）：节内 `mapmatch-service` 计数 = 1，节内模块枚举计数 = 8 = 父 pom 模块计数，逐名一致。
3. 归档并入主规格：spec-delta MODIFIED 应用到主规格 L35；头部归档列表末尾追加
   `- update-spec-module-enum（规格模块枚举补正）`；`git mv` 入 `spec/changes/archive/`，
   核对 `git diff --cached --name-only` 只含预期 3 文件。
4. 回归：`bash scripts/verify/mvn-verify.sh --mode=offline test` → BUILD SUCCESS / 283
   （17/19/31/80/81/49/6）——纯 spec 改动无代码影响，仍按铁律跑。
5. 台账：TASK-112 两件套（handoff 含「只改清单」节）+ `work/mailbox/PLAN.md` 按收口清单五条追加验收记录。

## 验收判据汇总

- 主规格「多模块工程结构」枚举 8 项与父 pom `<module>` 8 项逐名一致；
- 主规格头部归档列表含 `update-spec-module-enum`；
- `spec/changes/archive/update-spec-module-enum/` 存在且 `spec/changes/` 下无同名未归档目录；
- offline 283 不变；`mailbox-contract.sh --open=TASK-018,TASK-106` 脏树退出 1 属预期（历史任务过冲），收口后退出 0。

## 完成定义

`handoff.md` 写入：只改清单（纯路径）/ 红绿取证（修正前节内计数 vs 父 pom 计数、修正后 8=8）/
实跑结论（offline 283、归档后目录状态、git diff --cached 核对）/ 契约自证（退出码与残余）/
台账（PLAN.md 行号）/ 未决、分期。临时文件用完即删。
