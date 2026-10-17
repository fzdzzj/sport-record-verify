# TASK-260 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-17 执行子agent 实施批 G-2 全流程：R1/R2/R3/R4 落库 + R5 触发派发 §6 停止条件零改动收口；门禁/证据/偏差回填如下，独立复核五块与指导侧终裁待行）

## 1. 开工基线

- 开工基线：main @ `27181dc0d5485d7bbeb1357ae0a0ee18ada08666`（TASK-260 立项笔；`git rev-parse HEAD` 实测与派发要求 27181dc 逐位一致，开工时工作树 porcelain 空）
- 任务书：`work/mailbox/tasks/TASK-260/spec.md`（白名单/修法 R1-R5/门禁/受限声明以 spec 为准）

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 27181dc` 双向全等）

- 改动清单（7 项 = C-01 业务/测试 5 + C-02 台账 2，逐行路径 token 供判据 B 机器比对）：
  - record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
  - record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapperSqlContractTest.java
  - mapmatch-service/src/main/resources/application.yml
  - work/mailbox/tasks/TASK-260/spec.md
  - work/mailbox/tasks/TASK-260/handoff.md
- 未跟踪文件核验（C-01 前 `git ls-files -o --exclude-standard`）：恰 1 项 = 白名单第 4 项新测试文件（R2 落点，§4 登记）；.trae/tmp 临时件属 gitignore 不纳入比对。
- spec §2 白名单双向全等自证：业务/测试 5 文件逐项落改（R1/R2/R3/R4 全落点在其内，R5 零改动见 §3）；台账 5 项中 spec/handoff 由本笔 C-02 回填，PLAN/BACKLOG/HANDOVER 本笔零动（沿 TASK-259 先例由指导侧封盘笔滚动）；白名单外全仓零 diff（api 模块、user/verify/gateway 服务 src/test、全部 pom.xml、package.json、sql/、scripts/、web/、docker-compose*.yml、.github/ 均零触碰）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（经 D:\git\Git\usr\bin\bash.exe --login） | 0 | BUILD SUCCESS（08:18 min，2026-10-17 10:14）；surefire **63/78/215/310/200/109/35**（common/gateway/user/record/verify/leaderboard/mapmatch）——基线 63/78/215/308/200/108/35 只增不减：record 308→310 恰 +2（§4 两新测，零删减零跳过）、leaderboard 108→109 恰 +1（SQL 契约测试，零删减）、mapmatch 35 持平（yml 注释级零行为，照跑） |
| 2 | R5 EXPLAIN（scratch 库时点快照） | 真机 EXPLAIN（task260_scratch，禁用生产库） | — | 时点 2026-10-17 约 09:50–10:00，MySQL 8.0.46 容器 sport-verify-mysql；scratch 库 task260_scratch 由仓库权威 DDL（sql/02-record-db.sql + add-track-archive-column-and-idx 迁移，sed 改名灌入；函数索引 KEY 行按 8.0.46 可建语法订正后建出，见 §4 N2）+ 演示库 record_db.sport_record 167,365 行/24 用户按列名映射只读复制（visibility 全 0=列默认 PUBLIC，演示数据早于列引入）、ANALYZE 后实测。代表性场景 caller=11、friends=[12]（互为好友，各 ~10.4k 行）：①G13 目标 auth-on 分支 `user_id IN (11,12) AND (user_id=11 OR visibility IN (0,1)) ORDER BY created_at DESC LIMIT 20` → type=range, key=idx_user_time, rows=36,668, Extra=Using index condition; Using where; **Using filesort——filesort 坐实**；②候选形态 A 顶层 OR 拆分（spec §3 示例改形）计划与原形态逐位相同仍 Using filesort——**改形无效**；③候选形态 B UNION ALL 有界改写：两臂均 ref + Backward index scan 无排序、外层 union result 仍 **Using temporary; Using filesort**（输入收敛 ≤40 行）且 wrapper 不可表达、需手写 SQL 重设计分页语义，超出「OR 拆分/条件重排」范畴；④对照：degrade 分支（visibility=0）全表扫 166,135 行 + filesort（非 G13 点名形态，一并登记）、MP 分页 count 查询 range 36,668 无 filesort。**裁决：filesort 坐实但 spec 允许的查询形态改写不能消除（消除实质排序须引入 (visibility, created_at) 类索引 = spec 红线）→ 触发派发 §6 停止条件「R5 需加索引才能消除 filesort」，R5 零改动收口，pageFeed 本笔零改动，待指导侧终裁（UNION ALL 有界改写如立项属独立 perf 课题）** |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-260 --baseline=27181dc` | 0 | C-01 前工作树态（5 业务 + 2 台账已编辑）：判据 A 通过 + 判据 B 恰 7 路径 token 与实际改动集双向全等 rc=0；C-01 后态（C-01 `3ab2f04`，台账 2 文件在途）rc=0 判据 B 通过；C-02 后终态复跑 rc=0（收官汇报） |
| 4 | token 门 29 项 | `git grep -cF` 逐项（清单沿 TASK-181 handoff §6 全 29 token，全仓匹配行累加口径沿 TASK-257） | 0 | SUM 基线 27181dc = **2017** vs 工作树 = **2017**，29 项逐位持平零减少（13.4=154、18.0=170、73.93=111、68.8=106、6315=86、1.8612=85、3.3066=85、5.7056=91、9.408=76、36525962432=69、36586847965=66、36438897772=68、36399582548=66、36098038547=64、2806=106、598=103、36736221648=63、36808102571=51、36821040708=45、36845152965=42、36871294588=40、36880083885=42、36958994260=41、36976873215=36、36992632143=33、36995450125=31、37008317295=30、37021305016=29、37591580687=28）；改动集 5 业务文件联合查询零命中（rc=1 通过态） |
| 5 | 词面门（模式文件法 + 探针阳性对照） | `git grep -n -I -iE -f <模式文件>`（CI 权威口径：8 禁词形态 + 三排除路径） | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1 通过态；掩写串 -F 严格全仓 tracked 清零 rc=1；模式文件 od 实测 LF 无 \r（防 CRLF 假阴性先例，末形态 附录?A 不被截断）；阳性探针（仓根 .txt，UTF-8 转义写入非命令行中文）`--untracked` rc=0 恰 1 行命中后即删、复跑 rc=1 状态复原——词面机器活性坐实 |
| 6 | checkstyle delta | `mvn-verify.sh --static=<模块>` + 纯净 worktree 基线对照 | 1（先例预期形态） | 本笔工作树：record-service rc=1 恰 **4** 违规（SportRecordService.java L1076 JavadocType 4 组件缺 @param，DerivedTrackMetrics TASK-234 遗留）、leaderboard-service rc=0 恰 **0**；基线 27181dc 纯净 worktree（.trae/tmp 独立 worktree，用后 git worktree remove）同法实测 record 恰 **4** 违规同集合于 L1059、leaderboard **0**——本笔 LeaderboardContributionMapper 有 diff 不能沿「零 diff⇒基线同值」论证，双向实测坐实：违规集合同构、行位移 +17 恰为本笔 import/注释/块插入量、本笔改动行 0 违规 ⇒ **delta=0**；record failOnViolation 于第 1 goal 中止故 spotbugs/pmd 未及（沿 TASK-254/255/259 record 静态门同形态，停手条件核验非七门禁） |
| 7 | diff 净与行尾 | `git diff --check` / `git ls-files --eol` | 0 | diff --check 净（C-01 前 rc=0）；4 个修改文件保持既有行尾（record 2 文件 w/crlf、leaderboard mapper 与 mapmatch yml w/lf，blobs 均 i/lf，ls-files --eol 逐文件实测）；新测试文件纯 LF + 末字节 0x0A（od 实测） |

## 4. 条件项与非阻断登记（如实回填）

- **R2 测试落点登记（C-01 前）**：`leaderboard-service/src/test/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapperSqlContractTest.java`（新建）——按仓内既有测试布局：注解 @Select SQL 契约的纯 JVM 可执行落点沿 TASK-142 `VerifyEventOutboxMapperSqlContractTest` 先例（反射取注解 SQL 文本断言）；真引擎同分排序行为属 scratch MySQL IT 范畴（LeaderboardDailySummaryMapperMysqlIT 形态）不在 offline 门内。断言两契约：`ORDER BY totalDistance DESC, user_id ASC`（同分 tie-break）+ `LIMIT #{size}`（容量上限）。
- **TableInfoHelper 预注册**：R1 两测（owner 重写 + staleSnapshot 新测）与 R4 新测均沿本测试文件既有惯用形态 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class)`（同 L694/L1162/L1383 先例）——LambdaUpdateWrapper.getSqlSet()/getSqlSegment() 需列缓存渲染（TASK-221 先例）。
- **R1 并发语义承载（spec §5.1 预登记）**：mock 语义断言——selectById 返回 VERIFYING/v1 旧快照场景，断言所发 UPDATE 的 SET 列恰 visibility 单列（getSqlSet 不含 status/version）、实体旧快照 status/version/visibility 零改动、updateById 零触发；真实毫秒级双线程竞态实测不强制。
- **R4 口径断言**：新测断言 SQL 段含 `COALESCE(start_time, created_at) >=` + 否定断言不再出现裸 `start_time >=` + 窗口参数值（startDate.atStartOfDay()）；既有两测（withAllFilters/individualFilters）口径断言同步订正为 COALESCE 形态（原 start_time >=/<= 断言改写，仅断言面、无条件面回退）。
- **R5 双结局**：落「filesort 坐实、改形不可消」结局（非「实测无 filesort」结局）——触发 §6 停止条件，零改动收口；EXPLAIN 全读数见 §3 行 2。
- **N1（非阻断）**：演示库（record_db 实况卷）schema 落后仓库 DDL 多个迁移（无 visibility 列 / idx_status_created / idx_user_coalesce_time / idx_archive），R5 scratch 因此按仓库权威 DDL 重建而非现场复制行结构；演示库实况与本笔零关联，登记备查。
- **N2（非阻断，建议后续课题）**：`sql/02-record-db.sql` L34 与 `sql/migrations/add-idx-record-coalesce-time.sql` 的函数索引语法 `((user_id), (COALESCE(start_time, created_at)))` 经 MySQL 8.0.46 实证 ERROR 3762（函数索引位含裸列不被支持；scratch 对照实证单括号 `(user_id, (COALESCE(...)))` 可建）——**新装库 docker-entrypoint-initdb.d 初始化会在该语句中断、迁移脚本同因失败**（实况演示库无此索引佐证其从未建成）；sql/ 属本笔零触碰红线，仅登记不动，需独立课题订正（E23 R5 的 sargable 收益实况未兑现；R4 的 COALESCE 过滤在实况库上暂走 user_id 前缀 + 回表，与修前同形，不构成回归）。
- **N3（非阻断）**：本机 MySQL 容器 sport-verify-mysql 开工时不存在（数据卷在），`docker compose up -d mysql` 重建后以 .env 口令接入（既有记忆先例 root/root 已失效）；R5 scratch 库 task260_scratch 用后留存（DROP 与容器停启由指导侧裁决；容器为本地演示环境既有件，EXPLAIN 全程只读演示库）。
- **N4（受限沿 spec §5 四项）**：G2 并发窗口毫秒级真实双线程竞态不强制（mock 语义断言承载）；G13 EXPLAIN 为 scratch 时点快照（数据量随演示库演化，登记于 §3 行 2）；G11 仅影响存量 start_time=NULL 行（TASK-235 前旧数据）；Nacos 运行时配置未核。

## 5. 收口自证

- `git status --porcelain` 输出为空：C-02 后实测为空（原文见收官汇报）；.trae/tmp 临时件（offline 日志 / static 两读数日志 / tokens 清单 / lex 模式与掩写模式 / msg.txt / task260 scratch 注入件）用后清理；基线纯净 worktree 已 `git worktree remove`。
