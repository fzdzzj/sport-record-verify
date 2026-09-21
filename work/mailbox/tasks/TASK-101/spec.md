# TASK-101 安全与暴露面加固（F01/F02/F08/F10/F19）

## 目标
修复 5 条安全类发现：网关降级路径伪造身份漏洞、中间件弱口令与端口暴露、actuator 暴露过宽、密钥硬编码兜底、Sentinel 兜底路由不全。明细见 work/mailbox/findings-summary.md 对应条目（先读它）。

## 范围外
- 不改任何 pom.xml、不改业务逻辑代码（除本 spec 列出的文件）
- 不动版本矩阵；不引入任何新依赖（离线仓库约束，见下）

## 硬约束：离线仓库
本机 Maven 用离线仓库 `.m2-repo`（settings 为 .mvn-settings.xml）。禁止新增任何依赖/插件。所有构建/测试命令统一加 `-s .mvn-settings.xml`。

## 先读文件
- work/mailbox/findings-summary.md（F01/F02/F08/F10/F19 节）
- gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java、auth/JwtTokenParser.java、config/SentinelGatewayRuleConfig.java
- gateway-service/src/main/resources/application.yml
- gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java
- common/src/main/java/com/sportverify/common/internal/InternalApiAuthFilter.java 及其测试
- api/src/main/java/com/sportverify/api/internal/InternalApiFeignInterceptor.java
- user-service/src/main/java/com/sportverify/user/auth/util/JwtUtil.java 及其测试
- docker-compose.yml、.env、README.md
- 各服务 application.yml / record-service application.properties（management 段）

## 只改文件（其他任务并行中，严禁越界）
- gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java
- gateway-service/src/main/java/com/sportverify/gateway/config/SentinelGatewayRuleConfig.java
- gateway-service/src/main/resources/application.yml
- gateway-service/src/test/**（仅 AuthGlobalFilter/Sentinel 相关测试）
- common/src/main/java/com/sportverify/common/internal/InternalApiAuthFilter.java
- common/src/test/java/com/sportverify/common/internal/InternalApiAuthFilterTest.java
- api/src/main/java/com/sportverify/api/internal/InternalApiFeignInterceptor.java
- user-service/src/main/java/com/sportverify/user/auth/util/JwtUtil.java
- user-service/src/test/java/com/sportverify/user/auth/util/JwtUtilTest.java
- verify-service/user-service/leaderboard-service/mapmatch-service 的 src/main/resources/application.yml
- record-service/src/main/resources/application.properties
- docker-compose.yml、.env、README.md

## 要做的修改
1. F01：AuthGlobalFilter 在未鉴权放行的所有路径（authEnabled=false 降级 + 命中白名单）必须 remove 外部传入的 X-User-Id / X-Role 头再透传；鉴权通过路径维持覆盖注入。补测试：降级模式携带伪造头 → 下游收到请求不含这两头。
2. F02：docker-compose.yml 所有口令改为 ${VAR:-占位} 从 .env 注入；.env 补 MYSQL_ROOT_PASSWORD/POSTGRES_PASSWORD/REDIS 说明等强占位；非必要端口映射绑 127.0.0.1（保留宿主机本地访问）；文件头加「仅限本地开发，生产禁用」警告。README 快速开始节同步说明。
3. F08：各服务 `management.endpoint.health.show-details` 改 `when-authorized`（或 never）；网关白名单从 `/actuator/**` 收窄为 `/actuator/health`（Prometheus 抓取经 host.docker.internal 直连服务端口，不经网关，不受影响——在 README 写明这一点）。
4. F10：JwtUtil 的 secret 默认值移除语义化加固——构造器对空/默认演示值在 prod profile 下启动失败（实现口径：yml 改为 ${JWT_SECRET:} 空缺省，JwtUtil 已有 <32 字节校验会自然启动失败；本地开发 README 写明必须设 JWT_SECRET 或在 local 配置里给值）。InternalApiAuthFilter 与 InternalApiFeignInterceptor 的 token 默认 `local-demo-internal-token` 保留但改为：expectedToken 为空或等于演示默认时，启动日志大写 WARN「生产禁止」；比较改 `java.security.MessageDigest.isEqual` 常量时间。
5. F19：SentinelGatewayRuleConfig.ROUTE_IDS 补全为 gateway application.yml 路由表的全部 7 个 route id。

## 验收命令
1. `mvn -s .mvn-settings.xml -q -pl gateway-service,common,api,user-service -am test` 全绿
2. `mvn -s .mvn-settings.xml -q -pl record-service,verify-service,leaderboard-service,mapmatch-service -am test` 全绿（yml 改动不破坏上下文/测试）
3. `docker compose config -q` 通过（无 docker 时跳过并在 handoff 说明）
4. grep 验证：`grep -r "X-User-Id" gateway-service/src/test` 存在降级剥头测试

## 完成定义
- 5 条发现全部落地，验收命令全过
- 写 work/mailbox/tasks/TASK-101/handoff.md：完成情况 / 每条发现对应的改动点（文件:行）/ 验收命令输出结论 / 「待主 agent 决定」清单（没有写无）
- 回报 ≤300 字：产出 / 校验结果 / 未解决项 / 待决策项

## 约束
- 你是上述文件的唯一写入者；最多 1 次修复重试
- 不准猜测，缺信息写 handoff「待主 agent 决定」
