【回传】TASK-005 修复 P0——TransactionConfig 让 leaderboard-service 起不来

## 结论

P0 已修，2 条常驻上下文级测试落码，全量 **39 条 0 失败 BUILD SUCCESS**。业务代码只动了 `TransactionConfig.java` 一个文件。

## 改动文件（全部）

| 文件 | 动作 |
| --- | --- |
| `leaderboard-service/src/main/java/com/sportverify/leaderboard/config/TransactionConfig.java` | 删自建 `transactionManager()` bean + 删 `@EnableTransactionManagement`，只留 `@Configuration` 空壳 + 说明 |
| `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/TransactionConfigTest.java` | 新建，1 条用例 |
| `leaderboard-service/src/test/java/com/sportverify/leaderboard/aop/TransactionLogAspectTest.java` | 新建，1 条用例 |

未碰：`mq/`、`CacheConfig`、任何 Service/Controller、`pom.xml`。**未 commit、未 push**。
注：`git status` 里 `LeaderboardService.java` / `LeaderboardServiceTest.java` / `application.yml` 等 M 是 TASK-001~004 遗留的未提交改动，不是本任务产生的（本任务对该文件的临时变异已按备份精确还原，`Transactional` 计数已复验为 0）。

## 第 1 步 RED 证据（复现主 agent 的探针）

用例名：`TransactionConfigTest.transactionManagerIsAutoConfiguredInsteadOfHandBuilt`

```
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
BeanCreationException: Error creating bean with name 'transactionManager' defined in
  com.sportverify.leaderboard.config.TransactionConfig: Property 'dataSource' is required
Caused by: java.lang.IllegalArgumentException: Property 'dataSource' is required
```

与派发词的探针一致，且证明了两点：该 bean 名与 Boot 自动装配的 bean 名**同为 `transactionManager`**（探针打印 `txManagers=[transactionManager]`），所以"顶掉自动装配"是真的，而 Bean 名断言区分不出手搓/自动装配——这就是下面第二段 runner 存在的原因。

## 第 2 步 GREEN + 变异验证

`mvn -B -ntp -pl leaderboard-service -am test -Dtest=TransactionConfigTest` → `Tests run: 1, Failures: 0` / BUILD SUCCESS。

变异 1（禁用改法：注入 DataSource 再 new 一个，`setDataSource` 补对）：
第一段 runner（带 Boot 自动装配）**照样全绿**——单靠它会漏。红在第二段刻意摘掉自动装配的 runner：
```
Expecting: <Started application [...beanDefinitionCount = 8]>
not to have any beans of type: <org.springframework.transaction.PlatformTransactionManager>
but found: <["transactionManager"]>
```
即：一个不带 Boot 自动装配的上下文里，只要 `TransactionConfig` 还贡献任何 `PlatformTransactionManager` 就红。已按 `/tmp` 备份还原。

## 删 `@EnableTransactionManagement` 的依据（Boot 3.2.4 实测 + 字节码）

- 一次性探针（跑完已删）在**不注册 `TransactionConfig`**、只挂 `AopAutoConfiguration` + `DataSourceTransactionManagerAutoConfiguration` + `TransactionAutoConfiguration` 的上下文里打印：
  `transactionInterceptorPresent=true`、`txAdvisor=[org.springframework.transaction.config.internalTransactionAdvisor]`
  → Boot 确实自己开了注解式事务。
- `javap` 看 `TransactionAutoConfiguration$EnableTransactionManagementConfiguration`：
  `@ConditionalOnBean(TransactionManager.class)` + `@ConditionalOnMissingBean(AbstractTransactionManagementConfiguration.class)`，`@EnableTransactionManagement(proxyTargetClass=…)` 在其嵌套 `Cglib/JdkAutoProxyConfiguration` 上，由 `spring.aop.proxy-target-class` 选择。
  → 反过来讲：应用里那个裸 `@EnableTransactionManagement` 一直在**压住** Boot 这条（`@ConditionalOnMissingBean`），删掉后代理口径回到 Boot 默认 CGLIB。

## 第 3 步 全量数字（对账）

| 轮次 | Tests run | 结果 |
| --- | --- | --- |
| 基线（动手前现场复跑） | 37, Failures: 0 | BUILD SUCCESS |
| 第 2 步修完（派发词的 38 闸口） | **38, Failures: 0** | BUILD SUCCESS |
| 加切面测试后最终 | **39, Failures: 0** | BUILD SUCCESS |

39 = 37 基线 + `TransactionConfigTest`(1) + `TransactionLogAspectTest`(1)。派发词的"38（37+1）"是在第 3 步"另补一条测试"之前写的，多的 1 条来自那条追加要求，不是漏账。

## 切面空切：证明 + 分析（只分析，未加任何注解）

### 证据

`grep` 复核：main 源码里 `@Transactional` 命中数 0（唯一命中是 `aop/TransactionLogAspect.java:31` 的切点注释）。
`TransactionLogAspectTest` 按生产装配方式起真上下文（`TransactionConfig` + 上述三个 AutoConfiguration + 真 `LeaderboardService`/`LeaderboardController`），断言两者 `AopUtils.isAopProxy(...)` 均 false → 切面一个 bean 都没拦到。当前绿。

变异 2（给 `LeaderboardService#applyVerified` 临时加 `@Transactional`）→ 该用例红，消息即断言里的 `as(...)`：
```
[LeaderboardService 被代理了：说明有 @Transactional 进了事务边界，切面不再是空切]
Tests run: 1, Failures: 1
```
探针同时打印出点亮后的 advisor 链（顺序即调用顺序）：
```
ExposeInvocationInterceptor.ADVISOR
InstantiationModelAwarePointcutAdvisorImpl -> transactionPointcut() / logTransaction(ProceedingJoinPoint)   ← 切面，最外
BeanFactoryTransactionAttributeSourceAdvisor -> TransactionInterceptor                                       ← 事务，最内
```
两点附带事实：切面 `@Order(HIGHEST_PRECEDENCE+1)` 落在事务拦截器**外侧**，所以真有了事务边界时它打的"提交/回滚"日志位置是对的；而带这个变异跑全量是 `41 跑 1 红`（红的就是这条新测试）——**其余 38 条对"给榜单写路径加 @Transactional"毫无反应**，这条测试是目前唯一的闸。

### 分析：三条路径该不该有事务边界

**裁定：三条都不该加。** 依据不是"加了没好处"，而是 ADR-0009 第 4 节明列禁止（`applyVerified`/`rollbackOnRejected`「禁止把 Redis 纳入事务」、`settleAndReconcile`「保持最终一致」），且实测加了会**主动破坏**两个现有不变量：

1. **提交前释放锁（正确性）。** 两条写路径都是 `tryLock(lock:rollback:{recordId})` → DB 写 → `zincrby` → `finally unlock`。`@Transactional` 的边界是方法进出，**commit 在 finally 之后**：解锁时锚点行还没提交。并发对手机械地撞进去——`applyVerified` 插入(未提交)后解锁，`rollbackOnRejected` 立刻拿锁 `selectById` 在 READ_COMMITTED 下**看不见那行** → 走 `无贡献不回滚` 分支直接 return → 提交后这条贡献永久 ACTIVE、永不回滚（只能等下一次改判事件或人工）。今天的 autocommit 之所以安全，恰恰因为"提交在解锁之前"。ADR-0009 第 57 行对 `FriendService` 写的是同一个坑并明令"不借机重构"。
2. **幂等闸门依赖"提交先于危险写"（会重复扣分/重复加分）。** `zincrby` 是 Redis 写，事务回滚不回它。今天的链路：`updateStatus` autocommit 落库 → `zincrby` 抛（例如 socket 超时但命令实际已生效）→ `mq/LeaderboardEventConsumer.java:182-189` catch 后删去重键 + `RECONSUME_LATER` → 重投时 `updateStatus` 影响 0 行 → 幂等跳过 → ZSet 偏差交给结算纠偏。加事务后：那次 `updateStatus` 被回滚成 ACTIVE → 重投时又影响 1 行 → **再扣一次**。`applyVerified` 镜像同构（`insertIgnore` 回滚 → 重投重新插 + 再 `ZINCRBY +distance` → 重复加分）。
3. **MQ 重投行为本身的其余影响（不改变上面的裁定）。** ①`LeaderboardService.java:127/171` 拿不到锁抛的 `IllegalStateException` 发生在"锁等 3s"期间，加事务后这条路径会**从方法入口就占用一条 DB 连接**直到等锁结束：消费并发 × 3s × 重试，HikariCP（Boot 默认 10）可被打穿，与 verify-service 抢同一个 namesrv；②`settleAndReconcile` 里 `markSettled` 是**单条批量 UPDATE**，本就原子，包进事务只是把连接和（若将来改成 `FOR UPDATE`）行锁横跨 `ZADD/ZREM` 多次 Redis 往返；它跑在 `@Scheduled` 单线程上（全仓 `@Async` 命中 0），长事务会顺带拖住同服务其它定时任务；③默认 `rollbackFor` 只管 RuntimeException，本链路异常确实都是 unchecked，但要与仓库既有先例一致得显式写 `rollbackFor = Exception.class`（见 `record-service` 的 `SportRecordService.submit:70`）——这属于"真要加时"的细节，不是建议加。
4. **`applyVerified` 里那两条 DB 写不构成原子对。** `insertIgnore` 返回 1 时不走 `updateStatus`，返回 0 时才走——互斥分支，不存在"两写必须同进退"的前提。仓库里唯一满足该前提的是 ADR-0009 已补过的 `RecordLikeService.flushPendingLikes`（用 `TransactionTemplate` 只包两次 DB 写、`trimConsumed` 留在事务后）。榜单没有对应物。

### 若目标就是"让切面有意义"

按代价排序，三条都不需要给榜单加 `@Transactional`：

- **A 改切点，不加注解（推荐给主 agent 拍板）**：把切点从 `@Transactional` 换成本模块真实存在的边界——`mq/LeaderboardEventConsumer#handleMessage`（重试/DLQ 日志）或 `@Scheduled`/`@XxlJob` 方法。代价：日志文案里的"事务开始/提交成功/回滚"对无事务路径是假话，切面得改名（如事件/任务执行日志），`log.info(..., joinPoint.getArgs())` 要打具体参数别打整个 DTO（榜单只有 recordId，暂无 PII；若将来指向 user 域就是隐私面）。
- **B 直接删掉切面（我倾向这个）**：它今天 0 命中，而 `LeaderboardService`/`LeaderboardEventConsumer` 已经在每个边界上手写中文日志（`入榜成功`/`回滚成功`/`消费榜单事件失败，等待重投`/`已投递死信队列`）。留着 `[TRANSACTION]` 前缀反而误导读日志的人以为榜单有事务。删 main 源码文件超出本次"只改 TransactionConfig.java"的授权，所以只提不做。
- **C 保持现状**：接受"装饰件"，本任务已用 `TransactionLogAspectTest` 把它空切这件事钉成常驻断言——将来谁想加注解，必须先面对这条红。

### 待主 agent 裁定

1. `TransactionConfig` 现在是无成员空壳（`@Configuration` 只为保留 ADR-0009/切面的说明锚点）。**要不要连文件一起删**（服务照样由 Boot 自动装配，`TransactionConfigTest` 第二段 runner 会失去被测对象，需同步调整）？我按"只改该文件"保留了它。
2. 切面按上面 A/B/C 哪条走？我未动 `aop/` 下任何代码。
3. 第 3 步"另补"的那条测试让全量从 38 变 39，若 38 是硬闸口，请指一条该删的（我不建议删）。
