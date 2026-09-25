# 复测：提交链路 DB 等待归因与重复负载核验（TASK-140，同一负载 100 并发 × 2000 请求）

> 对应变更：`spec/changes/measure-submit-db-wait-evidence/`。本任务只测量与订正证据等级，
> 不实施任何性能优化（SQL/池大小/索引/事务边界/JVM/刷盘均未动）。开工基线
> `eb624131674c2c0bff7bcd9257ffd4ce83c707fe`（TASK-139 收口提交，业务代码与
> `bd34f81` 逐字一致）。新增负载共 3 轮（上限 3）：rpt1 / rpt2（计时关）+ connwait（计时开）。

## 结论（按可信度分层）

**已验证事实（直接测得）**

1. **record-service 现有指标不暴露任何连接池计量项**：`/actuator/metrics` 名称列表
   49 项全部为 JVM/system/http/tomcat/executor 类；`/actuator/prometheus` 872 行中
   `hikari|datasource|jdbc` 0 命中；直查 `/actuator/metrics/hikaricp.connections.pending`
   HTTP 404。内层 Hikari 池由 ShardingSphere 反射创建（`sharding.yaml`
   `dataSourceClassName`），不经 Spring 的 DataSource 指标绑定——**现有指标无法回答
   请求级池等待问题**（门槛 2 实测，命令与片段见下文）。
2. **请求级物理连接获取等待已直接测得**：新增默认关闭的最小插桩（内层池包装类在真实
   获取点计时，经线程级桥接归到当前提交请求），同负载 connwait 轮 `connWait` 段
   **P50 527.1ms / P95 970.9ms / max 1597.5ms**（n=2000，含 HikariCP 池内等待）。
   该数值包含池排队与可能的建连开销，不区分二者。
3. **同版本重复跑次的波动远大于 TASK-139 的单次前后差**：计时关闭三轮
   （dbfoot / rpt1 / rpt2）QPS 121.4~166.8、P50 580.7~711.45ms、
   P95 796.4~2169.0ms、P99 1036.3~2794.5ms，全部 2000/2000 成功、0 限流 0 错误。
   TASK-139 观察到的 QPS +1.9% / P50 −8.5% 完全落在此轮间波动带内——单次前后对比
   不构成因果证据（规范「同一负载重复结果须保留波动与可比边界」）。

**推断（有配对数据支撑，但非隔离测得）**

4. select 段（幂等前置）P50 531.7ms 与 connWait P50 527.1ms 为同一请求人群的配对
   分位：差值 ≈4.7ms 是 select 段中除获取等待外的份额（含幂等 SELECT 执行与框架
   开销）的**推导值**。TASK-139 的「约 92% 是池等待」推断方向被本实测覆盖
   （本暖机轮配对占比 ≈99.1%，为推导比较而非隔离测得，不作为通用占比引用）。

**未知（未分离测得，不做结论）**

5. **fsync 份额**：commit 段 24.5ms（P50）是 `beforeCommit→afterCommit` 区间，含提交
   网络往返与刷盘；fsync 单项无请求级计时边界，**未分离测得**。
6. **单条 SQL 的纯 JDBC 执行时间**与 ShardingSphere 解析/路由份额：insertMain
   （2.3ms）/ trackWrite（19.6ms）仍是含获取、解析、执行、网络往返的混合区间。

## 门槛 2 实测（只读，2026-09-25，record-service 8082）

| 检查 | 命令（脱敏） | 结果 |
| --- | --- | --- |
| 指标名清单 | `curl http://127.0.0.1:8082/actuator/metrics` | 49 项，`hikari\|datasource\|jdbc\|pool` 计量项 0 命中 |
| Prometheus | `curl http://127.0.0.1:8082/actuator/prometheus`，grep `hikari\|datasource\|jdbc` | 872 行，0 命中 |
| 直查池指标 | `curl .../actuator/metrics/hikaricp.connections.pending` | HTTP 404 |

归属核实：Spring 侧唯一 DataSource Bean 是 ShardingSphere 代理
（`ShardingDataSourceConfig` 经 `YamlShardingSphereDataSourceFactory` 构建，注释明示
「全工程唯一 DataSource」），内层 Hikari 池由 ShardingSphere 按 YAML
`dataSourceClassName` 反射创建——Micrometer 的 Hikari 绑定看不到它，实测与结构一致。
**JMX/快照类指标即使存在也只能给池占用快照，给不出单请求等待时长**，故未采用。

## 最小插桩（默认关闭，机制与边界）

**机制**（3 个主文件 + 2 个测试文件，全部新增/局部改动，无新依赖、无新端点、无表）：

- `config/TimingHikariDataSource`：普通 `DataSource` 包装，内部懒持有真实
  `HikariDataSource`；`getConnection()` 在真实物理获取点计时。归因边界：仅当前线程被
  武装（计时开启的提交请求）才记录 `connWait` 段；其他端点/后台线程零样本。
- `config/TimingHikariDataSourcePoolMetaData` + `META-INF/services` 注册：ShardingSphere
  5.4.1 按 `dataSourceClassName` 精确匹配 TypedSPI 选池元数据（标准键 `url` ↔ Hikari
  `jdbcUrl` 等同义词）；未注册时建池 NPE（已实测复现）。该 SPI 是 ShardingSphere 公共
  扩展点（pool-hikari 模块同款机制），非私有 API。
- `service/SubmitTxTiming`：各段驻留样本改为有界（最近 `SAMPLE_CAP=4096` 个，淘汰最旧，
  snapshot 增加 `cap` 字段）——修复 TASK-139 开启态无界保留样本问题；新增 connWait
  桥接（`armConnWait`/`disarmConnWait`/`recordConnWait`）。
- `sharding.yaml`：`dataSourceClassName` 指向包装类（计时关时行为仅多一次
  ThreadLocal 读）。未动 `sql-show`、池大小等任何参数。

**计时边界与遗漏路径（如实登记）**

- `connWait` = 进入包装类 `getConnection` 到返回物理连接；获取失败也记到异常抛出，
  样本随该请求回滚丢弃（聚合仅在 afterCommit 收口，单测覆盖）。
- 武装窗口 = submit 方法体入口（先于任何 SQL）到事务完结 `afterCompletion`（含回滚）；
  幂等/重复键/异常路径均被覆盖。已知缺口：若在注册同步前抛异常（理论窗口），线程保持
  武装到下一次提交武装为止——期间其他端点的获取写入的是已死 Rec，**不进入聚合**，
  对存活样本无影响。
- `connWait` 含池排队与建连开销，不区分二者；冷池首轮含 TCP/TLS 建连。
- SQL/JDBC 执行与提交刷盘无独立计时点，未插桩（引入 JDBC 全链委托或数据库侧探针
  超出「最小、低侵入」约束，按任务书停止并记未覆盖）。

**单测门槛**（`mvn-verify.sh --mode=offline --pl record-service test`，rc=0，
**95/0/0/0**，TASK-139 收口时 86 → +9：SubmitTxTimingTest 5、SPI 接线判别 2、
submit 接线判别 1、主 yaml 池类判别 1；既有用例只增不减）：
关闭态零样本零分段、回滚不计成功样本（flush 仅 afterCommit）、开启态驻留样本封顶
`cap=4096`、武装人群仅限提交线程（未武装线程同调用零样本）、池包装计时端到端
（空配置池快速失败仍走过计时边界）、SPI 注册与同义词契约、主 yaml 池类指向。

## 重复同负载（计时关闭三轮；独立 label 与 raw 文件）

| 轮次 | 时间 | QPS | P50 | P95 | P99 | MAX | 成功/限流/错误 | 运行新鲜度 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| dbfoot（TASK-139） | 09-25 | 123.23 | 711.45 | 1332.35 | 1713.44 | 1959.83 | 2000/0/0 | 四服务新起，MySQL 容器沿用（暖） |
| rpt1 | 09-25 | 121.4 | 676.0 | 2169.0 | 2794.5 | 3045.5 | 2000/0/0 | 四服务与 MySQL 容器均新起（冷） |
| rpt2 | 09-25 | 166.8 | 580.7 | 796.4 | 1036.3 | 1345.7 | 2000/0/0 | 全暖（rpt1 后静置 75s） |

- **三轮范围**：QPS 121.4~166.8（跨度 37.4%）、P50 580.7~711.45ms、
  P95 796.4~2169.0ms、P99 1036.3~2794.5ms。
- 轮间条件差异如实登记：dbfoot 的 MySQL 缓冲池沿用上一任务（暖），rpt1 为容器冷启动
  后首轮（JIT/类加载/缓冲池均冷，尾部抬高），rpt2 全暖。三轮仍为同 HEAD、同池 10、
  计时关、sql-show 关、同依赖版本、同模板（每请求 300 点）的可比配置；新鲜度差异
  记录为观察条件，不据此计算任何「改善百分比」。
- 各轮自证：`SUBMIT_TX_TIMING` 行数 0（`rpt-record-timingoff.log` 留档）、ShardingSphere
  SQL 展示行数 0。
- 对 TASK-139 P95 +12.1% 的结论：**保持观察状态**——行为完全未改的重复跑次 P95 跨度
  （796~2169ms）远大于该单次差值，单次前后对比不构成反向或正向证据。

## connwait 轮（计时开，独立组，不与计时关混组）

`load 100 2000 connwait`（record 以 `--record.submit.tx-timing-enabled=true` 重启，
MYSQL_PORT=3307）：QPS 148.5 / P50 591.8ms / P95 1092.7ms / P99 1322.1ms / MAX 1650.5ms，
2000/0/0。运行新鲜度：record JVM 新起、DB 暖。snapshot（samples=2000，cap=4096，
微秒，`connwait-record-timingon.log` 留档）：

| 段 | P50 | P95 | max | 边界说明 |
| --- | --- | --- | --- | --- |
| **connWait** | **527,067us** | 970,905us | 1,597,511us | 物理连接获取（含 Hikari 池等待），**直接测得** |
| select | 531,719us | 981,684us | 1,602,547us | 幂等前置混合段（获取 + SELECT） |
| insertMain | 2,268us | 7,996us | 133,830us | 主表 INSERT 混合段 |
| trackWrite | 19,601us | 59,553us | 307,059us | 轨迹多值 INSERT 混合段 |
| commit | 24,527us | 47,199us | 213,528us | `beforeCommit→afterCommit` 区间（非 fsync 单项） |

- updateStatus 段不存在（TASK-139 已从提交路径删除），符合预期。
- connWait 与 select 为同请求配对：P50 差 ≈4.7ms（推导值）；「select 段绝大部分是
  获取等待」的 TASK-139 推断与本实测方向一致，但占比数字仅在配对人群内成立。
- 计时开启轮的 QPS/P50 落在计时关三轮波动带内（148.5 / 591.8ms），无行为改动的迹象；
  该轮不参与「计时关闭同组」统计。

## 容量与变更边界

- 预计新增轨迹点 3 轮 × 2010 × 300 ≈ 1.809M 行；实测 sport_record 26352 → 32382
  （+6030 = 3×2010，逐轮核对一致），track_point_0 488700 → 563700 → 601400 附近
  （每轮 +37.5k ≈ 603k/16 分片）。未清库、未动 Docker 卷。
- 磁盘：D: 可用 223G，三轮前后无实质变化。负载命令退出码：rpt1/rpt2/connwait 均 0，
  无一次失败重试。
- 本任务未改：SQL、池大小（默认 10 未动）、索引、事务边界、JVM/GC、MQ、消费并发、
  innodb 刷盘、500/1000 档（按任务书禁止）。

## 下一步唯一假设（本任务不实施）

提交请求延迟的支配项是**物理连接获取等待**（直接测得 P50 527ms），其来源是池 10 下
按事务内工作（轨迹多值 INSERT ≈20ms + 提交区间 ≈25ms）排队的倍数效应——若再优化，
方向是进一步缩短事务内工作（轨迹写入或提交区间本身），在证据等级更高的connWait
基线上复核，而不是盲调池大小或刷盘参数。

## 未覆盖项（如实记账，不编造）

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 请求级 fsync 耗时 | **未分离测得** | 提交段是 `beforeCommit→afterCommit` 区间；fsync 无应用侧计时边界，DB 侧仅聚合计数器 |
| 单条 SQL 纯 JDBC 执行时间 / ShardingSphere 解析份额 | 未分离测得 | 需 JDBC 全链委托或数据库侧探针，超出最小低侵入约束（任务书授权停止） |
| connWait 中池等待 vs 建连的区分 | 未分离测得 | 获取点计时含两者；区分需池内部事件级插桩（私有 API，禁止） |
| 池等待的通用占比数字（如「92%」「99%」） | 不引用 | 仅在配对人群/特定暖机状态内成立的推导值，不作通用结论 |
| quality 验收集 | 未覆盖 | mapmatch-service 不在栈内，R5 全程降级（TASK-138 先例） |
| 500/1000 并发档 | 未覆盖 | 任务书禁止改档 |

---

*产物：`docs/perf/data/attr-submit-db-wait.json`（机器可读摘要）；原始数据
`docs/perf/data/raw/{rpt1,rpt2,connwait}-c100-*` 与 `{rpt-record-timingoff,connwait-record-timingon}.log`
（gitignore，不入库）。*
