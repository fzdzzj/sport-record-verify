# TASK-268 立项任务书（代码规范清零与容量告警/网关写链路限流防御加固）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-18 指导亲核三块全 PASS，七门禁全绿，予以封盘）

## 1. 证据链（实测暴露与指导侧亲验坐实，2026-10-18）

1. **存量 Checkstyle 违规（代码卫生与静态门禁历史遗留）**：
   - 执行 `scripts/verify/mvn-verify.sh --mode=offline --static=record-service` 始终以退出码 1 失败；
   - 审查违规明细：恰 4 项违规全部位于 `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java:1076`，原因为 `DerivedTrackMetrics` record 缺少其 4 个组件参数（`startTime`, `endTime`, `durationSeconds`, `distanceKm`）的 `@param` Javadoc 标签（自 TASK-234 遗留至今）；
   - 补齐该 4 行注释可彻底使 `record-service` 静态检查违规归零，达成 `BUILD SUCCESS`（退出码 0），消除全仓唯一的遗留静态检查缺陷。
2. **Prometheus 告警规则缺少压测容量锚点（TASK-265 容量报告建议落地）**：
   - TASK-265 全链路端到端压测实测（`docs/perf/benchmark-e2e-capacity-report.md` §5.2、§7.4）沉淀出关键容量监控锚点：
     ① 在写高峰时 `verify-service` HikariCP 活跃连接频繁触顶（10/10），若持续超过 30s 即为异步校验链消化速率失配、积压延误的直接信号；
     ② 500 并发下由于底层连接池饱和，网关触发 10s 超时抛出 504 响应，504 出现即代表写链路过载；
   - 目前 `prometheus/alert-rules.yml` 仅覆盖实例下线、5xx 错误率与堆使用率，缺少上述容量防御规则，需扩充落地。
3. **网关写链路限流防御加固（高并发防雪崩）**：
   - 目前 `gateway-service` 的 `SentinelGatewayRuleConfig.java` 对全部 7 个路由统一采用全局默认 `app.gateway.rate-limit.qps: 5000` 兜底；
   - 但真实压测实证表明，单机单实例 `record-service` 在约 88 QPS 时已达到数据库连接池瓶颈；5000 的统一兜底在极端流量下无法阻断写过载，反而让请求大量堆积至 10s 网关超时；
   - 增加对 `route-record-service` 等特定路由支持独立限流 QPS 配置（默认设为 200 QPS，对齐 TASK-265 报告单机安全水位并保留峰值弹性），使过载流量由 Sentinel 快速返回标准 HTTP 429 优雅背压，配合完善单测守卫。

## 2. 改动白名单（仅限下列文件，双向全等）

- **C-01 业务/配置/测试（5 文件）**：
  1. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（R1 补齐 DerivedTrackMetrics 4 项 @param 注释）
  2. `prometheus/alert-rules.yml`（R2 扩充容量告警规则组）
  3. `gateway-service/src/main/java/com/sportverify/gateway/config/SentinelGatewayRuleConfig.java`（R3 增加特定路由差异化限流 QPS 兜底支持）
  4. `gateway-service/src/main/resources/application.yml`（R3 增加特定路由限流配置项声明与说明）
  5. `gateway-service/src/test/java/com/sportverify/gateway/config/SentinelGatewayRuleConfigTest.java`（R3 增补差异化路由限流规则解析与生效单测）
- **C-02 台账（1 文件）**：
  6. `work/mailbox/tasks/TASK-268/handoff.md`（实施回填）

零触碰面声明：除上述 5+1 文件外，全仓其他微服务生产 Java 源码、既有单测、pom.xml、package.json、前端 web/、docker-compose、sql/ 等 100% 零触碰。

## 3. 修法要求

- **R1（record-service 存量 Checkstyle 清零）**：
  - 在 `SportRecordService.java:1075` 的 `DerivedTrackMetrics` record 补充 4 个 `@param` 标签：
    `@param startTime 开始时间（东八墙钟）`、`@param endTime 结束时间（东八墙钟）`、`@param durationSeconds 运动时长（秒）`、`@param distanceKm 运动距离（公里）`；
  - 确保 `scripts/verify/mvn-verify.sh --mode=offline --static=record-service` 静态检查达成 0 violations、`BUILD SUCCESS`（退出码 0）。
- **R2（Prometheus 容量告警规则扩充）**：
  - 在 `prometheus/alert-rules.yml` 中新增告警组 `sport-verify-capacity`：
    1. `VerifyHikariPoolSaturated`：表达式 `hikaricp_connections_active{job="verify-service"} >= 10`，持续 30s，级别 `warning`；
    2. `GatewayWrite504Spike`：表达式 `sum(rate(http_server_requests_seconds_count{job="gateway-service",status="504"}[1m])) > 0`，持续 1m，级别 `critical`。
- **R3（网关写链路差异化限流支持与单测）**：
  - 在 `SentinelGatewayRuleConfig.java` 引入特定路由的差异化限流支持（例如 `@Value("${app.gateway.rate-limit.routes.route-record-service.qps:200}") private long recordRouteQps;`）；
  - `defaultRules()` 生成规则时，为 `route-record-service` 赋予其专属阈值（默认 200），其余 6 个路由继续保持全局 `qps`（5000）；
  - `application.yml` 显式声明该配置项及设计注释；
  - `SentinelGatewayRuleConfigTest.java` 增加单测断言：在存在路由差异化配置时，`route-record-service` 规则精确匹配专属阈值，其余路由为全局默认值，且所有路由均未遗漏；
  - 确保 `SentinelRouteCoverageTest` 既有用例保持 100% 通过；
  - surefire `gateway-service` 测试计数 78 -> **79**（只增不减，全仓 1034 → 1035）。

## 4. 门禁与验证要求（MEDIUM 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`，全量七模块 surefire 基线 **63/78/221/323/203/110/36** 只增不减（gateway-service 78→79，净增 1 测，总计 1035 测，零失败零跳过）；
- Checkstyle 门禁：`scripts/verify/mvn-verify.sh --mode=offline --static=record-service` 实测通过（0 violations，BUILD SUCCESS，退出码 0）；
- 契约门：`scripts/verify/mailbox-contract.sh --open=TASK-268 --baseline=c27e94b`；
- token 门 29 项逐位持平零减少（期望值口径：**C-02 树 = 2249 + 29 = 2278**）；
- 词面门模式文件法 + 探针阳性对照（四 locale 全 ZERO_HIT）；
- checkstyle delta=0；
- `git diff --check` 净；
- 双提交 C-01（业务/配置/测试）/ C-02（台账）；`git status --porcelain` 收口为空；
- MEDIUM 档由指导侧亲核三块（Git 摘要、高风险定向抽查、门禁与读数裁决）把关，免独立复核子 agent。

## 5. 受限声明（预登记）

1. 本任务专注代码规范清理、运维告警扩展与网关流控配置加固，不改变核心微服务业务交互接口；
2. 保持依赖纯净，不引入任何新的外部库。

## 6. 停止条件与升级路径

- 若修改导致既有测试断言失败，立即停手排查；
- 若 `SentinelRouteCoverageTest` 或其他网关测试红，立即停手；
- 严禁触碰白名单外任何文件。

## 7. 验收判据

- C-01+C-02 双提交落库、白名单双向全等；
- `record-service` Checkstyle 违规数清零（0 违规，退出码 0）；
- 七门禁全绿，surefire gateway-service 净增 +1（78→79，总测试 1035）；
- 指导亲核三块全 PASS。
