# 归档：前提不成立的 8 个方向

判定来自 `../triage.md`（P2 前提体检，2026-09-20，逐条现场 grep/读文件，未采信 handoff 自述）。
这里**不删任何文件**，只是移出待办队列：归档的是"照原 spec 做＝重复建设或空建"这一判断，
每节末尾的**真缺口**是仍值得做的部分，要做得重开任务、换范围。

| 任务 | 作废的原因（能力已在） | 真剩下的缺口 |
|---|---|---|
| TASK-007 | `@Scheduled` 体系已在跑（`RecordApplication.java:22` + 3 条真任务，均带 Redisson 锁）；XXL-JOB 无 executor、无 admin、jar 未 vendored | 无（原 spec 要的正是空桩 + 注释配置，与 D5 判死的形态同构） |
| TASK-012 | 网关路径准入已在 `AuthGlobalFilter.java:53,61,95`；BCrypt 已在 `AuthService.java:54,112,148`，且 ADR-0007 明确只引 `spring-security-crypto` | 无；若要网关侧引 WebFlux Security，得先改 ADR-0007 |
| TASK-014 | 判定不是"缺依赖"而是**构件不存在**：Cloud 2023.0.1 BOM 里 `sleuth` 命中 0；现状是自建最小 traceId（`TraceIds.java:8`、`RequestIdGlobalFilter.java:23`） | 要做是"换方案"：Micrometer Tracing + zipkin 容器 + 6 份 pom/yml，需选型与采样率拍板 |
| TASK-016 | Feign 超时/熔断/降级已在（`application.yml:29-35,84-98`、`UserApi.java:28` fallbackFactory）；全仓无真实外部 HTTP 依赖方 | Jackson 全局日期格式属**契约变更**（8 份 api DTO + 4 个 MQ 收发点会改线格式）；连接池只需补 `spring.cloud.openfeign.httpclient.*` 键，不是新建生产类 |
| TASK-021 | 穿透/击穿/雪崩三防护已在 `RuleCacheService.java:53,69,134-161`，ADR-0008 记了账；leaderboard 的 TTL 与容量也已在 `CacheConfig.java:49,76` | 只有两项：`CacheConfig.java:175-188` 缺互斥重建、两层 TTL 无抖动；另建议先定"抽 `RuleCacheService` 到 common 复用"还是允许二次实现。静态检查（Checkstyle/PMD）与缓存无关，应另开条目 |
| TASK-023 | 6 个 Dockerfile 全在、健康检查 6 份配置全在、脱敏已在真实 PII 路径（`AuthService.java:305 maskPhone` + `InternalUserController.java:48,56`）；spec 要点标 `@Masked` 的字段里**零个**敏感字段 | 只缺 K8s 编排（本机无集群，`kubectl apply` 无从执行）；用反射替换已验证的手写 `maskPhone` 需单独拍板 |
| TASK-024 | spec 要"找"的 `getTopRecords()` 全仓命中 0，实为 `topOverall`（`LeaderboardService.java:223-225` 已带 `@Cacheable`）；该方法只读 Redis ZSet，**"空值穿透 DB"这个缺陷在此路径不成立** | 两条：`:224` 的 `unless` 让空榜不入缓存（需空值短 TTL 哨兵，但 ADR-0008 已记过"Caffeine 共享 TTL 无法按 key 设独立过期"这个坑，属重复踩）；预热确实没有，验它需真 Redis |
| TASK-025 | Sentinel QPS 限流已在 `SentinelGatewayRuleConfig.java:68-94`（启动兜底 + Nacos 动态源），handoff 四条自述逐条复核为真；且该目录**没有 spec.md**，无验收口径可依 | 无；应作废并并入 TASK-019。"改 Nacos 阈值不重启即生效"属需环境验证，不是需写码 |

另注：TASK-003（RabbitMQ 平行栈）、TASK-006、TASK-004（XXL 死桩）同样是前提不成立，但它们已按
PLAN.md 的 D1/D3/D5 实际执行过回滚，目录留在 `../tasks/` 作为改动历史，不在此归档。
