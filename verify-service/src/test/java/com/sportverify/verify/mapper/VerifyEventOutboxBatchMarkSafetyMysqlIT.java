package com.sportverify.verify.mapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sportverify.verify.entity.VerifyEventOutbox;
import com.sportverify.verify.mq.VerifyEventProducer;
import com.sportverify.verify.mq.VerifyOutboxRelay;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「批末统一标记 SENT」候选 vs「逐行发送后立即条件 markSent」现行的<strong>真库语义判别</strong>（TASK-153）。
 *
 * <p>要回答的问题只有一个是/否：把成功行留到批末再标记，是否在不改变既有可靠投递 / 可见性 / 重试 /
 * eventId / 时间语义的前提下可行。本类<strong>不实施优化</strong>：生产 relay / Mapper / 配置一行不改，
 * 批末更新 SQL <strong>只存在于本测试</strong>；本类结果<strong>不是</strong>吞吐或延迟对照——
 * TASK-152 的单轮 73.93% 是日志整数值的同窗商，不得当作批量化收益。</p>
 *
 * <p><b>什么是真的、什么是模拟的</b>：真实 MySQL 8（专用 scratch schema）+ 仓库真实 DDL
 * （{@code sql/03-verify-db.sql} 机械替换库名灌入）+ 真实 {@link VerifyEventOutboxMapper} SQL +
 * <strong>真实 {@link VerifyOutboxRelay#relay()} 代码路径</strong> + 真实 {@link VerifyEventProducer}；
 * 仅 RocketMQ 客户端（{@link RocketMQTemplate}）与 Redisson 锁被替换为测试替身——发送是否被
 * 「broker 接收」、发送失败、进程退出都由测试可控地模拟。<strong>数据库读写一律真库</strong>，
 * 不以 Mockito 预置行集冒充 SQL 结果；也<strong>不得</strong>把本类结果称为 RocketMQ / Redis 端到端通过。</p>
 *
 * <p>可观察量：每行最终 {@code status}/{@code retry_count}/{@code sent_at}、独立连接在
 * 「每次发送时刻」看到的状态快照、模拟崩溃后<strong>下轮真实取批 SQL</strong> 会重投的行集与
 * 其 {@code event_id}（relay 重发沿用行内 eventId）。</p>
 *
 * <p><b>需要真 MySQL，默认不参与常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集，只有
 * {@code -Dtest=} 显式指定才跑），并要求三个环境变量，缺任一即 assume 跳过（跳过不算通过）：
 * {@code TASK153_IT_URL} / {@code TASK153_IT_USER} / {@code TASK153_IT_PASSWORD}。
 * 库名必须解析为专用 {@code task153_batch_scratch}，否则直接失败（防止误指演示库）。</p>
 *
 * <p>准备库（与 TASK-147/148/149 同一机械程序，跑完可 DROP；未触碰演示 {@code verify_db} 与其他
 * 既有 scratch schema）：
 * <pre>
 * sed 's/verify_db/task153_batch_scratch/g' sql/03-verify-db.sql \
 *   | docker exec -i task131-scratch-mysql mysql -uroot -proot
 * </pre>
 * 运行（唯一入口 + {@code MAVEN_ARGS} 注入目标类选择器，Maven 3.9+ 原生支持该环境变量）：
 * <pre>
 * MAVEN_ARGS="-Dtest=VerifyEventOutboxBatchMarkSafetyMysqlIT -Dsurefire.failIfNoSpecifiedTests=false" \
 *   bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test
 * </pre>
 * </p>
 */
class VerifyEventOutboxBatchMarkSafetyMysqlIT {

    private static final String SCHEMA = "task153_batch_scratch";
    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY = 16;
    /** 时间语义用例的受控发送耗时：让「发送完成时刻」与「批末标记时刻」在秒精度 DATETIME 下可分辨。 */
    private static final long SEND_HOLD_MS = 3000L;

    private HikariDataSource dataSource;
    private SqlSessionFactory factory;
    private VerifyEventOutboxMapper mapper;
    private ObjectMapper objectMapper;
    private ListAppender<ILoggingEvent> relayLogAppender;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TASK153_IT_URL");
        String user = System.getenv("TASK153_IT_USER");
        String password = System.getenv("TASK153_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "缺 TASK153_IT_URL/USER/PASSWORD 环境变量，跳过 scratch MySQL 语义判别（不视为通过）");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(6);
        cfg.setPoolName("task153-batch-mark-it");
        dataSource = new HikariDataSource(cfg);

        // 防误指：环境变量必须落在本任务专用 scratch schema 上，否则直接失败（不静默跳过）。
        String catalog;
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DATABASE()")) {
            assertTrue(rs.next(), "SELECT DATABASE() 应返回当前库");
            catalog = rs.getString(1);
        }
        assertEquals(SCHEMA, catalog,
                "TASK153_IT_URL 必须指向专用 " + SCHEMA + "（当前=" + catalog + "）；本用例绝不触碰演示库");
        assertTrue(url.contains(SCHEMA), "JDBC URL 必须显式含 " + SCHEMA + "：url=" + url);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("task153-scratch", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(VerifyEventOutboxMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        mapper = newMapperProxy();

        exec("TRUNCATE TABLE verify_event_outbox");
    }

    @AfterEach
    void tearDown() {
        if (relayLogAppender != null) {
            ((Logger) LoggerFactory.getLogger(VerifyOutboxRelay.class)).detachAppender(relayLogAppender);
            relayLogAppender.stop();
            relayLogAppender = null;
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ------------------------------------------------------------------ 用例

    /**
     * 独立连接可见性：现行在每行发送返回后立即提交条件 {@code markSent}，故第 k 次发送时刻，
     * 前 k-1 行已对其他连接可见为 SENT；候选把成功行留到批末，故批末之前整批仍显示 PENDING。
     */
    @Test
    void visibilityBetweenSends_perRowCommitsImmediately_batchEndKeepsPending() throws Exception {
        List<Long> rowIds = insertPending("vis-e1", "vis-e2", "vis-e3");

        List<String> baselineSnapshots = new ArrayList<>();
        VerifyEventProducer baselineProducer = producerWithPolicy(
                (destination, payload) -> baselineSnapshots.add(Arrays.toString(snapshotStatuses(rowIds))));
        newRelay(baselineProducer).relay();

        assertEquals("[PENDING, PENDING, PENDING]", baselineSnapshots.get(0), "发送第 1 行时标记尚未执行");
        assertEquals("[SENT, PENDING, PENDING]", baselineSnapshots.get(1),
                "现行逐行路径：第 2 行发送时，第 1 行已在独立连接上可见为 SENT（发送后立即提交）");
        assertEquals("[SENT, SENT, PENDING]", baselineSnapshots.get(2),
                "现行逐行路径：第 3 行发送时前两行均已提交可见");
        assertEquals("[SENT, SENT, SENT]", Arrays.toString(snapshotStatuses(rowIds)), "整批最终 SENT");

        exec("TRUNCATE TABLE verify_event_outbox");
        List<Long> candidateRowIds = insertPending("vis-e1", "vis-e2", "vis-e3");
        List<String> candidateSnapshots = new ArrayList<>();
        VerifyEventProducer candidateProducer = producerWithPolicy(
                (destination, payload) -> candidateSnapshots.add(Arrays.toString(snapshotStatuses(candidateRowIds))));
        List<Long> sentIds = sendPhaseBatchEnd(candidateProducer);
        assertEquals(3, sentIds.size(), "候选发送阶段应记录 3 行成功");

        assertEquals("[PENDING, PENDING, PENDING]", candidateSnapshots.get(0));
        assertEquals("[PENDING, PENDING, PENDING]", candidateSnapshots.get(1),
                "候选：第 2 行发送时，已投递的第 1 行在独立连接上仍是 PENDING（SENT 可见性被推迟）");
        assertEquals("[PENDING, PENDING, PENDING]", candidateSnapshots.get(2),
                "候选：第 3 行发送时整批仍 PENDING");
        assertEquals(3, markSentBatch(sentIds), "候选批末标记应命中全部 3 行");
        assertEquals("[SENT, SENT, SENT]", Arrays.toString(snapshotStatuses(candidateRowIds)));

        evidence("visibility.baseline.snapshots", baselineSnapshots.toString());
        evidence("visibility.candidate.snapshots", candidateSnapshots.toString());
    }

    /**
     * 最强反例：第 k 条发送已被模拟 broker 接收后、批末标记前进程退出。
     * 现行逐行路径只剩「当前在飞那 1 行」待重扫；候选留下整批 PENDING 并在下轮全部重投。
     */
    @Test
    void crashAfterLastSend_reSendScope_baselineSingleRow_vs_batchEndWholeBatch() throws Exception {
        // ---- 现行逐行：3 行全部发送成功后再模拟进程退出（此时前 2 行已各自提交 SENT）
        List<Long> rowIds = insertPending("crash-e1", "crash-e2", "crash-e3");
        Map<String, String> baselineDelivered = new LinkedHashMap<>();
        VerifyEventProducer baselineProducer = producerWithPolicy((destination, payload) -> {
            baselineDelivered.put(eventIdOfPayload(payload), destination);
            if (baselineDelivered.size() == 3) {
                throw new SimulatedProcessExit("模拟：第 3 条已被接收后、本地标记前进程退出");
            }
        });
        assertThrows(SimulatedProcessExit.class, () -> newRelay(baselineProducer).relay(),
                "逐行路径下 Error 不被 relay 的 catch (Exception) 捕获，应原样逃出（模拟硬退出）");
        assertEquals(3, baselineDelivered.size(), "崩溃前 3 行都已被模拟 broker 接收");

        assertEquals("[SENT, SENT, PENDING]", Arrays.toString(snapshotStatuses(rowIds)),
                "现行逐行路径：已提交标记的前两行不因后续崩溃回到 PENDING");
        List<String> baselineNextRound = nextRoundEventIds();
        assertEquals(List.of("crash-e3"), baselineNextRound,
                "现行逐行路径下轮只会重投「发送成功但未标记」的那 1 行");
        long baselineRowCount = countRows();

        // ---- 候选：同样 3 行发送成功后再模拟进程退出（批末标记尚未执行）
        exec("TRUNCATE TABLE verify_event_outbox");
        List<Long> candidateRowIds = insertPending("crash-e1", "crash-e2", "crash-e3");
        Map<String, String> candidateDelivered = new LinkedHashMap<>();
        VerifyEventProducer candidateProducer = producerWithPolicy((destination, payload) -> {
            candidateDelivered.put(eventIdOfPayload(payload), destination);
            if (candidateDelivered.size() == 3) {
                throw new SimulatedProcessExit("模拟：第 3 条已被接收后、批末标记前进程退出");
            }
        });
        assertThrows(SimulatedProcessExit.class, () -> sendPhaseBatchEnd(candidateProducer),
                "候选的发送阶段不得吞掉硬退出");
        assertEquals(3, candidateDelivered.size(), "候选崩溃前同样 3 行已被模拟 broker 接收");

        assertEquals("[PENDING, PENDING, PENDING]", Arrays.toString(snapshotStatuses(candidateRowIds)),
                "候选：整批发送成功但批末标记未执行，数据库仍称三行都「未投递」");
        List<String> candidateNextRound = nextRoundEventIds();
        assertEquals(List.of("crash-e1", "crash-e2", "crash-e3"), candidateNextRound,
                "候选下轮会重投整批（重复投递范围由在飞 1 行扩大为批大小上限）");
        assertEquals(baselineRowCount, countRows(), "两条路径都不新增行（重投沿用原行）");
        assertEquals(2, candidateNextRound.size() - baselineNextRound.size(),
                "同批同崩溃点：候选比现行多 2 行会在下轮重投");

        // 原 eventId：下轮重投的行沿用行内 event_id，与首次实际投递出去的 eventId 逐字一致
        assertEquals(new ArrayList<>(candidateDelivered.keySet()), candidateNextRound,
                "下轮重投集合的 eventId 与首次投递的 eventId 逐字一致（不换 id）");
        assertEquals(List.of("crash-e1", "crash-e2", "crash-e3"), new ArrayList<>(baselineDelivered.keySet()),
                "首次投递的 payload eventId 与行内 eventId 一致");

        evidence("crash.baseline.nextRound", baselineNextRound.toString());
        evidence("crash.candidate.nextRound", candidateNextRound.toString());
        evidence("crash.extraReSendRows", String.valueOf(candidateNextRound.size() - baselineNextRound.size()));
        evidence("crash.reSendEventIdsUnchanged", "true");
    }

    /** 混合批次：成功 / 发送失败 / 重试耗尽共存时必须各行分明，失败行不得被标 SENT 或丢弃。 */
    @Test
    void mixedBatch_failedSendNeverMarkedSent_retryCountAdvances_exhaustedUntouched_bothPaths() throws Exception {
        // ---- 现行逐行
        long ok1 = insertRow("mix-e1", "PENDING", 0);
        long bad = insertRow("mix-e2", "PENDING", 0);
        long ok3 = insertRow("mix-e3", "PENDING", 0);
        long exhausted = insertRow("mix-e4", "PENDING", MAX_RETRY);

        VerifyEventProducer baselineProducer = producerWithPolicy((destination, payload) -> {
            if (eventIdOfPayload(payload).equals("mix-e2")) {
                throw new IllegalStateException("模拟：MQ 发送失败");
            }
        });
        newRelay(baselineProducer).relay();

        assertEquals("SENT", statusOf(ok1));
        assertNotNull(sentAtOf(ok1), "成功行标记时置 sent_at");
        assertEquals("PENDING", statusOf(bad), "发送失败行不得被标 SENT");
        assertEquals(1, retryCountOf(bad), "发送失败行 retry_count+1");
        assertNull(sentAtOf(bad), "失败行不置 sent_at");
        assertEquals("SENT", statusOf(ok3), "单个失败不得中断后续行");
        assertEquals("PENDING", statusOf(exhausted), "耗尽行保持 PENDING 供人工处理");
        assertEquals(MAX_RETRY, retryCountOf(exhausted), "耗尽行不重复累加");
        assertNull(sentAtOf(exhausted));

        List<String> baselineNextRound = nextRoundEventIds();
        assertEquals(List.of("mix-e2"), baselineNextRound,
                "下轮只重试失败行（耗尽行 retry_count<16 不成立，不占批次）");
        String badEventId = eventIdOf(bad);

        // ---- 候选（同一数据形状）
        exec("TRUNCATE TABLE verify_event_outbox");
        long cOk1 = insertRow("mix-e1", "PENDING", 0);
        long cBad = insertRow("mix-e2", "PENDING", 0);
        long cOk3 = insertRow("mix-e3", "PENDING", 0);
        long cExhausted = insertRow("mix-e4", "PENDING", MAX_RETRY);

        VerifyEventProducer candidateProducer = producerWithPolicy((destination, payload) -> {
            if (eventIdOfPayload(payload).equals("mix-e2")) {
                throw new IllegalStateException("模拟：MQ 发送失败");
            }
        });
        List<Long> candidateSent = sendPhaseBatchEnd(candidateProducer);
        assertEquals(2, candidateSent.size(), "候选中仅 2 行进入成功集合");
        assertFalse(candidateSent.contains(cBad), "失败行不得进入批末标记集合");
        assertFalse(candidateSent.contains(cExhausted), "耗尽行不得进入批末标记集合");
        assertEquals(2, markSentBatch(candidateSent));

        assertEquals("SENT", statusOf(cOk1));
        assertEquals("PENDING", statusOf(cBad), "候选同样不得把失败行标 SENT");
        assertEquals(1, retryCountOf(cBad));
        assertNull(sentAtOf(cBad));
        assertEquals("SENT", statusOf(cOk3));
        assertEquals("PENDING", statusOf(cExhausted));
        assertEquals(MAX_RETRY, retryCountOf(cExhausted));
        assertEquals(List.of("mix-e2"), nextRoundEventIds(), "候选下轮资格与现行一致（同一取批 SQL）");
        assertEquals(badEventId, eventIdOf(cBad), "失败行重试沿用原 eventId");

        evidence("mixed.baseline.nextRound", baselineNextRound.toString());
        evidence("mixed.candidate.nextRound", nextRoundEventIds().toString());
    }

    /** 条件更新的粒度：逐行调用能给出该行的 0/1；候选一条批量 UPDATE 只返回聚合行数，无法逐行归因。 */
    @Test
    void conditionalUpdate_zeroRowsAndAggregateAttribution() throws Exception {
        // ---- 逐行：已 SENT 的行再标一次 → 条件不命中，返回 0，且 sent_at 不被改写
        long sent = insertRow("cond-e1", "PENDING", 0);
        assertEquals(1, mapper.markSent(sent));
        Object sentAtBefore = sentAtOf(sent);
        assertEquals(0, mapper.markSent(sent), "条件 UPDATE 对已 SENT 行返回 0（逐行可归因）");
        assertEquals("SENT", statusOf(sent));
        assertEquals(String.valueOf(sentAtBefore), String.valueOf(sentAtOf(sent)), "重复标记不得改写 sent_at");
        assertEquals(0, mapper.markSent(999_999L), "不存在的行同样返回 0");

        // ---- 候选：一条批量 UPDATE 覆盖「1 行 PENDING + 1 行已 SENT」→ 只返回聚合计数 1
        long pending = insertRow("cond-e2", "PENDING", 0);
        int affected = markSentBatch(List.of(pending, sent));
        assertEquals(1, affected,
                "批量条件 UPDATE 命中 1 行：与逐行调用不同，聚合计数无法指出是哪一行未命中");
        assertEquals("SENT", statusOf(pending));
        assertEquals(0, markSentBatch(List.of(sent)), "全为已 SENT 时批量为 0 行");
        assertEquals(0, markSentBatch(List.of(999_998L)), "不存在的行批量为 0 行");

        // ---- 真实 SQL 失败的传播（改表名制造真错，与 TASK-147 同法）：整批留在 PENDING
        long f1 = insertRow("cond-e3", "PENDING", 0);
        long f2 = insertRow("cond-e4", "PENDING", 0);
        renameOutbox("verify_event_outbox", "verify_event_outbox_task153_broken");
        try {
            Throwable batchFailure = assertThrows(Throwable.class, () -> markSentBatch(List.of(f1, f2)),
                    "批量 UPDATE 在真 SQL 失败时应抛出（不得吞掉）");
            Throwable singleFailure = assertThrows(Throwable.class, () -> mapper.markSent(f1),
                    "逐行标记在真 SQL 失败时同样抛出");
            assertNotNull(batchFailure.getMessage());
            assertFalse(batchFailure.getMessage().isBlank());
            assertNotNull(singleFailure.getMessage());
            evidence("conditional.batchFailureType", batchFailure.getClass().getName());
            evidence("conditional.singleFailureType", singleFailure.getClass().getName());
        } finally {
            renameOutbox("verify_event_outbox_task153_broken", "verify_event_outbox");
        }
        assertEquals("PENDING", statusOf(f1), "批量 UPDATE 失败后已投递行仍为 PENDING（下轮会重投）");
        assertEquals("PENDING", statusOf(f2));
        assertEquals(List.of("cond-e3", "cond-e4"), nextRoundEventIds(),
                "批量标记真失败后，下轮重投范围 = 整批已投递行");

        // ---- 现行 relay 对「标记 0 行」的既有处理：返回值被丢弃，仍按成功计数
        exec("TRUNCATE TABLE verify_event_outbox");
        long zero = insertRow("cond-e5", "PENDING", 0);
        VerifyEventProducer producer = producerWithPolicy((destination, payload) -> {
            try {
                // 发送返回后、relay 标记前，另一个实例抢先标记（真实 SQL，另一条连接）
                assertEquals(1, markSentOnSeparateConnection(zero), "另一实例抢先标记应命中 1 行");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        newRelay(producer, true).relay();
        String summary = relayLogLines().stream()
                .filter(m -> m.contains("outbox relay 诊断（批次）"))
                .findFirst().orElse("");
        assertEquals("SENT", statusOf(zero), "该行最终 SENT（由抢先的标记写入）");
        assertTrue(summary.contains("success=1") && summary.contains("failed=0"),
                "既有行为：relay 丢弃 markSent 返回值，0 行命中仍计为成功；实测=" + summary);
        assertEquals(List.of(), nextRoundEventIds(), "该行既已 SENT，不应再出现在下轮取批中");

        evidence("conditional.aggregateAffected", String.valueOf(affected));
        evidence("conditional.zeroRowStillCountedSuccess", summary);
    }

    /** sent_at 语义：现行 ≈ 该行发送完成时刻；候选批末统一，首行偏差可达整批时长（DDL 注释为「投递成功时间」）。 */
    @Test
    void sentAt_semantics_driftAndFlattening() throws Exception {
        // ---- 现行逐行：两行各占用 3s 发送，标记紧随各自发送返回
        long b1 = insertRow("time-e1", "PENDING", 0);
        long b2 = insertRow("time-e2", "PENDING", 0);
        Map<String, String> baselineSendDone = new LinkedHashMap<>();
        VerifyEventProducer baselineProducer = producerWithPolicy((destination, payload) -> {
            hold(SEND_HOLD_MS);
            baselineSendDone.put(eventIdOfPayload(payload), dbNow());
        });
        newRelay(baselineProducer).relay();

        long baselineDrift1 = secondsBetween(baselineSendDone.get("time-e1"), sentAtOf(b1));
        long baselineDrift2 = secondsBetween(baselineSendDone.get("time-e2"), sentAtOf(b2));
        long baselineGap = secondsBetween(String.valueOf(sentAtOf(b1)), sentAtOf(b2));
        assertTrue(baselineDrift1 <= 1, "现行：sent_at 与发送完成时刻相差应很小，实测=" + baselineDrift1 + "s");
        assertTrue(baselineDrift2 <= 1, "现行：第二行同理，实测=" + baselineDrift2 + "s");
        assertTrue(baselineGap >= 2, "现行：两行 sent_at 各自贴近各自发送时刻，实测间隔=" + baselineGap + "s");

        // ---- 候选：同一发送节奏，标记在整批结束后一次写入
        exec("TRUNCATE TABLE verify_event_outbox");
        long c1 = insertRow("time-e1", "PENDING", 0);
        long c2 = insertRow("time-e2", "PENDING", 0);
        Map<String, String> candidateSendDone = new LinkedHashMap<>();
        VerifyEventProducer candidateProducer = producerWithPolicy((destination, payload) -> {
            hold(SEND_HOLD_MS);
            candidateSendDone.put(eventIdOfPayload(payload), dbNow());
        });
        List<Long> candidateSent = sendPhaseBatchEnd(candidateProducer);
        assertEquals(2, markSentBatch(candidateSent));

        long candidateDrift1 = secondsBetween(candidateSendDone.get("time-e1"), sentAtOf(c1));
        long candidateGap = secondsBetween(String.valueOf(sentAtOf(c1)), sentAtOf(c2));
        assertTrue(candidateDrift1 >= SEND_HOLD_MS / 1000,
                "候选：首行 sent_at 明显晚于该行发送完成时刻，实测漂移=" + candidateDrift1 + "s");
        assertTrue(candidateGap <= 1,
                "候选：整批 sent_at 被压平到同一时刻，实测间隔=" + candidateGap + "s");
        assertTrue(candidateDrift1 > baselineDrift1,
                "两条路径的 sent_at 漂移必须可分辨：" + candidateDrift1 + "s vs " + baselineDrift1 + "s");

        evidence("sentAt.baseline.driftSeconds", baselineDrift1 + "," + baselineDrift2);
        evidence("sentAt.candidate.driftSeconds", candidateDrift1 + "," + SEND_HOLD_MS / 1000);
        evidence("sentAt.baseline.gapSeconds", String.valueOf(baselineGap));
        evidence("sentAt.candidate.gapSeconds", String.valueOf(candidateGap));
    }

    /**
     * 多实例 / 下轮重扫：候选崩溃态下，已投递未标记的行对第二个连接完全等同「尚未投递」——
     * 它会被第二个实例取走并投递；随后批末标记只能给出聚合计数，无法指出是哪一行已被别人标记。
     */
    @Test
    void secondInstance_seesDeliveredUnmarkedRows_andAggregateMarkLosesAttribution() throws Exception {
        List<Long> rowIds = insertPending("multi-e1", "multi-e2");
        VerifyEventProducer producer = producerWithPolicy((destination, payload) -> {
        });
        List<Long> sentIds = sendPhaseBatchEnd(producer);
        assertEquals(2, sentIds.size());
        assertEquals("[PENDING, PENDING]", Arrays.toString(snapshotStatuses(rowIds)),
                "崩溃前状态：两行都已被送出，但库里仍是 PENDING");

        // 第二个实例（另一条会话、同一真实取批 SQL）看到的两行与「尚未投递」无法区分
        List<String> secondInstanceSelection = nextRoundEventIds();
        assertEquals(List.of("multi-e1", "multi-e2"), secondInstanceSelection,
                "第二个实例会把已投递未标记的行当作待投递行取走（重复投递窗口 = 整批）");
        assertEquals(1, markSentOnSeparateConnection(rowIds.get(0)),
                "第二个实例发送并标记第 1 行成功（真实 SQL）");

        int affected = markSentBatch(sentIds);
        assertEquals(1, affected,
                "原实例的批末聚合计数据只有 1，无法逐行指出「第 1 行已被别处标记、第 2 行才是本次标记的」");
        assertEquals("[SENT, SENT]", Arrays.toString(snapshotStatuses(rowIds)));

        evidence("multi.secondInstanceSelection", secondInstanceSelection.toString());
        evidence("multi.batchAggregateAffected", String.valueOf(affected));
    }

    /** 重试耗尽行：取批资格与现行一致，两条路径都不得发送 / 标记 / 累加计数。 */
    @Test
    void exhaustedRow_neverSelected_untouchedByBothPaths() throws Exception {
        long exhausted = insertRow("exh-e1", "PENDING", MAX_RETRY);
        assertEquals(List.of(), nextRoundEventIds(), "耗尽行不占发送批次（真实取批 SQL 资格条件）");

        VerifyEventProducer baselineProducer = producerWithPolicy((destination, payload) -> {
            throw new AssertionError("耗尽行不得被发送");
        });
        newRelay(baselineProducer).relay();
        assertUntouched(exhausted);

        exec("TRUNCATE TABLE verify_event_outbox");
        long exhausted2 = insertRow("exh-e1", "PENDING", MAX_RETRY);
        VerifyEventProducer candidateProducer = producerWithPolicy((destination, payload) -> {
            throw new AssertionError("耗尽行不得被发送");
        });
        assertEquals(0, sendPhaseBatchEnd(candidateProducer).size(), "候选发送阶段不得取到耗尽行");
        assertUntouched(exhausted2);
        assertEquals(0, markSentBatch(List.of()), "空成功集合的批末 UPDATE 影响 0 行");
    }

    // ------------------------------------------------------------------ 被测协议

    /**
     * 候选（<strong>仅本测试存在</strong>，不落生产代码）：与 relay 相同的取批、按 id 顺序、
     * 耗尽兜底与失败逐行 {@code incrRetry}；唯一差别是成功行不在发送后立即标记，批末不写任何标记。
     *
     * @return 本批发送成功的行 id（批末标记的目标集合）
     */
    private List<Long> sendPhaseBatchEnd(VerifyEventProducer producer) throws Exception {
        List<VerifyEventOutbox> batch = mapper.selectPendingBatch(BATCH_SIZE, MAX_RETRY);
        List<Long> sentIds = new ArrayList<>();
        for (VerifyEventOutbox row : batch) {
            if (row.getRetryCount() != null && row.getRetryCount() >= MAX_RETRY) {
                continue;
            }
            try {
                producer.syncSend(row);
            } catch (Exception e) {
                mapper.incrRetry(row.getId());
                continue;
            }
            sentIds.add(row.getId());
        }
        return sentIds;
    }

    /**
     * 候选的批末标记（<strong>测试专用 SQL</strong>，刻意不加入生产 {@code VerifyEventOutboxMapper}）：
     * 一条条件批量 UPDATE，条件与原逐行 SQL 相同的 {@code status='PENDING'}。
     */
    private int markSentBatch(List<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() "
                + "WHERE status = 'PENDING' AND id IN (" + placeholders + ")";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ 基础设施

    /** 按 @Value 默认语义注入配置的真实 relay（诊断关闭）；policy 决定每次发送的行为。 */
    private VerifyOutboxRelay newRelay(VerifyEventProducer producer) {
        return newRelay(producer, false);
    }

    private VerifyOutboxRelay newRelay(VerifyEventProducer producer, boolean diagnosticsEnabled) {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock("verify:outbox:relay")).thenReturn(lock);
        try {
            when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        VerifyOutboxRelay relay = new VerifyOutboxRelay(mapper, producer, redissonClient);
        ReflectionTestUtils.setField(relay, "batchSize", BATCH_SIZE);
        ReflectionTestUtils.setField(relay, "maxRetry", MAX_RETRY);
        ReflectionTestUtils.setField(relay, "relayDiagnosticsEnabled", diagnosticsEnabled);
        ReflectionTestUtils.setField(relay, "relayDiagnosticsWindowMs", 10_000L);
        if (diagnosticsEnabled) {
            relayLogAppender = new ListAppender<>();
            relayLogAppender.start();
            ((Logger) LoggerFactory.getLogger(VerifyOutboxRelay.class)).addAppender(relayLogAppender);
        }
        return relay;
    }

    /** 本轮 relay 输出（有界诊断开着时才有行）。 */
    private List<String> relayLogLines() {
        return relayLogAppender == null ? List.of()
                : relayLogAppender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }

    /** 真实 {@link VerifyEventProducer}：RocketMQ 客户端被替换为测试替身，发送行为由 policy 控制。 */
    @SuppressWarnings("unchecked")
    private VerifyEventProducer producerWithPolicy(SendPolicy policy) {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        when(template.syncSend(anyString(), any(Message.class))).thenAnswer(invocation -> {
            Message<String> message = invocation.getArgument(1);
            policy.onSend(invocation.getArgument(0), message.getPayload());
            return null;
        });
        return new VerifyEventProducer(template, objectMapper);
    }

    /** 测试专用发送策略：可记录、可抛真实失败、可在「已被接收」后抛 {@link SimulatedProcessExit}。 */
    @FunctionalInterface
    private interface SendPolicy {
        void onSend(String destination, String payload) throws Exception;
    }

    /** 测试专用：模拟进程硬退出；Error 不被 relay 的 {@code catch (Exception e)} 捕获。 */
    private static final class SimulatedProcessExit extends Error {
        SimulatedProcessExit(String message) {
            super(message);
        }
    }

    /**
     * 逐行会话代理：每次调用打开一个 auto-commit 会话（与 MyBatis-Spring 在无 Spring 事务时
     * 「一调用一提交」的语义一致，沿用 TASK-147/148/149 已入库的 scratch IT 口径）。
     */
    private VerifyEventOutboxMapper newMapperProxy() {
        return (VerifyEventOutboxMapper) Proxy.newProxyInstance(
                VerifyEventOutboxMapper.class.getClassLoader(),
                new Class<?>[]{VerifyEventOutboxMapper.class},
                (proxy, method, args) -> {
                    try (SqlSession session = factory.openSession(true)) {
                        try {
                            return method.invoke(session.getMapper(VerifyEventOutboxMapper.class), args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }

    /** 下轮真实取批 SQL（生产 Mapper 的 selectPendingBatch），返回会被重投的 eventId 顺序。 */
    private List<String> nextRoundEventIds() {
        return mapper.selectPendingBatch(BATCH_SIZE, MAX_RETRY).stream()
                .map(VerifyEventOutbox::getEventId)
                .collect(Collectors.toList());
    }

    private int markSentOnSeparateConnection(long id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE verify_event_outbox SET status = 'SENT', sent_at = NOW() "
                        + "WHERE id = ? AND status = 'PENDING'")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    private long insertRow(String eventId, String status, int retryCount) throws SQLException {
        String sql = "INSERT INTO verify_event_outbox "
                + "(event_id, topic, tag, payload, trace_id, status, retry_count, created_at, sent_at) "
                + "VALUES (?, 'record-verify-events', 'VERIFIED', ?, NULL, ?, ?, NOW(), NULL)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, eventId);
            ps.setString(2, "{\"eventId\":\"" + eventId + "\",\"recordId\":1,\"userId\":1}");
            ps.setString(3, status);
            ps.setInt(4, retryCount);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "插入应返回自增 id");
                return keys.getLong(1);
            }
        }
    }

    private List<Long> insertPending(String... eventIds) throws SQLException {
        List<Long> ids = new ArrayList<>();
        for (String eventId : eventIds) {
            ids.add(insertRow(eventId, "PENDING", 0));
        }
        return ids;
    }

    /** 独立连接（另一条会话）读取指定行的 status 快照。 */
    private String[] snapshotStatuses(List<Long> ids) throws SQLException {
        String[] out = new String[ids.size()];
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (int i = 0; i < ids.size(); i++) {
                try (ResultSet rs = s.executeQuery("SELECT status FROM verify_event_outbox WHERE id = " + ids.get(i))) {
                    out[i] = rs.next() ? rs.getString(1) : null;
                }
            }
        }
        return out;
    }

    private String statusOf(long id) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT status FROM verify_event_outbox WHERE id = " + id)) {
            assertTrue(rs.next(), "行应存在：id=" + id);
            return rs.getString(1);
        }
    }

    private int retryCountOf(long id) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT retry_count FROM verify_event_outbox WHERE id = " + id)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private String eventIdOf(long id) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT event_id FROM verify_event_outbox WHERE id = " + id)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private Object sentAtOf(long id) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT sent_at FROM verify_event_outbox WHERE id = " + id)) {
            assertTrue(rs.next());
            return rs.getObject(1);
        }
    }

    private long countRows() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM verify_event_outbox")) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private void assertUntouched(long id) throws SQLException {
        assertEquals("PENDING", statusOf(id), "耗尽行不因本轮被标 SENT");
        assertEquals(MAX_RETRY, retryCountOf(id), "耗尽行不重复累加 retry_count");
        assertNull(sentAtOf(id), "耗尽行不置 sent_at");
    }

    /** 数据库时钟当前时刻（与 sent_at 同源，避免宿主 / 库时钟混用）。 */
    private String dbNow() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT NOW()")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private long secondsBetween(String from, Object to) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT TIMESTAMPDIFF(SECOND, ?, ?)")) {
            ps.setString(1, sqlTimestamp(from));
            ps.setString(2, sqlTimestamp(to));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /** 统一成 MySQL 可解析的 'yyyy-MM-dd HH:mm:ss'（LocalDateTime.toString() 带 'T'）。 */
    private static String sqlTimestamp(Object value) {
        return String.valueOf(value).replace('T', ' ');
    }

    private String eventIdOfPayload(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        return node.get("eventId").asText();
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private void renameOutbox(String from, String to) throws SQLException {
        exec("RENAME TABLE " + from + " TO " + to);
    }

    private static void hold(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void evidence(String key, String value) {
        System.out.println("TASK153-EVIDENCE " + key + "=" + value);
    }
}
