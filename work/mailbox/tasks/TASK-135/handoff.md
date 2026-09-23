# TASK-135 Handoff

## 结论

旧实现的 Spring 代理判别式失败：`LeaderboardController#leaderboard` → 容器代理 `LeaderboardService#top` → `topOverall` 的 self-invocation 绕过了位于 `topOverall` 上的 `@Cacheable`，同尺寸总榜两次调用底层 ZSet 两次。

已做最小修正：把同一总榜缓存接到正常入口 `LeaderboardService#top`，用 condition 只匹配 overall，并移除 `topOverall` 上不可由正常入口触发的缓存注解。缓存名、TTL、总榜返回范围未扩大；好友榜没有 `@Cacheable`。

## 编号与基线

- 任务编号：`TASK-135`。编号核对结果：现有 `work/mailbox/tasks` 与 `PLAN.md` 已占用编号截至 `TASK-134`（另有历史空档，不属于当前连续待办）；本任务取首个未占用连续编号，不占用既有待办。
- 开工基线：`a771da381389359439f1c7da5a19ac72e319c85b`。
- 未 push、未建 PR、未提交；`.trae/` 是开工前已存在的未跟踪目录，本任务未触碰。

## 编号澄清

旧交接文档曾把 `TASK-135` 用作 F15/F16 点赞事项的**候选编号**；该编号未被那些点赞事项实际占用。实际 `TASK-135` 现为本总榜缓存任务，F15/F16 点赞事项以后另编号。

## 根因证据

- `LeaderboardController#leaderboard` 只调用 `leaderboardService.top(...)`。
- 旧实现 `top(...)` 内部直接调用同类 `topOverall(...)`；Java self-invocation 不经过 Spring 代理。
- 旧 `@Cacheable` 位于 `topOverall(int)`，所以从容器代理取得 `top(...)` 后仍会绕过缓存拦截器。
- 普通 Mockito 直接实例化 `LeaderboardService` 的既有测试未被用作缓存判据；本任务新增判别式通过 `ApplicationContextRunner`、`@EnableCaching`、容器注入的 `LeaderboardController` 和 `AopUtils.isAopProxy` 验证。

## 红绿取证

### 旧实现红测（先于生产代码修正）

命令：

```text
mvn -s .mvn-settings.xml -q -pl leaderboard-service -am "-Dtest=LeaderboardCacheInvocationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：退出码 `1`。新增判别式测试失败，关键证据为：

```text
Wanted 1 time: zSetOperations.reverseRangeWithScores("leaderboard:overall", 0L, 9L)
But was 2 times
-> LeaderboardService.topOverall(LeaderboardService.java:235)
-> LeaderboardService.topOverall(LeaderboardService.java:235)
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
```

### 修正后相关测试

命令：

```text
mvn -s .mvn-settings.xml -q -pl leaderboard-service -am "-Dtest=LeaderboardCacheInvocationTest,CacheConfigTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：退出码 `0`；`LeaderboardCacheInvocationTest` `3/0/0/0`，`CacheConfigTest` `8/0/0/0`。其中：

- Spring 容器代理的 Controller 正常入口连续两次同尺寸总榜查询，ZSet 查询只发生一次；
- 好友榜连续两次调用仍逐次调用 `UserApi.listFriends` 与 ZSet；
- `UserApi` 故障时好友榜返回空榜，且不查询 ZSet；
- `CacheConfig` 的缓存名 bean 解析、L1/L2 写穿/回填/失效测试仍通过。

### 仓库目标模块验收入口

命令：

```text
bash scripts/verify/mvn-verify.sh --mode=offline --pl leaderboard-service test
```

结果：退出码 `0`，`BUILD SUCCESS`；目标模块日志汇总 `Tests run: 57, Failures: 0, Errors: 0, Skipped: 0`。

### mailbox 契约入口

命令：

```text
bash scripts/verify/mailbox-contract.sh --baseline=a771da381389359439f1c7da5a19ac72e319c85b
```

结果：退出码 `1`，但 `TASK-135：判据 B 通过（只改清单与实际改动集一致）`。总体失败来自既有在途任务与共享工作树公共文件交叠（历史任务报告的 `PLAN.md`/公共路径过冲），不是 TASK-135 的两件套或清单不一致。

### 目标静态入口

因缓存配置注释及缓存接线相关测试断言随入口迁移而同步，补跑：

```text
bash scripts/verify/mvn-verify.sh --mode=offline --static=leaderboard-service
```

结果：退出码 `0`，`BUILD SUCCESS`；Checkstyle `0 violations`，SpotBugs `Error size is 0`，PMD `check` 成功。SpotBugs 报告的 9 个 Medium 为既有构造器注入/数据流条目，未新增高危失败项。

## 实际改动清单

- `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
- `leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java`
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/CacheConfigTest.java`
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java`
- `work/mailbox/tasks/TASK-135/spec.md`
- `work/mailbox/tasks/TASK-135/handoff.md`
- `work/mailbox/PLAN.md`

## 变更边界核对

- 未给好友榜添加 `@Cacheable`；缓存条件明确限定 `type=overall`。
- 未修改 `CacheConfig` 的 TTL（仍为 5 分钟）、缓存名或 L1/L2 实现；仅同步 Javadoc 中的真实入口说明。
- 未修改好友分页、好友 API、user-service 或其他业务服务。
- 未引入依赖，未修改总榜查询的 `size` 上限/返回数据范围。

## 未覆盖项

- 未连接真实 Redis 做本任务新增的端到端 Controller→缓存→ZSet 验证；判别式使用 Spring 容器 + 内存 `CacheManager`，底层 ZSet 为 Mockito 计数桩。
- 未运行真实 user-service 跨服务调用；好友服务逐次调用和故障空榜均已在 Spring 代理上下文中用契约桩覆盖。
- `online` 依赖口径、CI、外部门槛未覆盖；本地 offline 结论不升级为 CI 通过。
- 未运行真实 MySQL/RocketMQ IT；本任务不涉及这些路径。

## 审核边界

当前只提供未提交工作树的 diff 与上述本地证据，等待审核；不 push、不建 PR、不宣称 CI 通过。
