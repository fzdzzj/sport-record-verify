# 任务书：可观测性基线建设（TASK-198 / add-observability-baseline）

唯一指令源：本任务书 + spec/changes/add-observability-baseline/。冲突以本任务书为准。全程 bash 经 `D:\git\Git\bin\bash.exe`；命令行参数禁携中文；唯一验证入口 scripts/verify/mvn-verify.sh（`mvn dependency:tree` 只读分析允许）；offline 与 --static 禁并行（历史 race）。

## §0 红线（十条，违反即 FAILED）

1. **白名单制改动面**：C-01 ≤29 文件按类别——批1 主源 5（verify/mq/VerifyOutboxRelay.java、record/service/VerifyDegradeService.java、record/service/RecordLikeService.java、record/service/TrackPointArchiveService.java、leaderboard/service/LeaderboardService.java）+ 批1 新增单测（对应测试树）+ 父 pom.xml + 六服务 logback-spring.xml（新建）+ 六服务 application 配置（gateway/user/verify/leaderboard/mapmatch yml + record properties）+ 探针测试 ×6；C-02 恰 4 台账文件（§4）。零触碰：ci.yml、docker-compose*、scripts/verify/*、web、sql、ADR、api/common 模块（TraceIds 零改动）、既有测试用例语义。
2. **MDC 包裹零侵入**：try/finally + TraceIds.put/clear，零方法签名改动、零业务逻辑改动、零 @Scheduled 参数改动；relay 用 row.traceId 续链，其余入口 TraceIds 生成新值。
3. **批2 版本与格式纪律**：logstash-logback-encoder 锁 7.4（禁 8.x）；console appender pattern 逐字保留；record 配置只落 application.properties（禁建 yml，snakeyaml 冲突）；开工一次性在线补仓后门禁照常 offline。
4. **批3 组裁剪纪律**：include 与该服务真实依赖严格一致（validate-group-membership 默认 true，跨服务复制即启动失败属预期防线）；liveness 不挂外部依赖；MQ 不进组；show-details=never 维持；gateway 白名单精确追加两路径（保持既有精确匹配语义）。
5. **探针测试口径**：只断言 liveness UP 与 readiness 组结构存在，禁断言 db/redis UP（CI 无中间件）；local 冒烟：本地中间件在跑则实测 readiness 全 UP 留证，不可用则如实登记口径，不造假读数。
6. **offline 基线只增不减**：开工实测 569（36/44/127/137/149/64/12）→ 收口逐模块登记新增数；既有用例语义零改动。
7. **static 触源门**：触碰 record/leaderboard 源码后 `--static=record-service` 与 `--static=leaderboard-service` 均 rc=0；checkstyle 9.3 口径（80 列/FinalParameters/Javadoc 全参）零新增违规；SpotBugs failThreshold=High。
8. **token 29 项只增不减**：tracked `git grep -cF` 权威口径（禁 --untracked 口径标注，TASK-197 已定规范）；新文档禁枚举字面量；台账纯追加。
9. **提交纪律**：两笔 `type(scope): description (TASK-198)` 未推送不建 PR；CI 红禁重试刷绿；新文件 pure LF + 末尾换行、修改文件保持既有行尾；`git diff --check` 干净；MSYS sed -i 禁用于 tracked 文件（F2 教训）。
10. **零性能数字 claim**：观测能力只登记机制性事实（字段存在、组结构、用例计数），不写任何延迟/吞吐/改善数字。

## §1 现状基线（侦察实证，不必重查）

- traceId 链路既有设施：common/trace/TraceIds（HEADER=X-Request-Id）、gateway/trace/RequestIdGlobalFilter（order -200）、五服务 TraceIdFilter、api 模块 TraceIdFeignInterceptor；pattern 已含 `%X{traceId}` 六服务逐字统一。
- 六处 @Scheduled 入口位置：VerifyOutboxRelay.java:150（+批内 worker 线程路径）、VerifyDegradeService.java:96、RecordLikeService.java:297 与 :382、TrackPointArchiveService.java:80、LeaderboardService.java:373。
- 单测范式：Servlet 用 MockHttpServletRequest（TraceIdFilterTest）、MQ 断言用 Mockito 捕获 Message（LikeEventProducerTest）、relay 打桩 Mapper。
- 依赖：micrometer 1.12.4 全家已在树；logstash-encoder 不在本地仓（批2 补仓）；Boot 3.2.4 BOM 管理 micrometer-tracing 但路线甲否决（ADR-0003），零引入。

## §2 预注册设计

- **§2.1 批1**：五主源 MDC 包裹 + 单测；leaderboard/record 触 checkstyle 9.3，MDC 行注意 80 列。
- **§2.2 批2**：父 pom dm 锁 7.4；logback-spring.xml 模板（console 逐字保留 + file LogstashEncoder + springProperty app 名）；六服务 logging.file；record 落 properties。
- **§2.3 批3**：management 配置六服务分叉口径（§0-4）；gateway 白名单追加；探针测试六服务各一。
- **§2.4 三批序贯**，每批完成即跑该批门禁留证，批间不回退已绿批次。

## §3 停止条件

1. MDC 包裹引发既有 outbox/flush/结算语义测试红且无法当日归因（relay 语义历次课题高频验证，不得为 MDC 放松）；
2. JSON file appender 破坏现网 console 人读口径或 logback 上下文启动失败；
3. 某服务 health 组配置引发启动失败且裁剪后仍失败 → 该服务回退仅 readinessState 并申报；
4. logstash-encoder 7.4 与 logback 1.4.14 实测不兼容（编译或运行期冲突）；
5. 需触碰白名单外文件（含 api/common、scripts、compose、ci.yml）；
6. offline 用例数较开工基线减少或既有用例语义被迫改动。

## §4 白名单（只改清单）

- **C-01 实施笔**（≤29 文件，类别清单见 §0-1，新增测试文件路径随实施登记入 handoff 只改清单）
- **C-02 台账笔**（恰 4 文件）：spec/changes/add-observability-baseline/tasks.json、work/mailbox/tasks/TASK-198/spec.md（§7 回填）、work/mailbox/tasks/TASK-198/handoff.md（新建）、work/mailbox/PLAN.md（纯追加）
- 提交主题：C-01 `feat(obs): traceId定时链收口与结构化日志及健康探针基线（TASK-198）`；C-02 `docs(mailbox): 登记TASK-198可观测性基线验收与台账闭环（TASK-198）`

## §5 受保护 token（29 项）

开工实测 SUM（tracked git grep -cF，预期 2030 持平）→ 收口复测只增不减；清单见 TASK-187 handoff §7。

## §6 门禁结构

- **开工规程**：基线 HEAD=派发笔、工作区净、offline 569 开工实测、token 开工实测、六 @Scheduled 入口行号复核
- **预提交门禁**：三批门禁读数齐；`git diff --check` 干净；行尾核验；词面门（改动文件集 ZERO_HIT）
- **收口门禁**：offline 全量（569+新增逐模块）+ static 双模块 + 契约门双态（--open TASK-198 --baseline=<派发笔哈希> 与无参）+ token 复测 + 只改清单全等 + 零越界 + 台账纯追加
- **外部终验**：push 后第 39 次 CI 全绿（web 档 typed-router 零改动），红则按签名归因禁重试

## §7 收口记录（执行侧 C-02 纯追加回填）

### §7.1 提交记录

- 派发笔：`397be37`（父 `b829e8e` TASK-197 补记笔）
- C-01 实施笔：`f0bc5a9`（恰 29 文件：批1 主源 5 改 + 批1 新增单测 5 + 父 pom 1 + 六服务 logback-spring.xml 6 新建 + 六服务 application 配置 6 改 + 探针测试 6 新建）
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff §8 给出）`

### §7.2 实施读数（回填：三批逐批读数 / MDC 包裹点清单与续链口径 / logstash 补仓与版本 / 六服务 readiness 组 include 实配 / 冒烟口径 / 新增用例逐模块计数）

**三批逐批读数**：
- 批1（MDC 补口）：五主源六入口全包裹（relay 入口 + sendRow 行级 + runShard/runBatchMarkShard 两个 worker 路径；VerifyDegradeService.compensateStuckVerifying；RecordLikeService.flushPendingLikes 与 reconcileLikeCounts；TrackPointArchiveService.archiveSweep；LeaderboardService.settleAndReconcile）；新增单测 21 例全绿。
- 批2（结构化日志）：logstash-logback-encoder 7.4 在线补仓至 `.m2-repo`（jar+pom 双证）；父 pom property+dependencyManagement 锁 7.4（logback 1.4.14 兼容线，禁 8.x）+ 根级 `<dependencies>` runtime 继承（六服务 pom 不在白名单，dependency:tree 实证 `net.logstash.logback:logstash-logback-encoder:jar:7.4:runtime` 与 logback 1.4.14 同树）；六服务 logback-spring.xml（console pattern 逐字保留 application.yml 原值、file=RollingFileAppender+LogstashEncoder、springProperty APP_NAME 入 customFields、`${LOG_FILE}` 取 logging.file.name）；六服务 logging.file.name（record 落 application.properties）。
- 批3（健康探针）：六服务 probes.enabled=true + readiness 组按真实依赖裁剪；gateway 白名单精确追加两探针路径（保持精确匹配语义，/actuator/health 聚合端点原样保留）；六服务探针测试（真实懒初始化上下文 RANDOM_PORT + TestRestTemplate 实测 HTTP，readiness 状态值对中间件实况不敏感故不做 UP/DOWN 硬断言，组成员结构以资源文件断言）。

**MDC 包裹点清单与续链口径**：relay() 入口 put(TraceIds.newId())/finally clear；sendRow（四条投递路径共用体）行级 put(row.getTraceId())/finally 恢复前值（主线程恢复本轮 UUID、worker 线程清空，行内空值沿用入口值，行间互不串号）；runShard/runBatchMarkShard worker 入口 put(newId())/finally clear。其余五入口（降级补偿/flush/对账/归档/结算）均 put(newId())/finally clear。

**六服务 readiness 组 include 实配**：gateway=`readinessState`；user/verify/leaderboard=`readinessState,db,redis`；record/mapmatch=`readinessState,db`（record 落 properties 键 `management.endpoint.health.group.readiness.include`）。show-details=never 全数维持；liveness 组不挂外部依赖（Boot 自动生成）；MQ 不进组。

**冒烟口径**：本地 compose 中间件栈实起（MySQL 容器 3307 healthy、Nacos 8848 healthy、RocketMQ namesrv 9876 healthy + broker 启动；Redis 复用本机原生 6379 实例，Docker 侧 6379 端口被占未启容器）。gateway 生产 jar 实起实测：`/actuator/health/liveness`={"status":"UP"}、`/actuator/health/readiness`={"status":"UP"}、聚合 health 显示 `groups:["liveness","readiness"]`。user/verify/record/leaderboard 生产 jar 实起均被**既有生产启动缺陷**阻断（逐条见偏差登记，全部有实起失败日志留证），其运行时证据由批3 探针测试的真实懒初始化上下文（RANDOM_PORT 实测 HTTP + 真实中间件部分在线）与 JSON 日志留证补位：五服务 JSON 文件日志（LogstashEncoder）含 traceId 字段行数——verify 2564 / record 189 / user 174 / leaderboard 40 / mapmatch 6（leaderboard 样本为真实结算定时任务携生成 UUID traceId 落 JSON；verify 样本含 relay worker 线程行级续链 traceId）。

**新增用例逐模块计数**（开工 569 → 收口 609，+40）：gateway 44→48（+4 探针）、user 127→130（+3 探针）、record 137→151（+11 批1 单测 +3 探针）、verify 149→159（+7 批1 单测 +3 探针）、leaderboard 64→70（+3 批1 单测 +3 探针）、mapmatch 12→15（+3 探针）、common/api 持平。

### §7.3 门禁读数（回填：offline 逐模块 / static 双模块 / 契约门双态 / token / 只改清单 / diff --check / 行尾）

- **offline**：三批每批全量 `--mode=offline test` rc=0（批1 后 590、批2 后 590、批3 后 609）；收口态逐模块 36/48/130/151/159/70/15（common/api/gateway/user/record/verify/leaderboard/mapmatch 顺序注意 api 无用例不计）= 609，只增不减。
- **static 双模块**：`--static=record-service` rc=0 与 `--static=leaderboard-service` rc=0 在批1/批2/批3 后各复跑一轮（共三轮六次）全 rc=0。
- **契约门双态**：在途态 `--open TASK-198 --baseline=397be37` rc=0（判据 A 两件套齐 + 判据 B 清单一致）；无参态于 C-02 提交后复跑 rc=0（读数时点显式登记，沿 TASK-196 教训；提交前工作树窗口 rc=1 属时点窗口 artifact）。
- **token**：29 项 tracked `git grep -cF` 权威口径，开工实测 SUM=2030（与 TASK-197 收口真值逐位持平）；C-01 后 SUM=2030 零新增；台账/handoff/PLAN 纯追加不引入受保护 token 字面量，收口复测只增不减。
- **只改清单**：C-01 恰 29 文件（12 改 + 17 新，逐类与 §0-1 全等）；C-02 恰 4 台账文件；`git status --porcelain` 收口后为空。
- **diff --check**：C-01 前后均干净（无行尾空白/冲突标记）。
- **行尾**：新文件提交 blob 级 pure LF + 末尾换行（`git show HEAD:<file>` 逐文件复验 0 CR）；修改文件 blob 级既有行尾保持（`git diff --check` + diff 逐行核验）。

### §7.4 偏差登记（执行侧如实登记）

1. **D1 common ListAppender 单测被零触碰红线取代**：tasks.json 批2 步骤含「common 加 ListAppender 单测断言 JSON 字段含 traceId」，但任务书 §0-1 将 api/common 列为零触碰、§3 停止条件 #5 将触碰 common 列为停手条件（冲突以任务书为准）。改以双路证据补位：offline 全量绿（logback 配置解析面）+ 批3 探针真实上下文运行留证（五服务 JSON 文件日志 traceId 字段行数见 §7.2，含真实定时任务携生成 UUID 的样本行）。tasks.json 该步照常勾选，豁免依据即本条。
2. **D2 offline 增量 +40（预估 +11 左右）**：批1 每入口按「轮内非空 + 出口清理 + 异常传播清理 + 提前返回清理」口径补测，覆盖面大于预估；只增不减达标。
3. **D3【重大发现·既有缺陷·非本任务改动面】四个服务生产启动在 HEAD 即坏**（CI 从不拉起 Spring 上下文故历次外部 CI 不可见；全部经生产 jar 实起失败日志实证）：
   - user-service：`StompConnectAuthInterceptor` 全仓无任何 bean 注册（无 @Component/@Bean，TASK-185 引入），WebSocket 基础设施急切装配即启动失败（生产实起失败日志 `.trae/tmp/smoke-user.log` 复核后已删，失败签名见本条）；批3 探针测试以 `@Import` 供给该 bean 使探针端点可验证（测试侧 accommodation，缺陷本身未修——主源不在白名单）。
   - record/verify/leaderboard-service：TASK-196 精准Pin根除 fastjson 传递存量后，rocketmq-spring 2.3.1 `rocketMQTemplate` bean 创建期加载 `com.alibaba.fastjson.TypeReference` 即 NoClassDefFoundError——凡急切链触达模板的服务（record 的 @Scheduled flush→LikeEventProducer、verify 的 relay→VerifyEventProducer、leaderboard 的 leaderboardEventConsumer init）生产启动即失败（实起失败日志留证）；批3 探针测试以 @Primary mock 模板顶替（自动配置 @ConditionalOnMissingBean 让位）。
   - 修复均已列 PLAN 后续项，不在本任务白名单内擅自处置。
4. **D4 record 探针测试上下文定制**：既有测试夹具 `src/test/resources/sharding.yaml`（占位符替换验证用，刻意不含 dataSourceClassName）在测试类路径覆盖主资源，且 Boot 3.2.4 db 健康贡献者按 Map 注入会实例化全部 DataSource bean——record 探针测试以 ProbeApp 定制上下文（组件扫描排除 ShardingDataSourceConfig 与 RecordApplication 自身）+ ProbeStubConfig 提供唯一数据源/工厂/事务管理器与 Redisson/RocketMQTemplate mock（主源与夹具零改动）。
5. **D5 探针测试范式偏差**：tasks.json 言「@SpringBootTest 现网懒初始化范式」——实测确认可行（六服务均以 `spring.main.lazy-initialization=true` + `spring.cloud.nacos.discovery.fail-fast=false`（测试侧属性，注册失败降级为日志）真实拉起上下文并经 RANDOM_PORT 实测 HTTP）；readiness「组结构存在」在 show-details=never 下 HTTP 层不可见成员明细，故组结构以资源文件断言、端点存在性以 HTTP 断言（对中间件实况不敏感，本地/CI 两种实况都绿）。
6. **D6 冒烟范围**：user/verify/record/leaderboard 生产实起被 D3 缺陷阻断，生产态 readiness「全 UP」实测仅覆盖 gateway；其余服务的 readiness 运行时证据为探针测试真实上下文（部分中间件在线：MySQL 3307/Redis 6379/Nacos/MQ）+ 配置结构断言，不造假读数。

### §7.5 后续项（随本笔登记入 PLAN）

- MQ 健康自研 indicator 列后续项（本任务书既有要求）。
- D3 三类生产启动缺陷修复（StompConnectAuthInterceptor bean 注册；fastjson 精准Pin 与 rocketmq-spring 2.3.1 的兼容处置——升级 rocketmq-spring 或恢复受控 fastjson 运行时依赖，另立课题裁决）。
- record 测试夹具 sharding.yaml 与 @SpringBootTest 类测试的类路径冲突治理（可选微课题）。
