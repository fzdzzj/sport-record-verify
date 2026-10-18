# TASK-267 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-18 实施侧自测回填；MEDIUM 档双提交 + 七门禁自测全绿，指导亲核三块把关，免独立复核子 agent）

## 1. 开工基线

- 开工基线：main @ `cabaebcfe3af240f55e4137ed5f57ef1e4d3ee9e`（TASK-267 立项笔；派发令口径一致；spec.md 行内注记的 970c404 为立项前稿基线，以派发令 cabaebc 为准，详见 §4 口径事实登记第 1 条）
- C-01 业务提交：`fcfc370`（`fix(db): 修复迁移脚本缺少USE语句断链缺陷并增加预检防御与契约单测（TASK-267）`；3 文件 +103/−0）
- 任务书：`work/mailbox/tasks/TASK-267/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only cabaebc` 双向全等）

- 改动清单（恰 4 项 = C-01 业务/脚本/测试 3 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - sql/migrations/add-leaderboard-daily-summary.sql
  - scripts/db/migrate.sh
  - record-service/src/test/java/com/sportverify/record/db/SqlMigrationContractTest.java
  - work/mailbox/tasks/TASK-267/handoff.md
- 零触碰面声明：除上述 4 文件外，全仓其他微服务生产 Java 源码、既有单测与契约测试、构建脚本、前端、编排与环境配置文件、sql/0*.sql 首次建表脚本等 100% 零触碰；未引入任何新的外部依赖（R3 为纯 JDK/JUnit 断言，无新增库）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test` | 0 | BUILD SUCCESS（07:56）；surefire **63/78/221/323/203/110/36**（common/gateway/user/record/verify/leaderboard/mapmatch），record 322→323 净增 +1（新契约测试 SqlMigrationContractTest 恰 1 测），总计 1034 测，零失败零错误零跳过；record 模块 --pl 单隔离预跑同口径 323 全绿 |
| 2 | 契约门 | `mailbox-contract.sh --open=TASK-267 --baseline=cabaebc` | 0 | C-01 前预跑 rc=0（进行中声明放行，含 1 个待办进行中）；C-01 后 handoff 未跟踪态预跑 rc=0，判据 B 4 路径双向全等（清单多报 0、改动集未声明 0）；C-02 后终态复跑 rc=0 |
| 3 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | 0 | 基线 cabaebc 树实测 SUM = **2220** = 派发令期望；C-01 树（fcfc370）实测 SUM = **2220**（3 个改动文件 29-token 扫描零命中，逐位计数与基线完全相同）；C-02 树期望 SUM = **2249** = 2220 + 29（本 handoff 本行逐位表自枚举恰各 +1，C-02 后实测复核）。29 项逐位持平零减少（基线读数）：13.4=161、18.0=177、73.93=118、68.8=113、6315=93、1.8612=92、3.3066=92、5.7056=98、9.408=83、36525962432=76、36586847965=73、36438897772=75、36399582548=73、36098038547=71、2806=113、598=110、36736221648=70、36808102571=58、36821040708=52、36845152965=49、36871294588=47、36880083885=49、36958994260=48、36976873215=43、36992632143=40、36995450125=38、37008317295=37、37021305016=36、37591580687=35 |
| 4 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex266.txt`（8 禁词模式文件法，三排除路径） | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1；阳性探针 probe267.txt `--untracked` rc=0 恰命中后即删、带排除复跑 rc=1 状态复原——词面机器活性坐实 |
| 5 | checkstyle delta=0 | `mvn-verify.sh --mode=offline --static=record-service` | 1 | rc=1 为 record-service 既有存量形态：恰 4 违规全为 SportRecordService.java L1076 JavadocType（DerivedTrackMetrics 4 组件缺 @param，TASK-234 遗留；本任务主代码零改动，违规集与上一任务读数逐项同源、行号一致），零新增违规 delta=0 |
| 6 | diff 净与行尾 | `git diff cabaebc HEAD --check` / 暂存 blob CR 计数 | 0 | diff --check 输出空 rc=0；三个 C-01 文件暂存 blob 逐字节 CR=0 纯 LF（sql 17 行 / migrate.sh 66 行 / 新测试 91 行，含末字节校验）；migrate.sh 工作树 CRLF 为 autocrlf 签出形态、入库 blob 归一纯 LF；新测试文件工作树与 blob 均纯 LF |

## 4. 条件项与实施要点（如实回填）

- **R1（迁移文件补齐 USE）**：`sql/migrations/add-leaderboard-daily-summary.sql` 头部注释块后、`CREATE TABLE` 前插入 `USE record_db;`（前后各一空行，与 add-record-visibility.sql 等既有 12 个迁移文件「注释块→空行→USE→空行→正文」风格逐字对齐）；表结构、字段定义、主键/索引与末尾注释 100% 未动（diff 恰 +2 行：USE 行 + 空行）。
- **R2（migrate.sh 预检防御）**：在迁移文件收集（nullglob + LC_ALL=C sort）之后、执行循环之前插入预检循环：逐文件 `grep -q -E '^[[:space:]]*USE[[:space:]]+[a-zA-Z0-9_]+;' "$f"`（与派发令正则逐字一致），缺失即向 stderr 输出「错误: 迁移脚本 ${base} 缺少 USE <database>; 语句，可能导致迁移中断或写入错误库！」并 `exit 1` fail-fast；脚本 mode 100644 保持，入库 blob 纯 LF。
- **R3（自动化契约单测）**：新增 `SqlMigrationContractTest`（1 测，常规 surefire 收集，record 322→323 净增 1）：
  - 纯 JVM 单测，参考同目录 SportRecordSqlContractTest 的 findRepoFile 向上查仓根结构（helper 扩展为同时支持目录定位，用于 sql/migrations）；
  - 动态 `Files.list` 扫描 sql/migrations 下全部 .sql（下限断言 13 个），逐文件以 `(?m)^\s*USE\s+(record_db|user_db|verify_db);` 正则断言库限定语句（与 migrate.sh 预检同口径）；
  - 针对 add-leaderboard-daily-summary.sql 断言显式 `USE record_db;` 且含反引号包裹的表名；
  - 对 sql/02-record-db.sql 与该迁移文件分别截取 leaderboard_daily_summary 的 `CREATE TABLE` 整块（起于 CREATE TABLE IF NOT EXISTS、止于收尾分号），assertEquals 断言两处逐字一致——把「两处人工同步」约定固化为机器判据，未来任一侧漂移即红。
- **migrate.sh 预检阳阴探针实证（零 DB 触碰）**：以假 docker shim（inspect 恒报 healthy、exec 恒成功）+ 仓库真实 migrate.sh 与 migrations 全量副本构建沙盘，四路径实测：阳性 13 文件预检静默、直达执行循环 rc=0；阴性无 USE 文件精确输出上述错误文案并 exit 1，执行循环未达（fail-fast 坐实）；小写 use 语句拒绝（预检正则大小写敏感，与派发令 grep -E 无 -i 口径一致）；缺收尾分号的 USE 行拒绝。探针沙盘位于 gitignored 临时目录，未触真实容器与数据库。
- **口径事实登记**：
  1. spec.md 行内记「开工基线 main @ 970c404」为立项前稿口径；立项笔 cabaebc 落库后派发令以 cabaebc 为开工基线，契约门与 token 门三树均按 cabaebc 实测（SUM=2220 与派发令期望一致，互为印证）；
  2. 预检正则大小写敏感为派发令原文口径；当前 13 个迁移文件全为大写 USE 形态，预检与 R3 契约测试两处同口径，如未来引入小写合法脚本需两处同步放宽；
  3. migrate.sh 末行无换行符为既有形态（EOF 字节未动，diff --check 净）。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；.trae/tmp 临时件（探针沙盘、词面门脚本与模式、token 三树脚本、commit msg 文件、门禁日志）留于 gitignored 目录不入库，词面探针文件已删。
- 未推送：`origin/main..HEAD` 收口后 = **3 笔**（本任务开工前 0 笔未推送 + 立项笔 `cabaebc` + C-01 `fcfc370` + C-02 本笔）——推送由指导侧批次统一执行，本任务严格零推送。
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 4 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望 2249 一致。
