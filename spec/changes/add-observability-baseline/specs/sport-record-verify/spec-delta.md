# spec-delta：可观测性基线建设（TASK-198 add-observability-baseline）

## ADDED 需求：R1 定时任务 traceId 收口

六个 @Scheduled 入口（VerifyOutboxRelay relay 与批内 worker、VerifyDegradeService、RecordLikeService flush 与对账、TrackPointArchiveService、LeaderboardService 结算）SHALL 以 try/finally + TraceIds.put/clear 包裹任务体（relay 以 outbox 行 traceId 续链，其余入口生成新 UUID 去横线值），保证任务日志 MDC traceId 非空且 finally 清理；零方法签名改动、零业务逻辑改动。

## ADDED 需求：R2 结构化日志（双 appender）

六服务 SHALL 各建 logback-spring.xml：console appender 保留现人读 pattern 逐字不变；file appender 使用 LogstashEncoder（JSON），MDC traceId 自动并入字段，springProperty 注入应用名；父 pom dependencyManagement SHALL 锁 net.logstash.logback:logstash-logback-encoder:7.4（logback 1.4.14 兼容线，禁 8.x）。record 服务配置只落 application.properties。

## ADDED 需求：R3 健康探针深化

六服务 SHALL 显式开启 `management.endpoint.health.probes.enabled=true` 并配置 readiness 组：gateway 仅 readinessState；user/verify/leaderboard 加 db,redis；record/mapmatch 加 db。liveness 组不挂外部依赖；show-details=never 维持；MQ 不进组（无官方 indicator）。gateway 白名单 SHALL 精确追加 `/actuator/health/liveness,/actuator/health/readiness`。探针测试只断言 liveness UP 与组结构存在，禁断言 db/redis UP（CI 无中间件）。

## 验收断言

- 三批序贯全门禁（offline 只增不减 + static 触源模块 rc=0）；checkstyle 9.3 口径（80 列/FinalParameters/Javadoc 全参）零新增违规
- 只改清单：C-01 按类别清单（批1 Java 主源 5 + 新增单测、父 pom 1、logback-spring.xml ×6、application 配置 ×6、探针测试 ×6，≤29 文件）；C-02 恰 4 台账文件
- 零触碰：ci.yml、docker-compose*、scripts/verify/*、web、sql、ADR、api/common 模块
- token 29 项 tracked git grep -cF 权威口径只增不减；台账纯追加；第 39 次 CI 全绿为外部终验
