package com.sportverify.record.service;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-179 判别 IT：点赞对账 pipeline 化的语义边界（课题 5 第二轮）。
 *
 * <p>被测面冻结：基线取生产 {@code RecordLikeService.reconcileLikeCounts()}（{@code src/main} 零改动），
 * 候选是本类内的 test-only 切片 {@link PipelineReconcileCandidate}——同装配、同分组、同三元命令
 * {@code SET count / DEL users / SADD members}，仅把逐条往返改为 {@code executePipelined} 按批提交。</p>
 *
 * <p>观测三件（同一套探针复用于基线与候选，维度不减）：① 连接内逐命令计时（客户端侧 DEL→SADD 间隔）；
 * ② 独立只读连接的关键时点主动探测 + 轮询（外部可见态与空窗命中）；③ 服务端 {@code MONITOR} 命令流
 * （服务端逐命令时间戳＝空窗分布与命令序比对的权威凭据）。{@code MONITOR} 会给服务端每条命令追加一次
 * 写出、拖慢逐命令处理，故只用于小档语义判别；服务率对比档不启用。</p>
 *
 * <p>缺任一变即 assume 跳过（不视为通过）：{@code TASK179_IT_DB_URL} / {@code TASK179_IT_DB_USER} /
 * {@code TASK179_IT_DB_PASSWORD} / {@code TASK179_IT_REDIS_HOST} / {@code TASK179_IT_REDIS_PORT}；
 * 可选 {@code TASK179_IT_REDIS_PASSWORD}、{@code TASK179_IT_RUN}（raw 子目录名，缺省 run-judge）。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecordLikeReconcilePipelineJudgeIT {

    private static final String SCRATCH_DB = "task179_it";
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
    private static final int SAMPLED_PROBES = 8;
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
    private static InstrumentedTemplate template;
    private static StringRedisTemplate observerTemplate;
    private static Recorder recorder;
    private static RecordLikeMapper likeMapper;
    private static RecordLikeService service;
    private static RecordLikeService injectorService;
    private static PipelineReconcileCandidate candidate;
    private static Path rawDir;
    private static String runId;

    private static final Map<String, Object> READINGS = new LinkedHashMap<>();
    private static final List<Map<String, Object>> RATE_ROUNDS = new ArrayList<>();

    @BeforeAll
    static void setUpAll() throws Exception {
        String envUrl = System.getenv("TASK179_IT_DB_URL");
        dbUser = System.getenv("TASK179_IT_DB_USER");
        dbPassword = System.getenv("TASK179_IT_DB_PASSWORD");
        redisHost = System.getenv("TASK179_IT_REDIS_HOST");
        String envPort = System.getenv("TASK179_IT_REDIS_PORT");
        redisPassword = System.getenv("TASK179_IT_REDIS_PASSWORD");
        runId = System.getenv().getOrDefault("TASK179_IT_RUN", "run-judge");

        StringBuilder missing = new StringBuilder();
        appendIfBlank(missing, "TASK179_IT_DB_URL", envUrl);
        appendIfBlank(missing, "TASK179_IT_DB_USER", dbUser);
        appendIfBlank(missing, "TASK179_IT_DB_PASSWORD", dbPassword);
        appendIfBlank(missing, "TASK179_IT_REDIS_HOST", redisHost);
        appendIfBlank(missing, "TASK179_IT_REDIS_PORT", envPort);
        Assumptions.assumeTrue(missing.length() == 0,
                "缺环境变量：" + missing + "，跳过对账 pipeline 语义判别 IT（不视为通过）");
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
        hikari.setPoolName("task179-judge-it");
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
        recorder = new Recorder();
        template = new InstrumentedTemplate(factory, recorder);
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
        SportRecordMapper sportRecordMapper = sessionTemplate.getMapper(SportRecordMapper.class);
        likeMapper = sessionTemplate.getMapper(RecordLikeMapper.class);
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        service = new RecordLikeService(sportRecordMapper, likeMapper, template, redisson,
                JSON, txManager, new SimpleMeterRegistry());
        injectorService = new RecordLikeService(sportRecordMapper, likeMapper, observerTemplate,
                observerRedisson, JSON, new DataSourceTransactionManager(dataSource),
                new SimpleMeterRegistry());
        candidate = new PipelineReconcileCandidate();

        rawDir = repoRoot().resolve("docs/perf/data/raw/task179").resolve(runId);
        Files.createDirectories(rawDir);
        writeRaw("task179-run-meta.json", runMeta());
    }

    @AfterAll
    static void tearDownAll() {
        if (rawDir == null) {
            // 环境门未过（@BeforeAll 已 assume 跳过）：不落读数、不做清理，保持「跳过」而非「报错」
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", "TASK-179");
        out.put("run", runId);
        out.put("finishedAt", LocalDateTime.now().toString());
        out.put("readings", READINGS);
        out.put("rateRounds", RATE_ROUNDS);
        writeRawQuietly("task179-it-summary.json", out);
        try {
            deleteKeysByPattern("like:*");
            deleteKeysByPattern("lock:like:*");
        } catch (Exception ignored) {
            // 清理尽力而为，不影响结论
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

    // ==================== J1：基线逐 record 语义快照 ====================

    @Test
    @Order(1)
    @DisplayName("J1 基线快照：逐命令计时 + 关键时点探测 + 服务端命令流 + 轮询空窗命中")
    void j1BaselineSnapshot() throws Exception {
        Map<String, Object> reading = runSnapshot("baseline-S", true, 0, TIER_S_RECORDS);
        READINGS.put("j1BaselineS", reading);
        writeRaw("task179-j1-baseline-tierS.json", reading);
        assertAllChecks("J1-baseline-S", reading);
    }

    @Test
    @Order(2)
    @DisplayName("J2 候选快照（分批 500/1000）：同一探针、同一观测维度")
    void j2CandidateSnapshots() throws Exception {
        Map<String, Object> perCap = new LinkedHashMap<>();
        for (int cap : CHUNK_CAPS) {
            Map<String, Object> reading = runSnapshot("candidate-cap" + cap, false, cap,
                    TIER_S_RECORDS);
            perCap.put("cap" + cap, reading);
            writeRaw("task179-j2-candidate-cap" + cap + ".json", reading);
            assertAllChecks("J2-candidate-cap" + cap, reading);
        }
        READINGS.put("j2CandidateS", perCap);

        Map<String, Object> base = cast(READINGS.get("j1BaselineS"));
        for (int cap : CHUNK_CAPS) {
            Map<String, Object> cand = cast(perCap.get("cap" + cap));
            double baseWindow = windowSeconds(base);
            double candWindow = windowSeconds(cand);
            assertTrue(candWindow <= baseWindow,
                    "候选服务端 DEL→SADD 空窗总时长必须不大于基线（否则语义劣化）：cap=" + cap
                            + " 基线=" + baseWindow + "ms 候选=" + candWindow + "ms");
        }
    }

    /** 同一套探针跑一遍基线或候选，产出可直接对照的读数包。 */
    private Map<String, Object> runSnapshot(String label, boolean baselineShape, int cap, int records)
            throws Exception {
        seedTier(records, true);
        List<Long> sampleIds = sampleIds(records);
        WindowPoller poller = new WindowPoller(sampleIds);
        MonitorRecorder monitor = new MonitorRecorder();
        recorder.reset();

        List<Map<String, Object>> probes = Collections.synchronizedList(new ArrayList<>());
        if (baselineShape) {
            template.setAfterHook((type, key) -> {
                if ("delete".equals(type) && sampleIds.contains(idOf(key))) {
                    probes.add(criticalPointProbe(key));
                }
            });
        } else {
            candidate.setQueuePointProbe(recordId -> {
                if (sampleIds.contains(recordId)) {
                    probes.add(criticalPointProbe(usersKey(recordId)));
                }
            });
        }

        poller.start();
        long wallStart = System.nanoTime();
        if (baselineShape) {
            service.reconcileLikeCounts();
        } else {
            candidate.reconcile((long) records * MEMBERS, cap);
        }
        double wallMs = ms(System.nanoTime() - wallStart);
        poller.stopAndJoin();
        template.setAfterHook(null);
        candidate.setQueuePointProbe(null);
        Thread.sleep(150);
        List<MonitorCmd> monitorCmds = monitor.stopAndCollect();

        Map<String, Object> reading = assemble(label, records, wallMs, probes, poller.summary(),
                monitorCmds, baselineShape);
        reading.put("shape", baselineShape ? "baseline" : "candidate");
        if (!baselineShape) {
            reading.put("chunkCommandCap", cap);
            reading.put("pipelineFlushCount", candidate.flushCount());
        }
        return reading;
    }

    private Map<String, Object> assemble(String label, int records, double wallMs,
                                         List<Map<String, Object>> probes,
                                         Map<String, Object> pollerSummary,
                                         List<MonitorCmd> monitorCmds, boolean baselineShape) {
        List<Event> events = recorder.snapshot();
        long setCount = 0;
        long deleteCount = 0;
        long saddCount = 0;
        long saddMembers = 0;
        List<Double> clientGapsMs = new ArrayList<>();
        Map<String, Long> delEnd = new LinkedHashMap<>();
        List<Long> recordOrder = new ArrayList<>();
        for (Event e : events) {
            switch (e.type()) {
                case "value.set" -> setCount++;
                case "delete" -> {
                    deleteCount++;
                    delEnd.put(String.valueOf(e.key()), e.endNanos());
                }
                case "set.add" -> {
                    saddCount++;
                    saddMembers += ((Number) e.payload()).longValue();
                    Long end = delEnd.remove(String.valueOf(e.key()));
                    if (end != null) {
                        clientGapsMs.add((e.startNanos() - end) / 1_000_000.0);
                    }
                    recordOrder.add(idOf(String.valueOf(e.key())));
                }
                default -> {
                }
            }
        }

        List<MonitorCmd> writes = new ArrayList<>();
        for (MonitorCmd c : monitorCmds) {
            if (c.isLikeWrite()) {
                writes.add(c);
            }
        }
        List<Double> serverGapsMs = new ArrayList<>();
        Map<String, Double> serverGapSamples = new LinkedHashMap<>();
        Map<String, Double> lastDelTs = new LinkedHashMap<>();
        List<String> stream = new ArrayList<>();
        for (MonitorCmd c : writes) {
            stream.add(c.command() + " " + c.firstArg());
            if ("DEL".equalsIgnoreCase(c.command())) {
                lastDelTs.put(c.firstArg(), c.serverTsMicro());
            } else if ("SADD".equalsIgnoreCase(c.command())) {
                Double delTs = lastDelTs.remove(c.firstArg());
                if (delTs != null) {
                    double gapMs = (c.serverTsMicro() - delTs) / 1000.0;
                    serverGapsMs.add(gapMs);
                    if (serverGapSamples.size() < 12) {
                        serverGapSamples.put(c.firstArg(), round6(gapMs));
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        out.put("records", records);
        out.put("rows", (long) records * MEMBERS);
        out.put("reconcileWallMs", wallMs);
        out.put("setCount", setCount);
        out.put("deleteCount", deleteCount);
        out.put("saddCount", saddCount);
        out.put("saddMembersTotal", saddMembers);
        out.put("clientSideDelToSaddGapMs", distributionMs(
                baselineShape ? clientGapsMs : candidate.lastQueueToQueueGapMs()));
        out.put("clientGapSemantics", baselineShape
                ? "基线＝DEL 往返返回与 SADD 往返发出之间的客户端间隔"
                : "候选无逐命令往返，本栏改记批次内 DEL 排队与 SADD 排队之间的间隔；服务端空窗以 MONITOR 时间戳为准");
        out.put("serverSideDelToSaddGapMs", distributionMs(serverGapsMs));
        out.put("serverGapSamplesMs", serverGapSamples);
        out.put("criticalPointProbes", probes);
        out.put("windowPoller", pollerSummary);
        out.put("monitorWriteCommandCount", writes.size());
        out.put("monitorCommandStream", stream);
        out.put("monitorTransactionWrapperCommands", transactionWrappers(monitorCmds));
        out.put("monitorDistinctClients", new ArrayList<>(new TreeSet<>(clients(monitorCmds))));
        out.put("recordProcessingOrder", recordOrder);

        List<String> violations = convergenceViolations(records);
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("setDeleteSaddCountsMatchRecords",
                setCount == records && deleteCount == records && saddCount == records);
        checks.put("saddMembersMatchRows", saddMembers == (long) records * MEMBERS);
        checks.put("serverDelToSaddGapSamplesMatchRecords", serverGapsMs.size() == records);
        checks.put("criticalPointProbesEqualSampledCount", probes.size() == SAMPLED_PROBES);
        checks.put("finalConvergenceEqualsDbAuthority", violations.isEmpty());
        out.put("checks", checks);
        out.put("finalConvergenceViolations", violations);
        out.put("convergenceCensus", convergenceCensus(records, true));
        return out;
    }

    private static double windowSeconds(Map<String, Object> reading) {
        return ((Number) cast(reading.get("serverSideDelToSaddGapMs")).get("sumMs")).doubleValue();
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

    private static Set<String> clients(List<MonitorCmd> cmds) {
        Set<String> out = new LinkedHashSet<>();
        for (MonitorCmd c : cmds) {
            out.add(c.clientAddr());
        }
        return out;
    }

    /** (a) 逐 record 权威比对：Redis 计数与成员集都等于 DB 权威才算收敛；返回前若干违反项。 */
    private static List<String> convergenceViolations(int records) {
        List<String> violations = new ArrayList<>();
        String expected = String.valueOf(MEMBERS);
        for (int i = 0; i < records; i++) {
            long recordId = baseOf(records) + i;
            String count = observerTemplate.opsForValue().get(COUNT_PREFIX + recordId);
            Long card = observerTemplate.opsForSet().size(usersKey(recordId));
            if (!expected.equals(count) || card == null || card != MEMBERS) {
                if (violations.size() < 12) {
                    violations.add(recordId + ":count=" + count + ",card=" + card);
                }
            }
        }
        return violations;
    }

    // ==================== (b) 并发 like 注入 ====================

    @Test
    @Order(3)
    @DisplayName("(b) 并发 like：基线三确定时点 + 候选两时点 + 批量并发丢失率对照")
    void concurrentLikeInjection() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baselineDeterministicPoints", baselineDeterministicInjections());
        out.put("candidateDeterministicPoints", candidateDeterministicInjections());
        Map<String, Object> baseLoss = statisticalLossRate("baseline", 0);
        out.put("baselineLossRate", baseLoss);
        for (int cap : CHUNK_CAPS) {
            out.put("candidateLossRateCap" + cap, statisticalLossRate("candidate" + cap, cap));
        }
        READINGS.put("j1j2ConcurrentInjection", out);
        writeRaw("task179-j1j2-concurrent-injection.json", out);

        assertEquals(10, postCompletionSurvivors(baseLoss),
                "基线判别式前提：对账完成后的注入必须全部存活；读数=" + resultText(baseLoss));
        for (int cap : CHUNK_CAPS) {
            Map<String, Object> cand = cast(out.get("candidateLossRateCap" + cap));
            assertEquals(10, postCompletionSurvivors(cand),
                    "(b) 候选不得新增丢失面：对账完成后的注入必须全部存活；读数=" + resultText(cand));
            assertEquals(classValue(baseLoss, "after-record-completed", "lost"),
                    classValue(cand, "after-record-completed", "lost"),
                    "(b) 「该 record 已处理完」类别下候选丢失数不得多于基线；读数=" + resultText(cand));
            assertTrue(classValue(cand, "inside-del-to-sadd-window", "lost")
                            <= classValue(baseLoss, "inside-del-to-sadd-window", "lost"),
                    "(b) 窗口内丢失数候选不得多于基线；读数=" + resultText(cand));
            assertEquals(Boolean.TRUE, cand.get("pendingRetainsAllInjectedOps"),
                    "(b) 候选不得丢弃 pending 队列中的注入操作；读数=" + resultText(cand));
            assertEquals(Boolean.TRUE, baseLoss.get("pendingRetainsAllInjectedOps"),
                    "基线 pending 读数异常（判别式前提不成立）：" + resultText(baseLoss));
        }
    }

    private List<Map<String, Object>> baselineDeterministicInjections() throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        String[] points = {"before-set", "inside-del-to-sadd-window", "after-sadd"};
        for (String point : points) {
            seedTier(TIER_S_RECORDS, false);
            long target = S_BASE + 7;
            long user = INJECT_USER_FLOOR(point);
            AtomicBoolean fired = new AtomicBoolean(false);
            Map<String, Object> during = new LinkedHashMap<>();
            if (point.equals("before-set")) {
                template.setBeforeHook((type, key) -> {
                    if ("value.set".equals(type) && (COUNT_PREFIX + target).equals(key)
                            && fired.compareAndSet(false, true)) {
                        during.put("stateSeenBeforeInjection", snapshotRecord(target));
                        during.put("likeCountReturned", injectorService.like(target, user).getLikeCount());
                    }
                });
            } else if (point.endsWith("window")) {
                template.setAfterHook((type, key) -> {
                    if ("delete".equals(type) && usersKey(target).equals(key)
                            && fired.compareAndSet(false, true)) {
                        during.put("stateSeenBeforeInjection", snapshotRecord(target));
                        during.put("likeCountReturned", injectorService.like(target, user).getLikeCount());
                    }
                });
            } else {
                template.setAfterHook((type, key) -> {
                    if ("set.add".equals(type) && usersKey(target).equals(key)
                            && fired.compareAndSet(false, true)) {
                        during.put("stateSeenBeforeInjection", snapshotRecord(target));
                        during.put("likeCountReturned", injectorService.like(target, user).getLikeCount());
                    }
                });
            }
            recorder.reset();
            service.reconcileLikeCounts();
            clearHooks();
            cases.add(injectionCase(point, target, user, during));
        }
        return cases;
    }

    private List<Map<String, Object>> candidateDeterministicInjections() throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        long target = S_BASE + 7;

        seedTier(TIER_S_RECORDS, false);
        long userA = INJECT_USER_FLOOR("candidate-queue-point");
        Map<String, Object> duringA = new LinkedHashMap<>();
        candidate.setQueuePointProbe(recordId -> {
            if (recordId == target) {
                duringA.put("stateSeenBeforeInjection", snapshotRecord(target));
                duringA.put("likeCountReturned", injectorService.like(target, userA).getLikeCount());
            }
        });
        recorder.reset();
        candidate.reconcile((long) TIER_S_RECORDS * MEMBERS, CHUNK_CAPS[0]);
        candidate.setQueuePointProbe(null);
        cases.add(injectionCase("candidate-inside-queue-between-del-and-sadd", target, userA, duringA));

        seedTier(TIER_S_RECORDS, false);
        long userB = INJECT_USER_FLOOR("candidate-post-commit");
        AtomicBoolean fired = new AtomicBoolean(false);
        Map<String, Object> duringB = new LinkedHashMap<>();
        candidate.setPostCommitProbe(recordId -> {
            if (recordId == target && fired.compareAndSet(false, true)) {
                duringB.put("stateSeenBeforeInjection", snapshotRecord(target));
                duringB.put("likeCountReturned", injectorService.like(target, userB).getLikeCount());
            }
        });
        recorder.reset();
        candidate.reconcile((long) TIER_S_RECORDS * MEMBERS, CHUNK_CAPS[0]);
        candidate.setPostCommitProbe(null);
        cases.add(injectionCase("candidate-after-chunk-commit", target, userB, duringB));
        return cases;
    }

    private Map<String, Object> injectionCase(String point, long target, long user,
                                              Map<String, Object> during) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("point", point);
        out.put("targetRecord", target);
        out.put("injectedUser", user);
        out.putAll(during);
        out.put("afterReconcile", snapshotRecord(target));
        out.put("injectedMemberSurvived", Boolean.TRUE.equals(
                observerTemplate.opsForSet().isMember(usersKey(target), String.valueOf(user))));
        out.put("dbRowsForTarget", countDb(target));
        out.put("pendingQueueLength", llen());
        Thread.sleep(30);
        return out;
    }

    private static long INJECT_USER_FLOOR(String tag) {
        return 900_000L + Math.abs(tag.hashCode() % 50_000);
    }

    /**
     * 并发注入的时序分类：把「注入早于该 record 被对账触碰」「落在 DEL→SADD 窗口内」「该 record 已
     * 处理完」三类分开统计。只有后两类可跨形态比较——第一类无论形态如何都会被 DB 权威重建覆盖，
     * 这是既有的最终一致语义，不是 pipeline 引入的新丢失面。
     */
    private Map<String, Object> statisticalLossRate(String shape, int cap) throws Exception {
        seedTier(TIER_S_RECORDS, false);
        ConcurrentLinkedQueue<long[]> accepted = new ConcurrentLinkedQueue<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong sequence = new AtomicLong();
        Thread injector = new Thread(() -> {
            while (!stop.get()) {
                long i = sequence.getAndIncrement();
                long recordId = S_BASE + (i % TIER_S_RECORDS);
                long user = 950_000L + i;
                long startNanos = System.nanoTime();
                try {
                    injectorService.like(recordId, user);
                    accepted.add(new long[]{recordId, user, startNanos, System.nanoTime()});
                } catch (Exception ignored) {
                    // 注入线程自身失败不参与分类统计
                }
            }
        }, "task179-injector-" + shape);
        injector.setDaemon(true);

        recorder.reset();
        injector.start();
        Thread.sleep(50);
        long wallStart = System.nanoTime();
        if (cap == 0) {
            service.reconcileLikeCounts();
        } else {
            candidate.reconcile((long) TIER_S_RECORDS * MEMBERS, cap);
        }
        double wallMs = ms(System.nanoTime() - wallStart);
        stop.set(true);
        injector.join(10_000);
        clearHooks();

        Map<Long, long[]> boundaries = recordBoundaries();
        Map<String, long[]> byClass = new LinkedHashMap<>();
        for (String cls : List.of("before-record-touched", "inside-del-to-sadd-window",
                "after-record-completed")) {
            byClass.put(cls, new long[]{0, 0});
        }
        for (long[] inj : accepted) {
            long[] bounds = boundaries.get(inj[0]);
            String cls = classify(inj[2], bounds);
            long[] tally = byClass.get(cls);
            tally[0]++;
            Boolean member = observerTemplate.opsForSet().isMember(usersKey(inj[0]),
                    String.valueOf(inj[1]));
            if (!Boolean.TRUE.equals(member)) {
                tally[1]++;
            }
        }
        Map<String, Object> classes = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : byClass.entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("attempts", e.getValue()[0]);
            one.put("lost", e.getValue()[1]);
            one.put("lossRate", e.getValue()[0] == 0 ? null
                    : round6((double) e.getValue()[1] / e.getValue()[0]));
            classes.put(e.getKey(), one);
        }
        long pending = llen();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shape", shape);
        out.put("acceptedInjecties", accepted.size());
        out.put("byTimingClass", classes);
        out.put("reconcileWallMs", wallMs);
        out.put("pendingQueueLength", pending);
        out.put("pendingRetainsAllInjectedOps", pending == accepted.size());
        out.put("dbRowsUnchanged", countRows() == (long) TIER_S_RECORDS * MEMBERS);
        out.put("countDriftRecordsSampled", countDriftSample());
        out.put("postCompletionSurvivors", postCompletionInjections(shape));
        return out;
    }

    /** 每 record 的 DEL 完成与 SADD 完成时刻（候选形态下两者同属一个批次时间窗）。 */
    private Map<Long, long[]> recordBoundaries() {
        Map<Long, long[]> boundaries = new LinkedHashMap<>();
        for (Event e : recorder.snapshot()) {
            long recordId = idOf(String.valueOf(e.key()));
            if (recordId < 0) {
                continue;
            }
            long[] bounds = boundaries.computeIfAbsent(recordId, k -> new long[]{-1, -1});
            if ("delete".equals(e.type())) {
                bounds[0] = e.endNanos();
            } else if ("set.add".equals(e.type())) {
                bounds[1] = e.endNanos();
            }
        }
        return boundaries;
    }

    private static String classify(long injectionStartNanos, long[] bounds) {
        if (bounds == null || bounds[0] < 0) {
            return "before-record-touched";
        }
        if (injectionStartNanos < bounds[0]) {
            return "before-record-touched";
        }
        if (injectionStartNanos < bounds[1]) {
            return "inside-del-to-sadd-window";
        }
        return "after-record-completed";
    }

    /** 对账完成后再注入 10 次：这类写入在两形态下都必须存活，是「不得新增丢失面」的硬判据。 */
    private int postCompletionInjections(String shape) {
        int survivors = 0;
        for (int i = 0; i < 10; i++) {
            long recordId = S_BASE + i;
            long user = 980_000L + shape.hashCode() % 1000 + i;
            injectorService.like(recordId, user);
            Boolean member = observerTemplate.opsForSet().isMember(usersKey(recordId),
                    String.valueOf(user));
            if (Boolean.TRUE.equals(member)) {
                survivors++;
            }
        }
        return survivors;
    }

    /** 计数漂移抽样：注入后 Redis 计数与 DB 权威不等的记录数（对账覆盖窗口的直接后果）。 */
    private List<String> countDriftSample() {
        List<String> drift = new ArrayList<>();
        for (int i = 0; i < TIER_S_RECORDS && drift.size() < 12; i++) {
            long recordId = S_BASE + i;
            String count = observerTemplate.opsForValue().get(COUNT_PREFIX + recordId);
            Long card = observerTemplate.opsForSet().size(usersKey(recordId));
            long expected = MEMBERS;
            if (card != null && card != expected) {
                drift.add(recordId + ":count=" + count + ",card=" + card + ",db=" + expected);
            }
        }
        return drift;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> timingClass(Map<String, Object> reading, String cls) {
        return cast(cast(reading.get("byTimingClass")).get(cls));
    }

    private static long classValue(Map<String, Object> reading, String cls, String field) {
        Object v = timingClass(reading, cls).get(field);
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static int postCompletionSurvivors(Map<String, Object> reading) {
        return ((Number) reading.get("postCompletionSurvivors")).intValue();
    }

    private static Map<String, Object> snapshotRecord(long recordId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", observerTemplate.opsForValue().get(COUNT_PREFIX + recordId));
        out.put("card", observerTemplate.opsForSet().size(usersKey(recordId)));
        return out;
    }

    // ==================== (d) 锁语义 ====================

    @Test
    @Order(4)
    @DisplayName("(d) 锁语义：第二把 tryLock 被拒 + 全部写在锁内 + 锁外可见中间态")
    void lockSemantics() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseline", lockTrial(true));
        out.put("candidate", lockTrial(false));
        READINGS.put("j1j2LockSemantics", out);
        writeRaw("task179-j1j2-lock.json", out);

        Map<String, Object> base = cast(out.get("baseline"));
        Map<String, Object> cand = cast(out.get("candidate"));
        assertEquals(Boolean.FALSE, base.get("secondTryLockAcquired"),
                "基线持锁期间第二把 tryLock 必须被拒：" + resultText(base));
        assertEquals(Boolean.FALSE, cand.get("secondTryLockAcquired"),
                "(d) 候选必须同样受对账锁保护：" + resultText(cand));
        assertEquals(Boolean.TRUE, base.get("allWritesObservedInsideLock"), "基线写在锁内异常");
        assertEquals(Boolean.TRUE, cand.get("allWritesObservedInsideLock"),
                "(d) 候选必须在锁内完成全部写：" + resultText(cand));
    }

    private Map<String, Object> lockTrial(boolean baselineShape) throws Exception {
        seedTier(TIER_S_RECORDS, true);
        recorder.reset();
        AtomicBoolean writeSeenOutsideLock = new AtomicBoolean(false);
        AtomicBoolean paused = new AtomicBoolean(false);
        CountDownLatch hold = new CountDownLatch(1);
        Runnable pause = () -> {
            if (paused.compareAndSet(false, true)) {
                if (!Boolean.TRUE.equals(observerTemplate.hasKey(RecordLikeService.RECONCILE_LOCK_KEY))) {
                    writeSeenOutsideLock.set(true);
                }
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        if (baselineShape) {
            template.setAfterHook((type, key) -> {
                if ("delete".equals(type)) {
                    pause.run();
                }
            });
        } else {
            candidate.setPostCommitProbe(recordId -> {
                if (recordId >= 0) {
                    pause.run();
                }
            });
        }
        Thread worker = new Thread(() -> {
            if (baselineShape) {
                service.reconcileLikeCounts();
            } else {
                candidate.reconcile((long) TIER_S_RECORDS * MEMBERS, CHUNK_CAPS[0]);
            }
        }, "task179-locked-reconcile");
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(300);

        long tryStart = System.nanoTime();
        boolean acquired = false;
        RLock second = observerRedisson.getLock(RecordLikeService.RECONCILE_LOCK_KEY);
        try {
            acquired = second.tryLock(3, -1, TimeUnit.SECONDS);
        } finally {
            if (acquired) {
                second.unlock();
            }
        }
        double secondTryLockElapsedMs = ms(System.nanoTime() - tryStart);
        hold.countDown();
        worker.join(60_000);
        clearHooks();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shape", baselineShape ? "baseline" : "candidate-cap" + CHUNK_CAPS[0]);
        out.put("secondTryLockAcquired", acquired);
        out.put("secondTryLockElapsedMs", secondTryLockElapsedMs);
        out.put("productionLockWaitSeconds", 3);
        out.put("allWritesObservedInsideLock", !writeSeenOutsideLock.get());
        out.put("workerCompleted", !worker.isAlive());
        return out;
    }

    // ==================== (c) 失败模式 ====================

    @Test
    @Order(5)
    @DisplayName("(c) 失败模式：中断点后的残留形态与下轮收敛")
    void failureModes() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baselineAbortAfterThreeRecords", abortRun(true, 3, "after-records"));
        out.put("candidateAbortAfterFirstChunk", abortRun(false, 1, "between-chunks"));
        out.put("candidateAbortWhileQueueingBatch", abortRun(false, 1, "inside-batch"));
        out.put("serverSideMidBatchKill", probeMidBatchKillCapability());
        READINGS.put("j2FailureModes", out);
        writeRaw("task179-j2-failure-modes.json", out);

        Map<String, Object> base = cast(out.get("baselineAbortAfterThreeRecords"));
        assertEquals(Boolean.TRUE, base.get("nextRoundConverges"), "基线残留须由下轮收敛");
        for (String key : List.of("candidateAbortAfterFirstChunk", "candidateAbortWhileQueueingBatch")) {
            Map<String, Object> cand = cast(out.get(key));
            assertEquals(Boolean.TRUE, cand.get("nextRoundConverges"),
                    "(c) 候选残留必须能由下轮对账收敛，不得产生基线没有的不可恢复态：" + resultText(cand));
            assertEquals(Boolean.TRUE, cand.get("errorSeenByCallerWasInjectedFailure"),
                    "(c) 中断未按预期抛出则本轮判别式为虚跑：" + resultText(cand));
        }
    }

    private Map<String, Object> abortRun(boolean baselineShape, int stopAfter, String mode)
            throws Exception {
        seedTier(TIER_S_RECORDS, true);
        AtomicInteger steps = new AtomicInteger();
        AtomicBoolean armed = new AtomicBoolean(true);
        if (mode.equals("after-records")) {
            template.setAfterHook((type, key) -> {
                if ("delete".equals(type) && armed.get() && steps.incrementAndGet() == stopAfter) {
                    armed.set(false);
                    throw new IllegalStateException("task179 injected failure after " + stopAfter + " records");
                }
            });
        } else if (mode.equals("between-chunks")) {
            candidate.setAbortAfterFlushes(stopAfter);
        } else if (mode.equals("inside-batch")) {
            candidate.setAbortInsideQueueing(stopAfter);
        }
        recorder.reset();
        String error = null;
        Class<?> errorType = null;
        try {
            if (baselineShape) {
                service.reconcileLikeCounts();
            } else {
                candidate.reconcile((long) TIER_S_RECORDS * MEMBERS, CHUNK_CAPS[0]);
            }
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            errorType = e.getClass();
        }
        clearHooks();

        Map<String, Object> residue = convergenceCensus(TIER_S_RECORDS, true);
        boolean lockReleasedAfterAbort = !observerRedisson
                .getLock(RecordLikeService.RECONCILE_LOCK_KEY).isLocked();
        service.reconcileLikeCounts();
        Map<String, Object> afterNext = convergenceCensus(TIER_S_RECORDS, true);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shape", baselineShape ? "baseline-per-record" : "candidate-per-chunk");
        out.put("mode", mode);
        out.put("errorSeenByCaller", error);
        out.put("errorSeenByCallerWasInjectedFailure",
                errorType != null && IllegalStateException.class.equals(rootCauseType(errorType)));
        out.put("stepsBeforeThrow", steps.get());
        out.put("residue", residue);
        out.put("lockReleasedAfterAbort", lockReleasedAfterAbort);
        out.put("afterNextRound", afterNext);
        out.put("nextRoundConverges",
                ((Number) afterNext.get("converged")).intValue() == TIER_S_RECORDS
                        && ((Number) afterNext.get("halfApplied")).intValue() == 0);
        return out;
    }

    private static Class<?> rootCauseType(Class<?> type) {
        return type;
    }

    /** 服务端批内中断（已写入但未全部应用）能否注入：探测 CLIENT KILL ID 的可得性，不可得则登记未覆盖。 */
    private Map<String, Object> probeMidBatchKillCapability() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attempt", "CLIENT SETNAME 后从独立连接 CLIENT KILL ID 命中候选连接");
        try {
            String clientId = template.execute((RedisCallback<String>) connection -> {
                Object raw = connection.execute("CLIENT", "ID".getBytes(StandardCharsets.UTF_8));
                return raw == null ? null : new String((byte[]) raw, StandardCharsets.UTF_8);
            });
            String listed = template.execute((RedisCallback<String>) connection -> {
                Object raw = connection.execute("CLIENT", "LIST".getBytes(StandardCharsets.UTF_8));
                return raw == null ? null : new String((byte[]) raw, StandardCharsets.UTF_8);
            });
            boolean sameConnection = clientId != null && listed != null
                    && listed.contains("id=" + clientId);
            out.put("clientIdReadable", clientId != null);
            out.put("samePooledConnectionAcrossCalls", sameConnection);
            out.put("covered", false);
            out.put("notCoveredReason", sameConnection
                    ? "可得客户端 ID，但批次写出与 KILL 的先后无法在客户端侧确定 ⇒ 本任务登记未覆盖"
                    : "Redisson 连接池跨调用不复用同一物理连接，无法定位候选批次所在连接 ⇒ 登记未覆盖");
        } catch (Exception e) {
            out.put("clientIdReadable", false);
            out.put("covered", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    /** 收敛普查：converged / setOnly（计数已改成员集未重建）/ halfApplied（DEL 已执行 SADD 未到）/ untouched。 */
    private static Map<String, Object> convergenceCensus(int records, boolean phantomSeeded) {
        int converged = 0;
        int setOnly = 0;
        int halfApplied = 0;
        int untouched = 0;
        int other = 0;
        String authority = String.valueOf(MEMBERS);
        for (int i = 0; i < records; i++) {
            long recordId = baseOf(records) + i;
            String count = observerTemplate.opsForValue().get(COUNT_PREFIX + recordId);
            Long card = observerTemplate.opsForSet().size(usersKey(recordId));
            boolean countOk = authority.equals(count);
            boolean cardOk = card != null && card == MEMBERS;
            if (countOk && cardOk) {
                converged++;
            } else if (countOk && card != null && card == 0L) {
                halfApplied++;
            } else if (countOk) {
                setOnly++;
            } else if (!countOk && phantomSeeded && card != null && card == MEMBERS + 1L) {
                untouched++;
            } else {
                other++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("converged", converged);
        out.put("setOnly", setOnly);
        out.put("halfApplied", halfApplied);
        out.put("untouched", untouched);
        out.put("other", other);
        return out;
    }

    // ==================== (e) 命令序可重放 ====================

    @Test
    @Order(6)
    @DisplayName("(e) 命令序：同数据同会话内基线与候选的服务端命令序逐条比对")
    void commandOrderReplay() throws Exception {
        seedTier(TIER_S_RECORDS, true);
        Map<String, Object> out = new LinkedHashMap<>();
        for (int cap : CHUNK_CAPS) {
            MonitorRecorder baseMonitor = new MonitorRecorder();
            recorder.reset();
            service.reconcileLikeCounts();
            Thread.sleep(120);
            List<MonitorCmd> baseCmds = baseMonitor.stopAndCollect();
            List<String> baseStream = likeWriteStream(baseCmds);
            List<Long> baseOrder = recordOrderFrom(recorder.snapshot());

            MonitorRecorder candMonitor = new MonitorRecorder();
            recorder.reset();
            candidate.reconcile((long) TIER_S_RECORDS * MEMBERS, cap);
            Thread.sleep(120);
            List<MonitorCmd> candCmds = candMonitor.stopAndCollect();
            List<String> candStream = likeWriteStream(candCmds);
            List<Long> candOrder = recordOrderFrom(recorder.snapshot());

            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("chunkCommandCap", cap);
            diff.put("baselineStreamSize", baseStream.size());
            diff.put("candidateStreamSize", candStream.size());
            diff.put("identical", baseStream.equals(candStream));
            diff.put("firstDifferenceIndex", firstDifference(baseStream, candStream));
            diff.put("groupingDifference", groupingDifference(baseCmds, candCmds, cap));
            diff.put("recordOrderIdentical", baseOrder.equals(candOrder));
            diff.put("candidateTransactionWrappers", transactionWrappers(candCmds));
            diff.put("baselineTransactionWrappers", transactionWrappers(baseCmds));
            diff.put("candidateCommandsPerFlush", candidateCommandsPerFlush(cap));
            out.put("cap" + cap, diff);
        }
        READINGS.put("j2CommandOrder", out);
        writeRaw("task179-j2-command-order.json", out);

        for (int cap : CHUNK_CAPS) {
            Map<String, Object> diff = cast(out.get("cap" + cap));
            assertEquals(Boolean.TRUE, diff.get("recordOrderIdentical"),
                    "分组顺序必须相同才能比对命令序：" + resultText(diff));
            assertEquals(Boolean.TRUE, diff.get("identical"),
                    "(e) cap=" + cap + " 服务端命令序必须与基线逐条相同：" + resultText(diff));
            assertEquals(List.of(), diff.get("candidateTransactionWrappers"),
                    "(e) 候选不得引入 MULTI/EXEC 等基线没有的包装命令");
            assertEquals(diff.get("baselineStreamSize"), diff.get("candidateStreamSize"),
                    "(e) 两方案命令总量必须相同");
        }
    }

    /** 提交分组差异：相邻两条 like 写命令之间的服务端间隔（基线＝逐条往返，候选＝批内紧贴）。 */
    private static Map<String, Object> groupingDifference(List<MonitorCmd> baseCmds,
                                                          List<MonitorCmd> candCmds, int cap) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baselineInterCommandGapMs", interCommandGaps(baseCmds));
        out.put("candidateInterCommandGapMs", interCommandGaps(candCmds));
        out.put("candidateFlushes", candidate.flushCount());
        out.put("candidateCommandsPerFlush", candidate.commandsPerFlush(cap));
        return out;
    }

    private static Map<String, Object> interCommandGaps(List<MonitorCmd> cmds) {
        List<Double> gaps = new ArrayList<>();
        Double previous = null;
        int writes = 0;
        for (MonitorCmd c : cmds) {
            if (!c.isLikeWrite()) {
                continue;
            }
            writes++;
            if (previous != null) {
                gaps.add((c.serverTsMicro() - previous) / 1000.0);
            }
            previous = c.serverTsMicro();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("writeCommands", writes);
        out.put("gapDistributionMs", distributionMs(gaps));
        return out;
    }

    private Map<String, Object> candidateCommandsPerFlush(int cap) {
        return candidate.commandsPerFlush(cap);
    }

    private static List<String> likeWriteStream(List<MonitorCmd> cmds) {
        List<String> stream = new ArrayList<>();
        for (MonitorCmd c : cmds) {
            if (c.isLikeWrite()) {
                stream.add(c.command() + " " + c.firstArg());
            }
        }
        return stream;
    }

    private static List<Long> recordOrderFrom(List<Event> events) {
        List<Long> order = new ArrayList<>();
        for (Event e : events) {
            if ("delete".equals(e.type())) {
                order.add(idOf(String.valueOf(e.key())));
            }
        }
        return order;
    }

    private static int firstDifference(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return i;
            }
        }
        return a.size() == b.size() ? -1 : n;
    }

    // ==================== (f) 服务率量化 ====================

    @Test
    @Order(7)
    @DisplayName("(f) 服务率：2000 records 档，基线与候选交替各 3 轮（只登记绝对数字）")
    void serviceRateTierM() throws Exception {
        seedTier(TIER_M_RECORDS, false);
        for (int rep = 1; rep <= 3; rep++) {
            for (String shape : List.of("baseline", "cap500", "cap1000")) {
                RATE_ROUNDS.add(rateRound(shape, rep));
            }
            writeRawQuietly("task179-j2f-rate-rep" + rep + ".json",
                    Map.of("rep", rep, "rounds", new ArrayList<>(RATE_ROUNDS)));
        }
        Map<String, Object> summary = rateSummary();
        READINGS.put("j2fServiceRate", summary);
        writeRaw("task179-j2f-service-rate.json", summary);

        for (int cap : CHUNK_CAPS) {
            Map<String, Object> one = cast(summary.get("cap" + cap));
            assertEquals(one.get("baselineCommands"), one.get("candidateCommands"),
                    "(f) 两方案命令总量必须相同（仅提交方式不同）");
            for (String key : List.of("baselineTotalMs", "candidateTotalMs", "baselineCommands",
                    "candidateCommands", "candidateFlushes", "candidateRoundTrips")) {
                assertNotNull(one.get(key), "(f) 缺读数项：" + key);
            }
        }
    }

    private Map<String, Object> rateRound(String shape, int rep) {
        recorder.reset();
        long wallStart = System.nanoTime();
        long commands;
        long roundTrips;
        if (shape.equals("baseline")) {
            service.reconcileLikeCounts();
            commands = countLikeWriteEvents();
            roundTrips = commands;
        } else {
            int cap = shape.equals("cap500") ? 500 : 1_000;
            candidate.reconcile((long) TIER_M_RECORDS * MEMBERS, cap);
            commands = candidate.commandsIssued();
            roundTrips = candidate.flushCount();
        }
        double wallMs = ms(System.nanoTime() - wallStart);
        Map<String, Object> census = convergenceCensus(TIER_M_RECORDS, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shape", shape);
        out.put("rep", rep);
        out.put("wallMs", wallMs);
        out.put("commands", commands);
        out.put("roundTrips", roundTrips);
        out.put("converged", census.get("converged"));
        return out;
    }

    private Map<String, Object> rateSummary() {
        Map<String, List<Double>> byShape = new LinkedHashMap<>();
        Map<String, Long> commandsByShape = new LinkedHashMap<>();
        for (Map<String, Object> r : RATE_ROUNDS) {
            String shape = String.valueOf(r.get("shape"));
            byShape.computeIfAbsent(shape, k -> new ArrayList<>())
                    .add(((Number) r.get("wallMs")).doubleValue());
            commandsByShape.put(shape, ((Number) r.get("commands")).longValue());
        }
        List<Double> base = byShape.get("baseline");
        double baselineTotal = base.stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", TIER_M_RECORDS);
        out.put("rows", (long) TIER_M_RECORDS * MEMBERS);
        out.put("roundsPerShape", base.size());
        out.put("measurementOrder", "baseline,cap500,cap1000 交替重复 3 轮");
        out.put("baselineRoundsMs", base);
        out.put("baselineTotalMs", round3(baselineTotal));
        out.put("baselineCommandsPerRound", commandsByShape.get("baseline"));
        for (int cap : CHUNK_CAPS) {
            String shape = "cap" + cap;
            List<Double> cand = byShape.get(shape);
            double candTotal = cand.stream().mapToDouble(Double::doubleValue).sum();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("candidateRoundsMs", cand);
            one.put("candidateTotalMs", round3(candTotal));
            one.put("baselineTotalMs", round3(baselineTotal));
            one.put("baselineCommands", commandsByShape.get("baseline"));
            one.put("candidateCommands", commandsByShape.get(shape));
            one.put("candidateFlushes", candidateFlushes(cap));
            one.put("candidateRoundTrips", candidateFlushes(cap));
            one.put("commandsPerFlushCap", cap);
            out.put("cap" + cap, one);
        }
        return out;
    }

    private long candidateFlushes(int cap) {
        long total = 0;
        for (Map<String, Object> r : RATE_ROUNDS) {
            if (r.get("shape").equals("cap" + cap)) {
                total += ((Number) r.get("roundTrips")).longValue();
            }
        }
        return total;
    }

    private long countLikeWriteEvents() {
        long n = 0;
        for (Event e : recorder.snapshot()) {
            if (e.type().equals("value.set") || e.type().equals("delete") || e.type().equals("set.add")) {
                n++;
            }
        }
        return n;
    }

    // ==================== 变异红：证明探针具判别力 ====================

    @Test
    @Order(8)
    @DisplayName("变异红：抽掉 DEL、DEL/SADD 次序颠倒必须被 (a) 收敛判据捕获")
    void mutationRed() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();

        seedTier(TIER_S_RECORDS, true);
        recorder.reset();
        service.reconcileLikeCounts();
        out.put("controlBaseline", convergenceCensus(TIER_S_RECORDS, true));

        seedTier(TIER_S_RECORDS, true);
        recorder.reset();
        candidate.setMutation(Mutation.DROP_DEL);
        candidate.reconcile((long) TIER_S_RECORDS * MEMBERS, CHUNK_CAPS[0]);
        candidate.clearMutation();
        Map<String, Object> dropDel = convergenceCensus(TIER_S_RECORDS, true);
        dropDel.put("deleteCommandCount", countEvent("delete"));
        dropDel.put("saddCommandCount", countEvent("set.add"));
        out.put("mutantDropDel", dropDel);

        seedTier(TIER_S_RECORDS, true);
        recorder.reset();
        candidate.setMutation(Mutation.SWAP_DEL_SADD);
        candidate.reconcile((long) TIER_S_RECORDS * MEMBERS, CHUNK_CAPS[0]);
        candidate.clearMutation();
        Map<String, Object> swapped = convergenceCensus(TIER_S_RECORDS, true);
        swapped.put("tripleOrderPreserved", triplesInQueueOrderPreserved());
        out.put("mutantSwapDelSadd", swapped);

        READINGS.put("mutationRed", out);
        writeRaw("task179-mutation-red.json", out);

        Map<String, Object> control = cast(out.get("controlBaseline"));
        assertEquals(TIER_S_RECORDS, ((Number) control.get("converged")).intValue(),
                "对照轮必须全收敛，否则判别式前提失真：" + resultText(control));
        Map<String, Object> m1 = cast(out.get("mutantDropDel"));
        assertTrue(((Number) m1.get("converged")).intValue() < TIER_S_RECORDS,
                "变异 1（抽掉 DEL）未被 (a) 捕获 ⇒ 探针无判别力：" + resultText(m1));
        Map<String, Object> m2 = cast(out.get("mutantSwapDelSadd"));
        assertTrue(((Number) m2.get("converged")).intValue() < TIER_S_RECORDS,
                "变异 2（DEL/SADD 次序颠倒）未被 (a) 捕获 ⇒ 探针无判别力：" + resultText(m2));
        assertEquals(Boolean.FALSE, m2.get("tripleOrderPreserved"),
                "变异 2 必须体现为三元次序破坏");
    }

    private long countEvent(String type) {
        long n = 0;
        for (Event e : recorder.snapshot()) {
            if (type.equals(e.type())) {
                n++;
            }
        }
        return n;
    }

    /** 队列内每条 record 的三元次序是否仍为 SET→DEL→SADD（变异 2 应为 false）。 */
    private boolean triplesInQueueOrderPreserved() {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (Event e : recorder.snapshot()) {
            if (e.type().equals("value.set") || e.type().equals("delete") || e.type().equals("set.add")) {
                byKey.computeIfAbsent(String.valueOf(e.key()), k -> new ArrayList<>())
                        .add(e.type());
            }
        }
        for (List<String> seq : byKey.values()) {
            if (!seq.equals(List.of("value.set", "delete", "set.add"))) {
                return false;
            }
        }
        return !byKey.isEmpty();
    }

    // ==================== J3：六判据逐项裁决与三支判定 ====================

    @Test
    @Order(9)
    @DisplayName("J3 六判据 (a)-(f) 逐项裁决 + 三支归属")
    void j3Verdict() throws Exception {
        Map<String, Object> base = cast(READINGS.get("j1BaselineS"));
        Map<String, Object> cand = cast(cast(READINGS.get("j2CandidateS")).get("cap500"));
        Map<String, Object> cand1000 = cast(cast(READINGS.get("j2CandidateS")).get("cap1000"));
        Map<String, Object> injection = cast(READINGS.get("j1j2ConcurrentInjection"));
        Map<String, Object> lock = cast(READINGS.get("j1j2LockSemantics"));
        Map<String, Object> failure = cast(READINGS.get("j2FailureModes"));
        Map<String, Object> order = cast(READINGS.get("j2CommandOrder"));
        Map<String, Object> rate = cast(READINGS.get("j2fServiceRate"));
        Map<String, Object> mutation = cast(READINGS.get("mutationRed"));

        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("a_finalConvergence", judge("a",
                violationsEmpty(base), violationsEmpty(cand) && violationsEmpty(cand1000),
                "两方案对账后逐 record 计数与成员集均等于 DB 权威（含幽灵成员前置态）"));

        Map<String, Object> baseLoss = cast(injection.get("baselineLossRate"));
        Map<String, Object> candLoss500 = cast(injection.get("candidateLossRateCap500"));
        Map<String, Object> candLoss1000 = cast(injection.get("candidateLossRateCap1000"));
        boolean bOk = postCompletionSurvivors(candLoss500) == 10
                && postCompletionSurvivors(candLoss1000) == 10
                && postCompletionSurvivors(baseLoss) == 10
                && classValue(candLoss500, "after-record-completed", "lost")
                        == classValue(baseLoss, "after-record-completed", "lost")
                && classValue(candLoss1000, "after-record-completed", "lost")
                        == classValue(baseLoss, "after-record-completed", "lost")
                && classValue(candLoss500, "inside-del-to-sadd-window", "lost")
                        <= classValue(baseLoss, "inside-del-to-sadd-window", "lost")
                && Boolean.TRUE.equals(candLoss500.get("pendingRetainsAllInjectedOps"))
                && Boolean.TRUE.equals(candLoss1000.get("pendingRetainsAllInjectedOps"));
        verdict.put("b_concurrentWriteBehaviour", judge("b",
                classValue(baseLoss, "after-record-completed", "attempts") > 0, bOk,
                "按注入时序分类对照：完成态后的注入在两形态下全部存活、窗口内丢失不增、pending 不被丢弃；"
                        + "基线读数=" + resultText(baseLoss) + " 候选500=" + resultText(candLoss500)
                        + " 候选1000=" + resultText(candLoss1000)));

        Map<String, Object> baseFail = cast(failure.get("baselineAbortAfterThreeRecords"));
        Map<String, Object> candFail = cast(failure.get("candidateAbortAfterFirstChunk"));
        Map<String, Object> candFail2 = cast(failure.get("candidateAbortWhileQueueingBatch"));
        verdict.put("c_failureMode", judge("c",
                Boolean.TRUE.equals(baseFail.get("nextRoundConverges")),
                Boolean.TRUE.equals(candFail.get("nextRoundConverges"))
                        && Boolean.TRUE.equals(candFail2.get("nextRoundConverges")),
                "中断后残留态均可由下轮对账收敛；未覆盖项见 serverSideMidBatchKill="
                        + resultText(cast(failure.get("serverSideMidBatchKill")))));

        Map<String, Object> baseLock = cast(lock.get("baseline"));
        Map<String, Object> candLock = cast(lock.get("candidate"));
        Map<String, Object> midState = midStateComparison(base, cand);
        boolean dOk = Boolean.TRUE.equals(candLock.get("allWritesObservedInsideLock"))
                && Boolean.FALSE.equals(candLock.get("secondTryLockAcquired"))
                && Boolean.TRUE.equals(midState.get("candidateOk"));
        verdict.put("d_lockSemantics", judge("d",
                Boolean.TRUE.equals(baseLock.get("allWritesObservedInsideLock")), dOk,
                "候选全部写仍在同一对账锁内、第二把 tryLock 同样被拒、锁外可见中间态不多于基线"));
        verdict.put("d_midStateDetail", midState);

        boolean eOk = allStreamsIdentical(order) && noWrappers(order);
        verdict.put("e_commandOrder", judge("e", true, eOk,
                "候选服务端命令序与基线逐条相同，仅提交分组不同（读数和间隔见 j2CommandOrder）"));

        verdict.put("f_serviceRate", rate);
        verdict.put("mutationRedDetection", Map.of(
                "controlConverged", ((Number) cast(mutation.get("controlBaseline")).get("converged")).intValue(),
                "mutantDropDelConverged", ((Number) cast(mutation.get("mutantDropDel")).get("converged")).intValue(),
                "mutantSwapConverged", ((Number) cast(mutation.get("mutantSwapDelSadd")).get("converged")).intValue(),
                "records", TIER_S_RECORDS));

        boolean degraded = !candidateOk(verdict, "a_finalConvergence")
                || !candidateOk(verdict, "b_concurrentWriteBehaviour")
                || !candidateOk(verdict, "c_failureMode")
                || !candidateOk(verdict, "d_lockSemantics")
                || !candidateOk(verdict, "e_commandOrder");
        verdict.put("branch", degraded ? "NO-GO" : "GO");
        verdict.put("branchRule", "任一 (a)-(e) 劣化 ⇒ NO-GO；全过 ⇒ GO 且不改生产（另立提案）");
        verdict.put("j1Attribution", "J1 基线快照无论 J3 结果如何均为有效产出（A1.3 由代码级登记升级为实测）");
        verdict.put("j2Attribution", degraded ? "候选对照显示语义劣化项，见逐项 basis" : "候选对照未发现 (a)-(e) 劣化");
        verdict.put("j3Attribution", verdict.get("branch"));
        verdict.put("scopeCaveat", "本机、隔离环境、未达外部门槛；服务率只登记绝对数字，不写百分比、不外推生产收益");
        READINGS.put("j3Verdict", verdict);
        writeRaw("task179-j3-verdict.json", verdict);

        for (String key : List.of("a_finalConvergence", "b_concurrentWriteBehaviour", "c_failureMode",
                "d_lockSemantics", "e_commandOrder")) {
            Map<String, Object> one = cast(verdict.get(key));
            assertNotNull(one.get("baselineObservation"), key + " 缺基线观测");
            assertEquals(Boolean.TRUE, one.get("adjudicated"), key + " 未完成裁决");
        }
        assertTrue(List.of("GO", "NO-GO").contains(verdict.get("branch")),
                "三支归属必须为 GO/NO-GO（环境可达 ⇒ 无 UNDETERMINED）：" + resultText(verdict));
    }

    private static boolean candidateOk(Map<String, Object> verdict, String key) {
        return Boolean.TRUE.equals(cast(verdict.get(key)).get("candidateOk"));
    }

    private static boolean violationsEmpty(Map<String, Object> snapshot) {
        return castList(snapshot.get("finalConvergenceViolations")).isEmpty();
    }

    /** (d) 的后半：关键时点外部可见中间态形态对比（基线空窗 vs 候选未提交旧态）。 */
    private static Map<String, Object> midStateComparison(Map<String, Object> base,
                                                          Map<String, Object> cand) {
        int baseEmpty = countProbeCards(base, 0);
        int candEmpty = countProbeCards(cand, 0);
        int candOld = countProbeCards(cand, MEMBERS + 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baselineEmptyWindowProbes", baseEmpty);
        out.put("candidateEmptyWindowProbes", candEmpty);
        out.put("candidateOldStateProbes", candOld);
        out.put("baselinePollerEmptyObservations", pollerEmptyCount(base));
        out.put("candidatePollerEmptyObservations", pollerEmptyCount(cand));
        out.put("candidateOk", candEmpty <= baseEmpty);
        out.put("note", "候选关键时点探测发生在批次提交之前，外部读到的是旧成员集仍在（未提交态），"
                + "而非基线的空窗；两者是不同形态的中间态，均登记");
        return out;
    }

    private static int countProbeCards(Map<String, Object> snapshot, int expected) {
        int n = 0;
        for (Object o : castList(snapshot.get("criticalPointProbes"))) {
            Object card = cast(o).get("observedCard");
            if (card != null && ((Number) card).intValue() == expected) {
                n++;
            }
        }
        return n;
    }

    private static Object pollerEmptyCount(Map<String, Object> snapshot) {
        return cast(snapshot.get("windowPoller")).get("emptyWindowObservations");
    }

    private static boolean allStreamsIdentical(Map<String, Object> order) {
        Map<String, Object> perCap = cast(order);
        for (int cap : CHUNK_CAPS) {
            Map<String, Object> diff = cast(perCap.get("cap" + cap));
            if (!Boolean.TRUE.equals(diff.get("identical"))
                    || !Boolean.TRUE.equals(diff.get("recordOrderIdentical"))) {
                return false;
            }
        }
        return true;
    }

    private static boolean noWrappers(Map<String, Object> order) {
        for (int cap : CHUNK_CAPS) {
            if (!castList(cast(cast(order.get("cap" + cap))).get("candidateTransactionWrappers")).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> judge(String letter, boolean baselineObserved,
                                             boolean candidateOk, String basis) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("criterion", letter);
        out.put("baselineObservation", baselineObserved);
        out.put("candidateOk", candidateOk);
        out.put("basis", basis);
        out.put("adjudicated", true);
        return out;
    }

    // ==================== pipeline 候选（test-only 切片） ====================

    private enum Mutation { DROP_DEL, SWAP_DEL_SADD }

    /** 与生产同分组、同三元命令，仅把逐条往返改为按批 executePipelined 提交。 */
    private static final class PipelineReconcileCandidate {

        private final AtomicInteger flushes = new AtomicInteger();
        private final AtomicLong commands = new AtomicLong();
        private final List<Integer> commandsPerFlush = new ArrayList<>();
        private final List<Double> lastQueueGaps = new ArrayList<>();
        private volatile Mutation mutation;
        private volatile java.util.function.LongConsumer queuePointProbe;
        private volatile java.util.function.LongConsumer postCommitProbe;
        private volatile int abortAfterFlushes = -1;
        private volatile int abortInsideQueueing = -1;

        void setMutation(Mutation value) {
            mutation = value;
        }

        void clearMutation() {
            mutation = null;
        }

        void setQueuePointProbe(java.util.function.LongConsumer probe) {
            queuePointProbe = probe;
            if (probe != null) {
                postCommitProbe = null;
            }
        }

        void setPostCommitProbe(java.util.function.LongConsumer probe) {
            postCommitProbe = probe;
            if (probe != null) {
                queuePointProbe = null;
            }
        }

        void setAbortAfterFlushes(int afterFlushes) {
            abortAfterFlushes = afterFlushes;
        }

        void setAbortInsideQueueing(int afterCommands) {
            abortInsideQueueing = afterCommands;
        }

        void clearAbortInjection() {
            abortAfterFlushes = -1;
            abortInsideQueueing = -1;
        }

        int flushCount() {
            return flushes.get();
        }

        long commandsIssued() {
            return commands.get();
        }

        List<Double> lastQueueToQueueGapMs() {
            return new ArrayList<>(lastQueueGaps);
        }

        Map<String, Object> commandsPerFlush(int cap) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cap", cap);
            out.put("flushes", flushes.get());
            out.put("min", commandsPerFlush.stream().mapToInt(Integer::intValue).min().orElse(-1));
            out.put("max", commandsPerFlush.stream().mapToInt(Integer::intValue).max().orElse(-1));
            out.put("total", commands.get());
            return out;
        }

        void reconcile(long expectedRows, int commandCap) {
            flushes.set(0);
            commands.set(0);
            commandsPerFlush.clear();
            lastQueueGaps.clear();
            RLock lock = redisson.getLock(RecordLikeService.RECONCILE_LOCK_KEY);
            boolean locked = false;
            try {
                locked = lock.tryLock(3, -1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertTrue(locked, "候选必须取得与基线同一把对账锁");
            try {
                List<RecordLike> pairs = likeMapper.selectRecordLikePairs();
                assertEquals(expectedRows, pairs.size(), "候选载入行数须与基线一致");
                Map<Long, List<Long>> usersByRecord = new LinkedHashMap<>();
                for (RecordLike pair : pairs) {
                    usersByRecord.computeIfAbsent(pair.getRecordId(), k -> new ArrayList<>())
                            .add(pair.getUserId());
                }
                List<Cmd> chunk = new ArrayList<>();
                for (Map.Entry<Long, List<Long>> entry : usersByRecord.entrySet()) {
                    long recordId = entry.getKey();
                    String countKey = COUNT_PREFIX + recordId;
                    String usersKey = usersKey(recordId);
                    String[] memberArray = entry.getValue().stream().map(String::valueOf)
                            .toArray(String[]::new);
                    chunk.add(Cmd.set(countKey, String.valueOf(memberArray.length)));
                    if (mutation == Mutation.SWAP_DEL_SADD) {
                        // 变异 2：同一 record 内 DEL 落到 SADD 之后，重建的成员集随即被抹掉
                        chunk.add(Cmd.sadd(usersKey, memberArray, recordId));
                        chunk.add(Cmd.del(usersKey, recordId));
                    } else {
                        if (mutation != Mutation.DROP_DEL) {
                            chunk.add(Cmd.del(usersKey, recordId));
                        }
                        chunk.add(Cmd.sadd(usersKey, memberArray, recordId));
                    }
                    if (chunk.size() > commandCap) {
                        flush(chunk);
                        chunk = new ArrayList<>();
                    }
                }
                if (!chunk.isEmpty()) {
                    flush(chunk);
                }
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }

        private void flush(List<Cmd> chunk) {
            long start = System.nanoTime();
            AtomicInteger queued = new AtomicInteger();
            long[] queueStamps = new long[chunk.size()];
            List<Object> results = template.executePipelined((RedisCallback<Object>) connection -> {
                int index = 0;
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
                            connection.sAdd(key, vals);
                        }
                        default -> throw new IllegalStateException("unexpected op " + c.op);
                    }
                    queueStamps[index++] = System.nanoTime();
                    if (c.op.equals("DEL") && queuePointProbe != null) {
                        queuePointProbe.accept(c.recordId);
                    }
                    if (abortInsideQueueing > 0 && queued.incrementAndGet() == abortInsideQueueing) {
                        throw new IllegalStateException("task179 injected failure while queueing batch ("
                                + abortInsideQueueing + " commands queued, batch not committed)");
                    }
                }
                return null;
            });
            long end = System.nanoTime();
            assertNotNull(results, "pipeline 结果引用不应为 null");
            int sets = 0;
            int dels = 0;
            int sadds = 0;
            for (Cmd c : chunk) {
                switch (c.op) {
                    case "SET" -> sets++;
                    case "DEL" -> dels++;
                    case "SADD" -> sadds++;
                    default -> {
                    }
                }
            }
            recorder.note("chunk.commit", start, end, chunk.get(chunk.size() - 1).key, chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                Cmd c = chunk.get(i);
                if ("DEL".equals(c.op) && i + 1 < chunk.size() && "SADD".equals(chunk.get(i + 1).op)) {
                    lastQueueGaps.add((queueStamps[i + 1] - queueStamps[i]) / 1_000_000.0);
                }
            }
            for (Cmd c : chunk) {
                switch (c.op) {
                    case "SET" -> recorder.note("value.set", start, end, c.key, 1);
                    case "DEL" -> recorder.note("delete", start, end, c.key, 1);
                    case "SADD" -> recorder.note("set.add", start, end, c.key, c.members.length);
                    default -> throw new IllegalStateException("unexpected op " + c.op);
                }
            }
            flushes.incrementAndGet();
            commands.addAndGet(chunk.size());
            commandsPerFlush.add(chunk.size());
            if (abortAfterFlushes > 0 && flushes.get() == abortAfterFlushes) {
                throw new IllegalStateException("task179 injected failure between batches after "
                        + abortAfterFlushes + " committed batches");
            }
            java.util.function.LongConsumer post = postCommitProbe;
            if (post != null) {
                for (Cmd c : chunk) {
                    if ("SADD".equals(c.op)) {
                        post.accept(c.recordId);
                    }
                }
            }
        }
    }

    private static final class Cmd {

        private final String op;
        private final String key;
        private final String value;
        private final String[] members;
        private final long recordId;

        private Cmd(String op, String key, String value, String[] members, long recordId) {
            this.op = op;
            this.key = key;
            this.value = value;
            this.members = members;
            this.recordId = recordId;
        }

        static Cmd set(String key, String value) {
            return new Cmd("SET", key, value, new String[0], idOf(key));
        }

        static Cmd del(String key, long recordId) {
            return new Cmd("DEL", key, null, new String[0], recordId);
        }

        static Cmd sadd(String key, String[] members, long recordId) {
            return new Cmd("SADD", key, null, members, recordId);
        }
    }

    // ==================== 观测：独立连接探测 / 轮询 / MONITOR ====================

    private static Map<String, Object> criticalPointProbe(String usersKey) {
        Map<String, Object> probe = new LinkedHashMap<>();
        long recordId = idOf(usersKey);
        probe.put("recordId", recordId);
        probe.put("probedAtNanos", System.nanoTime());
        probe.put("observedCard", observerTemplate.opsForSet().size(usersKey));
        probe.put("observedExists", observerTemplate.hasKey(usersKey));
        probe.put("observedCount", observerTemplate.opsForValue().get(COUNT_PREFIX + recordId));
        return probe;
    }

    /** 独立只读连接轮询采样：命中「DB 有行但成员集为空」即一次空窗观测；轮询周期即检测误差上界。 */
    private static final class WindowPoller {

        private final List<Long> sampleIds;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final ConcurrentLinkedQueue<long[]> observations = new ConcurrentLinkedQueue<>();
        private final AtomicInteger cycles = new AtomicInteger();
        private final AtomicInteger emptyObservations = new AtomicInteger();
        private final AtomicInteger oldObservations = new AtomicInteger();
        private volatile Thread worker;
        private volatile long cycleMs;

        WindowPoller(List<Long> sampleIds) {
            this.sampleIds = sampleIds;
        }

        void start() {
            running.set(true);
            worker = new Thread(() -> {
                long cycleStart = System.nanoTime();
                while (running.get()) {
                    for (long recordId : sampleIds) {
                        Long card = observerTemplate.opsForSet().size(usersKey(recordId));
                        long at = System.nanoTime();
                        observations.add(new long[]{recordId, card == null ? -1 : card, at});
                        if (card != null && card == 0L) {
                            emptyObservations.incrementAndGet();
                        } else if (card != null && card == MEMBERS + 1L) {
                            oldObservations.incrementAndGet();
                        }
                    }
                    cycles.incrementAndGet();
                    cycleMs = Math.round((System.nanoTime() - cycleStart) / 1_000_000.0);
                    cycleStart = System.nanoTime();
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "task179-window-poller");
            worker.setDaemon(true);
            worker.start();
        }

        void stopAndJoin() throws InterruptedException {
            running.set(false);
            if (worker != null) {
                worker.join(10_000);
            }
        }

        Map<String, Object> summary() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sampledRecords", sampleIds.size());
            out.put("cycles", cycles.get());
            out.put("lastCycleMs", cycleMs);
            out.put("observations", observations.size());
            out.put("emptyWindowObservations", emptyObservations.get());
            out.put("oldStateObservations", oldObservations.get());
            Map<Long, Integer> emptyPerRecord = new LinkedHashMap<>();
            for (long[] o : observations) {
                if (o[1] == 0L) {
                    emptyPerRecord.merge(o[0], 1, Integer::sum);
                }
            }
            out.put("emptyHitsByRecord", emptyPerRecord);
            out.put("caveat", "轮询为采样观测：未命中不等于窗口不存在；周期与误差见 lastCycleMs 与 cycles");
            return out;
        }
    }

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
            }, "task179-monitor-pump");
            pump.setDaemon(true);
            pump.start();
        }

        List<MonitorCmd> stopAndCollect() {
            running.set(false);
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关闭尽力而为
            }
            return new ArrayList<>(lines);
        }

        @Override
        public void close() {
            stopAndCollect();
        }
    }

    private static MonitorCmd parseMonitorLine(String line) {
        Matcher m = MONITOR_LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        double ts = Double.parseDouble(m.group(1)) * 1_000_000.0;
        int db = Integer.parseInt(m.group(2));
        String addr = m.group(3);
        List<String> args = new ArrayList<>();
        Matcher am = MONITOR_ARG.matcher(m.group(4));
        String command = "";
        int index = 0;
        while (am.find()) {
            if (index == 0) {
                command = am.group(1);
            } else {
                args.add(am.group(1));
            }
            index++;
        }
        return new MonitorCmd(ts, db, addr, command, args);
    }

    // ==================== 仪表化模板与记录器 ====================

    private interface EventHook {
        void onEvent(String type, String key);
    }

    private static final class Recorder {

        private final List<Event> events = new ArrayList<>();

        synchronized void note(String type, long startNanos, long endNanos, Object key, Object payload) {
            events.add(new Event(type, startNanos, endNanos, key, payload));
        }

        synchronized void reset() {
            events.clear();
        }

        synchronized List<Event> snapshot() {
            return new ArrayList<>(events);
        }
    }

    private record Event(String type, long startNanos, long endNanos, Object key, Object payload) {
    }

    private static final class InstrumentedTemplate extends StringRedisTemplate {

        private final Recorder recorder;
        private volatile EventHook beforeHook;
        private volatile EventHook afterHook;

        InstrumentedTemplate(RedisConnectionFactory connectionFactory, Recorder recorder) {
            super(connectionFactory);
            this.recorder = recorder;
        }

        void setBeforeHook(EventHook hook) {
            beforeHook = hook;
        }

        void setAfterHook(EventHook hook) {
            afterHook = hook;
        }

        void clearHooks() {
            beforeHook = null;
            afterHook = null;
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
            EventHook before = beforeHook;
            if (before != null) {
                before.onEvent("delete", key);
            }
            long start = System.nanoTime();
            Boolean result = super.delete(key);
            long end = System.nanoTime();
            recorder.note("delete", start, end, key, 1);
            EventHook after = afterHook;
            if (after != null) {
                after.onEvent("delete", key);
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private <T> T timed(Class<T> iface, T target) {
            return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return method.invoke(target, args);
                        }
                        String name = method.getName();
                        String key = args != null && args.length > 0 && args[0] instanceof String k ? k : "";
                        boolean tracked = (name.equals("set") || name.equals("add"))
                                && key.startsWith("like:");
                        EventHook before = beforeHook;
                        if (tracked && before != null) {
                            before.onEvent(name.equals("set") ? "value.set" : "set.add", key);
                        }
                        long start = System.nanoTime();
                        Object result;
                        try {
                            result = method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        long end = System.nanoTime();
                        if (tracked) {
                            if (name.equals("set")) {
                                recorder.note("value.set", start, end, key, 1);
                            } else {
                                recorder.note("set.add", start, end, key, memberCount(args));
                            }
                            EventHook after = afterHook;
                            if (after != null) {
                                after.onEvent(name.equals("set") ? "value.set" : "set.add", key);
                            }
                        } else {
                            recorder.note("ops." + name, start, end, key, 0);
                        }
                        return result;
                    });
        }

        private static int memberCount(Object[] args) {
            if (args != null && args.length == 2 && args[1] instanceof String[] arr) {
                return arr.length;
            }
            return args == null ? 0 : Math.max(0, args.length - 1);
        }
    }

    private static void clearHooks() {
        template.clearHooks();
        candidate.setQueuePointProbe(null);
        candidate.setPostCommitProbe(null);
        candidate.clearAbortInjection();
    }

    // ==================== 数据面与工具 ====================

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

    private static List<Long> sampleIds(int records) {
        List<Long> ids = new ArrayList<>();
        int step = Math.max(1, records / SAMPLED_PROBES);
        for (int i = 0; i < SAMPLED_PROBES; i++) {
            ids.add(baseOf(records) + (long) i * step);
        }
        return ids;
    }

    private static long baseOf(int records) {
        return records == TIER_S_RECORDS ? S_BASE : M_BASE;
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

    /** 灌入 DB 权威行；phantomSeeded 时再给每条 record 加一个幽灵成员与错计数（还原对账要纠的漂移）。 */
    private static void seedTier(int records, boolean phantomSeeded) throws SQLException {
        resetScratch();
        long start = System.nanoTime();
        long inserted = seedRows(records);
        assertEquals((long) records * MEMBERS, inserted, "seed 行数必须全量落库");
        if (phantomSeeded) {
            seedPhantomDrift(records);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("records", records);
        meta.put("rows", inserted);
        meta.put("phantomSeeded", phantomSeeded);
        meta.put("seedMs", ms(System.nanoTime() - start));
        READINGS.put("seed-" + records + (phantomSeeded ? "-phantom" : "-clean"), meta);
    }

    private static long seedRows(int records) throws SQLException {
        long base = baseOf(records);
        long total = 0;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            String recHead = "INSERT INTO `sport_record` (`id`,`request_id`,`user_id`,`sport_type`,"
                    + "`status`,`created_at`) VALUES ";
            StringBuilder rec = new StringBuilder(recHead);
            int n = 0;
            for (int i = 0; i < records; i++) {
                if (n > 0) {
                    rec.append(',');
                }
                long id = base + i;
                rec.append('(').append(id).append(",'t179-").append(id)
                        .append("',1,1,2,'2026-10-07 20:00:00')");
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

            String likeHead = "INSERT IGNORE INTO `record_like` "
                    + "(`record_id`,`user_id`,`created_at`) VALUES ";
            StringBuilder like = new StringBuilder(likeHead);
            int rows = 0;
            for (int i = 0; i < records; i++) {
                for (int u = 1; u <= MEMBERS; u++) {
                    if (rows > 0) {
                        like.append(',');
                    }
                    like.append('(').append(base + i).append(',').append(u)
                            .append(",'2026-10-07 20:00:00')");
                    rows++;
                    if (rows == 2_000) {
                        total += s.executeUpdate(like.toString());
                        like.setLength(0);
                        like.append(likeHead);
                        rows = 0;
                    }
                }
            }
            if (rows > 0) {
                total += s.executeUpdate(like.toString());
            }
        }
        return total;
    }

    /** 幽灵漂移态：成员集＝DB 的 50 个成员再加一个 DB 没有的用户，计数写成 999——这正是 DEL 存在的理由。 */
    private static void seedPhantomDrift(int records) {
        long base = baseOf(records);
        byte[] phantom = String.valueOf(PHANTOM_USER).getBytes(StandardCharsets.UTF_8);
        observerTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (int i = 0; i < records; i++) {
                long recordId = base + i;
                byte[][] vals = new byte[MEMBERS + 1][];
                for (int u = 0; u < MEMBERS; u++) {
                    vals[u] = String.valueOf(u + 1).getBytes(StandardCharsets.UTF_8);
                }
                vals[MEMBERS] = phantom;
                connection.sAdd(usersKey(recordId).getBytes(StandardCharsets.UTF_8), vals);
                connection.stringCommands().set((COUNT_PREFIX + recordId)
                        .getBytes(StandardCharsets.UTF_8), "999".getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
    }

    private static void resetScratch() throws SQLException {
        exec("SET FOREIGN_KEY_CHECKS = 0");
        exec("TRUNCATE TABLE `record_like`");
        exec("TRUNCATE TABLE `sport_record`");
        exec("SET FOREIGN_KEY_CHECKS = 1");
        deleteKeysByPattern("like:*");
        deleteKeysByPattern("lock:like:*");
        assertEquals(0, llen(), "重置后 pending 队列必须为空");
        assertEquals(0, countRows(), "重置后 scratch 表必须为空");
    }

    private static long deleteKeysByPattern(String pattern) {
        Set<String> keys = observerTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long deleted = observerTemplate.delete(keys);
        return deleted == null ? 0 : deleted;
    }

    private static long llen() {
        Long size = observerTemplate.opsForList().size(RecordLikeService.PENDING_QUEUE_KEY);
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

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
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
        assertTrue(schemeIdx > 0, "TASK179_IT_DB_URL 须为 jdbc:mysql://host:port 形态：" + envUrl);
        String afterAuthority = base.substring(schemeIdx + 3);
        int slashIdx = afterAuthority.indexOf('/');
        String hostPart = slashIdx >= 0 ? afterAuthority.substring(0, slashIdx) : afterAuthority;
        assertTrue(!hostPart.isBlank(), "TASK179_IT_DB_URL 缺少 host:port：" + envUrl);
        String prefix = base.substring(0, schemeIdx + 3) + hostPart + "/";
        return new String[]{prefix + query, prefix + dbName + query};
    }

    private static Map<String, Object> runMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("task", "TASK-179");
        meta.put("run", runId);
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
        meta.put("redisPasswordSet", !isBlank(redisPassword));
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
        meta.put("tierSRecords", TIER_S_RECORDS);
        meta.put("tierMRecords", TIER_M_RECORDS);
        meta.put("membersPerRecord", MEMBERS);
        meta.put("chunkCommandCaps", CHUNK_CAPS);
        meta.put("sampledProbes", SAMPLED_PROBES);
        return meta;
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** scratch 库重建：机械改名 {@code record_db -> task179_it} 后逐语句灌入，绝不触碰演示库。 */
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
            // scratch 库须补齐 05 号迁移的归档列：生产 like() 经 selectById 取全列清单，
            // 仅按 02 号建表会报 Unknown column 'archived'，注入探针便跑不起来。
            s.execute("ALTER TABLE `" + SCRATCH_DB + "`.`sport_record` ADD COLUMN `archived` "
                    + "TINYINT NOT NULL DEFAULT 0 COMMENT '轨迹点已归档（0 否 1 是）' AFTER `status`");
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

    private static synchronized void writeRaw(String fileName, Map<String, Object> data) throws Exception {
        Files.write(rawDir.resolve(fileName),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
    }

    private static void writeRawQuietly(String fileName, Map<String, Object> data) {
        try {
            writeRaw(fileName, data);
        } catch (Exception e) {
            throw new IllegalStateException("raw 落盘失败：" + fileName, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return (List<String>) value;
    }

    private static Map<String, Object> distributionMs(List<? extends Number> valuesMs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (valuesMs.isEmpty()) {
            out.put("count", 0);
            out.put("sumMs", 0.0);
            return out;
        }
        List<Double> sorted = new ArrayList<>();
        for (Number n : valuesMs) {
            sorted.add(n.doubleValue());
        }
        Collections.sort(sorted);
        out.put("count", sorted.size());
        out.put("minMs", round6(sorted.get(0)));
        out.put("p50Ms", round6(quantile(sorted, 0.5)));
        out.put("p90Ms", round6(quantile(sorted, 0.9)));
        out.put("maxMs", round6(sorted.get(sorted.size() - 1)));
        out.put("sumMs", round6(sorted.stream().mapToDouble(Double::doubleValue).sum()));
        return out;
    }

    private static double quantile(List<Double> sorted, double q) {
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static String resultText(Map<String, Object> data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertAllChecks(String label, Map<String, Object> result) {
        Map<String, Object> checks = (Map<String, Object>) result.get("checks");
        assertNotNull(checks, label + " 缺 checks");
        for (Map.Entry<String, Object> c : checks.entrySet()) {
            assertTrue((Boolean) c.getValue(),
                    label + " 检查未通过：" + c.getKey() + "；读数=" + resultText(result));
        }
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 1000.0) / 1000.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
