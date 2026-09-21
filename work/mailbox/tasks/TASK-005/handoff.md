【回传】D8+D9 删除空壳配置与空切面——ADR-0009 哨兵已改名并证明仍会变红

## 结论

D8、D9 已落码。**账对不上：预期 38，实际 39**（未硬凑，见下"数字对账"）。红-绿凭据齐：哨兵在变异下确实变红，还原后全绿 BUILD SUCCESS。

## 改动文件（全部）

| 文件 | 动作 |
| --- | --- |
| `leaderboard-service/src/main/java/com/sportverify/leaderboard/aop/TransactionLogAspect.java` | 删除（连同已空的 `main/.../aop/` 目录） |
| `leaderboard-service/src/main/java/com/sportverify/leaderboard/config/TransactionConfig.java` | 删除（`config/` 目录保留，内含 `CacheConfig.java` 未碰） |
| `leaderboard-service/src/test/java/com/sportverify/leaderboard/aop/TransactionLogAspectTest.java` | 删除（连同已空的 `test/.../aop/` 目录），语义迁至下行 |
| `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardWritePathStaysUnproxiedTest.java` | 新建（由上条改名而来），1 条用例 |
| `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/TransactionConfigTest.java` | 改写，1 条用例（第二段 runner 已删） |
| `work/mailbox/rollback/TASK-005-aspect.patch` | 新建，删前备份 |

未碰：`mq/`、`CacheConfig.java`/`CacheConfigTest.java`、任何 `pom.xml`、任何 Service/Controller 生产代码。**未 commit、未 push**。
`git status` 里 `LeaderboardService.java` / `application.yml` / `LeaderboardServiceTest.java` / `pom.xml` 的 M 是 TASK-001~004 遗留未提交改动；本任务对 `LeaderboardService.java` 的临时变异已按 md5 精确还原（见下）。

## 删除理由（按派发词只写这里，不进代码注释）

- **D8 `TransactionLogAspect`**：切点是 `@within/@annotation(Transactional)`，而 leaderboard-service 生产代码 `@Transactional` 恒为 0（本轮复验：`grep -rn Transactional src/main/java` 命中 **0**）→ 永久空切，记录的"事务开始/提交/回滚"在这条链路上是假话，`[TRANSACTION]` 前缀反而误导读日志的人。附带风险：`log.info(..., joinPoint.getArgs())` 把入参整体落 INFO，TASK-023 脱敏未落。且 Spring 自己在 DEBUG 已打 begin/commit/rollback，无信息增量。
- **D9 `TransactionConfig`**：删掉自建 TM 后该类只剩一个无成员 `@Configuration` 空壳，唯一作用是承载 Javadoc，而那段 Javadoc 与 `TransactionConfigTest` 顶部注释重复。事务管理器与注解式事务本就由 Boot 自动装配提供，空壳无装配贡献。

## 第 0 步 patch 证据（已实测可还原）

- 两文件均 untracked，git 救不回，故先存 `work/mailbox/rollback/TASK-005-aspect.patch`：**4564 字节 / 101 行 / 2 个 `diff --git` 段**（非空已确认）。
- **还原命令必须禁掉 autocrlf**（仓库 `core.autocrlf=true`，而这两个文件在工作区是 LF）：
  `git -c core.autocrlf=false apply work/mailbox/rollback/TASK-005-aspect.patch`
- 已做过完整往返：备份到 `/tmp` → 删 → 上述命令 apply → 与 `/tmp` 副本 `diff` **零差异**（`ROUNDTRIP_BYTE_IDENTICAL`）。首轮曾用裸 `git apply`，还原出 CRLF 版本（内容同、行尾不同），故改用上面这条命令复验后才执行真实删除。

## 测试改动（守卫语义逐条对照）

### `TransactionConfigTest`（保留类名，1 条用例）

| 原断言 | 现状 |
| --- | --- |
| `hasNotFailed()` | 保留 |
| `hasSingleBean(PlatformTransactionManager.class)` | 保留 |
| `isInstanceOf(DataSourceTransactionManager.class)` | 保留 |
| **`getDataSource()` 与容器 `DataSource` `isSameAs`**（硬断言） | 保留，仍是本类的落点 |
| 第二段 runner：刻意摘掉 Boot 自动装配，断言 `doesNotHaveBean(PlatformTransactionManager)` | **删**。它守的是"`TransactionConfig` 自己不贡献 TM"，被测类已不存在，无对象可注册 |

第一段 runner 同时去掉了 `withUserConfiguration(TransactionConfig.class)`（类已删），只剩 mock `DataSource` + 两个 Boot 自动装配——这正是生产装配形态。
类名未改：派发词只对切面测试点名"改名"。若主 agent 认为 `TransactionConfigTest` 这个名字也在"引用"，改叫 `TransactionManagerWiringTest` 是一句话的事。

### `LeaderboardWritePathStaysUnproxiedTest`（新建，原 `TransactionLogAspectTest` 的语义承接者）

- 去掉 `withBean(TransactionLogAspect.class)` 与 `withUserConfiguration(TransactionConfig.class)`；
- 上下文口径不变：`AopAutoConfiguration` + `DataSourceTransactionManagerAutoConfiguration` + `TransactionAutoConfiguration` + mock `DataSource` + 真 `LeaderboardService`/`LeaderboardController`；
- 两条 `AopUtils.isAopProxy(...).isFalse()` 原样保留（service / controller 各一条），`as(...)` 前半句逐字保留：`"... 被代理了：说明有 @Transactional 进了事务边界，..."`；后半句原文是"切面不再是空切"，切面已删，改为"违反 ADR-0009 榜单最终一致边界"——**这是文案唯一的语义改动，如不接受请退回**；
- Javadoc 指向 `docs/adr/0009-事务边界.md:37-38`（`applyVerified`/`rollbackOnRejected` 禁止把 Redis 纳入事务、`settleAndReconcile` 保持最终一致）与 `:53`（禁止批量给 Service 铺 `@Transactional`），并写明"加注解会改变 MQ 重投与 Redisson 锁/提交顺序"这条真实后果；
- 落点包：与被守卫的 `LeaderboardService` 同包（`test/.../service/`），与仓库"测试与_subject_ 同包"的既有惯例一致（`CacheConfigTest` 在 `config/`）。

## 红-绿凭据

**红（变异：给 `LeaderboardService.applyVerified` 临时加 `@Transactional`）**

```
org.opentest4j.AssertionFailedError:
[ERROR] LeaderboardWritePathStaysUnproxiedTest.leaderboardWritePathBeansStayUnproxied:54
        ->lambda$leaderboardWritePathBeansStayUnproxied$3:58
        [LeaderboardService 被代理了：说明有 @Transactional 进了事务边界，违反 ADR-0009 榜单最终一致边界]
Expecting value to be false but was true
Tests run: 39, Failures: 1, Errors: 0, Skipped: 0 → BUILD FAILURE
```
用例名 `leaderboardWritePathBeansStayUnproxied`；红的是 service 那条断言（源码 56-58 行），controller 那条未触发。
同一次变异里其余 **38 条全绿**——这条仍是"给榜单写路径加 `@Transactional`"的唯一闸，删掉切面没有削弱它（代理来自 Boot 的事务 advisor，与切面无关）。

**还原**：`md5sum` 还原前后一致（`10f82aedb66b496eb7d77b9d3f57cf05`）；`grep -rn "Transactional" leaderboard-service/src/main/java` 命中 **0**（含 import），未给任何写路径留下注解。

**绿（还原后现场复跑 `mvn -B -ntp -pl leaderboard-service -am test`）**

```
CacheConfigTest 7 | TransactionConfigTest 1 | LeaderboardEventConsumerTest 9
LeaderboardServiceTest 21 | LeaderboardWritePathStaysUnproxiedTest 1
Tests run: 39, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

## 数字对账（停下回传，未硬凑）

派发词预期 39 → 38，理由是"随两个类删除各减 1 条"。这条推理把两类东西混了：

- 本轮删的是**两个 main 源文件**（`TransactionLogAspect`/`TransactionConfig`），它们不携带任何用例；
- 携带用例的是**两个 test 类**，而派发词同时要求"守卫语义一条都不许丢"，故两个 test 类都保留（一个改写、一个改名）——用例数因此不变。

三种口径：删 2 个 test 类 → 37；删 1 个 → 38（正好是预期值，但等于丢掉一条守卫）；两个都留 → **39（本轮回传值）**。
要凑出 38 只能牺牲一条守卫，与"一条都不许丢"直接冲突，所以按后者交，不硬凑。若主 agent 确认 38 是硬闸口，请指一条该删的（我建议删 `TransactionConfigTest` 保留哨兵，因哨兵守 ADR 禁令、前者只防一个已被根因消除的装配错法）。

## 待主 agent 裁定

1. 上面"数字对账"：39 收下，还是指一条该删的测试凑 38？
2. `TransactionConfigTest` 类名要不要随之改为 `TransactionManagerWiringTest`？
3. `as(...)` 后半句从"切面不再是空切"改成"违反 ADR-0009 榜单最终一致边界"是否接受？
4. PLAN.md 的 D8/D9 状态位与"已核实缺陷"表里 `aop/TransactionLogAspect`、`TransactionLogAspect 无测试证明拦截` 两行现已消解，请主 agent 改写（按协议我不动 PLAN.md）。
