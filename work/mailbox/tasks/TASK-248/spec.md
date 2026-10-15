# TASK-248 任务书（spec.md）

> **课题**：B23 覆盖增强——好友榜 15s 本地缓存「空榜不缓存防背锅」机制补齐显式单测断言  
> **级别**：MEDIUM 档（纯测试新增，零生产代码改动）  
> **责任服务**：`leaderboard-service`（仅测试目录）  
> **开工基线**：`<立项笔哈希>`（main，工作树干净）  
> **状态**：待实施

---

## 1. 背景与缺口

TASK-246 B23 为 `LeaderboardService.topFriends` 引入 15s Caffeine 本地缓存（Key=`userId:size`，上限 2000）。
实现中「空榜不缓存」为防背锅关键分支：`topFriends` 仅在 `!result.isEmpty()` 时回填缓存
（`LeaderboardService.java` L285-288，注释口径：降级空榜短暂恢复后应立刻能拉出数据，不背着旧空榜）。

**既有覆盖**（侦察结论）：
- `LeaderboardServiceTest.topFriend_secondCallHitsLocalCache_friendFeignNotRepeated`：非空榜二次调用命中缓存（Feign/Pipeline 各 1 次）；
- `LeaderboardServiceTest`（降级空榜用例）与 `LeaderboardCacheInvocationTest.friendServiceFailureStillReturnsEmptyBoardThroughProxy`：空榜路径仅有**单次调用**的返回语义断言。

**缺口**：无任何测试显式断言「空榜结果不进入本地缓存——第二次调用必须重新穿透拉取，
且依赖恢复后第二次调用立刻返回真实数据」。若该分支被误改（如去掉 `isEmpty` 判定），现有测试全绿，缺陷静默潜伏 15s 窗口。

---

## 2. 严格改动文件白名单

实施侧仅允许在以下白名单内修改，禁止超出范围：

1. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java`（仅新增测试方法）
2. `work/mailbox/tasks/TASK-248/spec.md`
3. `work/mailbox/tasks/TASK-248/handoff.md`

**零触碰红线声明**：
- 一切生产代码（任何模块 `src/main`）绝对零触碰——本课题为纯测试新增，出现任何生产代码改动即违规红线；
- `LeaderboardServiceTest.java` 等其他既有测试文件零触碰（新断言统一落在 `LeaderboardCacheInvocationTest`，与 B23 缓存判别式同家）；
- 根与各子模块 `pom.xml`、`web/package.json` 绝对零触碰（零新外部依赖，Caffeine 已在依赖树内）；
- `work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 绝对禁止子 Agent 触碰。

---

## 3. 判别式与实现契约

在 `LeaderboardCacheInvocationTest` 内新增两个测试方法（复用既有 `CachingTestConfiguration` Spring 代理上下文，沿仓内「缓存判别式必须经真实入口」口径）：

### R1 降级空榜不缓存 + 恢复立即可见（核心防背锅语义）
- 桩：`userApi.listFriends(7L, 1L, 1000L)` 首次 `thenThrow`（模拟 user-service 不可用）；
- 第一次 `service.top("friend", 7L, 10)`：断言返回空榜（既有降级口径不回归）；
- **重打桩坑（沿 `LeaderboardServiceTest` 既有注释先例）**：对已 `thenThrow` 的桩重打桩必须用
  `doReturn(...).when(userApi).listFriends(...)`（`when` 写法会先触发旧桩抛异常），
  返回正常好友页（1 个好友）+ `redis.executePipelined` 返回 `[42.5]`；
- 第二次 `service.top("friend", 7L, 10)`：
  - 断言返回**非空**（恢复后立刻拉出数据，不被旧空榜背锅）；
  - `verify(userApi, times(2)).listFriends(7L, 1L, 1000L)` —— 空榜未被缓存，两次均真实穿透；
  - `verify(redis, times(2)).executePipelined(any(RedisCallback.class))`。

### R2 无好友空榜同口径不缓存
- 桩：`userApi.listFriends(8L, 1L, 1000L)` 返回空页（total=0，无好友路径）；
- 连续两次 `service.top("friend", 8L, 10)` 均返回空；
- `verify(userApi, times(2)).listFriends(8L, 1L, 1000L)` —— 两次均穿透，空榜未进缓存。

### 禁止事项
- 禁止用反射直取 `friendLeaderboardCache` 私有字段断言（行为断言优先，与仓内既有 mock 风格一致）；
- 禁止修改任何生产代码使测试变绿；禁止删弱/改写既有测试断言；
- 新增测试须各自独立 `@Test`，不与既有用例混合断言。

---

## 4. 本地门禁要求

1. **后端单测**（离线分级收窄——仅触碰 leaderboard-service 测试，其余模块凭白名单外零 diff 论证不变）：
   `& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline --pl leaderboard-service test` 全绿（测试数只增不减）；
2. **前端回归**（零前端改动，按惯例照跑）：`cd web && pnpm run type-check && pnpm test` 全绿；
3. **契约门禁**（Git Bash）：`bash scripts/verify/mailbox-contract.sh --open=TASK-248 --baseline=<开工基线>` 退出码 0；
4. **token 门**：29 项 `git grep -cF` 只增不减；**词面门**：CI 权威口径 `git grep -n -I -iE` 零命中（本机验证须显式 `-E` 并做探针阳性对照）。

---

## 5. 双提交规范

- **C-01 实施提交**：`test(leaderboard): 补齐好友榜空榜不缓存显式断言（TASK-248）`——仅文件 1；
- **C-02 台账提交**：`docs(mailbox): 登记TASK-248实施完成与门禁回填（TASK-248）`——仅文件 2、3。
