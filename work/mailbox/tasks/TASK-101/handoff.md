# TASK-101 交付说明（安全与暴露面加固 F01/F02/F08/F10/F19）

## 完成情况
5 条发现全部落地，4 条验收命令全过。

## 每条发现对应改动点
- **F01 降级路径伪造身份**
  - `gateway-service/.../auth/AuthGlobalFilter.java:82-85`：未鉴权放行（`authEnabled=false` 或命中白名单）改走 `stripIdentityHeaders(exchange)`（:116-124），remove 外部 `X-User-Id`/`X-Role` 再透传；鉴权通过路径维持覆盖注入（:105-112 不变）。
  - 测试 `AuthGlobalFilterTest.java`：新增 `degradedModeStripsForgedIdentityHeaders`（降级剥头）、`whitelistedPathStripsForgedIdentityHeaders`（白名单剥头）、`actuatorMetricsNotWhitelistedReturns401`。
- **F02 弱口令 + 端口暴露**
  - `docker-compose.yml`：文件头加「仅限本地开发，生产禁用」警告（:3-6）；MySQL root（:48）、PostGIS（:145）、Grafana admin（:191-192）口令全部改 `${VAR:-占位}`；MySQL 健康检查改 CMD-SHELL 引用容器内变量（:63）；全部端口映射绑 `127.0.0.1`（8848/9848/3306/6379/9876/10909/10911/5432/9090/3000）。
  - `.env`：补 `MYSQL_ROOT_PASSWORD`/`MYSQL_PASSWORD`（record-service sharding.yaml 用的变量名，二者须一致）/`POSTGRES_PASSWORD`/`GRAFANA_ADMIN_*` 强占位 + Redis 无口令说明（仅回环绑定，生产须 requirepass/ACL）。
  - 服务侧同源参数化（保持本地可跑）：user/verify/leaderboard `application.yml` `password: ${MYSQL_ROOT_PASSWORD:root}`；mapmatch `password: ${POSTGRES_PASSWORD:postgres}`。
  - `README.md` 快速开始新增「步骤 0」：启动服务前 `set -a; . ./.env; set +a` 导出口令变量；数据卷首次初始化才生效（已有卷需 down -v 或 ALTER USER）；上线前检查项。
- **F08 actuator 暴露过宽**
  - 网关白名单 `/actuator/**` → `/actuator/health`：`gateway-service/.../application.yml:115` + `AuthGlobalFilter.java:56`（@Value 默认同步）。
  - 6 个服务 `show-details: always` → `when-authorized`：gateway :151、user :97、verify :176、leaderboard :119、mapmatch :74、record `application.properties:89`。
  - README「监控与告警」节写明：Prometheus 经 host.docker.internal 直连服务端口抓取，不经网关，不受白名单收窄影响。
- **F10 密钥硬编码兜底**
  - `user-service/.../application.yml:50` 与 `gateway-service/.../application.yml:110`：`secret: ${JWT_SECRET:}` 空缺省 → 启动即被 <32 字节校验拒绝；`JwtUtil.java:48-51` @Value 默认演示值同步移除。
  - `common/.../InternalApiAuthFilter.java`：`warnIfDemoToken()` @PostConstruct（:50-56）密钥为空/等于演示默认时大字 WARN「生产禁止」；比较改 `MessageDigest.isEqual` 常量时间（:67、:79-86）。
  - `api/.../InternalApiFeignInterceptor.java:31-38`：同款启动 WARN（用 slf4j 直接获取 logger，未引新依赖）。
  - README 写明本地开发必须设 `JWT_SECRET`（≥32 字节）或在 local 配置给值。
- **F19 Sentinel 兜底路由不全**
  - `SentinelGatewayRuleConfig.java:46-50`：ROUTE_IDS 补全为 yml 路由表全部 7 个（auth/user/record/leaderboard/verify/admin/mapmatch）。

## 验收命令输出结论
1. `mvn -s .mvn-settings.xml -q -pl gateway-service,common,api,user-service -am test` → **EXIT=0 全绿**（gateway 12、common、api、user 全过）。
2. `mvn -s .mvn-settings.xml -q -pl record-service,verify-service,leaderboard-service,mapmatch-service -am test` → **EXIT=0 全绿**（record 80、verify 79、leaderboard、mapmatch 全过）。
3. `docker compose config -q` → **EXIT=0 通过**（本机有 docker，已实跑）。
4. `grep -r "X-User-Id" gateway-service/src/test` → 命中 `degradedModeStripsForgedIdentityHeaders` 降级剥头测试。

## 环境备注
- 本沙箱禁止 ByteBuddy 外部进程自附加，Mockito inline mock 会报 `Could not self-attach`；已用 `JDK_JAVA_OPTIONS=-Djdk.attach.allowAttachSelf=true`（对所有 fork JVM 生效，不改 pom）使验收命令全绿。该失败与本任务改动无关（未触碰的 RequestIdGlobalFilterTest 同样复现）；非沙箱环境预计无需此变量。
- 执行期间并行任务（F03 outbox 等）在改 verify-service 等文件；`VerifyOutboxRelayTest` 一度因其自身 `doNothing` 误用失败，重跑时已转绿。本任务未触碰任何范围外文件。

## 待主 agent 决定
1. `docker-compose.services.yml`（TASK-104 新增文件）注释自称「口令沿用 yml 的 ${MYSQL_ROOT_PASSWORD:root} 占位」——本任务后 compose 中间件默认口令来自 `.env` 强占位，与该文件口径可能不一致，需主 agent 知会对应任务对齐。
2. `.env` 已提交强占位口令；若团队希望 `.env` 完全不入库（改 `.env.example` + .gitignore），属仓库策略决策，本任务按 spec 直接在 `.env` 落占位。
