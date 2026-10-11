# 提案：可观测性基线建设（add-observability-baseline / TASK-198）

## 背景与问题

侦察实证：traceId 链路（X-Request-Id + MDC）已依 ADR-0003 既定路线贯穿 6/6 服务（HTTP/Feign/MQ 全通），本课题不重建链路，收口三个真实缺口：①定时任务/补偿链路 MDC 补口（relay/降级/点赞 flush 与对账/轨迹归档/榜单结算 6 处 @Scheduled 入口日志 traceId 为空）；②结构化日志全仓为零（无 JSON file appender）；③健康探针 probes/health groups 全仓为零（liveness/readiness 未分离，依赖状态不可探）。

## 路线裁决（依侦察，延续 ADR-0003）

路线乙（既有自研链收口 + 深化）。路线甲（Micrometer Tracing）否决依据：ADR-0003 明文不引入、离线仓无实体 jar、RocketMQ 端零收益（四消费者为原生客户端）、SCG 端需全局 Reactor 开关。若未来需 span 级观测，另立课题修订 ADR，不搭车。

## 三批交付（序贯，每批全门禁）

- **批 1 traceId 链路收口**：5 个主源文件 @Scheduled 入口补 MDC 包裹（try/finally + TraceIds.put/clear，零签名改动）+ 对应纯 JVM 单测（Mockito 打桩范式已有先例）
- **批 2 结构化日志**：父 pom dependencyManagement 锁 logstash-logback-encoder 7.4（Boot 3.2.4=logback 1.4.14 兼容线，禁 8.x）+ 六服务各建 logback-spring.xml（console 保留人读 pattern、file 走 JSON encoder，MDC traceId 自动并入字段，springProperty 注入应用名）+ 六服务 application 配置 logging.file；开工一次性在线补仓后门禁照常 offline
- **批 3 健康探针深化**：六服务 `management.endpoint.health.probes.enabled=true` + readiness 组按真实依赖裁剪（gateway=readinessState；user/verify/leaderboard=+db,redis；record=+db；mapmatch=+db）+ gateway 白名单精确追加 `/actuator/health/liveness,/actuator/health/readiness` + 每服务探针测试（只断言 liveness UP 与组结构，禁断言 db/redis UP——CI 无中间件）

## 边界与约束

- 改动面：C-01 ≈ 29 文件上限（批 1 Java 主源 5 + 新增单测 ≈5、父 pom 1、logback-spring.xml ×6 新建、application 配置 ×6、探针测试 ×6）；C-02 恰 4 台账文件
- 零触碰：ci.yml、docker-compose*、scripts/verify/*、web、sql、ADR、api/common 模块（TraceIds 零改动）、业务语义（MDC 包裹零签名零逻辑改动）
- record 配置只落 application.properties（snakeyaml 冲突，勿建 yml）；show-details=never 维持；liveness 组不挂外部依赖；MQ 不进 health 组（无官方 indicator，自研列后续）
- validate-group-membership 默认 true：include 必须与该服务真实依赖严格一致，跨服务复制即启动失败（属预期防线）
- offline 基线 569 只增不减（预计 +11 左右）；触 record/leaderboard 源码受 checkstyle 9.3 口径（80 列/FinalParameters/Javadoc 全参）与 SpotBugs failThreshold=High 约束
- 零性能数字 claim；台账纯追加；第 39 次 CI 全绿为外部终验
