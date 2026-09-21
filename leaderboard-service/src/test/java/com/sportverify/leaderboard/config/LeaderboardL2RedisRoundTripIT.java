package com.sportverify.leaderboard.config;

import com.sportverify.api.record.dto.LeaderboardDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 榜单二级缓存 L2 的<b>真 Redis</b> JDK 序列化往返（TASK-110 事项 A）。
 *
 * <p>已有 {@link HierarchicalCacheRedisRoundTripTest} 只在「内存 Map 假件」上证明过
 * {@code JdkSerializationRedisSerializer} 存 {@code List<LeaderboardDTO}> 的字节活着；
 * 本类把同一套生产 {@link CacheConfig#hierarchicalCacheManager(RedisConnectionFactory)} 接到
 * <b>真实 Redis</b> 上：写入 {@code byte[]} 真正落到远端存储，另一个全新实例（等价于另一台机器或
 * 同一机器的另一次读取）能按 {@code @Cacheable} 语义原样读回。</p>
 *
 * <p><b>实例归属核实</b>：{@link CacheConfig} 之 {@code RedisCacheConfiguration} 里的值走
 * {@code JdkSerializationRedisSerializer}、键走 {@code StringRedisSerializer}，TTL=5 分钟——
 * 本类连上的 Redis 正是启动脚本里与容器 {@code sport-verify-redis}（本机 6379 常驻原生
 * {@code redis-server.exe} 会抢占端口，需把容器另映射宿主端口的做法见回传）核对过 run_id 的那一个。
 * 连接后本类会打印 {@code INFO server} 的 {@code run_id}/{@code tcp_port}，与
 * {@code docker exec sport-verify-redis redis-cli -p 6379 INFO server} 的输出比对即可确认连的是容器实例。</p>
 *
 * <p><b>红线复演</b>（断言被破坏即红）：把方法内 {@code scale()} 的期望从 {@code 2} 临时改成
 * {@code 3}，或改监听实例读取的断言值，重跑本类即红（如 {@code expected: 3 but was: 2}）；
 * 还原即绿。</p>
 *
 * <p><b>需要真 Redis，默认不进常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集，
 * 仅 {@code --it} 定向执行），缺 {@code TASK110_IT_REDIS_HOST} / {@code TASK110_IT_REDIS_PORT}
 * 任一即 assume 跳过（按未覆盖记账，不计通过）。口令不设则连无口令实例。</p>
 */
class LeaderboardL2RedisRoundTripIT {

    private static final String CACHE_NAME = "leaderboard:overall";
    /** 用独立大数用户键，避免与真实 ZSet/榜单键纠缠；写完清理 */
    private static final Long USER_KEY = 9900100L;
    private static final String REDIS_KEY = CACHE_NAME + "::" + USER_KEY;
    /** 生产缓存写穿后预计的 TTL（秒）——与 {@code CacheConfig#CACHE_TTL} 对齐 */
    private static final long EXPECTED_TTL_SECONDS = 300L;

    private final List<LeaderboardDTO> written = List.of(
            LeaderboardDTO.of(1, 1001L, "阿跑", new BigDecimal("12.50")),
            LeaderboardDTO.of(2, 1002L, "阿骑", new BigDecimal("88.05")));

    private RedissonClient redisson;
    private RedisConnectionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        String host = System.getenv("TASK110_IT_REDIS_HOST");
        String port = System.getenv("TASK110_IT_REDIS_PORT");
        Assumptions.assumeTrue(host != null && port != null && !host.isBlank() && !port.isBlank(),
                "缺 TASK110_IT_REDIS_HOST/PORT 环境变量，跳过真 Redis 集成测试（不视为通过）");

        String password = System.getenv("TASK110_IT_REDIS_PASSWORD");
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }
        redisson = Redisson.create(config);
        factory = new RedissonConnectionFactory(redisson);

        // 清掉同一键的上一轮残留，保证断言的是本轮写入
        try (RedisConnection c = factory.getConnection()) {
            c.del(REDIS_KEY.getBytes(StandardCharsets.UTF_8));
        }
    }

    @AfterEach
    void tearDown() {
        try {
            if (factory != null) {
                try (RedisConnection c = factory.getConnection()) {
                    c.del(REDIS_KEY.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception ignore) {
            // 清理尽力而为
        }
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @Test
    void roundTripsListThroughRealRedisWithScaleAndTtl() {
        // 生产 @Bean 路径：不经内存假件，字节真正落到远端 Redis
        CacheManager manager = new CacheConfig().hierarchicalCacheManager(factory);
        manager.getCache(CACHE_NAME).put(USER_KEY, written);

        // 另一个全新实例（新 CacheManager，L1 为空）按 @Cacheable 语义从真 Redis 读回
        Cache fresh = new CacheConfig().hierarchicalCacheManager(factory).getCache(CACHE_NAME);
        Cache.ValueWrapper hit = fresh.get(USER_KEY);
        assertNotNull(hit, "真 Redis 中应存在 JDK 序列化写下的榜单条目");

        @SuppressWarnings("unchecked")
        List<LeaderboardDTO> restored = (List<LeaderboardDTO>) hit.get();
        assertEquals(written, restored);
        // compareTo 相等但 scale 不同＝精度在往返里丢了（12.50 变 12.5），榜单里程要显式两位小数
        assertEquals(2, restored.get(0).getDistance().scale(), "BigDecimal scale 必须在真 Redis 往返中存活");
        assertEquals(2, restored.get(1).getDistance().scale(), "BigDecimal scale 必须在真 Redis 往返中存活");

        // TTL 必须真的落到远端（配了 5 分钟却写死不过期=会漏测的静默失效）
        long ttl;
        try (RedisConnection c = factory.getConnection()) {
            ttl = c.ttl(REDIS_KEY.getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(ttl <= EXPECTED_TTL_SECONDS && ttl >= EXPECTED_TTL_SECONDS - 20,
                "TTL 应为约 " + EXPECTED_TTL_SECONDS + " 秒，实际 " + ttl);

        // 实例归属证据：打印 INFO server 的 run_id / tcp_port，回传与 docker exec 结果比对
        try (RedisConnection c = factory.getConnection()) {
            Properties info = c.info();
            String runId = info.getProperty("run_id");
            String tcpPort = info.getProperty("tcp_port");
            System.out.println("[TASK110] redis instance run_id=" + runId + " tcp_port=" + tcpPort
                    + " (connected to " + System.getenv("TASK110_IT_REDIS_HOST") + ":"
                    + System.getenv("TASK110_IT_REDIS_PORT") + ")");
            assertNotNull(runId, "应取到 INFO server 的 run_id 作为实例归属凭据");
        }
    }
}