package com.sportverify.verify.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sportverify.api.record.SportType;
import com.sportverify.verify.config.TwoLevelCacheProperties;
import com.sportverify.verify.config.VerifyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L2（Redis 层）序列化往返断言 —— 本任务硬性交付项。
 *
 * <p><b>为什么要有这个测试</b>（决策 2 / R2 的直接落地）：缓存分支 fork 点在
 * sport-type 之前，它的序列化路径从未见过「按类型分维度」的嵌套阈值结构；而
 * {@link RuleCacheService} 用 Jackson 把整个 {@link VerifyProperties} 写进 Redis，
 * {@code readRedis} 的失败口径又是 {@code catch (Exception e) → log.warn → return null → 回源 DB}。
 * 一旦序列化/反序列化对不上嵌套结构（例如 bySportType 被拍扁、退化为单套默认阈值），
 * 服务不报错、测试照样绿，只是 L2「静默永不命中」或「命中但退回默认值」，压测数字失实。
 * 本测试把「序列化 → 写 Redis → 读回 → 按类型取值」整条链路钉死，防止嵌套阈值在往返中丢失。</p>
 *
 * <p>Redis 用「内存 Map 假件」模拟真实 set/get 行为：写入的 JSON 原样存进 Map，
 * 读回时再吐出来——因此往返经过了真实的 Jackson 序列化/反序列化，而非打桩跳过。</p>
 */
class RuleCacheServiceSerializationRoundTripTest {

    private static final String KEY = "verify:rules:v1";
    private static final String REDIS_KEY = "verify:rule:cache:" + KEY;

    private Cache<String, Object> caffeine;
    private RLock lock;
    private TwoLevelCacheProperties props;
    private RuleCacheService service;
    private StringRedisTemplate redis;
    private final Map<String, String> redisStore = new LinkedHashMap<>();

    private Logger ruleCacheLogger;
    private ListAppender<ILoggingEvent> warnCapture;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        caffeine = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build();
        redisStore.clear();

        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        // 写入即存入内存 Map（模拟 Redis set，JSON 原样保存）
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        // 读回即从 Map 取（模拟 Redis get）
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));
        // 精准失效即删除（模拟 Redis DEL）
        doAnswer(inv -> {
            redisStore.remove(inv.getArgument(0));
            return null;
        }).when(redis).delete(anyString());
        when(redis.opsForValue()).thenReturn(valueOps);

        lock = mock(RLock.class);
        doReturn(true).when(lock).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);

        props = new TwoLevelCacheProperties();
        service = new RuleCacheService(caffeine, redis, redisson, new ObjectMapper(), props);

        // 挂 logback ListAppender 捕获 RuleCacheService 的 warn 日志（验证静默降级可观测）
        ruleCacheLogger = (Logger) LoggerFactory.getLogger(RuleCacheService.class);
        warnCapture = new ListAppender<>();
        warnCapture.start();
        ruleCacheLogger.setLevel(Level.WARN);
        ruleCacheLogger.addAppender(warnCapture);
    }

    @AfterEach
    void tearDown() {
        ruleCacheLogger.detachAppender(warnCapture);
    }

    /** 构造含多运动类型嵌套阈值的规则集：RUNNING 与 CYCLING 的 R1 速度阈值必须不同 */
    private VerifyProperties nestedBySportType(double runningR1Speed, double cyclingR1Speed) {
        VerifyProperties props = new VerifyProperties();
        Map<String, VerifyProperties.Rules.RuleThreshold> byType = new LinkedHashMap<>();
        byType.put(SportType.RUNNING.name(), threshold(runningR1Speed));
        byType.put(SportType.CYCLING.name(), threshold(cyclingR1Speed));
        props.getRules().setBySportType(byType);
        return props;
    }

    private VerifyProperties.Rules.RuleThreshold threshold(double r1Speed) {
        VerifyProperties.Rules.RuleThreshold t = new VerifyProperties.Rules.RuleThreshold();
        t.getR1().setSpeed(r1Speed);
        return t;
    }

    /**
     * 正向断言：嵌套阈值经「写入 Redis（Jackson 序列化）→ 清本地 → 读回（Jackson 反序列化）」
     * 往返后，按 CYCLING 与按 RUNNING 解析出的 R1 阈值依然不同，且都等于写入前的值。
     *
     * <p>这是 R2 的核心断言：若序列化把 bySportType 拍扁、往返退化成单套默认值，
     * 此处 CYCLING 会跌回 5.5，与 RUNNING 相等，断言直接失败——证明嵌套结构活着，
     * 而不是「看似命中实则用错阈值」。同时断言 Redis 里的原始 JSON 含 CYCLING 维度，
     * 防止「读回走 DB 兜底、Redis 根本没存对」的假阳性。</p>
     */
    @Test
    void roundTrip_nestedThresholdsSurvivePerSportType() {
        double running = 5.5;
        double cycling = 15.0;
        VerifyProperties written = nestedBySportType(running, cycling);

        // 第一次 get：两级 miss → 回源 DB（loader 返回 written）→ 回填 Redis（真实序列化写入）
        assertEquals(Optional.of(written),
                service.get(KEY, VerifyProperties.class, () -> written));

        // Redis 里确实存了按类型分维度的 JSON（防「根本没写 Redis」的假阳性）
        String storedJson = redisStore.get(REDIS_KEY);
        assertTrue(storedJson != null && storedJson.contains("CYCLING"),
                "Redis 载荷必须含 CYCLING 维度，实际=" + storedJson);

        // 只清本地 Caffeine，保留 Redis：下一次 get 必须走 Redis 命中（loader 不应再被调用）
        caffeine.invalidate(KEY);
        VerifyProperties readBack = service.get(KEY, VerifyProperties.class, () -> {
            throw new AssertionError("Redis 命中路径不应再回源 DB");
        }).orElseThrow();

        double cyclingR1 = readBack.getRules().threshold(SportType.CYCLING).getR1().getSpeed();
        double runningR1 = readBack.getRules().threshold(SportType.RUNNING).getR1().getSpeed();
        assertEquals(cycling, cyclingR1, 1e-9, "CYCLING R1 阈值往返后须等于写入前的 15.0");
        assertEquals(running, runningR1, 1e-9, "RUNNING R1 阈值往返后须等于写入前的 5.5");
        assertNotEquals(runningR1, cyclingR1,
                "按类型分维度的嵌套阈值不得在序列化往返中退化为同一默认值");
    }

    /**
     * 负向断言：故意让 Redis 里的 JSON 无法反序列化（损坏载荷），断言此时不会把
     * 「悄悄出现的默认值」当成命中的正常值交给上层——真实结果是回源 DB 的 loader 值，
     * 并且必然有一条 {@code Redis 读取规则失败} 的 warn 日志可观测（R2 的静默降级
     * 至少要有告警出口，否则压测失实连排查线索都没有）。
     *
     * <p>防的是这类事故：反序列化失败被 {@code readRedis} 吞掉返回 null，上层把
     * 降级结果当「命中」用，阈值悄悄变回默认却无人察觉。</p>
     */
    @Test
    void corruptedJson_fallsBackToDbLoaderAndEmitsWarn_notSilentDefaultHit() {
        // 损坏载荷：Jackson readValue 必然抛异常 → readRedis 进 catch → warn + null → 回源 DB
        redisStore.put(REDIS_KEY, "{oops-not-valid-json");
        double markerCyclingR1 = 99.0; // 与任何默认阈值都不同的标记值，用于区分「回源结果」与「默认值」
        VerifyProperties fromDb = nestedBySportType(5.5, markerCyclingR1);

        VerifyProperties result = service.get(KEY, VerifyProperties.class, () -> fromDb).orElseThrow();

        assertEquals(markerCyclingR1,
                result.getRules().threshold(SportType.CYCLING).getR1().getSpeed(), 1e-9,
                "反序列化失败必须回源 DB loader 的值，不得悄悄退化为默认阈值");
        assertTrue(warnCapture.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().contains("Redis 读取规则失败")),
                "静默降级必须可观测：应产生「Redis 读取规则失败」warn 日志");
    }
}
