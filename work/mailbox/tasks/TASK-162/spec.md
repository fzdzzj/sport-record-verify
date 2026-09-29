# TASK-162 任务书：复测 relay 调度间隔（可重复性口径）并条件式落地默认值

> 执行侧唯一权威任务书。指导侧已亲自复核并接受 TASK-161 的第三支裁决（证据不足），本任务与 TASK-161 无依赖关系。
> 本任务书本身也要被实跑验证：所有「开工读数」都是指导侧 2026-09-29 本会话亲跑的一手值（Level A），你若跑出不同数字，**停手回报**，不要自行解释。

## 0. 角色、铁律与工具口径

1. 你是执行侧，做有界机械活并按本任务书取证；判断密集的事（门槛设计、口径订正、裁决归属）由指导侧做。**报告＝待验证假设**，指导侧会重跑你跑过的每一道门。
2. **不 push、不建 PR、不 `git stash`、不 `git add -A`/`git add .`**（逐路径 add）、不改任何 git 配置、不动既有脏项。
3. **命令行全程 ASCII**：中文内容（提交信息、报告、grep 过滤词）一律先写进 `.trae/tmp/` 下的 UTF-8 无 BOM 文件再用（提交走 `git commit -F`）。PowerShell 命令行内联中文会被剥离或直接 exit 127——本会话执行侧已自踩两次，不要第三次。
4. **bash 一律写成 `.sh` 文件**（放 `.trae/tmp/`）再 `& 'D:\git\Git\bin\bash.exe' <路径>` 调用；**禁 `bash -lc` 内联**。需要抓非零退出码的段落**不要**放在 `set -e` 下。
5. **权威解释器**：`D:\git\Git\bin\bash.exe`（git 2.20.1）。词面门必须用它跑——TASK-161 已实测另一套自带 git 2.52 在 `C.UTF-8` 下会把字节 0x8E/0x9E 折成 CP1252 大小写对，在既有 `api/.../MapMatchResultDTO.java` 产生 2 处**伪命中**。
6. **Maven 唯一入口**：`& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh ...`。禁裸 mvn、禁 `MAVEN_OPTS`、禁建 `.mvn/maven.config`（本任务不跑 IT，**不需要** TASK-156/161 的带外通道；若你发现需要，停手回报）。
7. 行尾计数只用 `tr -cd '\r' | wc -c` / `tr -cd '\n' | wc -c`；定位单行 CR 只用字节扫描（`od -An -tx1 -v`）。 `grep -c $'\r$'` 假阳性、`awk '$0~/\r/'` 假阴性（GNU Awk 4.2.1 剥记录尾 CR），都不要用。**新增（TASK-162 执行侧开工实测，指导侧已采纳）**：权威 bash 自带的 grep 3.1 在 CRLF 行尾（0d 0a）上做行尾锚点匹配会**失配**——本机 mvn/Java 日志正是 CRLF。故凡按行尾锚点从日志提取数字（例如 Tests run 汇总行），必须先做回车归一化再 grep；否则会拿到空输出并误读成「门没过」。留档：执行侧 t162-grepvar.sh 与 .crlfnorm.txt。
8. `git grep --untracked` 时 `--untracked` 必须**在 pattern 之前**（否则 fatal rc=128）；`git grep` 默认**不搜未跟踪文件**。

## 1. 唯一问题（单因素）

指导侧本会话亲读生产代码得到的一手事实（Level A）：

- `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java` 第 126–127 行：
  `@Scheduled(fixedDelayString = "${verify.outbox.relay-interval-ms:5000}", initialDelayString = "${verify.outbox.relay-initial-delay-ms:10000}")`；
  第 157 行每个 tick **只调用一次** `outboxMapper.selectPendingBatch(batchSize, maxRetry)`，其后是**一趟** for 循环，**没有排空循环**；
  第 68 行 `batch-size:100`、第 72 行 `max-retry:16`、第 96 行 `relay-send-concurrency:1`（TASK-160 落地、默认关闭）。
- `verify-service/src/main/resources/application.yml` 里 **没有任何 `verify.outbox` 键**（命中 0）⇒ 生效值就是上面的 `@Value` 默认。hikari 段只设了 `initialization-fail-timeout: -1`（⇒ 池生效上限 Hikari 默认 10）。
- 因此稳态投递上限 = 100 行 / 5 s = **20 行/s**。

已入库的实测（Level B，`docs/perf/data/exp-outbox-relay-interval.json`，TASK-144）：负载期 outbox 创建速率 87.39~91.36 行/s；A 档（5000ms）净投递 15.20/14.73 行/s、`callback→SENT` P50 = 56769/59317 ms、2010 行排空需 132.2/136.5 s；B 档（500ms）净投递 35.59/37.25 行/s、P50 = 19782/18306 ms、排空 56.5/54.0 s。改善门当时**已满足**（B 两轮较 A 较好者 −65.15%/−67.75%、P95 不劣），但**预注册可比性门失败**：A1 提交 QPS 130.91 相对四轮中位 155.205 偏 **−15.65%**（限 ±15%），且 A1 混入 2 次消费失败 + 2 次重投 ⇒ 裁决 **UNDETERMINED**、未改默认值、`update-verify-outbox-relay-delay` 的 tasks.json 第 3 项至今 `completed=false / passes=false`。

**唯一问题**：把「首轮冷启动」这个可比性缺陷修掉后重跑单因素 A-B-B-A，`verify.outbox.relay-interval-ms` 由 5000 改 500 的 `callback→SENT` P50 收益**是否可重复**？若可重复且全部门槛通过，则把**这一项**默认值落地到 verify-service 的 classpath YAML 并加绑定测试。

本任务**不**回答：并发（`relay-send-concurrency` 全程必须保持 1）、批次大小、池容量、SQL/索引、消费者线程、MQ 参数。TASK-144 提案的停止条件明写「任何结果均不叠加调批次、并发发送、连接池、JVM、SQL 或索引作为补救」，本任务继承该约束。

## 2. 起点冻结（G0）

- 开工基线 SHA = `121273d6cf46fd1e8968d4817f8134d71edffda0`（`git rev-parse HEAD` 必须逐位一致）。
- `git rev-list --left-right --count origin/main...main` 原样记录：`0	5`（指导侧尚未推送本批）或 `0	0`（指导侧已推送，此时 `origin/main` = 121273d）**都合法**；其余取值停手回报。
- 工作树只允许两类未跟踪项：既有脏项 `?? spec/changes/add-verify-degrade-status-index/`（**零触碰**）与本任务书目录 `?? work/mailbox/tasks/TASK-162/`（收口时随台账入库）。
- 全程不改 `spec/specs/sport-record-verify/spec.md`（本任务**不合并任何 delta**，只新增在途三件套）。

## 3. 只改清单（超出即失败）

1. `docs/perf/复测-outbox-relay-调度间隔-可重复性.md`（新报告）
2. `docs/perf/data/exp-outbox-relay-interval-repeatable.json`（机器摘要）
3. **仅当落地支成立**：`verify-service/src/main/resources/application.yml`（纯新增行，numstat 必须 `N/0`）
4. **仅当落地支成立**：`verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayIntervalDefaultTest.java`（新增测试类）
5. `spec/changes/prove-verify-outbox-relay-interval-repeatable/`：`proposal.md` + `specs/sport-record-verify/spec-delta.md` + `tasks.json`（**纯 ADDED**）
6. `work/mailbox/PLAN.md`（**纯追加**验收记录节，既有行含 L4 零改动）+ `work/mailbox/tasks/TASK-162/{spec.md, handoff.md}`
7. `docs/perf/data/raw/task162-*` 与 `.trae/tmp/*`（`.gitignore` 第 46/64 行已忽略，**不入库**）

**禁改**：所有生产 Java（`VerifyOutboxRelay`/`RelayDiagnostics`/`VerifyEventOutboxMapper`/`VerifyEventProducer`/消费者）、任何 SQL/索引/schema/迁移、任何 pom、`scripts/**`、`.github/**`、`docker-compose*.yml`、主规格、`spec/changes/archive/**`、其余 20 个在途 `spec/changes/*` 目录、其他任务的 `work/mailbox/tasks/*`、既有 `docs/perf/**` 全部文件（TASK-143/144/145/152/156/161 的报告与 JSON：**数字与文字都不得改写**）、既有测试类（含 `VerifyOutboxRelayTest` 的 19 个用例与 TASK-160 新增的 `VerifyOutboxRelayConcurrencyTest`）、既有 10 个 `*IT`（verify-service 7 + leaderboard-service 3）。`relay-send-concurrency` 保持 1、`batch-size`/`max-retry`/消费线程/池/JVM/MQ/Nacos 全部不动。MySQL 容器**不得** recreate / 改配置 / 删卷；`task131-scratch-mysql` 与 `task161_pool_scratch` **不得触碰**。演示库**不得** TRUNCATE/DELETE/清库。

## 4. 起栈 runbook（照既有工具，不得另造）

1. 容器（当前实测全部 Exited，只 `docker start`，**禁 `docker compose up`**，因为那会 recreate）：
   `docker start sport-verify-mysql sport-verify-rocketmq-namesrv sport-verify-rocketmq-broker sport-verify-redis sport-verify-nacos`，随后 `docker ps` 五个 Up 并原文留档。
   `sport-verify-postgis` **不起**（leaderboard/mapmatch 本轮不覆盖，与 TASK-144 同口径）。
2. 建 jar：`& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh --mode=offline package`（rc=0；同时充当一次 offline 全量证据）。四个 jar 用 `Get-FileHash` 记 sha256；**A/B 计数轮之间不得重建任何 jar**。
3. 起服务：`bash scripts/perf/run-perf.sh start-services`，再 `bash scripts/perf/healthcheck.sh`（8080/8081/8082/8083 四个健康端点原文留档）。
4. B 档注入（唯一变量）：先按 `run-perf.sh` 第 109 行的 kill 模式停 verify-service（按 jar 名匹配，勿按端口杀），再起：
   `nohup "$JAVA_BIN" -jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar --verify.outbox.relay-interval-ms=500 > logs/verify.log 2>&1 &`
   A 档 = 不带该参数重启（回到默认）。**每次重启后等健康 + ≥15 s**（越过 `relay-initial-delay-ms=10000`）再跑任何轮次。
5. 负载：`bash scripts/perf/run-perf.sh load 100 2000 task162-<label>`（内部 `--warmup 10 --timeout 60 --user-total 16`，产物落 `docs/perf/data/raw/task162-<label>-c100-*`）。
6. 资源采样：每轮 before/after 各一次 `bash scripts/perf/relay-round-sampler.sh task162-<label> before|after`（含 `hikaricp_connections_pending/active/idle/max/timeout_total`、MySQL 全局状态、JVM/GC；取不到时脚本自己标 FAILED/UNAVAILABLE，**不得**写成「无异常」）。
7. 库侧读数一律走 `docker exec sport-verify-mysql mysql -uroot -proot -N -e "<SQL>"`（照 `run-perf.sh` 第 76–84 行既有形态）。

## 5. 轮次协议（预热全部丢弃；计数轮 A-B-B-A）

**预热轮（丢弃，不进任何统计，但必须逐轮在 handoff 列出 label/QPS/排空耗时）**

- W0（全局，起栈后首轮之前）：`load 100 2000 task162-w0` → 等排空。目的：吸收 record/gateway/verify 四服务冷启动——TASK-144 的 A1 正是起栈后首轮，QPS 偏 −15.65% 并混入 2 次消费失败。
- W_cell（每次 verify-service 重启后、该 cell 首个计数轮之前）：`load 100 200 task162-w<cell>` → 等排空。目的：吸收该 cell 的 JIT/池冷启动，且不给统计引入负载形状差异。
- 预热轮**不得**用于配对、**不得**计入 QPS 中位数、**不得**写进报告的 A/B 表。

**计数轮（严格顺序 A1 → B1 → B2 → A2；替换轮追加在尾部，最多 2 个）**

每轮固定 6 步，缺一步该轮无效：

1. 前置：`healthcheck.sh` 四端口 200；可投递 PENDING 与耗尽 PENDING 均 = 0
   （`SELECT COUNT(*) FROM verify_db.verify_event_outbox WHERE status='PENDING' AND retry_count < 16` / `... AND retry_count >= 16`）；
   记 id 下界（`SELECT COALESCE(MAX(id),0) FROM record_db.sport_record` 与 `SELECT COALESCE(MAX(id),0) FROM verify_db.verify_event_outbox`，各 +1 作为 RecMin/ObMin）；记磁盘可用；`relay-round-sampler.sh ... before`。
2. 负载：`run-perf.sh load 100 2000 task162-<label>`（该 cell 的注入状态必须已在前一步确认）。
3. 排空等待：轮询可投递 PENDING 到 0（A 档硬超时 400 s、B 档 200 s）；超时 ⇒ 本轮**无效**并记原因。
4. 后置：记 id 上界（RecMax/ObMax）；`relay-round-sampler.sh ... after`；**立即**把 `logs/verify.log` 与 `logs/record.log` 复制成 `docs/perf/data/raw/task162-<label>-verify.log` / `-record.log`——`logs/verify.log` 会在下次 verify-service 重启时被截断（`>` 重定向），晚复制就丢证据；再统计 relay 失败行/耗尽行/`RECONSUME_LATER`/`Fallback`/重复 `event_id` 报错的行数。
5. 配对：复用 `docs/perf/data/raw/task144-parse.ps1`（在忽略目录、未 tracked）：
   `powershell -NoProfile -File docs/perf/data/raw/task144-parse.ps1 -Label task162-<label> -VerifyLog <复制后的 verify.log> -RecordLog <复制后的 record.log> -RecMin <> -RecMax <> -ObMin <> -ObMax <> -Out docs/perf/data/raw/task162-<label>-stats.txt`
   **不得修改该脚本**；若确实必须改，复制成同目录 `task162-parse.ps1` 再改，并在 handoff 逐行说明差异（原文件不动、两者都不入库）。
6. 记账：本轮 `outbox` 行数、markSent 计数、`retry_count > 0` 行数（必须 0）、耗尽行增量（必须 0）、10 s 桶创建形态三桶数字。

**有效性判据 V1–V7（预注册；任一不满足 ⇒ 该轮无效、不进统计）**

- V1 负载 summary：`ok=2000`、`errors=0`、`limited429=0`
- V2 `consumeFailures=0` 且 `redeliveries=0`（verify.log 零 `RECONSUME_LATER`、零 `RecordApiFallback`）
- V3 relay 失败行 = 0 且 relay 耗尽行 = 0
- V4 轮前可投递/耗尽 PENDING 均 = 0，轮后排空到 0（超时即无效）
- V5 配对完整：`cohort.records == cohort.outboxLinked == callbackToSent.n`（缺样本即无效）
- V6 提交 QPS 相对**全部有效计数轮（A+B 合并）中位数**偏差在 **±15%** 内
- V7 创建形态可比：outbox `created_at` 的 10 s 桶序列同 cell 内不出现「第三桶显著抬高」型差异（照 TASK-144 `comparability.creationShape` 口径，逐轮记录三桶原始数字交指导侧复核）

**预算（硬上限，防钓鱼）**：最多 **6 个计数轮**（基础 4 + 替换 2）、最多 **4 个丢弃预热轮**。失败轮**照占预算**（TASK-144 口径）。任一 cell 在预算内拿不到 **2 个有效轮** ⇒ 直接落「未定」支，**不加跑、不落地**。

## 6. 预注册裁决门（三支，不得事后放宽）

**改善门（与 TASK-144 第 3 步同口径，一字未放宽）**：两轮有效 B 的 `callback→SENT` **P50 均**较「两轮有效 A 中较好者（P50 较小者）」下降 **≥20%**，且两轮有效 B 的 **P95 均不劣于**该 A 较好者。

**资源门**：① 所有计数轮 `hikaricp_connections_timeout_total` 增量 = **0**；② verify.log 零 `锁释放异常`、零 `锁获取被中断`；③ 每轮 `hikaricp_connections_pending/active` 轮内最大值如实记录（不设上限，但必须与净投递速率**并列**披露）；④ B 档每轮 MySQL `Com_select` 增量相对 A 档上升是**预期**（tick 频率约 10×），必须如实记录倍数并与收益并列披露，**不得只报其一**；⑤ 磁盘：开工与收口各记一次可用空间。

**语义门**：`retry_count > 0` 的行数全程 = 0；耗尽行增量 = 0；`uk_event_id` 零重复报错；每轮 markSent 计数 = 该轮 cohort 行数；零 `RECONSUME_LATER`。

**三支**：

- **落地支**：A cell ≥2 有效轮 **且** B cell ≥2 有效轮 **且** 改善门成立 **且** 资源门与语义门全过 ⇒ 按第 7 节落地 `relay-interval-ms: 500` 并跑 C 确认轮。
- **未定支**：任一 cell 有效轮 <2、或 V6/V7 可比性不成立、或改善门未达、或资源/语义门有红 ⇒ **不改任何默认值**、不动 `application.yml`、不加测试类；报告记「未定/不推荐」并逐条列出触发的门与实测读数。
- **反证支**：两轮有效 B 的 P50 劣于 A 较好者，或两轮 B 之间方向矛盾 ⇒ 记「不推荐」，明写「与 TASK-144 方向相反」，**不得挑选有利的一轮**，不改默认值。

无论落哪一支：**不得放宽门槛、不得换 B 档取值再试（不试 200/1000/2000ms）、不得叠加 batch/并发/池/JVM/SQL/索引/MQ 作为补救**（继承 TASK-144 停止条件）。

## 7. 落地实现细则（仅落地支；含 C 确认轮与回滚）

**7.1 `application.yml`（纯新增，numstat 必须 `N/0`）**

- 开工实测：该文件 190 行、`^verify:` 根键**恰好 1 个**、`verify.outbox` 与 `relay-interval-ms` 命中均 **0**（`git grep` rc=1）。
- 编辑后必须仍然**只有 1 个** `^verify:` 根键（重复根键会导致静默覆盖，是本轮最大的落地陷阱）。在既有 `verify:` 映射内、与 `rules:` 同级（2 空格缩进）新增 `outbox:` → `relay-interval-ms: 500`，并配 3~5 行注释写明：判别来源（TASK-162 报告路径）、与 TASK-144 UNDETERMINED 的关系（新实验、新预注册，不翻案）、**只改这一项**、`relay-send-concurrency` 仍为 1。
- 保持该文件既有行尾风格（逐字不动既有行 ⇒ `git diff --numstat` 为 `N/0`，0 删除）。

**7.2 新增测试类 `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayIntervalDefaultTest.java`**

- **纯 JUnit 5**：**不得** `@SpringBootTest`、不得连 Nacos/MySQL/Redis/RocketMQ、不得有任何网络或中间件依赖（offline 全量必须在中间件全停时也能跑过；本仓 offline 套件是 hermetic 的，不要破坏这一点）。
- 至少 3 个 `@Test`（记为 k，落地后 offline 的 verify-service 计数必须 = **120 + k**）：
  1. 用 `YamlPropertySourceLoader` 加载 classpath 的 `application.yml`，断言 `verify.outbox.relay-interval-ms` = **500**；
  2. 反射断言 `VerifyOutboxRelay#relay` 的 `@Scheduled.fixedDelayString` **仍逐字**为 `${verify.outbox.relay-interval-ms:5000}`、`initialDelayString` 仍为 `${verify.outbox.relay-initial-delay-ms:10000}`（证明生效值来自 YAML 覆盖、**代码默认值未被改**）；
  3. 断言 classpath YAML 里 `^verify:` 根键只出现一次（防重复根键静默覆盖）。
- checkstyle 只扫 `src/main/java`（指导侧本会话实测：867 条违规全部来自主源），新测试类不会新增违规；静态门仍要求 **≤867**。
- 既有测试类**一个字节都不改**（`VerifyOutboxRelayTest`/`VerifyOutboxRelayConcurrencyTest`/`RelayDiagnosticsTest`/`VerifyEventProducerTest` 的 numstat 必须为空）。

**7.3 C 确认轮（落地后的功能确认，不进 A/B 统计）**

1. `& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh --mode=offline --pl verify-service package`（rc=0）。用 `Get-FileHash` 证明**只有** verify-service 的 jar 变了，record/user/gateway 三个 jar sha256 与 A/B 轮一致。
2. 停 verify-service（按 jar 名匹配），以**无任何注入**启动新 jar；健康检查 + ≥15 s；W_C 预热（`load 100 200 task162-wC`，丢弃）→ 排空。
3. C 轮：`load 100 2000 task162-C` → 排空 → 复制日志 → `task144-parse.ps1` 配对 → 统计 >1 s 空档数与空档中位。
4. **判据**（A 档实测 20~21 个 >1s 空档、中位 5022~5023 ms；B 档 1~2 个、中位 1053~1570 ms）：
   `>1s 空档数 ≤ 5` **且** `longGapMedianMs ≤ 2000` **且** C 轮 P50 落在两轮有效 B 的 P50 中位 **±25%** 内。
5. **C 判据不过 ⇒ 回滚**：用编辑前备份把 `application.yml` 还原到**字节一致**（`cmp` rc=0）、删除新测试类、复跑 offline 确认 verify-service 回到 **120**、报告记「未落地（确认轮失败）」并附 C 轮原始读数。**不得保留未被运行证明的默认值改动。**
6. C 轮通过后：停四个服务（`run-perf.sh stop-services`），容器**保持原状**（不 stop 演示库容器亦可，但必须原文记录终态 `docker ps -a`）。

## 8. 硬门 G0–G13（命令与期望读数）

期望值全部是指导侧 2026-09-29 本会话**亲跑**的一手读数（Level A）。任一不符 ⇒ 停手回报原文，不要自行解释、不要「顺手修」。

| 门 | 命令/动作 | 期望（指导侧亲跑值） |
| --- | --- | --- |
| G0 起点 | `git rev-parse HEAD`；`git status --porcelain`；`git rev-list --left-right --count origin/main...main` | `121273d6cf46fd1e8968d4817f8134d71edffda0`；只有既有脏项 + `?? work/mailbox/tasks/TASK-162/`；`0	5` 或 `0	0` |
| G1a 配置开工态 | `git grep -c "verify.outbox" -- verify-service/src/main/resources/application.yml`；同法查 `relay-interval-ms`、`maximum-pool-size`；`grep -c '^verify:'`；`wc -l` | 三个 grep 全 **rc=1（0 命中）**；根键 **1**；**190** 行 |
| G1b 代码开工态 | 读 `VerifyOutboxRelay.java` | L68 `batch-size:100`、L72 `max-retry:16`、L96 `relay-send-concurrency:1`、L126–127 `@Scheduled` 两个默认值 5000/10000、L157 单次 `selectPendingBatch`、**无排空循环**；文件 493 行 |
| G1c offline 全量 | `mvn-verify.sh --mode=offline` | rc=**0**、BUILD SUCCESS、七模块 **36/41/33/103/120/59/10**、Skipped 全 0 |
| G1d 静态门 | `mvn-verify.sh --mode=offline --static=verify-service` | rc=**1**（第 2/2 段 checkstyle 失败）+`You have 867 Checkstyle violations`；**867 是上限**；spotbugs/pmd 被阻断在前 = **未覆盖**，不得写成通过 |
| G1e 词面门 | 正则**现场从 ci.yml 提取**：`sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml | head -1`；四形态（CI 原样 / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）+ 正向对照 | 正则长度 **26**、分支数 **8**（7 个竖线）；四形态全 **ZERO_HIT rc=1**；正向对照（探针 → rc=**0** 且命中探针行 → `git rm --cached` + 删文件 → `git status --porcelain` 与探针前逐字一致） |
| G1f 契约（**已订正**，见 §15） | 无参 `bash scripts/verify/mailbox-contract.sh`；在途 `bash scripts/verify/mailbox-contract.sh --open TASK-162 --baseline=121273d6` | **开工态**：无参 rc=**1**（末行 `判据 A=1 判据 B=0`，原文含「进行中（仅 spec）：TASK-162」与「未声明（--open 缺 TASK-162），判据 A 失败」）——这是**本任务书自身落盘造成的预期读数**（`mailbox-contract.sh` L84–92：只有 spec 没有 handoff 的目录必须由 `--open` 声明）；在途 `--open` rc=**0**（`判据 A 两件套齐（含 1 个待办进行中）+ 判据 B 清单一致`）。**收口态**无参必须 rc=**0**（见 G10） |
| G1g 空白 | `git diff --check` | rc=**0** |
| G1h 主规格 | `wc -l`、`tr -cd '\r'|wc -c`、`tr -cd '\n'|wc -c`、末 2 字节 `od`、`grep -c '^### Requirement:'` | **2806 / CR=2806 / LF=2806 / `0d 0a` / 121**；本任务全程零改动 |
| G1i PLAN | 同上（CR/LF） | **1194 行、CR=0、LF=1194**（纯 LF，追加后仍须 CR=0） |
| G1j delta 目录 | `ls -1d spec/changes/*/ | grep -v archive | wc -l`；`ls -1d spec/changes/archive/*/ | wc -l` | 开工 **20 / 43**；收口 **21 / 43**（只新增自己的目录，不合并、不移名） |
| G1k 磁盘（**已订正**，见 §15） | `Get-PSDrive D` 或 `df -k /d` | **动态量**：门槛是 Free ≥ **100 GB**，不是与指导侧快照逐字节相等。指导侧快照序列 234738380800（20:15）→ 234675306496（20:47，≈218.5 GiB；`df -k /d` 同刻 61% used）自身即在下降。开工与收口各记一次并回报差值；Free < 100 GB 立即停 |
| G1l 容器 | `docker ps -a` | 开工实测：`sport-verify-mysql` Exited(255)、`rocketmq-broker`/`namesrv` Exited(137)、`redis` Exited(0)、`nacos` Exited(143)、`postgis` Exited(255)、`task131-scratch-mysql` **Up**；只 `docker start` 前五个 |
| G2 生效配置 | verify.log 原文取证 Nacos 无 `verify-service.yml`；四个 jar sha256 | Nacos 侧无覆盖（TASK-144 实测 `config data not exist`）；A/B 计数轮全程同一 verify jar（sha256 不变） |
| G3 每轮判据 | V1–V7 | 逐轮原文 + 逐轮通过/无效结论 |
| G4 语义 | 语义门四项 | 全 0 回归 |
| G5 资源 | 资源门五项 | `timeout_total` 增量全 0；`Com_select` 倍数如实记录 |
| G6 改善 | 改善门 | 逐轮 P50/P95/P99/n 原文 + 计算式 |
| G7 落地实现 | 7.1/7.2 + offline + 静态 + `git diff --check` | YAML numstat `N/0`、根键仍 1；新测试类 k 个用例；offline = **36/41/33/103/(120+k)/59/10** 全绿；静态 **≤867**；既有测试类 numstat 为空 |
| G8 C 确认轮 | 7.3 | 判据三项原文；不过则回滚证据（`cmp` rc=0 + offline 回 120） |
| G9 词面门收口态 | 三件套/报告/两件套入库后重跑 G1e | 四形态 ZERO_HIT + 正向对照；**任何入库文件不得内嵌该正则的字面量**（TASK-158 教训：连「描述禁词」都会让 CI 门槛永远红） |
| G10 契约收口 | 在途 `--open TASK-162 --baseline=121273d6` 与收口后无参 | 在途 rc 与过冲逐条归因（既有脏项 + 非 ASCII 路径的 quotepath 转义 + 公共文件 `PLAN.md` 交叠是既知模式）；**收口后无参 rc=0** |
| G11 只改清单 | `git diff --name-only 121273d6..HEAD`；受保护数字计数 base vs HEAD | 恰为第 3 节清单内路径；PLAN.md 里 `13.4`=4、`18.0`=4、`73.93`=5、`68.8`=1、`6315`=1、`1.8612`=1、`3.3066`=1、`5.7056`=1、`9.408`=1、`36525962432`=2、`36438897772`=1 为**基线计数**，HEAD 计数**不得减少**（可增加） |
| G12 提交 | 逐笔 `git show --check`、`git diff --cached --name-only` 原文 | 全 rc=0；清单原文随回复回传 |
| G13 外部门槛 | `--mode=online` / CI | **未跑** ⇒ 如实写「未达外部门槛（本次不 push，待下次授权由 CI 复验）」 |

> G1 读数核对助手（指导侧本会话用过、已在磁盘、属忽略目录、不入库）：`.trae/tmp/lead-precheck.sh`（打印 G1a/G1h/G1i/G1j 与 delta 目录计数）、`.trae/tmp/lead-speccheck.sh`（打印本任务书的结构与词面门四形态 + 正向对照）。你仍须自己写脚本跑全部门槛并回传原文；这两个只用于与指导侧读数对齐。

## 9. 提交结构（逐路径 add；提交信息走 UTF-8 文件 `-F`）

- **落地支（4 笔）**：C1 = `application.yml` + 新测试类；C2 = 报告 + JSON；C3 = 三件套；C4 = PLAN 追加 + `TASK-162/{spec.md,handoff.md}`。
- **未定/反证支（3 笔）**：C1 = 报告 + JSON；C2 = 三件套；C3 = PLAN + 两件套。
- 末笔允许 `--amend --no-edit` 补记收口实测（TASK-157~161 先例）；**初版 SHA 必须在补记里披露**，终版以 `git log -1` 核对。
- 不 push、不建 PR、不 stash、不 `add -A`。

## 10. 交付物

1. `docs/perf/复测-outbox-relay-调度间隔-可重复性.md`：一句裁决（落哪一支 + 关键数字）、与 TASK-144 的关系（**其 UNDETERMINED 不被翻案、其数字不被改写**）、逐轮表（含丢弃预热轮）、逐轮 V1–V7、配对 P50/P95/P99/n、净投递速率与空档形态、资源读数、语义回归、落地/回滚证据、未覆盖与不得推出。
2. `docs/perf/data/exp-outbox-relay-interval-repeatable.json`：结构沿用 `exp-outbox-relay-interval.json`（task/head/date/verdict/rounds/comparability/metricGate/exitCodes/notCovered/notClaimed），新增 `warmupRounds`、`validityPerRound`、`landing`（含 C 轮与回滚）。
3. 三件套 `spec/changes/prove-verify-outbox-relay-interval-repeatable/`：`proposal.md`（Why / What Changes / Impact / 停止条件，含第 14 节 5 条预登记反例）、`specs/sport-record-verify/spec-delta.md`（**纯 ADDED**，`## ADDED Requirements` + `### Requirement:` + `#### Scenario:` 形态，照 TASK-160/161 的 delta）、`tasks.json`（每步 `completed`/`passes`；**未落地时落地步必须 `completed=false`/`passes=false`，不得伪绿**——参照在途 `update-verify-outbox-relay-delay/tasks.json` 第 3 项的诚实写法）。
4. `work/mailbox/tasks/TASK-162/handoff.md`：结论、起点全 SHA、只改逐文件清单、G0–G13 原文与退出码、逐轮原始读数、未覆盖与不得推出。
5. `work/mailbox/PLAN.md` 末尾**纯追加**验收记录节（表格形态照 TASK-161 节；含「是否到达外部门槛」栏与「是否实施/是否落地」栏）。

## 11. 未覆盖与不得推出（逐条写进报告与 handoff）

- 四服务局部（leaderboard/mapmatch/postgis 未起）⇒ 榜单消费与真实 R5 **未覆盖**，A/B/C 全部在 R5 降级下跑，不得声称全链路。
- 不得把 B 档收益外推到更高到达率、更长时间窗或生产多实例（本轮只证明单实例、2010 行、本窗口）。
- **不得声称任何并发收益**：`relay-send-concurrency` 全程 = 1；不得用 TASK-161 的 `S_prod(N)` 换算本轮任何数字。
- 不得把本轮 P50 与 TASK-152 的 `18.0 ms/行`、TASK-156/161 的 `S(N)`/`S_prod(N)` 并列成「优化前后」（不同实例/不同装配/不同窗）。
- 不得改写 TASK-143/144/145/152/156/161 的任何数字或结论；**TASK-144 的 UNDETERMINED 不被翻案**（本轮是新实验、新预注册、新预算）。
- spotbugs/pmd **未覆盖**（被 checkstyle 阻断在前）；`--mode=online`/CI 未跑 ⇒ **未达外部门槛**。
- 落地后 500 ms 使空扫 tick 频率约 10×，这是**已披露的代价**；不得只报收益。
- 演示库会新增约 1.2~1.6 万行记录与相应轨迹点（含预热轮），这是运行证据，**不得清理**。

## 12. 停止条件（立即停、如实报、不落地）

起栈失败或任一容器起不来 / 健康检查不过 / Nacos 存在覆盖 `verify.outbox` 的配置 / 磁盘可用 < 100 GB / 演示库出现耗尽行增长 / 任一 cell 预算耗尽仍不足 2 个有效轮 / 负载出现 429 或 errors>0 / relay 出现失败行或耗尽行 / 任一轮日志缺失或配对样本不完整 / 开工读数与 G1 任一项不符。**不为凑满轮次强行继续，不为救结论放宽门槛。**

## 13. 回传格式（给指导侧，照此结构）

1. 一句裁决：落哪一支 + 四个关键数字（A 较好者 P50、两轮 B 的 P50、下降百分比）+ 是否落地。
2. 逐轮表（含丢弃预热轮）：label / cell / 注入 / QPS / ok / errors / 429 / 消费失败 / 重投 / P50 / P95 / P99 / n / 净投递速率 / >1s 空档数与中位 / 排空耗时 / V1–V7 结论。
3. G0–G13 逐项：命令原文 + 退出码 + 关键读数（尤其 G1c 七模块数字串、G1d 违规数、G1e 四形态与正向对照、G7 offline 数字串与 k、G8 C 轮判据、G10 收口无参 rc）。
4. 提交清单：每笔 SHA + `git diff --cached --name-only` 原文 + `git show --check` rc；`git diff --shortstat 121273d6..HEAD`。
5. 只改清单一致性 + 受保护数字 base/HEAD 计数表。
6. 未覆盖与不得推出（照第 11 节逐条确认）。
7. 异常与自踩红线披露（编译失败、伪命中、命令行中文、工具报错等，**一律如实**，附日志路径）。

## 14. 预登记反例（5 条，必须在 proposal 里逐字登记并逐条说明触碰情况）

1. **「B 档改善已由 TASK-144 证明，可以直接落地」**——不成立：TASK-144 的改善门虽满足，但**可比性门失败**，其裁决是 UNDETERMINED；本任务不重判 TASK-144，而是用修好可比性的新实验独立取证。若本轮可比性再失败，结论仍是未定。
2. **「500ms 一定比 5000ms 好，所以可以顺手试 200ms/1000ms 找最优」**——禁止：换档试探就是钓鱼。B 档取值 **500ms** 在本任务书里预注册死，不换、不扫。
3. **「间隔调小后 relay 更快，所以并发也可以一起开」**——禁止：`relay-send-concurrency` 全程 = 1；TASK-160 的代码默认关闭，TASK-161 只到「证据不足」，两者都不构成本轮开启并发的授权。
4. **「P50 从 57s 降到 19s 说明单行成本下降了」**——不成立：本轮只改 tick 频率，单行 `syncSend`+`markSent` 成本未测未变；净投递速率上升来自空档缩短，不得表述为「单行变快」或「SQL 优化」。
5. **「资源没报红就说明 500ms 无代价」**——不成立：空扫 tick 频率约 10× 是结构性代价，必须与收益并列披露；`Com_select` 增量倍数缺失即视为披露不完整，指导侧会退回。

## 15. 任务书订正记录（指导侧自纠，2026-09-29）

执行侧按「任一不符 ⇒ 停手回报原文」在 G0/G1 阶段停手，报出 2 项不符。指导侧**亲手复跑取证**后裁定如下：两项均**放行**，其中 G1f 的责任在指导侧（任务书期望值过期）。

1. **G1f 契约门（订正，责任在指导侧）**：任务书原写「开工无参 rc=**0**」。该期望值取自指导侧 20:15 前后的一次实跑，**早于本任务书 spec.md 落盘（20:26:40）**——文件时间戳证据：`.trae/tmp/lead-precheck.sh` 20:22:01、`.trae/tmp/lead-speccheck.sh` 20:25:23、`work/mailbox/tasks/TASK-162/spec.md` 20:26:40。spec 落盘后该目录成为「仅 spec 无 handoff」的在途任务，无参形态必然 rc=1。指导侧 20:47 复跑取证：无参 rc=**1**（`判据 A=1 判据 B=0`）、`--open TASK-162 --baseline=121273d6` rc=**0**（`判据 A 两件套齐（含 1 个待办进行中）+ 判据 B 清单一致`），与执行侧原文逐字一致。**裁定：开工口径 = 在途 `--open` rc=0 且无参 rc=1 为预期；收口态无参 rc=0 仍由 G10 承担。**
   **指导侧自记（流程修正）**：任务书自身落盘会改变契约门读数，故任何写进任务书的契约门期望值**必须在 spec.md 写完之后再复跑取**；本轮先跑后写导致期望值过期。
2. **G1k 磁盘（订正表述，门槛未变）**：Free 是动态量，指导侧给的 234738380800 只是快照。执行侧实测序列 234738380800 → 234727989248（G1c 前）→ 234710654976（G1c 后），与指导侧 20:47 的 234675306496 同向下降，成因是全量构建与系统写盘。**裁定：放行；门槛仍是 Free ≥ 100 GB（当前 ≈218.5 GiB，远未触发）；以执行侧 G1c 前的 234727989248 为开工基线，收口复记一次并回报差值。**
3. **采纳执行侧披露的 harness 事实**：权威 bash 的 grep 3.1 在 CRLF 行上 `$` 锚点失配（执行侧用二分实验证明：去掉该锚点的同式命中 60 行），已写入 §0 第 7 条。执行侧据此以 `tr -d '\r'` 归一化后的日志复核 G1c/G1d 读数，**方法正确、读数采信**。
4. **不重跑条款**：G0、G1a–G1e、G1g–G1j、G1l 已实测通过且与期望值一致，**不必重跑**；G1f 按本节订正口径把两种形态的原文补进 handoff 即可；G1k 按本节口径记开工基线与收口值。其余 G2–G13 照原任务书执行。
5. **不变的部分**：预注册裁决门（§6）、轮次协议与预算（§5）、落地细则与 C 确认轮/回滚（§7）、只改与禁改清单（§3）、提交结构（§9）、交付物（§10）、未覆盖与不得推出（§11）、停止条件（§12）、回传格式（§13）、5 条预登记反例（§14）**一字未改**。本节只订正两处期望值表述与一处 harness 口径，**不放宽任何门槛**。
