# TASK-163 任务书：以「负载停止后的排空斜率」判别 relay 调度间隔，并条件式落地默认值

> 执行侧唯一权威任务书。指导侧已复核并接受 TASK-162 的**未定支（UNDETERMINED）**裁决，已把该批 8 笔推送并取得 CI run `36586847965` success。
> 本任务书里所有「开工读数」都是指导侧 2026-09-29 深夜**亲跑**的一手值（Level A）。任一不符 ⇒ **停手回报原文**，不要自行解释、不要顺手修。
> 本任务书落盘后契约门的无参形态**必然 rc=1**（详见 G1f 与 §15 教训），这不是异常。

## 0. 角色、铁律与工具口径

1. 你是执行侧，做有界机械活并按本任务书取证；判断密集的事（门槛设计、口径订正、裁决归属）由指导侧做。**报告＝待验证假设**，指导侧会重跑你跑过的每一道门。
2. **不 push、不建 PR、不 `git stash`、不 `git add -A`/`git add .`**（逐路径 add）、不改任何 git 配置、不动既有脏项。
3. **命令行全程 ASCII**：中文（提交信息、报告、grep 过滤词）一律先写进 `.trae/tmp/` 下的 UTF-8 无 BOM 文件再用（提交走 `git commit -F`）。
4. **bash 一律写成 `.sh` 文件**（放 `.trae/tmp/`）再 `& 'D:\git\Git\bin\bash.exe' <路径>` 调用；**禁 `bash -lc` 内联**；需要抓非零退出码的段落**不要**放 `set -e` 下。
5. **权威解释器** `D:\git\Git\bin\bash.exe`（git 2.20.1）。词面门只能用它跑（另一套自带 git 2.52 在 `C.UTF-8` 下会产生 2 处伪命中，TASK-161 已实测）。
6. **Maven 唯一入口** `& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh ...`；禁裸 mvn、禁 `MAVEN_OPTS`、禁建 `.mvn/maven.config`（本任务不跑 IT，不需要带外通道）。
7. **harness 三条红线（前两轮实测）**：① `grep -c $'\r$'` 假阳性、`awk '$0~/\r/'` 假阴性（GNU Awk 4.2.1 剥记录尾 CR）⇒ 行尾计数只用 `tr -cd '\r' | wc -c` / `tr -cd '\n' | wc -c`，定位单行 CR 只用字节扫描（`od -An -tx1 -v`）；② 权威 bash 的 grep 3.1 在 **CRLF 行尾上行尾锚点失配**（本机 mvn/Java 日志是 CRLF）⇒ 凡按行尾锚点从日志提取数字，先 `tr -d '\r'` 归一化再 grep，否则空输出会被误读成「门没过」；③ `git grep --untracked` 必须置于 pattern **之前**（否则 fatal rc=128），且**不搜 ignored 文件**⇒ 词面门正向对照的探针**必须植在非忽略路径**（例如仓库根），植在 `.trae/tmp/` 会让对照恒空（TASK-162 执行侧首版即踩此坑）。
8. **词面门三态判定**：rc=0 命中 / rc=1 无命中 / **其他 rc＝工具错误，判失败不判通过**；必做正向对照（探针 → 命中 → `git rm --cached` + 删文件 → `git status --porcelain` 与探针前逐字一致）。正则**一律从 `.github/workflows/ci.yml` 现场提取**，**任何入库文件不得内嵌其字面量**（连「描述禁词」也不行，TASK-158 教训）。

## 1. 唯一问题与口径变更声明

**唯一问题**：`verify.outbox.relay-interval-ms` 由 5000 改 500，relay 的**净投递能力**（负载停止后的排空斜率，rows/s）提升多少？若达到预注册门槛且健康/代价门全过，则把**这一项**默认值落地到 verify-service 的 classpath YAML 并加绑定测试。

**为什么换指标（口径变更声明，必须逐字进 proposal 与报告）**：

- TASK-144 与 TASK-162 是同一问题的两次独立预注册实验，**都因「提交 QPS 可比性门」判 UNDETERMINED**。TASK-162 实测：提交 QPS 在整个作业窗**单调上漂**——预热轮 84.09 / 95.13 / 108.01，计数轮 119.24 / 141.56 / 136.23 / 187.04 / 169.46 / 204.39（首尾 +143%），墙钟 23.784s → 9.785s。
- 结论：**任何「A-B-B-A + 相对池中位数 ±15%」的设计在该环境下结构性不可满足**——越晚跑的轮次 QPS 越高，而 A 档按设计要有两轮分布在首尾，尾部 A 轮必然超限。TASK-162 的 A2/A3/A4 正是这样被判无效的（+37.30% / +24.39% / +50.03%）。
- 因此本轮**不再用提交 QPS 作有效性判据**，改用**负载停止之后**测得的排空斜率：该测量窗内提交负载为 0、到达率为 0，斜率只由 relay 自身节奏决定，**与提交 QPS 无关**。这不是放宽容差，而是换掉被污染的度量。
- **同时加强因果控制**（TASK-144/162 都没有这道门）：新增 **M3 排序控制门**——第二个 A 轮（A2）在时间上**晚于**两个 B 轮，若系统「越跑越快」能解释 B 的优势，A2 就该比 A1 快；预注册要求 A2 与 A1 的斜率相差 ≤20%。TASK-162 的既有数据在这道门上是通过的（A1 14.40 / A2 14.88 / A3 14.94 / A4 15.27 rows/s，极差 ±3%；且 A2~A4 更「热」却仍慢 3 倍），但那**不是本轮证据**，本轮必须自己重测。
- **不翻案**：TASK-144 与 TASK-162 的 UNDETERMINED 结论与其全部数字**一律不改写、不重判**；本轮是新数据、新预注册、新预算。TASK-162 的 −65.06%/−67.52% 仍是**未过门观测**，不得在本轮报告里被写成「已证明的收益」。
- **QPS 仍逐轮记录并披露**（含 10s 桶创建形态），只是不设门；必须披露两个 A 轮之间、两个 B 轮之间的 QPS 差异，不得隐藏。

## 2. 结构模型与阈值先验推导（阈值不是从数据反推的）

生产代码事实（Level A，指导侧亲读）：`VerifyOutboxRelay` L126–127 `@Scheduled(fixedDelayString = "${verify.outbox.relay-interval-ms:5000}", initialDelayString = "${verify.outbox.relay-initial-delay-ms:10000}")`；L157 每 tick **只调用一次** `selectPendingBatch(batchSize, maxRetry)`（L68 `batch-size:100`、L72 `max-retry:16`），其后一趟 for 循环，**无排空循环**；L96 `relay-send-concurrency:1`（TASK-160 落地、默认关闭）。`application.yml` 无任何 `verify.outbox` 键 ⇒ 生效值即上述 `@Value` 默认。

由此得稳态模型：`slope = 100 / (interval + T_batch)`，`T_batch` = 处理一批 100 行的锁内耗时。已入库实测 `T_batch` ≈ 1.312s（TASK-145，5000ms 档）/ 1.452s（TASK-145，500ms 档）。代入：

- A 档（interval=5s）：slope = 100/(5+1.312..1.452) = **15.8..15.6 行/s**（TASK-144/162 实测净速率 14.40~15.27，含负载期重叠，量级一致）
- B 档（interval=0.5s）：slope = 100/(0.5+1.452) = **51.4 行/s**（实测净速率 33.66~37.25 偏低，说明 B 档 `T_batch` 实际约 2.4~2.5s，比 TASK-145 的 1.452s 大）
- **比值 = (5+T)/(0.5+T)**：T=1.312 → 3.50；T=2.5 → 2.50；T=10 → 1.45。⇒ 结构预测比值落在 **2.5~3.5**，只有当 `T_batch` 大到 10s 才会掉到 1.45。

预注册门槛取值（保守下界，先验推导，非数据拟合）：**M2 比值 ≥ 1.5**（约为结构预测下沿的 60%）；**M3 A2/A1 斜率相差 ≤ 20%**；**M1 空档窗**：A 档 >1s 空档数 ≥15 且中位 ∈ [4000, 6000] ms（2000 行 / 批 100 ⇒ 约 20 个批间空档，每个约 5s），B 档 >1s 空档数 ≤5（批间空档约 0.5~3s，多数不足 1s）。

**顺带得到的容量事实（Level B 算术，不需新测量，可在报告里作为派生结论）**：A 档稳态上限 ≈15 行/s，而已入库实测的负载期 outbox 创建速率为 87.39~91.36 行/s（TASK-144）⇒ 默认配置下 relay 的排空能力比演示负载的到达率**低约 6 倍**，任何持续到达 >15 行/s 都会让 `verify_event_outbox` 的 PENDING 无界增长。本轮**只把该算术作为派生结论登记**，不据此单独落地（落地仍须过 M1–M5）。

## 3. 起点冻结（G0）与开工读数（G1，指导侧亲跑值）

- 开工基线 SHA = `1ff96e0697694b6ec9669bc35c7234f2efdfe92d`（`git rev-parse HEAD` 必须逐位一致）。该笔是指导侧的 PLAN L4 订正（仅 L4 单行整行替换，numstat 1/1）。
- `git rev-list --left-right --count origin/main...main` 原样记录：`0	1`（指导侧尚未推送 L4 订正）或 `0	0`（已推送）**都合法**；其余取值停手回报。
- 工作树只允许两类未跟踪项：既有脏项 `?? spec/changes/add-verify-degrade-status-index/`（**零触碰**）与本任务书目录 `?? work/mailbox/tasks/TASK-163/`（收口随台账入库）。
- **java 进程数必须为 0**（指导侧 23:0x 已用 `bash scripts/perf/run-perf.sh stop-services` 停掉 TASK-162 遗留的四个服务，实测 `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 计数 0）。若你开工时发现有 java 进程在跑，**先停再动手**：jar 被占用会让 `mvn clean` 删不掉 target 而 BUILD FAILURE（指导侧本轮亲历，原文 `Failed to clean project: Failed to delete D:\code\sports\gateway-service\target\...`）。
- 五个演示容器开工时**已是 Up (healthy)**（指导侧 23:0x 实测 `sport-verify-mysql` / `rocketmq-namesrv` / `rocketmq-broker` / `redis` / `nacos` 均 Up 2 hours；`task131-scratch-mysql` 亦 Up，**不得触碰**）。若届时已 Exited，只 `docker start`，**禁 `docker compose up`**（会 recreate）、禁改配置、禁删卷。`sport-verify-postgis` **不起**（榜单与真实 R5 本轮不覆盖）。

## 4. 只改清单（超出即失败）与禁改清单

**只改（6 项）**：
1. `docs/perf/判别-outbox-relay-排空斜率.md`（新报告）
2. `docs/perf/data/exp-outbox-relay-drain-rate.json`（机器摘要）
3. **仅落地支**：`verify-service/src/main/resources/application.yml`（纯新增行，numstat 必须 `N/0`）
4. **仅落地支**：`verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayIntervalDefaultTest.java`（新增纯 JUnit 测试类）
5. `spec/changes/prove-verify-outbox-relay-drain-rate/`：`proposal.md` + `specs/sport-record-verify/spec-delta.md` + `tasks.json`（**纯 ADDED**）
6. `work/mailbox/PLAN.md`（**纯追加**验收记录节，既有行含 L4 零改动）+ `work/mailbox/tasks/TASK-163/{spec.md, handoff.md}`；另 `docs/perf/data/raw/task163-*` 与 `.trae/tmp/*` 属忽略路径（`.gitignore` L46/L64/L22），**不入库**

**禁改**：所有生产 Java（`VerifyOutboxRelay` / `RelayDiagnostics` / `VerifyEventOutboxMapper` / `VerifyEventProducer` / 消费者）、任何 SQL/索引/schema/迁移、任何 pom、`scripts/**`、`.github/**`、`docker-compose*.yml`、主规格 `spec/specs/sport-record-verify/spec.md`（**本任务不合并任何 delta**）、`spec/changes/archive/**`、其余 21 个在途 `spec/changes/*` 目录（含 TASK-162 的 `prove-verify-outbox-relay-interval-repeatable`：其 UNDETERMINED 结论与 tasks.json 的 `completed=false` **一律不动**）、其他任务的 `work/mailbox/tasks/*`、既有 `docs/perf/**` 全部文件（TASK-143/144/145/152/156/161/162 的报告与 JSON：**数字与文字都不得改写**）、既有测试类（`VerifyOutboxRelayTest` 19 个用例、`VerifyOutboxRelayConcurrencyTest` 10 个、`RelayDiagnosticsTest`、`VerifyEventProducerTest`、`config/` 下既有 2 个）、既有 10 个 `*IT`（verify-service 7 + leaderboard-service 3）。

**不得改的因素**：`relay-send-concurrency` 全程 = **1**、`batch-size` = 100、`max-retry` = 16、`relay-initial-delay-ms` = 10000、消费线程 32/40、`consume-message-batch-max-size` = 8、连接池、JVM、MQ、Nacos、索引、SQL。MySQL 容器不得 recreate/改配置/删卷/改 `innodb_flush_log_at_trx_commit`；`task131-scratch-mysql` 与 `task161_pool_scratch` 不得触碰；演示库**禁 TRUNCATE/DELETE/清库**（本轮会新增约 1.4 万行记录与相应轨迹点，属运行证据，不得清理）。

## 5. 起栈 runbook 与**收尾条**（照既有工具，不得另造）

1. 容器：按 §3，开工时应已 Up；若 Exited 则 `docker start sport-verify-mysql sport-verify-rocketmq-namesrv sport-verify-rocketmq-broker sport-verify-redis sport-verify-nacos`，`docker ps` 原文留档。
2. 建 jar：`& 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh --mode=offline package`（rc=0，同时充当开工 offline 证据）；四个 jar 用 `Get-FileHash` 记 sha256；**A/B 计数轮之间不得重建任何 jar**（jarSwapDuringRounds 必须 = NONE）。
3. 起服务：`bash scripts/perf/run-perf.sh start-services`，再 `bash scripts/perf/healthcheck.sh`（8080/8081/8082/8083 原文留档）。
4. B 档注入（唯一变量）：按 `run-perf.sh` L109 的 kill 模式停 verify-service（按 jar 名匹配，勿按端口杀），再起 `nohup "$JAVA_BIN" -jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar --verify.outbox.relay-interval-ms=500 > logs/verify.log 2>&1 &`；A 档 = 不带该参数重启。**每次重启后等健康 + ≥15s**（越过 `relay-initial-delay-ms=10000`）。启动 verify-service 的那个 shell 必须先 `export MYSQL_PORT=3307`（TASK-162 执行侧曾漏此步导致连到宿主 3306 Access denied、进程被杀）。
5. 负载：`bash scripts/perf/run-perf.sh load 100 2000 task163-<label>`（内部 `--warmup 10 --timeout 60 --user-total 16`，产物落 `docs/perf/data/raw/task163-<label>-c100-*`）。
6. 资源采样：每轮 before/after 各一次 `bash scripts/perf/relay-round-sampler.sh task163-<label> before|after`（含 `hikaricp_connections_pending/active/idle/max/timeout_total`、MySQL 全局状态、JVM/GC；取不到时脚本自标 FAILED/UNAVAILABLE，**不得**写成「无异常」）。
7. 库侧读数一律 `docker exec sport-verify-mysql mysql -uroot -proot -N -e "<SQL>"`（照 `run-perf.sh` L76–84 既有形态）。
8. **收尾条（不论落哪一支都必须做，TASK-162 任务书缺此条导致指导侧本轮 BUILD FAILURE）**：最后一轮结束后 `bash scripts/perf/run-perf.sh stop-services`，并验证 `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 计数 = **0**；随后**必须**复跑一次 `mvn-verify.sh --mode=offline`（收口 offline 证据，两支都要跑）；容器保持原状并原文记录终态 `docker ps -a`。

## 6. 轮次协议（预热全弃；计数轮**交错** A1 → B1 → A2 → B2）

**预热轮（丢弃、不进任何统计，但必须逐轮在 handoff 列出 label/QPS/墙钟/排空耗时）**：W0（起栈后首轮之前，`load 100 2000 task163-w0`）；W_cell（每次 verify-service 重启后、该 cell 首个计数轮之前，`load 100 200 task163-w<cell>`）。**最多 4 个预热轮**。预热轮同样要跑到 PENDING 归零再进入下一轮。

**计数轮固定 7 步（缺一步该轮无效）**：
1. 前置：`healthcheck.sh` 四端口 200；可投递 PENDING 与耗尽 PENDING 均 = 0（`SELECT COUNT(*) FROM verify_db.verify_event_outbox WHERE status='PENDING' AND retry_count < 16` / `... AND retry_count >= 16`）；记 id 下界（`SELECT COALESCE(MAX(id),0) FROM record_db.sport_record`、`SELECT COALESCE(MAX(id),0) FROM verify_db.verify_event_outbox`，各 +1 为 RecMin/ObMin）；记磁盘可用；`relay-round-sampler.sh ... before`；确认该 cell 的注入状态（A 档进程命令行无 `relay-interval-ms`，B 档有 `=500`，用 `Get-CimInstance` 的 CommandLine 原文留档）。
2. 负载：`run-perf.sh load 100 2000 task163-<label>`。
3. **排空采样（本轮新指标，负载命令返回后立即开始，不得先做别的事）**：每 2s 采一次可投递 PENDING 计数并打毫秒时间戳，写入 `docs/perf/data/raw/task163-<label>-drain.csv`（两列 `epoch_ms,pending`），直到计数为 0；硬超时 A 档 400s / B 档 200s（超时 ⇒ 该轮无效并记原因）。参考实现（写进 `.trae/tmp/` 的 .sh，不要内联）：`while :; do ts=$(date +%s%3N); n=$(docker exec sport-verify-mysql mysql -uroot -proot -N -e "SELECT COUNT(*) FROM verify_db.verify_event_outbox WHERE status='PENDING' AND retry_count<16"); echo "$ts,$n" >> "$OUT"; [ "$n" = "0" ] && break; sleep 2; done`。
4. 后置：记 id 上界（RecMax/ObMax）；`relay-round-sampler.sh ... after`；**立即**把 `logs/verify.log` 与 `logs/record.log` 复制成 `docs/perf/data/raw/task163-<label>-verify.log` / `-record.log`（verify.log 会在下次重启时被 `>` 截断，晚复制即丢证据）；统计 relay 失败行/耗尽行/`RECONSUME_LATER`/`Fallback`/重复 `event_id` 报错行数，并按时间窗区分「本轮窗内」与「预热窗残留」（TASK-162 双口径先例）。
5. 配对：复用 `docs/perf/data/raw/task144-parse.ps1`（忽略目录、未 tracked）：`powershell -NoProfile -File docs/perf/data/raw/task144-parse.ps1 -Label task163-<label> -VerifyLog <复制后的 verify.log> -RecordLog <复制后的 record.log> -RecMin <> -RecMax <> -ObMin <> -ObMax <> -Out docs/perf/data/raw/task163-<label>-stats.txt`。**不得修改该脚本**；确需改则复制成同目录 `task163-parse.ps1` 再改，并在 handoff 逐行说明差异。
6. 斜率计算（预注册估计量，不得换）：`P_peak` = 该轮 drain.csv 中 PENDING 的最大值；`t_peak` = 首次达到 P_peak 的时间戳；`t_zero` = 首次采到 0 的时间戳；**`slope = P_peak / ((t_zero − t_peak)/1000)` 行/s**。另报**前半段斜率** `slope_half = (P_peak − P_at_mid) / ((t_mid − t_peak)/1000)`（`t_mid` = t_peak 与 t_zero 的中点时刻最近采样）用于线性度检查：`|slope_half − slope| / slope > 30%` 必须披露并给出形态解释（阶梯/批边界）。**要求 `P_peak ≥ 100`**（小于批大小时斜率不再反映天花板 ⇒ 该轮无效）。取峰值而非负载停止后首值，是因为负载返回瞬间仍有在途消费尚未写入 outbox（TASK-162 的 10s 桶形态即此现象）。
7. 记账：本轮 outbox 行数、markSent 计数（须 = cohort）、`retry_count > 0` 行数（须 0）、耗尽行增量（须 0）、10s 桶创建形态、提交 QPS 与墙钟（**只披露不设门**）。

**逐轮判据**：
- **M1 机制门**（证明单变量真的生效）：从复制后的 verify.log 取 relay 相邻 SENT 时间戳间距，A 档 >1s 空档数 **≥15** 且其中位 ∈ **[4000, 6000] ms**；B 档 >1s 空档数 **≤5**。（参照：TASK-144/162 实测 A 档 20~23 个、中位 5017~5023ms；B 档 1~2 个、中位 1053~1065ms。）
- **M4 健康门**：`ok=2000`、`errors=0`、`limited429=0`；relay 失败行 = 0、耗尽行 = 0；`retry_count>0` 行 = 0；耗尽行增量 = 0；`uk_event_id` 零重复报错；markSent 计数 = cohort 行数；**本轮窗内**零 `RECONSUME_LATER` / 零 `RecordApiFallback`；轮前可投递与耗尽 PENDING 均 = 0；`hikaricp_connections_timeout_total` 增量 = 0；零 `锁释放异常` / 零 `锁获取被中断`；drain.csv 完整（首样本 ≤ 负载返回后 5s、末样本 = 0）。
- **M5 代价门**：逐轮记录 `Com_select` / `Com_update` 增量并给出 B/A 比值（预期 ≈1.0，TASK-162 实测 1.001）；比值 >1.5 必须给结构性解释并**与收益并列披露**，不得只报其一。

**预算（硬上限，防钓鱼）**：**4 个计数轮**（A1/B1/A2/B2）+ **最多 2 个替换轮**（仅当某轮 M4 健康门红时才允许替换，且替换轮追加在尾部、cell 与被替换轮相同）+ **最多 4 个预热轮** + 落地支的 1 个 C 确认轮。M1/M2/M3 不过**不给替换轮**（那是结论，不是事故）。失败轮照占预算，**不得为凑结论加跑**。

## 7. 预注册裁决（三支 + 落地门，不得事后放宽）

- **M2 效应门**：`slope(B1)/slope(A1) ≥ 1.5` **且** `slope(B2)/slope(A2) ≥ 1.5`。
- **M3 排序控制门**：`|slope(A2) − slope(A1)| / slope(A1) ≤ 0.20` **且** A2 的 M1 亦成立（A2 在时间上晚于两个 B 轮，用以排除「系统越跑越快」的解释）。
- **落地支**：4 个计数轮**全部有效**（逐轮 M1 + M4 通过）**且** M2、M3、M5 全过 ⇒ 按 §8 落地 `relay-interval-ms: 500` + 绑定测试，并跑 **C 确认轮**；C 不过 ⇒ 字节回滚。
- **未定支**：任一计数轮因 M4 无效且替换轮用尽，或起栈/环境/数据关联不可靠 ⇒ **不改任何默认值**、不动 `application.yml`、不加测试类；报告记「未定」并逐条列触发的门与实测读数。
- **反证支**：`slope(B) ≤ slope(A)`（比值 ≤1.0）、或 M3 失败（A2 明显快于 A1 ⇒ 时间/预热足以解释差异）、或 M1 在任一 A/B 轮不成立（说明注入未生效或单变量被破坏）⇒ 记「**不推荐**」，明写与 TASK-144/162 的方向关系，**不得挑选有利的一轮**，不改默认值。
- 无论落哪一支：**不得放宽门槛、不得换 B 档取值（不试 200/1000/2000ms）、不得叠加 batch/并发/池/JVM/SQL/索引/MQ 作为补救**（继承 TASK-144 停止条件）。

## 8. 落地实现细则（仅落地支；含 C 确认轮与回滚）

**8.1 `application.yml`（纯新增，numstat 必须 `N/0`）**：开工实测该文件 **190 行**、`^verify:` 根键**恰好 1 个**、`verify.outbox` / `relay-interval-ms` / `maximum-pool-size` 三个 grep 全 **0 命中**（rc=1）。编辑后必须仍然**只有 1 个** `^verify:` 根键（重复根键会静默覆盖，是本轮最大落地陷阱）：在既有 `verify:` 映射内、与 `rules:` 同级（2 空格缩进）新增 `outbox:` → `relay-interval-ms: 500`，配 3~5 行注释写明判别来源（TASK-163 报告路径）、与 TASK-144/162 两次 UNDETERMINED 的关系（新指标、新预注册，**不翻案**）、**只改这一项**、`relay-send-concurrency` 仍为 1。既有行一个字节都不动。

**8.2 新增测试类 `verify-service/src/test/java/com/sportverify/verify/config/VerifyOutboxRelayIntervalDefaultTest.java`**：**纯 JUnit 5**，**禁** `@SpringBootTest`、禁任何中间件/网络依赖（offline 套件必须在全停机下可跑）。至少 3 个 `@Test`（记为 k）：① 用 `YamlPropertySourceLoader` 加载 classpath 的 `application.yml`，断言 `verify.outbox.relay-interval-ms` = **500**；② 反射断言 `VerifyOutboxRelay#relay` 的 `@Scheduled.fixedDelayString` **仍逐字**为 `${verify.outbox.relay-interval-ms:5000}`、`initialDelayString` 仍为 `${verify.outbox.relay-initial-delay-ms:10000}`（证明生效值来自 YAML 覆盖、代码默认值未改）；③ 断言 classpath YAML 里 `^verify:` 根键只出现一次。checkstyle 只扫 `src/main/java`（指导侧实测 867 条违规全部来自主源）⇒ 新测试类不新增违规，静态门仍要求 **≤867**。既有测试类 numstat 必须为空。

**8.3 C 确认轮（落地后的功能确认，不进 A/B 统计）**：① `mvn-verify.sh --mode=offline --pl verify-service package`（rc=0），用 `Get-FileHash` 证明**只有** verify-service 的 jar 变了；② 停 verify-service，以**无任何注入**启动新 jar，健康 + ≥15s，跑 W_C 预热（c100×200，丢弃）并排空；③ C 轮 `load 100 2000 task163-C` + 排空采样；④ **判据**：C 轮 M1 满足 B 档口径（>1s 空档数 ≤5）**且** `slope(C) / mean(slope(A1), slope(A2)) ≥ 1.5`；⑤ **C 判据不过 ⇒ 回滚**：用编辑前备份把 `application.yml` 还原到**字节一致**（`cmp` rc=0）、删除新测试类、复跑 offline 确认 verify-service 回到 **120**、报告记「未落地（确认轮失败）」并附 C 轮原始读数。**不得保留未被运行证明的默认值改动**；⑥ 收尾照 §5 第 8 条。

## 9. 硬门 G0–G13（命令与期望读数＝指导侧亲跑值）

| 门 | 命令/动作 | 期望（指导侧 2026-09-29 23:0x 亲跑） |
| --- | --- | --- |
| G0 起点 | `git rev-parse HEAD`；`git status --porcelain`；`git rev-list --left-right --count origin/main...main` | `1ff96e0697694b6ec9669bc35c7234f2efdfe92d`；只有既有脏项 + `?? work/mailbox/tasks/TASK-163/`；`0	1` 或 `0	0` |
| G1a 配置开工态 | `git grep -c` 三查 `verify.outbox` / `relay-interval-ms` / `maximum-pool-size`（限 `verify-service/src/main/resources/application.yml`）；`grep -c '^verify:'`；`wc -l` | 三 grep 全 **rc=1（0 命中）**；根键 **1**；**190** 行 |
| G1b 代码开工态 | 读 `VerifyOutboxRelay.java` | **493** 行；L68 `batch-size:100`、L72 `max-retry:16`、L96 `relay-send-concurrency:1`、L126–127 `@Scheduled` 默认 5000/10000、L157 单次 `selectPendingBatch`、**无排空循环** |
| G1c offline 全量 | `mvn-verify.sh --mode=offline` | rc=**0**、BUILD SUCCESS、七模块 **36/41/33/103/120/59/10**、Skipped 全 0（指导侧 22:57 亲跑，用时 2:14；**必须在四个服务已停的前提下跑**，否则 clean 删不掉 target 会 BUILD FAILURE） |
| G1d 静态门 | `mvn-verify.sh --mode=offline --static=verify-service` | rc=**1**（第 2/2 段 checkstyle 失败）+ `You have 867 Checkstyle violations`；**867 是上限**；spotbugs/pmd 被阻断在前 = **未覆盖**，不得写成通过 |
| G1e 词面门 | 正则现场自 ci.yml 提取（`sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml \| head -1`）；四形态 + 正向对照 | 正则长度 **26**、分支 **8**（7 个竖线）；四形态（CI 原样 / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）全 **ZERO_HIT rc=1**；正向对照 rc=**0** 且命中探针行（探针植**非忽略路径**）、删后 `git status --porcelain` 逐字还原 |
| G1f 契约（**开工态无参 rc=1 为预期**） | 无参 `bash scripts/verify/mailbox-contract.sh`；在途 `... --open TASK-163 --baseline=1ff96e06` | 无参 rc=**1**（原文含「进行中（仅 spec）：TASK-163」「未声明（--open 缺 TASK-163），判据 A 失败」，末行 `判据 A=1 判据 B=0`）——本任务书自身落盘造成（脚本 L84–92）；在途 `--open` rc=**0**（`判据 A 两件套齐（含 1 个待办进行中）+ 判据 B 清单一致`）。**收口态无参必须 rc=0**（G10） |
| G1g 空白 | `git diff --check` | rc=**0**（`warning: LF will be replaced by CRLF` 属常规提示，不算命中） |
| G1h 主规格 | `wc -l`、CR/LF 计数、末 2 字节 `od`、`grep -c '^### Requirement:'` | **2806 / CR=2806 / LF=2806 / `0d 0a` / 121**；本任务全程零改动 |
| G1i PLAN | 同上 | **1222 行、CR=0、LF=1222**（纯 LF；追加后仍须 CR=0） |
| G1j delta 目录 | `ls -1d spec/changes/*/ \| grep -v archive \| wc -l`；`ls -1d spec/changes/archive/*/ \| wc -l` | 开工 **21 / 43**；收口 **22 / 43**（只新增自己的目录，不合并、不移名、不动 TASK-162 的目录） |
| G1k 磁盘 | `Get-PSDrive D` 或 `df -k /d` | **动态量**，门槛是 Free ≥ **100 GB**（不是与快照逐字节相等）。指导侧快照序列：234738380800（20:15）→ 234675306496（20:47）→ 执行侧 TASK-162 收口 232684974080。开工与收口各记一次并回报差值 |
| G1l 进程与容器 | `Get-CimInstance Win32_Process -Filter "Name='java.exe'"`；`docker ps -a` | 开工 java 进程数 = **0**（指导侧已停 TASK-162 遗留四服务）；五个演示容器 **Up (healthy)**、`task131-scratch-mysql` Up（不得触碰）、`sport-verify-postgis` 不起 |
| G2 生效配置 | verify.log 原文取证 Nacos 无 `verify-service.yml`；四 jar sha256；每轮进程 CommandLine 原文 | Nacos 侧无覆盖（TASK-144/162 均实测 `config data not exist`）；A/B 计数轮全程同一 verify jar；A 档命令行**无** `relay-interval-ms`、B 档**有** `=500` |
| G3 逐轮判据 | M1 / M4 / M5 | 逐轮原文 + 逐轮有效/无效结论 + drain.csv 路径 |
| G4 裁决门 | M2 / M3 + 三支归属 | 四个 slope 原值、两个比值、A2/A1 偏差、落哪一支的计算式 |
| G5 语义 | 语义门（含在 M4 内） | `retry_count>0`=0、耗尽增量=0、`uk_event_id` 零重复、markSent=cohort、零 `RECONSUME_LATER` |
| G6 资源 | `relay-round-sampler.sh` before/after | `timeout_total` 增量全 0；`pending`/`active` 峰值如实记录（TASK-162 曾见 pending 峰值 19 / active 10，只在高 QPS 的 A 轮出现，须照记）；`Com_select` B/A 比值 |
| G7 落地实现 | §8.1 / §8.2 + offline + 静态 + `git diff --check` | YAML numstat `N/0`、根键仍 **1**；新测试类 k 个用例；offline = **36/41/33/103/(120+k)/59/10** 全绿；静态 **≤867**；既有测试类 numstat 为空 |
| G8 C 确认轮 | §8.3 | 判据两项原文；不过则回滚证据（`cmp` rc=0 + offline 回 **120**） |
| G9 词面门收口态 | 三件套/报告/两件套入库后重跑 G1e | 四形态 ZERO_HIT + 正向对照；**任何入库文件不得内嵌正则字面量** |
| G10 契约收口 | 在途 `--open TASK-163 --baseline=1ff96e06` 与收口后无参 | 在途 rc 与过冲逐条归因（既有脏项 4 文件 + 非 ASCII 路径的 `core.quotepath` 转义 + 公共文件 `PLAN.md` 交叠是既知模式）；**收口后无参 rc=0** |
| G11 只改清单与受保护数字 | `git diff --name-only 1ff96e06..HEAD`；PLAN.md 内 token 计数 base vs HEAD | 恰为 §4 清单内路径；下列 token 的 **base 计数**（HEAD=`1ff96e06`）：`13.4`=5、`18.0`=6、`73.93`=6、`68.8`=2、`6315`=2、`1.8612`=2、`3.3066`=2、`5.7056`=2、`9.408`=2、`36525962432`=3、`36586847965`=2、`36438897772`=2、`36399582548`=1、`36098038547`=1、`2806`=6、`598`=1 —— HEAD 计数**不得减少**（可增加） |
| G12 提交 | 逐笔 `git show --check`、`git diff --cached --name-only` 原文 | 全 rc=0；清单原文随回复回传 |
| G13 外部门槛 | `--mode=online` / CI | 本任务自身提交**未跑** ⇒ 如实写「未达外部门槛（本次不 push，待下次授权由 CI 复验）」。上一批（8 笔，HEAD `ccd64f0`）已由 CI run `36586847965` 判 success，可引用但**不得**混成本轮的外部门槛 |

## 10. 提交结构（逐路径 add；提交信息走 UTF-8 文件 `-F`）

- **落地支（4 笔）**：C1 = `application.yml` + 新测试类；C2 = 报告 + JSON；C3 = 三件套；C4 = PLAN 追加 + `TASK-163/{spec.md,handoff.md}`。
- **未定/反证支（3 笔）**：C1 = 报告 + JSON；C2 = 三件套；C3 = PLAN + 两件套。
- 末笔允许 `--amend --no-edit` 补记收口实测（TASK-157~162 先例），**初版 SHA 必须在补记里披露**，终版以 `git log -1` 核对。提交信息用「主题行 + 空行 + 正文要点」形态（指导侧本轮曾因单行超长信息返工 amend）。
- 不 push、不建 PR、不 stash、不 `add -A`。

## 11. 交付物

1. `docs/perf/判别-outbox-relay-排空斜率.md`：一句裁决（落哪一支 + 四个 slope + 两个比值 + A2/A1 偏差）、**口径变更声明（§1 逐条）**、与 TASK-144/162 的关系（两次 UNDETERMINED 不翻案、数字不改写）、逐轮表（含丢弃预热轮与 QPS 披露）、逐轮 M1/M4/M5、drain.csv 路径与斜率计算式、线性度检查、资源与语义读数、落地/回滚证据、未覆盖与不得推出。
2. `docs/perf/data/exp-outbox-relay-drain-rate.json`：结构沿用 `exp-outbox-relay-interval-repeatable.json`（task/head/date/verdict/rounds/comparability→改名 `disclosure`/exitCodes/notCovered/notClaimed），新增 `slopeEstimator`（估计量定义原文）、`perRoundSlope`、`orderingControl`（M3）、`warmupRounds`、`landing`（含 C 轮与回滚）。
3. 三件套 `spec/changes/prove-verify-outbox-relay-drain-rate/`：`proposal.md`（Why / What Changes / Impact / 停止条件，含 §15 的 5 条预登记反例与 §1 口径变更声明）、`specs/sport-record-verify/spec-delta.md`（**纯 ADDED**，`## ADDED Requirements` + `### Requirement:` + `#### Scenario:` 形态，照 TASK-160/161/162 的 delta）、`tasks.json`（每步 `completed`/`passes`；**未落地时落地步必须 `completed=false`/`passes=false`，不得伪绿**——参照在途 `update-verify-outbox-relay-delay` 与 `prove-verify-outbox-relay-interval-repeatable` 的诚实写法）。
4. `work/mailbox/tasks/TASK-163/handoff.md`：结论、起点全 SHA、只改逐文件清单、G0–G13 原文与退出码、逐轮原始读数、未覆盖与不得推出。
5. `work/mailbox/PLAN.md` 末尾**纯追加**验收记录节（表格形态照 TASK-161/162 节；含「是否到达外部门槛」与「是否落地」栏）。

## 12. 未覆盖与不得推出（逐条写进报告与 handoff）

- 四服务局部（leaderboard/mapmatch/postgis 未起）⇒ 榜单消费与真实 R5 **未覆盖**，全部轮次在 R5 降级下跑，不得声称全链路。
- **不得声称任何并发收益**：`relay-send-concurrency` 全程 = 1；不得用 TASK-156 的 `S(N)` 或 TASK-161 的 `S_prod(N)` 换算本轮任何数字。
- 不得把本轮 slope 与 TASK-152 的 `18.0 ms/行`、TASK-156/161 的 `S(N)`/`S_prod(N)`、TASK-144/162 的 P50 并列成「优化前后」（不同实例/装配/窗/指标）。
- 排空斜率是**负载停止后**的净投递能力，**不等于**负载期的端到端延迟改善；本轮不把 slope 换算成 P50，也不得声称「P50 改善已被证明」（TASK-144/162 的 P50 观测仍未过门）。
- 不得外推到更高到达率、更长时间窗、生产多实例（本轮单实例、2000 行/轮、本窗口）。
- 落地后 500ms 使空扫 tick 频率约 10×，是**已披露的代价**（TASK-162 实测 `Com_select` B/A≈1.001，仍须本轮重测并披露）；不得只报收益。
- spotbugs/pmd **未覆盖**（被 checkstyle 阻断在前）；`--mode=online`/CI 未跑 ⇒ **未达外部门槛**。
- 容量派生结论（A 档上限 ≈15 行/s < 到达率 87~91 行/s）是 **Level B 算术**，不是本轮新测量，不得写成「实测无界增长」。

## 13. 停止条件（立即停、如实报、不落地）

起栈失败或容器起不来 / 健康检查不过 / Nacos 存在覆盖 `verify.outbox` 的配置 / 开工发现 java 进程非 0 且停不掉 / 磁盘 Free < 100 GB / 演示库出现耗尽行增长 / 替换轮用尽仍不足 4 个有效计数轮 / 负载出现 429 或 errors>0 / relay 出现失败行或耗尽行 / drain.csv 缺失或采样断裂 / 日志或配对样本缺失 / 任何与 G1 期望值不符的读数。**不为凑满轮次强行继续，不为救结论放宽门槛。**

## 14. 回传格式（给指导侧，照此结构）

1. 一句裁决：落哪一支 + `slope(A1)/slope(B1)/slope(A2)/slope(B2)` + 两个比值 + A2/A1 偏差 + 是否落地。
2. 逐轮表（含丢弃预热轮）：label / cell / 注入 / QPS / 墙钟 / ok / errors / 429 / P_peak / t_peak→t_zero / slope / slope_half / M1 空档数与中位 / M4 逐项 / 排空耗时。
3. G0–G13 逐项：命令原文 + 退出码 + 关键读数（尤其 G1c 七模块数字串、G1d 违规数、G1e 四形态与正向对照、G1f 两形态、G7 offline 数字串与 k、G8 C 轮判据、G10 收口无参 rc）。
4. 提交清单：每笔 SHA + `git diff --cached --name-only` 原文 + `git show --check` rc；`git diff --shortstat 1ff96e06..HEAD`。
5. 只改清单一致性 + 受保护数字 base/HEAD 计数表（16 个 token 逐个）。
6. 未覆盖与不得推出（照 §12 逐条确认）。
7. 异常与自踩红线披露（编译失败、伪命中、命令行中文、工具报错、进程/端口残留等，一律如实，附日志路径）。

## 15. 预登记反例（5 条，必须逐字进 proposal 并逐条说明触碰情况）+ 指导侧教训

1. **「P50 已经两次实测降 65%，直接落地就行」**——不成立：那两次都判 UNDETERMINED，其 P50 是**未过门观测**；本轮换新指标不是为了让旧观测生效，而是换一个不受提交 QPS 漂移污染的度量重新取证。若本轮 M1–M5 不过，仍不落地。
2. **「换指标＝放宽门槛」**——不成立，但必须自证：新门槛 M2 ≥1.5 由结构模型 `(5+T)/(0.5+T)` 先验推导（T∈[1.3,2.5] ⇒ 2.5~3.5），比结构预测下沿还保守；且新增了 TASK-144/162 都没有的 **M3 排序控制门**。报告必须同时披露 QPS（不设门但不得隐藏），以便复核「换指标是否掩盖了不可比」。
3. **「A2 比 A1 快一点是系统预热，正常」**——正是 M3 要抓的：若 A2 明显快于 A1（>20%），说明「越跑越快」足以解释部分差异，则 B 的优势不能被归因到 interval ⇒ 落**反证支**，不落地。
4. **「slope 提升 2~3 倍就等于用户延迟降 65%」**——不成立：slope 是负载停止后的排空能力，端到端 P50 还取决于到达过程与队列长度；两者不得互相换算（§12）。
5. **「资源没报红就说明 500ms 无代价」**——不成立：空扫 tick 频率约 10× 是结构性代价，必须与收益并列披露；`Com_select` 比值缺失即视为披露不完整，指导侧会退回。

**指导侧自记（本轮教训，已内建为条款）**：① TASK-162 任务书把「停四服务」只写进落地支（§7.3），未定支无收尾条 ⇒ 执行侧留着四个 java 进程，指导侧复跑 offline 时 `mvn clean` 删不掉被占用的 jar 而 BUILD FAILURE（原文 `Failed to clean project: Failed to delete ...gateway-service\target\...`）。本轮 §5 第 8 条为**两支共用**的强制收尾条。② 任务书自身落盘会让契约门无参形态变红，故 G1f 的期望值**必须在 spec.md 写完之后**复跑取（TASK-162 曾因此误报「不符」，责任在指导侧）。③ 写进任务书的一切「指导侧亲跑值」都必须是**当轮**实测，不得沿用上一轮快照（磁盘、进程、容器状态都会漂移）。
