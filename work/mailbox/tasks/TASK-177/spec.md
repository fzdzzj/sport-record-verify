# 任务书：TASK-177 add-verify-degrade-status-index 复活与实施

派发：指导 Agent（2026-10-07）。本任务书与提案三件套由指导侧亲笔；执行侧负责实施与台账闭环（C-01/C-02 两笔），不得改动派发笔内容。

## 0. 硬约束与红线

1. **唯一 mvn 入口**：一切官方门禁只经 `bash scripts/verify/mvn-verify.sh`。唯一例外：本任务 IT（`SportRecordIndexMysqlIT`）因 harness `--it` 分支硬编码 leaderboard 清单（脚本 L33-36）无法承载，允许以镜像 harness offline 纪律的完整命令直跑（`-o -s .mvn-settings.xml -pl record-service -am -Dtest=SportRecordIndexMysqlIT -Dsurefire.failIfNoSpecifiedTests=false`），完整命令与退出码登记入 verification.md；harness IT 清单缺口登记为 Notice。
2. **bash 纪律**：仅 `D:\git\Git\bin\bash.exe` 且仅执行脚本文件（禁内联 bash -c，PowerShell 会吞内联引号）；临时脚本落仓库根 `*.tmp`（已 gitignore），用毕删除。
3. **git 纪律**：禁 push / PR / `add -A` / `add .` / stash；逐路径显式 add；提交信息经 `-F` 文件落盘（中文命令行 rc=127 坑）。
4. **禁并发 mvn**；测试基线 450 只增不减。
5. **语义冻结（D4）**：`VerifyDegradeService.java`、`stuckSeconds`、`SCAN_LIMIT`、状态机、补偿与转人工逻辑零改动；禁 index hints；被测 SQL 沿 attempt-1 已记录文本，对照当前 `SportRecord` 实体列序复核后固定。
6. **数据库纪律（D6）**：scratch 库 `record_idx_scratch` + IT 自建库隔离；绝不触碰演示 `record_db` 与用户数据卷；不引入 Flyway / Testcontainers / 新 Maven 插件；既有迁移脚本零修改。
7. **措辞纪律**：不声称线上延迟或业务耗时改善（只登记访问路径与 EXPLAIN ANALYZE 实读行数）；不翻案 attempt-1 裁决原文；新增文档零禁词、零词面门正则字面量。
8. **token 与 PLAN**：受保护 29 项 token 只增不减；PLAN.md 纯追加（无删除历史行）。
9. **环境不可用** ⇒ 走 UNDETERMINED 支如实收口，禁伪造读数、禁重试刷绿。
10. **停止条件**：任何「顺手优化」（连接池/JVM/其他索引/第二扫描路径）⇒ 停手回报，登记为候选后续提案。

## 1. 唯一目标与任务背景

### 1.1 背景与史实链

- **提案史**：2026-09-25 attempt-1 实施后被否（verification.md 首行：①同一 SQL 的 EXPLAIN ANALYZE 未证明变快；②(status, created_at) 不能在 LIMIT 100 处停止），四文件未跟踪搁置至今。正文 attempt-1 证据（EXPLAIN 双计划、迁移幂等验证、IT 设计）原样保留为历史，attempt-2 段落由本轮追加。
- **台账史**：`idx_status_created` 曾于 TASK-103 被虚报为已改动（PLAN.md L670，`git log -S` 零代码落地）；L671 F18 将其列为「转立项建议」。本次为该索引首次真实落地尝试（第二轮）。
- **前提核验（指导侧 2026-10-07 亲验）**：`sql/02-record-db.sql` 与 `VerifyDegradeService.java` 自 2026-09-24 后零提交；sport_record 现仅 PRIMARY / uk_request_id / idx_user_time 三索引；扫描 SQL = `WHERE status=VERIFYING AND created_at<阈值 ORDER BY id ASC LIMIT 100`（`SCAN_LIMIT=100`，每 60s 轮询）；服务内第二条 MANUAL_REVIEW 扫描（L131-134）**不在本提案范围**。

### 1.2 目标

1. 双形态种子下 EXPLAIN + EXPLAIN ANALYZE 前后对比，按预注册三支判定收口；
2. 索引落地：DDL 一行 + 幂等迁移新文件 + IT 复活（2 例）；
3. 台账闭环：C-01 实施笔 + C-02 台账笔（§6 结构）。

## 2. 实施与测量设计（指导侧预注册裁决，只可细化不得漂移）

### 2.1 D2 双形态种子（每形态 16000 行；阈值字面量 T 运行时定值；种子 SQL 全文入 verification.md；每次捕获前 `ANALYZE TABLE sport_record`；EXPLAIN 与 EXPLAIN ANALYZE 双留档；前后两次之间仅差 `ALTER TABLE ... ADD INDEX idx_status_created (status, created_at)`，数据不变）

- **S-sparse（生产形态）**：500 新鲜 VERIFYING（created_at=T+30min）+ 3 滞留 VERIFYING（T-60min）+ 14537 PASSED + 640 REJECTED + 320 MANUAL_REVIEW（终态 created_at 散布 T-48h..T）；各状态交错注入，禁整段聚集。
- **S-dense（首轮对抗形态复刻）**：14560 PASSED / 640 REJECTED / 480 VERIFYING 全滞留 / 320 MANUAL_REVIEW。

### 2.2 D3 三支判定（预注册）

- **PASSED**：两形态计划均 `key=idx_status_created (type=range)`，且 S-sparse 前置 ANALYZE 实读行数≈全表级（>15000）而后置≈status=1 条目级（数百内），且 IT 2/2 绿。
- **FAILED**：S-sparse 优化器不选新索引或无数量级下降 ⇒ 如实登记不合入结论，提案去留交指导侧终裁。
- **UNDETERMINED**：环境不可用（Docker/MySQL 不可达）。
- **S-dense 豁免（预注册）**：后置劣于前置不构成否决——索引服务生产形态，dense 是事故场景且 attempt-1 已证优化器会选中该索引；数字如实登记并附 filesort 输入规模分析。

### 2.3 D4 语义冻结

`VerifyDegradeService.java` 及全部业务 Java 零改动；滞留阈值（120s）、扫描上限（100）、状态迁移、补偿与转人工语义零改动；禁 index hints；`(status, id)` 替代方案作为已考虑不采纳方案登记（created_at 不在索引内致常见路径逐条目回表），不实施。

### 2.4 D5 实施面

- `sql/02-record-db.sql`：sport_record 增一行 `KEY idx_status_created (status, created_at)`；
- 新文件 `sql/migrations/add-idx-record-status.sql`：幂等 information_schema 模式沿 `add-idx-record-seq.sql` 先例，仅 sport_record 单表，USE record_db；
- 新文件 `record-service/src/test/java/com/sportverify/record/db/SportRecordIndexMysqlIT.java`：沿 attempt-1 设计 2 例（①新库索引集恰为四个 + idx_status_created 列序断言；②查询语义不变种子：105 滞留 VERIFYING + 1 新鲜 + 1 滞留 MANUAL_REVIEW + 1 滞留 PASSED，恰按 id 升序返回前 100 条滞留 VERIFYING，扫描后行数与状态分布不变）；环境变量 `TASK177_IT_URL / TASK177_IT_USER / TASK177_IT_PASSWORD`（沿 TASK108_IT_* 惯例），缺变量 assume 跳过不视为通过。

### 2.5 D6 纪律

只登记访问路径变化与 ANALYZE 读数；scratch 隔离；既有迁移零修改；不引入新插件；IT 直跑留证例外见 §0.1。

## 3. 开工读数（时序差惯例沿 TASK-176 §3：任务书先于派发笔写就）

- 任务书落盘时点 HEAD = `f47e8bd7c03c63daea5b42a49cd754033459ce48`，派发笔入库后基线前移一位（`origin/main...main` 由 `0 0` 变 `0 1`）；执行侧开工核验：HEAD = 派发笔哈希（git log 考取）、领先计数 `0 1`、工作树**零残留**（原受保护脏项 `?? spec/changes/add-verify-degrade-status-index/` 已随派发笔转正——历史轮次的「受保护零触碰」约定就此反转，依据：提案推进转正）。
- 测试基线（offline，分模块）：36/41/33/127/144/59/10 = 450，Failures/Errors/Skipped 全 0。
- Checkstyle `--static=record-service` 基线：开工实测留证（新文件须零新增违规）。

## 4. 白名单（只改清单判据）

- **派发笔（已入库，执行侧零触碰）**：`spec/changes/add-verify-degrade-status-index/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md, verification.md}` + 本任务书。
- **C-01 实施笔**：`sql/02-record-db.sql`、`sql/migrations/add-idx-record-status.sql`（新）、`record-service/src/test/java/com/sportverify/record/db/SportRecordIndexMysqlIT.java`（新）、`spec/changes/add-verify-degrade-status-index/verification.md`（attempt-2 追加）。
- **C-02 台账笔**：`spec/changes/add-verify-degrade-status-index/tasks.json`（闭环勾选）、本任务书（收口记录纯追加）、`work/mailbox/tasks/TASK-177/handoff.md`（新）、`work/mailbox/PLAN.md`（纯追加：验收记录表 + token 追踪表第 29 行落表 37591580687）。
- **禁触**：`VerifyDegradeService.java` 及其余 src/、既有迁移脚本、`spec/changes/` 其他目录、`.codex/`、`.trae/`。

## 5. 受保护 tokens 基线（29 项，只增不减；2026-10-07 指导侧实测 `grep -cF work/mailbox/PLAN.md`）

- `13.4` = 17
- `18.0` = 19
- `73.93` = 18
- `68.8` = 18
- `6315` = 15
- `1.8612` = 14
- `3.3066` = 14
- `5.7056` = 14
- `9.408` = 14
- `36525962432` = 14
- `36586847965` = 13
- `36438897772` = 14
- `36399582548` = 13
- `36098038547` = 13
- `2806` = 20
- `598` = 13
- `36736221648` = 12
- `36808102571` = 7
- `36821040708` = 5
- `36845152965` = 4
- `36871294588` = 4
- `36880083885` = 5
- `36958994260` = 5
- `36976873215` = 5
- `36992632143` = 4
- `36995450125` = 2
- `37008317295` = 3
- `37021305016` = 4
- `37591580687` = 3

收口时 PLAN.md 所有 token 出现次数必须 ≥ 基线值。

## 6. 门禁与提交结构

1. **预提交门禁（C-01/C-02 每笔前全项亲跑并记录退出码）**：
   - `tasks.json` 语法验证（python json.load）rc=0；
   - 词面门四形态（default / `LC_ALL=C` / `LC_ALL=zh_CN.UTF-8` / `LC_ALL=C.UTF-8`，正则自 `.github/workflows/ci.yml` 提取，三 pathspec 排除同口径）：全 ZERO_HIT rc=1；正向探针（打 ci.yml 本体，去排除）rc=0；三态判定（rc 非 0/1 判工具错误）；
   - `git diff --check`（含 staged）rc=0；
   - 契约门在途：`bash scripts/verify/mailbox-contract.sh --open TASK-177 --baseline=<派发笔哈希>` rc=0；
   - token 29 项 `grep -cF` 只增不减；新增文件纯 LF、末尾换行完整。
2. **收口门禁（C-02 后全项亲跑留证入 handoff）**：
   - offline 七模块 test：450 恒等（分模块逐位），Failures/Errors/Skipped 全 0；
   - `--static=record-service` 违规数不增（新文件零违规）；
   - IT 直跑 2/2 rc=0（完整命令留证）；
   - 契约门无参 rc=0（TASK-177 足迹收口）；
   - 只改清单与 handoff 声明逐条全等（判据 B）；PLAN.md 自派发笔起纯追加。
3. **提交结构**：
   - C-01 实施笔：D5 三文件 + verification.md attempt-2 追加。subject 类型沿仓库代码笔先例（开工 `git log` 考据 sql/ 相关提交措辞），禁性能改善措辞；
   - C-02 台账笔：`docs(mailbox): 登记 TASK-177 验收记录与提案闭环（TASK-177）`。
4. **handoff.md**（沿 TASK-176 同构 §1-§10）：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 测量证据（四组合关键读数 + 三支归属）/ 逐门实测表 / 受保护 token 前后读数 / 未覆盖项与不得推出的结论 / 提交表。
