# TASK-246 台账（handoff）

> **TASK**：后台架构韧性与存储生命周期治理（B20 Outbox历史清理与毒丸重放 + B21 通知历史清理与索引 + B23 好友榜多级缓存防穿透 + B24 网关统一异常标准包装，MEDIUM 档）  
> **开工基线**：fa9858a（main，工作树干净）  
> **方案**：方案 E（第二阶段：后台架构韧性与存储生命周期治理）  
> **状态**：已实施并门禁全绿

## 1. 实施摘要

| 判别式 | 归属 | 状态 | 说明 |
| --- | --- | --- | --- |
| B20 Outbox 历史清理与毒丸人工重放 | verify-service + web | 已完成 | Mapper 三项（selectExhausted/replayExhausted/cleanSentEvents）+ Controller 三接口 + 管理端死信看板 |
| B21 通知中心历史已读清理与索引 | user-service + sql + web | 已完成 | idx_read_created 复合索引 + Mapper/Service/Controller 清理 + 前端清空已读 |
| B23 好友榜本地多级缓存防穿透 | leaderboard-service | 已完成 | 15s Caffeine 本地缓存（userId:size 维度）+ 防穿透单测 |
| B24 网关未捕获异常统一标准包装 | gateway-service | 已完成 | GatewayExceptionHandler（Order=-1）统一 Result 契约 JSON + 单测 |

## 2. 修改清单（实际改动集）

> 与实际改动集一致（git diff fa9858a + 未跟踪）。注明：spec.md §2 白名单为 19 文件，实际多改动
> 1 个既有测试 `LeaderboardCacheInvocationTest.java`（见 §4 已知受限：B23 缓存与该既有测试旧断言
> 冲突，经确认后适配，列入清单以通过契约判据 B）。

- sql/01-user-db.sql
- sql/migrations/add-idx-notification-read-created.sql
- verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java
- verify-service/src/main/java/com/sportverify/verify/controller/VerifyController.java
- verify-service/src/test/java/com/sportverify/verify/controller/VerifyControllerTest.java
- user-service/src/main/java/com/sportverify/user/mapper/NotificationMapper.java
- user-service/src/main/java/com/sportverify/user/service/NotificationService.java
- user-service/src/main/java/com/sportverify/user/controller/NotificationController.java
- user-service/src/test/java/com/sportverify/user/service/NotificationServiceTest.java
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java
- gateway-service/src/main/java/com/sportverify/gateway/handler/GatewayExceptionHandler.java
- gateway-service/src/test/java/com/sportverify/gateway/handler/GatewayExceptionHandlerTest.java
- web/src/api/client.ts
- web/src/api/__tests__/client.test.ts
- web/src/pages/notifications.page.vue
- web/src/pages/admin.page.vue
- work/mailbox/tasks/TASK-246/handoff.md

> 零触碰红线遵守：`record-service`、根与各子模块 `pom.xml`、`package.json`（零新外部依赖）、
> `work/mailbox/PLAN.md` 均未改动。

## 3. 门禁验证记录

- 后端单测：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service,leaderboard-service,gateway-service -am` → BUILD SUCCESS（全绿）✓
- 前端类型：`cd web && pnpm run type-check` → 0 错误 ✓
- 前端单测：`cd web && pnpm test` → 4 files / 61 tests 全绿 ✓
- 契约门禁：`bash scripts/verify/mailbox-contract.sh --open=TASK-246 --baseline=fa9858a` → 退出码 0，判据 B 清单一致，契约校验通过 ✓

## 4. 已知受限与偏差声明

- **B23 与该既有测试冲突（已确认处理）**：`LeaderboardCacheInvocationTest.friendQueriesBypassOverallCacheAndCallFriendServiceEachTime`
  原断言同一好友榜连调 2 次调用 2 次 Feign（旧非缓存行为），与 B23「二次调用命中本地缓存、Feign 次数不增」
  判定式直接冲突、不可兼得。经与用户确认后，将该既有测试适配为「经 Spring 代理二次调用命中本地缓存、
  Feign 与 Redis Pipeline 各仅 1 次」，并把该文件列入上述实际改动清单。这是对 spec.md §2 19 文件白名单的
  唯一、最小、必需的偏离。
- **B24 网关零 common 依赖**：gateway-service 依赖边界为「零 common 模块」（见 AuthGlobalFilter 模块边界注），
  `GatewayExceptionHandler` 不引用 `com.sportverify.common.result.Result`，而以 Jackson 序列化同形契约
  `{"code":status,"message":msg,"data":null}`，与业务侧 Result.failure 字段逐字对齐。
- **B20/B21 清理上限**：死信列表 limit cap 200、历史投递批删单批上限 200、通知过期清理批上限 200（防手滑超大值）。
- **本地缓存空榜不缓存**：好友榜本地缓存仅回填非空结果；降级空榜（user-service 不可用等）命中即逐次穿透，
  避免长时间背着旧空榜。