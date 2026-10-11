# spec-delta：探针测试环境隔离 v2（TASK-199 fix-probe-nacos-isolation 改派）

## MODIFIED 需求：探针测试环境隔离（TASK-198 R3 探针测试的修正）

六服务探针测试（`*ProbeTest.java`）SHALL 不依赖任何外部中间件：上下文急切创建中间件客户端的服务（Redisson 等）SHALL 以 @Primary mock accommodation 隔离（VerifyProbeTest.ProbeTemplateConfig 范式，自动配置让位）；Nacos 依赖以既有 fail-fast=false 降级（gateway CI 实跑已证），双禁用属性为备选第二手段。既有断言语义（liveness UP / readiness 端点 / 组结构静态断言 / 禁断言 db-redis UP）与既有 accommodation（@Import / ProbeApp）零改动；新增 mock accommodation 为本 delta 授权范围。

## 验收断言

- CI 等效无中间件口径下六探针测试全绿（属性模拟，禁停共享中间件容器；不以本机中间件在跑作任何 PASS 依据）
- offline 609 逐位不减；static record/leaderboard rc=0；契约门双态 rc=0；token 2030 只增不减
- 只改清单：C-01 主 1 文件（UserProbeTest.java；例外通道至多 6 个 `*ProbeTest.java`）；C-02 恰 4 台账文件；零触碰主源/配置/pom/ci.yml/compose/scripts
- 第 40 次 CI 全绿为外部终验（user 档必须实跑绿；四档若仍 SKIPPED 以本地等效读数登记不阻断）；红则按签名归因禁重试
