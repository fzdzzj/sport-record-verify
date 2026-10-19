# TASK-278 实施交接台账（handoff）

> 状态：**待实施**（2026-10-19 指导侧立项；FULL 档强制独立复核；开工基线 main @ `<TASK-277 封盘笔>`；本台账由执行子 Agent 落地 C-01/C-02 后回填，回填时逐项保持实测口径并给出命令与读数）

## 0. 阅读顺序与申报义务

- 先读 `work/mailbox/tasks/TASK-278/spec.md` 全文（含 §五预注册裁决与停止条件——裁决已定案无需申报，冲突即停）；
- 实施中任何与 spec 预期不符的实测（行号漂移、范式冲突、白名单需增删），先登记后处置，禁止静默适配；
- 关键决策需在 §5 逐项留痕（背景 / 选项 / 采纳理由 / 证据）。

## 1. 基线与提交

- 开工基线：main @ 立项笔（哈希以实施派发书为准；开工实测 `git status --porcelain` 为空后填写哈希）；
- 任务书：`work/mailbox/tasks/TASK-278/spec.md`；
- C-01：（待执行子 Agent 落地后回填，`<type>(<scope>): <描述>（TASK-278）`，附 `git show --numstat` 实测行数）；
- C-02：本笔仅本台账 1 文件；同一笔内无法自指哈希，C-02 哈希由指导侧封盘时以 `git log -1` 回填；
- 提交链核验：`git log --oneline <baseline>..HEAD` 恰 2 笔线性、`origin/main..HEAD` 严格零推送；
- 白名单核对：改动集 ⊆ spec §三 13 项上限，双向全等。

## 2. 改动清单

（逐行路径 token 供判据 B 机器比对；行数为 `git show --numstat` 实测；未进入改动集的白名单项需注明原因）

## 3. 七门禁实测读数

| # | 门禁 | 命令 | rc | 关键读数 |
| :- | :--- | :--- | :-: | :--- |
| 1 | offline 全量 | `scripts/verify/mvn-verify.sh --mode=offline test` | | 基线 1197 = 63/79/250/406/203/139/57 只增不减 |
| 2 | 静态门 | `--static=record-service` / `--static=leaderboard-service` | | Checkstyle 0 / SpotBugs High=0 / PMD 通过 |
| 3 | 前端三件套 | type-check / test / e2e | | 零前端改动全绿保持 |
| 4 | 契约门 | `mailbox-contract.sh --open=TASK-278 --baseline=<baseline>` | | 判据 A + 判据 B 双向全等 |
| 5 | token 门 | 三树 `git grep -cF` 29 项 | | 只增不减（口径：C-02 树 = 基线 + 29） |
| 6 | 词面门 | 四 locale + 阳性对照 | | ZERO_HIT |
| 7 | diff 净与行尾 | `--check` / blob CR / BOM | | 空 / 0 / 无 BOM |
| 8 | DDL 幂等 | migrations scratch 库双跑 | | 二跑全跳过 |

## 4. R0–R5 实施要点

（R0 基线确认 / R1 锚点表 DDL / R2 record 锚点化重构 / R3 对账任务 / R4 顺手项 / R5 收口，逐项实测留痕）

## 5. 决策记录与偏差说明

## 6. token 门 29 项逐项读数

## 7. 收口自证与回滚

- `git status --porcelain` 收口后为空；`origin/main..HEAD` 恰 2 笔线性，严格零推送；
- 回滚：`git revert` C-01 + C-02 + DDL 反向（DROP TABLE IF EXISTS club_distance_contribution）即回到挂账态，无外部残留；
- 证据文件（本机 `.trae/tmp/`，非入库）：
