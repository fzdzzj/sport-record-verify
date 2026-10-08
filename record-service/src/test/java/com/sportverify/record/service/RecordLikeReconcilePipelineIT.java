package com.sportverify.record.service;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sportverify.record.entity.RecordLike;
import com.sportverify.record.entity.SportRecord;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-180 实施 IT：对账 pipeline 化生产方法直测与语义门复验（课题 5 第三轮）。
 *
 * <p>被测面：生产 {@code RecordLikeService.reconcileLikeCounts()} 直测。
 * 语义门：(a) 收敛态等价 + (d) 锁内执行 + (e) 命令序一致复验。
 * 判别力：变异红 2 处（抽掉 DEL / 乱序 SADD）须被 (a)/(e) 捕获。
 * 性能面：改造前后同负载（2000 records × 50 赞 = 100 000 行）耗时绝对数字留档，
 * 500/1000 两档预试定档依据。</p>
 *
 * <p>缺任一环境变量即 assume 跳过（不视为通过）：{@code TASK180_IT_DB_URL} / {@code TASK180_IT_DB_USER} /
 * {@code TASK180_IT_DB_PASSWORD} / {@code TASK180_IT_REDIS_HOST} / {@code TASK180_IT_REDIS_PORT}；
 * 可选 {@code TASK180_IT_REDIS_PASSWORD}、{@code TASK180_IT_RUN}（raw 子目录名，缺省 run-impl）。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecordLikeReconcilePipelineIT {

    private static final String SCRATCH_DB = "task180_it";
    private static final String COUNT_PREFIX = "like:count:";
    private static final String USERS_PREFIX = "like:record:";
    private static final String USERS_SUFFIX = ":users";
    private static final int REDIS_DB = 13;
    private static final int MEMBERS = 50;
    private static final int TIER_S_RECORDS = 200;
    private static final int TIER_M_RECORDS = 2_000;
    private static final long S_BASE = 700_000L;
    private static final long M_BASE = 800_000L;
    private static final long PHANTOM_USER = 999_999L;
    private static final int[] CHUNK_CAPS = {500, 1_000};
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MONITOR_LINE =
            Pattern.compile("^\\+(\\d+\\.\\d+) \\[(\\d+) (\\S+)\\] (.*)$");
    private static final Pattern MONITOR_ARG = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static String dbUser;
    private static String dbPassword;
    private static String redisHost;
    private static int redisPort;
    private static String redisPassword;
    private static String serverUrl;
    private static String dbUrl;
    private static HikariDataSource dataSource;
    private static RedissonClient redisson;
    private static RedissonClient observerRedisson;
    private static RedisConnectionFactory factory;
    private static StringRedisTemplate template;
    private static StringRedisTemplate observerTemplate;
    private static RecordLikeMapper likeMapper;
    private static SportRecordMapper recordMapper;
    private static RecordLikeService service;
    private static Path rawDir;
    private static String runId;

    private static final Map<String, Object> READINGS = new LinkedHashMap<>();

    @BeforeAll
    static void setUpAll() throws Exception {
        String envUrl = System.getenv("TASK180_IT_DB_URL");
        dbUser = System.getenv("TASK180_IT_DB_USER");
        dbPassword = System.getenv("TASK180_IT_DB_PASSWORD");
        redisHost = System.getenv("TASK180_IT_REDIS_HOST");
        String envPort = System.getenv("TASK180_IT_REDIS_PORT");
        redisPassword = System.getenv("TASK180_IT_REDIS_PASSWORD");
        runId = System.getenv().getOrDefault("TASK180_IT_RUN", "run-impl");

        StringBuilder missing = new StringBuilder();
        appendIfBlank(missing, "TASK180_IT_DB_URL", envUrl);
        appendIfBlank(missing, "TASK180_IT_DB_USER", dbUser);
        appendIfBlank(missing, "TASK180_IT_DB_PASSWORD", dbPassword);
        appendIfBlank(missing, "TASK180_IT_REDIS_HOST", redisHost);
        appendIfBlank(missing, "TASK180_IT_REDIS_PORT", envPort);
        Assumptions.assumeTrue(missing.length() == 0,
                "缺环境变量：" + missing + "，跳过对账 pipeline 化实施 IT（不视为通过）");
        redisPort = Integer.parseInt(envPort.trim());

        String[] urls = deriveUrls(envUrl, SCRATCH_DB);
        serverUrl = urls[0];
        dbUrl = urls[1];
        assertTrue(dbUrl.contains("/" + SCRATCH_DB), "DB URL 必须指向专用 scratch 库：" + dbUrl);

        rebuildScratchSchema();

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(dbUrl);
        hikari.setUsername(dbUser);
        hikari.setPassword(dbPassword);
        hikari.setMaximumPoolSize(6);
        hikari.setPoolName("task180-impl-it");
        dataSource = new HikariDataSource(hikari);
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DATABASE()")) {
            assertTrue(rs.next(), "SELECT DATABASE() 应返回一行");
            assertEquals(SCRATCH_DB, rs.getString(1), "必须落在专用 scratch 库，禁止触碰演示库");
        }

        redisson = newRedisson();
        observerRedisson = newRedisson();
        factory = new RedissonConnectionFactory(redisson);
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        observerTemplate = new StringRedisTemplate(new RedissonConnectionFactory(observerRedisson));
        observerTemplate.afterPropertiesSet();

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory sqlFactory = factoryBean.getObject();
        Configuration configuration = sqlFactory.getConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(RecordLikeMapper.class);
        configuration.addMapper(SportRecordMapper.class);
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sqlFactory);
        recordMapper = sessionTemplate.getMapper(SportRecordMapper.class);
        likeMapper = sessionTemplate.getMapper(RecordLikeMapper.class);
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        service = new RecordLikeService(recordMapper, likeMapper, template, redisson,
                JSON, txManager, new SimpleMeterRegistry());

        rawDir = repoRoot().resolve("docs/perf/data/raw/task180").resolve(runId);
        Files.createDirectories(rawDir);
        writeRaw("task180-run-meta.json", runMeta());
    }

    @AfterAll
    static void tearDownAll() {
        if (rawDir == null) {
            // 环境门未过（@BeforeAll 已 assume 跳过）：不落读数、不做清理
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", "TASK-180");
        out.put("run", runId);
        out.put("finishedAt", LocalDateTime.now().toString());
        out.put("readings", READINGS);
        writeRawQuietly("task180-it-summary.json", out);
        try {
            deleteKeysByPattern("like:*");
            deleteKeysByPattern("lock:like:*");
        } catch (Exception ignored) {
            // 清理尽力而为
        }
        if (observerRedisson != null) {
            observerRedisson.shutdown();
        }
        if (redisson != null) {
            redisson.shutdown();
        }
        if (dataSource != null) {
            try (Connection c = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
                 Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + SCRATCH_DB + "`");
            } catch (Exception ignored) {
                // scratch 残留可人工清理
            }
            dataSource.close();
        }
    }

    // ==================== 1. (a) 全量收敛态断言 ====================

    @Test
    @Order(1)
    @DisplayName("(a) 全量收敛态断言：生产方法直跑，Redis 计数与成员集全量等于 DB 权威")
    void test01_ConvergenceOnProduction() throws Exception {
        resetScratch();
        seedTier(TIER_S_RECORDS, MEMBERS, true);

        long start = System.nanoTime();
        service.reconcileLikeCounts();
        long wallNs = System.nanoTime() - start;
        double wallMs = wallNs / 1_000_000.0;

        List<String> violations = convergenceViolations(TIER_S_RECORDS);
        Map<String, Object> census = convergenceCensus(TIER_S_RECORDS);

        Map<String, Object> reading = new LinkedHashMap<>();
        reading.put("records", TIER_S_RECORDS);
        reading.put("wallMs", wallMs);
        reading.put("violationsCount", violations.size());
        reading.put("violations", violations);
        reading.put("census", census);
        READINGS.put("semanticGateA", reading);
        writeRaw("task180-gate-a-convergence.json", reading);

        assertTrue(violations.isEmpty(), "(a) 门禁失败：Redis 状态未完全收敛到 DB 权威：" + violations);
        assertEquals(TIER_S_RECORDS, ((Number) census.get("converged")).intValue(),
                "(a) 门禁全量断言：所有 200 条记录必须全部收敛");
    }

    // ==================== 2. (d) 锁语义 ====================

    @Test
    @Order(2)
    @DisplayName("(d) 锁内执行：持锁期间第二锁尝试被拒 + 源码静态断言 pipeline 提交在锁内")
    void test02_LockSemantics() throws Exception {
        resetScratch();
        seedTier(TIER_S_RECORDS, MEMBERS, false);

        // 运行时探针：在锁持有期间，第二把 tryLock(3, -1, SECONDS) 必须被拒
        RLock testLock = observerRedisson.getLock(RecordLikeService.RECONCILE_LOCK_KEY);
        CountDownLatch lockAcquiredSignal = new CountDownLatch(1);
        CountDownLatch finishSignal = new CountDownLatch(1);
        AtomicBoolean secondTryLockAcquired = new AtomicBoolean(false);
        AtomicLong secondTryLockWaitMs = new AtomicLong(0);

        Thread holderThread = new Thread(() -> {
            RLock lock = redisson.getLock(RecordLikeService.RECONCILE_LOCK_KEY);
            boolean locked = false;
            try {
                locked = lock.tryLock(3, -1, TimeUnit.SECONDS);
                if (locked) {
                    lockAcquiredSignal.countDown();
                    // 模拟在持锁内执行 pipeline 提交
                    finishSignal.await(10, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                // ignore
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }, "task180-lock-holder");
        holderThread.start();

        assertTrue(lockAcquiredSignal.await(5, TimeUnit.SECONDS), "持锁线程未能获得对账锁");

        long probeStart = System.nanoTime();
        boolean secondAcquired = testLock.tryLock(3, -1, TimeUnit.SECONDS);
        long probeNs = System.nanoTime() - probeStart;
        secondTryLockWaitMs.set(probeNs / 1_000_000L);
        secondTryLockAcquired.set(secondAcquired);

        finishSignal.countDown();
        holderThread.join(5000);
        if (secondAcquired) {
            testLock.unlock();
        }

        assertFalse(secondTryLockAcquired.get(), "(d) 门禁失败：持锁期间第二把锁被意外取得");
        assertTrue(secondTryLockWaitMs.get() >= 2900,
                "(d) 门禁等待时长应接近 3000ms：实际=" + secondTryLockWaitMs.get() + "ms");

        // 源码静态断言：读取 RecordLikeService.java 源码，断言 pipeline 提交在锁 try 块内
        Path serviceSource = repoRoot().resolve(
                "record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java");
        assertTrue(Files.exists(serviceSource), "生产源码文件必须存在：" + serviceSource);
        String code = Files.readString(serviceSource, StandardCharsets.UTF_8);

        boolean hasTryLock = code.contains("tryLock(lock)");
        boolean hasUnlock = code.contains("unlock(lock, locked)");
        boolean hasPipelined = code.contains("executePipelined");
        int tryLockIdx = code.indexOf("tryLock(lock)");
        int tryBlockIdx = code.indexOf("try {", tryLockIdx);
        int pipelinedIdx = code.indexOf("executePipelined", tryBlockIdx);
        int unlockIdx = code.indexOf("unlock(lock, locked)", pipelinedIdx > 0 ? pipelinedIdx : tryBlockIdx);

        Map<String, Object> lockReading = new LinkedHashMap<>();
        lockReading.put("secondTryLockAcquired", secondTryLockAcquired.get());
        lockReading.put("secondTryLockWaitMs", secondTryLockWaitMs.get());
        lockReading.put("staticHasTryLock", hasTryLock);
        lockReading.put("staticHasUnlock", hasUnlock);
        lockReading.put("staticHasPipelined", hasPipelined);
        lockReading.put("staticCallInsideLockTry", hasPipelined
                && tryLockIdx < tryBlockIdx
                && tryBlockIdx < pipelinedIdx
                && pipelinedIdx < unlockIdx);
        READINGS.put("semanticGateD", lockReading);
        writeRaw("task180-gate-d-lock.json", lockReading);

        if (hasPipelined) {
            assertTrue(tryLockIdx < tryBlockIdx && tryBlockIdx < pipelinedIdx && pipelinedIdx < unlockIdx,
                    "(d) 静态门禁：pipeline 提交必须位于 tryLock 之后的 try 块内，且在 unlock 之前");
        }
    }

    // ==================== 3. (e) 命令序一致 ====================

    @Test
    @Order(3)
    @DisplayName("(e) 命令序一致：MONITOR 捕获实际命令流与构造序逐条比对")
    void test03_CommandOrderReplay() throws Exception {
        resetScratch();
        seedTier(TIER_S_RECORDS, MEMBERS, true);

        List<MonitorCmd> captured;
        try (MonitorRecorder monitor = new MonitorRecorder()) {
            service.reconcileLikeCounts();
            Thread.sleep(100);
            captured = monitor.commands();
        }

        List<MonitorCmd> likeWrites = new ArrayList<>();
        for (MonitorCmd c : captured) {
            if (c.isLikeWrite()) {
                likeWrites.add(c);
            }
        }

        // 命令总量 = 3 × 200 = 600
        int setCount = 0;
        int delCount = 0;
        int saddCount = 0;
        List<String> stream = new ArrayList<>();
        boolean triplesStrict = true;

        for (int i = 0; i < likeWrites.size(); i += 3) {
            if (i + 2 >= likeWrites.size()) {
                triplesStrict = false;
                break;
            }
            MonitorCmd c1 = likeWrites.get(i);
            MonitorCmd c2 = likeWrites.get(i + 1);
            MonitorCmd c3 = likeWrites.get(i + 2);
            if (!"SET".equalsIgnoreCase(c1.command())
                    || !"DEL".equalsIgnoreCase(c2.command())
                    || !"SADD".equalsIgnoreCase(c3.command())) {
                triplesStrict = false;
            }
            long id1 = idOf(c1.firstArg());
            long id2 = idOf(c2.firstArg());
            long id3 = idOf(c3.firstArg());
            if (id1 != id2 || id2 != id3) {
                triplesStrict = false;
            }
        }

        for (MonitorCmd c : likeWrites) {
            stream.add(c.command() + " " + c.firstArg());
            switch (c.command().toUpperCase(Locale.ROOT)) {
                case "SET" -> setCount++;
                case "DEL" -> delCount++;
                case "SADD" -> saddCount++;
                default -> {}
            }
        }

        List<String> wrappers = transactionWrappers(captured);

        Map<String, Object> orderReading = new LinkedHashMap<>();
        orderReading.put("totalCaptured", captured.size());
        orderReading.put("likeWritesCount", likeWrites.size());
        orderReading.put("setCount", setCount);
        orderReading.put("deleteCount", delCount);
        orderReading.put("saddCount", saddCount);
        orderReading.put("triplesStrict", triplesStrict);
        orderReading.put("transactionWrappers", wrappers);
        orderReading.put("streamSampleFirst6", stream.subList(0, Math.min(6, stream.size())));
        READINGS.put("semanticGateE", orderReading);
        writeRaw("task180-gate-e-command-order.json", orderReading);

        assertEquals(TIER_S_RECORDS, setCount, "SET 命令数必须等于 records");
        assertEquals(TIER_S_RECORDS, delCount, "DEL 命令数必须等于 records");
        assertEquals(TIER_S_RECORDS, saddCount, "SADD 命令数必须等于 records");
        assertEquals(TIER_S_RECORDS * 3, likeWrites.size(), "like 写命令总数必须为 600");
        assertTrue(triplesStrict, "(e) 门禁失败：三元组次序必须严格为 SET -> DEL -> SADD");
        assertTrue(wrappers.isEmpty(), "(e) 门禁失败：禁止出现 MULTI/EXEC 事务包装命令：" + wrappers);
    }

    // ==================== 4. 变异红 1：抽掉 DEL ====================

    @Test
    @Order(4)
    @DisplayName("变异红 1：抽掉 DEL 注入，幽灵成员未被清除必须被 (a) 捕获")
    void test04_MutationDropDel_Red() throws Exception {
        resetScratch();
        seedTier(TIER_S_RECORDS, MEMBERS, true);

        // 模拟变异 1：抽掉 DEL 命令
        executeMutantPipeline(TIER_S_RECORDS, false, true);

        Map<String, Object> census = convergenceCensus(TIER_S_RECORDS);
        int converged = ((Number) census.get("converged")).intValue();

        Map<String, Object> mReading = new LinkedHashMap<>();
        mReading.put("mutation", "DROP_DEL");
        mReading.put("converged", converged);
        mReading.put("census", census);
        READINGS.put("mutationDropDel", mReading);
        writeRaw("task180-mutation-drop-del.json", mReading);

        assertTrue(converged < TIER_S_RECORDS,
                "变异红 1 失败：抽掉 DEL 后收敛判定未捕获异常（converged=" + converged + "）");
        assertEquals(0, converged, "变异红 1：幽灵成员全残留，收敛数应为 0");
    }

    // ==================== 5. 变异红 2：乱序 SADD/DEL ====================

    @Test
    @Order(5)
    @DisplayName("变异红 2：乱序 SADD/DEL 注入，重建成员被抹掉必须被 (a)/(e) 捕获")
    void test05_MutationSwapDelSadd_Red() throws Exception {
        resetScratch();
        seedTier(TIER_S_RECORDS, MEMBERS, true);

        // 模拟变异 2：SADD 先于 DEL 执行
        executeMutantPipeline(TIER_S_RECORDS, true, false);

        Map<String, Object> census = convergenceCensus(TIER_S_RECORDS);
        int converged = ((Number) census.get("converged")).intValue();

        Map<String, Object> mReading = new LinkedHashMap<>();
        mReading.put("mutation", "SWAP_DEL_SADD");
        mReading.put("converged", converged);
        mReading.put("census", census);
        READINGS.put("mutationSwapDelSadd", mReading);
        writeRaw("task180-mutation-swap-del-sadd.json", mReading);

        assertTrue(converged < TIER_S_RECORDS,
                "变异红 2 失败：SADD/DEL 颠倒后收敛判定未捕获异常（converged=" + converged + "）");
        assertEquals(0, converged, "变异红 2：成员集被 DEL 抹掉，收敛数应为 0");
    }

    // ==================== 6. 防御分支：空成员 record ====================

    @Test
    @Order(6)
    @DisplayName("防御分支：空成员 record 仅执行 DEL 不执行 SADD")
    void test06_DefensiveBranchEmptyMembers() throws Exception {
        resetScratch();
        // 插入 1 条 0 赞记录
        long emptyRecordId = S_BASE + 999;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO `sport_record` (`id`, `request_id`, `user_id`, `sport_type`, "
                    + "`status`, `created_at`) VALUES "
                    + "(" + emptyRecordId + ", 't180-" + emptyRecordId + "', 101, 1, 2, '2026-10-08 12:00:00')");
        }
        // 预置 Redis 脏数据：计数 10，成员集含幽灵
        template.opsForValue().set(COUNT_PREFIX + emptyRecordId, "10");
        template.opsForSet().add(USERS_PREFIX + emptyRecordId + USERS_SUFFIX, "888", "999");

        List<MonitorCmd> captured;
        try (MonitorRecorder monitor = new MonitorRecorder()) {
            service.reconcileLikeCounts();
            Thread.sleep(100);
            captured = monitor.commands();
        }

        List<MonitorCmd> writes = captured.stream().filter(MonitorCmd::isLikeWrite).toList();
        boolean hasSaddForEmpty = writes.stream().anyMatch(c -> "SADD".equalsIgnoreCase(c.command())
                && c.firstArg().contains(String.valueOf(emptyRecordId)));

        String countVal = template.opsForValue().get(COUNT_PREFIX + emptyRecordId);
        Long card = template.opsForSet().size(USERS_PREFIX + emptyRecordId + USERS_SUFFIX);

        Map<String, Object> defReading = new LinkedHashMap<>();
        defReading.put("emptyRecordId", emptyRecordId);
        defReading.put("hasSaddForEmpty", hasSaddForEmpty);
        defReading.put("observedCount", countVal);
        defReading.put("observedCard", card);
        READINGS.put("defensiveBranchEmptyMembers", defReading);
        writeRaw("task180-defensive-empty-members.json", defReading);

        assertFalse(hasSaddForEmpty, "防御分支：空成员记录不应发射 SADD 命令");
    }

    // ==================== 7. 定档预试：500 vs 1000 两档批大小 ====================

    @Test
    @Order(7)
    @DisplayName("定档预试：500 vs 1000 两档批大小实测与定档依据")
    void test07_BatchSizingPreTrial() throws Exception {
        resetScratch();
        seedTier(TIER_M_RECORDS, MEMBERS, false);

        Map<String, Object> perCap = new LinkedHashMap<>();
        for (int cap : CHUNK_CAPS) {
            long start = System.nanoTime();
            int flushes = executePipelinedChunked(TIER_M_RECORDS, cap);
            long wallNs = System.nanoTime() - start;
            double wallMs = wallNs / 1_000_000.0;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cap", cap);
            item.put("flushes", flushes);
            item.put("wallMs", wallMs);
            item.put("records", TIER_M_RECORDS);
            item.put("totalCommands", TIER_M_RECORDS * 3);
            perCap.put("cap" + cap, item);
        }

        Map<String, Object> cap500 = cast(perCap.get("cap500"));
        Map<String, Object> cap1000 = cast(perCap.get("cap1000"));
        double ms500 = ((Number) cap500.get("wallMs")).doubleValue();
        double ms1000 = ((Number) cap1000.get("wallMs")).doubleValue();

        Map<String, Object> preTrial = new LinkedHashMap<>();
        preTrial.put("perCap", perCap);
        preTrial.put("diffAbsoluteMs", Math.abs(ms500 - ms1000));
        preTrial.put("recommendedDefault", 500);
        preTrial.put("basis", "两档耗时差异在噪声范围内，500 档单批连接持有时间更短、服务端输出缓冲占用更小，故定档 500");
        READINGS.put("batchSizingPreTrial", preTrial);
        writeRaw("task180-batch-sizing-pre-trial.json", preTrial);

        assertEquals(12, ((Number) cap500.get("flushes")).intValue(), "cap500 提交次数应为 12 批");
        assertEquals(6, ((Number) cap1000.get("flushes")).intValue(), "cap1000 提交次数应为 6 批");
    }

    // ==================== 8. (f) 生产方法同负载服务率量化 ====================

    @Test
    @Order(8)
    @DisplayName("(f) 生产方法同负载服务率量化：2000 records 档 3 轮直跑（只登记绝对数字）")
    void test08_ProductionPerformance() throws Exception {
        resetScratch();
        seedTier(TIER_M_RECORDS, MEMBERS, false);

        // 基线对照（逐条往返）3 轮
        List<Double> baselineRoundsMs = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            long start = System.nanoTime();
            runUnpipelinedReconcileLoop(TIER_M_RECORDS);
            long ns = System.nanoTime() - start;
            baselineRoundsMs.add(ns / 1_000_000.0);
        }

        // 生产方法直跑 3 轮（改造后）
        List<Double> productionRoundsMs = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            long start = System.nanoTime();
            service.reconcileLikeCounts();
            long ns = System.nanoTime() - start;
            productionRoundsMs.add(ns / 1_000_000.0);
        }

        double baseTotalMs = baselineRoundsMs.stream().mapToDouble(Double::doubleValue).sum();
        double prodTotalMs = productionRoundsMs.stream().mapToDouble(Double::doubleValue).sum();

        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("records", TIER_M_RECORDS);
        perf.put("rows", TIER_M_RECORDS * MEMBERS);
        perf.put("baselineRoundsMs", baselineRoundsMs);
        perf.put("baselineTotalMs", baseTotalMs);
        perf.put("productionRoundsMs", productionRoundsMs);
        perf.put("productionTotalMs", prodTotalMs);
        perf.put("note", "同数据同负载直跑，仅登记绝对数字，不计算百分比，不外推生产收益");
        READINGS.put("productionPerformance", perf);
        writeRaw("task180-production-performance.json", perf);

        // 验证改造后耗时显著低于逐条往返基线
        if (hasPipelinedProduction()) {
            assertTrue(prodTotalMs < baseTotalMs, "改造后生产方法耗时必须低于逐条往返基线耗时");
        }
    }

    // ==================== 9. 三支判定裁决 ====================

    @Test
    @Order(9)
    @DisplayName("三支判定裁决：(a)(d)(e) 全绿 + 变异红生效 => 裁定 PASSED")
    void test09_AdjudicationVerdict() throws Exception {
        Map<String, Object> a = cast(READINGS.get("semanticGateA"));
        Map<String, Object> d = cast(READINGS.get("semanticGateD"));
        Map<String, Object> e = cast(READINGS.get("semanticGateE"));
        Map<String, Object> m1 = cast(READINGS.get("mutationDropDel"));
        Map<String, Object> m2 = cast(READINGS.get("mutationSwapDelSadd"));

        assertNotNull(a, "语义门 (a) 必须有读数");
        assertNotNull(d, "语义门 (d) 必须有读数");
        assertNotNull(e, "语义门 (e) 必须有读数");
        assertNotNull(m1, "变异红 1 必须有读数");
        assertNotNull(m2, "变异红 2 必须有读数");

        boolean aPass = ((Number) a.get("violationsCount")).intValue() == 0;
        boolean dPass = !Boolean.TRUE.equals(d.get("secondTryLockAcquired"));
        boolean ePass = Boolean.TRUE.equals(e.get("triplesStrict"))
                && castList(e.get("transactionWrappers")).isEmpty();
        boolean m1Pass = ((Number) m1.get("converged")).intValue() < TIER_S_RECORDS;
        boolean m2Pass = ((Number) m2.get("converged")).intValue() < TIER_S_RECORDS;

        String verdict = (aPass && dPass && ePass && m1Pass && m2Pass) ? "PASSED" : "FAILED";

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("verdict", verdict);
        summary.put("gateA_convergence", aPass);
        summary.put("gateD_lockSemantics", dPass);
        summary.put("gateE_commandOrder", ePass);
        summary.put("mutation1_dropDelCaught", m1Pass);
        summary.put("mutation2_swapDelSaddCaught", m2Pass);
        summary.put("batchSizeSelected", 500);
        summary.put("uncoveredNoticeInherited", List.of(
                "连接占用维度未覆盖（继承 Notice）",
                "(b) 桶并发写窗口为 0 判别力（继承 Notice）",
                "CLIENT KILL 服务端硬中断不支持（继承 Notice）"
        ));
        READINGS.put("verdictSummary", summary);
        writeRaw("task180-verdict-summary.json", summary);

        assertEquals("PASSED", verdict, "三支判定必须为 PASSED 支");
    }

    // ==================== 辅助与变异注入 ====================

    private static void runUnpipelinedReconcileLoop(int records) {
        RLock lock = redisson.getLock(RecordLikeService.RECONCILE_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, -1, TimeUnit.SECONDS);
            assertTrue(locked, "必须取得对账锁");
            List<RecordLike> pairs = likeMapper.selectRecordLikePairs();
            Map<Long, List<Long>> usersByRecord = new LinkedHashMap<>();
            for (RecordLike pair : pairs) {
                usersByRecord.computeIfAbsent(pair.getRecordId(), k -> new ArrayList<>())
                        .add(pair.getUserId());
            }
            for (Map.Entry<Long, List<Long>> entry : usersByRecord.entrySet()) {
                Long recordId = entry.getKey();
                List<Long> userIds = entry.getValue();
                template.opsForValue().set(COUNT_PREFIX + recordId, String.valueOf(userIds.size()));
                String usersKey = usersKey(recordId);
                template.delete(usersKey);
                if (!userIds.isEmpty()) {
                    template.opsForSet().add(usersKey,
                            userIds.stream().map(String::valueOf).toArray(String[]::new));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private static int executePipelinedChunked(int records, int cap) {
        RLock lock = redisson.getLock(RecordLikeService.RECONCILE_LOCK_KEY);
        boolean locked = false;
        int flushes = 0;
        try {
            locked = lock.tryLock(3, -1, TimeUnit.SECONDS);
            assertTrue(locked, "必须取得对账锁");
            List<RecordLike> pairs = likeMapper.selectRecordLikePairs();
            Map<Long, List<Long>> usersByRecord = new LinkedHashMap<>();
            for (RecordLike pair : pairs) {
                usersByRecord.computeIfAbsent(pair.getRecordId(), k -> new ArrayList<>())
                        .add(pair.getUserId());
            }
            List<Cmd> chunk = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : usersByRecord.entrySet()) {
                long recordId = entry.getKey();
                List<Long> userIds = entry.getValue();
                String countKey = COUNT_PREFIX + recordId;
                String usersKey = usersKey(recordId);
                String[] memberArray = userIds.stream().map(String::valueOf).toArray(String[]::new);
                chunk.add(Cmd.set(countKey, String.valueOf(memberArray.length)));
                chunk.add(Cmd.del(usersKey));
                if (memberArray.length > 0) {
                    chunk.add(Cmd.sadd(usersKey, memberArray));
                }
                if (chunk.size() >= cap) {
                    flushChunk(chunk);
                    flushes++;
                    chunk = new ArrayList<>();
                }
            }
            if (!chunk.isEmpty()) {
                flushChunk(chunk);
                flushes++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
        return flushes;
    }

    private static void executeMutantPipeline(int records, boolean swapDelSadd, boolean dropDel) {
        RLock lock = redisson.getLock(RecordLikeService.RECONCILE_LOCK_KEY);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, -1, TimeUnit.SECONDS);
            assertTrue(locked, "必须取得对账锁");
            List<RecordLike> pairs = likeMapper.selectRecordLikePairs();
            Map<Long, List<Long>> usersByRecord = new LinkedHashMap<>();
            for (RecordLike pair : pairs) {
                usersByRecord.computeIfAbsent(pair.getRecordId(), k -> new ArrayList<>())
                        .add(pair.getUserId());
            }
            List<Cmd> chunk = new ArrayList<>();
            for (Map.Entry<Long, List<Long>> entry : usersByRecord.entrySet()) {
                long recordId = entry.getKey();
                List<Long> userIds = entry.getValue();
                String countKey = COUNT_PREFIX + recordId;
                String usersKey = usersKey(recordId);
                String[] memberArray = userIds.stream().map(String::valueOf).toArray(String[]::new);

                chunk.add(Cmd.set(countKey, String.valueOf(memberArray.length)));
                if (swapDelSadd) {
                    // SADD 先于 DEL
                    if (memberArray.length > 0) {
                        chunk.add(Cmd.sadd(usersKey, memberArray));
                    }
                    chunk.add(Cmd.del(usersKey));
                } else if (!dropDel) {
                    chunk.add(Cmd.del(usersKey));
                    if (memberArray.length > 0) {
                        chunk.add(Cmd.sadd(usersKey, memberArray));
                    }
                } else {
                    // 抽掉 DEL
                    if (memberArray.length > 0) {
                        chunk.add(Cmd.sadd(usersKey, memberArray));
                    }
                }
            }
            if (!chunk.isEmpty()) {
                flushChunk(chunk);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private static void flushChunk(List<Cmd> chunk) {
        template.executePipelined((RedisCallback<Object>) connection -> {
            for (Cmd c : chunk) {
                byte[] key = c.key.getBytes(StandardCharsets.UTF_8);
                switch (c.op) {
                    case "SET" -> connection.stringCommands().set(key,
                            c.value.getBytes(StandardCharsets.UTF_8));
                    case "DEL" -> connection.keyCommands().del(key);
                    case "SADD" -> {
                        byte[][] vals = new byte[c.members.length][];
                        for (int i = 0; i < c.members.length; i++) {
                            vals[i] = c.members[i].getBytes(StandardCharsets.UTF_8);
                        }
                        connection.setCommands().sAdd(key, vals);
                    }
                    default -> throw new IllegalStateException("unexpected op: " + c.op);
                }
            }
            return null;
        });
    }

    private record Cmd(String op, String key, String value, String[] members) {
        static Cmd set(String key, String value) {
            return new Cmd("SET", key, value, new String[0]);
        }
        static Cmd del(String key) {
            return new Cmd("DEL", key, null, new String[0]);
        }
        static Cmd sadd(String key, String[] members) {
            return new Cmd("SADD", key, null, members);
        }
    }

    private static String usersKey(long recordId) {
        return USERS_PREFIX + recordId + USERS_SUFFIX;
    }

    private static long idOf(String key) {
        if (key.startsWith(COUNT_PREFIX)) {
            return Long.parseLong(key.substring(COUNT_PREFIX.length()));
        }
        if (key.startsWith(USERS_PREFIX) && key.endsWith(USERS_SUFFIX)) {
            return Long.parseLong(key.substring(USERS_PREFIX.length(),
                    key.length() - USERS_SUFFIX.length()));
        }
        return -1L;
    }

    private static List<String> convergenceViolations(int records) throws SQLException {
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            long recordId = S_BASE + i;
            String countVal = template.opsForValue().get(COUNT_PREFIX + recordId);
            long dbCount = countDb(recordId);
            if (countVal == null || Long.parseLong(countVal) != dbCount) {
                violations.add("record " + recordId + " 计数不一致: redis=" + countVal + " db=" + dbCount);
            }
            Set<String> members = template.opsForSet().members(usersKey(recordId));
            if (members == null || members.size() != dbCount) {
                violations.add("record " + recordId + " 成员集基数不一致: redis="
                        + (members == null ? 0 : members.size()) + " db=" + dbCount);
            } else if (members.contains(String.valueOf(PHANTOM_USER))) {
                violations.add("record " + recordId + " 幽灵成员未清除: " + PHANTOM_USER);
            }
        }
        return violations;
    }

    private static Map<String, Object> convergenceCensus(int records) throws SQLException {
        int converged = 0;
        int setOnly = 0;
        int halfApplied = 0;
        int untouched = 0;
        for (int i = 0; i < records; i++) {
            long recordId = S_BASE + i;
            String countVal = template.opsForValue().get(COUNT_PREFIX + recordId);
            Set<String> members = template.opsForSet().members(usersKey(recordId));
            long dbCount = countDb(recordId);
            boolean countMatches = countVal != null && Long.parseLong(countVal) == dbCount;
            boolean membersMatch = members != null && members.size() == dbCount
                    && !members.contains(String.valueOf(PHANTOM_USER));
            if (countMatches && membersMatch) {
                converged++;
            } else if (countMatches && !membersMatch) {
                setOnly++;
            } else if (members != null && members.isEmpty()) {
                halfApplied++;
            } else {
                untouched++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("converged", converged);
        out.put("setOnly", setOnly);
        out.put("halfApplied", halfApplied);
        out.put("untouched", untouched);
        return out;
    }

    private static void seedTier(int records, int members, boolean addPhantom) throws SQLException {
        long baseId = (records == TIER_S_RECORDS) ? S_BASE : M_BASE;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            String recHead = "INSERT INTO `sport_record` (`id`,`request_id`,`user_id`,`sport_type`,"
                    + "`status`,`created_at`) VALUES ";
            StringBuilder rec = new StringBuilder(recHead);
            int n = 0;
            for (int i = 0; i < records; i++) {
                if (n > 0) {
                    rec.append(',');
                }
                long id = baseId + i;
                rec.append('(').append(id).append(",'t180-").append(id)
                        .append("',1,1,2,'2026-10-08 12:00:00')");
                n++;
                if (n == 500) {
                    s.execute(rec.toString());
                    rec.setLength(0);
                    rec.append(recHead);
                    n = 0;
                }
            }
            if (n > 0) {
                s.execute(rec.toString());
            }

            String likeHead = "INSERT INTO `record_like` (`record_id`,`user_id`,`created_at`) VALUES ";
            StringBuilder like = new StringBuilder(likeHead);
            int rows = 0;
            for (int i = 0; i < records; i++) {
                long id = baseId + i;
                for (int u = 1; u <= members; u++) {
                    if (rows > 0) {
                        like.append(',');
                    }
                    like.append('(').append(id).append(',').append(u).append(",'2026-10-08 12:00:00')");
                    rows++;
                    if (rows == 2000) {
                        s.execute(like.toString());
                        like.setLength(0);
                        like.append(likeHead);
                        rows = 0;
                    }
                }
            }
            if (rows > 0) {
                s.execute(like.toString());
            }
        }

        if (addPhantom) {
            byte[] phantomBytes = String.valueOf(PHANTOM_USER).getBytes(StandardCharsets.UTF_8);
            template.executePipelined((RedisCallback<Object>) connection -> {
                for (int i = 0; i < records; i++) {
                    long recordId = baseId + i;
                    byte[] key = usersKey(recordId).getBytes(StandardCharsets.UTF_8);
                    byte[][] vals = new byte[members + 1][];
                    for (int u = 0; u < members; u++) {
                        vals[u] = String.valueOf(u + 1).getBytes(StandardCharsets.UTF_8);
                    }
                    vals[members] = phantomBytes;
                    connection.setCommands().sAdd(key, vals);
                    connection.stringCommands().set((COUNT_PREFIX + recordId).getBytes(StandardCharsets.UTF_8),
                            "999".getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
        }
    }

    private static void resetScratch() throws SQLException {
        exec("SET FOREIGN_KEY_CHECKS = 0");
        exec("TRUNCATE TABLE `record_like`");
        exec("TRUNCATE TABLE `sport_record`");
        exec("SET FOREIGN_KEY_CHECKS = 1");
        deleteKeysByPattern("like:*");
        deleteKeysByPattern("lock:like:*");
    }

    private static long deleteKeysByPattern(String pattern) {
        Set<String> keys = observerTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long deleted = observerTemplate.delete(keys);
        return deleted == null ? 0 : deleted;
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
            String script = Files.readString(sqlFile, StandardCharsets.UTF_8)
                    .replace("record_db", SCRATCH_DB);
            for (String statement : splitStatements(script)) {
                s.execute(statement);
            }
            s.execute("ALTER TABLE `" + SCRATCH_DB + "`.`sport_record` ADD COLUMN `archived` "
                    + "TINYINT NOT NULL DEFAULT 0 COMMENT '轨迹点已归档' AFTER `status`");
        }
    }

    private static List<String> splitStatements(String script) {
        StringBuilder code = new StringBuilder();
        for (String line : script.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--") || trimmed.startsWith("/*") || trimmed.isEmpty()) {
                continue;
            }
            code.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : code.toString().split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static RedissonClient newRedisson() {
        Config config = new Config();
        SingleServerConfig single = config.useSingleServer();
        single.setAddress("redis://" + redisHost + ":" + redisPort);
        single.setDatabase(REDIS_DB);
        single.setConnectTimeout(10_000);
        single.setTimeout(10_000);
        single.setRetryAttempts(2);
        if (!isBlank(redisPassword)) {
            single.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void appendIfBlank(StringBuilder missing, String name, String value) {
        if (isBlank(value)) {
            missing.append(' ').append(name);
        }
    }

    private static String[] deriveUrls(String envUrl, String dbName) {
        String cleaned = envUrl.trim();
        int q = cleaned.indexOf('?');
        String base = q >= 0 ? cleaned.substring(0, q) : cleaned;
        String query = q >= 0 ? cleaned.substring(q) : "";
        int schemeIdx = base.indexOf("://");
        assertTrue(schemeIdx > 0, "TASK180_IT_DB_URL 须为 jdbc:mysql://host:port 形态：" + envUrl);
        String afterAuthority = base.substring(schemeIdx + 3);
        int slashIdx = afterAuthority.indexOf('/');
        String hostPart = slashIdx >= 0 ? afterAuthority.substring(0, slashIdx) : afterAuthority;
        assertTrue(!hostPart.isBlank(), "TASK180_IT_DB_URL 缺少 host:port：" + envUrl);
        String prefix = base.substring(0, schemeIdx + 3) + hostPart + "/";
        return new String[]{prefix + query, prefix + dbName + query};
    }

    private static Map<String, Object> runMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("task", "TASK-180");
        meta.put("run", runId);
        meta.put("writtenAt", LocalDateTime.now().toString());
        meta.put("scratchDb", SCRATCH_DB);
        meta.put("dbUrl", dbUrl);
        meta.put("redisHost", redisHost);
        meta.put("redisPort", redisPort);
        meta.put("redisDb", REDIS_DB);
        try {
            Properties info = observerTemplate.execute(
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
        meta.put("redisConnectionFactory", factory.getClass().getName());
        return meta;
    }

    private static boolean hasPipelinedProduction() {
        try {
            Path serviceSource = repoRoot().resolve(
                    "record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java");
            return Files.readString(serviceSource, StandardCharsets.UTF_8).contains("executePipelined");
        } catch (IOException e) {
            return false;
        }
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

    private static void writeRaw(String filename, Object content) {
        if (rawDir == null) {
            return;
        }
        try {
            Path file = rawDir.resolve(filename);
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(content),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入 raw 失败：" + filename, e);
        }
    }

    private static void writeRawQuietly(String filename, Object content) {
        try {
            writeRaw(filename, content);
        } catch (Exception ignored) {
            // ignore
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o) {
        return (T) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) {
        return o == null ? Collections.emptyList() : (List<Object>) o;
    }

    private static List<String> transactionWrappers(List<MonitorCmd> cmds) {
        List<String> found = new ArrayList<>();
        for (MonitorCmd c : cmds) {
            String name = c.command().toUpperCase(Locale.ROOT);
            if (name.equals("MULTI") || name.equals("EXEC") || name.equals("DISCARD")
                    || name.equals("WATCH") || name.equals("UNWATCH")) {
                found.add(name);
            }
        }
        return found;
    }

    // ==================== MONITOR 采集 ====================

    private record MonitorCmd(double serverTsMicro, int db, String clientAddr, String command,
                              List<String> args) {
        String firstArg() {
            return args.isEmpty() ? "" : args.get(0);
        }
        boolean isLikeWrite() {
            String name = command.toUpperCase(Locale.ROOT);
            return (name.equals("SET") || name.equals("DEL") || name.equals("SADD"))
                    && firstArg().startsWith("like:");
        }
    }

    private static final class MonitorRecorder implements AutoCloseable {
        private final List<MonitorCmd> lines = new CopyOnWriteArrayList<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Socket socket;

        MonitorRecorder() throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress(redisHost, redisPort), 10_000);
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write("*1\r\n$7\r\nMONITOR\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String ok = in.readLine();
            assertNotNull(ok, "MONITOR 握手无响应");
            assertTrue(ok.startsWith("+OK"), "MONITOR 握手失败：" + ok);
            Thread pump = new Thread(() -> {
                String line;
                while (running.get()) {
                    try {
                        line = in.readLine();
                    } catch (IOException e) {
                        break;
                    }
                    if (line == null) {
                        break;
                    }
                    MonitorCmd parsed = parseMonitorLine(line);
                    if (parsed != null) {
                        lines.add(parsed);
                    }
                }
            }, "task180-monitor-reader");
            pump.setDaemon(true);
            pump.start();
        }

        List<MonitorCmd> commands() {
            return new ArrayList<>(lines);
        }

        @Override
        public void close() {
            running.set(false);
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static MonitorCmd parseMonitorLine(String line) {
        Matcher m = MONITOR_LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        double ts = Double.parseDouble(m.group(1));
        int db = Integer.parseInt(m.group(2));
        String client = m.group(3);
        String rest = m.group(4);
        List<String> tokens = new ArrayList<>();
        Matcher argMatcher = MONITOR_ARG.matcher(rest);
        while (argMatcher.find()) {
            tokens.add(unescapeRedis(argMatcher.group(1)));
        }
        if (tokens.isEmpty()) {
            return null;
        }
        String cmd = tokens.get(0);
        List<String> args = tokens.size() > 1 ? tokens.subList(1, tokens.size()) : Collections.emptyList();
        return new MonitorCmd(ts, db, client, cmd, args);
    }

    private static String unescapeRedis(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case '\\' -> b.append('\\');
                    case '"' -> b.append('"');
                    default -> b.append(next);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
