# 提案：sharding 数据源 host 环境变量覆盖（独立 ADDED 单开并入）

## Why

TASK-115 批量归档 14 个存量 spec 变更时，`add-sharding-host-parameterization` 因其 MODIFIED
「真库端到端测试有确定路径」的基线被 `add-middleware-it-coverage`（`4be7929`）先并入覆盖，
按「冲突即停」整体停手（TASK-115 handoff「冲突停手」节），仍留在 `spec/changes/` 未归档。
但其**独立 ADDED 需求「sharding 数据源 host 可由环境变量覆盖」不受阻挡**，且代码侧已由
TASK-111 落地（`SHARDING_MYSQL_HOST`，commit `75ec1dd`：`sharding.yaml` jdbcUrl 占位
`${SHARDING_MYSQL_HOST:127.0.0.1}` + 哨兵单测 2 条；offline 283 全绿，容器口径红绿取证齐全，
台账《sharding.yaml 的 MySQL host 参数化（TASK-111）》在案）。

本提案把该 ADDED 需求以「不 MODIFIED 既有需求」的方式单开独立变更并入主规格，并把原变更
`add-sharding-host-parameterization` 整体移入 `archive/`（其 MODIFIED 不再并入——与 TASK-115
停手判定一致），闭合该停手项，使 `spec/changes/` 仅剩 `archive/`。

**期望状态**：主规格 `sport-record-verify` 新增一条独立需求「sharding 数据源 host 可由环境变量
覆盖」（宿主直跑默认 `127.0.0.1` 口径零变化；容器内经 `SHARDING_MYSQL_HOST` 服务名寻址），
既有需求文本零改动。

## What Changes

- 新建 `spec/changes/add-sharding-host-env-override/` 三件套：spec-delta **仅含 ADDED 需求一条**
  （从原变更 spec-delta 的 ADDED 段原样复制），零 MODIFIED。
- 并入主规格 `spec/specs/sport-record-verify/spec.md`：ADDED 需求追加到「校验引擎」分区
  「轨迹分片存储」需求之后（record-service 的 `sharding.yaml` 即该需求 track_point 分片
  ShardingSphere 数据源的配置载体，与既有 sharding 表述同节）；头部「本规范已归档以下提案」
  追加 `add-sharding-host-env-override`。
- 新变更并入后随 `git mv` 入 `spec/changes/archive/`（先例 TASK-115 逐变更「并入即归档」口径）；
  `git mv spec/changes/add-sharding-host-parameterization spec/changes/archive/` 整体归档。

**明确不做**：

- 不 MODIFIED 任何既有需求（「真库端到端测试有确定路径」保持 `4be7929` 并入后的 middleware
  泛化版不动；本变更并入前复核该文本仍为泛化版，对不上即停手）。
- 不碰代码/脚本/Dockerfile/compose/迁移脚本；`SHARDING_MYSQL_HOST` 实现为 TASK-111 既有交付，
  本提案仅补规范口径。
- 不 push、不建 PR、不改 CI。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — ADDED「sharding 数据源 host 可由环境变量覆盖」
  追加到「校验引擎」分区既有 sharding 表述同节；头部归档列表 +1。零 MODIFIED。

### 受影响的代码
- 无（纯 spec 文档改动）。

### 用户影响
- 规范口径与实现对齐：宿主直跑无需额外配置（默认 127.0.0.1 不变）；record-service 容器化部署
  经 `SHARDING_MYSQL_HOST: mysql` 服务名连上 MySQL 已是 TASK-111 验过的判据。

### API 变更 / 需要迁移
- 无。

## 时间线评估
- 三件套 + 并入 + 归档 + 台账，纯文档，约 1 小时（含红绿取证与 offline 零扰动复跑）。

## 风险与缓解

- **误改既有需求**（把 ADDED 写成 MODIFIED，或误触 middleware 泛化文本）→ 缓解：spec-delta 零
  MODIFIED；主规格 diff 仅追加两处（需求 + 头部列表），`git diff` 逐字核对。
- **落点漂移**（目标分区被后续变更再覆盖）→ 缓解：并入前核对「轨迹分片存储」与「真库端到端
  测试有确定路径」当前文本与预期基线一致，对不上即停手回传明细，不强行合并。
- **词面自检命中** → 缓解：新增文本不含禁用口径词汇；并入后按 CI 原版口径（git grep 三排除）
  复跑确认 ZERO-HIT。

## 备注

- 红绿取证（文档判据）：红 = 主规格 `SHARDING_MYSQL_HOST` / 需求标题计数 0，且
  `add-sharding-host-parameterization` 仍在 `spec/changes/`（非 archive）；绿 = 需求标题计数 1、
  头部列表含新变更名、`git diff --cached --name-only` 仅含预期、`spec/changes/` 仅剩
  `archive/` 且含 add-sharding-host-parameterization 与本变更。
- 逐变更一个 commit（`docs(spec): 归档 <id> 并入主规格` 风格），可回滚。
- 不 push、不建 PR；未达外部门槛时在台账显式标注。
