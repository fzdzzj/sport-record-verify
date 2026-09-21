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
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * 写入即把 {@code byte[]} 原样存进 Map，读取即吐回，因此 Jackson/JDK 序列化真的跑了一遍。
 * 范式照 {@code RuleCacheServiceSerializationRoundTripTest}。</p>
 *
 * <p>三件事被钉住：值走 JDK 序列化且完整（不是 toString、不是空数组）、TTL 是 5 分钟、
 * 换一个实例（等价于另一台机器或 L1 刚重启）能原样读回并按 {@code @Cacheable} 语义跳过回源。</p>
 *
 * <p>{@code stringCommands()} 与 {@code keyCommands()} 故意返回<b>同一个</b>假件：
 * Spring Data Redis 的缓存写路径在不同小版本里会在两者之间漂移，分开桩会静默漏测。</p>
 */
class HierarchicalCacheRedisRoundTripTest {

    private static final String CACHE_NAME = "leaderboard:overall";
    private static final String REDIS_KEY = "leaderboard:overall::10";

    private final Map<String, byte[]> redisStore = new HashMap<>();
    private final AtomicReference<Expiration> lastExpiration = new AtomicReference<>();
    private final JdkSerializationRedisSerializer valueSerializer = new JdkSerializationRedisSerializer();

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
    void writeGoesThroughRealJdkSerializationWithFiveMinuteTtl() {
        productionCacheManager().getCache(CACHE_NAME).put(10, written);

        byte[] stored = redisStore.get(REDIS_KEY);
        assertThat(stored).as("L2 必须以 @Cacheable 的 cache 名作前缀落键").isNotNull();
        assertThat(RedisSerializer.byteArray().deserialize(stored))
                .as("载荷不能是 toString 或空字节")
                .isNotEmpty();

        Object readBack = valueSerializer.deserialize(stored);
        assertThat(readBack).isEqualTo(written);

        assertThat(lastExpiration.get()).as("TTL 必须真的传给 Redis，而不是配了 5 分钟却写死不过期").isNotNull();
        assertThat(lastExpiration.get().getExpirationTimeInSeconds()).isEqualTo(300L);
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
