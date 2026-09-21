# TASK-107 榜单二级缓存 L2 真序列化往返

## 目标
证明 `hierarchicalCacheManager` 的 L2（Redis 层）**真的能把 `List<LeaderboardDTO>` 写出去、读回来**，
并锁住 TTL=5 分钟。今天没有任何测试做到这点：`CacheConfigTest` 的 Redis 桩把
`stringCommands().set(...)` 整体 mock 掉（写进去的东西立刻消失、读永远 miss），
所以"写穿打到 Redis"只证明了**调用发生过**，没证明**字节存活过**。

## 先读文件
- `leaderboard-service/src/main/java/com/sportverify/leaderboard/config/CacheConfig.java`
  （54-58 行 `@Bean @Primary hierarchicalCacheManager`；`newRedisCacheManager` 里
  key 用 `StringRedisSerializer`、value 用 `JdkSerializationRedisSerializer`、`entryTtl` 5 分钟）
- `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/CacheConfigTest.java`
  （52-57 行共享 runner、174-186 行现成 Redis 桩；**本任务不许改这个文件**）
- `verify-service/src/test/java/com/sportverify/verify/service/RuleCacheServiceSerializationRoundTripTest.java`
  （**范式照抄对象**：用内存 Map 假件承接真实序列化字节，正向断言 + 损坏载荷负向断言）
- `api/.../com/sportverify/api/record/dto/LeaderboardDTO.java`（`implements Serializable`，
  字段 `Integer/Long/String/BigDecimal`）

## 只改文件
- 新建 `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/HierarchicalCacheRedisRoundTripTest.java`

**不许改任何生产代码。** 只允许一种临时改动：变异验证时按第 3 节改 `CacheConfig.java`，改完必须还原并证零差异。

## 要做的测试

1. **内存 Map 假件承接真字节**：自造 `RedisConnectionFactory` 假件，`connection.stringCommands()`
   返回的 mock 上实现
   `set(byte[] key, byte[] value, Expiration exp, RedisStringCommands.SetOption opt)`
   ——把 key/value 的字节数组原样存进一个 `Map<ByteBuffer, byte[]>`（或 `Map<String,byte[]>`，key 用
   `new String(keyBytes, UTF_8)`），并**同时记下 `exp`**。`get(byte[])` 从 Map 取回原字节。
   其余没被写路径用到的命令一律不 stub（Mockito 默认返回 null/0 即可，别顺手 stub 一堆用不到的）。
   注意 `RedisCacheWriter` 可能走 `keyCommands()` 做 exists/delete，需要时一并实现，不要凭空造。
2. **正向断言（写→读跨实例）**：
   - 用假件工厂构造**第一个** `CacheManager`（直接 `new` 或用 runner 都行，但要与被测配置同源），
     取 `leaderboard:overall` 这个 cache，`put(10, List.of(LeaderboardDTO.of(1, 1001L, "阿跑", new BigDecimal("12.50"))))`；
   - 断言 Map 里那个 key 真的有值、且用 `new JdkSerializationRedisSerializer().deserialize(bytes)`
     能还原出等值 List（这一步证"字节确实是 JDK 序列化产物且完整"，不是空数组、不是 toString）；
   - 断言记下的 `Expiration.getTimeoutSeconds() == 300`（TTL=5 分钟，这是 TASK-002 承诺过但无人看守的数）；
   - 用**同一个假件**再构造**第二个** `CacheManager`（等价于"另一个实例"或"L1 刚重启"），
     从它的 cache 里 `get(10)`，断言拿回的 List 与写入等值，且 `BigDecimal` 的 **scale 也一致**
     （用 `assertEquals(0, expected.getDistance().compareTo(actual))` 之外再断
     `expected.getDistance().scale() == actual.getDistance().scale()`）——只比 `equals` 抓不住精度漂移。
3. **变异验证（必须做两轮，不接受"写完就绿"）**：
   - 变异 A：把 `CacheConfig` 的 value 序列化器换成 `new StringRedisSerializer()` → **本任务测试必须红**
     （预期红在反序列化或跨实例读回那一条；记下异常类型与行号原文）；
   - 变异 B：把 `entryTtl(Duration.ofMinutes(5))` 改成 `ofMinutes(1)` → 必须红在 TTL 断言；
   - 两次都要还原，并给出零差异证据。
4. **还原陷阱**（本仓已实测踩过）：源文件在工作区是 LF，`cp -p` 还原会把 mtime 退回备份时刻、
   比变异版 `.class` 还旧，Maven 增量判定"无需重编"，于是**还原后照样红一次**——那是假红。
   用不带 `-p` 的 `cp`，或还原后 `touch` 源文件。
   分不清"变异没生效"和"测试测不出"时看字节码：
   `javap -v -p -cp leaderboard-service/target/classes com.sportverify.leaderboard.config.CacheConfig | grep -c Primary`
   （应为 1）。

## 已知事实，不要重复调查
- 本仓 Redis 栈是 `redisson-spring-boot-starter`（自带 spring-data-redis），**没有 lettuce**，
  也不许新增任何依赖（`.m2-repo` 离线集合外的一律不可用）。
- `LeaderboardDTO` 字段全是可序列化类型，所以"JDK 序列化不了"**不是**本任务的风险点；
  风险点是**接线**（序列化器选错、TTL 丢掉、key 前缀不一致导致读不回）。别去改 DTO。
- `topOverall` 只读 ZSet，不落 DB，缓存里没有"空值穿透"问题，不要顺手加空值哨兵（属越界）。

## 验收命令（规范口径，缺 `-o -s` 视为未验收）
```bash
cd /d/code/sports && mvn -B -ntp -o -s .mvn-settings.xml -pl leaderboard-service -am test
# 本任务前：Tests run: 39, Failures: 0 / BUILD SUCCESS
# 本任务后：Tests run: 39 + 新增条数，全绿
cd /d/code/sports && mvn -B -ntp -o -s .mvn-settings.xml test
# 全仓基线 268，只许变多不许变红
```

## 完成定义
- 写 `work/mailbox/tasks/TASK-107/handoff.md`：新测试文件与用例名 / 变异 A、B 红的原文（异常类型+行号）/
  还原零差异证据 / 复跑两条命令的 `Tests run` 与 `BUILD` 行 / 「待主 agent 决定」（没有写"无"）
- 若确实补不出能变红的测试（例如假件无论如何都测不到字节），**不要放宽断言凑绿**：
  停下把证据写进「待主 agent 决定」。

## 约束
- 你是新测试文件的唯一写入者；`CacheConfigTest.java` 与其余 7 条断言一字不改
- 最多 1 次修复重试；不 commit、不 push
