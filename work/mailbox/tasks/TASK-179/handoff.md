# TASK-179 handoff：judge-like-reconcile-pipeline-semantics 对账 pipeline 化语义判别

> 状态：**已收口（本地六笔提交，未 push；结构见 §9）**。§0 开工规程与基线读数；§1 偏差登记；§2 一句话结论；§3 足迹清单；§4 判别证据；§5 逐门实测；§6 机制与口径；§7 token；§8 未覆盖项；§9 提交；§10 通道还原。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。
> 原始读数落 `docs/perf/data/raw/task179/<run>/`（gitignore，未入库）：`run1-g0-baseline`（开工门禁）、`run2-j1j2-measurement`（判别测量轮，历次缺陷另存 `attempt*/` 子目录）、`run3-precommit-c01`（预提交门禁与暂存核对）、`run4-closure-evidence`（收口 IT 复跑）、`run5-closure-gates`（收口 mvn 门禁）。仓库根 `task179-*.tmp` 为临时脚本，用毕删除。

## 0. 开工规程与基线读数逐位核验（Level A）

- 开工 HEAD（派发笔）：`0b5175bf405cb5d70eeb9b17f44bd21affeb465a`，`git log -1 --format=%s 0b5175bf405cb5d70eeb9b17f44bd21affeb465a` = `docs(spec): 派发 TASK-179 对账pipeline化语义判别提案与任务书`；
- `git rev-list --left-right --count origin/main...main` = **`0 1`**；`git status --porcelain` 为空——零残留核验通过；
- 离线基线：`bash scripts/verify/mvn-verify.sh --mode=offline test` rc=**0**，分模块 `36/41/33/127/144/59/10`、合计 **450**，Failures/Errors/Skipped 全 0，BUILD SUCCESS（`run1-g0-baseline/g0-offline-test-mvn.log`）；
- 静态基线：`bash scripts/verify/mvn-verify.sh --static=record-service` rc=**1**、`You have 814 Checkstyle violations`（严格 814 持平口径）；
- 词面门：正则自 `.github/workflows/ci.yml` 现场提取（`PATTERN_BYTES=58`），四形态（default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）`git grep --untracked -n -I -iE` 全 **ZERO_HIT rc=1**，正向探针（内容由 pattern 首个候选派生，脚本内零中文字面量）四形态全 **HIT rc=0**，探针删除后 `PROBE_GONE=yes` 并复扫 ZERO_HIT；
- 受保护 token：29 项以 `grep -cF` 逐行命中 PLAN.md，开工实测逐项 = 任务书 §5 基线 **+1**（TASK-178 C-02/C-03 已追加台账所致，任务书 §5 已预告），`TOKEN_VIOLATIONS=0`（`run1-g0-baseline/task179-token-readings.txt`）；
- 在途契约门：`bash scripts/verify/mailbox-contract.sh --open TASK-179 --baseline=0b5175bf405cb5d70eeb9b17f44bd21affeb465a` 开工态 rc=**0**；
- `tasks.json` 语法 rc=**0**；`git diff --check` 与 `git diff --cached --check` 均 rc=**0**；
- **环境核验**：scratch MySQL 可达（容器 `sport-verify-mysql`，宿主 **3307**，服务端 **8.0.46**，`SHOW DATABASES` 含演示 `record_db` 但本轮只在 `task179_it` 建表）；受控真实 Redis 可达（宿主 **16379** 通道，容器 `sport-verify-redis`，redis **7.2.16**，`run_id 3c7000f34fe52b6fc67c8d4754b09b4ad1a1ebbb`，容器内外两侧同值互证）。宿主 6379 存在竞争，处置见 §1.2。两路均可达 ⇒ **无 UNDETERMINED 分支**。

## 1. 偏差登记

### 1.1 任务书 §1.2 的「Lettuce 底层」与本仓装配不符（前提修正，非判别设计漂移）

1. 普查事实：`record-service/pom.xml` 引 `org.redisson:redisson-spring-boot-starter`（父 pom 定版 3.27.2）；该 starter 的 POM 对 `spring-boot-starter-data-redis` **显式排除 `io.lettuce:lettuce-core` 与 `redis.clients:jedis`**；本地仓库 `.m2-repo` 内不存在任何 lettuce-core 构件；starter 只暴露 `org.redisson.spring.starter.RedissonAutoConfigurationV2` 一个自动配置类。IT 侧一手证据：`run2-j1j2-measurement/task179-run-meta.json` 的 `redisConnectionFactory = org.redisson.spring.data.connection.RedissonConnectionFactory`。
2. 影响：任务书 §1.2「StringRedisTemplate（Lettuce 底层）」与 §2.2「Lettuce pipeline 上限内」「Lettuce 集群/LRU 无关」两处口径对本仓不适用。
3. 处置：按 §2.2 硬要求「同数据**同装配**」，候选在与生产相同的 `RedissonConnectionFactory` 上实现 `executePipelined`（任务书点名的 API 未变），分批 500/1000 两档预注册参数照测。**未改预注册判据、未缩维度**；结论适用面收窄为「Redisson 连接工厂下的 `executePipelined`」，已在报告 §14 第 2 条登记为不得外推项。

### 1.2 宿主 6379 竞争与 16379 受控通道（沿 TASK-178 先例，用毕已还原）

1. 探针一手读数：宿主 `127.0.0.1:6379` 返回 `+PONG` 后 `INFO server` 被 `Connection reset by peer`，`netstat` 显示 6379 由 PID 6480（原生 `redis-server.exe`）与 22980（容器代理）同时监听；`tasklist` 命中 `redis-server.exe` 1 个进程 ⇒ 直连 6379 会测在错误实例上（任务书 §0.6 禁止的伪造读数风险）。
2. 处置：以仓库根临时 `task179-compose-override.tmp`（`services.redis.ports: ["16379:6379"]`）对**既有**容器追加宿主端口，`docker compose -f docker-compose.yml -f <override> up -d redis` rc=0；未新建容器、未停用户进程、未改基线 compose 文件本体。
3. 归属互证：容器内 `redis-cli INFO server` 与宿主 16379 探针同为 `run_id 3c7000f3…`、`redis_version 7.2.16`、容器内 `tcp_port 6379`；IT 侧 `redisRunId` 同值。注意本轮容器因追加映射被重建，`run_id` 相对 TASK-178 的 `dcbb35cf…` 已变化（读数按实测登记，不沿用历史值）。
4. 生命周期：测量轮与收口复跑结束后按基线 compose 还原映射并删除 override 文件，还原读数见 §10。

### 1.3 IT 无法经 harness `--it` 承载（Notice 延续）+ 直跑例外

`scripts/verify/mvn-verify.sh` 的 `--it` 分支仍硬编码 `IT_MODULE=leaderboard-service` 与三个 leaderboard IT 类名，record-service 的新 IT 无法承载。按任务书 §0.2 与 TASK-177/178 Notice 先例，以镜像 harness offline 纪律的完整命令直跑（报告 §12），完整命令 + rc 入报告与本文件 §5。本任务未改 `scripts/`。

### 1.4 判别式自身的六处缺陷（测量面，非被测语义异常；原始读数均未抹除）

IT 共跑 7 轮：第 1–5 轮各暴露一处测量工具缺陷，第 6 轮判别全绿，第 7 轮（收口复跑）另修一处收尾缺陷。逐条如下——判红对象始终是本侧工具，未出现被测语义异常。

1. **scratch 建表缺 05 号迁移列**（`attempt1-sqlsyntax-defect/`）：首轮 9 项全红，根因 `Unknown column 'archived' in 'field list'`——并发注入走生产 `like()`，其 `requireRecord()` 经 `selectById` 取全列清单，而 scratch 库仅按 `sql/02-record-db.sql` 重建；`archived` 列在 `sql/05-track-point-archive-shards.sql` 才补上。处置：scratch 建表后追加同一列定义（不改 `src/main`、不改读数口径）。同时修正本侧一处 `sport_record` 列清单反引号笔误。**顺带登记一个仓库事实**：`02` 号建表脚本与实体列清单存在滞后，凡需在 scratch 库调用 `SportRecordMapper.selectById` 的测试都要补该列（供指导侧评估是否新增一致性门槛）。
2. **候选暂停钩子挂错位置**（`attempt2-partial-readings/`）：(d) 初版把候选的暂停挂在仪表化模板的 `chunk.commit` 事件上，而候选经 `RedisConnection` 直发、不走模板方法 ⇒ 暂停未生效，第二把 `tryLock` 在 **4.185ms** 即成功（假读数、判红）。改挂到候选自身的批次提交后探针位复测为 **3005.895ms 被拒**，与基线 3011.793ms 同量级。
3. **(b) 聚合丢失率是伪结论**（`attempt3-baggregate-defect/`）：初版按「总丢失/总注入」对照，得候选 0.941 vs 基线 0.221，看似候选劣化。核查原始读数后定位为口径错配：候选整轮更快，绝大多数注入落在「该 record 尚未被对账触碰」一类，而这一类在**两形态都会被 DB 权威重建覆盖**（TASK-178 A1.2 的既有语义），与 pipeline 无关。改为按注入相对该 record 重建完成时刻的**时序分类**对照（§4.3），判据回到「候选不得新增丢失面」的原意。修正前后读数均留档。
4. **候选客户端间隔栏负值**（`attempt4-clientgap-artifact/`）：候选把「批内排队时刻」与「批提交时刻」混用于同一栏，得 p50 −30.7ms 的无意义值。改为该栏在候选档显式记「批内 `DEL` 排队 → `SADD` 排队」间隔并标注栏语义；服务端空窗一律以 `MONITOR` 时间戳为准。
5. **(c) 「第 2 个批次后中断」在 200 records / cap500 档等于跑完全程**（`attempt5-failuremode-trivial/`）：残留普查为 200/200 已收敛 ⇒ 判别式空转。改为「第 1 个批次后中断」，才真正考察批粒度残留（§4.4）。
6. **缺变量跳过路径的收尾落盘**（`run4-closure-evidence/attempt-skippath-defect/`）：C-01 落库后的收口复跑判别 9/9 绿，但「不导出 `TASK179_IT_*` 复跑同命令」这一证明轮报 `Tests run: 1, Errors: 1`——`@BeforeAll` 已 assume 跳过，`@AfterAll` 仍去写 `task179-it-summary.json`，`rawDir` 为空即抛 `IllegalStateException`。该路径正是任务书 §2.5「缺变量不视为通过」的门禁凭据，必须以 rc=0 的可复跑形态存在。处置：`@AfterAll` 增加「环境门未过即返回」守卫（不落读数、不做清理），**测量逻辑、判据口径与全部读数零改动**；复跑为 `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0`、rc=**0**。此修复与报告订正作为 C-01 之后的独立修正笔入库（路径仍在白名单内，见 §9）。

### 1.5 契约门判据 B：本任务中文交付物名不可被工具收割（沿 TASK-178 §1.5 结论，不试图刷绿）

1. 读数（`run5-closure-gates/contract-inflight-c02.log`）：开工态 `--open TASK-179 --baseline=0b5175b` rc=**0**；C-01 在途（三件证据落工作树、未提交）rc=**0**；C-02 在途（台账四件 + 修正笔已写、未提交）rc=**1**，末行「判据 A=0 判据 B=1」，日志内判据 B 失败共 **72** 行（历史回传交叉触发为主因）；**收口无参 rc=0**（提交后实测，见 §5 收口终检）。
2. 本任务自身判据 B 只失败**一条**：`改动集未声明 …"docs/perf/\345\210\244\345\210\253-like-…-pipeline-…\350\257\255\344\271\211\350\276\271\347\225\214.md"`，即任务书 §2.5 规定的中文报告文件名。根因是工具的两条表达边界：`extract_claims()` 的路径字符类为 ASCII，收不到中文文件名；改动集侧该文件又以 `core.quotepath` 八进制转义形态出现，与明文清单不逐字相等 ⇒ 清单侧永远缺这一条，**属路径形态而非越界改动，不可自纠（除非法名）**，登记不刷绿。
3. 「清单多报」为 **0** 条（本笔自纠）：把 claims 窗口收窄到 §3 一节——① 该节标题改用触发词「只改清单」（工具 awk 门为 `只改|改动|文件清单`；此前写作「足迹清单」未命中，工具回退**全文**收割，把 §0/§1/§4 里作为口径出处出现的 `ci.yml`、`mvn-verify.sh`、`docker-compose.yml`、两个 sql 名等 12 个 token 误当声明项）；② 该节正文只出现白名单全路径，短名与其他文件名一律移到标题不含触发词的 §1/§4/§6。
4. 实质符合性（Level A）：足迹 = `git diff --name-only 0b5175b` + untracked，与 §3 全 7 条逐条相等；`git diff --name-only 0b5175b -- '*/src/main/*'` 命中 **0**，`git status --porcelain -- '*/src/main/*'` 为空。工具侧建议（本任务零改动 `scripts/`）：比对前加 `-c core.quotepath=false`，或 claims 仅取小节内代码块行。

## 2. 一句话结论

**完成**：TASK-179 对账 pipeline 化语义判别收口——`src/main` 零触碰（`reconcileLikeCounts` 冻结，候选仅为 test-only 切片），三支归属 **GO**（(a) 最终收敛态两方案逐 record 等于 DB 权威；(b) 按注入时序分类对照未新增丢失面、pending 未被丢弃；(c) 三种中断残留均可由下轮对账收敛；(d) 候选仍在同一 `lock:like:reconcile` 锁内完成全部写、第二把 tryLock 同样被拒；(e) 服务端命令序与基线逐条相同 600/600 且无 `MULTI/EXEC` 包装；(f) 服务率只登记绝对毫秒与提交次数）。核心语义发现三条：`DEL→SADD` 空窗由本机 p50 0.975ms 压至 0.006ms **但不归零**；关键时点外部可见中间态由「成员集为空」换为「旧成员集仍未提交」；**批内排队遇业务异常时已排队命令仍会触达服务端**（观测到 1 条 `SET` 生效），故「批次原子」不成立。判别力由两支变异红证明（对照 200/200 收敛 vs 抽掉 DEL、DEL/SADD 颠倒各 0/200）。门禁：开工与 C-01 在途契约门 rc=0、offline **450 恒等**、静态 **814 持平**、IT 直跑 9/9 rc=0、词面门四形态 + 探针、token 29 项只增不减、收口无参契约门 rc=0。GO 按任务书 §0.1/§2.4 **不授权生产改动**，pipeline 化实施须另立提案；本机隔离环境读数、未达外部门槛、未 push。

## 3. 只改清单（与 `git diff --name-only 0b5175bf405cb5d70eeb9b17f44bd21affeb465a` 逐条比对）

```
docs/perf/data/exp-like-reconcile-pipeline-semantics.json
docs/perf/判别-like-对账-pipeline-语义边界.md
record-service/src/test/java/com/sportverify/record/service/RecordLikeReconcilePipelineJudgeIT.java
spec/changes/judge-like-reconcile-pipeline-semantics/tasks.json
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-179/handoff.md
work/mailbox/tasks/TASK-179/spec.md
```

前 3 条为 **C-01 证据笔**（3 files / **+3457 −0**：IT 新文件 2404 行、判别报告 224 行、机器摘要 JSON 829 行；哈希 `6fbc95cfcd9eb3227db10287dcd97f38216fe403`）；后 4 条为 **C-02 台账笔**（提案任务清单 10 步 completed + 3 分组 passes 全 true、任务书 §7 收口记录纯追加、本回传、台账 §7 验收记录与 29 项 token 追踪表纯追加）。

> **书写注意**：本小节是契约工具 claims 的唯一来源，正文只允许出现上列全路径；短名与其他文件名一律写在标题避用触发词的小节里（§1、§4、§6）。

## 4. 判别证据（Level A，一手读数）

### 4.1 观测三件与扰动口径（同一套探针对基线与候选复用，维度不减）

| 手段 | 实现 | 口径边界 |
| --- | --- | --- |
| 连接内逐命令计时 | `StringRedisTemplate` 子类 + JDK 动态代理包裹 `opsForValue/opsForSet/delete` | 基线＝真实往返；候选无逐命令往返，该栏改记批内排队间隔（栏内显式标注） |
| 独立只读连接 | 第二个 Redisson 客户端 + 裸模板，做关键时点主动探测（8 条采样 record）与轮询采样 | 轮询为采样：未命中不等于窗口不存在，误差上界＝轮询周期（基线档末轮 15ms、65 轮 520 次观测） |
| 服务端 `MONITOR` | 独立裸 socket 发 `MONITOR`，取服务端逐命令时间戳与客户端地址 | 每命令追加一次服务端写出，会拖慢逐命令处理 ⇒ **MONITOR 档耗时不作服务率证据**；服务率档不启用。存档形态（订正笔② 同步）：命令流 600 条入档但**无时间戳**；时间戳派生的空窗时隙样本仅前 12 条落盘（**12/200**，record 700000–700011） |

装配与生产同构：mapper 经 `MybatisSqlSessionFactoryBean` + `SqlSessionTemplate`；锁为真实 Redisson `tryLock(3, -1, SECONDS)` + 30s 看门狗（防重语义未降级）；连接工厂 `RedissonConnectionFactory`（§1.1）；数据前置态＝每 record「DB 的 50 个成员 + 1 个 DB 没有的幽灵成员 + 计数 999」，使 (a) 判据真正考验 `DEL` 的重建语义。

### 4.2 J1 基线快照与 J2 候选对照（200 records × 50 = 10 000 行，MONITOR 开启）

| 项 | 基线 | 候选 cap500 | 候选 cap1000 |
| --- | --- | --- | --- |
| SET/DEL/SADD 次数 | 200 / 200 / 200 | 同 | 同 |
| SADD 成员总数 | 10 000 | 10 000 | 10 000 |
| 墙钟 ms（含 MONITOR 扰动） | 1049.131 | 85.893 | 59.654 |
| 提交次数 | 600 次逐条往返 | 2（501 + 99 条命令） | 1（600 条命令） |
| 服务端 `DEL→SADD` 空窗 p50 / p90 / max / 合计 ms（统计口径 n=200，落盘抽样 12 条） | 0.975 / 1.542 / 21.669 / 271.075 | 0.006 / 0.006 / 0.053 / 1.398 | 0.006 / 0.007 / 0.070 / 1.573 |
| 客户端栏 p50 / max / 合计 ms | 0.0661 / 16.4676 / 52.6431（往返间隔） | 0.0086 / 3.9815 / 27.8779（批内排队间隔） | 0.0035 / 3.6981 / 26.7375（批内排队间隔） |
| 关键时点探测（8 条采样） | 8/8 读到 `SCARD=0` | 8/8 读到 `SCARD=51`（旧态） | 同 |
| 轮询命中 | 空窗 2 / 旧态 338 / 520 次观测 | 空窗 0 / 旧态 40 / 48 次 | 空窗 0 / 旧态 24 / 32 次 |
| `MONITOR` like 写命令数 | 600 | 600 | 600 |
| `MULTI/EXEC` 包装命令 | 无 | 无 | 无 |
| 5 项结构闭合检查 | 全 true | 全 true | 全 true |

- **空窗统计口径（订正笔② 同步）**：服务端空窗行 p50 / p90 / max / 合计为测量轮内存计算的 **n=200 统计口径**，落盘抽样仅前 12 条（IT 内 `serverGapSamplesMs`，record 700000–700011）；p50 / p90 / 合计**不可由存档样本复算**；表中唯一可由存档互证的是基线档 max 21.669ms＝record 700000 的落盘值（候选两档 max 高于各自落盘样本最大值，不得据落盘样本反推统计 max）。口径与报告 §4、exp JSON 注记一致。

### 4.3 (b) 并发 like 注入（走生产 `like()` 序列：`SADD` 幂等闸门 → `INCR`+`PERSIST` → `RPUSH`）

确定时点（目标 record 700007，DB 50 行）：

| 注入时点 | `like()` 返回计数 | 对账后 计数/成员 | 注入成员存活 |
| --- | --- | --- | --- |
| 基线 · 该 record `SET` 之前 | 1 | 50 / 50 | **否**（被 `DEL` + 重建覆盖） |
| 基线 · `DEL` 与 `SADD` 之间 | 51 | 51 / 51 | 是，留下与 DB 权威的漂移 |
| 基线 · `SADD` 之后 | 51 | 51 / 51 | 是（同样漂移，下轮对账抹平） |
| 候选 · 批内 `DEL` 排队后 `SADD` 排队前 | 1 | 50 / 50 | **否**（整批未提交，随后被批内重建覆盖） |
| 候选 · 批次提交之后 | 51 | 51 / 51 | 是（与基线同类漂移） |

批量并发（对账进行中持续注入，按注入起始时刻相对该 record 重建完成时刻分类）：

| 形态 | 接受注入 | 早于被触碰（丢失/尝试） | 落在 DEL→SADD 窗口（丢失/尝试） | 已处理完（丢失/尝试） | 完成后再注入存活 | pending 对齐 | DB 行数不变 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 基线 | 96 | 25 / 26 | 0 / 0 | 0 / 70 | 10 / 10 | 95→95 成立 | 是 |
| 候选 cap500 | 17 | 15 / 16 | 0 / 0 | 0 / 1 | 10 / 10 | 成立 | 是 |
| 候选 cap1000 | 19 | 18 / 19 | 0 / 0 | 0 / 0 | 10 / 10 | 成立 | 是 |

- 「早于被触碰」一类在两形态均丢失＝既有「pending 未落库操作被 DB 权威覆盖」语义，非 pipeline 新增面（混算即 §1.4 第 3 条的伪结论）。
- 「落在 `DEL→SADD` 窗口」两形态尝试数均为 0：注入自身往返耗时（本机数毫秒）大于窗口宽度（本机亚毫秒），统计注入撞不进窗口 ⇒ 该维度只能由关键时点探测与确定时点注入证明，已如实标注为对照边界。
- 候选确实新增的是**时长**而非**类别**：某 record 在所属批次提交前始终是旧态，此期间的注入都会被批内重建覆盖。

### 4.4 (c) 失败模式（每轮跑后再做一次正常对账）

| 注入 | 残留普查（converged / setOnly / halfApplied / untouched） | 下轮 | 中止后锁已释放 |
| --- | --- | --- | --- |
| 基线：第 3 条 record 的 `DEL` 后抛错 | 2 / 0 / **1** / 197 | 200 / 0 / 0 / 0 收敛 | 是 |
| 候选：第 1 个批次提交后抛错 | 167 / 0 / 0 / 33 | 200 / 0 / 0 / 0 收敛 | 是 |
| 候选：批内排队第 1 条命令后抛错 | 0 / **1** / 0 / 199 | 200 / 0 / 0 / 0 收敛 | 是 |

- 基线失败单元＝单条命令（可留 1 条 record 处于「`SET`+`DEL` 已完成、`SADD` 未到」的半应用态）；候选按批提交时残留呈**整批粒度**（167 完成 / 33 未触碰 / 无半应用 record）。
- **批内排队遇业务异常仍有一条 `SET` 触达服务端** ⇒ `executePipelined` 在回调抛错时仍会走到连接层收尾，已排队命令是否触达服务端由连接层决定 ⇒ 「批次要么全不提交要么全提交」不成立，实施提案若要批粒度原子性须另设手段。
- 服务端批内**硬**中断（`CLIENT KILL ID`）不可注入：该连接工厂的 spring-data 连接执行 `CLIENT` 返回 `UnsupportedOperationException` ⇒ 登记未覆盖（§8 第 2 条），未以数学等价替代。

### 4.5 (d) 锁语义

| 项 | 基线 | 候选 cap500 |
| --- | --- | --- |
| 第二把 `tryLock(3,-1,SECONDS)` 是否取得 | 否 | 否 |
| 第二把等待耗时 ms | 3011.793 | 3005.895 |
| 采样时刻写是否发生在锁内 | 是 | 是 |
| 锁外可见空窗探测数 | 8 | 0 |
| 新增形态的中间态 | — | 「旧成员集未提交」8 条（登记为形态差异，非数量优势） |

### 4.6 (e) 命令序可重放

同数据同会话内先跑基线再跑候选：`MONITOR` 侧 like 写命令序列 **600 vs 600 逐条相同**（`identical=true`），record 分组顺序相同（`recordOrderIdentical=true`），无 `MULTI/EXEC/WATCH` 包装命令，命令总量相等。分组差异体现在相邻命令的服务端间隔：基线 p50 ≈ 1ms（逐条往返），候选批内 p50 ≈ 0.006ms、批间为提交点。每批命令数：cap500 为 501 + 99（2 批），cap1000 为 600（1 批）。

### 4.7 (f) 服务率量化（绝对数字，无百分比、无外推）

2 000 records × 50 = 100 000 行，无 MONITOR，基线与两档候选**交替重复 3 轮**：

| 形态 | 每轮命令数 | 每轮往返/提交次数 | 三轮墙钟 ms | 三轮合计 ms | 三轮收敛普查 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 6 000 | 6 000 | 2998.041 / 2832.446 / 2919.595 | 8750.082 | 2000 全收敛 ×3 |
| 候选 cap500 | 6 000 | 12 | 272.408 / 240.220 / 242.977 | 755.605 | 2000 全收敛 ×3 |
| 候选 cap1000 | 6 000 | 6 | 202.952 / 162.381 / 195.108 | 560.441 | 2000 全收敛 ×3 |

命令总量两方案逐位相等，差异只在提交分组。cap500 与 cap1000 的本机差值不足以支撑选参结论（§8 第 5 条）。

### 4.8 变异红（判别力证明）

| 轮 | converged | setOnly | halfApplied | 其他读数 |
| --- | --- | --- | --- | --- |
| 对照（生产对账） | 200 | 0 | 0 | — |
| 变异 1：候选抽掉 `DEL` | 0 | 200 | 0 | `delete` 命令事件 0、`set.add` 仍 200 ⇒ 幽灵成员未清除 |
| 变异 2：候选 `DEL` 置于 `SADD` 之后 | 0 | 0 | 200 | `tripleOrderPreserved=false` ⇒ 重建成员随即被抹掉 |

## 5. 逐门实测退出码

| 门 | 判据 | 实测 | 结果 |
| --- | --- | --- | --- |
| G0 开工读数 | 任务书 §3 逐位核验 | HEAD `0b5175bf…`、`0 1`、工作树 0 行、offline 450 逐位、静态 814、token 29 项 = 基线 +1、契约门开工态 rc=0 | 过 |
| G1 离线（开工基线） | `bash scripts/verify/mvn-verify.sh --mode=offline test` | rc=**0**，`36/41/33/127/144/59/10` = 450，全 0 失败/错误/跳过，BUILD SUCCESS | 过 |
| G2 静态（开工基线） | `bash scripts/verify/mvn-verify.sh --static=record-service` | rc=**1**、**814** 持平 | 过 |
| G3 IT 直跑（测量轮） | §1.3 直跑例外，`TASK179_IT_RUN=run2-j1j2-measurement` | `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`、rc=**0**、60.70s，BUILD SUCCESS | 过 |
| G3b IT 缺变量跳过 | 不导出 `TASK179_IT_*` 复跑同命令 | 修复收尾缺陷后：`Tests run: 0, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`、rc=**0**，**不记通过**；修复前该轮报 `Tests run: 1, Errors: 1`（§1.4 第 6 条，原文留档） | 过（按未覆盖记账） |
| G4 词面门 | ci.yml 现场提取 58 字节正则，四形态 + 三态判定 + 探针 | 四形态 ZERO_HIT rc=1、探针四形态 HIT rc=0、`PROBE_GONE`；新 IT 与报告单独复扫 0 命中 | 过 |
| G5 契约门 | 开工 / C-01 在途 / C-02 在途 / 收口无参 | 开工 rc=**0**；C-01 在途 rc=**0**；C-02 在途 rc=**1**（本任务中文交付物名不可收割 + 历史回传交叉，见 §1.5）；收口无参 rc=**0** | 读数如实登记，收口过 |
| G6 空白门 | `git diff --check` / `--cached --check` | 均 rc=**0** | 过 |
| G7 足迹清单 | 与 §3 全 7 条比对 | C-01 恰 3 条、C-02 恰 4 条、合计恰 7 条，与任务书 §4 白名单全等；修正笔与两订正笔改动路径均在白名单内，足迹恒 7 | 过 |
| G8 红线 | `src/main/**` 零改动、无并发 mvn、逐路径 add、bash 仅红线入口脚本 | `git diff --name-only 0b5175b -- '*/src/main/*'` = 0；暂存清单核对为 3 条；临时脚本用毕删除 | 过 |
| G9 token | PLAN.md `grep -cF` 29 项只增不减 | 开工逐项 = 基线 +1；本笔追加后逐项 = 开工 +1；`TOKEN_VIOLATIONS=0` | 过 |
| G10 tasks.json | `json.load` | rc=**0**（`TASKS_JSON_OK`），10 步 completed、3 分组 passes 全 true | 过 |
| G11 提交信息 | `-F` 文件落盘且无 BOM | `MSG_HEAD3=746573`、`HAS_BOM False` | 过 |
| G12 收口复跑 | offline 450 / 静态 814 / IT 直跑（`run4-closure-evidence`） | 见下方收口终检 | — |

收口终检（一手读数入 `run5-closure-gates/` 与 `run4-closure-evidence/`）：

- offline 七模块 `36/41/33/127/144/59/10` = **450 恒等**，Failures/Errors/Skipped 全 0，BUILD SUCCESS，rc=**0**；
- `bash scripts/verify/mvn-verify.sh --mode=offline --static=record-service` rc=**1**、`You have 814 Checkstyle violations` ⇒ **不增**；
- 收口 IT 复跑（`TASK179_IT_RUN=run4-closure-evidence`，独立 run 目录，不复写测量轮读数）：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，rc=**0**，67.84s；与测量轮**结构性计数逐位相同**——三档 SET/DEL/SADD 各 200、成员总数 10 000、`MONITOR` like 写命令 600、批次数 cap500=2 / cap1000=1、命令序 `identical=true`（两档）、变异红对照 200 vs 抽 DEL=0 / 颠倒=0、三支归属同为 GO、五项结构闭合检查两侧全 true；仅耗时浮动（J1 墙钟 1049.131 → 957.775ms；服务率基线三轮 2998.041 / 2832.446 / 2919.595 → 3479.996 / 3664.577 / 3421.904ms，候选 cap500 272.408 / 240.220 / 242.977 → 306.689 / 231.498 / 228.901ms，cap1000 202.952 / 162.381 / 195.108 → 210.842 / 224.231 / 194.045ms）⇒ 复现性成立，**报告与 JSON 取测量轮原值**，收口轮数字仅作复现证据登记；
- 缺变量证明轮（同命令、不导出 `TASK179_IT_*`）：`Tests run: 0`、rc=**0**（修复见 §1.4 第 6 条）；
- 契约门：**收口无参 rc=0**（HEAD=`ca39e7013f1ea596f01d271075750f7cd1b9c658`、`git status --porcelain` 0 行，`run7-closure-final/closure-final.log`）；
- **最终 HEAD 上的 mvn 复跑**（`run7-closure-final/`，IT 收尾守卫修正之后）：offline 七模块 `36/41/33/127/144/59/10` = **450 恒等**、rc=**0**、BUILD SUCCESS；`--mode=offline --static=record-service` rc=**1**、**814** 持平；足迹 `git diff --name-only 0b5175b` 计数 **7**，与 §3 全 7 条一致；`origin/main...main` = `0 4`（本笔订正入库后为 `0 5`）；
- 词面门（含台账四件后全仓复扫）：四形态 ZERO_HIT rc=1、探针四形态 HIT rc=0、`PROBE_GONE`；
- token：29 项 PLAN.md 行命中数逐项 = 任务书 §5 基线 +2（开工 +1、本笔追加再 +1），`TOKEN_VIOLATIONS=0`；
- `git diff --name-only 0b5175b` = §3 全 7 条；`git status --porcelain` 为空；PLAN.md 与任务书相对派发笔**纯追加**（删除行数 0）；
- 终态领先/落后：C-01 后 `0 2`、修正笔后 `0 3`、C-02 后 `0 4`、订正笔① 后 `0 5`、订正笔② 落库后 **`0 6`**（均以 `git rev-list --left-right --count origin/main...main` 实测登记，见 §9）；
- 通道还原读数见 §10。

## 6. 机制归因与口径要点

1. **空窗为何不归零**：候选把三条命令在同一次提交内写出，服务端 `DEL` 与 `SADD` 之间只剩服务端逐命令处理间隔（本机 p50 0.006ms），不再有网络往返；但只要窗口非 0，并发 `SADD` 落在其间就可能被批内重建覆盖——因此 (b) 的正确对照单位是「注入相对该 record 重建完成时刻的类别」，不是聚合率。
2. **可见时点被批量对齐**：基线每 record 处理瞬间即对外可见；候选在该 record 所属批次提交前一直显示旧态（幽灵成员与错计数仍在）。读侧在提交窗口内读到的是全批旧值——这是**语义形态变化**，不是单纯提速。
3. **提交边界属于连接层**：业务异常发生在批内排队阶段时，收尾仍会把已排队命令写出（观测到 1 条 `SET` 生效）。要拿到「批原子」语义须显式引入事务/脚本包装，而那会引入基线没有的 `MULTI/EXEC` 命令 ⇒ 直接触 (e) 判据，本轮未实施也未授权。
4. **命令序等价 ≠ 语义等价**：(e) 只证明内容与顺序逐条可重放；空窗形态、可见时点、失败粒度、连接占用四条差异（§4.2–§4.4）必须各自独立裁决，任务书 §0.7 的这条纪律在本案落地为「同一套探针复用 + 观测不到的维度登记未覆盖」。
5. **规模口径**：语义档 200 records（MONITOR 逐命令扰动决定，命令流 600 条）、服务率档 2 000 records（任务书 §2.1 建议规模）。任务书建议的 2 000 未用于语义档，缩小理由已在报告 §15 第 2 条登记。

## 7. 受保护 token（PLAN.md 行命中数 `grep -cF`）

29 项：任务书 §5 基线 → 开工实测 → C-02 追加后实测。开工逐项 = 基线 +1（TASK-178 台账追加所致，任务书 §5 已预告）；本笔追加的追踪表含 29 行 token ⇒ 逐项再 +1（= 基线 +2）。逐项读数见 PLAN.md「TASK-179 受保护 token 追踪表」，实测 `TOKEN_VIOLATIONS=0`，集合规模仍为 **29 项**（本轮无新增）。本文件与报告、JSON 均不含词面门正则字面量与禁用口径词。

## 8. 未覆盖项与不得推出的结论

1. **无线上/生产测量**：全部读数来自本机、scratch 库 `task179_it`、专用 Redis DB 13、单实例、无并发读 ⇒ 不得外推线上延迟或对账周期占用；服务率对比只登记绝对毫秒与提交次数，未计算任何百分比。
2. **服务端批内硬中断未覆盖**：`CLIENT KILL ID` 在该连接工厂不支持 ⇒ 「批次写到一半被服务端断开」的已提交/未提交边界未实测，仅覆盖业务异常路径（§4.4）。
3. **客户端库适用面**：结论仅对 `RedissonConnectionFactory` 上的 `executePipelined` 成立；不得外推为任意 Redis 客户端（含任务书假定的 Lettuce）的 pipeline 语义。
4. **连接池与并发读未测**：候选在批内长时间占用借出连接，其对池水位、等待时延与生产 10min 节拍并发读的影响未测；`MONITOR` 记录的客户端地址数（基线档 49、候选档 30/29）含关键时点探测与轮询自身连接、且随运行时长变化，**不用作占用证据**（报告 §5 第 5 条同步改按未覆盖登记）。
5. **分批参数不结论**：cap500/cap1000 的本机差值不足以支撑选参；真实约束（服务端输出缓冲、占用时长、失败粒度）须由实施提案另行判别。
6. **热路径与积压未测**：`like()/unlike()/getLike()` 单次延迟、真实到达率下 pending 队列形态不在本轮。
7. **规模与实例边界**：语义档 200 records、服务率档 2 000 records / 100 000 行；Redis 单机 7.2.16，未覆盖集群/哨兵/其他版本。
8. **未引用他任务数字充当本轮结论**：只引用 TASK-178 的代码事实与登记口径（如 A1.2 的覆盖语义），数字均本轮实测。
9. **不得推出的结论与止损**：不得据本轮修改 `reconcileLikeCounts`、调整分批参数、引入 pipeline 化或对账增量化，亦不得据 (f) 的绝对毫秒差声称生产收益；GO 仅授权**另立实施提案**（任务书 §0.1/§2.4）。本轮未发现需要 NO-GO 登记的劣化，也未发现基线自身的未知语义异常（§1.4 四处缺陷均属本侧测量工具）。

## 9. 提交

| 提交 | 哈希 | 内容 |
| --- | --- | --- |
| 派发笔 | `0b5175bf405cb5d70eeb9b17f44bd21affeb465a` | `docs(spec): 派发 TASK-179 对账pipeline化语义判别提案与任务书`（本任务开工基线） |
| C-01 证据笔 | `6fbc95cfcd9eb3227db10287dcd97f38216fe403` | `test(record): 新增对账 pipeline 化语义判别 IT 与本机边界报告（TASK-179）`（3 files / +3457 −0；committed blob 实测 CR=0、末字节 0x0a，`run3-precommit-c01/blob-eol-check.log`） |
| 修正笔 | `7e73093a0026c6d98299b0d63586635575d495d2` | `test(record): 修复对账判别 IT 缺变量跳过路径的收尾落盘并订正报告读数（TASK-179）`（IT +4 / 报告 +3 −1；只动 `@AfterAll` 守卫与报告 §12、§15 读数，测量逻辑与判据口径零改动；两路径均在白名单内，故 §3 足迹不变） |
| C-02 台账笔 | `ca39e7013f1ea596f01d271075750f7cd1b9c658` | `docs(mailbox): 登记 TASK-179 判别结论与台账闭环（TASK-179）`（tasks.json 10 步 completed + 3 分组 passes 全 true、任务书 §7 收口记录纯追加、本回传、PLAN.md 验收记录与 29 项 token 表纯追加） |
| 订正笔① | `1a55888578d259c32d1c891be422fb87afc581a2` | `docs(perf): 订正 TASK-179 连接占用维度为未覆盖并登记收口复跑（TASK-179）` |
| 订正笔② | 父锚定：父 = `1a55888578d259c32d1c891be422fb87afc581a2`；落库后 `git rev-list --left-right --count origin/main...main` = **`0 6`** | `docs(perf): 订正 TASK-179 台账提交表与空窗样本存档口径`（本笔：§9 提交表六笔化、证据节与报告及 exp JSON 空窗存档口径注记） |

**提交结构（实际六笔）与偏差登记**：实际结构为「派发笔 + 证据笔 C-01 + 修正笔 + 台账笔 C-02 + 订正笔① + 订正笔②」共六笔，与任务书 §6.3 预注册的两笔结构（C-01 证据笔、C-02 台账笔）存在偏差——后三笔均属测量面修正与台账口径订正（修正笔修跳过路径收尾、订正笔①改连接占用维度登记、订正笔②订正提交表与空窗样本存档口径），**零 `src/main` 改动、足迹恒 7 条、判别结论 GO 与全部数字零改动**，沿 TASK-178 C-03 勘误先例登记。本表一律以**显式哈希**或**父锚定**登记提交，**禁用 `git log -1` 类时效指针**（原表「C-02 见 `git log -1`」即因后续订正笔落库失效，已按实际哈希订正）。PLAN.md TASK-179 段「三笔/样本口径」核查：**无**此类叙述（grep 零命中），故不编辑 PLAN.md。

## 10. 通道还原与临时文件清理

- Redis 宿主映射还原（一手读数 `run7-closure-final/channel-restore.log`）：还原前 `docker port` 为 `6379/tcp -> 0.0.0.0:6379` + `0.0.0.0:16379`（v4/v6 各两条）；`docker compose -f docker-compose.yml up -d redis` rc=**0**；还原后仅 `6379/tcp -> 0.0.0.0:6379` 与 `[::]:6379`，`docker ps` 显示 `0.0.0.0:6379->6379/tcp`；容器因重建 `run_id` 变为 `616288b123bb95dd314a9c4dbfc73f118c177950`（`redis_version 7.2.16`），16379 端口探测 `PROBE16379_AFTER_RESTORE_RC=1`（已拒绝）；临时 override 文件已删（`OVERRIDE_GONE=yes`）。
- scratch 库：`@AfterAll` 执行 `DROP DATABASE IF EXISTS task179_it`；演示 `record_db` / `user_db` / `verify_db` 与用户数据卷零触碰；Redis 专用 DB 13 键（`like:*`、`lock:like:*`）跑前后清理，DB0 演示键零触碰。
- 仓库根 `task179-*.tmp` 临时脚本与消息文件用毕全部删除；`docs/perf/data/raw/` 为 gitignore，不入库。
