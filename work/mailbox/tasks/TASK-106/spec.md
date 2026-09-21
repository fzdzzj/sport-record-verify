# TASK-106 CacheConfig 的 @Primary 补测（P0-3 变异未取到红的遗留）

## 目标
`leaderboard-service` 的 `CacheConfig.hierarchicalCacheManager` 上挂着 `@Primary`，但全仓没有任何测试能观测它——
P0-3（2026-09-20）实测：摘掉 `@Primary` 后 `CacheConfigTest` 仍 7/7 绿（`javap` 已确认注解不在字节码里）。
本任务补一条测试，使"摘掉 `@Primary`"当场变红，把这条静默失效接上哨兵。

**只补测试，不改 `CacheConfig` 生产代码。**

## 先读文件
- `leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java`（54-58 行：`@Bean @Primary` 的 `hierarchicalCacheManager`；两个裸 manager 是 `private static` 方法、不是 bean）
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/CacheConfigTest.java`（55-57 行共享 `runner`；59-69 行 `onlyHierarchicalCacheManagerIsExposed`；174-186 行 Redis 桩）
- `docs/adr/`（缓存/事务边界相关决策，确认 `@Primary` 存在的理由写在哪）

## 范围外（越界即视为未验收）
- 不改 `CacheConfig.java` 的两层读写逻辑，不改其 bean 形状（不加也不删 `@Primary`、不把裸 manager 提升为 bean）
- 不改 `CacheConfigTest` 既有 7 条测试的任何断言内容，不改共享 `runner` 字段
- 不改任何 `pom.xml`、不新增依赖（`.m2-repo` 离线集合外的一律不可用）
- 不动 `LeaderboardService.java` / `LeaderboardServiceTest.java` / `application.yml`（P0-3 刚落地）
- 不 commit、不 push

## 要做的修改
只改 `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/CacheConfigTest.java`，新增一条测试：

1. **另起一个私有 runner，不要往共享 `runner` 上加 bean。** 共享 `runner` 一旦被塞进第二个 `CacheManager`，
   `onlyHierarchicalCacheManagerIsExposed` 的 `hasSingleBean(CacheManager.class)` 与
   `getBeanNamesForType(...).containsExactly("hierarchicalCacheManager")` 会连带变红——那是既有断言被改写，属越界。
   新测试自带 runner：`withUserConfiguration(CacheConfig.class)` + `withBean(RedisConnectionFactory.class, this::stubRedisConnectionFactory)`
   （复用 174-186 行现成的桩）+ 一个**次优于**目标的 `CacheManager` bean（如 `ConcurrentMapCacheManager`，160-161 行已在用）。
2. **断言按类型解析落到 `hierarchicalCacheManager` 上**，例如
   `assertThat(context.getBean(CacheManager.class)).isSameAs(context.getBean("hierarchicalCacheManager", CacheManager.class))`。
   不要断言"容器里有 2 个 CacheManager"（那样无论有无 `@Primary` 都绿，测不到东西）。
   期望语义：有 `@Primary` → 按类型取到 ours；摘掉 `@Primary` → Spring 抛 `NoUniqueBeanDefinitionException` → 本条红。
3. javadoc 按该文件既有风格写明这条守的是什么失效（多 bean 时按类型注入歧义），以及为什么 `@Primary` 不是装饰。

## 变异验证（必须做，不接受"写完就绿"）
补测的意义在于它能变红，两轮都要跑并留原文：

- 基准：`cd /d/code/sports && mvn -B -ntp -o -s .mvn-settings.xml -pl leaderboard-service -am test` → 全绿，记下 `Tests run`（本任务前为 39）
- 变异：临时摘掉 `CacheConfig.java:55` 的 `@Primary` → **新测试必须变红**；记下红在哪个方法哪一行、异常类型原文。
  既有 7 条应保持绿（若它们也红，说明第 1 条约束被破坏）
- 还原：`@Primary` 加回，`cmp` 与备份零差异后复跑 → 全绿

两条还原陷阱（P0-3 实测踩过）：
- **还原源文件别用 `cp -p`**：mtime 会退回备份时刻、比变异版 `.class` 还旧，maven 增量判定"无需重编"，
  于是还原后照样红一次。用不带 `-p` 的 `cp`，或还原后 `touch` 源文件。
- 想分清"变异没生效"和"测试测不出"，直接看字节码：
  `javap -v -p -cp leaderboard-service/target/classes com.sportverify.leaderboard.config.CacheConfig | grep -c Primary`
  （摘掉时 0，还原后 2）

## 验收命令（规范口径，缺 `-o -s` 视为未验收）
1. `cd /d/code/sports && mvn -B -ntp -o -s .mvn-settings.xml test` → 全仓 BUILD SUCCESS，总 `Tests run` = 269（本任务前 268 + 新增 1 条）
2. 单跑新测试：`mvn -B -ntp -o -s .mvn-settings.xml -pl leaderboard-service -am test "-Dtest=CacheConfigTest" "-Dsurefire.failIfNoSpecifiedTests=false"` → 全绿
   （注意属性名是 `surefire.failIfNoSpecifiedTests`，不带前缀的 `failIfNoSpecifiedTests` 会让 common 模块报 "No tests matching pattern" 假失败）

## 完成定义
- 写 `work/mailbox/tasks/TASK-106/handoff.md`：新测试文件:行 / 变异红的原文（异常类型 + 断言位置）/ 还原后复跑结果 /
  `CacheConfig.java` 还原零差异证据 / 「待主 agent 决定」清单（没有写无）
- 回报 ≤200 字：产出 / 校验结果 / 待决策项

## 约束
- 你是 `CacheConfigTest.java` 的唯一写入者；`CacheConfig.java` 仅允许"摘 `@Primary` → 复跑 → 还原零差异"这一种临时改动
- 最多 1 次修复重试；不准猜测，缺信息写 handoff「待主 agent 决定」
- 若发现补不出能变红的测试（例如 `@Primary` 在当前 bean 形状下确实无法被观测），**不要为了让任务过而放宽断言**：
  停下来把证据写进 handoff「待主 agent 决定」，候选出路是"删掉冗余的 `@Primary`"或"把裸 manager 提升为 bean 以恢复歧义场景"
