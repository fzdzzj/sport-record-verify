# TASK-117：add-sharding-host-parameterization 停手项闭合（独立 ADDED 并入主规格并归档）

## 背景与目标

TASK-115 归档 14 个存量变更时，`spec/changes/add-sharding-host-parameterization` 因 MODIFIED
「真库端到端测试有确定路径」的基线被 `add-middleware-it-coverage`（`4be7929`）先并入覆盖而按
「冲突即停」整体停手，仍留在 `spec/changes/`（未归档）。其**独立 ADDED 需求「sharding 数据源
host 可由环境变量覆盖」不受阻挡**，且代码侧已由 TASK-111 落地（`SHARDING_MYSQL_HOST`，commit
`75ec1dd`）。

本次：

1. 新建独立变更 `spec/changes/add-sharding-host-env-override/` 三件套——spec-delta **仅含
   ADDED 需求一条**（从原变更 spec-delta 的 ADDED 段原样复制），零 MODIFIED；
2. 并入主规格 `spec/specs/sport-record-verify/spec.md`：ADDED 追加到「校验引擎」分区
   「轨迹分片存储」需求之后（record-service 的 `sharding.yaml` 即该需求 track_point 分片
   ShardingSphere 数据源的配置载体，与既有 sharding 表述同节）；头部「本规范已归档以下提案」
   追加 `add-sharding-host-env-override`；
3. 新变更并入即随 `git mv` 入 `archive/`（先例 TASK-115 逐变更口径）；
   `git mv spec/changes/add-sharding-host-parameterization spec/changes/archive/` 整体归档
   保留历史，其 MODIFIED 不再并入——与 TASK-115 停手判定一致；
4. 台账两件套 + PLAN.md 验收记录。

目标判据：闭合 TASK-115 停手项，`spec/changes/` 仅剩 `archive/`。

## 冲突即停复核（并入前）

- 「真库端到端测试有确定路径」当前文本（主规格 L428 起）仍为 middleware 泛化版（「真实数据库
  或其他真实中间件 + 该入口 SHALL 能按清单覆盖多条此类测试 + 未覆盖记账」），与任务基线描述
  一致；本变更零 MODIFIED，不触碰该需求；
- 「轨迹分片存储」（追加落点）文本与基线一致，未被后续变更覆盖；
- 两项复核通过，不触发停手。

## 约束（硬边界）

- 只改白名单内文件；不 push、不建 PR；不碰代码/脚本/Dockerfile/compose；禁改迁移脚本 V21-V23；
  不改主规格除 ADDED 追加与头部列表外的其他文本。
- 唯一验收入口 `scripts/verify/mvn-verify.sh`；退出码 3 = 依赖来源不可判定，不得记通过。
- Git Bash 专用 `D:\git\Git\bin\bash.exe`（cmd/bash 是坏 WSL 桩）；命令行不带中文（触发
  exit 127），含中文判据的模式/脚本经临时文件落盘后引用。
- 冲突即停：并入时若发现目标分区文本与预期对不上，停手回传明细，不强行合并。
- 逐变更一个 commit（`docs(spec): 归档 <id> 并入主规格` 风格），可回滚。

## 只改清单

1. `spec/specs/sport-record-verify/spec.md`
2. `spec/changes/add-sharding-host-env-override/` 三件套（新建，后随 git mv 入 `archive/`）
3. `spec/changes/archive/add-sharding-host-parameterization/` 三件套（git mv，3 文件）
4. `work/mailbox/tasks/TASK-117/spec.md`
5. `work/mailbox/tasks/TASK-117/handoff.md`
6. `work/mailbox/PLAN.md`

## 执行步骤

1. 红取证：主规格 `SHARDING_MYSQL_HOST` / 需求标题计数 0；`add-sharding-host-parameterization`
   仍在 `spec/changes/`（非 archive）、archive 无 sharding 目录。
2. 新建三件套 → 并入主规格（两处追加）→ `git add` + `git mv` 入 archive → 逐 commit 核对
   `git diff --cached --name-only` 仅含预期 → 提交。
3. `git mv add-sharding-host-parameterization` 入 archive → 提交。
4. 实跑验收：`mvn-verify.sh --mode=offline test`（offline 283 零扰动）+ 词面自检三排除
   ZERO-HIT。
5. 台账收口：TASK-117 两件套 + PLAN.md 验收记录 → 收口提交 → 契约自证退出 0。

## 验收判据汇总

- 绿：主规格需求标题计数 1、`SHARDING_MYSQL_HOST` 计数 3（需求正文 1 + 场景 GIVEN 2），
  头部列表含 `add-sharding-host-env-override`；
- 绿：逐 commit `git diff --cached --name-only` 仅含预期（并入 commit 4 文件 = spec.md +
  archive 三件套；归档 commit 3 文件 R100）；
- 绿：`spec/changes/` 仅剩 `archive/`，archive 含 `add-sharding-host-parameterization` 与
  `add-sharding-host-env-override`；
- offline 283（17/19/31/80/81/49/6）零扰动（纯 spec 改动）；词面自检 ZERO-HIT；
- 契约收口提交后退出 0；
- 主规格零 MODIFIED：middleware 泛化文本与既有需求零改动（diff 仅 22 行纯插入）。

## 完成定义

`handoff.md` 写入：只改清单 / 归档动作与 commit 列表 / 红绿取证原文 / 实跑结论 / 停手项闭合
核对 / 完成定义核对。临时文件用完即删。
