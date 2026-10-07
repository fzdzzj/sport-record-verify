package com.sportverify.record.service;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sportverify.record.entity.RecordLike;
import com.sportverify.record.mapper.RecordLikeMapper;
import com.sportverify.record.mapper.SportRecordMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点赞写路径容量与对账收敛测量 IT（TASK-178，课题 5 首轮）。
 *
 * <p>在隔离 scratch 真库（Schema 自 {@code sql/02-record-db.sql} 机械改名重建）与受控真实
 * Redis（建议专用 DB 12）上，同包直调包私有 {@code flushPendingLikes()} /
 * {@code reconcileLikeCounts()}，对照生产默认 {@code app.like.flush-batch=200} 采集：</p>
 * <ul>
 *   <li><b>E1</b> flush 服务率三形态（M1 全新对 5000 / M2 抵消混合 7500 / M3 同 key 重复 2000），
 *       每形态 3 轮，逐轮核闭合断言（LRANGE 总量、LTRIM 总量、行数增量 =
 *       INSERT 影响行数 − DELETE 影响行数、LLEN 终态、终态行集 = 末次动作净结果）；</li>
 *   <li><b>E2</b> 对账成本曲线三档（400 / 4000 / 20000 records × 固定 50 赞），每档 2 轮
 *       （首轮冷、次轮稳态同值覆盖），记录载入耗时、分组规模、SET/DEL/SADD 写段耗时与总耗时、堆读数；</li>
 *   <li><b>A1</b> 收敛语义审计可测两条：A1.1 pending 丢失漂移单轮收敛、A1.2 对账覆盖未落库
 *       pending 两跳收敛；A1.3（DEL+SADD 非原子窗口）为代码级审计登记，不在本类注入并发。</li>
 * </ul>
 *
 * <p><b>装配与生产同构</b>：mapper 经 {@link MybatisSqlSessionFactoryBean} 构建后由
 * {@link SqlSessionTemplate} 暴露，使 flush 的 {@code TransactionTemplate} 两写（INSERT IGNORE +
 * DELETE）真正共用一个本地事务；锁走真实 Redisson 客户端（{@code lock:like:flush} /
 * {@code lock:like:reconcile} 的 tryLock 防重语义不降级）。计时经 JDK 动态代理包一层读写记录，
 * 不改动被测类。</p>
 *
 * <p><b>默认不进常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集，只有
 * {@code -Dtest=} 显式指定才跑），且要求环境变量，缺任一即 assume 跳过（不视为通过）：
 * {@code TASK178_IT_DB_URL} / {@code TASK178_IT_DB_USER} / {@code TASK178_IT_DB_PASSWORD} /
 * {@code TASK178_IT_REDIS_HOST} / {@code TASK178_IT_REDIS_PORT}（口令可选
 * {@code TASK178_IT_REDIS_PASSWORD}）。</p>
 *
 * <p><b>隔离纪律</b>：DB URL 由执行侧改写到专用 scratch 库 {@code task178_it}（连接后
 * {@code SELECT DATABASE()} 强校验），跑完 DROP；Redis 键带 {@code like:} / {@code lock:like:}
 * 前缀且落在专用 DB，绝不触碰演示库。原始读数落 {@code docs/perf/data/raw/}（ignored）。
 * 运行（完整命令与退出码留证于报告）：
 * <pre>
 * TASK178_IT_DB_URL='jdbc:mysql://127.0.0.1:3307/?useSSL=false&amp;allowPublicKeyRetrieval=true&amp;serverTimezone=Asia/Shanghai' \
 * TASK178_IT_DB_USER=root TASK178_IT_DB_PASSWORD=root \
 * TASK178_IT_REDIS_HOST=127.0.0.1 TASK178_IT_REDIS_PORT=16379 \
 * mvn -B -ntp -o -s .mvn-settings.xml -pl record-service -am test \
 *   -Dtest=RecordLikeWritePathCapacityIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * </p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecordLikeWritePathCapacityIT {

    private static final String SCRATCH_DB = "task178_it";
    /** 与生产默认 app.like.flush-batch 同值（service 字段初始化值，测内不注入 @Value）。 */
    private static final int FLUSH_BATCH = 200;
    /** 专用 Redis DB（演示键在 DB0，不受影响）。 */
    private static final int REDIS_DB = 12;

    private static final long E1_M1_BASE = 300_000L;
    private static final long E1_M2_CANCEL_BASE = 400_000L;
    private static final long E1_M2_PURE_BASE = 410_000L;
    private static final long E1_M3_BASE = 500_000L;
    private static final long E2_R1_BASE = 1_000_000L;
    private static final long E2_R2_BASE = 2_000_000L;
    private static final long E2_R3_BASE = 3_000_000L;
    private static final long A1_RECORD_A = 990_001L;
    private static final long A1_RECORD_B = 990_002L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String dbUser;
    private static String dbPassword;
    private static String redisHost;
    private static String redisPort;
    private static String serverUrl;
    private static String dbUrl;
    private static HikariDataSource dataSource;
    private static RedissonClient redisson;
    private static Recorder recorder;
    private static RecordingRedisTemplate template;
    private static RecordLikeService service;
    private static Path rawDir;

    private static final List<Map<String, Object>> E1_REPS = new ArrayList<>();
    private static final List<Map<String, Object>> E2_ROUNDS = new ArrayList<>();
    private static Map<String, Object> a1Result;

    @BeforeAll
    static void setUpAll() throws Exception {
        String envUrl = System.getenv("TASK178_IT_DB_URL");
        dbUser = System.getenv("TASK178_IT_DB_USER");
        dbPassword = System.getenv("TASK178_IT_DB_PASSWORD");
        redisHost = System.getenv("TASK178_IT_REDIS_HOST");
        redisPort = System.getenv("TASK178_IT_REDIS_PORT");
        String redisPassword = System.getenv("TASK178_IT_REDIS_PASSWORD");

        StringBuilder missing = new StringBuilder();
        if (isBlank(envUrl)) {
            missing.append(" TASK178_IT_DB_URL");
        }
        if (isBlank(dbUser)) {
            missing.append(" TASK178_IT_DB_USER");
        }
        if (isBlank(dbPassword)) {
            missing.append(" TASK178_IT_DB_PASSWORD");
        }
        if (isBlank(redisHost)) {
            missing.append(" TASK178_IT_REDIS_HOST");
        }
        if (isBlank(redisPort)) {
            missing.append(" TASK178_IT_REDIS_PORT");
        }
        Assumptions.assumeTrue(missing.length() == 0,
                "缺环境变量：" + missing + "，跳过点赞写路径容量 IT（不视为通过）");

        String[] urls = deriveUrls(envUrl, SCRATCH_DB);
        serverUrl = urls[0];
        dbUrl = urls[1];
        assertTrue(dbUrl.contains("/" + SCRATCH_DB),
                "DB URL 必须被改写指向专用 " + SCRATCH_DB + "，实际：" + dbUrl);

        rebuildScratchSchema();

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(dbUrl);
        hikari.setUsername(dbUser);
        hikari.setPassword(dbPassword);
        hikari.setMaximumPoolSize(4);
        hikari.setPoolName("task178-capacity-it");
        dataSource = new HikariDataSource(hikari);
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DATABASE()")) {
            assertTrue(rs.next(), "SELECT DATABASE() 应返回当前库");
            assertEquals(SCRATCH_DB, rs.getString(1), "JDBC URL 必须落到专用 " + SCRATCH_DB + "，禁止触碰演示库");
        }

        Config redisConfig = new Config();
        SingleServerConfig single = redisConfig.useSingleServer();
        single.setAddress("redis://" + redisHost + ":" + redisPort);
        single.setDatabase(REDIS_DB);
        single.setConnectTimeout(10_000);
        single.setTimeout(10_000);
        single.setRetryAttempts(3);
        if (!isBlank(redisPassword)) {
            single.setPassword(redisPassword);
        }
        redisson = Redisson.create(redisConfig);
        RedisConnectionFactory connectionFactory = new RedissonConnectionFactory(redisson);
        recorder = new Recorder();
        template = new RecordingRedisTemplate(connectionFactory, recorder);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory factory = factoryBean.getObject();
        Configuration configuration = factory.getConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(RecordLikeMapper.class);
        configuration.addMapper(SportRecordMapper.class);
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(factory);
        SportRecordMapper sportRecordMapper = sessionTemplate.getMapper(SportRecordMapper.class);
        RecordLikeMapper realLikeMapper = sessionTemplate.getMapper(RecordLikeMapper.class);
        RecordLikeMapper timedLikeMapper = timingMapper(realLikeMapper, recorder);
        PlatformTransactionManager txManager = new TimingTransactionManager(dataSource, recorder);
        service = new RecordLikeService(sportRecordMapper, timedLikeMapper, template, redisson,
                JSON, txManager, new SimpleMeterRegistry());

        rawDir = repoRoot().resolve("docs/perf/data/raw");
        Files.createDirectories(rawDir);
        writeRaw("task178-it-run-meta.json", runMeta(!isBlank(redisPassword)));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        try {
            if (template != null) {
                deleteKeysByPattern("like:*");
                deleteKeysByPattern("lock:like:*");
            }
        } catch (Exception ignored) {
            // 清理尽力而为，不影响结论
        }
        if (redisson != null) {
            redisson.shutdown();
        }
        if (dataSource != null) {
            try (Connection c = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
                 Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + SCRATCH_DB + "`");
            } catch (Exception ignored) {
                // scratch 库残留可人工清理，不影响结论
            }
            dataSource.close();
        }
        if (service != null) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("task", "TASK-178");
            summary.put("finishedAt", LocalDateTime.now().toString());
            summary.put("e1", E1_REPS);
            summary.put("e2", E2_ROUNDS);
            summary.put("a1", a1Result);
            writeRaw("task178-it-summary.json", summary);
        }
    }

    // ==================== E1：flush 服务率三形态 × 3 轮 ====================

    @Test
    @Order(1)
    @DisplayName("E1-M1 5000 互异纯 LIKE（INSERT 主导）×3 轮")
    void e1M1DistinctPureLikes() throws Exception {
        List<SeedPair> ops = new ArrayList<>(5_000);
        for (int i = 0; i < 5_000; i++) {
            ops.add(new SeedPair(E1_M1_BASE + i % 100, 1L + i / 100, "LIKE"));
        }
        runE1Shape("M1", ops, new ShapeExpectation(5_000, 0, 5_000, 0, 5_000, 25));
    }

    @Test
    @Order(2)
    @DisplayName("E1-M2 2500 抵消对 + 2500 互异纯 LIKE（无行可删幂等路径）×3 轮")
    void e1M2CancelPairsAndPureLikes() throws Exception {
        List<SeedPair> ops = new ArrayList<>(7_500);
        for (long j = 0; j < 2_500; j++) {
            ops.add(new SeedPair(E1_M2_CANCEL_BASE + j, 1L, "LIKE"));
            ops.add(new SeedPair(E1_M2_CANCEL_BASE + j, 1L, "UNLIKE"));
        }
        for (long k = 0; k < 2_500; k++) {
            ops.add(new SeedPair(E1_M2_PURE_BASE + k, 1L, "LIKE"));
        }
        runE1Shape("M2", ops, new ShapeExpectation(2_500, 2_500, 2_500, 0, 2_500, 38));
    }

    @Test
    @Order(3)
    @DisplayName("E1-M3 2000 条仅 100 互异 key 重复操作（末次动作去重路径）×3 轮")
    void e1M3RepeatedKeysLastWins() throws Exception {
        List<SeedPair> ops = new ArrayList<>(2_000);
        for (long k = 0; k < 100; k++) {
            long recordId = E1_M3_BASE + k;
            if (k < 50) {
                for (int m = 0; m < 10; m++) {
                    ops.add(new SeedPair(recordId, 1L, "UNLIKE"));
                }
                for (int m = 0; m < 10; m++) {
                    ops.add(new SeedPair(recordId, 1L, "LIKE"));
                }
            } else {
                for (int m = 0; m < 10; m++) {
                    ops.add(new SeedPair(recordId, 1L, "LIKE"));
                }
                for (int m = 0; m < 10; m++) {
                    ops.add(new SeedPair(recordId, 1L, "UNLIKE"));
                }
            }
        }
        runE1Shape("M3", ops, new ShapeExpectation(50, 50, 50, 0, 50, 10));
    }

    private static void runE1Shape(String shape, List<SeedPair> ops, ShapeExpectation expected) throws Exception {
        List<Map<String, Object>> reps = new ArrayList<>();
        for (int rep = 1; rep <= 3; rep++) {
            Map<String, Object> result = runE1Rep(shape, rep, ops, expected);
            writeRaw("task178-e1-" + shape.toLowerCase() + "-rep" + rep + ".json", result);
            assertAllChecks("E1-" + shape + "-rep" + rep, result);
            reps.add(result);
            E1_REPS.add(result);
        }
        for (int i = 1; i < reps.size(); i++) {
            for (String key : List.of("batchCount", "rangeTotal", "trimTotal", "likesInputTotal",
                    "unlikesInputTotal", "likesAffectedTotal", "unlikesAffectedTotal", "rowsDelta")) {
                assertEquals(reps.get(0).get(key), reps.get(i).get(key),
                        "E1 " + shape + " 轮间读数不稳定：" + key);
            }
        }
    }

    private static Map<String, Object> runE1Rep(String shape, int rep,
                                                List<SeedPair> ops, ShapeExpectation expected) throws Exception {
        resetScratch();
        long rowsBefore = countRows();
        assertEquals(0, rowsBefore, "seed 前 scratch 表必须为空");

        for (SeedPair op : ops) {
            template.opsForList().rightPush(RecordLikeService.PENDING_QUEUE_KEY,
                    pendingJson(op.recordId(), op.userId(), op.action()));
        }
        long seeded = ops.size();
        long llenAfterSeed = llen();
        assertEquals(seeded, llenAfterSeed, "seed 后 LLEN 必须等于元素数");

        recorder.reset();
        long wallStart = System.nanoTime();
        int rounds = drainFlush(seeded);
        long wallNanos = System.nanoTime() - wallStart;

        List<Event> events = recorder.snapshot();
        List<BatchRow> batches = reconstructBatches(events);
        long rangeTotal = 0;
        long trimTotal = 0;
        long likesInput = 0;
        long unlikesInput = 0;
        long likesAffected = 0;
        long unlikesAffected = 0;
        long txSpanTotal = 0;
        long txSpanMax = 0;
        List<Map<String, Object>> perBatch = new ArrayList<>();
        for (BatchRow b : batches) {
            rangeTotal += b.rangeN;
            trimTotal += b.trimN;
            likesInput += b.likesInput;
            unlikesInput += b.unlikesInput;
            likesAffected += b.likesAffected;
            unlikesAffected += b.unlikesAffected;
            txSpanTotal += b.txSpanNanos;
            txSpanMax = Math.max(txSpanMax, b.txSpanNanos);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rangeN", b.rangeN);
            row.put("trimN", b.trimN);
            row.put("likesInput", b.likesInput);
            row.put("likesAffected", b.likesAffected);
            row.put("unlikesInput", b.unlikesInput);
            row.put("unlikesAffected", b.unlikesAffected);
            row.put("txSpanMs", ms(b.txSpanNanos));
            perBatch.add(row);
        }
        long rowsAfter = countRows();
        long rowsDelta = rowsAfter - rowsBefore;
        long llenFinal = llen();
        Set<String> dbRowSet = selectAllPairs();
        Set<String> expectedPresent = new LinkedHashSet<>();
        Map<String, String> net = new LinkedHashMap<>();
        for (SeedPair op : ops) {
            net.put(op.recordId() + ":" + op.userId(), op.action());
        }
        for (Map.Entry<String, String> e : net.entrySet()) {
            if ("LIKE".equals(e.getValue())) {
                expectedPresent.add(e.getKey());
            }
        }

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("batchCountMatches", batches.size() == expected.batchCount());
        checks.put("rangeTotalEqualsSeeded", rangeTotal == seeded);
        checks.put("trimTotalEqualsSeeded", trimTotal == seeded);
        checks.put("roundsEqualBatchCount", rounds == batches.size());
        boolean perBatchTrimOk = true;
        for (BatchRow b : batches) {
            perBatchTrimOk &= b.trimN == b.rangeN;
        }
        checks.put("perBatchTrimEqualsRange", perBatchTrimOk);
        checks.put("rowsDeltaMatchesAffected", rowsDelta == likesAffected - unlikesAffected);
        checks.put("llFinalZero", llenFinal == 0);
        checks.put("likesInputMatchesExpected", likesInput == expected.likesInput());
        checks.put("unlikesInputMatchesExpected", unlikesInput == expected.unlikesInput());
        checks.put("likesAffectedMatchesExpected", likesAffected == expected.likesAffected());
        checks.put("unlikesAffectedMatchesExpected", unlikesAffected == expected.unlikesAffected());
        checks.put("rowsDeltaMatchesExpected", rowsDelta == expected.rowsDelta());
        checks.put("finalRowsMatchModel", new TreeSet<>(dbRowSet).equals(new TreeSet<>(expectedPresent)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shape", shape);
        result.put("rep", rep);
        result.put("seeded", seeded);
        result.put("llenAfterSeed", llenAfterSeed);
        result.put("rounds", rounds);
        result.put("batchCount", batches.size());
        result.put("rangeTotal", rangeTotal);
        result.put("trimTotal", trimTotal);
        result.put("likesInputTotal", likesInput);
        result.put("unlikesInputTotal", unlikesInput);
        result.put("likesAffectedTotal", likesAffected);
        result.put("unlikesAffectedTotal", unlikesAffected);
        result.put("rowsBefore", rowsBefore);
        result.put("rowsAfter", rowsAfter);
        result.put("rowsDelta", rowsDelta);
        result.put("llenFinal", llenFinal);
        result.put("drainWallMs", ms(wallNanos));
        result.put("txSpanTotalMs", ms(txSpanTotal));
        result.put("txSpanMaxMs", ms(txSpanMax));
        result.put("perBatch", perBatch);
        Map<String, Object> expectedMap = new LinkedHashMap<>();
        expectedMap.put("batchCount", expected.batchCount());
        expectedMap.put("seeded", seeded);
        expectedMap.put("likesInputTotal", expected.likesInput());
        expectedMap.put("unlikesInputTotal", expected.unlikesInput());
        expectedMap.put("likesAffectedTotal", expected.likesAffected());
        expectedMap.put("unlikesAffectedTotal", expected.unlikesAffected());
        expectedMap.put("rowsDelta", expected.rowsDelta());
        expectedMap.put("finalRows", expectedPresent.size());
        result.put("expected", expectedMap);
        result.put("checks", checks);
        return result;
    }

    // ==================== E2：对账成本曲线三档 × 2 轮 ====================

    @Test
    @Order(4)
    @DisplayName("E2-R1 400 records × 50 赞 = 2 万行对账 ×2 轮")
    void e2R1() throws Exception {
        e2Tier("R1", E2_R1_BASE, 400);
    }

    @Test
    @Order(5)
    @DisplayName("E2-R2 4000 records × 50 赞 = 20 万行对账 ×2 轮")
    void e2R2() throws Exception {
        e2Tier("R2", E2_R2_BASE, 4_000);
    }

    @Test
    @Order(6)
    @DisplayName("E2-R3 20000 records × 50 赞 = 100 万行对账 ×2 轮")
    void e2R3() throws Exception {
        e2Tier("R3", E2_R3_BASE, 20_000);
    }

    private static void e2Tier(String tier, long base, int records) throws Exception {
        resetScratch();
        long rows = records * 50L;
        recorder.reset();
        long seedStart = System.nanoTime();
        long inserted = seedLikeRows(base, records);
        long seedNanos = System.nanoTime() - seedStart;
        assertEquals(rows, inserted, tier + " seed 必须全量落行（空表 INSERT IGNORE，affected=行数）");

        Map<String, Object> round1 = reconcileRound(tier, 1, base, records, rows, seedNanos);
        writeRaw("task178-e2-" + tier.toLowerCase() + "-round1.json", round1);
        assertAllChecks("E2-" + tier + "-round1", round1);
        E2_ROUNDS.add(round1);
        Map<String, Object> round2 = reconcileRound(tier, 2, base, records, rows, null);
        writeRaw("task178-e2-" + tier.toLowerCase() + "-round2.json", round2);
        assertAllChecks("E2-" + tier + "-round2", round2);
        E2_ROUNDS.add(round2);

        for (String key : List.of("pairsLoaded", "distinctRecords", "setCount", "deleteCount",
                "saddCount", "saddMembersTotal")) {
            assertEquals(round1.get(key), round2.get(key), "E2 " + tier + " 稳态轮结构性计数不一致：" + key);
        }
    }

    private static Map<String, Object> reconcileRound(String tier, int round, long base, int records,
                                                      long rows, Long seedNanos) throws Exception {
        recorder.reset();
        long heapBefore = usedHeapBytes();
        long wallStart = System.nanoTime();
        service.reconcileLikeCounts();
        long wallNanos = System.nanoTime() - wallStart;
        long heapAfter = usedHeapBytes();

        List<Event> events = recorder.snapshot();
        PairsPayload pairs = null;
        long loadNanos = -1;
        long setCount = 0;
        long setNanos = 0;
        long deleteCount = 0;
        long deleteNanos = 0;
        long saddCount = 0;
        long saddNanos = 0;
        long saddMembers = 0;
        for (Event e : events) {
            switch (e.type()) {
                case "mapper.selectRecordLikePairs" -> {
                    pairs = (PairsPayload) e.payload();
                    loadNanos = e.durNanos();
                }
                case "value.set" -> {
                    setCount++;
                    setNanos += e.durNanos();
                }
                case "delete" -> {
                    deleteCount++;
                    deleteNanos += e.durNanos();
                }
                case "set.add" -> {
                    saddCount++;
                    saddNanos += e.durNanos();
                    if (e.payload() instanceof Integer members) {
                        saddMembers += members;
                    }
                }
                default -> {
                }
            }
        }
        long pairsLoaded = pairs == null ? -1 : pairs.size();
        long distinctRecords = pairs == null ? -1 : pairs.distinctRecords();
        double loadMs = pairs == null ? -1 : ms(loadNanos);

        List<Map<String, Object>> samples = new ArrayList<>();
        boolean samplesOk = true;
        long[] sampleIds = {base, base + records / 2, base + records - 1};
        for (long id : sampleIds) {
            String count = template.opsForValue().get("like:count:" + id);
            Long card = template.opsForSet().size("like:record:" + id + ":users");
            long dbCount = countDb(id);
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("recordId", id);
            sample.put("redisCount", count);
            sample.put("redisCard", card);
            sample.put("dbCount", dbCount);
            samples.add(sample);
            samplesOk &= "50".equals(count) && card != null && card == 50L && dbCount == 50L;
        }

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("pairsLoadedMatchesRows", pairsLoaded == rows);
        checks.put("distinctRecordsMatches", distinctRecords == records);
        checks.put("setCountMatches", setCount == records);
        checks.put("deleteCountMatches", deleteCount == records);
        checks.put("saddCountMatches", saddCount == records);
        checks.put("saddMembersMatchesRows", saddMembers == rows);
        checks.put("samplesAllFifty", samplesOk);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tier", tier);
        result.put("records", records);
        result.put("rows", rows);
        result.put("round", round);
        if (seedNanos != null) {
            result.put("seedMs", ms(seedNanos));
        }
        result.put("pairsLoaded", pairsLoaded);
        result.put("distinctRecords", distinctRecords);
        result.put("loadMs", loadMs);
        result.put("setCount", setCount);
        result.put("setTotalMs", ms(setNanos));
        result.put("deleteCount", deleteCount);
        result.put("deleteTotalMs", ms(deleteNanos));
        result.put("saddCount", saddCount);
        result.put("saddMembersTotal", saddMembers);
        result.put("saddTotalMs", ms(saddNanos));
        result.put("redisWriteTotalMs", ms(setNanos + deleteNanos + saddNanos));
        result.put("reconcileWallMs", ms(wallNanos));
        result.put("heapUsedBeforeMb", round1(heapBefore / 1048576.0));
        result.put("heapUsedAfterMb", round1(heapAfter / 1048576.0));
        result.put("heapMaxMb", round1(Runtime.getRuntime().maxMemory() / 1048576.0));
        result.put("samples", samples);
        Map<String, Object> expectedMap = new LinkedHashMap<>();
        expectedMap.put("pairsLoaded", rows);
        expectedMap.put("distinctRecords", records);
        expectedMap.put("setCount", records);
        expectedMap.put("deleteCount", records);
        expectedMap.put("saddCount", records);
        expectedMap.put("saddMembersTotal", rows);
        result.put("expected", expectedMap);
        result.put("checks", checks);
        return result;
    }

    // ==================== A1：收敛语义审计（可测两条 + 登记一条） ====================

    @Test
    @Order(7)
    @DisplayName("A1 收敛语义审计：A1.1 单轮收敛 / A1.2 两跳收敛")
    void a1ConvergenceSemantics() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();

        // A1.1 pending 丢失漂移 → 单轮收敛：Redis 计数/成员集含用户 777 而 DB 无行
        resetScratch();
        exec("INSERT INTO `record_like` (`record_id`,`user_id`,`created_at`) VALUES "
                + "(" + A1_RECORD_A + ",1,NOW()),(" + A1_RECORD_A + ",2,NOW()),(" + A1_RECORD_A + ",3,NOW())");
        template.opsForValue().set("like:count:" + A1_RECORD_A, "999");
        template.opsForSet().add("like:record:" + A1_RECORD_A + ":users", "1", "2", "3", "777");
        long a11DbRows = countDb(A1_RECORD_A);
        assertEquals(3L, a11DbRows, "A1.1 前置：DB 行 {1,2,3}");

        recorder.reset();
        service.reconcileLikeCounts();
        String a11Count = template.opsForValue().get("like:count:" + A1_RECORD_A);
        Set<String> a11Members = template.opsForSet().members("like:record:" + A1_RECORD_A + ":users");
        Boolean a11Stranger = template.opsForSet().isMember("like:record:" + A1_RECORD_A + ":users", "777");

        Map<String, Object> a11 = new LinkedHashMap<>();
        a11.put("setup", "redisCount=999, redisMembers={1,2,3,777}; dbRows={1,2,3}");
        a11.put("countAfterReconcile", a11Count);
        a11.put("membersAfterReconcile", a11Members == null ? null : new TreeSet<>(a11Members));
        a11.put("strangerStillMember", a11Stranger);
        boolean a11Ok = "3".equals(a11Count)
                && a11Members != null && new TreeSet<>(a11Members).equals(new TreeSet<>(List.of("1", "2", "3")))
                && Boolean.FALSE.equals(a11Stranger);
        a11.put("checkPassed", a11Ok);
        out.put("a11", a11);
        writeRaw("task178-a1.json", out);
        assertTrue(a11Ok, "A1.1 若失败：单轮对账后 Redis 计数/成员集未收敛到 DB 权威值；读数=" + resultText(a11));

        // A1.2 对账覆盖未落库 pending → 两跳收敛：DB 旧行 {1} + Redis 新态 {1,2} + pending LIKE(990002,2)
        resetScratch();
        exec("INSERT INTO `record_like` (`record_id`,`user_id`,`created_at`) VALUES "
                + "(" + A1_RECORD_B + ",1,NOW())");
        template.opsForValue().set("like:count:" + A1_RECORD_B, "2");
        template.opsForSet().add("like:record:" + A1_RECORD_B + ":users", "1", "2");
        template.opsForList().rightPush(RecordLikeService.PENDING_QUEUE_KEY,
                pendingJson(A1_RECORD_B, 2L, "LIKE"));

        // 第 1 跳：对账先跑（DB 旧值权威）→ 计数暂时回退为 1、成员集去掉 2；pending 未被触碰
        service.reconcileLikeCounts();
        String hop1Count = template.opsForValue().get("like:count:" + A1_RECORD_B);
        long hop1Llen = llen();
        Boolean hop1Member2 = template.opsForSet().isMember("like:record:" + A1_RECORD_B + ":users", "2");
        // 第 2 跳：flush 落库 pending → 再对账 → 收敛到含新行的权威态
        service.flushPendingLikes();
        long hop2Llen = llen();
        long dbRowsAfterFlush = countDb(A1_RECORD_B);
        service.reconcileLikeCounts();
        String hop2Count = template.opsForValue().get("like:count:" + A1_RECORD_B);
        Set<String> hop2Members = template.opsForSet().members("like:record:" + A1_RECORD_B + ":users");

        Map<String, Object> a12 = new LinkedHashMap<>();
        a12.put("setup", "dbRows={1}, pending=LIKE(990002,2) 未 flush; redisCount=2, redisMembers={1,2}");
        a12.put("hop1CountAfterReconcile", hop1Count);
        a12.put("hop1Llen", hop1Llen);
        a12.put("hop1Member2StillPresent", hop1Member2);
        a12.put("hop2LlenAfterFlush", hop2Llen);
        a12.put("dbRowsAfterFlush", dbRowsAfterFlush);
        a12.put("hop2CountAfterSecondReconcile", hop2Count);
        a12.put("hop2MembersAfterSecondReconcile", hop2Members == null ? null : new TreeSet<>(hop2Members));
        boolean a12Ok = "1".equals(hop1Count)
                && hop1Llen == 1L
                && Boolean.FALSE.equals(hop1Member2)
                && hop2Llen == 0L
                && dbRowsAfterFlush == 2L
                && "2".equals(hop2Count)
                && hop2Members != null && new TreeSet<>(hop2Members).equals(new TreeSet<>(List.of("1", "2")));
        a12.put("checkPassed", a12Ok);
        out.put("a12", a12);

        Map<String, Object> a13 = new LinkedHashMap<>();
        a13.put("kind", "CODE_AUDIT_REGISTERED");
        a13.put("note", "DEL+SADD 非原子窗口：不注入并发时序（超出本轮范围），窗口存在性与合并行为见报告代码级审计登记");
        out.put("a13", a13);
        a1Result = out;
        writeRaw("task178-a1.json", out);
        assertTrue(a12Ok, "A1.2 若失败：两跳后 Redis 未收敛到含新行的权威态；读数=" + resultText(a12));
    }

    // ==================== 装配与计时工具 ====================

    private static String[] deriveUrls(String envUrl, String dbName) {
        String cleaned = envUrl.trim();
        int q = cleaned.indexOf('?');
        String base = q >= 0 ? cleaned.substring(0, q) : cleaned;
        String query = q >= 0 ? cleaned.substring(q) : "";
        int schemeIdx = base.indexOf("://");
        assertTrue(schemeIdx > 0, "TASK178_IT_DB_URL 须为 jdbc:mysql://host:port 形态：" + envUrl);
        String afterAuthority = base.substring(schemeIdx + 3);
        int slashIdx = afterAuthority.indexOf('/');
        String hostPart = slashIdx >= 0 ? afterAuthority.substring(0, slashIdx) : afterAuthority;
        assertFalse(hostPart.isBlank(), "TASK178_IT_DB_URL 缺少 host:port：" + envUrl);
        String prefix = base.substring(0, schemeIdx + 3) + hostPart + "/";
        return new String[]{prefix + query, prefix + dbName + query};
    }

    private static RecordLikeMapper timingMapper(RecordLikeMapper real, Recorder rec) {
        return (RecordLikeMapper) Proxy.newProxyInstance(RecordLikeMapper.class.getClassLoader(),
                new Class<?>[]{RecordLikeMapper.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(real, args);
                    }
                    long start = System.nanoTime();
                    Object result;
                    try {
                        result = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    long end = System.nanoTime();
                    switch (method.getName()) {
                        case "batchInsertIgnore", "batchDelete" ->
                                rec.note("mapper." + method.getName(), start, end,
                                        new AffectedPayload(((List<?>) args[0]).size(), (Integer) result));
                        case "selectRecordLikePairs" -> {
                            List<?> pairs = (List<?>) result;
                            long distinct = pairs.stream()
                                    .map(l -> ((RecordLike) l).getRecordId())
                                    .distinct().count();
                            rec.note("mapper.selectRecordLikePairs", start, end,
                                    new PairsPayload(pairs.size(), distinct));
                        }
                        default -> rec.note("mapper." + method.getName(), start, end, null);
                    }
                    return result;
                });
    }

    private static final class RecordingRedisTemplate extends StringRedisTemplate {

        private final Recorder recorder;

        RecordingRedisTemplate(RedisConnectionFactory factory, Recorder recorder) {
            super(factory);
            this.recorder = recorder;
        }

        @Override
        public ListOperations<String, String> opsForList() {
            return timed(ListOperations.class, super.opsForList());
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return timed(ValueOperations.class, super.opsForValue());
        }

        @Override
        public SetOperations<String, String> opsForSet() {
            return timed(SetOperations.class, super.opsForSet());
        }

        @Override
        public Boolean delete(String key) {
            long start = System.nanoTime();
            Boolean result = super.delete(key);
            recorder.note("delete", start, System.nanoTime(), key);
            return result;
        }

        @SuppressWarnings("unchecked")
        private <T> T timed(Class<T> iface, T target) {
            return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(target, args);
                        }
                        long start = System.nanoTime();
                        Object result;
                        try {
                            result = method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        long end = System.nanoTime();
                        recorder.note(opsType(method.getName()), start, end,
                                opsPayload(method.getName(), args, result));
                        return result;
                    });
        }

        private static String opsType(String opsMethod) {
            return switch (opsMethod) {
                case "range", "trim", "size", "rightPush" -> "list." + opsMethod;
                case "set", "get", "increment", "decrement" -> "value." + opsMethod;
                case "add", "remove", "isMember", "members" -> "set." + opsMethod;
                default -> "ops." + opsMethod;
            };
        }

        private static Object opsPayload(String opsMethod, Object[] args, Object result) {
            return switch (opsMethod) {
                case "range" -> new RangePayload(((Number) args[1]).longValue(),
                        ((Number) args[2]).longValue(),
                        result instanceof List<?> list ? list.size() : 0);
                case "trim" -> new TrimPayload(((Number) args[1]).longValue());
                case "add" -> {
                    if (args != null && args.length == 2 && args[1] instanceof String[] members) {
                        yield members.length;
                    }
                    yield args == null ? 0 : Math.max(0, args.length - 1);
                }
                default -> null;
            };
        }
    }

    private static final class TimingTransactionManager implements PlatformTransactionManager {

        private final DataSourceTransactionManager delegate;
        private final Recorder recorder;
        private long lastBeginStart = -1;

        TimingTransactionManager(javax.sql.DataSource dataSource, Recorder recorder) {
            this.delegate = new DataSourceTransactionManager(dataSource);
            this.recorder = recorder;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            long start = System.nanoTime();
            TransactionStatus status = delegate.getTransaction(definition);
            long end = System.nanoTime();
            recorder.note("tx.begin", start, end, null);
            lastBeginStart = start;
            return status;
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            long start = System.nanoTime();
            delegate.commit(status);
            long end = System.nanoTime();
            recorder.note("tx.commit", start, end, end - lastBeginStart);
            lastBeginStart = -1;
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            delegate.rollback(status);
        }
    }

    private static final class Recorder {

        private final List<Event> events = new ArrayList<>();

        synchronized void note(String type, long startNanos, long endNanos, Object payload) {
            events.add(new Event(type, startNanos, endNanos, payload));
        }

        synchronized void reset() {
            events.clear();
        }

        synchronized List<Event> snapshot() {
            return new ArrayList<>(events);
        }
    }

    private record Event(String type, long startNanos, long endNanos, Object payload) {

        long durNanos() {
            return endNanos - startNanos;
        }
    }

    private record RangePayload(long start, long end, int size) {
    }

    private record TrimPayload(long n) {
    }

    private record AffectedPayload(int inputSize, int affected) {
    }

    private record PairsPayload(long size, long distinctRecords) {
    }

    private record SeedPair(long recordId, long userId, String action) {
    }

    private record ShapeExpectation(long likesInput, long unlikesInput, long likesAffected,
                                    long unlikesAffected, long rowsDelta, int batchCount) {
    }

    private static final class BatchRow {

        private final long rangeN;
        private long trimN;
        private long likesInput;
        private long likesAffected;
        private long unlikesInput;
        private long unlikesAffected;
        private long txSpanNanos;

        BatchRow(long rangeN) {
            this.rangeN = rangeN;
        }
    }

    // ==================== 数据面与驱动工具 ====================

    private static List<BatchRow> reconstructBatches(List<Event> events) {
        List<BatchRow> batches = new ArrayList<>();
        for (Event e : events) {
            switch (e.type()) {
                case "list.range" -> {
                    if (e.payload() instanceof RangePayload rp && rp.end() >= 1) {
                        batches.add(new BatchRow(rp.size()));
                    }
                }
                case "mapper.batchInsertIgnore" -> {
                    AffectedPayload ap = (AffectedPayload) e.payload();
                    BatchRow last = batches.get(batches.size() - 1);
                    last.likesInput += ap.inputSize();
                    last.likesAffected += ap.affected();
                }
                case "mapper.batchDelete" -> {
                    AffectedPayload ap = (AffectedPayload) e.payload();
                    BatchRow last = batches.get(batches.size() - 1);
                    last.unlikesInput += ap.inputSize();
                    last.unlikesAffected += ap.affected();
                }
                case "tx.commit" -> batches.get(batches.size() - 1).txSpanNanos = (Long) e.payload();
                case "list.trim" -> batches.get(batches.size() - 1).trimN = ((TrimPayload) e.payload()).n();
                default -> {
                }
            }
        }
        return batches;
    }

    private static int drainFlush(long seeded) {
        int guard = (int) (seeded / FLUSH_BATCH) + 5;
        int rounds = 0;
        for (int i = 0; i < guard; i++) {
            service.flushPendingLikes();
            rounds++;
            if (llen() == 0) {
                break;
            }
        }
        return rounds;
    }

    private static long seedLikeRows(long base, int records) throws SQLException {
        long total = 0;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            StringBuilder sql = new StringBuilder(
                    "INSERT IGNORE INTO `record_like` (`record_id`,`user_id`,`created_at`) VALUES ");
            int rowsInStatement = 0;
            for (int i = 0; i < records; i++) {
                for (int u = 1; u <= 50; u++) {
                    if (rowsInStatement > 0) {
                        sql.append(',');
                    }
                    sql.append('(').append(base + i).append(',').append(u).append(",'2026-10-07 20:00:00')");
                    rowsInStatement++;
                    if (rowsInStatement == 2_000) {
                        total += s.executeUpdate(sql.toString());
                        sql.setLength(0);
                        sql.append("INSERT IGNORE INTO `record_like` (`record_id`,`user_id`,`created_at`) VALUES ");
                        rowsInStatement = 0;
                    }
                }
            }
            if (rowsInStatement > 0) {
                total += s.executeUpdate(sql.toString());
            }
        }
        return total;
    }

    private static String pendingJson(long recordId, long userId, String action) {
        ObjectNode node = JSON.createObjectNode();
        node.put("recordId", recordId);
        node.put("userId", userId);
        node.put("action", action);
        node.put("enqueuedAt", System.currentTimeMillis());
        return node.toString();
    }

    private static void resetScratch() throws SQLException {
        exec("TRUNCATE TABLE `record_like`");
        deleteKeysByPattern("like:*");
        deleteKeysByPattern("lock:like:*");
        assertEquals(0, llen(), "重置后 pending 队列必须为空");
        assertEquals(0, countRows(), "重置后 scratch 表必须为空");
    }

    private static long deleteKeysByPattern(String pattern) {
        Set<String> keys = template.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long deleted = template.delete(keys);
        return deleted == null ? 0 : deleted;
    }

    private static long llen() {
        Long size = template.opsForList().size(RecordLikeService.PENDING_QUEUE_KEY);
        return size == null ? 0 : size;
    }

    private static long countRows() throws SQLException {
        return queryLong("SELECT COUNT(*) FROM `record_like`");
    }

    private static long countDb(long recordId) throws SQLException {
        return queryLong("SELECT COUNT(*) FROM `record_like` WHERE `record_id` = " + recordId);
    }

    private static long queryLong(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "查询应返回一行：" + sql);
            return rs.getLong(1);
        }
    }

    private static Set<String> selectAllPairs() throws SQLException {
        Set<String> pairs = new LinkedHashSet<>();
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT `record_id`, `user_id` FROM `record_like`")) {
            while (rs.next()) {
                pairs.add(rs.getLong(1) + ":" + rs.getLong(2));
            }
        }
        return pairs;
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void rebuildScratchSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS `" + SCRATCH_DB + "`");
            Path sqlFile = repoRoot().resolve("sql/02-record-db.sql");
            String script = new String(Files.readAllBytes(sqlFile), StandardCharsets.UTF_8)
                    .replace("record_db", SCRATCH_DB);
            for (String statement : splitStatements(script)) {
                s.execute(statement);
            }
        }
    }

    private static List<String> splitStatements(String script) {
        StringBuilder code = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            code.append(trimmed).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : code.toString().split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("sql/02-record-db.sql"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根（sql/02-record-db.sql）：自 "
                + Paths.get("").toAbsolutePath() + " 向上查找");
    }

    // ==================== 结果落盘 ====================

    private static Map<String, Object> runMeta(boolean redisPasswordSet) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("task", "TASK-178");
        meta.put("writtenAt", LocalDateTime.now().toString());
        meta.put("scratchDb", SCRATCH_DB);
        meta.put("dbUrl", dbUrl);
        try {
            meta.put("mysqlVersion", scalarString("SELECT VERSION()"));
        } catch (SQLException e) {
            meta.put("mysqlVersion", "query-failed: " + e.getMessage());
        }
        meta.put("redisHost", redisHost);
        meta.put("redisPort", redisPort);
        meta.put("redisDb", REDIS_DB);
        meta.put("redisPasswordSet", redisPasswordSet);
        try {
            Properties info = template.execute(
                    (RedisCallback<Properties>) connection -> connection.serverCommands().info("server"));
            if (info != null) {
                meta.put("redisRunId", info.getProperty("run_id"));
                meta.put("redisVersion", info.getProperty("redis_version"));
                meta.put("redisTcpPort", info.getProperty("tcp_port"));
            }
        } catch (Exception e) {
            meta.put("redisInfo", "query-failed: " + e.getMessage());
        }
        meta.put("javaVersion", System.getProperty("java.version"));
        meta.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        meta.put("maxHeapMb", round1(Runtime.getRuntime().maxMemory() / 1048576.0));
        meta.put("flushBatch", FLUSH_BATCH);
        return meta;
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void writeRaw(String fileName, Map<String, Object> data) throws Exception {
        Files.write(rawDir.resolve(fileName),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
    }

    /** 读数已落盘之后再判定：checks 任一为 false 即该轮闭合断言失败（FAILED 支证据保留在 raw 文件里）。 */
    @SuppressWarnings("unchecked")
    private static void assertAllChecks(String label, Map<String, Object> result) {
        Map<String, Object> checks = (Map<String, Object>) result.get("checks");
        assertNotNull(checks, label + " 缺少 checks 字段");
        for (Map.Entry<String, Object> c : checks.entrySet()) {
            assertTrue((Boolean) c.getValue(),
                    label + " 检查未通过：" + c.getKey() + "；读数=" + resultText(result));
        }
    }

    private static String resultText(Map<String, Object> data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            return data.toString();
        }
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 1000.0) / 1000.0;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
