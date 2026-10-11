# TASK-198 add-observability-baseline 可观测性基线建设 —— 回传笔（handoff / 执行侧）

## 1. 一句话结论与三支裁决

三批序贯全绿收口：批1 定时任务 MDC 补口（六入口 try/finally + TraceIds 包裹，relay 行级续链含批内 worker，+21 单测）、批2 结构化日志（logstash-logback-encoder 7.4 + 父 pom 锁版本/根级 runtime 继承 + 六服务 logback-spring.xml 双 appender + logging.file）、批3 健康探针深化（六服务 probes + readiness 组按真实依赖裁剪 + gateway 白名单精确追加两路径 + 六探针测试）。offline 569→609（+40，只增不减）；static record/leaderboard 双模块三轮共六次全 rc=0；契约门 --open 态 rc=0；token 29 项 tracked 口径 SUM=2030 与开工逐位持平；C-01 恰 29 文件与白名单逐类全等。

**重大发现（D3，既有缺陷，非本任务改动面）**：以生产 jar 实起冒烟实证四个服务生产启动在 HEAD 即坏——user-service（StompConnectAuthInterceptor 全仓无 bean 注册，TASK-185 引入）、record/verify/leaderboard（TASK-196 精准Pin根除 fastjson 传递存量后，rocketmq-spring 2.3.1 rocketMQTemplate bean 创建期 NoClassDefFoundError: com/alibaba/fastjson/TypeReference）。CI 从不拉起 Spring 上下文故历次外部 CI 均不可见。批3 探针测试以测试侧 accommodation（@Import / @Primary mock + @ConditionalOnMissingBean 让位）使探针端点得以真实验证，缺陷修复列 PLAN 后续项、主源与 pom 不在白名单内未擅动。

## 2. 开工规程核验

| 项 | 实测 |
| --- | --- |
| 基线 | 派发笔 HEAD `397be37`（父 `b829e8e` TASK-197 补记笔）；`origin/main...main` = `0 2`；工作区净 |
| offline 开工 | `--mode=offline test` rc=0，569（common 36 / gateway 44 / user 127 / record 137 / verify 149 / leaderboard 64 / mapmatch 12，api 无用例） |
| token 开工 | 29 项 tracked `git grep -cF` 权威口径 SUM=2030（与 TASK-197 收口真值逐位持平） |
| 六入口行号复核 | VerifyOutboxRelay.java:150（relay，+批内 worker 两路径）、VerifyDegradeService.java:96、RecordLikeService.java:297 与 :382、TrackPointArchiveService.java:80、LeaderboardService.java:373——与任务书 §1 一致 |

## 3. 批1 MDC 补口（读数）

**包裹点清单（5 主源 6 入口 + relay 三层）**：
1. `VerifyOutboxRelay.relay()`（verify）：入口 `TraceIds.put(TraceIds.newId())` / finally `TraceIds.clear()`，任务体 +4 缩进逐字保留。
2. `VerifyOutboxRelay.sendRow`（5 参共用体）：行级续链——`previous=TraceIds.current(); TraceIds.put(row.getTraceId())`，finally **恢复前值**（主线程恢复本轮 UUID、worker 线程清空）；行内 traceId 空/空白时 put 为 no-op 沿用入口值，保证行日志 traceId 恒非空、行间互不串号。串行/并发/分块串行/分块并发四条投递路径共用此体，一并覆盖。
3. `VerifyOutboxRelay.runShard` / `runBatchMarkShard`（批内 worker 路径）：入口 put(newId()) / finally clear（worker 线程 MDC 隔离，行级续链由 sendRow 叠加）。
4. `VerifyDegradeService.compensateStuckVerifying`、`RecordLikeService.flushPendingLikes`、`RecordLikeService.reconcileLikeCounts`、`TrackPointArchiveService.archiveSweep`、`LeaderboardService.settleAndReconcile`：入口 put(newId()) / finally clear，覆盖开关关闭提前返回、锁竞争提前返回、异常传播全部路径。
**零侵入实证**：五文件剥离行首空白 + 删空行后与 `git show HEAD` 逐字 diff，差异**恰为纯新增包裹行、零删除**；六入口签名/注解/参数零改动；既有 relay/flush/结算语义测试全绿（停止条件 #1 未触发）。
**单测**（新增 5 文件 21 例，范式 TraceIdFilterTest/LikeEventProducerTest/既有类测试打桩）：轮内 MDC 非空（ListAppender 逐事件 / 协作者调用点捕获）、行级续链（syncSend 调用点捕获 row.traceId）、行间隔离、worker 路径并发续链（线程安全收集、集合口径断言）、异常传播清理、提前返回清理、UUID 去横线 32 位形态。

## 4. 批2 结构化日志（读数）

- **补仓**：`mvn dependency:get -Dartifact=net.logstash.logback:logstash-logback-encoder:7.4 -s .mvn-settings.xml`（在线一次），`.m2-repo/net/logstash/logback/logstash-logback-encoder/7.4/` jar+pom 双证；此后门禁照常 offline。
- **父 pom**：property `<logstash-logback-encoder.version>7.4</...>`（注释明示 logback 1.4.14 兼容线、禁 8.x）+ dependencyManagement 锁版本 + 根级 `<dependencies>` runtime 继承（六服务 pom 不在白名单；`dependency:tree` 实证 gateway 树内 `logstash-logback-encoder:jar:7.4:runtime` 与 `logback-classic:1.4.14` 同树）。
- **六服务 logback-spring.xml**（新建，主体逐字一致仅头行服务名差异）：CONSOLE appender pattern 与 application.yml `logging.pattern.console` 逐字相同（自定义 xml 接管后 Boot 默认 pattern 属性失效，故 xml 字面量即人读口径）；JSON_FILE = RollingFileAppender(`${LOG_FILE}` ← logging.file.name) + SizeAndTimeBasedRollingPolicy（20MB/14 天/1GB）+ LogstashEncoder（customFields `{"app":"${APP_NAME}"}`，springProperty 注入）。
- **六服务 logging.file.name**：gateway/user/verify/leaderboard/mapmatch yml `logging.file.name: logs/<服务名>.log`；record 落 application.properties（禁建 yml）`logging.file.name=logs/record-service.log`；logs/ 既有 gitignore，无工作区污染。
- **JSON-traceId 运行时证据**（任务书红线内机制性事实，零性能数字）：批3 探针真实上下文运行后，五服务 JSON 文件日志含 traceId 字段行——verify 2564 / record 189 / user 174 / leaderboard 40 / mapmatch 6；样本：verify 行 `{"...","message":"outbox 事件投递成功：id=2, ...","thread_name":"verify-outbox-relay-2",...,"traceId":"trace-worker-2","app":"verify-service"}`（worker 线程行级续链）、leaderboard 行 `"traceId":"a6e028c38d..."`（真实结算定时任务携生成 UUID，真实 MySQL 3307 roundtrip）。

## 5. 批3 健康探针（读数）

**六服务 management 实配**（probes.enabled=true 全数；readiness include 与真实依赖逐字一致；show-details=never 维持；liveness 不挂外部依赖；MQ 不进组）：

| 服务 | readiness include | 落点 |
| --- | --- | --- |
| gateway | `readinessState` | application.yml |
| user | `readinessState,db,redis` | application.yml |
| verify | `readinessState,db,redis` | application.yml |
| leaderboard | `readinessState,db,redis` | application.yml |
| record | `readinessState,db` | application.properties |
| mapmatch | `readinessState,db` | application.yml |

**gateway 白名单**：`whitelist: /api/auth/**,/actuator/health,/actuator/health/liveness,/actuator/health/readiness,/user/ws-notifications`（精确追加两路径，无 /** 后缀保持精确匹配语义；聚合 /actuator/health 原样；prometheus 等仍不经网关放行）。
**探针测试 ×6**：真实懒初始化上下文（`spring.main.lazy-initialization=true` + 测试侧 `spring.cloud.nacos.discovery.fail-fast=false`）RANDOM_PORT + TestRestTemplate 实测 HTTP——liveness 200+UP（六服务全断言）；readiness 端点可达（状态值随中间件实况，不做 UP/DOWN 硬断言——本地/CI 两种实况都绿）；组成员结构以资源文件断言（include 逐字一致 + 禁跨服务复制反断言 + show-details=never 维持）；gateway 另断言白名单精确追加与禁通配。禁断言 db/redis UP 遵守。

## 6. 冒烟口径（如实登记）

- **中间件实况**：compose 栈实起 mysql(容器 127.0.0.1:3307 healthy)/nacos(8848 healthy)/rocketmq-namesrv(9876 healthy)+broker 启动；本机原生 Redis 占 6379（Docker redis 容器端口被占未启，原生实例同址复用）。
- **gateway 生产 jar 实起**：`/actuator/health/liveness`={"status":"UP"}、`/actuator/health/readiness`={"status":"UP"}、聚合 health `{"status":"UP","groups":["liveness","readiness"]}`（组结构运行时可见）——生产态探针全 UP 实证。
- **user/verify/record/leaderboard 生产 jar 实起**：均失败，签名逐一归因（见 §9 D3），生产态 readiness「全 UP」未覆盖此四服务；不造假读数，运行时证据以批3 探针真实上下文（RANDOM_PORT 实测 HTTP + 真实 MySQL 3307/Redis 6379 在线）与 §4 JSON 日志留证补位。
- 冒烟进程已清理（taskkill java），中间件容器留存（环境态，不入库）。

## 7. 新增用例逐模块计数（569 → 609，+40）

| 模块 | 开工 | 收口 | 增量构成 |
| --- | --- | --- | --- |
| common | 36 | 36 | — |
| api | 0 | 0 | — |
| gateway | 44 | 48 | 探针 +4 |
| user | 127 | 130 | 探针 +3 |
| record | 137 | 151 | 批1 单测 +11、探针 +3 |
| verify | 149 | 159 | 批1 单测 +7、探针 +3 |
| leaderboard | 64 | 70 | 批1 单测 +3、探针 +3 |
| mapmatch | 12 | 15 | 探针 +3 |
| 合计 | 569 | 609 | +40（预估 +11 左右，实际覆盖异常/提前返回路径更多，见偏差 D2） |

## 8. 逐门实测表

| 门禁 | 时点 | 读数 | 结论 |
| --- | --- | --- | --- |
| offline 全量 | 批1 后 | rc=0，590（137+11/149+7/64+3） | 绿 |
| offline 全量 | 批2 后 | rc=0，590（配置面无用例变化） | 绿 |
| offline 全量 | 批3 后/收口态 | rc=0，609（36/48/130/151/159/70/15） | 绿 |
| static record-service | 批1/批2/批3 后各一轮 | rc=0 ×3 | 绿 |
| static leaderboard-service | 批1/批2/批3 后各一轮 | rc=0 ×3 | 绿 |
| 契约门在途态 | C-01 后 | `--open TASK-198 --baseline=397be37` rc=0（判据 A 两件套齐 + 判据 B 清单一致） | 绿 |
| 契约门无参态 | C-02 提交后复跑 | rc=0（读数时点显式登记；提交前工作树窗口 rc=1 属时点窗口 artifact，沿 TASK-196 教训） | 绿 |
| token | 开工/C-01 后/收口 | tracked `git grep -cF` SUM=2030 / 2030 / 收口复测只增不减 | 持平 |
| 只改清单 | 收口 | C-01 恰 29 文件（12 改 + 17 新）逐类全等；C-02 恰 4 文件；porcelain 收口后 EMPTY | 全等 |
| diff --check | C-01 前/后 | 干净（无行尾空白/冲突标记） | 干净 |
| 行尾 | 收口 | 新文件 blob 级 pure LF + 末尾换行（`git show HEAD:<file>` 逐文件 0 CR）；修改文件保持既有行尾 | 合规 |
| 零越界 | 收口 | 零触碰 ci.yml/compose*/scripts/web/sql/ADR/api/common/既有测试语义（新增均为新文件或纯追加） | 合规 |

## 9. 偏差登记

1. **D1 common ListAppender 单测被零触碰红线取代**：tasks.json 批2 步骤「common 加 ListAppender 单测」与任务书 §0-1 零触碰 api/common、§3 停止条件 #5 冲突——冲突以任务书为准，不建 common 测试；以 offline 解析面 + §4 运行时 JSON 证据补位，tasks.json 照常勾选、豁免依据见 spec.md §7.4。
2. **D2 offline 增量 +40（预估 +11 左右）**：单测按「轮内非空 + 出口清理 + 异常传播清理 + 提前返回清理」口径全覆盖所致；只增不减达标。
3. **D3【重大发现·既有缺陷】四服务生产启动在 HEAD 即坏**（全部生产 jar 实起实证，CI 不拉上下文故外部 CI 不可见）：
   - user-service：`StompConnectAuthInterceptor` 无任何 bean 注册（TASK-185 引入缺 @Component/@Bean）→ WebSocket 基础设施急切装配即 `APPLICATION FAILED TO START`（实起失败日志复核后按临时件清理）。
   - record/verify/leaderboard：TASK-196 精准Pin根除 fastjson 后，rocketmq-spring 2.3.1 rocketMQTemplate bean 创建期 `NoClassDefFoundError: com/alibaba/fastjson/TypeReference`（record @Scheduled flush→LikeEventProducer 急切链、verify relay→VerifyEventProducer 急切链、leaderboard leaderboardEventConsumer init 触发）。
   - 批3 探针测试以测试侧 accommodation 让探针可验证（user @Import 供 bean；record/verify/leaderboard @Primary mock 模板/Redisson + 自动配置 @ConditionalOnMissingBean 让位）；缺陷本身未修（主源/pom 不在白名单），修复列 PLAN 后续项。
4. **D4 record 探针上下文定制**：既有夹具 `src/test/resources/sharding.yaml` 在测试类路径覆盖主资源（刻意无 dataSourceClassName）+ Boot 3.2.4 db 健康贡献者 Map 注入实例化全部 DataSource——ProbeApp 定制上下文（扫描排除 ShardingDataSourceConfig 与 RecordApplication 自身）+ ProbeStubConfig 提供唯一数据源/MyBatis-Plus 工厂（@MapperScan sqlSessionFactoryRef 按名引用）/事务管理器与 Redisson/RocketMQTemplate mock；主源与夹具零改动。
5. **D5 探针测试范式落地口径**：懒初始化真实上下文范式实测可行；readiness 组结构在 show-details=never 下 HTTP 层不可见，组结构以资源文件断言、端点存在性以 HTTP 断言；`fail-fast=false` 为测试侧属性（注册失败降级为日志，其余发现口径不变）。
6. **D6 冒烟覆盖**：生产态探针「全 UP」仅 gateway 实测；四服务被 D3 阻断，运行时证据以探针真实上下文补位，不造假读数。
7. **D7 行尾**：Write 工具新文件工作区为 CRLF（平台常态），提交 blob 级自动归一 LF 并逐文件复验 0 CR——「新文件 pure LF」以 blob 级口径达标。

## 10. 提交表（显式哈希）

| 笔 | 哈希 | 内容 |
| --- | --- | --- |
| 派发笔 | `397be37` | 任务书派发（开工基线） |
| C-01 实施笔 | `f0bc5a9` | feat(obs) 恰 29 文件（12 改 + 17 新，2043+/364-） |
| C-02 台账笔 | 本笔自指：显式哈希以回传报告为准 | docs(mailbox) 恰 4 文件 |

父锚定 `origin/main...main` = `0 2 → 0 4`；两笔均未推送、不建 PR；推送后第 39 次外部门槛 CI 全绿为外部终验（web 档 typed-router 零改动），红则按签名归因禁重试刷绿。

## 11. 未覆盖项与后续项

- MQ 健康 indicator 自研（无官方 contributor，本任务书明列后续）。
- D3 三类生产启动缺陷修复（user bean 注册；fastjson 精准Pin 与 rocketmq-spring 2.3.1 兼容处置——升级 rocketmq-spring 或受控恢复 fastjson 运行时依赖，另立课题裁决）。
- record 测试夹具 sharding.yaml 对 @SpringBootTest 类测试的类路径冲突治理（可选微课题）。
- 生产态 readiness「全 UP」六服务全量实测：待 D3 修复后补（本笔以 gateway 实测 + 探针真实上下文补位，如实登记）。
