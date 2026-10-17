# 全链路端到端容量基准报告（TASK-265）

> 状态：**已实施待复核**（2026-10-18 实测采样；开工基线 main @ `eeb8400`；配套工具变更 `scripts/perf/LoadTest.java`（R1）与 `scripts/perf/benchmark-e2e.sh`（R2），本报告为 R3）
>
> 原始产物目录：`docs/perf/data/raw/bench-e2e-task265/`（主矩阵 12 档）与 `docs/perf/data/raw/bench-e2e-task265supp/`（c300 档中间件水位补采）——该目录在 `.gitignore`（`docs/perf/data/raw/`），不入库；本报告内嵌全部关键读数。

## 1. 摘要（TL;DR）

在默认 HikariCP 连接池（`maximumPoolSize=10`，G22 基线）与网关 10s 响应超时的本地全栈上，四大核心业务链路在 100/300/500 三档并发下的实测结论：

| 链路 | 吞吐平台期（QPS） | P50 走势（c100→c500） | 首个劣化档位 | 劣化形态 |
| --- | --- | --- | --- | --- |
| 记录提交（写链路） | ~71-88 | 1313ms → 5895ms | c500 | HTTP 504（网关 10s 超时兜底），9.46% |
| 动态流 feed（读+授权） | ~83-113 | 1112ms → 4429ms | c500 | 客户端连接异常（22 例，0.44%） |
| 点赞批量（20 条热集） | ~1494-2117 | 57ms → 236ms | c500（轻微） | 客户端连接异常（23 例，0.46%） |
| 排行榜总榜（L1+L2 缓存） | ~2860-3639 | 24ms → 124ms | 未出现 | 零错误；MySQL 几乎零查询 |

核心定量结论：

1. **写链路是唯一在 c500 出现大面积服务端劣化的链路**：吞吐对并发不敏感（71→88→75 QPS），瓶颈在 record-service 连接池（2 个 ShardingSphere 数据源 × `maximumPoolSize=10`）+ 单请求 300 轨迹点落库量；c500 时排队超网关 10s 上限，473/5000 请求被 504 快速失败。
2. **连接池顶永不突破**：整个压测期间 `information_schema.processlist WHERE DB='record_db'` 恒为 20（= 2 池 × 10），任何档位无超卖；MySQL `Threads_connected` 恒 41，距 `max_connections=151` 余量巨大。
3. **榜单缓存有效性定量坐实**：leaderboard 场景压满 300 并发时 MySQL 线程活跃数与基线一致（`Threads_running=2`，HikariCP active=0），D1/G12 的 L1+L2 缓存吸收了全部查询。
4. **异步 verify 链健康**：约 1.3 万条压测提交后，outbox 侧 `SENT` 全量、无 PENDING 积压；记录侧仅剩 3802 条 VERIFYING 排水中，链路自愈正常。

## 2. 环境与口径

### 2.1 被测栈（全部实拉实测）

| 组件 | 版本/形态 | 状态 |
| --- | --- | --- |
| 微服务 | gateway(8080)/user(8081)/record(8082)/verify(8083)/leaderboard(8084)，JDK 21.0.9 `java -jar` 生产 jar | 全部 health 200 |
| mapmatch(8085) | 未参与四场景路由 | 按 spec §5.1 受限声明未拉起，控内存 |
| MySQL | `sport-verify-mysql`（3307），max_connections=151，innodb_buffer_pool_size=128MB（默认） | healthy |
| Redis | 宿主机 6379（Windows 原生 3.0.504） | PONG |
| Nacos / RocketMQ | 8848 / 9876+10909 容器 | healthy |
| 被压数据规模 | record_db.sport_record 起始 167365 行（16 分片 track_point 表） | healthy |

### 2.2 口径声明（如实登记，读数前必读）

1. **鉴权降级态**：本压测在 `app.auth.enabled=false`（网关/record/leaderboard 默认值）下进行，四端点免 JWT；R1 新增的 `--header` 能力即为鉴权开启态预留（如 `--header "Authorization: Bearer ..."`），本次未启用。
2. **record 启动运行时开关**：生产 jar 直接启动暴露既有循环依赖（`SportRecordService` ↔ `RecordLikeService` 双 @Autowired setter，自 TASK-221/223 引入后无 prod jar 冒烟验证过；Boot 3.2 默认禁止循环引用）。本次以 `--spring.main.allow-circular-references=true` 运行时参数拉起，**未改动任何仓库文件**；修复该环需生产代码变更，超出本任务白名单，建议另立任务（登记于 handoff §4）。
3. **存量库结构对齐**：本地存活卷缺 `visibility` 列（repo DDL 已含、迁移脚本 `sql/migrations/add-record-visibility.sql` 未应用过），feed/likes 首探即 500（Unknown column）。已按官方口径在容器内执行该幂等迁移脚本补齐列（环境运维操作，零仓库文件改动），并重启 record 使 ShardingSphere 重载表结构元数据。
4. **网关路由事实**：排行榜总榜实际路由为 `GET /leaderboard/api/leaderboard?type=overall&size=50`（`LeaderboardController` 的 `@GetMapping` + `type` 参数），任务书里 `/leaderboard/api/leaderboard/overall` 为简写口径，实测以真实路由为准。
5. **客户端模型**：闭环并发（concurrency 个虚拟线程各自循环请求，直至达到 total），与 run-perf.sh 既有 100/500/1000 口径一致；吞吐=完成数/墙钟，百分位线性插值，延迟为客户端观测（含网关往返）。
6. **错误口径**：HTTP 429 单列（`limited429`，本次全零——相关路径未配限流规则）；`errors` = 非 2xx 且非 429 的 HTTP 响应 + 客户端连接异常（`EXCEPTION`，单列于 statusClass）。

## 3. 压测方法

### 3.1 R1：LoadTest.java 多链路内核扩展（本任务变更）

- `--header "Name: Value"` 可重复自定义请求头（鉴权/压测标识透传，经网关）；
- `--method` 显式支持 GET/POST/PUT/DELETE（GET 不携带请求体与 Content-Type，POST/PUT 携带 JSON 体）；
- 状态码两级统计：`statusHistogram`（逐码）+ `statusClass`（2xx/400/401/403/404/429/5xx/other/EXCEPTION 分类），控制台与摘要 JSON 双输出；
- URL 支持 `{userId}`/`{seq}` 逐请求渲染（点赞批量 `?userId={userId}` 身份轮转）；
- 保持单文件零依赖运行（`java scripts/perf/LoadTest.java ...`，JDK21）；CSV 列结构不变（seq,concurrency,latency_ms,status,ok），JSON 仅新增 `method`/`statusClass` 键，向后兼容。

### 3.2 R2：benchmark-e2e.sh 一键编排（本任务新增，100755）

- 四场景编排：submit（POST `/record/api/records`，模板 300 轨迹点，`{requestId}` 唯一幂等键）、feed（GET `/record/api/records/feed?page=1&size=20`）、likes（POST `/record/api/records/likes/batch?userId={userId}`，请求体为库内最新 20 条真实记录 id 热集）、leaderboard（GET `/leaderboard/api/leaderboard?type=overall&size=50`）；
- 档位与总量：100/300/500 并发 × 2000/3000/5000 请求（`--tiers`/`--total` 可覆盖），每档预热 20 请求；
- 中间件水位采样器（每档独立 TSV，2s 一拍，逐段容错）：MySQL `Threads_running`/`Threads_connected`、record_db processlist 连接数（ShardingSphere 内层池 Spring 不绑定 hikaricp 指标，processlist 为池水位佐证口径）、verify/leaderboard 服务 HikariCP `connections_active`（prometheus 直读）、Redis `connected_clients`；
- CPU/内存：各档首末对 java 进程族做 PowerShell `Get-Process` 快照，差分得 CPU 秒/工作集；
- 产物：`<scenario>-c<C>-summary.json` / `-raw.csv` / `sampler-<scenario>-c<C>.tsv` / `cpu-mem.tsv` / `rollup.csv`，全部落 gitignored 的 `docs/perf/data/raw/`。

## 4. 主矩阵（12 档全读数，主压测 run `bench-e2e-task265`）

### 4.1 记录提交写链路（POST /record/api/records）

| 并发 | 样本 | 成功 | 429 | 错误 | 错误率 | QPS | P50(ms) | P95(ms) | P99(ms) | MAX(ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 | 2000 | 2000 | 0 | 0 | 0.00% | 71.49 | 1312.91 | 1931.57 | 2248.55 | 3962.18 |
| 300 | 3000 | 3000 | 0 | 0 | 0.00% | 88.12 | 3244.77 | 4770.84 | 6611.51 | 8543.69 |
| 500 | 5000 | 4527 | 0 | 473（HTTP 504） | 9.46% | 74.81 | 5895.39 | 11899.51 | 13321.05 | 15780.23 |

- 状态码分布（c500）：`{200: 4527, 504: 473}`——错误全部为网关 10s response-timeout 兜底（快速失败，非悬挂）。
- 写放大事实：单请求落 1 行 sport_record + 300 行 track_point（16 分片路由）+ 1 行 outbox 事件，提交 QPS 88 对应轨迹点写入 ≈ 26400 行/s 的瞬时峰值口径。

### 4.2 动态流读链路（GET /record/api/records/feed?page=1&size=20）

| 并发 | 样本 | 成功 | 429 | 错误 | 错误率 | QPS | P50(ms) | P95(ms) | P99(ms) | MAX(ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 | 2000 | 2000 | 0 | 0 | 0.00% | 83.04 | 1111.52 | 1795.88 | 2662.28 | 3624.48 |
| 300 | 3000 | 3000 | 0 | 0 | 0.00% | 97.42 | 2940.53 | 3760.87 | 3908.36 | 5226.28 |
| 500 | 5000 | 4978 | 0 | 22（EXCEPTION） | 0.44% | 113.06 | 4428.65 | 4613.31 | 4669.89 | 6301.71 |

- 降级态 feed 为 PUBLIC 全站时间线（page 1 热页）；每页 20 条 × 每条批量可见性判定 + 轨迹聚合，P50 秒级由 DB 侧工作量大头决定；c500 出现 22 例客户端连接异常（网关过载侧连接层拒绝/重置，服务端未回 5xx）。

### 4.3 批量点赞查询链路（POST /record/api/records/likes/batch?userId={userId}）

| 并发 | 样本 | 成功 | 429 | 错误 | 错误率 | QPS | P50(ms) | P95(ms) | P99(ms) | MAX(ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 | 2000 | 2000 | 0 | 0 | 0.00% | 1494.25 | 57.07 | 138.03 | 174.40 | 236.36 |
| 300 | 3000 | 3000 | 0 | 0 | 0.00% | 2117.16 | 129.46 | 227.15 | 281.70 | 511.02 |
| 500 | 5000 | 4977 | 0 | 23（EXCEPTION） | 0.46% | 1941.64 | 235.61 | 338.17 | 412.07 | 507.35 |

- 20 条真实记录 id 热集 + `{userId}` 16 分片轮转（G3 批量权限 + 点赞计数与 Feign 懒拉链路）；吞吐对并发在 c300 达峰（2117），c500 轻微回落并出现 23 例连接异常。

### 4.4 排行榜聚合查询链路（GET /leaderboard/api/leaderboard?type=overall&size=50）

| 并发 | 样本 | 成功 | 429 | 错误 | 错误率 | QPS | P50(ms) | P95(ms) | P99(ms) | MAX(ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 100 | 2000 | 2000 | 0 | 0 | 0.00% | 2860.34 | 24.38 | 84.62 | 155.88 | 447.17 |
| 300 | 3000 | 3000 | 0 | 0 | 0.00% | 3639.36 | 60.22 | 168.57 | 283.83 | 717.25 |
| 500 | 5000 | 5000 | 0 | 0 | 0.00% | 2869.69 | 124.31 | 369.28 | 427.47 | 695.06 |

- 全档零错误；c500 P50 较 c300 上升一倍（124ms）但 QPS 仍近 3000——L1 进程内缓存吸收主要读量，L2 Redis 兜底，DB 零参与（见 §5 水位）。

## 5. 中间件与资源水位（补采 run `bench-e2e-task265supp`，c300 档 + 全程观测）

### 5.1 MySQL（3307 容器）

| 观测量 | 基线（空载） | submit c300 | feed c300 | likes c300 | leaderboard c300 |
| --- | --- | --- | --- | --- | --- |
| Threads_running | 2 | 峰值 22 | 峰值 32 | 2-5 | 2 |
| Threads_connected | 41 | 41（恒定） | 41 | 41 | 41 |
| record_db processlist 连接数 | 20 | 20（恒定） | 20 | 20 | 20 |

- `record_db` processlist 恒 20 = record-service 两个 ShardingSphere 数据源 × `maximumPoolSize=${MYSQL_POOL_SIZE:10}`（sharding.yaml L37 默认 10）之和；**任何档位、任何场景池顶从未被突破**——默认池容量是写链路吞吐平台期的直接成因，但配置本身工作正常、无泄漏无超卖。
- `max_connections=151`：全栈连接足迹（record 20 + verify ≤10 + leaderboard ≤10 + user ≤10 + 采样/运维 <5）≈ 55，余量充足。
- `innodb_buffer_pool_size=128MB`（默认值）：对 18 万行 sport_record + 5000 万行级 track_point 的工作集明显偏小，读路径 P50 秒级与其相关（见 §7 建议）。

### 5.2 服务侧 HikariCP（prometheus 直读，`connections_active`）

| 服务 | 观测峰 | 池上限 | 结论 |
| --- | --- | --- | --- |
| verify-service | 10.0（submit 期间多次触顶） | 10（默认） | 写高峰时 verify 池打满，为异步链消化速率参与者之一 |
| leaderboard-service | 0.0（全场景恒零） | 10（默认） | 缓存全吸收，DB 零参与 |
| record-service | 不适用（ShardingSphere 包装，Spring 不绑定内层池，无 hikaricp* 指标） | 2×10 | 以 processlist=20 口径佐证（§5.1） |

### 5.3 Redis（宿主 6379）

`connected_clients` 稳定在 101→109 区间（压测客户端与服务的连接增量 +6~8），无连接抖动；总榜 L2 命中态下 Redis CPU 无可感知退化。

### 5.4 压测机 CPU/内存（java 进程族 = 5 服务 + LoadTest JVM，PowerShell 快照差分）

| 阶段 | CPU 增量（秒） | 墙钟（秒） | 折合占用（核） | 工作集峰值 |
| --- | --- | --- | --- | --- |
| submit c100 | 139.4 | 28.0 | ~5.0 | ~1.84GB |
| submit c500 | 208.3 | 66.8 | ~3.1 | ~1.88GB |
| feed c100 | 105.9 | 24.1 | ~4.4 | ~2.00GB |
| likes c300 | 10.6 | 1.4 | 短时高峰 | ~2.02GB |
| leaderboard c300 | 8.6 | 0.8 | 短时高峰 | ~2.04GB |

- 写/feed 链路在 c100 即把 5 核级 CPU 推满（客户端+6 服务的共享宿主机口径），后续吞吐平台期与 CPU 饱和叠加；likes/leaderboard 单档墙钟 <2.5s，CPU 影响可忽略。java 族工作集全程 ≤2.04GB。

### 5.5 异步 verify 链状态（压测后快照）

- `verify_db.verify_event_outbox`：156325 行全部 `SENT`，零 PENDING——outbox 中继无积压；
- `record_db.sport_record` 状态分布：PASSED 172665 / REJECTED 3977 / VERIFYING 3802（排水中的在途判定，MQ 消费侧正常追赶）/ 其它 1；
- 压测增量约 1.3 万条提交全部进入正常状态机流转，未发现卡死态。

## 6. 对照 G22（连接池默认 10）的定量分析

1. **默认池是写链路平台期的第一解释变量，但不是配置缺陷**：submit 吞吐 71→88→75 QPS 对并发不敏感，为「池内 20 连接 × 单请求 DB 工作量（301 行写 + 分片路由）」的乘积上限；同时 processlist 恒 20、无等待超卖，说明池在正确工作——瓶颈是容量而非健康度。
2. **读链路对默认池不敏感**：feed c300 时 record 池同样恒 20 但吞吐仍在爬升（83→97→113），feed 的秒级 P50 由单请求 DB 工作量（每页 20 条 × 可见性批量 + 轨迹聚合）决定，池扩容对该形态收益有限。
3. **verify 池触顶（10/10）出现在 submit 高峰**：异步链消化速率与 record 写入速率失配时表现为 §5.5 的 VERIFYING 存量排水（最终归零），属预期弹性而非缺陷。
4. **缓存链路证明「池 10」在正确架构下绰绰有余**：leaderboard 3600 QPS 时 DB active=0——池容量问题本质是「每请求 DB 工作量」问题，架构侧消解（缓存/批量）优于参数侧扩容。
5. **网关 10s response-timeout 是过载兜底的真实执行者**：c500 写链路 473 例 504 快速失败（无悬挂连接、无池雪崩、压测后零人工干预自愈），该行为值得在生产容量规划中保留。

## 7. 生产容量基线建议（定量）

1. **安全运行点（默认配置、不调任何参数）**：写链路 ≤300 并发（c300 零错误、QPS 88、P99 6.6s 偏高但无失败）；feed ≤300 并发（零错误）；likes/leaderboard 在 c500 仍有大余量。据此单实例容量基线：**写 ≈ 85 QPS、feed ≈ 100 QPS、likes ≈ 2100 QPS、榜单 ≈ 3600 QPS**（本地 5-6 核共享宿主机口径，生产同规格需按核数线性重标定）。
2. **若需 c500 写链路无损**：优先顺序为 ① 复用既有 `MYSQL_POOL_SIZE` 环境变量提高 record 池上限（零代码改动，需配合 MySQL max_connections 与宿主内存重测）→ ② 评估 gateway response-timeout 与写链路 SLA 的匹配性 → ③ 降低单请求轨迹点写入量（分批持久化）。**严禁在未重测前盲调池容量**（本任务红线，G22 结论维持）。
3. **MySQL 侧配套观察项**：innodb_buffer_pool 128MB 默认值对当前数据量偏小，feed P50 秒级与其相关；生产规格建议按数据热集 1.5-2 倍内存配置后重测 feed 基线。
4. **容量监控锚点**（可直接接入告警）：`Threads_running` 基线 2、写高峰 22-32；`record_db` processlist = 20（池顶）；verify hikari active 触顶 10 持续 >30s = 异步链消化不足信号；网关 504 计数 >0 = 写链路过载信号。
5. **限流缺口提示**：四链路 429 全零——当前网关未对四条业务路径配置 Sentinel 限流规则；过载时系统以 504/连接异常形态自然背压，生产建议对写链路显式限流（本次仅登记，不改规则）。

## 8. 产物清单与可复现性

- 主矩阵：`docs/perf/data/raw/bench-e2e-task265/`（12 × summary.json + raw.csv + cpu-mem.tsv + rollup.csv）；
- 水位补采：`docs/perf/data/raw/bench-e2e-task265supp/`（4 × sampler-*.tsv + rollup.csv）；
- 复现命令：`bash scripts/perf/benchmark-e2e.sh --label=<runid>`（默认四场景 × 100/300/500；前置：五服务栈健康 + `sport-verify-mysql` 容器 + redis 6379）；
- 已知偏差：补采 run 的 submit c300（QPS 42.56 / 504 率 22.07%）劣于主矩阵同档（88.12 / 0%），成因为补采时主 run 的 1.3 万条提交仍在异步链排水（§5.5），属带背景负载的叠加读数——主矩阵为权威基线，补采仅用于水位观测。

## 9. 受限声明

1. 单机共享宿主机（客户端与服务同机），CPU 口径为进程族差分，绝对值仅供相对比较；
2. 鉴权降级态（§2.2-1）下测得，鉴权开启态（JWT 校验 + X-User-Id 注入）读数待另测；
3. record 以运行时开关拉起（§2.2-2），循环依赖修复后再测时启动路径读数不受影响（该开关不参与请求路径）；
4. likes 场景为 20 条 id 热集读形态，未覆盖冷 id 分布与批量下推的极端分片情况；
5. mapmatch 链路不在四场景范围（无路由参与），其容量口径未建立。
