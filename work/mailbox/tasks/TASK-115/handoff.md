# TASK-115 handoff：14 个存量 spec 变更归档并入主规格

## 只改清单（contract 判据 B，与 `git diff --name-only 8521f32` 一致）

- `spec/specs/sport-record-verify/spec.md`
- `spec/changes/archive/add-web-console-scaffold/{proposal.md,tasks.json,specs/sport-record-verify/spec-delta.md}`
- `spec/changes/archive/add-web-auth-session/{...三件套}`
- `spec/changes/archive/add-web-admin-console/{...三件套}`
- `spec/changes/archive/add-web-record-console/{...三件套}`
- `spec/changes/archive/add-gateway-browser-cors/{...三件套}`
- `spec/changes/archive/add-db-migration-entrypoint/{...三件套}`
- `spec/changes/archive/add-mailbox-contract-check/{...三件套}`
- `spec/changes/archive/add-transaction-boundary-audit/{...三件套}`
- `spec/changes/archive/add-perf-demo-innodb-flush/{...三件套}`
- `spec/changes/archive/add-perf-g1-pause-target/{...三件套}`
- `spec/changes/archive/add-perf-mq-publish-async/{...三件套}`
- `spec/changes/archive/add-perf-submit-aggregation-gate/{...三件套}`
- `spec/changes/archive/update-perf-optimized-defaults/{...三件套}`
- `spec/changes/archive/add-middleware-it-coverage/{...三件套}`
- `work/mailbox/tasks/TASK-115/spec.md`
- `work/mailbox/tasks/TASK-115/handoff.md`
- `work/mailbox/PLAN.md`

开工基线 `8521f32`；未 push。`.trae/`（gitignore）不属交付载体。

## 归档动作（14 个 commit，逐变更一个，可回滚）

| # | commit | 变更 | 并入分区 |
|---|---|---|---|
| 1 | `1292596` | add-web-console-scaffold | 新增「Web 控制台」分区分组 |
| 2 | `fdac48f` | add-web-auth-session | Web 控制台 |
| 3 | `ce2ca12` | add-web-admin-console | Web 控制台 |
| 4 | `f84762a` | add-web-record-console | Web 控制台 |
| 5 | `12cb4cd` | add-gateway-browser-cors | 工程结构 |
| 6 | `15725e4` | add-db-migration-entrypoint | 工程结构（MODIFIED 数据库初始化 + ADDED 存量库迁移入口）/ 验收与门槛（schema 漂移可观测） |
| 7 | `1773f0b` | add-mailbox-contract-check | 验收与门槛 |
| 8 | `1571172` | add-transaction-boundary-audit | 点赞（MODIFIED 异步批量落库 + ADDED 写路径事务边界、跨存储/跨服务） |
| 9 | `9c1bded` | add-perf-demo-innodb-flush | 压测 |
| 10 | `8790307` | add-perf-g1-pause-target | 压测 |
| 11 | `90ca84c` | add-perf-mq-publish-async | 校验引擎（提交事件异步发布） |
| 12 | `d331db7` | add-perf-submit-aggregation-gate | 压测（跨请求聚合闸门） |
| 13 | `0fcb53c` | update-perf-optimized-defaults | 压测（MODIFIED 瓶颈优化实录 + ADDED 提交路径默认批量插入） |
| 14 | `4be7929` | add-middleware-it-coverage | 验收与门槛（MODIFIED 真库端到端 + ADDED 真中间件路径、清单一键） |

每个 commit 的 `git diff --cached --name-only` 均只含预期 4 文件
（`spec.md` + archive 内 3 件套），符合「只含预期」。

## 冲突停手：add-sharding-host-parameterization（未并入未归档）

- 与 add-middleware-it-coverage **共同 MODIFIED 同一需求「真库端到端测试有确定路径」**（均
  覆盖「真实中间件」泛化前提）。取证：sharding 的 MODIFIED 正文已含「真实中间件」，预设
  middleware 泛化先落地；
- 处理顺序为先 middleware（`4be7929` 已并入，当前文本含「该入口 SHALL 能按清单覆盖多条此类
  测试」「未覆盖记账」），再核对 sharding 时其 MODIFIED 基线文本与主规格当前文本**对不上**
  （并入 sharding 将丢弃 middleware 已并入的清单/未覆盖内容 = 强行合并）；且该变更还有独立
  ADDED「sharding 数据源 host 可由环境变量覆盖」。
- **按「冲突即停」：add-sharding-host-parameterization 整体停手**，`spec/changes/` 下保持
  `add-sharding-host-parameterization/`（非 archive），不移入，也不强并入。冲突明细回传，
  后续处置留给指导侧。

## 实跑结论（唯一入口 `scripts/verify/mvn-verify.sh`）

- `--mode=offline test`（本机，`D:\git\Git\bin\bash.exe`；`cmd/bash` 为坏 WSL 桩
  `ERROR_FILE_NOT_FOUND`）→ **BUILD SUCCESS / 9 模块全 SUCCESS**，模块测试合计
  **283** = 17 / 19 / 31 / 80 / 81 / 49 / 6，Failures 0 / Errors 0 / Skipped 0，
  与基线 `8521f32` 零扰动（纯 spec 文档改动，无代码）。
- 词面自检（CI 原版口径，git grep 三排除：`spec/changes/archive/**`、`docs/internal/**`、
  `.github/workflows/ci.yml`）→ **ZERO-HIT**：ripgrep 全仓命中仅落在上述三个排除路径内
  （`.trae/tmp/wording-check.sh` 为 TASK-113 历史残留、`ci.yml` 自身正则、`archive/add-two-level-cache`
  历史表述），发载体本变更新增内容 0 命中。
- 契约：收口 commit 前脏树 `mailbox-contract.sh --open=TASK-018,TASK-106` 退出 1（在途属预期）；
  收口 commit 后 ACTUAL 空（仅 `.trae/` 排除）→ TASK-115 足迹不在工作树视为已收口，退出 **0**。

## 主规格头部归档列表

新增 14 项（web-console-scaffold / web-auth-session / web-admin-console / web-record-console /
gateway-browser-cors / db-migration-entrypoint / mailbox-contract-check / transaction-boundary-audit /
perf-demo-innodb-flush / perf-g1-pause-target / perf-mq-publish-async / perf-submit-aggregation-gate /
update-perf-optimized-defaults / middleware-it-coverage）。sharding 未上榜。

## 未决 / 分期

- **add-sharding-host-parameterization 冲突未归档**：其 ADDED「sharding 数据源 host 可由环境
  变量覆盖」为独立新增需求未受影响，但按规则整体停手。建议后续以该需求单独补开一个不 MODIFIED
  既有需求的变更，或在指导侧裁定口径后并入——本次不强行合并。
- 词面自检依赖的临时判据产物已清理；`.trae/tmp/wording-check.sh` 为历史遗留，不属本次改动范围。

## 完成定义核对

- ✅ 主规格头部归档列表新增 14 项；14 个变更 ADDED/MODIFIED 并入对应分区；
- ⛔ `spec/changes/` 仅剩 archive/ 的判据**未达成**（add-sharding-host-parameterization 冲突停手仍在）；
- ✅ offline 283 零扰动；✅ 契约收口后退出 0（在途 1 / 收口后 0）；✅ 词面 ZERO-HIT；
- ✅ 台账两件套 + PLAN.md 五条验收记录；✅ 逐变更 14 commit 可回滚；✅ 未 push。