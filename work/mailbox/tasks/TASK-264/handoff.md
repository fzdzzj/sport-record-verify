# TASK-264 执行交接（handoff）

> 状态：**已立项待派发**（2026-10-17 指导侧立项；开工基线 = main @ 44d505644b91f7b16ee4de2065f08009a0045705；MEDIUM 档，指导亲核三块把关，免独立复核子 agent）

## 1. 开工基线

- 开工基线：main @ `44d505644b91f7b16ee4de2065f08009a0045705`（TASK-263 外部终验补记笔）
- 任务书：`work/mailbox/tasks/TASK-264/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 44d5056` 双向全等）

- 改动清单（待实施回填）：
  - sql/02-record-db.sql
  - sql/migrations/add-idx-record-coalesce-time.sql
  - record-service/src/test/java/com/sportverify/record/db/SportRecordSqlContractTest.java
  - work/mailbox/tasks/TASK-264/handoff.md
- 零触碰面声明：除上述 4 文件外，全仓 100% 零触碰。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test` | 待测 | 待填（surefire 63/78/221/321/203/110/35 预期） |
| 2 | 真机 MySQL 验证与 EXPLAIN | `sport-verify-mysql` 容器内实跑 | 待测 | 待填 |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-264 --baseline=44d5056` | 待测 | 待填 |
| 4 | token 门 29 项 | `git grep -cF` 逐项 | 待测 | 待填（期望 SUM = 2133 = 2104 + 29） |
| 5 | 词面门 | `git grep -n -I -iE -f <模式文件>` | 待测 | 待填（ZERO_HIT + 探针阳性） |
| 6 | checkstyle delta=0 | `mvn-verify.sh --static=<模块>` | 待测 | 待填 |
| 7 | diff 净与行尾 | `git diff --check` / `git ls-files --eol` | 待测 | 待填 |

## 4. 条件项与非阻断登记（如实回填）

- 待回填

## 5. 收口自证

- 待回填
