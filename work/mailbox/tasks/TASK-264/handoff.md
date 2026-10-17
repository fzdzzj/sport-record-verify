# TASK-264 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-17 实施回填；开工基线 main @ 8e34aa215fe65633c9fbbdc40a9a9eb9fe458e5d；MEDIUM 档，指导亲核三块把关，免独立复核子 agent）

## 1. 开工基线

- 开工基线：main @ `8e34aa215fe65633c9fbbdc40a9a9eb9fe458e5d`（TASK-264 立项笔；`git rev-parse HEAD~1` 实测一致）
- C-01 业务提交：`13453f2`（`fix(sql,record): 订正函数索引双括号语法阻断并新增SQL契约测试与收尾R5（TASK-264）`）
- 任务书：`work/mailbox/tasks/TASK-264/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 8e34aa2` 双向全等）

- 改动清单（恰 4 项 = C-01 业务/配置/测试 3 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - sql/02-record-db.sql
  - sql/migrations/add-idx-record-coalesce-time.sql
  - record-service/src/test/java/com/sportverify/record/db/SportRecordSqlContractTest.java
  - work/mailbox/tasks/TASK-264/handoff.md
- 零触碰面声明：除上述 4 文件外，全仓代码、其它 sql 脚本、pom.xml、package.json、web 前端、docker-compose 等 100% 零触碰；Java 业务代码零触碰（pageFeed 保持原状）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（经 D:\git\Git\usr\bin\bash.exe --login） | 0 | BUILD SUCCESS（10:09 min）；surefire **63/78/221/321/203/110/35**（common/gateway/user/record/verify/leaderboard/mapmatch）——record 320→321 净增 1 契约测，其余六模块严格持平，零失败零跳过 |
| 2 | 真机 MySQL 验证与 EXPLAIN | `sport-verify-mysql` 容器内实跑 | 0 | 迁移脚本 `add-idx-record-coalesce-time.sql` 顺利执行通过并幂等验证；EXPLAIN SELECT * FROM sport_record WHERE user_id = 11 AND COALESCE(start_time, created_at) >= '2026-10-01 00:00:00' 实测读数：type=range, key=idx_user_coalesce_time, key_len=14, rows=1, filtered=100.00, Extra=Using where；成功执行 `DROP DATABASE IF EXISTS task260_scratch;` 清理临时库 |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-264 --baseline=8e34aa2` | 0 | 判据 A 两件套齐；判据 B 4 路径双向全等（清单多报 0、改动集未声明 0） |
| 4 | token 门 29 项 | `git grep -cF` 逐项（全仓 tracked 累加口径） | 0 | 终树实测 SUM = **2133** = 派发书期望绝对口径（2104 + 29）；29 项逐位持平零减少（因本 handoff 自枚举效应各 +1）：13.4=158、18.0=174、73.93=115、68.8=110、6315=90、1.8612=89、3.3066=89、5.7056=95、9.408=80、36525962432=73、36586847965=70、36438897772=72、36399582548=70、36098038547=68、2806=110、598=107、36736221648=67、36808102571=55、36821040708=49、36845152965=46、36871294588=44、36880083885=46、36958994260=45、36976873215=40、36992632143=37、36995450125=35、37008317295=34、37021305016=33、37591580687=32 |
| 5 | 词面门 | `git grep -n -I -iE -f <模式文件>`（CI 8 禁词权威口径，三排除路径） | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1；模式文件 od 实测 LF 无 \r；阳性探针 probe264.txt `--untracked` rc=0 恰命中后即删、复跑 rc=1 状态复原——词面机器活性坐实 |
| 6 | checkstyle delta=0 | `mvn-verify.sh --mode=offline --static=record-service` | 1（存量形态） | 模块扫描恰 4 违规（SportRecordService.java L1075 JavadocType 4 组件缺 @param，TASK-234 遗留）；新增纯 JVM 测试 SportRecordSqlContractTest.java 零违规；Java 业务代码零触碰，delta=0 |
| 7 | diff 净与行尾 | `git diff --check` / `git ls-files --eol` | 0 | diff --check 净（rc=0）；修改文件保持既有行尾（LF），新测试文件纯 LF + 末字节 0x0A 结尾 |

## 4. 条件项与非阻断登记（如实回填）

- **R1 N2 函数索引语法订正与真机实测**：
  - 在 `sql/02-record-db.sql` L34 与 `sql/migrations/add-idx-record-coalesce-time.sql` L2 头注释与 L23 中，去除了包裹普通列 `user_id` 的多余外层括号，订正为 MySQL 8.0 合法复合函数索引语法 `KEY idx_user_coalesce_time (user_id, (COALESCE(start_time, created_at)))`；
  - 新增单测 `SportRecordSqlContractTest`，纯 JVM 读取两 SQL 脚本，断言语法正确性并防御性排除了包含 `((user_id)` 双括号的非法形态；
  - 在 `sport-verify-mysql` 容器中成功执行迁移脚本并验证幂等性，EXPLAIN 证实 `user_id = 11 AND COALESCE(start_time, created_at) >= '2026-10-01 00:00:00'` 命中 `idx_user_coalesce_time` 进行范围扫描（`type=range, key_len=14`）；
- **R2 R5 查询形态收尾决策与 scratch 库清理**：
  - `SportRecordService.pageFeed` 既有实现零改动，作为已知有界性能特征（LIMIT 20）收敛关闭，严格遵守全局禁加索引红线；
  - 容器内执行 `DROP DATABASE IF EXISTS task260_scratch;` 成功清理临时库。
- **受限声明沿 spec §5 三项**：
  1. 全局禁加索引红线严格遵守，pageFeed 不引入新索引；
  2. MySQL 8.0.13+ 函数索引特性在低版本（如 MySQL 5.7）不兼容，本项目依赖 MySQL 8.0+ 运行环境；
  3. 演示库 `record_db` 存量数据演进留存，不影响单元测试。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；`.trae/tmp` 临时件收口清理。
- 未推送：`origin/main..HEAD` 收口后 = **4 笔**（TASK-263 补记笔 `44d5056` + 立项笔 `8e34aa2` + C-01 `13453f2` + C-02）——推送由指导侧批次统一执行，本笔严格零推送。
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 4 路径双向全等）。
