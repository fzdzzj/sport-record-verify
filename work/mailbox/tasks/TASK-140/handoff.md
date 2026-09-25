# TASK-140 回传：提交链路 DB 等待归因与重复负载核验（只测量不优化）

## 回传概要

- **开工 HEAD**：`eb624131674c2c0bff7bcd9257ffd4ce83c707fe`（与任务书一致；既有脏项
  archive 移名 / `.codex/` / `.trae/` / `add-verify-degrade-status-index` 未触碰）。
- **提交**：两笔本地提交——`b74f4fd2ca1efe268585c08e71255d3c89cd429b`
  （`perf(record): 提交链路 DB 等待归因测量与同负载复测`，业务+测试+交付物+规范三件套，
  18 文件）；台账两件套、TASK-139 handoff 口径订正与本记录为收口提交
  （`docs(mailbox): TASK-140 提交绑定与验收记录`，
  4 文件；哈希由任务回传承载，台账无法承载自身提交哈希）。未 push、未建 PR、未用 git stash。
- **指标门槛（只读实测）**：`/actuator/metrics` 名称 49 项中 hikari/datasource/jdbc/pool
  计量项 0；`/actuator/prometheus` 872 行 0 命中；直查 `hikaricp.connections.pending` 404。
  内层 Hikari 由 ShardingSphere 反射创建，Spring 指标绑定看不到——**现有指标不足**。
- **插桩**：默认关闭最小计时——`TimingHikariDataSource`（普通 DataSource 包装内持真
  Hikari 池，物理获取点计时）+ 池元数据 TypedSPI 注册 + `SubmitTxTiming` connWait 桥接
  与驻留样本有界化（cap=4096，修复 TASK-139 开启态无界保留）。 Maven 门槛
  `bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` **rc=0**，
  record-service **95/0/0/0**（86 → +9 只增不减）；package 复跑 BUILD SUCCESS。
- **三轮同负载（计时关，同 jar、混合运行条件）**：dbfoot（TASK-139）/ rpt1 / rpt2——
  QPS 121.4~166.8、P50 580.7~711.45ms、P95 796.4~2169.0ms、P99 1036.3~2794.5ms，全部
  2000/2000、0 限流 0 错误、退出码均 0。TASK-139 单次前后差（QPS +1.9% / P50 −8.5%）
  落在该混合运行条件观测区间内；P95 +12.1% 保持观察状态。
- **connwait 轮（计时开，独立组）**：`load 100 2000 connwait` 一次成功（QPS 148.5 /
  P50 591.8ms / P95 1092.7ms），**请求级物理连接获取等待直接测得：connWait P50 527.1ms /
  P95 970.9ms（n=2000，含 Hikari 池等待）**；commit 段 24.5ms 为提交区间（fsync 份额
  未分离测得）；select−connWait ≈4.7ms 为配对推导值。
- **口径订正**：TASK-139 复测报告/摘要 JSON/handoff 已追加订正（select 混合段、
  commit 区间非 fsync 单项、分段 P50 不可相加、单次前后不成因果），原始数字不动；
  TASK-138 归因文档补交叉引用。
- **契约**：收口提交后无参数 `bash scripts/verify/mailbox-contract.sh` **rc=0**
  （判据 A 两件套齐全；TASK-140 足迹不在工作树视为已收口）。git 层逐字比对 22=22；
  工具提取层对中文文件名交付物 3 项已知盲区（提取词元 `-db-wait-evidence.md` /
  `-submit-tx.md` / `-HEAD.md`，TASK-138/139 同类先例），以 git 层比对为准。

## 实际改动清单

record-service/src/main/java/com/sportverify/record/config/TimingHikariDataSource.java
record-service/src/main/java/com/sportverify/record/config/TimingHikariDataSourcePoolMetaData.java
record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
record-service/src/main/java/com/sportverify/record/service/SubmitTxTiming.java
record-service/src/main/resources/META-INF/services/org.apache.shardingsphere.infra.datasource.pool.metadata.DataSourcePoolMetaData
record-service/src/main/resources/sharding.yaml
record-service/src/test/java/com/sportverify/record/config/MainShardingYamlDefaultsTest.java
record-service/src/test/java/com/sportverify/record/config/TimingHikariDataSourceWiringTest.java
record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
record-service/src/test/java/com/sportverify/record/service/SubmitTxTimingTest.java
docs/perf/data/attr-submit-db-wait.json
docs/perf/data/attr-submit-tx-split.json
docs/perf/复测-db-wait-evidence.md
docs/perf/复测-submit-tx.md
docs/perf/归因-HEAD.md
spec/changes/measure-submit-db-wait-evidence/proposal.md
spec/changes/measure-submit-db-wait-evidence/specs/sport-record-verify/spec-delta.md
spec/changes/measure-submit-db-wait-evidence/tasks.json
work/mailbox/tasks/TASK-139/handoff.md
work/mailbox/tasks/TASK-140/spec.md
work/mailbox/tasks/TASK-140/handoff.md
work/mailbox/PLAN.md

## 清单说明

第 1、2 项为内层池计时包装类与池元数据 SPI 注册文件本体（纯新增类）；第 3、4 项为
submit 武装/解除接线与 SubmitTxTiming 有界化+connWait 桥接；第 5 项为 ShardingSphere
公共 SPI 注册文件（pool-hikari 模块同款机制）；第 6 项为 sharding.yaml 池类指向
（sql-show、池大小等参数未动）；第 7~10 项为测试（SubmitTxTimingTest 5 条、SPI 接线
判别 2 条、submit 接线判别 1 条、主 yaml 池类判别 1 条，均新增，无删除）；第 11~15 项
为本任务交付物与 TASK-139/138 口径订正；第 16~18 项为规范来源三件套（tasks.json 勾选
回填是其中唯一实质修改）；第 19 项为 TASK-139 handoff 的口径订正追加（本任务门槛 1，
追加节不改动原始数字）；第 20~22 项为本任务台账两件套与 PLAN 验收记录。清单之外无
任何文件进入提交；归档移名（wire-verify-outbox / adopt-native-mq-retry 的删除与未跟踪
态）、`.codex/`、`.trae/`、`spec/changes/add-verify-degrade-status-index/` 均未触碰、
未提交。

git 层逐字比对：`git -c core.quotepath=false diff --name-only --diff-filter=ACMR
eb62413`（台账文件暂存后）与上列 22 项排序后逐字一致（22=22，比对输出见契约节）。
契约工具提取层对第 13~15 项（文件名含非 ASCII 字节）存在已知盲区——提取正则只收
ASCII 路径字符，该三行被提取为词元 `-db-wait-evidence.md`、`-submit-tx.md`、`-HEAD.md`
与真实路径对不上（TASK-138 的归因报告、TASK-139 的复测报告同名文件先例）；真实改动
集一致性以 git 层比对为准。

## 判别式与实跑记录

1. **指标门槛（阶段 1，只读）**：四服务起栈后实测，命令与结果见概要与
   `docs/perf/复测-db-wait-evidence.md` 门槛节。结论：现有指标不能回答请求级池等待，
   具备「指标缺失且位置可证」前提。
2. **插桩（阶段 2）**：机制与归因边界见报告「最小插桩」节。期间两次起栈失败如实登记
   （均无仓库数据破坏）：第一次为 ShardingSphere 5.4.1 仅对字面量 HikariDataSource 剥离
   `dataSourceClassName` 键→未注册元数据时 `url` 归一化 NPE；第二次为继承 HikariDataSource
   方案撞上 HikariCP 5.0.1 委托分支对已移除属性 `netTimeoutForStreamingResults` 抛错——
   据此改为普通 DataSource 包装内持真 Hikari 池的结构并注册公共 SPI；第三次为
   StorageUnit 从实例 getter 反推 username/password，包装类缺 `getUsername/getPassword`
   NPE（独立复现脚本先证，起栈后确证 200）。全链用离线独立复现脚本定位根因，未改任何
   业务语义。
3. **单测门槛（阶段 3）**：唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline
   --pl record-service test`：一次编译红（SubmitTxTiming 重写漏 `List` 导入，rc=1，补
   导入后重跑）→ **rc=0**，95/0/0/0；`--pl record-service package` 复跑 BUILD SUCCESS
   （起栈产物）。无行为改动故无红绿判别式要求，新增用例全绿即门槛。
4. **容量门槛（阶段 4）**：每轮 2010×300 点，三轮预计 +1.809M 轨迹点；实测
   sport_record 26352→32382（+6030=3×2010，逐轮一致），track_point_0 488700→601400
   附近（每轮 +37.5k≈603k/16）；磁盘 D: 可用 223G 三轮前后无实质变化。未清库、未动
   Docker 卷。
5. **重复负载（阶段 5）**：计时关 rpt1/rpt2（旧 jar 语义与 dbfoot 同构：池 10 未注入、
   计时关、sql-show 关、SUBMIT_TX_TIMING 行数 0 留档于 `rpt-record-timingoff.log`），
   rpt1→rpt2 间静置 75s 待校验积压排空；connwait 轮为计时开独立组（record 单独带
   `--record.submit.tx-timing-enabled=true` 与 `MYSQL_PORT=3307` 重启，重启用时 30s 后
   health 200）。三轮退出码均 0，无失败重试。
6. **词面自检**：CI 同款 `git grep -n -I -iE -f <词表文件> --untracked`（LC_ALL=C，模式
   经 UTF-8 文件承载、命令行无中文；排除 archive/docs 内部目录/CI 工作流三个
   pathspec）对本任务全部交付物所在路径扫描 **0 命中**；台账不引用禁用词正则原文。
7. **旧数字边界**：dbfoot 数字为 TASK-139 原始记录只读引用；TASK-138 `head-c100-*` 与
   TASK-139 `split-c100-*` 原始文件未覆盖；旧环境（2026-09-12）数字未参与任何比较。

## 环境与操作注记（如实登记，均无仓库改动）

- 中间件容器（mysql:3307 / nacos / redis / rocketmq-namesrv / rocketmq-broker）本任务
  随 `docker compose up -d` 启动（任务开工时容器未在位）；postgis/grafana 未启动
  （mapmatch 缺席口径延续）。
- 插桩根因定位使用的独立复现脚本与 classpath 文件均在本机临时目录，不入库、用后清理。
- rpt1 轮 P95 2169ms 抬高与 rpt2 全暖 796ms 的解释（容器/JVM 冷启动）记录为观察条件，
  未作为因果结论写任何改善百分比。

## 契约核验（实测，D:\git\Git\bin\bash.exe 现跑）

- **收口提交后无参数口径（硬门槛）**：`bash scripts/verify/mailbox-contract.sh` →
  **rc=0**（判据 A 两件套齐全；TASK-140 足迹不在工作树、视为已收口）。
- **git 层逐字比对**：台账文件暂存后 `git -c core.quotepath=false diff --name-only --diff-filter=ACMR eb62413`
  输出与「实际改动清单」22 项排序后 `diff` 为空（22=22）。
- 工具提取层盲区：清单第 13~15 项文件名含非 ASCII 字节，契约提取正则只收 ASCII 路径
  字符（TASK-138/139 先例；TASK-114 曾以扩白名单收口）；本任务无契约脚本改动权，
  不代为修工具。

## 未覆盖与边界

- 请求级 fsync 耗时：**未分离测得**（提交段为 `beforeCommit→afterCommit` 区间，DB 侧
  仅聚合计数器）；connWait 中池等待与建连不区分；单条 SQL 纯 JDBC 执行与
  ShardingSphere 解析份额未插桩（超出最小低侵入约束，按任务书停止并登记）。
- 池等待「占比」数字（92%/99% 类）不作为通用结论引用，仅配对人群推导值。
- quality 验收集未跑（mapmatch 缺席，TASK-138 先例）；500/1000 档按任务书禁止未跑。
- 计时开轮与计时关轮不混组统计；dbfoot 数字为只读引用未重跑。

## 提交

- `b74f4fd2ca1efe268585c08e71255d3c89cd429b` `perf(record): 提交链路 DB 等待归因测量
  与同负载复测`（18 文件：服务 + 插桩 + 配置 + 测试 ×4 + 交付物 ×5 + 三件套 ×3；中文
  文件名交付物经 `git add docs/perf` 整目录加入并以 `git status` 核对）。
- 台账收口提交 `docs(mailbox): TASK-140 提交绑定与验收记录`（4 文件：TASK-140 台账
  两件套 + TASK-139 handoff 订正 + PLAN，`git commit -F`
  UTF-8 文件；哈希由任务回传承载——台账无法承载自身提交哈希）。
- 只 stage 清单内路径（未用 `git add -A`）；不 push、不建 PR；未用 git stash；开工前
  既有脏项（归档移名 / .codex / .trae / add-verify-degrade-status-index）保持原状。

## 收口后修订（追加，2026-09-25，同一作业；业务提交 `1c032bdf28fb5a19ad9cb65473219f476cd65b7e`，5 文件）

- **关闭路径修复**：`TimingHikariDataSource` 补实现 `AutoCloseable`——ShardingSphere
  `DataSourcePoolDestroyer`（字节码核实）仅按 `instanceof AutoCloseable` 调用 close，
  原实现（仅 DataSource）在优雅关闭路径不被识别，内层 Hikari 池泄漏。新增判别测试：
  AutoCloseable 识别、**外层关闭必须关闭内层真实池**（buildPool 反射构建 + setField
  注入 + close 后 `isClosed()` 断言）、建池前 close 为 no-op；`minimumIdle` 初始值
  -1 → 1（-1 被 HikariCP 5.x setter 拒绝，红测先暴露，生产路径因元数据必注入未触发）。
- **YAML 属性转发核对、注释订正**：直达内层池 jdbcUrl/username/password/maximumPoolSize/
  connectionTimeout/initializationFailTimeout；idleTimeout/maxLifetime/minimumIdle/
  keepaliveTime 由 ShardingSphere 池元数据默认值注入后同样转发；driverClassName 被
  ShardingSphere 反射跳过（驱动由 URL 推断，同直接使用 Hikari）；dataSourceClassName
  键无 setter 被静默跳过（`noSetDataSourceClassName` 结构守卫测试锁定）。sharding.yaml
  原「Hikari 子类 / setter 全部继承」注释按实际实现订正；转发逐项由
  `yamlProperties_forwardToInnerPoolConfig` 断言。
- **证据表述订正**：逐请求配对样本未保留（聚合 snapshot 只存各段分位），删除「配对
  中位差 ≈4.7ms 推导值」与「≈99.1%」比较口径——两条独立 P50 之差不是单请求量；
  三轮范围标注为**混合运行条件**观测区间，原始数字全部保留（报告 + 摘要 JSON 同步）。
- **门槛实跑**：`mvn-verify.sh --mode=offline --pl record-service test` rc=0，
  record **100/0/0/0**（95 → +5）；package BUILD SUCCESS；启动验证按回传先停服务再
  起栈（四服务 health 200，record 8082 200）后停栈。不重复跑大负载，未 push、未建 PR。
- 本修订只改清单（相对 `184e61f`，7 项）：TimingHikariDataSource.java ·
  TimingHikariDataSourceWiringTest.java · sharding.yaml · 复测-db-wait-evidence.md ·
  attr-submit-db-wait.json · 本 handoff（本节追加）· PLAN.md。

## 收口后修订二（追加，2026-09-26，同一作业；业务提交 `1c6f75ff7fc5ab1d06e7e5fe434c734a5ffd289a`，6 文件）

- **关闭状态判别补反例**：原 close() 在内层池未创建时仅返回，后续 getConnection() 会
  经懒初始化重新建池——优雅关闭后连接池可被重开（对照本地 HikariCP 5.0.1 字节码：
  HikariDataSource.close() 未建池也标记关闭、之后 getConnection() 拒绝）。修复：
  包装类增加关闭状态，close() 无论是否已建池都标记；关闭后取连接抛 SQLException 且
  懒初始化被拒绝；暴露 `isClosed()`。判别测试
  `close_beforeInnerPoolCreated_marksClosed_andRejectsConnection` 覆盖「关闭→标记→
  取连接拒绝→仍不建池」全链，外层关闭测试补关闭后取连接拒绝断言。
- **口径统一订正**：核对实跑记录确认 dbfoot / rpt1 / rpt2 三轮为**同 jar**（均为
  TASK-139 产物、无包装类；包装类首次随计时开的 connwait 轮进入），但运行条件混合
  （新鲜度三种状态）。全文订正：不再称「同版本重复跑次」「轮间波动带」，改为
  「同负载重复跑次（同 jar、混合运行条件）的观测区间」，差异不单独归因于新鲜度或
  任何单一条件（复测报告结论与重复节、复测-submit-tx.md 订正节、摘要 JSON
  revision20260926、本概要、PLAN 记录同步；原始数字全部保留）。
- **门槛实跑**：`mvn-verify.sh --mode=offline --pl record-service test` rc=0，
  record **100/0/0/0**（用例数不变：替换 1 条 no-op 判别为关闭状态全链判别 + 外层
  关闭测试补断言）。不跑大负载、不起栈（关闭语义由单测覆盖）、不 push、不建 PR。
- 本修订只改清单（相对 `9f748521402ce7b1fc5859a4aa8a328dac1ad4f7`，8 项）：
  TimingHikariDataSource.java · TimingHikariDataSourceWiringTest.java · sharding.yaml ·
  复测-db-wait-evidence.md · 复测-submit-tx.md · attr-submit-db-wait.json ·
  本 handoff（本节追加）· PLAN.md。
