package com.sportverify.leaderboard.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 二级缓存配置（Redis + Caffeine）。
 *
 * <p>解决 F13 提到的"判定结果缓存多实例不一致窗口"问题：</p>
 * <ul>
 *   <li><b>L1 本地缓存</b>：Caffeine 容量 1000 条，TTL=5min，单实例快速读取；</li>
 *   <li><b>L2 分布式缓存</b>：Redis，TTL=5min 基值 ±60s 抖动（TASK-262 R2，多实例错峰过期
 *       防集中失效雪崩，对齐 verify RuleCacheService 范式），值 JSON 序列化（TASK-262 R5，
 *       同仓序列化口径与 RuleCacheService 统一）、键前缀 {@code :v2} 版本化隔离旧 JDK 条目；</li>
 *   <li><b>读路径</b>：{@link HierarchicalCacheManager} 先查 L1，未命中查 L2 并回填 L1；</li>
 *   <li><b>写/失效</b>：写穿与失效都同时作用于两层，避免 L1 读到 L2 已失效的旧值。</li>
 * </ul>
 *
 * <p>对外只暴露 {@code hierarchicalCacheManager} 这一个 {@code CacheManager} bean：
 * 两个裸 manager 收为内部构造细节，否则按类型注入会出现歧义，
 * 且 {@code @Cacheable(cacheManager = "hierarchicalCacheManager")} 将无 bean 可解析。</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 与 {@code LeaderboardService#top} 的 overall {@code @Cacheable} value 一致 */
    private static final String OVERALL_CACHE = "leaderboard:overall";

    /** TTL 基值：L1 Caffeine 恒定 5min；L2 Redis 以此为基值做 ±60s 抖动 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /** L2 TTL 抖动幅度（秒）：±60s 错峰过期（TASK-262 R2，值域须覆盖 5min 基值） */
    private static final long L2_TTL_JITTER_SECONDS = 60;

    /** L2 Redis 键前缀版本后缀（TASK-262 R5）：切换 JSON 序列化后隔离旧 JDK 二进制条目，
     * 旧键 {@code leaderboard:overall::} 5min TTL 内自然过期孤儿化；
     * 末两位 {@code ::} 为 Spring cache 默认分隔符形态，保证物理键 {@code leaderboard:overall:v2::<key>} */
    private static final String L2_KEY_VERSION_SUFFIX = ":v2::";

    /**
     * L2 TTL 抖动函数（TASK-262 R2/G12）：5min 基值 ±60s 均匀抖动，防多实例同刻集中过期；
     * 包级可见供测试钉值域（R2 TTL 值域断言）。spring-data-redis 3.2.x API 为
     * {@link RedisCacheWriter.TtlFunction}（getTimeToLive 返回 Duration）。
     */
    static RedisCacheWriter.TtlFunction l2EntryTtl() {
        return (key, value) -> CACHE_TTL.plusSeconds(
                ThreadLocalRandom.current().nextLong(-L2_TTL_JITTER_SECONDS,
                        L2_TTL_JITTER_SECONDS + 1));
    }

    /**
     * L2 值序列化器（TASK-262 R5/G16）：JSON，对齐 verify RuleCacheService JSON 口径。
     * 定制 typing 为 {@code As.WRAPPER_ARRAY} 根级对称包裹——GenericJackson2JsonRedisSerializer
     * 默认 typing（As.PROPERTY）对根级 List 写侧不落类型包裹、读侧却按数组包裹回读，
     * 根级集合往返不对称（本批 offline 实测坐实）；WRAPPER_ARRAY 写读逐字对称，
     * 元素类型/BigDecimal/不可变 List 均可往返。仅 overall 榜单缓存使用（值恒非空
     * List，{@code @Cacheable} unless 守卫空值，NullValue 条目不产生）。
     */
    static RedisSerializer<Object> l2ValueSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Object.class)
                .build();
        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.WRAPPER_ARRAY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    /**
     * 二级缓存管理器（唯一的 CacheManager bean）：读 L1→L2 回填，写/失效两层同走。
     */
    @Bean
    @Primary
    public CacheManager hierarchicalCacheManager(RedisConnectionFactory connectionFactory) {
        return new HierarchicalCacheManager(newCaffeineCacheManager(), newRedisCacheManager(connectionFactory));
    }

    /**
     * StringRedisTemplate（用于 ZSet 榜单操作）。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    /**
     * L1 本地缓存：Caffeine，容量 1000 条，5 分钟 TTL，开启统计。
     */
    private static CacheManager newCaffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(OVERALL_CACHE);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(CACHE_TTL)
                .recordStats());
        return cacheManager;
    }

    /**
     * L2 分布式缓存：Redis，TTL=5min±60s 抖动（TASK-262 R2），值走 JSON 序列化
     * （TASK-262 R5，GenericJackson2JsonRedisSerializer，对齐 verify RuleCacheService 口径），
     * 键前缀 {@code :v2} 版本化（Spring cache 名保持 {@code leaderboard:overall} 不变，
     * 仅 L2 物理键前缀升版隔离旧 JDK 条目）。
     */
    private static RedisCacheManager newRedisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(l2EntryTtl())
                .computePrefixWith(cacheName -> cacheName + L2_KEY_VERSION_SUFFIX)
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(l2ValueSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .build();
    }

    /**
     * L1→L2 组合：本身不存数据，只把同名 cache 包成 {@link HierarchicalCache} 并缓存包装结果。
     */
    static final class HierarchicalCacheManager implements CacheManager {

        private final CacheManager l1Manager;
        private final CacheManager l2Manager;
        private final Map<String, Cache> caches = new ConcurrentHashMap<>();

        HierarchicalCacheManager(CacheManager l1Manager, CacheManager l2Manager) {
            this.l1Manager = l1Manager;
            this.l2Manager = l2Manager;
        }

        @Override
        public Cache getCache(String name) {
            return caches.computeIfAbsent(name, n -> new HierarchicalCache(l1Manager.getCache(n), l2Manager.getCache(n)));
        }

        @Override
        public Collection<String> getCacheNames() {
            return l1Manager.getCacheNames();
        }
    }

    /**
     * 单层读写的两级视图。缓存值为 {@code List<LeaderboardDTO>}，Redis 侧整值覆盖，无需增量合并。
     */
    static final class HierarchicalCache implements Cache {

        private final Cache l1;
        private final Cache l2;

        HierarchicalCache(Cache l1, Cache l2) {
            this.l1 = l1;
            this.l2 = l2;
        }

        @Override
        public String getName() {
            return l1.getName();
        }

        @Override
        public Object getNativeCache() {
            return l1.getNativeCache();
        }

        @Override
        public ValueWrapper get(Object key) {
            ValueWrapper hit = l1.get(key);
            if (hit != null) {
                return hit;
            }
            ValueWrapper fromL2 = l2.get(key);
            if (fromL2 != null) {
                l1.put(key, fromL2.get());
            }
            return fromL2;
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            T hit = l1.get(key, type);
            if (hit != null) {
                return hit;
            }
            T fromL2 = l2.get(key, type);
            if (fromL2 != null) {
                l1.put(key, fromL2);
            }
            return fromL2;
        }

        /**
         * {@code @Cacheable} 的非同步入口：先走两层读，全未命中才回源并写穿。
         */
        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Callable<T> valueLoader) {
            ValueWrapper hit = get(key);
            if (hit != null) {
                return (T) hit.get();
            }
            T value;
            try {
                value = valueLoader.call();
            } catch (Exception ex) {
                throw new ValueRetrievalException(key, valueLoader, ex);
            }
            put(key, value);
            return value;
        }

        /**
         * 写穿：先落 L1 再落 L2，任一层抛错都原样上抛（不静默降级，避免 Redis 故障被掩盖）。
         */
        @Override
        public void put(Object key, Object value) {
            l1.put(key, value);
            l2.put(key, value);
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, Object value) {
            ValueWrapper existing = get(key);
            if (existing != null) {
                return existing;
            }
            put(key, value);
            return new SimpleValueWrapper(value);
        }

        @Override
        public void evict(Object key) {
            l1.evict(key);
            l2.evict(key);
        }

        @Override
        public void clear() {
            l1.clear();
            l2.clear();
        }
    }
}
