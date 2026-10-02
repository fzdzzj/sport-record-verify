# TASK-172 handoff：将分块批量标记提案 add-verify-outbox-relay-batch-mark 并入主规格并归档（第二轮规格收敛）

> 状态：**已收口**。§1 为偏差登记；§2–§10 为结论、逐门、交付与收口终检。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。

## 0. 开工规程与门禁

- 开工读数逐位核验（Level A）：HEAD `732c2b9a2b9bfc6713c99a443ced93d8e34a4f5c`、`origin/main` `763b837fb039f103e1428c9a3c2844673bec132d`、`git rev-list --left-right --count origin/main...main = 0 1`；工作树未跟踪仅既有 `spec/changes/add-verify-degrade-status-index/`（零触碰）＋本任务目录；主规格 3568 行 / 161 个 `### Requirement:`；archive 目录 60；在途 tracked 提案 6（归档后 5）；锚点「规则阈值可配置」与「outbox relay 批内并发投递默认关闭…」在 L1043/L1061。
- 任务书 SHA256 `6c0c30d021edcdc2903f664cdad6af90c9459c9874bc34b17ff7ebb87fe06bfc` 逐位一致；命令行为全程无中文（中文仅出现在 `.git/` 下临时提交信息文件与脚本文件中）。
- 规程：① 开工逐位核对；② 主规格并入（头部清单 +1、插入 2 个 Requirement、变更历史 +1）；③ `tasks.json` 闭环并 `git mv` 归档；④ C-01 提交；⑤ 门禁实测；⑥ PLAN 纯追加 + 两件套 + C-02 提交；⑦ 收口终检（契约门无参 / `git diff --check` / 词面门复跑）。

## 1. 偏差登记

### 1.1 任务书基线口径偏差（非仓库态不符，未自行订正仓库）

| 项 | 任务书记 | 本机实测 | 判定 |
| --- | --- | --- | --- |
| 主规格行数 | `3569` | `3568`（`git grep -c '^'` = 3568；CR==LF==3568，末 2 字节 `0d 0a`） | 与 PLAN.md 及 TASK-167/168/169/170/171 对**同一 HEAD** 的记录一致；`3569` 系把末尾换行按 split 计数多出的一行，属计数口径差 |
| 受保护 token `36880083885` | `7` | 行命中法基线 `4` | 基线/收口同法实测均 4，**只增不减成立**；任务书所载数值与本机行命中口径不同 |
| 受保护 token `36958994260` | `4` | 行命中法基线 `3` | 本记录提及后收口 `4`（+1，只增不减） |

> 结论：除上述计数口径差外，§3 全部实质读数（HEAD、origin/main、rev-list、工作树、161 个 Requirement、archive 60、在途 6、锚点行号）**逐位一致**。

### 1.2 执行侧偏差与说明

1. `tasks.json` Task 5 原步骤文本含「（未授权，不执行）」，与「标为 completed」语义冲突；按任务书意图将其文本同步为已落地事实（引用 TASK-169/171/172 与对应 handoff），**未改写任何已入库结论或历史数字**。
2. `spec.md` 经 Edit 插入后出现 1 处裸 LF 行尾（行 3609）；已按工作树既有 CRLF 归一，终态 `CR==LF==3610`、bareLF=0、无 BOM。
3. 全程逐路径 `add`，无 `git add -A`、无 `git stash`、未 `push`、未建 PR。

## 2. 一句话结论

**完成**：`add-verify-outbox-relay-batch-mark` 的规范需求已逐字并入主规格（头部清单 58→59、2 个 Requirement、变更历史 +1），提案 `tasks.json` 闭环为 allPass 并经 `git mv` 归档入 `spec/changes/archive/`；全项门禁通过（offline `36/41/33/103/143/59/10` rc=0、Checkstyle 严格 862 rc=1、词面门四形态 ZERO_HIT rc=1、契约门无参 rc=0、`git diff --check` rc=0）。**零生产行为变化**（未改任何 `.java`/`.kt`/`.yml`/`.properties`/`.sql`/`pom.xml`/`scripts/**`/`docs/**`）。

## 3. 只改清单

相对开工基线 `732c2b9a`，本任务实际改动的 7 条路径：

```
spec/specs/sport-record-verify/spec.md
spec/changes/archive/add-verify-outbox-relay-batch-mark/proposal.md
spec/changes/archive/add-verify-outbox-relay-batch-mark/specs/sport-record-verify/spec-delta.md
spec/changes/archive/add-verify-outbox-relay-batch-mark/tasks.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-172/spec.md
work/mailbox/tasks/TASK-172/handoff.md
```

其中前 4 条经 `git mv` 自 `spec/changes/add-verify-outbox-relay-batch-mark/` 移入 archive（`proposal.md`/`spec-delta.md` 呈 R100 逐字节保真、`tasks.json` 呈 R080）。显式零触碰：既有脏项 `spec/changes/add-verify-degrade-status-index/`（开工即存在，未跟踪）；其余 5 个在途未定/测量提案目录；一切生产代码、SQL、配置、既有脚本与既有测试用例；主规格外的一切规格文件。原始证据日志位于 `.git/`（不入库）。

## 4. 主规格并入细节

- **头部清单**：`## 本规范已归档以下提案` 列表末尾追加第 59 项 `add-verify-outbox-relay-batch-mark（outbox relay 可选有界分块标记 SENT，默认开启）`，条数 58→**59**。
- **需求正文**：在「规则阈值可配置」与「outbox relay 批内并发投递默认关闭且串行路径与引入前等价」之间插入两个 Requirement：`### Requirement: outbox relay 有界分块标记 SENT 默认开启`（含 5 项被授权语义变化及上界 + 3 个 Scenario）与 `### Requirement: outbox relay 分块标记关闭路径与逐行等价保留`（含 1 个 Scenario），逐字取自任务书 §2.2。
- **变更历史**：末尾追加 `- **add-verify-outbox-relay-batch-mark**：…引用变更 spec/changes/archive/add-verify-outbox-relay-batch-mark/。`
- **量化**：`### Requirement:` 161→**163**；行数 3568→**3610**（`git diff --numstat` = `42 0` 纯插入）；两个新标题在本文件 occ 各 1；`CR==LF==3610`、末 2 字节 `0d 0a`、无 BOM。

## 5. 提案闭环与归档

- `tasks.json`：Task 5 三步 `completed:true`、`passes:true`；追加 Task 7「归档阶段」（2 步 completed）；JSON 经 `ConvertFrom-Json` 校验通过，7 个任务全 `passes=true`（allPass=True）；文件保持 LF、无 BOM。
- `git mv spec/changes/add-verify-outbox-relay-batch-mark spec/changes/archive/add-verify-outbox-relay-batch-mark`（rc=0）；`git diff --cached --name-status` 呈 `R100`（proposal/spec-delta）+ `R080`（tasks.json）。
- 不变式：archive 目录 60→**61**；头部清单 59；`archive 目录数 − 头部清单条数 = 恰 2 项合法例外`（`add-microservice-skeleton`、`add-sharding-host-parameterization`）保持；在途 tracked 6→**5**，恰为任务书零触碰名单。

## 6. 逐门 G0–G8

| 门 | 判据 | 实测 | 结果 |
| --- | --- | --- | --- |
| G0 开工基线 | HEAD/origin/main/rev-list/工作树/任务书 SHA256 | 逐位一致（见 §0） | 过 |
| G1 offline 全模块 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | 七模块 `36/41/33/103/143/59/10`、Failures/Errors/Skipped 全 0、BUILD SUCCESS、rc=**0** | 过 |
| G2 Checkstyle | `bash scripts/verify/mvn-verify.sh --mode=offline --static=verify-service` | 第 1 段 BUILD SUCCESS；第 2 段 `You have 862 Checkstyle violations`，rc=**1** | 过（严格 862 未变） |
| G3 词面门 | 四形态 `git grep -iE "<禁用措辞正则>"` | default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8` 全 **ZERO_HIT rc=1** | 过 |
| G4 契约门 | `bash scripts/verify/mailbox-contract.sh`（无参） | 收口后 rc=**0**（两件套齐 + 判据 B 清单一致） | 过 |
| G5 空白门 | `git diff --check` | rc=**0** | 过 |
| G6 规格不变式 | archive 数 − 头部清单数 = 2 | 61 − 59 = 2 | 过 |
| G7 纯文档 | `git diff` 无生产/配置/脚本文档外文件 | 仅 §3 的 7 条 §.md/§.json 路径 | 过 |
| G8 受保护 token | PLAN.md 行命中数只增不减 | 见 §7，无一减少 | 过 |

## 7. 受保护 token（PLAN.md 行命中数，基线 vs 收口）

| token | 基线 | 收口 | token | 基线 | 收口 |
| --- | --- | --- | --- | --- | --- |
| `13.4` | 16 | 16 | `598` | 12 | 12 |
| `18.0` | 18 | 18 | `36736221648` | 11 | 11 |
| `73.93` | 17 | 17 | `36808102571` | 6 | 6 |
| `68.8` | 13 | 13 | `36821040708` | 4 | 4 |
| `6315` | 14 | 14 | `36845152965` | 3 | 3 |
| `1.8612` | 13 | 13 | `36871294588` | 3 | 3 |
| `3.3066` | 13 | 13 | `36880083885` | 4 | 4 |
| `5.7056` | 13 | 13 | `36525962432` | 13 | 13 |
| `9.408` | 13 | 13 | `36586847965` | 12 | 12 |
| `2806` | 19 | 19 | `36438897772` | 13 | 13 |
| `36399582548` | 12 | 12 | `36098038547` | 12 | 12 |
| `36958994260` | 3 | **4** | | | |

无任一 token 减少（本记录为纯追加）。`36880083885` 与 `36958994260` 的任务书所载数值系不同计数口径，见 §1.1。

## 8. 交付物

- 主规格：`spec/specs/sport-record-verify/spec.md`（头部 +1、2 个 Requirement + 4 个 Scenario、变更历史 +1）。
- 归档提案：`spec/changes/archive/add-verify-outbox-relay-batch-mark/`（proposal.md、specs/sport-record-verify/spec-delta.md、tasks.json）。
- 台账：`work/mailbox/PLAN.md`（纯追加 TASK-172 验收记录）。
- 任务两件套：`work/mailbox/tasks/TASK-172/spec.md`（零修改，SHA256 逐位一致）、本 `handoff.md`。

## 9. 未覆盖项与不得推出的结论

1. `--mode=online` 与 CI 未跑；`--static` 在 checkstyle 处即失败，spotbugs/pmd **未覆盖**；**未达外部门槛**（未 push）。
2. 本轮**零生产行为变化**，不得据并入/归档动作推出任何吞吐、延迟或容量收益。
3. 不翻案 TASK-143~171 任何结论与历史数字；`relay-batch-mark-enabled`（true）与 `relay-send-concurrency`（2）生产默认态未在本轮改动。
4. 主规格行数/受保护 token 的任务书所载数值存在计数口径差（见 §1.1），不得据此判定仓库态不符。

## 10. 提交回填

| 提交 | 内容 | 文件数 / 行数 |
| --- | --- | --- |
| `58e9c0f` | C-01 `docs(spec): 将在途提案 add-verify-outbox-relay-batch-mark 并入主规格并归档（TASK-172）`（主规格并入 + tasks.json + git mv 归档） | 4 files / +67 −9 |
| `b83c02c` | C-02 `docs(mailbox): 登记 TASK-172 验收记录与任务两件套（TASK-172）`（PLAN 纯追加 + 两件套） | 3 files / +257 |
| `（C-03）` | C-03 `docs(mailbox): 回填 TASK-172 handoff 收口提交哈希与终检读数（TASK-172）`（仅本 `handoff.md`） | 1 file |

- 收口终检读数：契约门无参 `rc=0`（判据 A 两件套齐 + 判据 B 清单一致）、`git diff --check` `rc=0`、词面门四形态复跑全 `ZERO_HIT rc=1`。
- 起点 `git rev-list --left-right --count origin/main...main = 0 1`；未 push、未建 PR。
