# TASK-271 立项任务书（存量技术债清理与跨服务好友调用性能优化）

> 状态：**已立项待派发**（2026-10-18 指导侧立项；开工基线 main @ `4c85635`；LIGHT 档，指导亲核三块把关，免独立复核子 agent）

## 1. 证据链（实测暴露与指导侧亲验坐实，2026-10-18）

1. **E2E 存量 unmocked 缺口**：
   - TASK-269 在 `index.page.vue` 与 `feed.page.vue` 挂载期引入了 `GET /record/api/records/my/badges` 和 `GET /record/api/records/users/{userId}/equipped-badge`；
   - `web/e2e/helpers.ts` 的 `handleRecord` 尚未包含针对勋章相关端点的路由 mock，未匹配请求落入 `ctx.unmocked.push(...)` 并报 404，在后续执行 E2E 断言 `assertNoUnmocked` 时存在潜在红标隐患（已在 TASK-269 handoff §4 如实登记）。
2. **LeaderboardService 好友 ID 跨服务调用冗余**：
   - `LeaderboardService.fetchAllFriendIds` 现采用 `while (true)` 分页调用 `userApi.listFriends(userId, page, FRIEND_FETCH_SIZE)`；
   - 此调用为分页返回，并在 `user-service` 侧引发了对每一页记录的 `userMapper.selectBatchIds` 昵称查询回表；而 `LeaderboardService` 仅需要好友 `userId` 集合；
   - `UserApi.java:97` 在 TASK-222 已提供专用非分页接口 `Result<List<Long>> listFriendIds(Long userId)`，可单次网络直调，大幅降低跨服务往返与数据库负载。
3. **record-service sharding.yaml 配置漂移**：
   - `record-service/src/main/resources/sharding.yaml` 的 `!SINGLE` 列表中仍残留已迁移至 `leaderboard-service` 的 `ds0.leaderboard_contribution`；
   - 且未显式声明后续新增的单表 `ds0.sport_goal`（TASK-225）与 `ds0.user_badge`（TASK-269）。

---

## 2. 只改清单（白名单，恰 5 业务/配置/测试 + 1 台账 = 6 文件）

严格限制在以下 6 个文件内，严禁触碰白名单外任何文件：

1. `web/e2e/helpers.ts`
2. `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
3. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
4. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java`
5. `record-service/src/main/resources/sharding.yaml`
6. `work/mailbox/tasks/TASK-271/handoff.md`

---

## 3. 核心设计与实施要求

- **R1（E2E mock 路由补齐）**：
  - 在 `web/e2e/helpers.ts` 的 `handleRecord` 函数中补齐：
    - `GET /record/api/records/my/badges` → `route.fulfill(ok([]))`
    - `GET /record/api/records/users/{userId}/equipped-badge` → `route.fulfill(ok(null))`
    - `PUT /record/api/records/my/badges/{code}/wear` → `route.fulfill(ok(null))`
    - `PUT /record/api/records/my/badges/unwear` → `route.fulfill(ok(null))`
- **R2（LeaderboardService 好友 ID 直调改造）**：
  - 改造 `LeaderboardService.fetchAllFriendIds`：单次直调 `userApi.listFriendIds(userId)`，判空与软降级保持一致；
  - 清理无用静态常量 `FRIEND_FETCH_SIZE` 与对应过时 javadoc；
  - 同步更新 `LeaderboardServiceTest` 与 `LeaderboardCacheInvocationTest` 中的测试桩从 `listFriends` 迁移至 `listFriendIds`，保持 110 个单测全部通过。
- **R3（sharding.yaml 规则校正）**：
  - 在 `record-service/src/main/resources/sharding.yaml` 的 `!SINGLE` 列表移除 `ds0.leaderboard_contribution`，加入 `ds0.sport_goal` 和 `ds0.user_badge`。

---

## 4. 门禁要求

1. **Surefire 测试全量只增不减**：
   - 全仓七模块 1063 测保持（0 失败、0 错误、0 跳过）；前端 vitest 63 测保持；
2. **静态检查保持 0 违规**：
   - `mvn-verify.sh --mode=offline --static=leaderboard-service` 退出码 0（0 Checkstyle violations）；
   - `mvn-verify.sh --mode=offline --static=record-service` 退出码 0；
3. **契约门**：
   - `bash scripts/verify/mailbox-contract.sh --open=TASK-271 --baseline=4c85635` 退出码 0；
4. **Token 门（29 项历史指标）**：
   - 改动文件内 29 项指标零命中，C-01 保持不变，C-02 自枚举各 +1；
5. **词面门**：
   - 四 locale 全 `ZERO_HIT`（退出码 1）；
6. **差异与格式检查**：
   - `git diff --check` 输出为空，纯 LF，无 BOM。

---

## 5. 实施纪律

- 遵循双提交纪律（C-01 业务/配置/测试 + C-02 台账）；
- 封盘前严禁提前向远程推送。
