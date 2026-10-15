# TASK-246 任务书（spec.md）

> **课题**：方案 E：后台架构韧性与存储生命周期治理（B20 Outbox历史清理与毒丸重放 + B21 通知历史清理与索引 + B23 好友榜多级缓存防穿透 + B24 网关统一异常标准包装）  
> **级别**：MEDIUM 档  
> **责任服务**：`verify-service`、`user-service`、`leaderboard-service`、`gateway-service`、`web`、`sql`  
> **开工基线**：以当前 HEAD（立项准备提交）为准  

---

## 1. 业务目标与价值

1. **B20 Outbox 历史清理与毒丸人工重放（verify-service + web）**：
   - `verify_event_outbox` 表历史 `SENT` 行随着系统运行无限堆积，需建立安全的物理分批清理能力；
   - TASK-243 落地了重试超限流转至 `EXHAUSTED` 终态，但缺乏管理查看与人工重放通道。在管理端提供死信列表与一键重放（重置为 `PENDING` 且 `retry_count=0`），建立消息断环自愈闭环。
2. **B21 通知中心历史已读清理与索引（user-service + sql + web）**：
   - 补齐 `notification` 表 `KEY idx_read_created (is_read, created_at)` 复合索引，消除历史扫描瓶颈；
   - 建立 90 天以上历史已读通知定期清理能力，并在用户端提供「一键清空本人已读通知」，彻底提升用户消息治理体验。
3. **B23 好友榜本地多级缓存防穿透（leaderboard-service）**：
   - 好友榜（`friend`）当前每次请求均穿透调用两次 Feign（好友列表 + 用户昵称）与 Redis Pipeline，高并发下极易打爆 `user-service`；
   - 引入 10~15s 本地轻量 Caffeine 缓存（Key 为 `userId + ":" + size`），阻断高频刷新穿透雪崩，微秒级响应。
4. **B24 网关未捕获异常统一标准包装（gateway-service）**：
   - 网关当前未配置全局异常处理器，下游不可达（503）、路由不存在（404）或网关超时（504）时返回 Spring 默认 HTML 错误页或非标 JSON，导致前端拦截器报错崩溃；
   - 实现高优先级 `GatewayExceptionHandler`（实现 `ErrorWebExceptionHandler`），统一拦截网关异常并输出标准 `Result.failure(code, message)` JSON 契约。

---

## 2. 严格改动文件白名单

实施侧仅允许在以下白名单内修改或新增文件，禁止超出范围：

1. `sql/01-user-db.sql`
2. `sql/migrations/add-idx-notification-read-created.sql`
3. `verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java`
4. `verify-service/src/main/java/com/sportverify/verify/controller/VerifyController.java`
5. `verify-service/src/test/java/com/sportverify/verify/controller/VerifyControllerTest.java`
6. `user-service/src/main/java/com/sportverify/user/mapper/NotificationMapper.java`
7. `user-service/src/main/java/com/sportverify/user/service/NotificationService.java`
8. `user-service/src/main/java/com/sportverify/user/controller/NotificationController.java`
9. `user-service/src/test/java/com/sportverify/user/service/NotificationServiceTest.java`
10. `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
11. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
12. `gateway-service/src/main/java/com/sportverify/gateway/handler/GatewayExceptionHandler.java`
13. `gateway-service/src/test/java/com/sportverify/gateway/handler/GatewayExceptionHandlerTest.java`
14. `web/src/api/client.ts`
15. `web/src/api/__tests__/client.test.ts`
16. `web/src/pages/notifications.page.vue`
17. `web/src/pages/admin.page.vue`
18. `work/mailbox/tasks/TASK-246/spec.md`
19. `work/mailbox/tasks/TASK-246/handoff.md`

**零触碰红线声明**：
- `record-service` 绝对零触碰；
- 根与子模块 `pom.xml`、`package.json` 绝对零触碰（零新外部依赖）；
- `work/mailbox/PLAN.md` 绝对禁止子 Agent 触碰。

---

## 3. 详细判别式与实现契约

### 3.1 B20 Outbox 历史清理与毒丸重放
- **`VerifyEventOutboxMapper`**:
  - `selectExhausted(@Param("limit") int limit)`: `SELECT * FROM verify_event_outbox WHERE status = 'EXHAUSTED' ORDER BY id DESC LIMIT #{limit}`；
  - `replayExhausted(@Param("id") Long id)`: `UPDATE verify_event_outbox SET status = 'PENDING', retry_count = 0 WHERE id = #{id} AND status = 'EXHAUSTED'`（返回影响行数，非 EXHAUSTED 不做操作）；
  - `cleanSentEvents(@Param("before") LocalDateTime before, @Param("limit") int limit)`: `DELETE FROM verify_event_outbox WHERE status = 'SENT' AND sent_at < #{before} LIMIT #{limit}`。
- **`VerifyController`**:
  - `GET /api/outbox/exhausted`（参数 `limit` 默认 50，cap 200）：返回 `Result.success(List<VerifyEventOutbox>)`；
  - `POST /api/outbox/exhausted/{id}/replay`：返回 `Result.success(replayedRows)`；
  - `DELETE /api/outbox/sent`（参数 `days` 默认 7）：计算阈值调用 `cleanSentEvents`，返回 `Result.success(cleanedRows)`。
- **前端 `admin.page.vue` + `client.ts`**:
  - `client.ts` 导出 `fetchExhaustedOutbox(limit)`、`replayExhaustedOutbox(id)`、`cleanSentOutbox(days)`；
  - `admin.page.vue` 增加「Outbox 消息补偿与死信看板」卡片与表格，支持初载、一键重放与清理操作。

### 3.2 B21 通知中心历史清理与索引
- **SQL 与迁移**:
  - `sql/01-user-db.sql` 为 `notification` 表添加 `KEY idx_read_created (is_read, created_at)`；
  - 新建 `sql/migrations/add-idx-notification-read-created.sql`（使用 `information_schema.statistics` 防重，幂等执行）。
- **`NotificationMapper`**:
  - `cleanExpiredReadNotifications(@Param("before") LocalDateTime before, @Param("limit") int limit)`: `DELETE FROM notification WHERE is_read = 1 AND created_at < #{before} LIMIT #{limit}`；
  - `cleanUserReadNotifications(@Param("userId") Long userId)`: `DELETE FROM notification WHERE user_id = #{userId} AND is_read = 1`。
- **`NotificationService`**:
  - `cleanExpiredNotifications(int days, int limit)`：参数校验，分批物理删除；
  - `cleanMyReadNotifications(Long userId)`：清空当前用户已读通知，返回受影响行数。
- **`NotificationController`**:
  - `DELETE /api/notifications/read`：清空当前登录用户的已读通知（从 `X-User-Id` 认定身份）；
  - `DELETE /api/notifications/expired?days=90`：清理过期已读通知。
- **前端 `notifications.page.vue` + `client.ts`**:
  - `client.ts` 导出 `clearReadNotifications()`；
  - `notifications.page.vue` 表头操作区增加「清空已读」按钮（带 `a-popconfirm` 确认），成功后刷新列表并通知未读数。

### 3.3 B23 好友榜本地多级缓存防穿透
- **`LeaderboardService`**:
  - 引入内部本地 Caffeine 缓存：
    ```java
    private final com.github.benmanes.caffeine.cache.Cache<String, List<LeaderboardDTO>> friendLeaderboardCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(2000)
                    .expireAfterWrite(java.time.Duration.ofSeconds(15))
                    .build();
    ```
  - 在 `topFriends(Long userId, int size)` 入口构建缓存 Key（`userId + ":" + size`），命中直接返回；未命中时执行既有拉取与组装，返回前回填本地缓存。
- **`LeaderboardServiceTest`**:
  - 增加单测：连续调用 2 次 `topFriends`，验证第二次从缓存命中，外部依赖（Feign 模拟调用）次数不增加。

### 3.4 B24 网关统一未捕获异常标准包装
- **`gateway-service/src/main/java/com/sportverify/gateway/handler/GatewayExceptionHandler.java`**:
  - 实现 `org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler` 与 `org.springframework.core.Ordered`；
  - `getOrder()` 返回 `-1`（优先级高于 Spring 默认异常处理）；
  - 拦截未捕获异常：
    - `ResponseStatusException` 提取其 statusCode；
    - 无法连接（`ConnectException` 等）映射为 `503` 与文案「服务暂时不可用，请稍后重试」；
    - 超时（`TimeoutException`）映射为 `504` 与文案「服务响应超时，请稍后重试」；
    - 404 映射为 `404` 与文案「服务或接口不存在」；
    - 其余内部异常映射为 `500` 与文案「系统繁忙，请稍后重试」；
  - 设置 Response 状态码、Header `Content-Type: application/json;charset=UTF-8`，以 UTF-8 JSON 格式写出 `{"code": status, "message": msg, "data": null}`。
- **`GatewayExceptionHandlerTest`**:
  - 使用 `MockServerWebExchange` 覆盖 404、503、504 与未捕获通用异常，断言 HTTP Status 与响应体 JSON 契约。

---

## 4. 本地门禁要求

1. **后端单元测试**：
   - `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl verify-service,user-service,leaderboard-service,gateway-service -am` 全绿通过。
2. **前端类型检查**：
   - `cd web && pnpm run type-check` 0 错误。
3. **前端单元测试**：
   - `cd web && pnpm test` 全绿通过。
4. **台账契约门禁**：
   - 使用 Git Bash 执行 `bash scripts/verify/mailbox-contract.sh --open=TASK-246 --baseline=<开工基线>`，退出码必须为 0。
