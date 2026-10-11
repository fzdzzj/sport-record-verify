# 提案：探针测试环境隔离修复 v2（fix-probe-nacos-isolation / TASK-199 改派）

## 背景与问题（v2 归因勘误）

第 39 次 CI 红（run 38113472212）：TASK-198 批3 新增的 UserProbeTest 3 例 Failed to load ApplicationContext。round-1 曾归因 Nacos gRPC 9848——经 CI 权威日志全量复核与本地等效复现双证为误归因：9848 全部属 gateway 段噪音且 gateway 随后正常启动（fail-fast=false 降级路径在 CI 存活）；user 档真首因为 Redisson 急切建连（health 注册链 → redissonConnectionFactory → RedisConnectionException 127.0.0.1:6379），lazy-initialization 不覆盖该急切链。根因定性：批3 为 verify/leaderboard 配了 @Primary Redisson mock、record 以 ProbeApp 定制上下文全隔离，唯 user 漏配；本地绿为本机 Redis/MySQL/Nacos 均在跑的环境依赖泄漏假绿。「仅补 Nacos 隔离属性」经等效复现证伪（Nacos 双禁后 user 仍 130 例 3 错、首因同构），round-1 触发停止条件 §3.1 零提交停工，本 v2 改派。

## 修复方案（v2）

UserProbeTest 新增 @TestConfiguration 提供 @Primary RedissonClient mock（照 VerifyProbeTest.ProbeTemplateConfig 范式，自动配置让位），断言语义与既有 @Import 零改动；等效验证若暴露次生急切断点（MQ 类等）同文件同范式补配；Nacos 双禁用属性降为备选第二手段。以「CI 等效无中间件」口径验证六探针全绿（record/verify/leaderboard/mapmatch 四档 CI 从未跑到者一并等效留证）。既有 609 用例零回归。

## 边界与约束

- 改动面：C-01 主改动 UserProbeTest.java 单文件（例外通道：其余五探针等效验证暴露隐藏依赖时同范式最小补配，上限 6 文件）；C-02 恰 4 台账文件
- 零触碰：一切 src/main、application 配置、pom、ci.yml、compose、scripts/verify/*；断言语义（liveness UP / readiness 端点 / 组结构静态断言 / 禁断言 db-redis UP）零改动
- offline 609 逐位不减不增（mock 补配不加用例）；token 2030 只增不减；static/契约门照常
- StompConnectAuthInterceptor 生产 bean 注册缺陷维持 @Import 临时供 bean，D3 类课题不搭车（另立课题）
- 第 40 次 CI 全绿为外部终验（user 档必须实跑绿）；红则按签名归因禁重试
