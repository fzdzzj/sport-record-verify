# TASK-113 CI 镜像构建时长余量评估（可接手事项 5/5，末项）

## 背景与目标

CI `build` job（`.github/workflows/ci.yml` L10-81）现状：`mvn-verify.sh --mode=online verify`
→ 占位 `.env` → `docker compose config -q`（6 服务编排解析判据）→ 只实构 1 份代表镜像
（leaderboard-service，复用 compose 构建定义，其余 5 份由 config 覆盖）→ 词面自检。
runner 实测约 47s（含镜像内 Maven 层下载；来源见下）。评估问题：若后续加更多实构镜像，
策略 A（BuildKit 层缓存，如 GHA cache / buildx cache-from）vs **策略 B（维持现状：
1 份实构 + config 覆盖）**是否仍够用。产出为评估结论 / ADR——**纯评估，不实施**。

## 约束（硬边界）

- 只改白名单 4 文件；`git diff --name-only HEAD` + untracked 与之完全一致（`.trae/` 基线允许）。
- 不改 `.github/` 任何文件、不实施缓存、不动 compose 构建定义、不 push、不建 PR。
- 不建 `spec/changes/` 三件套：本任务无代码/规格需求改动，台账两件套即满足契约判据 A
  （handoff 中注明此理由）。
- 47s 基线来源如实标注；未实测项显式标注，不得编造 run 号与数据。
- Git Bash 调用；命令行不带中文（判据脚本落盘执行）；临时文件用完即删。

## 只改清单

1. `docs/adr/0010-ci-image-build-time-budget.md`（ADR 五节；编号说明：`docs/adr/` 已存在且
   0001-0009 已占用，任务包起草时假设该目录不存在，按既有编号顺延取 0010）
2. `work/mailbox/tasks/TASK-113/spec.md`
3. `work/mailbox/tasks/TASK-113/handoff.md`
4. `work/mailbox/PLAN.md`

## 评估内容（ADR 必含五节）

1. **现状**：引用 ci.yml build job 各步骤名与行号；47s 基线及来源（可绑外部门槛 run 号则绑，
   不可绑则显式标注"未达外部门槛"，不得编造）。
2. **策略对比表**：A（BuildKit 层缓存）vs B（现状）——时长增量预测、缓存配额与维护成本、
   失效风险、对 6 服务编排的适配度。
3. **量化推演**：每多实构 1 份镜像的时长增量估算（基于 47s 中镜像构建占比的机制分析；
   本机可无障碍跑 `docker compose build`，故做冷/热计时对照加强，并显式标注本机口径与
   runner 口径的差异）。
4. **结论与建议**：明确建议（维持/切换/触发条件，如"实构镜像数 > N 或 job > X 分钟时重评"）。
5. **后续路径**：若建议切换，列出属未来变更的动作清单（仅列举，不实施）。

## 验收判据汇总

- ADR 五节齐全，ci.yml 引用步骤名与行号经得起 `sed -n '<行号>p'` 抽查；
- 47s 基线来源如实标注；未实测项显式标注，无编造数据；
- `bash scripts/verify/mvn-verify.sh --mode=offline test` → BUILD SUCCESS / 283
  （纯文档零扰动，仍按铁律跑）；
- 词面自检（CI 同款 pathspec，另加 untracked 覆盖）ZERO-HIT；
- `bash scripts/verify/mailbox-contract.sh --open=TASK-018,TASK-106` 脏树退出 1 属预期
  （历史任务过冲），收口后退出 0。

## 完成定义

`handoff.md` 写入：只改清单（纯路径）/ ADR 交付要点（含编号偏离说明）/ 量化实测（冷/热/增量
三组与层命中明细，含口径限定）/ 实跑结论（offline 283）/ 判据自证（sed 抽查、词面、契约）/
台账（PLAN.md 行号）/ 未决、分期。临时文件用完即删。
