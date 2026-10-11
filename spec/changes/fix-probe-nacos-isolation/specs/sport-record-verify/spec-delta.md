# spec-delta：探针测试 Nacos 隔离（TASK-199 fix-probe-nacos-isolation）

## MODIFIED 需求：探针测试环境隔离（TASK-198 R3 探针测试的修正）

六服务探针测试（`*ProbeTest.java`）SHALL 不依赖任何外部中间件：凡上下文触达 Nacos 的测试 SHALL 以测试属性 `spring.cloud.nacos.discovery.enabled=false` + `spring.cloud.nacos.config.enabled=false`（或等价自动配置排除）隔离注册中心；既有断言语义（liveness UP / readiness 组结构 / 禁 db-redis-UP 断言）与 accommodation 结构（@Import / ProbeApp / @Primary mock）零改动。

## 验收断言

- CI 等效无中间件口径下六探针测试全绿（属性模拟，禁停共享中间件容器）
- offline 609 逐位不减；static record/leaderboard rc=0；契约门双态 rc=0；token 2030 只增不减
- 只改清单：C-01 仅 `*ProbeTest.java`（至多 6 文件）；C-02 恰 4 台账文件；零触碰主源/配置/pom/ci.yml/compose/scripts
- 第 40 次 CI 全绿为外部终验；红则按签名归因禁重试
