# TASK-109 派发—回传契约的可校验化（判据 A 两件套 + 判据 B 清单比对）

## 目标

把 `work/mailbox` 任务包的"只改白名单 + 指定复跑命令 + 停止边界"从提示词文字落成可机械执行的校验。
完整方案与三处论证（脚本落点 / 判据 B 基线 / open 任务处置）见
`spec/changes/add-mailbox-contract-check/proposal.md`，能力规格见同目录 spec-delta.md。
本任务不改任何 Maven / Java / 前端 / SQL / compose / Dockerfile 本体。

## 先读文件

- `scripts/verify/README.md`、`scripts/verify/mvn-verify.sh`（退出码语义 0/1/3/2；本脚本与之并列、职责正交）
- `.github/workflows/ci.yml`（词面自检扫全部 tracked 文本，你的新增文件也在范围内）
- `spec/changes/archive/add-controlled-verify-entrypoint/`（既有变更三件套范式）
- `work/mailbox/tasks/handoff.md`（回传模板）与 `work/mailbox/PLAN.md` 开头收口清单

## 只改/新建文件（越界即视为未验收）

- 新建 `scripts/verify/mailbox-contract.sh`
- 修改 `scripts/verify/README.md`（补脚本用法，如适用）
- 新建 `spec/changes/add-mailbox-contract-check/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}`
- 新建 `work/mailbox/tasks/TASK-109/{spec.md, handoff.md}`
- 修改 `work/mailbox/PLAN.md`（按收口清单格式追加本任务验收记录）

明确不动：`scripts/verify/mvn-verify.sh`、`.github/workflows/ci.yml`、一切 Java / 前端 / SQL / compose / Dockerfile。

## 判据口径

- 判据 A：任务目录须同时含 `spec.md` 与 `handoff.md`。"仅 spec 无 handoff"视为进行中任务，经 `--open`
  显式声明后列待办放行，否则判据 A 失败；"仅 handoff"或"两者皆无但目录非空"判据 A 失败。
- 判据 B：回传「改动清单」节声明的只改文件集 vs 工作树相对开工基线（默认 `HEAD`）的实际改动集。
  有交叠（在途回传）→ 要求完全一致；无交叠 → 视为已收口不重审；来源不可判定 → 退出码 3。
- 退出码：0 通过 / 1 契约或结构不符 / 2 参数错 / 3 差异来源不可判定。

## 必须做的取证（每条都要先证能红，再让它绿）

1. 红 A：系统临时目录构造"仅 spec 无 handoff"的任务目录（不声明 `--open`）→ 非 0 退出。
2. 红 B：系统临时目录构造 spec+handoff 且"改动清单"与传入改动集不一致（改动集含清单未声明文件）→ 非 0 退出。
3. 绿：台账全量（`--open=TASK-018,TASK-106`）与 TASK-108 → 0 退出；TASK-109 自身判据 B 清单与工作树一致。
4. 红 pair 用完即删，不污染台账与工作树。

## 禁止

- 补写或修改历史任务的 handoff（涉嫌伪造记录）；不 commit、不 push、不建 PR。
- 命令行不带中文参数（本机 exit 127）；中文检索/读写用工具。
- 新 .sh 与仓内脚本行尾保持一致（`core.autocrlf=true`，先核对 mvn-verify.sh）。
- 公开文本（含脚本注释）不写词面自检禁用措辞。

## 验收命令（规范口径，禁止手拼 mvn）

```bash
bash scripts/verify/mailbox-contract.sh --ledger=work/mailbox/tasks --open=TASK-018,TASK-106   # 契约校验：退出 0
bash scripts/verify/mvn-verify.sh --mode=offline test   # 证明没碰坏：预期 281 全绿
```

## 完成定义

写 `work/mailbox/tasks/TASK-109/handoff.md`：只改清单（file:line 级）/ 红 A、红 B、绿三对原文含退出码 /
`mailbox-contract.sh` 各次输出摘要 + `mvn-verify.sh` 离线 281 模块用例数汇总 / PLAN.md 追加记录位置与行号 /
未决（停止边界触发项、需指导 agent 决定项）。回报 ≤300 字。