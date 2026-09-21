package com.sportverify.leaderboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.api.record.dto.LeaderboardDTO;
import com.sportverify.leaderboard.service.LeaderboardService;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.types.Expiration;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-002 缓存接线断言（二级缓存：L1 Caffeine + L2 Redis）。
 *
 * <p>守三类此前只能靠人肉读码发现的静默失效：</p>
 * <ol>
 *   <li>{@code CacheConfig} 曾同时暴露 {@code caffeineCacheManager} 与 {@code redisCacheManager}
 *       两个 {@code CacheManager} bean 且无 {@code @Primary} —— 按类型注入当场歧义，
 *       只有起上下文才炸得出来，故用 {@link ApplicationContextRunner} 断言唯一解析；</li>
 *   <li>{@link LeaderboardService#topOverall} 的 {@code @Cacheable(cacheManager="hierarchicalCacheManager")}
 *       指向的 bean 当时根本不存在，首次调用即 {@code NoSuchBeanDefinitionException}。
 *       单测直调方法绕过了缓存代理，所以永远不红；这里按注解里的名字真去容器里取一次；</li>
 *   <li>{@code CacheConfig} 曾用裸 {@code new ObjectMapper()} 顶掉 Boot 的自动装配
 *       （丢 JavaTimeModule，带 {@code Instant} 的 DTO 直接序列化失败）。
 *       上下文里存在自定义 {@code ObjectMapper} bean 即为回归。</li>
 * </ol>
 *
 * <p>Redis 侧只给一个"能连上但什么都不存"的桩：上下文级用例证明接线、L1 往返，以及写操作
 * 确实打到了 Redis 层；两层串联语义（写穿、回填、失效两层同走）由下面的
 * {@link CacheConfig.HierarchicalCacheManager} 单测用两个内存 manager 证明。</p>
 */
class CacheConfigTest {

    private static final String OVERALL_CACHE = "leaderboard:overall";

    /** 断言 L2 真的被写入用：连接可用但恒查不到值，写操作只计数不落地。 */
    private final RedisStringCommands redisStringCommands = mock(RedisStringCommands.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CacheConfig.class)
            .withBean(RedisConnectionFactory.class, this::stubRedisConnectionFactory);

    @Test
    void onlyHierarchicalCacheManagerIsExposed() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // 再多一个裸 CacheManager 就在这里红：按名断言只允许 hierarchicalCacheManager 一个
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBeanNamesForType(CacheManager.class)).containsExactly("hierarchicalCacheManager");
            assertThat(context.getBean(CacheManager.class))
                    .isSameAs(context.getBean("hierarchicalCacheManager", CacheManager.class));
        });
    }

    @Test
    void cacheableQualifierResolvesToARealBean() {
        Cacheable cacheable = cacheableOnTopOverall();
        runner.run(context -> assertThat(context.containsBean(cacheable.cacheManager())).isTrue());
    }

    @Test
    void overallCachePutGetRoundTrip() {
        runner.run(context -> {
            Cache cache = context.getBean(CacheManager.class).getCache(OVERALL_CACHE);
            assertThat(cache).isNotNull();
            assertThat(cache.getName()).isEqualTo(OVERALL_CACHE);

            List<LeaderboardDTO> ranked = List.of(LeaderboardDTO.of(1, 1001L, "阿跑", new BigDecimal("12.5")));
            assertThat(cache.get(10)).isNull();

            cache.put(10, ranked);
            assertThat(cache.get(10).get()).isEqualTo(ranked);
            assertThat(cache.get(10, List.class)).isEqualTo(ranked);
            // 写穿确实打到了 Redis 侧，而不是只落在本地 Caffeine 里自说自话
            verify(redisStringCommands).set(any(byte[].class), any(byte[].class), any(Expiration.class),
                    any(RedisStringCommands.SetOption.class));

            cache.evict(10);
            assertThat(cache.get(10)).isNull();
        });
    }

    @Test
    void noCustomObjectMapperBean() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ObjectMapper.class));
    }

    @Test
    void writeThroughAndEvictTouchBothLayers() {
        StubbedLayers layers = new StubbedLayers();

        layers.manager.getCache(OVERALL_CACHE).put("k", "v");

        assertThat(layers.l1.getCache(OVERALL_CACHE).get("k").get()).isEqualTo("v");
        assertThat(layers.l2.getCache(OVERALL_CACHE).get("k").get()).isEqualTo("v");

        layers.manager.getCache(OVERALL_CACHE).evict("k");

        assertThat(layers.l1.getCache(OVERALL_CACHE).get("k")).isNull();
        assertThat(layers.l2.getCache(OVERALL_CACHE).get("k")).isNull();
    }

    @Test
    void l1MissReadsL2AndBackfillsL1() {
        StubbedLayers layers = new StubbedLayers();
        Cache hierarchical = layers.manager.getCache(OVERALL_CACHE);
        hierarchical.put("k", "v");
        // 只清 L1，模拟另一个实例已把值写进 Redis、本实例 L1 刚被逐出
        layers.l1.getCache(OVERALL_CACHE).evict("k");
        assertThat(layers.l1.getCache(OVERALL_CACHE).get("k")).isNull();

        assertThat(hierarchical.get("k").get()).isEqualTo("v");
        assertThat(layers.l1.getCache(OVERALL_CACHE).get("k").get()).isEqualTo("v");
    }

    @Test
    void valueLoaderHitsL2BeforeGoingToSource() {
        StubbedLayers layers = new StubbedLayers();
        Cache hierarchical = layers.manager.getCache(OVERALL_CACHE);
        hierarchical.put("k", "v");
        layers.l1.getCache(OVERALL_CACHE).clear();

        int[] sourceCalls = {0};
        assertThat(hierarchical.get("k", () -> {
            sourceCalls[0]++;
            return "fromDb";
        })).isEqualTo("v");
        assertThat(sourceCalls[0]).isZero();

        layers.l2.getCache(OVERALL_CACHE).clear();
        assertThat(hierarchical.get("missing", () -> {
            sourceCalls[0]++;
            return "fromDb";
        })).isEqualTo("fromDb");
        assertThat(sourceCalls[0]).isEqualTo(1);
        // 回源结果写穿两层，下次读不再触源
        assertThat(layers.l1.getCache(OVERALL_CACHE).get("missing").get()).isEqualTo("fromDb");
        assertThat(layers.l2.getCache(OVERALL_CACHE).get("missing").get()).isEqualTo("fromDb");
    }

    /** 两级都用内存 manager，专门验串联语义，不涉及 Redis 协议。 */
    private static final class StubbedLayers {

        private final ConcurrentMapCacheManager l1 = new ConcurrentMapCacheManager(OVERALL_CACHE);
        private final ConcurrentMapCacheManager l2 = new ConcurrentMapCacheManager(OVERALL_CACHE);
        private final CacheConfig.HierarchicalCacheManager manager =
                new CacheConfig.HierarchicalCacheManager(l1, l2);
    }

    private static Cacheable cacheableOnTopOverall() {
        try {
            return LeaderboardService.class.getMethod("topOverall", int.class).getAnnotation(Cacheable.class);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("LeaderboardService#topOverall(int) 签名变了，缓存注解断言需同步", ex);
        }
    }

    /** 连接可用但恒查不到值的 Redis 桩：L2 写变成 no-op，读永远 miss。 */
    private RedisConnectionFactory stubRedisConnectionFactory() {
        when(redisStringCommands.set(any(byte[].class), any(byte[].class), any(Expiration.class),
                any(RedisStringCommands.SetOption.class))).thenReturn(true);

        RedisConnection connection = mock(RedisConnection.class);
        when(connection.stringCommands()).thenReturn(redisStringCommands);
        when(connection.keyCommands()).thenReturn(mock(RedisKeyCommands.class));

        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        return factory;
    }
}
