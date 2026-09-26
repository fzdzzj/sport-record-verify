# TASK-141 回传：池生命周期并发前置修复与 MYSQL_POOL_SIZE 单因素对照

## 回传概要

- **开工 HEAD**：`8380e11d0ffde4342b1fdc9ff828adbbe191d197`（与任务书一致；既有脏项
  archive 移名 / `.codex/` / `.trae/` / `add-verify-degrade-status-index` 未触碰，
  未 stash、未 add -A）。
- **提交**：两笔本地提交——业务+测试+交付物+规范三件套为本地提交
  `27077a43cc1d69a8dd7a23c8a85fb8d0aeb9965d`
  （`perf(record): 池包装关闭-建池并发协调修复与池容量单因素对照`，7 文件）；台账
  两件套与本记录为收口提交（`docs(mailbox): TASK-141 提交绑定与验收记录`，3 文件；
  台账提交哈希由任务回传承载，台账无法承载自身提交哈希）。未 push、未建 PR。
- **结论（三选一）：不推荐**——本机、本负载（c100×2000）、诊断开启条件下，候选值
  `MYSQL_POOL_SIZE=20` 无可复现收益，且两轮 B 有一致的单事务 DB 段变慢与
  `Threads_running` 上升。四轮原始数字与判定对照见 `docs/perf/复测-连接池容量对照.md`
  与 `docs/perf/data/attr-submit-pool-capacity.json`。
- **安全前置（红→最小修复→绿）**：未修复实现上两条确定性交错判别失败
  （close 完成后包装类仍建出无人关闭的内层池：无参重载 HikariPool-4、凭据重载
  HikariPool-5，同轮其余 8 用例通过；交错由主线程占住建池管程 + 线程状态条件等待构造，
  不靠睡眠）。最小修复：`innerPool()` 建池临界区内复查 `closed`、`close()` 在同一
  `synchronized(this)` 内读内层池引用、锁外关池；稳态取连接不持包装类锁；两重载与
  重复 close 幂等保留。修复后该测试类 10/10（+3 只增不减）。
- **Maven 门槛**：`bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service
  test` **rc=0**（**103/0/0/0**，100 → +3 只增不减）；同入口 `package` **rc=0**。
  修复通过后才启动压测。
- **冻结与门槛**：四轮同一 jar（SHA-256
  `197978fcbe52ebfad36aba9f76972bdb7ef169f5840cc29afedd55a292f1f7be`）；磁盘 D: 223G
  余量、C: 97G；MySQL `max_connections=151`、开工 `Threads_connected=1`；开工积压
  SUBMITTED/VERIFYING=0、outbox PENDING=0；中间件四容器 healthy；环境指纹
  `docs/perf/data/raw/env-fingerprint-task141.txt`（JDK 21.0.9 / MySQL 8.0.46 /
  nacos v2.3.2 / redis 7.2 / rocketmq 5.2.0）。
- **四轮程序（同置）**：record 每轮重启（`MYSQL_PORT=3307` +
  `--record.submit.tx-timing-enabled=true` + 每轮独立 GC 日志），仅轮换
  `MYSQL_POOL_SIZE`；health 200 + 网关路由探测通过后跑
  `bash scripts/perf/run-perf.sh load 100 2000 <label>`；负载中每 2s 采样 MySQL 线程/
  record_db 连接数/record 进程 CPU·内存；轮后留存末条 `SUBMIT_TX_TIMING`，排空到 0
  （5s 轮询 23/23/23/24 次）并复查四服务健康 200 才进下一轮。gateway/user/verify 四轮
  共用不重启（user/verify 以 `MYSQL_PORT=3307` 启动——首启漏带该变量致健康检查 30s
  超时，属实验前环境修整，发生在任何负载之前）。
- **池参数生效佐证**：负载中 `record_db` processlist 连接数 10/20/20/10；
  `Threads_connected` 峰值 31/41/41/31（差值恰为 10）。
- **四轮结果（全部 2000/0/0，load 退出码均 0，一次成功无重试）**：
  pool10-r1 QPS 128.71 / P50 709.39 / connWait P50 632.0ms；pool20-r1 QPS 110.63 /
  P50 850.33 / connWait P50 669.0ms；pool20-r2 QPS 163.70 / P50 543.25 / connWait P50
  433.9ms；pool10-r2 QPS 129.48 / P50 691.44 / connWait P50 612.7ms。GC 四轮
  147~167 次总 637~665ms、CPU 窗口用量 66.0~73.5s、无一致方向差异。
- **判定对照**：「两次候选轮次方向一致且可区分」不成立（两轮 B 跨在 A 臂两侧）→
  不得称值得进一步验证；「两臂重叠 + 运行条件漂移」成立 → 在不确定/不推荐之间取
  **不推荐**，依据是无一致收益之外还有同方向代价信号（两轮 B 的 commit/trackWrite/
  insertMain P50 全部变慢 1.4~2 倍、`Threads_running` 峰值 28/18 对 14/13）与 B 臂
  同条件复现失败（QPS 跨 48%）；反例 pool20-r2（单轮全面占优）被 pool20-r1（同池全面
  变差）同池重复否定。
- **口径**：connWait 含池内等待与可能建连、不区分二者，不称纯池排队；四轮诊断开启，
  数字不得当作生产默认关闭状态的收益；TASK-140 的 527.1ms 标注为旧诊断轮、非本次
  同条件基线；未做独立 P50 相减。
- **收尾**：四轮后停全部四个 Java 服务；诊断仅以启动参数存在过、未写入配置；
  池默认 `${MYSQL_POOL_SIZE:10}` 未动；未清库（主表 32382→40422、track_point 约
  +241 万行）。逐轮驱动脚本为仓库外临时文件，未入库。

## 实际改动清单

record-service/src/main/java/com/sportverify/record/config/TimingHikariDataSource.java
record-service/src/test/java/com/sportverify/record/config/TimingHikariDataSourceWiringTest.java
docs/perf/data/attr-submit-pool-capacity.json
docs/perf/复测-连接池容量对照.md
spec/changes/measure-submit-pool-capacity/proposal.md
spec/changes/measure-submit-pool-capacity/specs/sport-record-verify/spec-delta.md
spec/changes/measure-submit-pool-capacity/tasks.json
work/mailbox/tasks/TASK-141/spec.md
work/mailbox/tasks/TASK-141/handoff.md
work/mailbox/PLAN.md

（前 7 项为业务提交；后 3 项为台账收口提交。proposal.md / spec-delta.md 为开工前
既有未跟踪文件，按提案随本变更一并提交，内容未改。）

## 未覆盖/缺口

- **pool10-r1 进程 CPU/内存未采集**：采样命令 PowerShell 字符串插值缺陷（该轮 proc
  tsv 只记录了属性名）；该轮请求/connWait/MySQL/GC 指标完整，A 臂 CPU/内存由
  pool10-r2 覆盖。未为补采样重跑负载（避免超四次预算）。
- **B 臂轮间 48% QPS 波动来源不可分离**：缓冲池新鲜度/后台 relay/JIT 等候选未控制；
  A 臂未现同量级波动，不归因为一般噪声，只如实登记。
- gateway/user/verify JVM 四轮共用（非全新态）；单机单负载；质量验收集（quality）非
  本任务门槛未跑；`--mode=online` 与 CI 未跑。
