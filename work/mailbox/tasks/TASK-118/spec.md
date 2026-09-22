# TASK-118 台账禁用词原文改写（解锁下次 push）

## 背景

`.github/workflows/ci.yml` 的 `Public docs wording self-check` 步骤扫描**全部 tracked 文本载体**
（`git grep -n -I -iE <禁用词表>`），仅显式排除三处：`spec/changes/archive/**`、`docs/internal/**`、
`.github/workflows/ci.yml` 自身。命中即该步骤失败。

当前有两行把**禁用词原文**写进了台账，用途是描述一个 locale 伪影现象（引用该词以说明"命中"是误判）。
这两行都在扫描范围内，且都属**已入库但未 push** 的内容 —— 一旦下次 push，该步骤必红：

| 落点 | 内容性质 |
| --- | --- |
| `work/mailbox/PLAN.md` 第 353 行 | TASK-018 验收记录的「词面自检」行 |
| `work/mailbox/tasks/TASK-106/handoff.md` 第 96–97 行 | TASK-106 契约自证节的「附带发现」 |

`work/mailbox/PLAN.md` 第 372 行（TASK-018 记录）已把该地雷登记为「下次 push 必炸」，
本任务即该登记项的处置。

## 目标

改写上述两处，**语义完整保留**，但不再出现禁用词原文，也不得出现禁用词表中任何词。
改写处统一改用「词面自检的禁用词」一类**指代表述**。

需保留的语义（三点，缺一即语义丢失）：

1. 本机 MSYS `git grep -i` 在 `C.UTF-8`（本机默认 locale）下，把某既有 Java 文件里一个汉字的
   **第二字节**按 cp1252 的 `Ž`/`ž` 大小写等价规则**误判**为词面自检的禁用词命中；
2. 该文件本任务/本任务前一次改动**未触碰**，且属上次 CI 绿（run `35671465068`）已含内容；
3. 因此判为 **locale 伪影、非真命中**；`LC_ALL=C` 下全量 ZERO-HIT。

## 只改清单（contract 判据 B，与实际受版本控制改动集一致）

- work/mailbox/PLAN.md
- work/mailbox/tasks/TASK-106/handoff.md
- work/mailbox/tasks/TASK-118/spec.md
- work/mailbox/tasks/TASK-118/handoff.md

## 范围外（越界即视为未验收）

- 不改这两行之外的**既有**台账内容（`PLAN.md` 第 353 行按本任务改写；TASK-106 handoff 仅改 96–97 行附近）
- 不改 `.github/workflows/ci.yml`（禁用词表与其示例词是 CI 自身承载，不排除则该门槛永远红）
- 不动 `spec/changes/archive/**` 与 `docs/internal/**`（二者本就在 CI 排除范围内）
- 不 push、不建 PR、不改 GitHub 设置
- 不引入新依赖/新 Maven 插件；不改契约脚本与统一验收入口本体

## 取证要求（先红后绿，两对都要留原文）

模式写进 `.trae/tmp/wording-check-118.sh`（UTF-8，命令行保持纯 ASCII，本机命令行含中文会 exit 127；
该目录不入库），`if git grep ... then exit 1; fi` 与 CI 同构：

- **红（改前）**：CI 同款 `git grep -n -I -iE` + 三排除 → 命中上表 2 行。
- **绿（改后）**：同命令 → 零命中，`grep` 以退出码 1 返回（CI 结构下即通过）。
- **两种 locale 各跑一次**：`LC_ALL=C` 与默认（本机 `C.UTF-8`）都必须零命中。

## 验收命令

1. `bash .trae/tmp/wording-check-118.sh` → 两种 locale 全 ZERO-HIT
2. `bash scripts/verify/mvn-verify.sh --mode=offline test` → 284 全绿零扰动（纯台账文本改动）
3. `bash scripts/verify/mailbox-contract.sh` → 退出码 0

## 完成定义

- `work/mailbox/tasks/TASK-118/handoff.md`：两处改写的 before/after 对照 + 红绿两对取证（命令/关键输出/退出码）
  + offline 模块汇总 + 契约退出码 + 「待主 agent 决定」（没有写无）
- 收口记录按 `PLAN.md` 开头「收口清单」格式追加（绑定 commit id、门槛来源、未 push 显式标注）
- 回传只改清单 / 红绿两对取证 / offline 模块汇总 / 契约退出码 / 未决

## 约束

- 禁用 `git stash`（历史 `.git` 事故）：如需临时态，用 `git show HEAD:<path>` + 不带 `-p` 的 `cp`
- 最终回传与提交信息不得含禁用词表中任何词
- 分批 commit：conventional commits 前缀 + 中文描述，经 `git commit -F <UTF-8 文件>`
