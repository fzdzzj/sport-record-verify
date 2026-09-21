# TASK-107 Handoff

**实现方：主 agent 本人**（用户指令"实现"，非子会话产物）。未 commit、未 push。

## 结论

L2（Redis 层）的字节存活与 TTL 首次被钉住。`Tests run` 39 → **42**，全仓 268 → **271**，规范口径 `BUILD SUCCESS`。

## 改动

- 新建 `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/HierarchicalCacheRedisRoundTripTest.java`（3 条用例）
- 生产代码 `CacheConfig.java` **零改动**（只做过下述两次临时变异，均已还原）
- 未碰 `CacheConfigTest.java`、未碰 pom、未新增依赖

## 用例与断言落点

| 用例 | 钉住什么 |
|---|---|
| `writeGoesThroughRealJdkSerializationWithFiveMinuteTtl` | 键必须是 `leaderboard:overall::10`（前缀+`StringRedisSerializer` 口径）；字节用 `JdkSerializationRedisSerializer` 能还原出等值 List（排除 toString／空数组）；捕获的 `Expiration.getExpirationTimeInSeconds() == 300` |
| `secondInstanceReadsBackWhatFirstWroteIncludingBigDecimalScale` | 同一份字节起**第二个** manager（等价另一实例／L1 冷启）读回等值，且 `BigDecimal.scale` 逐条相等——只比 `equals` 抓不住 `12.50 → 12.5` 的精度漂 |
| `secondInstanceValueLoaderIsNotInvokedWhenL2HasTheValue` | L2 命中时 `get(key, Callable)` 的回源 loader 调用次数必须为 0 |

装配入口用 `new CacheConfig().hierarchicalCacheManager(factory)`（即生产的 `@Bean` 方法），**没有**在测试里重抄一遍 `RedisCacheConfiguration`，否则测的是副本而非接线。
`stringCommands()` 与 `keyCommands()` 返回同一个假件（合并成 `StringAndKeyCommands` 接口）：写路径在小版本间会在两者之间漂移，分开桩会静默漏测。

## 红凭据（两轮变异，均现场跑）

**变异 A** — `CacheConfig.java:89` 值序列化器换成 `GenericJackson2JsonRedisSerializer`：

```
Tests run: 3, Failures: 0, Errors: 3 -- in HierarchicalCacheRedisRoundTripTest
org.springframework.data.redis.serializer.SerializationException: Cannot deserialize
  at CacheConfig$HierarchicalCache.get(CacheConfig.java:150)   // secondInstanceReadsBack... :123
  ...secondInstanceValueLoaderIsNotInvokedWhenL2HasTheValue :143
  ...writeGoesThroughRealJdkSerialization... :111
同时 CacheConfigTest: Tests run: 7, Failures: 0  ← 既有套件对"序列化器接错"完全无感
```

这条附带一个**好消息**：序列化器不匹配时是当场抛 `SerializationException`，不会静默回退成"看着命中其实拿默认值"。

**变异 B** — `CACHE_TTL` 改为 `Duration.ofMinutes(1)`：

```
Tests run: 3, Failures: 1, Errors: 0
expected: 300L
 but was: 60L
```
只有 TTL 那条红，另两条不受影响，分工正确。

**还原证据**：`grep` 现值 `Duration.ofMinutes(5)`（:49）、`new JdkSerializationRedisSerializer()`（:89）、
`@Primary`（:55）三处到位，`grep -c GenericJackson` = **0**；还原后复跑全仓 BUILD SUCCESS。

## 踩到的两个坑（写下来给后面的人）

1. `Expiration` 在本仓版本里是 `org.springframework.data.redis.core.types.Expiration`
   （不是 `connection.types`），且没有 `getExpirationTime(TimeUnit)`，只有 `getExpirationTimeInSeconds()`
   ——照 `CacheConfigTest` 抄 import 才对。
2. `-Dtest=A+B` **不是**多测试类的合法分隔符，surefire 会一条都不跑，配合
   `-Dsurefire.failIfNoSpecifiedTests=false` 就得到一次**假绿**（我第一次跑变异 A 就被这个骗过，
   看到 BUILD SUCCESS 差点收工）。正确写法：`-Dtest=A,B`。
   另：Windows 下 mvn 输出行尾带 `\r`，用 grep 抓 `Tests run` 汇总行时**不要加 `$` 锚点**，否则一律落空。

## 待主 agent 决定

无。
