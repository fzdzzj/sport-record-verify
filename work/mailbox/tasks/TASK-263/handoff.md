# TASK-263 执行交接（handoff）

> 状态：**已立项待实施**（2026-10-17 指导侧立项；开工基线 = 立项笔哈希；MEDIUM 档待执行侧实施）

## 1. 开工基线

- 开工基线：main @ `<立项笔哈希>`（TASK-263 立项笔；以开工实测 `git rev-parse HEAD` 回填 handoff 为准）
- 领先笔数实测：`git rev-list --count origin/main..HEAD` = **2 笔**（TASK-262 补记笔 `e2df483` + 本笔立项笔），禁单独推送，由指导侧批次统一执行。
- 任务书：`work/mailbox/tasks/TASK-263/spec.md`（白名单/修法/门禁/受限声明以 spec 为准）

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only <开工基线>` 双向全等）

- 改动清单待实施后回填（注意：改动清单节内除清单条目外，严禁在叙述文本中出现任何裸 path.ext 串，避免契约门 extract_claims 误判多报）

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test` | 待填 | surefire 基线 **63/78/219/320/203/110/35** 只增不减 |
| 2 | 前端三门禁 | `pnpm type-check` / `pnpm test` / `pnpm build` | 待填 | vitest 4 套 63 测全绿，build 主 chunk < 500KB，typed-router 保持净 |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-263 --baseline=<开工基线>` | 待填 | 判据 A 两件套齐，判据 B 清单一致 |
| 4 | token 门 29 项 | `git grep -cF` 逐项 | 待填 | 期望值：C-02 树 = 2075 + 29 = 2104 |
| 5 | 词面门 | `git grep -n -I -iE -f <模式文件>` | 待填 | 四 locale ZERO_HIT + 探针阳性对照 |
| 6 | checkstyle delta=0 | `mvn-verify.sh --static=<模块>` | 待填 | 纯净 worktree 双向坐实零新增 |
| 7 | diff 净与行尾 | `git diff --check` / `git ls-files --eol` | 待填 | diff 净，保持基线行尾 |

## 4. 条件项与非阻断登记（如实回填）

- 待执行侧实施后回填

## 5. 收口自证

- 待执行侧实施后回填
