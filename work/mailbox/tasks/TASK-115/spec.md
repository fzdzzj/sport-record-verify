# TASK-115 批量归档 14 个存量 spec 变更并入主规格

## 背景与目标

`spec/changes/` 下 14 个已验收未归档变更（web 系列、perf 系列、gateway-browser-cors、
transaction-boundary-audit、db-migration-entrypoint、add-mailbox-contract-check、
add-middleware-it-coverage、add-sharding-host-parameterization 等）需逐变更把 spec-delta
并入主规格 `spec/specs/sport-record-verify/spec.md` 后 `git mv` 入 `archive/`。

并入规则（先例 `update-spec-module-enum`，commit `0883bec`）：

- ADDED 需求追加到主规格对应分区；
- MODIFIED 需求以 spec-delta 文本替换主规格对应基线需求；
- 主规格头部「本规范已归档以下提案」列表追加该变更名；
- `git mv spec/changes/<id> spec/changes/archive/<id>`。

**冲突即停**：若某变更 MODIFIED 的基线文本与主规格当前文本对不上（被后续变更覆盖过），
该变更停手、回传冲突明细，不得强行合并。

## 冲突判定：add-sharding-host-parameterization 与被覆盖的 MODIFIED

`add-middleware-it-coverage`（TASK-110，先验收）与 `add-sharding-host-parameterization`
（TASK-111，后验收）均 MODIFIED 同一需求「真库端到端测试有确定路径」。

依据（git 取证，见 `db3e341` 引入的当前基线文本）：

- 主规格当前文本为「WHERE 存在需要真实数据库的端到端测试…准备库步骤…」，即
  `add-controlled-verify-entrypoint` 归档时的基线；
- 两个变更都要求改写它：middleware 把「真库」泛化为「真中间件 + 可按清单覆盖多条 +
  未覆盖记账」；sharding 在「真中间件」前提上补 record-service 容器内
  `${SHARDING_MYSQL_HOST}` 服务名寻址；
- **sharding 的 MODIFIED 正文已出现「真实中间件」**，证明其 author 预设 middleware 的
  泛化已先落地（我在先处理 middleware、后处理 sharding 的顺序下验证）；
- 因此在 middleware 并入后，sharding 欲再整体替换同需求，会**丢弃** middleware 已并入的
  「可覆盖多条/未覆盖」内容——即 baseline 已被后续并入覆盖，属「对不上」。
- 按「冲突即停」：**add-sharding-host-parameterization 停手、不并入、不移入 archive**，
  回传冲突明细由指导侧决定是否为其新增一个独立补充变更。其 ADDED「sharding 数据源 host
  可由环境变量覆盖」为独立新增需求，未被阻挡，但与 MODIFIED 同属一个变更，故整体停手。

归档结果：14 个变更已并入并归档；`spec/changes/` 下仅剩 `add-sharding-host-parameterization`
（冲突停手）+ `archive/`。

## 约束（硬边界，同主规格）

- 只改名单内文件；`git diff --name-only HEAD` + untracked 与只改清单一致（`.trae/` 基线允许）。
- 不 push、不建 PR；不引入代码/脚本改动；不改迁移脚本 V21-V23。
- 认可唯一 `scripts/verify/mvn-verify.sh`；退出码 3 = 依赖来源不可判定，不得记通过。
- Git Bash 专用 `D:\git\Git\bin\bash.exe`（`cmd/bash` 为坏 WSL 桩，`ERROR_FILE_NOT_FOUND`），
  命令行不带中文（带中文触发 exit 127），输出重定向日志文件再读。
- 逐变更一个 commit（`docs(spec): 归档 <id> 并入主规格`），可回滚。

## 已归档清单（14，按提交顺序）

1. add-web-console-scaffold（Web 控制台工程脚手架）
2. add-web-auth-session（Web 控制台登录会话）
3. add-web-admin-console（Web 治理面控制台）
4. add-web-record-console（Web 业务控制台）
5. add-gateway-browser-cors（网关浏览器跨域）
6. add-db-migration-entrypoint（存量迁移入口与 schema 漂移可观测）
7. add-mailbox-contract-check（派发—回传契约两件套与清单比对）
8. add-transaction-boundary-audit（写路径事务边界）
9. add-perf-demo-innodb-flush（演示环境可选刷盘）
10. add-perf-g1-pause-target（JVM 停顿目标可选）
11. add-perf-mq-publish-async（提交事件异步发布）
12. add-perf-submit-aggregation-gate（跨请求提交聚合须先复测）
13. update-perf-optimized-defaults（提交路径默认批量插入）
14. add-middleware-it-coverage（真中间件路径自动覆盖）

冲突停手（1）：add-sharding-host-parameterization —— MODIFIED「真库端到端测试有确定路径」
baseline 被 add-middleware-it-coverage 先并入覆盖，见「冲突判定」。

## 只改清单

1. `spec/specs/sport-record-verify/spec.md`（头部 14 项 + 14 变更的需求并入）
2. `spec/changes/archive/{14 个已归档变更}/*`（proposal.md / tasks.json / spec-delta.md，git mv）
3. `work/mailbox/tasks/TASK-115/spec.md`
4. `work/mailbox/tasks/TASK-115/handoff.md`
5. `work/mailbox/PLAN.md`

未动：`add-sharding-host-parameterization`（冲突停手，保持原状）。

## 执行步骤

1. 逐变更：读 spec-delta → ADDED 追加到对应分区 / MODIFIED 以 spec-delta 文本替换基线 →
   头部列表追加 → `git mv` 入 archive → 核对 `git diff --cached --name-only` 只含预期
   （4 文件：spec.md + archive 内 3 文件）→ commit。
2. 冲突检测：处理到 sharding 时核对「真库端到端测试有确定路径」被 middleware 并入后的当前文本，
   判定 baseline 对不上 → 停手、记录冲突，不移入 archive。
3. 规避逐撤销回归：逐提交后 `git diff --stat` 核对单变更增量；全部 end-to-end 收尾用
   `mvn-verify.sh --mode=offline test` 跑全仓。
4. 收尾：`spec/changes/` 仅剩 archive/ + 冲突停手变更；头部 14 项；offline 283 零扰动；
   `mailbox-contract.sh --open=TASK-018,TASK-106` 收口后退出 0；词面自检三排除口径 ZERO-HIT。
5. 台账：TASK-115 两件套 + `work/mailbox/PLAN.md` 五条验收记录。

## 验收判据汇总

- 主规格头部归档列表新增 14 项，且 14 个变更的 ADDED/MODIFIED 已并入对应分区；
- `spec/changes/archive/` 含 14 个新增目录，`spec/changes/` 下同名未归档目录为 0；
- offline 283（17/19/31/80/81/49/6）不变（纯 spec 改动零扰动）；
- 契约收口提交后退出 0；词面自检三排除口径 0 命中；
- 冲突停手：add-sharding-host-parameterization 仍在 `spec/changes/`（非 archive），已回传明细。

## 完成定义

`handoff.md` 写入：只改清单 / 冲突判定与停手明细 / 14 个 commit 列表 / 实跑结论（offline 283、
目录状态、契约退出码、词面命中）/ 未决（sharding 后续处置留给指导侧拍板）/ 分期。临时文件用完即删。