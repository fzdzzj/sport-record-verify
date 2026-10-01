# TASK-166 spec：补跑真库 IT 把 TASK-165 的授权上界抬到实测 ＋ 三项订正（F1/F2/F3）

## 0. 硬约束与红线（继承 TASK-165 §0，逐字适用）
1. 本任务书是唯一权威。开工先逐位核对 §2；**任一不符 ⇒ 停手回报原文，不要自行解释、不要自行订正**。
2. 命令行**全程无中文**（含 grep/sed/awk 模式）；中文先写 `.trae/tmp/` 下 UTF-8 无 BOM 文件再用；提交信息 `git commit -F <file>`（主题行＋空行＋正文要点）。
3. bash 一律写成 `.sh` 文件再用 `D:\git\Git\bin\bash.exe <路径>` 执行；禁 `bash -lc` 内联；需抓非零 rc 的段落不要放 `set -e` 下。
4. Maven 唯一入口 `bash scripts/verify/mvn-verify.sh`；禁裸 mvn、禁 `MAVEN_OPTS`；**唯一例外**是 §3 第 4 步的临时 `.mvn/maven.config` 通道（TASK-156 先例），必须在任何 `git add`/`commit` 之前删除并留跑前跑后快照。
5. 不 push、不建 PR、不 `git stash`、不 `git add -A`/`add .`（逐路径 add）。既有脏项 `spec/changes/add-verify-degrade-status-index/` 与容器 `task131-scratch-mysql` **零触碰**。
6. **本轮仍零生产行为变化**：不改 `application.yml`、不改两个新键默认值、不跑任何负载、不起四服务、不合并任何 delta、不改主规格一字、不改既有三个 Mapper 方法与 SQL。生产代码只允许 §4 的 F2（日志字段名）与 F3（javadoc 文字）两处。
7. 不翻案、不改写任何已入库结论与数字（TASK-143~165，特别是 TASK-153 的 NO-GO 与 TASK-164 的未定支）。
8. 任何门槛未跑一律写「未覆盖」；**skip ≠ pass**；不得用 mock 结果冒充真库。

## 1. 本轮唯一目标（三件，缺一即未收口）
- **A 补跑真库 IT**：`VerifyEventOutboxBatchMarkMapperMysqlIT` 的 6 个用例必须在真实 MySQL 上实跑并全绿，日志原文含 `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。Docker/MySQL 起不来 ⇒ **停手回报原文并记未覆盖**，不得降级断言。
- **B 三项订正**：F1 规格补录第 5 项被授权变化、F2 日志字段名如实化、F3 `processRow` javadoc 如实化 ＋ 欠账登记（详见 §4）。
- **C 量化登记**：把 IT 实测到的边界写进 handoff 与 PLAN：① 崩溃/未标记场景下轮 `selectPendingBatch` 重投行数**实测上界**（对照 chunk-size=25 与 batch-size=100）；② 同 chunk 内 `sent_at` 是否同值（实测时间戳原文）；③ 同一批 id 二次调用 `markSentBatch` 的 affected **实测=0**（条件幂等）；④ 已 SENT 行与耗尽行不被重选（实测）。**这是把 TASK-165 规格授权里的四个上界从设计意图抬到实测证据的唯一机会，必须逐项给出原文。**

## 2. 起点与开工读数（指导侧 2026-10-01 亲跑值）
- `HEAD = 30c5ab5b93bdf0ce7e7c652b56cfd6bea83a20c3`；`origin/main = b85098ae0eaa71ec7740b70c19b9a74b2c759352`；`git rev-list --left-right --count origin/main...main` = **`0	4`**（若指导侧已推送则为 `0 0`，两种都接受，照实记录是哪一个）。CI 最近 run `36736221648` conclusion=success。
- 工作树脏项**仅** `?? spec/changes/add-verify-degrade-status-index/`（零触碰）＋本任务目录。
- `--mode=offline` → rc=**0**，七模块 **36/41/33/103/137/59/10**，Skipped 全 0；其中 `VerifyOutboxRelayBatchMarkTest` **10** 绿、`VerifyOutboxRelayBatchMarkConfigTest` **4** 绿、三个保护件 **19/10/1** 绿。
- `--mode=offline --static=verify-service` → rc=**1**，`You have 867 Checkstyle violations`（门槛：收口 ≤**867**、改动文件零新违规）。
- 行数锚点：`VerifyOutboxRelay.java` = **783** 行（F3 目标在 **L315** 的 javadoc「单行处理体：串行与并发共用的唯一实现（语义只有一份，不可能漂移）」；F2 目标在 **L563** 的 `log.info("outbox 事件分块批量标记成功：chunkSize={}, affected={}"`）；`VerifyEventOutboxMapper.java` = **61** 行；`VerifyOutboxRelayBatchMarkTest.java` = 391 行；`VerifyOutboxRelayBatchMarkConfigTest.java` = 115 行；`VerifyEventOutboxBatchMarkMapperMysqlIT.java` = **257** 行（schema 常量 `task165_batch_mark_scratch`，环境变量 `TASK165_IT_URL/USER/PASSWORD`，内含防误指断言）。
- `application.yml` = **196** 行、`^verify:`=1、`^spring:`=1、`relay-batch-mark` 命中 **0**、`relay-interval-ms: 500` 在 L123。**本轮零改动。**
- 主规格 `spec/specs/sport-record-verify/spec.md` = **2806** 行 / **121** 个 `### Requirement:`（零触碰）；`work/mailbox/PLAN.md` = **1309** 行、CR=**0**；`spec/changes`：在途 **24** / archive **43**。
- 真实 DDL：`sql/03-verify-db.sql`（**67** 行，`CREATE TABLE IF NOT EXISTS verify_event_outbox` 在 **L39**）。
- 词面门：正则现场提取 len=**26**、pipes=7（8 分支）；含 `--untracked` 的四形态全 **ZERO_HIT rc=1** ＋ 正向对照 rc=**0**（探针植非忽略路径、用完删除并 `git status --porcelain` 逐字还原）。入库文件不得内嵌该正则字面量。
- 契约门：本任务书落盘后**无参 rc=1 属预期**，`--open TASK-166 --baseline=30c5ab5b93bdf0ce7e7c652b56cfd6bea83a20c3` 应 rc=**0**；收口后**无参必须 rc=0**。
- 环境：磁盘 D 盘 Free = **194480783360** 字节（≈181.1 GiB，门槛 ≥100 GB）；sports 的 java 进程 **0**；**Docker daemon 当前是关的**（`docker ps -a` 报 `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`）⇒ §3 第 1 步必须先启动。
- 受保护数字 token（**行数命中法**，指导侧实测 base=HEAD `30c5ab5`）：`13.4`=11、`18.0`=13、`73.93`=12、`68.8`=8、`6315`=9、`1.8612`=8、`3.3066`=8、`5.7056`=8、`9.408`=8、`36525962432`=8、`36586847965`=7、`36438897772`=8、`36399582548`=7、`36098038547`=7、`2806`=13、`598`=7、`36736221648`=5 ⇒ 收口**不得减少**；若另用出现次数法，须自测 base 与收口两次并同样不得减少。

## 3. IT 实跑规程（逐步照做，任一步不可用即停手回报原文）
1. 启动 Docker Desktop（`Start-Process`，`-WindowStyle Hidden`），轮询 `docker info` 至 rc=0（上限 5 分钟），存证。
2. `docker start sport-verify-mysql`；轮询至 healthy；确认宿主端口 **3307**；`docker ps -a` 开工存证。**不得启动/停止其它容器，不得碰 `task131-scratch-mysql`、不得碰 `sport-verify-postgis`。**
3. 建独立 scratch schema `task165_batch_mark_scratch`，用 `sql/03-verify-db.sql` 中 `verify_event_outbox` 的**原文 DDL** 建表（不得改 DDL 文字）。**严禁触碰演示库 `verify_db`**：跑前跑后各留一次 `SHOW DATABASES`、`verify_db.verify_event_outbox` 行数与最大 id 快照，证明未变。
4. 临时调用通道：仓库根建 `.mvn/maven.config` 两行 `-Dtest=VerifyEventOutboxBatchMarkMapperMysqlIT` 与 `-DfailIfNoSpecifiedTests=false`；跑前跑后 `git status --porcelain .mvn` 快照存证；**任何 `git add`/`commit` 之前删除该文件**。
5. 环境变量：`TASK165_IT_URL=jdbc:mysql://127.0.0.1:3307/task165_batch_mark_scratch?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true`、`TASK165_IT_USER=root`、`TASK165_IT_PASSWORD=root`（URL 必须含 schema 名，IT 内有断言）。
6. 跑 `bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service`，要求原文 `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` 且 rc=0；完整日志留档 `.trae/tmp/` 或 `docs/perf/data/raw/task166-*`（ignored）。
7. 删 `.mvn/maven.config`，复跑**全量** `--mode=offline`，要求 rc=0 且七模块回到 **36/41/33/103/137/59/10**（证明通道未污染基线）。
8. 若 IT 因**自身缺陷**在真库上失败 ⇒ **停手回报原文与失败堆栈**，不得自行修改 IT 断言或生产代码来凑绿；修复建议写进 handoff 交指导侧裁定。

## 4. 三项订正（F1/F2/F3，逐字要求）
- **F1 规格补录（纯追加，不改既有文字）**：在 `spec/changes/add-verify-outbox-relay-batch-mark/specs/sport-record-verify/spec-delta.md` 的「四项语义变化」之后追加**第 5 项被授权的变化**：开启态下**成功行不再有逐行 INFO 日志**（原 `outbox 事件投递成功：id=…, eventId=…, tag=…` 由每 chunk 一条汇总日志取代；失败行的逐行 WARN 日志**保持不变**），并登记两点影响：① 运维无法再按 `eventId` 从日志定位单条投递时刻；② TASK-163/164 的 M1 空档机制门依赖逐行日志时间戳，**在开启态不可用**，后续任何判别必须改用诊断口径的锁内吞吐。同时在 `proposal.md` 的 Impact 节追加同一条（纯追加）。
- **F2 日志字段名如实化**：`VerifyOutboxRelay.java` **L563** 的成功日志把 `chunkSize={}` 改为如实字段名（例如 `rows={}, affected={}`，实参＝该次 flush 的实际行数与受影响行数）；不得改日志级别、不得改调用次数、不得改其它字段。
- **F3 javadoc 如实化 ＋ 欠账登记**：**L315** 的「单行处理体：串行与并发共用的唯一实现（语义只有一份，不可能漂移）」在新增 `sendAndCollect` 后已**不成立**，必须订正为如实描述（关闭态走 `processRow`；开启分块标记态走 `sendAndCollect`；两者的发送/耗尽/`incrRetry` 语义必须同步维护，存在漂移风险），并在 PLAN 追加节登记欠账「后续把两份单行语义统一为一个 `sendRow`」。**本轮不得做该统一重构**（超范围）。

## 5. 门槛 G0–G10（全部报实测退出码与关键读数原文）
G0 起点核对（§2 全 SHA 逐位）；G1 开工读数逐项一致（不符即停手）；G2 三个保护件（`VerifyOutboxRelayTest` 19 / `VerifyOutboxRelayConcurrencyTest` 10 / `VerifyEventOutboxMapperSqlContractTest` 1）**numstat 为空**且全绿；G3 `git diff --exit-code <base>..HEAD -- verify-service/src/main/resources/application.yml` rc=**0** 且该文件不含两个新键；**G4 IT 实跑 `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`、rc=0（本轮最强判据）**；G5 全量 offline rc=0 且七模块 **36/41/33/103/137/59/10**；G6 静态门 rc=1 且 ≤**867**、改动文件零新违规；G7 词面门四形态 ZERO_HIT ＋ 对照 rc=0 ＋ 状态还原；G8 `git diff --check` rc=0 且每笔 `git show --check` rc=0；G9 契约（在途 `--open` rc=0；**收口无参 rc=0**）；G10 只改清单逐路径一致（§6）＋ 17 个受保护 token 不减。

## 6. 只改清单（超出即红）
1 `spec/changes/add-verify-outbox-relay-batch-mark/specs/sport-record-verify/spec-delta.md`（纯追加）；2 `spec/changes/add-verify-outbox-relay-batch-mark/proposal.md`（纯追加）；3 `spec/changes/add-verify-outbox-relay-batch-mark/tasks.json`（据实勾选，可把 IT 实跑步改 `completed=true` 并追加证据行）；4 `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java`（**仅** F2 与 F3 两处）；5 `work/mailbox/PLAN.md`（纯追加）；6 新 `work/mailbox/tasks/TASK-166/handoff.md`；7 `work/mailbox/tasks/TASK-166/spec.md`（本任务书原样入库，执行侧一字不改）。**不得改**：`VerifyEventOutboxMapper.java`、任何测试文件（含 IT）、`application.yml`、主规格、pom、scripts、`docs/perf/` 既有报告与 JSON。提交分批（订正 / 三件套追加 / 台账），逐路径 add。

## 7. 收尾硬条（缺一条即未收口）
`.mvn/maven.config` 删净（`git status --porcelain .mvn` 为空）→ sports 的 java 进程=0 → 复跑全量 `--mode=offline`（rc=0、137）→ 复跑静态门（≤867）→ 词面门四形态＋对照 → **无参 `mailbox-contract.sh` rc=0** → `git diff --check` rc=0 ＋ 每笔 `git show --check` rc=0 → `docker ps -a` 收口存证（证明未碰 `task131-scratch-mysql`）→ `verify_db` 跑前跑后快照一致 → scratch schema 保留并登记行数（不清库、不删表）→ PLAN 纯追加验收记录（含 §1-C 四项实测原文、F1/F2/F3 订正说明、17 token 计数、外部门槛栏「未达外部门槛（本次不 push，待下次授权由 CI 复验）」）。

## 8. 未覆盖与不得推出（照抄进 handoff）
spotbugs/pmd 未覆盖（被 checkstyle 阻断）；`--mode=online` 与 CI 未跑 ⇒ 未达外部门槛；IT 只在**单实例、无并发消费者、无真实 broker**的 scratch 库上验证 Mapper 语义 ⇒ 不含多实例竞争、不含 RocketMQ 重投、不含端到端；**不得**据 IT 通过就开启 `relay-batch-mark-enabled=true`（须另立判别轮，且必须用诊断口径的锁内吞吐，禁用 ~2s 排空采样器——其跨会话方差约 2×）；**不得**把 IT 结果或 markSent 占 72~77% 写成吞吐/延迟收益；**不得**与 `relay-send-concurrency>1` 组合开启；不翻案 TASK-153/154，不改写 TASK-152/156/161/162/163/164/165 任何数字。

## 9. handoff.md 六项交回物
① 一句话结论（IT 是否真跑全绿、四项上界实测值、三项订正是否落地）；② 起点全 SHA、每笔提交 SHA 与逐路径清单、shortstat；③ 只改清单逐项对齐与零修改声明（含三个保护件与 `application.yml` numstat 为空的原文）；④ G0–G10 逐门实测退出码与关键读数原文（含 IT 的 `Tests run` 原文与 `.mvn` 通道跑前跑后快照）；⑤ §1-C 四项实测证据逐条原文 ＋ F1/F2/F3 订正前后对照；⑥ 未覆盖项与不得推出的结论。

## 10. 工具与陷阱
`git grep --untracked` 必须置于 pattern 之前且不搜 ignored 文件；从 mvn/Java 日志提数字先 `tr -d '\r'`（CRLF 让 grep 3.1 行尾锚点失配）；行数用 bash `wc -l`，**不要**用 PowerShell `Measure-Object -Line`（不数空行）；`git show <sha>:<path>` 取到 LF blob、工作树是 CRLF，报数写明测的是哪一面；`git ls-tree -d` 只列含 tracked 文件的目录；脚本写文件时 `String.replace` 的替换串里 `` $` `` 是反向引用 ⇒ 含 `$` 的内容用 `split().join()`；`docker exec` 传 SQL 时中文一律先进文件。