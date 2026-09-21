# P2 前提体检（只读，2026-09-20）

范围口径先对齐：用户给的 ID 展开是 **15 个**（007、010、012~017、019~025），不是 18；
其中 **TASK-025 没有 spec.md**（目录里只有 handoff.md），故实际读到的 spec 为 14 份。
所有判定基于现场 grep/读文件，未采信任何 handoff 自述；未改 main/test 代码、spec.md、PLAN.md，未 commit。

按任务主判定统计（15 个）：**不成立 8**（007、012、014、016、021、023、024、025）｜**成立 3**（013、015、019）｜**需人拍板 3**（010、017、022）｜**需环境 1**（020）。其中 013/015/016 三个是"一半已存在、一半真缺"的拆分判定，细分见各节首行。

---

## TASK-007 RecordService XXL-JOB 每日汇总

前提是否成立：**不成立**（调度能力已在 `record-service/.../RecordApplication.java:22` + 三条 `@Scheduled`；XXL-JOB 侧无 executor、无 admin、无汇总表）
证据：`RecordApplication.java:22` `@EnableScheduling`；真任务已在跑 `RecordLikeService.java:204 flushPendingLikes`、`:263 reconcileLikeCounts`、`VerifyDegradeService.java:96 compensateStuckVerifying`（三条都带 Redisson 锁）；全仓 `grep XxlJobSpringExecutor` 命中 0，3 个 compose 文件 `grep -i xxl` 命中 0；record-service pom 无 xxl-job-core；`grep -iE "daily|summary|stat" sql/*.sql` 只有 `status` 列，无汇总表。另：同病桩类 `leaderboard-service/.../DailyLeaderboardReportJob.java` 被 `leaderboard-service/pom.xml:126-130` 用 `<excludes>` 排除编译，其 import 是 `xxl.job.core.*`（`:6-7`，官方包名为 `com.xxl.job.core`），依赖 `com.xuxueli:xxl-job-core:2.4.0` 在 `.m2-repo` 里 find 不到。spec 本身要求的正是一个 TODO 空桩（`spec.md:48-52`）+ 把 yml 配置注释掉（`spec.md:18`）——与 D5 判死的形态完全同构。
若成立：最小落点 ——（不适用；若坚持 XXL-JOB，先删 `DailyLeaderboardReportJob.java` 与该 `<excludes>` 块并开 ADR）
阻塞项：需 xxl-job-admin 容器 + executor 注册；需归档/汇总表 DDL（现在无表可写）

## TASK-010 Gateway OpenAPI 3.0

前提是否成立：**需人拍板**（springdoc 全仓确实为 0，缺口真实；但 spec 指定的落地坐标在本网关上不成立）
证据：缺失面——全仓 `grep -ri "springdoc|swagger"` 在 pom/yml/java 命中 0（唯一命中是 `RuleVersionController.java:18` 的注释词）→ 能力不存在，这点前提成立。但网关是响应式：`gateway-service/pom.xml:21` `spring-cloud-starter-gateway`，而 spec 指定的 `springdoc-openapi-starter-webmvc-ui`（`spec.md:20-24`）是 Servlet/MVC 构件；且 `grep -E "@RestController|@Controller|RouterFunction|@RequestMapping"` 在 `gateway-service/src/main` 命中 **0**（src 只有 5 个 java：`GatewayApplication`、`AuthGlobalFilter`、`JwtTokenParser`、`SentinelGatewayRuleConfig`、`RequestIdGlobalFilter`）→ springdoc 扫不到任何端点，"文档包含网关路由配置说明"必须手写路由表。验收命令的 8888 端口不存在：`gateway-service/src/main/resources/application.yml:2` `port: 8080`。
若成立：`gateway-service/pom.xml`（换 webflux-ui 构件）、`gateway-service/src/main/resources/application.yml`、+ 一份手写 OpenAPI 资源或自建 `/v3/api-docs` RouterFunction
阻塞项：无外部依赖，本地可验；仅需拍板"自动生成（改 webflux 构件）还是手写路由文档"

## TASK-012 Gateway CSRF 防护 + BCrypt 管理密码

前提是否成立：**不成立**（路径权限已在 `gateway-service/.../auth/AuthGlobalFilter.java:53,61,95`；密码加密已在 `user-service/.../AuthService.java:54,112,148`）
证据：`AuthGlobalFilter.java:53` 白名单 `${app.auth.whitelist:/api/auth/**,/actuator/**}` 已覆盖 spec 的"/actuator 放行"；`:61` `app.auth.admin.paths:/admin/**,/verify/rules/**` + `:95` `ROLE_ADMIN` 越权拒绝，已实现 spec 的"/admin 路径保护"，且 `/admin/**` 路由到 `lb://verify-service`（`gateway-service/src/main/resources/application.yml:62-67`）——网关侧没有本地管理员账号体系，spec 的 `spring.security.user.name=admin` 是虚构对象；CSRF 无对象可禁：gateway pom 无任何 security 依赖（`grep -n "<artifactId>" gateway-service/pom.xml` 全表未见）。BCrypt 在 `AuthService.java:54`（`new BCryptPasswordEncoder()`）、`:112` encode、`:148` matches，且 `user-service/pom.xml:75` 与 `docs/adr/0007-鉴权设计.md` 明确"只引 spring-security-crypto，避免拉 Spring Security 全家桶"。
若成立：——（不适用；要在网关层引 WebFlux Security 会与现有 JWT 过滤器链和 ADR-0007 正面冲突，需先改 ADR）
阻塞项：需人拍板是否推翻 ADR-0007 的依赖取舍

## TASK-013 Protobuf 序列化 + HTTP/2（含 Micrometer 指标半边）

前提是否成立：**成立**（序列化/HTTP2 半边，全仓零实现）＋**不成立**（Actuator 半边已完成）＋判"≥50%"**需环境**
证据：缺失面——全仓 `grep -i "protobuf|http2|protocol:"` 在 pom/yml/java 命中 0，无任何 codec。已完成面——spec 表格里 6 个服务的行号逐条核对为真：`record-service/src/main/resources/application.properties:84,87,88`、`user-service/.../application.yml:77-94`、`verify-service/...:156-173`、`leaderboard-service/...:100-116`、`mapmatch-service/...:55-71`、`gateway-service/...:131-148`（六份都含 `include: health,info,prometheus,metrics` 与 percentiles-histogram）；Prometheus/Grafana 也已在 `docker-compose.yml:154,177` → `spec.md:54` 的"需先配置 Prometheus/Grafana"同样过期。收益前提未证：`docs/perf/压测报告.md` + `docs/perf/data` + `scripts/perf/{run-perf,capture-baseline}.sh` 齐备，但 `docs/adr/0002-压测与优化实录.md` §1 记录的实测硬瓶颈是"HikariCP 10 连接 × 逐条 INSERT"（36 QPS→136.8 QPS 来自批量 INSERT），全程未出现序列化瓶颈。
若成立：`leaderboard-service/pom.xml`（protobuf-java）、`leaderboard-service/src/main/resources/application.yml`（`server.http2.enabled`）、+ 一个显式 codec/端点（Boot 不会自动切 protobuf）
阻塞项：需 perf 环境跑前后基线才能回答"≥50%"；需人拍板是否值得（ADR-0002 无序列化瓶颈证据；ADR-0001 §1 版本矩阵标"勿单独升级"）

## TASK-014 Gateway Sleuth + Zipkin

前提是否成立：**不成立**（Spring Cloud 2023.0.1 已无 Sleuth 这件构件；spec 的依赖坐标解析不出来）
证据：父 pom `pom.xml:41-43` Boot 3.2.4 / Cloud 2023.0.1 / SCA 2023.0.1.0；本地 BOM `.m2-repo/org/springframework/cloud/spring-cloud-dependencies/2023.0.1/spring-cloud-dependencies-2023.0.1.pom` 内 `grep -c sleuth` = **0**，`.m2-repo/org/springframework/cloud/` 下无任何 sleuth 目录 → `spec.md:30` 的 `spring-cloud-starter-sleuth` 拿不到版本也拿不到 jar；正解构件已在本地：`.m2-repo/io/micrometer/micrometer-tracing-bom/{1.2.0,1.2.2,1.2.4,1.2.6}`。zipkin 在 3 个 compose 文件命中 0。现状是自建最小实现：`common/.../trace/TraceIds.java:8` 注释自陈"最小实现，非 Sleuth/Zipkin"、`gateway/.../trace/RequestIdGlobalFilter.java:23`（X-Request-Id 头透传 + MDC，order -200 见 `:58`）、`leaderboard-service/.../application.yml:118-122` MDC `traceId` 日志格式。
若成立：——（不适用；这不是"补依赖"而是换方案）若拍板做 Micrometer Tracing：6 个服务 pom + 6 份 yml + compose 新增 zipkin service
阻塞项：需 zipkin 容器（compose 无）；需人拍板选型与采样率

## TASK-015 线程池隔离 + Hikari 调优 + Nacos 配置迁移

前提是否成立：**成立**（线程池、Hikari 池参数两项真缺）＋**不成立**（Nacos 接入与 actuator 依赖已在）；配置迁移本身**需人拍板**
证据：真缺口——全仓 `grep -E "@Async|ThreadPoolTaskExecutor"` 在 `*/src/main` 命中 0（只有 `LeaderboardEventConsumer.java:75`/`VerifyEventConsumer.java:83` 两个自建 `ScheduledExecutorService`），leaderboard `config/` 目录只有 `CacheConfig.java`；Hikari 无池参数——`leaderboard-service/src/main/resources/application.yml:46-47` 整段只有 `initialization-fail-timeout: -1`（对比 `docs/adr/0002` §1 实测 `maximumPoolSize` 10→30 的调法）。已完成——Nacos 双 starter 在 `leaderboard-service/pom.xml:46,50`，`application.yml:18-19` 已有 `config.import: optional:nacos:leaderboard-service.yml`（正是 `spec.md:34` 要求新增那一步），`:21-26` discovery/config server-addr 齐，`docker-compose.yml:17` nacos-server v2.3.2；actuator 已在 `leaderboard-service/pom.xml:36`（spec 又要"添加"）。验收命令端口错：spec 写 8080，实际 `application.yml:13` 是 8084。
若成立：`leaderboard-service/.../config/ThreadPoolConfig.java`（新）、`leaderboard-service/src/main/resources/application.yml`（hikari 池参数，走环境变量与 compose 注入同口径）
阻塞项：需 Nacos 实例才能验配置迁移（compose 有，需 up）；把 datasource/MQ 配置整体搬进 Nacos 属跨环境动作，需人拍板；Nacos 侧加密建议另立条目

## TASK-016 第三方客户端治理 + 统一 LocalDateTime 格式

前提是否成立：**不成立**（客户端治理已在 `leaderboard-service/.../application.yml:28-35,84-98` + `api/.../UserApi.java:28`）；Jackson 半边**需人拍板**（spec 自相矛盾且爆炸半径覆盖 MQ 线格式）
证据：故障隔离/超时已实装——`application.yml:29-30` `openfeign.circuitbreaker.enabled: true`、`:34-35` `connect-timeout: 1000 / read-timeout: 3000`、`:84-93` `resilience4j.circuitbreaker`（sliding-window 10、失败率 50%、open 10s）与 `:95-98` `timelimiter 4s`（注释自陈"与 record/verify 同参数"）；`api/src/main/java/com/sportverify/api/user/UserApi.java:28` `fallbackFactory = UserApiFallback.class`（文件在位）、`LeaderboardService.java:86` 注入 `UserApi`、`:248` 好友榜降级空榜、`:287` 昵称降级占位；全仓无任何外部第三方 HTTP 调用（`ExternalClient` 无对应真实外部系统）。Jackson 半边：`spec.md:4` "统一为 ISO-8601（yyyy-MM-dd HH:mm:ss）"自相矛盾（空格分隔不是 ISO-8601，Boot 默认输出带 `T`）；全仓 `grep date-format|JsonFormat` 在 yml 与 api DTO 命中 0 → 改的是对外契约，8 份 api DTO 直接挂裸 `LocalDateTime`，前端原样串展示：`web/src/api/client.ts:58,66` `createdAt?: string`、`web/src/pages/friends.page.vue:52` 表格列直读。且 common 里放全局 `ObjectMapper` bean 会被 MQ 链路注入（`record/mq/RecordEventProducer.java:31`、`leaderboard/mq/LeaderboardEventConsumer.java:65`、`verify/mq/VerifyEventProducer.java:29`、`verify/consumer/VerifyEventConsumer.java:59`）→ 等于改 RocketMQ 事件字节格式，影响在途消息与 outbox 重投；`verify/config/RulesSnapshotCodec.java:9-18` 正是为规避"全局 mapper 漂移"才自建独立 mapper，TASK-002 的 D4 也刚删过一个裸 `ObjectMapper` bean。
若成立：仅解决显示 → `web/src/**` 一处格式化；坚持后端全局改 → `common/.../config/JacksonConfig.java` + 4 个 MQ 收发点回归；连接池半边 → 只在 record/verify 补 `spring.cloud.openfeign.httpclient.*` 键，**不是**新建 `FeignHttpClientPoolConfig.java`
阻塞项：需人拍板（契约变更 + MQ 线格式兼容）；`ExternalClient` 无真实外部依赖方，属空建。另有一条未收口的半成品挡在前面：`record-service/src/test/java/com/sportverify/record/config/FeignHttpClientPoolConfigTest.java:56,64-74` 与 `verify-service/src/test/.../config/FeignHttpClientPoolConfigTest.java`（两者均 untracked，主类 `FeignHttpClientPoolConfig.java` 全仓 find 命中 0）断言 `spring.cloud.openfeign.httpclient.max-connections=100` 等键存在，而全仓 `grep -ri httpclient --include=*.properties --include=*.yml` 命中 **0**（record `application.properties:48-54`、verify `application.yml:19-27` 只有 circuitbreaker 开关与 client.config 超时）；连接池本可走 starter 传递的 `feign-hc5` + 自动装配（`.m2-repo/io/github/openfeign/feign-hc5`、`.m2-repo/org/apache/httpcomponents/client5` 均在仓）。实跑已确认这两处 `testCompile` 失败（见文末「附带发现 0」）——**先收这个口，再谈 TASK-016**。

## TASK-017 LeaderboardModule DDD 分层重构

前提是否成立：**需人拍板**（分层确实缺失、spec 的"贫血"判断与代码相符，但方案语言不可编译且会撞 ADR-0009 哨兵）
证据：缺失面——`find leaderboard-service/src -name "*.java"` 只有 config/controller/entity/enums/mapper/mq/scheduler/service 八个包，无 `domain/`；单类承载多职责属实（`LeaderboardService.java:106 applyVerified`、`:167 rollbackOnRejected`、`:205/:225/:242` 读路径、`:324 settleAndReconcile`）。阻断面——spec 的值对象/聚合/事件全是 Kotlin（`spec.md:51-125` 的 `data class`/`sealed class`/`entries`），全仓 `grep kotlin` 在所有 pom.xml 命中 0 → 这些代码一行都进不了构建；重构写路径必碰哨兵：`leaderboard-service/src/test/.../LeaderboardWritePathStaysUnproxiedTest.java:23,33-37` 断言 `LeaderboardService`/`LeaderboardController` "不得被 Spring 代理"，依据 `docs/adr/0009-事务边界.md:37-38,:53`（禁止把 Redis ZSet 纳入本地事务、禁止批量铺 `@Transactional`），而 `:28-29` 自陈"一旦有人补上 @Transactional，本类立刻变红"。
若成立：先出 ADR（分层边界 + 与 ADR-0009 关系）；最小一片：`leaderboard-service/.../domain/`（Java 值对象，非 Kotlin）+ `LeaderboardService.java` 委托改造
阻塞项：需人拍板（大范围不可逆重构、语言选型 Java/Kotlin、与 ADR-0009 哨兵的冲突如何解）

## TASK-019 网关流量染色 / 镜像 / 优先级

前提是否成立：**成立**（限流半边已在，染色/镜像/优先级三项零实现）
证据：已在——`gateway-service/pom.xml:37,41,49,54`（sentinel starter、sentinel-gateway、datasource-nacos/extension）、`SentinelGatewayRuleConfig.java:68-94`（启动 `loadRules` 兜底 + `register2Property` Nacos 动态源，按 `ROUTE_IDS` 生成 `FLOW_GRADE_QPS`）、`gateway/.../application.yml:11-22,121-130`。缺——`grep -riE "gray|canary|mirror|repeat|weight|metadata"` 在 `gateway-service/src` 命中 **0**；`RequestIdGlobalFilter.java:32-43` 只做 X-Request-Id 生成与头透传，不携带路由语义，不构成染色。
若成立：`gateway-service/.../filter/GrayTagGlobalFilter.java`（新，按 header/用户段打版本标）、`gateway-service/src/main/resources/application.yml`（路由 predicates/metadata 分流）、`SentinelGatewayRuleConfig.java`（若优先级要复用规则源）
阻塞项：优先级控制与镜像在 Sentinel SCG 适配器里没有对应物——`.m2-repo/com/alibaba/csp/sentinel-spring-cloud-gateway-adapter/1.8.6/…jar` 全部 13 个类只有 `SentinelGatewayFilter`、`RouteMatchers`/`Ant|RegexRoutePathMatcher`、`GatewayApiMatcherManager`/`WebExchangeApiMatcher`、`BlockRequestHandler`/`Default|RedirectBlockRequestHandler`/`GatewayCallbackManager`、`SentinelGatewayBlockExceptionHandler`、`ServerWebExchangeItemParser`，无镜像/优先级语义；且版本被 `pom.xml:43`（SCA 2023.0.1.0）与 ADR-0001 §1 锁死在 1.8.6 → 做哪一项、用什么做，需人拍板；镜像还需下游双份接收端

## TASK-020 历史数据归档 / 冷热分离

前提是否成立：**需环境**（缺口真实，但 spec 指定的落地路径在本项目不存在）
证据：`find . -path "*db/migration*"` 命中空、全仓 `grep -i "flyway|liquibase"` 在 pom/yml 命中 **0** → `spec.md:11` 的 `src/main/resources/db/migration/V10__archive_old_data.sql` 落在一个无迁移框架的目录约定上；本项目迁移手法是手工幂等 SQL + information_schema 守卫（`sql/migrations/add-idx-record-seq.sql`，`docs/adr/0002` §2 记录了为何**故意不**挂进 `docker-entrypoint-initdb.d` 以便复现基线）；`grep -i archive sql/` 命中 0 → 归档表不存在；leaderboard src 无 `ArchiveService`。规模依据不足：`LeaderboardService.java:73` 注释自陈演示规模 1000 用户。
若成立：`sql/migrations/`（新表 + 搬移脚本）、`leaderboard-service/.../service/ArchiveService.java`、`LeaderboardService.java` 或独立 scheduler（复用 `settleAndReconcile` 同款 Redisson 锁口径）
阻塞项：需测试库（真 MySQL 跑 EXPLAIN 与搬移验证，同 TASK-009 的阻塞）；保留期/归档口径需产品拍板

## TASK-021 缓存穿透/击穿/雪崩 + 内存管控

前提是否成立：**不成立**（三防护已在 `verify-service/.../service/RuleCacheService.java`，并由 ADR-0008 记账）；leaderboard 侧仅缺两项，需重定范围
证据：`RuleCacheService.java:53`（互斥锁前缀 `lock:rule-rebuild:`）、`:69 get(...,Supplier dbLoader)` 统一读路径、`:134-161` Redisson `tryLock` + Double-check + 拿不到锁降级无锁读；`docs/adr/0008-二级缓存.md` 决策 §2 逐条写明"穿透（空值哨兵，短 TTL 5s）/击穿（互斥重建）/雪崩（TTL 60s±10s 抖动）都落在 Redis 层"，§4 还有 `cache.two-level.enabled` 总开关。内存/TTL 在 leaderboard 也已有：`CacheConfig.java:49`（`CACHE_TTL=5min`）、`:76`（`maximumSize(1000)`）、`:77`、`:86-89`（Redis 侧 TTL 与序列化）。leaderboard 真缺的只有：`CacheConfig.java:175-188` 的 `get(key, Callable)` 无互斥（击穿）、两层统一固定 5min 无抖动（雪崩）。静态检查半边是另一回事：`pom.xml` 现有插件只有 compiler（`:186`）、enforcer（`:197`）、boot（`:218`）、jacoco（`:231`），Checkstyle/PMD/Spotbugs 命中 0 → 缺口真实但与"缓存"无关，混在一条任务里属范畴错配。
若成立：`leaderboard-service/.../config/CacheConfig.java`（补互斥重建 + TTL 抖动）；静态检查另开一条
阻塞项：无（可单测覆盖）；需人拍板"抽 `RuleCacheService` 到 common 复用"还是允许 leaderboard 二次实现

## TASK-022 网关灰度发布

前提是否成立：**需人拍板**（spec 自身只授权改文档，定义上不带真代码；且项目已有一套在跑的业务灰度）
证据：`spec.md:7-8` "只改文件"= `spec.md` + `handoff.md` → 按 PLAN 步骤 3 已记的教训，这种派发词必然只产出文档（本轮磁盘核对：`gateway-service/src` 无任何 gray 相关 java/yml，见下）。工程前提齐备：`gateway-service/pom.xml:26` nacos-discovery、`:30` loadbalancer，`application.yml:28-75` 六条路由全为 `lb://`。真缺实现：`grep -riE "gray|canary|weight"` 在 `gateway-service/src` 命中 **0**，`spec.md:24` 设想的 `lb://leaderboard-service-canary` 服务名在 compose/Nacos 里都不存在。重复建设风险：`docs/adr/0004-规则灰度发布.md` 已实装 `floorMod(userId,100)<gray_ratio` 采样 + Redis 广播秒级回滚，`sql/03-verify-db.sql:44` `rule_version.status` 即 0 GRAY/1 ACTIVE/2 RETIRED。
若成立：`gateway-service/.../filter/GrayRouteFilter.java`（新）、`gateway-service/src/main/resources/application.yml`（版本路由 + metadata）、+ 一个按 metadata 过滤实例的 `ReactorServiceInstanceLoadBalancer`
阻塞项：需环境（灰度分流要有第二版本实例注册到 Nacos，本地单实例无法验证）；需人拍板（与 ADR-0004 业务灰度是否重复）

## TASK-023 部署运维（Docker/K8s/健康检查）+ 敏感数据脱敏

前提是否成立：**不成立**（Dockerfile 六个全在、健康检查全在、脱敏已在 user-service；spec 指定的落点无敏感字段可标）
证据：`find` 到 `gateway-service/Dockerfile`、`user-service/Dockerfile`、`record-service/Dockerfile`、`verify-service/Dockerfile`、`leaderboard-service/Dockerfile`、`mapmatch-service/Dockerfile`——`spec.md:7` 要"新建"的正是已存在的那个；compose 已按仓库根上下文引用六份（`docker-compose.services.yml:17-19,45-47,75-77,112-114,144-146,176-178`）。actuator 已在 `leaderboard-service/pom.xml:36`；健康检查明细已在 6 份配置（见 TASK-013 核对的 `show-details` 各行号）。脱敏已实装且在真实 PII 路径上：`user-service/.../AuthService.java:305 maskPhone`（`:106,:118,:134,:277,:283` 调用）、`InternalUserController.java:56` + `:48` 下发前脱敏，`docs/adr/0007-鉴权设计.md:10` 把它记为既有惯例。而 `spec.md:11` 要点 `@Masked` 的实体字段只有 `LeaderboardContribution.java:30-42`（recordId/userId/distance/status/settledAt）与 `SportRecordSnapshot.java:21-30`（id/userId/distance/status），`LeaderboardDTO` 只有 rank/userId/nickname/distance → 零个手机/证件/邮箱字段。真缺只有 K8s：`find` 全仓无 `k8s/` 目录、无 deployment.yaml。
若成立：唯一真缺口 `leaderboard-service/k8s/deployment.yaml`（宜抽一份跨服务模板）；`@Masked` 若仍要做，落点是 `common/.../annotation/Masked.java` + `common/.../util/SensitiveUtils.java`，并把 user-service 的两处 `maskPhone` 换掉
阻塞项：需 K8s 集群（本机无，`kubectl apply` 无从执行）；需人拍板是否用"反射遍历"方案替换已验证的手写 `maskPhone`

## TASK-024 缓存预热 + 空值不穿透 DB

前提是否成立：**不成立**（回源入口已在 `CacheConfig.java:175-188`，`@Cacheable` 已在 `LeaderboardService.java:223-224`；且该方法全程不碰 DB，"穿透 DB"这个缺陷不成立）
证据：`grep -rn getTopRecords --include=*.java .` 命中 **0** → `spec.md:11` 要"找的方法"不存在，实为 `LeaderboardService.java:225 topOverall(int size)`，`:223` 已带 `@Cacheable(value="leaderboard:overall", cacheManager="hierarchicalCacheManager", key="#size")`；回源 loader 入口 `CacheConfig.java:175 <T> T get(Object key, Callable<T> valueLoader)`；"5 分钟失效"由 `:49 CACHE_TTL=Duration.ofMinutes(5)` 满足。穿透对象错：`:226-227` 只读 Redis ZSet（`reverseRangeWithScores`），DB 聚合仅发生在结算任务 `:333 selectActiveSummaries` → 空榜重读的是 Redis，不是 DB。真剩下的只有 `:224 unless = "#result == null || #result.isEmpty()"` 导致空结果不入缓存。预热确实没有：全仓 `grep -E "ApplicationRunner|warm"` 在 `*/src/main` 只命中 `SentinelGatewayRuleConfig.java:69`（装限流规则，与缓存无关）。另 `spec.md:4` 写"在 TASK-024 基础上"是同义自指，`spec.md:36` 又说"需先完成 TASK-002"——TASK-002 已按 D4 验收通过。
若成立：`LeaderboardService.java:224`（去掉 unless，让空榜入缓存）+ `CacheConfig.java`（空值短 TTL 哨兵，手法照搬 `RuleCacheService`）
阻塞项：需人拍板——`docs/adr/0008` "已知边界"已记过"Caffeine 空值哨兵沿用共享缓存 TTL、不可按 key 设独立过期"这个坑，本任务是重复踩；预热需 Redis 真连接才能验

## TASK-025 网关 Sentinel QPS 限流（无 spec.md，仅 handoff）

前提是否成立：**不成立**（该能力已在 `gateway-service/.../config/SentinelGatewayRuleConfig.java:68-94`）→ 任务应作废，并与 TASK-019 的限流半边合并
证据：四个依赖齐备 `gateway-service/pom.xml:37,41,49,54`；`SentinelGatewayRuleConfig.java:72` 启动即 `GatewayRuleManager.loadRules(defaultRules())` 兜底、`:76-78` 注册 `NacosDataSource` 并 `register2Property` 成动态源、`:51` `@Value("${app.gateway.rate-limit.qps:5000}")`、`:84-93` 按 `ROUTE_IDS`（`:46-48`）生成 `FLOW_GRADE_QPS` 规则；yml 侧 `gateway-service/src/main/resources/application.yml:11-22` 与 `:121-130`；1.8.6 与 `pom.xml:43`（SCA 2023.0.1.0）及 ADR-0001 §1 锁定矩阵一致。handoff 的四条自述（依赖存在 / yml 完整 / data-id 正确 / 降级响应）逐条现场复核为真，无夸大。**但**：`work/mailbox/tasks/TASK-025/` 下无 `spec.md`，只有 `handoff.md` → 该任务无验收口径可依。
若成立：——（不适用，判定为已存在）
阻塞项：要验收"改 Nacos 阈值不重启即生效"需 up Nacos（`docker-compose.yml:17`）→ 属需环境，非需写码

---

## 附带发现（体检中撞见的三处与既有记录不符，未做任何修改）

0. **【当前 `mvn test` 在 record/verify 两个模块是红的】**（只读体检，未修）两个 untracked 测试类引用了没有任何 pom 声明的依赖，`testCompile` 直接失败：
   ```
   # 第一条命令：record 先失败，verify 被 SKIPPED；故再单独跑 verify 取证
   mvn -B -ntp -o -s .mvn-settings.xml -pl record-service,verify-service -am test -Dtest=FeignHttpClientPoolConfigTest
   mvn -B -ntp -o -s .mvn-settings.xml -pl verify-service -am test -Dtest=FeignHttpClientPoolConfigTest
   [ERROR] record-service/src/test/.../config/FeignHttpClientPoolConfigTest.java:[7,17] 程序包feign.hc5不存在   → sport-verify-record-service FAILURE
   [ERROR] verify-service/src/test/.../config/FeignHttpClientPoolConfigTest.java:[7,17] 程序包feign.hc5不存在
   [ERROR] verify-service/src/test/.../config/FeignHttpClientPoolConfigTest.java:[8,42] 程序包org.apache.hc.client5.http.impl.io不存在
   [ERROR] verify-service/src/test/.../config/FeignHttpClientPoolConfigTest.java:[9,37] 程序包org.apache.hc.client5.http.io不存在   → sport-verify-verify-service FAILURE
   ```
   两侧 import 完全相同（各自 `:6-9`），而 `grep -rn "hc5\|httpclient5" --include=pom.xml .` 命中 **0**；`feign-hc5` 与 `httpclient5` 的 jar 却在 `.m2-repo/io/github/openfeign/feign-hc5`、`.m2-repo/org/apache/httpcomponents/client5` 里——即"依赖曾存在、pom 已回退、测试文件留下"。注意影响面：PLAN.md line 9-11 那次 37/37 是 `-pl leaderboard-service -am`，不含 record/verify，所以没撞上；但根目录全量 `mvn test`（以及 ADR-0008 结尾自陈的"`mvn clean install` 全绿"）在当前工作树不可复现。**处置需人拍板**：补 `feign-hc5` 依赖 + `spring.cloud.openfeign.httpclient.*` 配置（测试断言的正是这些键，全仓命中 0），还是删掉这两个测试（它们是 untracked，删前须存 patch，且按记忆 `git -c core.autocrlf=false apply` 才能原样还原）。

1. **`leaderboard-service/pom.xml:126-130` 用 `<excludes>` 把 `**/scheduler/DailyLeaderboardReportJob.java` 排除在编译之外**，同时该文件 import `xxl.job.core.*`（`:6-7`，官方包名是 `com.xxl.job.core`），声明的依赖 `com.xuxueli:xxl-job-core:2.4.0` 在本地仓库 `.m2-repo` 里 find 不到，而 `leaderboard-service/src/main/resources/application.yml:124-137` 还留着 `xxl.job.enabled: true` 一整段配置。也就是说：src/main 里躺着一个不参与构建、包名错误、依赖缺失的文件，而配置看起来像已启用。TASK-004（D5 待拍板）与 TASK-007 的判断都必须以"该文件当前对构建不可见"为前提。
2. **PLAN.md D11 记的"`TransactionConfigTest` 改名 `TransactionManagerWiringTest`"与磁盘不符**：`leaderboard-service/src/test/java/com/sportverify/leaderboard/config/` 下现存 `TransactionConfigTest.java`（内容确为 D9 简化后的"自动装配 TM 真绑 DataSource"版本），全仓不存在 `TransactionManagerWiringTest`。D9/D11 里"config/ 只剩 CacheConfig.java"与"`src/main` 内 `Transactional` 命中 0"两条本轮复验为真。
