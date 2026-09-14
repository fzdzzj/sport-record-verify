package com.sportverify.verify.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sportverify.verify.config.TwoLevelCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则读取二级缓存验收用例（规范「二级缓存读路径」+ 三防护 + 精准失效）。
 *
 * <p>用真实 Caffeine（验证本地回填/失效时机）+ 打桩 StringRedisTemplate（可注入 Redis 命中值）
 * + 打桩 Redisson（RLock 可控获取/放弃）。值类型统一用 String，经 Jackson 往返，易验证且不受
 * 实体字段影响；写 TTL 经 doAnswer 采集到 ttlWrites，便于断言抖动区间。</p>
 */
class RuleCacheServiceTest {

    private static final String KEY = "verify:rules:active";
    private static final String REDIS_KEY = "verify:rule:cache:" + KEY;

    private Cache<String, Object> caffeine;
    private RLock lock;
    private TwoLevelCacheProperties props;
    private RuleCacheService service;
    private StringRedisTemplate redis;
    private final List<Duration> ttlWrites = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        caffeine = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build();
        ttlWrites.clear();
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        doAnswer(inv -> {
            ttlWrites.add(inv.getArgument(2));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(redis.opsForValue()).thenReturn(valueOps);
        lock = mock(RLock.class);
        // tryLock 声明 throws InterruptedException，用 doReturn 规避检查型异常
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        props = new TwoLevelCacheProperties();
        service = new RuleCacheService(caffeine, redis, redisson, new ObjectMapper(), props);
    }

    private void stubRedisValue(String json) {
        when(redis.opsForValue().get(REDIS_KEY)).thenReturn(json);
    }

    /** 每次失效再重建，把 Redis 回填 TTL 采集进 ttlWrites（本实例不存值，模拟跨实例 miss） */
    private void rebuildAndRecordTtl(String value) {
        service.invalidate(KEY);
        service.get(KEY, String.class, () -> value);
    }

    // ===== 两级命中路径（规范 Scenario） =====

    /** 本地命中：第二次读取直接返回本地，不再访问 Redis 与 DB */
    @Test
    void localHit_skipsRedisAndDb() {
        AtomicInteger reads = new AtomicInteger();
        Optional<String> first = service.get(KEY, String.class, () -> {
            reads.incrementAndGet();
            return "GRAY";
        });
        assertEquals(Optional.of("GRAY"), first);

        Optional<String> second = service.get(KEY, String.class, () -> {
            reads.incrementAndGet();
            throw new AssertionError("本地命中的路径不应再回源 DB");
        });
        assertEquals(Optional.of("GRAY"), second);
        assertEquals(1, reads.get(), "loader 仅一次：第二次命中本地");
    }

    /** Redis 命中：返回 Redis 值并回填本地（后续本地命中，不再访问 Redis/DB） */
    @Test
    void redisHit_backfillsLocal() {
        stubRedisValue("\"ACTIVE\"");
        assertEquals(Optional.of("ACTIVE"), service.get(KEY, String.class, () -> {
            throw new AssertionError("Redis 命中不应回源 DB");
        }));

        // Redis 后续 miss：本地已回填，第二次仍命中本地（验证回填发生）
        stubRedisValue(null);
        assertEquals(Optional.of("ACTIVE"), service.get(KEY, String.class, () -> {
            throw new AssertionError("本地已回填不应回源 DB");
        }));
    }

    /** 两级未命中回源：查库并回填两级（Redis 写入 + 本地），后续命中本地 */
    @Test
    void bothMiss_backfillsTwoLevels() {
        AtomicInteger reads = new AtomicInteger();
        assertEquals(Optional.of("NEW"), service.get(KEY, String.class, () -> {
            reads.incrementAndGet();
            return "NEW";
        }));
        assertEquals(1, ttlWrites.size(), "回源后应写一次 Redis");
        // 本地回填命中：第二次不查库
        assertEquals(Optional.of("NEW"), service.get(KEY, String.class, () -> {
            reads.incrementAndGet();
            throw new AssertionError("本地回填命中不应再回源 DB");
        }));
        assertEquals(1, reads.get());
    }

    // ===== 空值缓存（穿透防护） =====

    /** 空值哨兵：DB 查不到缓存空值，后续相同查询不再打库 */
    @Test
    void emptyValue_secondQueryNotHitDb() {
        assertEquals(Optional.empty(), service.get(KEY, String.class, () -> null));
        assertEquals(Optional.empty(), service.get(KEY, String.class, () -> {
            throw new AssertionError("空值哨兵命中后不应再回源 DB");
        }));
    }

    /** 空值哨兵写 Redis 用短 TTL（emptyTtl），与正常值 TTL 区分开 */
    @Test
    void emptyValue_writesShortTtl() {
        service.get(KEY, String.class, () -> null);
        assertEquals(1, ttlWrites.size());
        assertEquals(props.getEmptyTtl(), ttlWrites.get(0));
    }

    // ===== 互斥重建（击穿防护） =====

    /** 持锁实例仅查库一次并回填，锁被释放 */
    @Test
    void holder_queriesDbExactlyOnceAndReleasesLock() {
        AtomicInteger reads = new AtomicInteger();
        assertEquals(Optional.of("V"), service.get(KEY, String.class, () -> {
            reads.incrementAndGet();
            return "V";
        }));
        assertEquals(1, reads.get(), "仅持锁实例触发一次 DB");
        verify(lock).unlock();
    }

    /** 未拿到锁：降级无锁读一次 DB（不阻塞主链路），但不误释放他实例持有的锁 */
    @Test
    void notHoldingLock_doesNotUnlockAndStillServes() throws InterruptedException {
        doReturn(false).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        Optional<String> v = service.get(KEY, String.class, () -> "FROM_DB");
        assertEquals(Optional.of("FROM_DB"), v);
        verify(lock, never()).unlock();
    }

    // ===== TTL 抖动（雪崩防护） =====

    /** 非空值的 Redis TTL 落在 [base-jitter, base+jitter] 区间（60s ± 10s） */
    @Test
    void nonEmpty_ttlRandomizedWithinJitterRange() {
        stubRedisValue(null);
        long min = props.getTtl().toSeconds() - props.getJitter().toSeconds();
        long max = props.getTtl().toSeconds() + props.getJitter().toSeconds();
        for (int i = 0; i < 200; i++) {
            rebuildAndRecordTtl("X");
        }
        for (Duration ttl : ttlWrites) {
            assertTrue(ttl.getSeconds() >= min && ttl.getSeconds() <= max,
                    "TTL 应在 [" + min + "," + max + "] 区间，实际=" + ttl);
        }
    }

    /** 抖动产生过至少两种 TTL（证明随机而非恒定值） */
    @Test
    void ttl_jitterProducesVariation() {
        stubRedisValue(null);
        Set<Long> ttls = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            rebuildAndRecordTtl("X");
            ttls.add(ttlWrites.get(ttlWrites.size() - 1).getSeconds());
        }
        assertTrue(ttls.size() > 1, "随机抖动应产生多种 TTL，实际=" + ttls);
    }

    // ===== 变更精准失效 =====

    /** invalidate：本地 + Redis 都清掉，下次读取回源最新值 */
    @Test
    void invalidate_clearsLocalAndRedis_thenBackfillsNew() {
        stubRedisValue(null);
        service.get(KEY, String.class, () -> "OLD");
        service.invalidate(KEY);
        verify(redis).delete(REDIS_KEY); // Redis 精准 DEL

        AtomicInteger reads = new AtomicInteger();
        assertEquals(Optional.of("NEW"), service.get(KEY, String.class, () -> {
            reads.incrementAndGet();
            return "NEW";
        }));
        assertEquals(1, reads.get(), "失效后须回源到最新值");
    }

    // ===== 总开关降级 =====

    /** enabled=false：整体跳过 Redis 层，仅 Caffeine + DB，不产生 Redis 交互 */
    @Test
    void disabled_skipsRedisEntirely() {
        props.setEnabled(false);
        AtomicInteger reads = new AtomicInteger();
        assertEquals(Optional.of("DB"), service.get(KEY, String.class, () -> {
            reads.incrementAndGet();
            return "DB";
        }));
        verify(redis, never()).opsForValue(); // 完全未触碰 Redis
        assertEquals(Optional.of("DB"), service.get(KEY, String.class, () -> {
            reads.incrementAndGet();
            return "DB";
        }));
        assertEquals(1, reads.get(), "本地回填命中，不再打库");
    }
}