# TASK-263 立项任务书（批 G-5：配置与前端效率群 G8+G20+G21+G7+G18+G19）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-17 指导侧终裁；开工基线 main @ 76a447b；MEDIUM 档，指导亲核三块 PASS 零阻断，七门禁全绿，封盘批次推送 origin/main）

## 1. 证据链（指导侧亲验坐实，2026-10-17，TASK-262 补记笔 e2df483 后实测行号）

1. **G8 主项·Redis 客户端超时矩阵缺口**：
   - `leaderboard-service/src/main/resources/application.yml` L61-63 仅配 `host: 127.0.0.1` / `port: 6379`，无 `timeout`；
   - `user-service/src/main/resources/application.yml` L30-33 仅配 `host: 127.0.0.1` / `port: 6379`，无 `timeout`；
   - `verify-service/src/main/resources/application.yml` L52-55 仅配 `host: 127.0.0.1` / `port: 6379`，无 `timeout`；
   - `record-service/src/main/resources/application.properties` L46-47 仅配 `spring.data.redis.host=127.0.0.1` / `port=6379`，无 `timeout`；
   - Lettuce 默认 command timeout 为 60s；当发生网络丢包或 Redis 阻塞时线程最长 hang 60s；对照网关 HTTP（E10，3s/10s）、mapmatch DB（E17，3s/5s）与 Feign 矩阵（1s/3s），Redis 客户端超时存在明显防御盲区；
2. **G20·CI web job 无 pnpm 依赖缓存**：
   - `.github/workflows/ci.yml` L145-159 中 web job 每次均全量重新下载依赖，无缓存步骤；对照 `build` job L28-33 有 `actions/cache@v5` 针对 Maven 本地仓缓存；
3. **G21·notifications 页面请求瀑布**：
   - `web/src/pages/notifications.page.vue` 在三处操作后串行等待两个独立请求——L192-193（`onBatchMarkRead`）、L267-268（`onMarkAll`）、L286-287（`onClearRead`）均写作 `await loadList(); await refreshUnread();`；两端点互不依赖，串行增加一次额外网络 RTT 延迟；
4. **G7·主 chunk 1.6MB 缺乏分包**：
   - `web/dist/assets/index-*.js` 体积达 1,639,216 字节（~1.64MB）；`web/vite.config.ts` L8-70 全文无 `build` 配置块，触发 Vite 500KB 告警，影响首屏资源加载性能；
5. **G18·STOMP 鉴权异常转译丢因**：
   - `user-service/src/main/java/com/sportverify/user/config/StompConnectAuthInterceptor.java` L64-67 `catch (Exception e)` 中 `throw new MessagingException("STOMP CONNECT 携带的 access token 无效或已过期，拒绝会话建立");` 未传原因 `e`，导致底层异常链丢失；
6. **G19·User passwordHash 序列化防线缺失**：
   - `user-service/src/main/java/com/sportverify/user/entity/User.java` L32-33 `private String passwordHash;` 字段未标注 `@JsonIgnore`；现全靠 DTO 映射纪律防护，缺实体层防御性序列化忽略保护；
7. **G22 / G23 既有受限事实**：
   - G22：六服务无 `maximum-pool-size`，生效 Hikari 默认 10；历史 TASK-164 报告自证未经同负载压测调大连接池收益未定且存竞争隐患，按预注册声明受限保持不变；
   - G23：各服务已有 mybatis-plus Slf4jImpl；硬约束禁止引入新 Maven 插件，生产环境慢 SQL 需依托 MySQL slow log 基础设施，按预注册声明受限。

## 2. 改动白名单（仅限下列文件，双向全等）

1. `leaderboard-service/src/main/resources/application.yml`（R1 G8 Redis timeout 配置）
2. `user-service/src/main/resources/application.yml`（R1 G8 Redis timeout 配置）
3. `verify-service/src/main/resources/application.yml`（R1 G8 Redis timeout 配置）
4. `record-service/src/main/resources/application.properties`（R1 G8 Redis timeout 配置）
5. `.github/workflows/ci.yml`（R2 G20 web job pnpm 缓存配置）
6. `web/src/pages/notifications.page.vue`（R3 G21 Promise.all 并行化）
7. `web/vite.config.ts`（R4 G7 manualChunks 分包配置）
8. `user-service/src/main/java/com/sportverify/user/config/StompConnectAuthInterceptor.java`（R5 G18 保留 cause e）
9. `user-service/src/main/java/com/sportverify/user/entity/User.java`（R6 G19 passwordHash 增加 @JsonIgnore）
10. `user-service/src/test/java/com/sportverify/user/config/StompConnectAuthInterceptorTest.java`（R5 测试断言 getCause 验证）
11. `user-service/src/test/java/com/sportverify/user/entity/UserJsonIgnoreTest.java`（R6 新增单测断言序列化忽略 passwordHash）
12. `user-service/src/test/java/com/sportverify/user/config/RedisTimeoutConfigTest.java`（R1 配置测试断言，或执行侧定点登记）
13. 台账：`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md`、`work/mailbox/HANDOVER.md`、`work/mailbox/tasks/TASK-263/spec.md`、`work/mailbox/tasks/TASK-263/handoff.md`

零触碰面：gateway/mapmatch 服务 src/test、common/api 模块、数据库 DDL/迁移脚本、业务服务核心逻辑、package.json/pom.xml 依赖与插件版本、状态机/MQ/批量/索引参数。

## 3. 修法要求

- **R1（G8 主项·Redis 超时矩阵配置）**：
  - 在 `leaderboard-service`、`user-service`、`verify-service` 的 `application.yml` 中配置 `spring.data.redis.timeout: 3000ms`；
  - 在 `record-service` 的 `application.properties` 中配置 `spring.data.redis.timeout=3000ms`；
  - 增补/扩展配置测试（如 `RedisTimeoutConfigTest`），断言环境属性中 `spring.data.redis.timeout` 存在且值为 `3000ms`；
- **R2（G20·CI web job pnpm 缓存）**：
  - 在 `.github/workflows/ci.yml` 的 `web` job 中引入依赖缓存步骤（在 `Enable pnpm` 之后、`Install with frozen lockfile` 之前），采用 `actions/cache@v5` 缓存 pnpm store 目录（或 `~/.local/share/pnpm/store`），key 基于 runner.os 与 `web/pnpm-lock.yaml` hash，对齐 build job 的缓存范式；
- **R3（G21·notifications 串行瀑布消除）**：
  - 在 `web/src/pages/notifications.page.vue` 的 `onBatchMarkRead`、`onMarkAll`、`onClearRead` 三处，将 `await loadList(); await refreshUnread();` 改为 `await Promise.all([loadList(), refreshUnread()]);` 并行触发；
- **R4（G7·Vite 构建分包优化）**：
  - 在 `web/vite.config.ts` 中配置 `build.rollupOptions.output.manualChunks`，将重量级依赖 `ant-design-vue` 与 `@ant-design/icons-vue` 划入 `vendor-antdv`，将 `vue`、`vue-router`、`@tanstack/vue-query` 划入 `vendor-vue`；
  - 确保分包后执行 `pnpm --dir web run build` 主 index chunk 大小降至 500KB 以下，消除 Vite 警告；且 `typed-router.d.ts` 保持净；
- **R5（G18·STOMP 异常保留原因）**：
  - `StompConnectAuthInterceptor.java` L66 改为 `throw new MessagingException("STOMP CONNECT 携带的 access token 无效或已过期，拒绝会话建立", e);`；
  - 在 `StompConnectAuthInterceptorTest.java` 中对该异常补齐断言 `assertNotNull(ex.getCause())`；
- **R6（G19·User passwordHash 防御性序列化忽略）**：
  - `User.java` 的 `passwordHash` 字段增加 `@JsonIgnore` 注解（`com.fasterxml.jackson.annotation.JsonIgnore`）；
  - 新增 `UserJsonIgnoreTest.java` 单元测试，断言通过 Jackson `ObjectMapper.writeValueAsString(user)` 输出不含 `"passwordHash"` 字段。

## 4. 门禁与验证要求（MEDIUM 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（经 `D:\git\Git\usr\bin\bash.exe --login`），全量七模块，surefire 基线 **63/78/219/320/203/110/35**（TASK-262 新基线）只增不减；
- 前端门禁：
  - `pnpm --dir web run type-check` 退出码 0；
  - `pnpm --dir web run test`（vitest 4 套 63 测）全绿通过；
  - `pnpm --dir web run build` 构建成功且主 chunk 体积 < 500KB，无 chunk 大小警告；
  - `git diff --exit-code -- web/src/typed-router.d.ts` 保持纯净；
- 契约门（`--open=TASK-263 --baseline=<开工基线>`，handoff §2 逐行列全改动集）；
- token 门 29 项只增不减（期望值口径：**C-02 树 = 2075 + 29 = 2104**，因 handoff 自枚举效应）；
- 词面门模式文件法 + 探针阳性对照（四 locale 全 ZERO_HIT）；
- checkstyle delta=0（五模块双向零新增违规、新增行交集为空）；
- `git diff --check` 净；
- 修改文件保持既有 CRLF/LF，新文件必须以 LF 结尾；
- 双提交 C-01（业务/测试/配置）/ C-02（台账）；`git status --porcelain` 收口为空。

## 5. 受限声明（预登记）

1. G22 Hikari 连接池容量保持默认 10，未经真实压测定参不得盲目调大；
2. G23 慢 SQL 依托现有 Slf4jImpl 与底层 MySQL 运维慢查日志，禁引入外部插件；
3. G8 Redis 客户端超时 3000ms 为生产防御性标称值，无本地网络丢包故障注入测试；
4. G20 pnpm 缓存生效以 GitHub Actions CI 真实运行为准，本地环境不包含 actions/cache 远端缓存服务；
5. Nacos 运行时配置未核（沿 G 系既有口径）。

## 6. 停止条件与升级路径

- 若 Vite manualChunks 分包引入运行时循环引用或页面加载白屏，立即停手排查；
- 若后端全量测试计数低于基线或任何既有用例失败，立即停手自纠；
- 若出现白名单外修改需求，必须先向指导侧汇报并经用户授权。

## 7. 验收判据

C-01+C-02 双提交落库、七门禁全绿、白名单双向全等、R1 Redis 超时配置断言全绿、R2 CI pnpm 缓存就位、R3 瀑布消除代码走读与前端测试全绿、R4 manualChunks 构建消除 500KB 警告、R5 STOMP 异常原因断言通过、R6 passwordHash 忽略测试通过；指导侧亲核三块 PASS；CI 外部终验由补记笔登记。
