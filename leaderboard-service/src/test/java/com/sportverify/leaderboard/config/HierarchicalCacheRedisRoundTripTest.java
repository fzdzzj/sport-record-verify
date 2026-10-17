package com.sportverify.leaderboard.config;

import com.sportverify.api.record.dto.LeaderboardDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 榜单二级缓存 L2 真序列化往返。
 *
 * <p>{@link CacheConfigTest} 的 Redis 桩把 {@code set(...)} 整个 mock 掉（写进去立刻消失、读永远 miss），
 * 所以它只证明过"写穿被调用"，没证明"字节活得下来"。本类用内存 Map 假件承接<b>真实字节</b>：
 * 写入即把 {@code byte[]} 原样存进 Map，读取即吐回，因此 JSON 序列化真的跑了一遍。
 * 范式照 {@code RuleCacheServiceSerializationRoundTripTest}。</p>
 *
 * <p>三件事被钉住：值走 JSON 序列化且完整（TASK-262 R5：不是 JDK 二进制魔数、不是 toString、
 * 不是空数组；键前缀带 {@code :v2} 版本后缀隔离旧 JDK 条目）、TTL 是 5min±60s 抖动值域
 * （TASK-262 R2）、换一个实例（等价于另一台机器或 L1 刚重启）能原样读回并按
 * {@code @Cacheable} 语义跳过回源。</p>
 *
 * <p>{@code stringCommands()} 与 {@code keyCommands()} 故意返回<b>同一个</b>假件：
 * Spring Data Redis 的缓存写路径在不同小版本里会在两者之间漂移，分开桩会静默漏测。</p>
 */
class HierarchicalCacheRedisRoundTripTest {

    private static final String CACHE_NAME = "leaderboard:overall";
    private static final String REDIS_KEY = "leaderboard:overall:v2::10";

    private final Map<String, byte[]> redisStore = new HashMap<>();
    private final AtomicReference<Expiration> lastExpiration = new AtomicReference<>();
    /** 用生产序列化器回读（TASK-262 R5 定制 WRAPPER_ARRAY typing），与写侧逐字对称。 */
    private final RedisSerializer<Object> valueSerializer = CacheConfig.l2ValueSerializer();

    private final List<LeaderboardDTO> written = List.of(
            LeaderboardDTO.of(1, 1001L, "阿跑", new BigDecimal("12.50")),
            LeaderboardDTO.of(2, 1002L, "阿骑", new BigDecimal("88.05")));

    private RedisConnectionFactory connectionFactory;

    /** 两个命令接口合并成一个假件，set/get/exists/delete 共用同一份 Map。 */
    private interface StringAndKeyCommands extends RedisStringCommands, RedisKeyCommands {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisStore.clear();
        lastExpiration.set(null);

        StringAndKeyCommands commands = mock(StringAndKeyCommands.class);
        when(commands.set(any(byte[].class), any(byte[].class), any(Expiration.class),
                any(RedisStringCommands.SetOption.class))).thenAnswer(inv -> {
            byte[] key = inv.getArgument(0);
            byte[] value = inv.getArgument(1);
            redisStore.put(new String(key, StandardCharsets.UTF_8), value);
            lastExpiration.set(inv.getArgument(2));
            return true;
        });
        when(commands.get(any(byte[].class))).thenAnswer(inv -> {
            byte[] key = inv.getArgument(0);
            return redisStore.get(new String(key, StandardCharsets.UTF_8));
        });
        when(commands.exists(any(byte[].class))).thenAnswer(inv ->
                redisStore.containsKey(new String(inv.getArgument(0), StandardCharsets.UTF_8)));
        when(commands.del(any(byte[].class))).thenAnswer(inv ->
                redisStore.remove(new String(inv.getArgument(0), StandardCharsets.UTF_8)) == null ? 0L : 1L);

        RedisConnection connection = mock(RedisConnection.class);
        when(connection.stringCommands()).thenReturn(commands);
        when(connection.keyCommands()).thenReturn(commands);

        connectionFactory = mock(RedisConnectionFactory.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
    }

    /** 走生产的 @Bean 方法，而不是在测试里重抄一份 RedisCacheConfiguration。 */
    private CacheManager productionCacheManager() {
        return new CacheConfig().hierarchicalCacheManager(connectionFactory);
    }

    @Test
    void writeGoesThroughRealJsonSerializationWithJitteredTtl() {
        productionCacheManager().getCache(CACHE_NAME).put(10, written);

        byte[] stored = redisStore.get(REDIS_KEY);
        assertThat(stored).as("L2 必须以 @Cacheable 的 cache 名作前缀落键，且带 :v2 版本后缀").isNotNull();
        assertThat(RedisSerializer.byteArray().deserialize(stored))
                .as("载荷不能是 toString 或空字节")
                .isNotEmpty();
        assertThat(stored[0])
                .as("载荷必须是 JSON（默认 typing 的数组包装以 [ 开头），不是 JDK 序列化魔数 0xACED")
                .isEqualTo((byte) '[');

        Object readBack = valueSerializer.deserialize(stored);
        assertThat(readBack).isEqualTo(written);

        assertThat(lastExpiration.get()).as("TTL 必须真的传给 Redis，而不是配了抖动却写死不过期").isNotNull();
        long ttlSeconds = lastExpiration.get().getExpirationTimeInSeconds();
        assertThat(ttlSeconds)
                .as("L2 TTL 必须落在 5min±60s 抖动值域内（TASK-262 R2）")
                .isBetween(240L, 360L);
    }

    /** TASK-262 R2 TTL 值域断言：多次取值均落在 [5min-60s, 5min+60s] 且不恒定（真抖动）。 */
    @Test
    void l2TtlJitter_staysWithinAmplitudeAndVaries() {
        Set<Long> observed = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            long ttlMillis = CacheConfig.l2EntryTtl().getTimeToLive("k", "v").toMillis();
            assertThat(ttlMillis)
                    .as("每次抖动取值都必须落在 [240s, 360s] 毫秒值域内")
                    .isBetween(240_000L, 360_000L);
            observed.add(ttlMillis);
        }
        assertThat(observed.size())
                .as("1000 次取值必须出现多个不同值（恒定值＝抖动失效，集中过期雪崩回归）")
                .isGreaterThan(2);
    }

    @Test
    void secondInstanceReadsBackWhatFirstWroteIncludingBigDecimalScale() {
        productionCacheManager().getCache(CACHE_NAME).put(10, written);

        Cache anotherInstance = productionCacheManager().getCache(CACHE_NAME);
        Cache.ValueWrapper hit = anotherInstance.get(10);
        assertThat(hit).as("L1 全新也应从 L2 字节里读回").isNotNull();

        @SuppressWarnings("unchecked")
        List<LeaderboardDTO> restored = (List<LeaderboardDTO>) hit.get();
        assertThat(restored).isEqualTo(written);
        // compareTo 相等但 scale 不同＝精度在往返里丢了（12.50 变 12.5），榜单里程要显式两位小数
        assertThat(restored.get(0).getDistance().scale())
                .as("BigDecimal scale 必须在序列化往返中存活")
                .isEqualTo(written.get(0).getDistance().scale());
        assertThat(restored.get(1).getDistance().scale())
                .isEqualTo(written.get(1).getDistance().scale());
    }

    @Test
    void secondInstanceValueLoaderIsNotInvokedWhenL2HasTheValue() {
        productionCacheManager().getCache(CACHE_NAME).put(10, written);

        AtomicInteger loaderCalls = new AtomicInteger();
        Cache anotherInstance = productionCacheManager().getCache(CACHE_NAME);
        List<LeaderboardDTO> value = anotherInstance.get(10, () -> {
            loaderCalls.incrementAndGet();
            return List.of();
        });

        assertThat(value).isEqualTo(written);
        assertThat(loaderCalls).as("L2 命中却回源 DB＝静默失效，压测数字会失实").hasValue(0);
    }
}
