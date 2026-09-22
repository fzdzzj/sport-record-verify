# TASK-126 任务规格：密钥默认值治理与常量时间比较（app.security.strict）

## 背景

四处密钥落点存在硬编码兜底默认（本地演示值），且内部接口共享密钥用 `String.equals`
比较（非常量时间）。本任务新增 `app.security.strict` 开关（默认 false，本地演示零扰动）：
strict=false 时四处行为与现状逐位一致；strict=true 时任何密钥项缺失（回落到演示默认）
即启动失败（fail-fast，`@PostConstruct` / 构造期校验实现，不新增依赖）。

## 开工核实（指导侧报的行号已实地复核）

| # | 落点 | 实核位置 | 兜底默认 |
| --- | --- | --- | --- |
| 1 | user-service `JwtUtil` 构造器 | `user-service/src/main/java/com/sportverify/user/auth/util/JwtUtil.java:48` | `app.auth.jwt.secret` 缺省回落 `sport-verify-hs256-secret-key-0123456789abcdef` |
| 2 | gateway `JwtTokenParser` 构造器 + `application.yml` | `gateway-service/src/main/java/com/sportverify/gateway/auth/JwtTokenParser.java:34`；`gateway-service/src/main/resources/application.yml:110` | 同一默认串（yml 侧 `${JWT_SECRET:<默认>}`，Java 侧 `@Value` 兜底同串） |
| 3 | common `InternalApiAuthFilter` | `common/src/main/java/com/sportverify/common/internal/InternalApiAuthFilter.java:41-42`（token 兜底 `local-demo-internal-token`）；`:52`（`equals` 比较） | `app.internal.token:${INTERNAL_API_TOKEN:local-demo-internal-token}` |
| 4 | api `InternalApiFeignInterceptor` | `api/src/main/java/com/sportverify/api/internal/InternalApiFeignInterceptor.java:19` | 发送侧同一默认串 |

指导侧报「gateway application.yml 约 110 行」实核为 `secret: ${JWT_SECRET:...}`（110 行）；
网关 Java 侧 `JwtTokenParser`（:34）与 user-service `JwtUtil` 同构，属同一落点的另一半，
一并治理（按实际核实数登记：5 个文件位置、4 处逻辑落点）。

## 需求（EARS）

- WHEN `app.security.strict=false`（默认）, 系统 SHALL 保持四处行为与现状逐位一致
  （既有测试全绿即证；演示默认值原样保留）。
- WHEN `app.security.strict=true` 且任一密钥项缺失（解析结果等于演示默认串或为 null）,
  系统 SHALL 在启动期失败（fail-fast）：JWT 侧构造期抛 `IllegalStateException`；
  内部接口两侧 `@PostConstruct` 抛 `IllegalStateException`。不新增依赖。
- WHEN `app.security.strict=true` 且密钥已显式注入, 系统 SHALL 正常启动。
- `InternalApiAuthFilter` 的共享密钥比较 SHALL 改用 `MessageDigest.isEqual`
  （常量时间），行为等价（相同则放行、不同则 403/1002）。

## 停止边界

- 不改各密钥的 yml 默认值本身（strict=false 时演示值原样保留）。
- 不动 token 传递链路（`InternalApiHeaders.TOKEN` 语义）。
- 不引入新依赖；不 push。

## 只改清单

- `common/src/main/java/com/sportverify/common/internal/InternalApiAuthFilter.java`
- `api/src/main/java/com/sportverify/api/internal/InternalApiFeignInterceptor.java`
- `user-service/src/main/java/com/sportverify/user/auth/util/JwtUtil.java`
- `gateway-service/src/main/java/com/sportverify/gateway/auth/JwtTokenParser.java`
- `gateway-service/src/main/resources/application.yml`
- `common/src/test/java/com/sportverify/common/internal/InternalApiAuthFilterTest.java`
- `user-service/src/test/java/com/sportverify/user/auth/util/JwtUtilTest.java`
- `gateway-service/src/test/java/com/sportverify/gateway/auth/JwtTokenParserTest.java`
- `spec/changes/add-strict-secret-fail-fast/`（三件套）
- `work/mailbox/tasks/TASK-126/spec.md`、`handoff.md`（新建）
- `work/mailbox/PLAN.md`（追加验收记录）

## 测试判别式设计

红绿均走 `ApplicationContextRunner`（spring-boot-starter-test 既有依赖，不新增），
纯属性驱动、不引用新 API，红阶段可编译可运行：

- common：`withUserConfiguration(InternalApiAuthFilter.class)` + `app.security.strict=true`
  且不注入 token → 期望上下文启动失败（实现前会启动成功 → 红）；
  strict=true + 显式 token → 启动成功；strict 缺省 + 演示默认 → 启动成功（零扰动）。
- user-service：同法对 `JwtUtil`（strict=true 不注入 secret → 启动失败）。
- gateway：同法对 `JwtTokenParser`。
- 既有测试不回归：common 4 条 `InternalApiAuthFilterTest`（`new` 直构不经 `@PostConstruct`，
  不受影响）、JwtUtilTest 3 条、JwtTokenParserTest 3 条。
- api 侧：api 模块无测试基建（无 junit/test 依赖），不建测试（避免新增依赖）；
  实现与 common 侧同构，判别式由 common 侧同型覆盖，api 侧记未覆盖。
