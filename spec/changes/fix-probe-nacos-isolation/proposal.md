# 提案：探针测试 Nacos 环境隔离修复（fix-probe-nacos-isolation / TASK-199）

## 背景与问题

第 39 次 CI 红（run 38113472212）：TASK-198 新增的 UserProbeTest 3 例 `Failed to load ApplicationContext`，根因 Nacos gRPC `127.0.0.1:9848` Connection refused——探针测试拉全上下文（RANDOM_PORT）但未隔离注册中心，CI 无 Nacos 即红；本地绿为本机 Nacos 在跑的假绿（环境依赖泄漏）。verify/leaderboard/mapmatch 探针在 CI 被 SKIPPED 从未跑到，大概率同病；record 用 ProbeApp 定制上下文可能幸免；gateway 探针在 CI 实跑已绿。

## 修复方案

六服务探针测试统一补 Nacos 隔离：测试属性 `spring.cloud.nacos.discovery.enabled=false` + `spring.cloud.nacos.config.enabled=false`（或等价自动配置排除，以各测试现有属性机制为准——UserProbeTest 已有 `spring.cloud.nacos.discovery.fail-fast=false` 属性段，就地扩展）。执行侧须以「CI 等效无中间件」口径验证（属性指向不可达地址模拟，勿停共享中间件容器）：六探针测试全绿 + 上下文可起。既有 569+40 用例零回归。

## 边界与约束

- 改动面：C-01 仅探针测试文件（至多 6 个 `*ProbeTest.java`，实际按需——CI 已绿的 gateway 可不动）；C-02 恰 4 台账文件
- 零触碰：一切主源（src/main）、配置文件、pom、ci.yml、compose、scripts；探针测试的既有断言语义（liveness UP / 组结构 / 禁 db-redis-UP）零改动，只补隔离属性
- offline 609 逐位不减不增（属性修复不加用例）；token 2030；static/契约门照常（测试文件不在静态门范围但 record/leaderboard checkstyle 含测试源——需过既有口径）
- D3 修复课题不搭车（user bean 注册 + fastjson/rocketmq-spring 兼容 + N-1 record readiness 口径，另立课题）
- 第 40 次 CI 全绿为外部终验；红则继续按签名归因禁重试
