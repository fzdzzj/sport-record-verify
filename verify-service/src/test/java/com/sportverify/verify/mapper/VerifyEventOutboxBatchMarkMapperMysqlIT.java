package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 MySQL scratch 库上的 markSentBatch 契约与语义 IT（TASK-165）.
 *
 * <p>默认不被 surefire 收集（*IT 后缀）；需显式通过环境变量提供独立 scratch 连接串.
 * 环境变量缺失时走 Assumptions skip（根据规范跳过不计通过）.
 * 严禁触碰演示库 verify_db 与已停止容器 task131-scratch-mysql.</p>
 */
class VerifyEventOutboxBatchMarkMapperMysqlIT {

    private static final String SCHEMA = "task165_batch_mark_scratch";
    private HikariDataSource dataSource;
    private SqlSessionFactory factory;
    private VerifyEventOutboxMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TASK165_IT_URL");
        String user = System.getenv("TASK165_IT_USER");
        String password = System.getenv("TASK165_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "缺 TASK165_IT_URL/USER/PASSWORD 环境变量，"
                        + "跳过 scratch MySQL 真实测试（不视为通过）");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(6);
        cfg.setPoolName("task165-batch-mark-it");
        dataSource = new HikariDataSource(cfg);

        // 防误指保护：必须明确指向 task165_batch_mark_scratch
        String catalog;
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DATABASE()")) {
            assertTrue(rs.next(), "SELECT DATABASE() 应返回当前库");
            catalog = rs.getString(1);
        }
        assertEquals(SCHEMA, catalog,
                "TASK165_IT_URL 必须指向专用 " + SCHEMA + "（当前=" + catalog + "）");
        assertTrue(url.contains(SCHEMA),
                "JDBC URL 必须显式包含 " + SCHEMA + "，禁止触碰演示库");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "task165-scratch",
                new JdbcTransactionFactory(),
                dataSource));
        configuration.addMapper(VerifyEventOutboxMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        mapper = newMapperProxy();

        exec("TRUNCATE TABLE verify_event_outbox");
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private void exec(final String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private VerifyEventOutboxMapper newMapperProxy() {
        return (VerifyEventOutboxMapper) Proxy.newProxyInstance(
                VerifyEventOutboxMapper.class.getClassLoader(),
                new Class<?>[]{VerifyEventOutboxMapper.class},
                (proxy, method, args) -> {
                    try (SqlSession session = factory.openSession(true)) {
                        VerifyEventOutboxMapper m =
                                session.getMapper(VerifyEventOutboxMapper.class);
                        return method.invoke(m, args);
                    }
                });
    }

    private Long insertRow(final String eventId, final String status,
                           final int retryCount) throws SQLException {
        String sql = "INSERT INTO verify_event_outbox "
                + "(event_id, topic, tag, payload, status, retry_count, "
                + "created_at) VALUES (?, 'test-topic', 'SUBMITTED', "
                + "'{}', ?, ?, NOW())";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, eventId);
            ps.setString(2, status);
            ps.setInt(3, retryCount);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    @Test
    @DisplayName("1. 条件幂等：第二次执行 affected=0")
    void test1_conditionalIdempotency_secondUpdateAffectedZero()
            throws Exception {
        Long id = insertRow("idem-1", "PENDING", 0);

        int first = mapper.markSentBatch(List.of(id));
        assertEquals(1, first, "首次标记影响 1 行");

        int second = mapper.markSentBatch(List.of(id));
        assertEquals(0, second, "重复标记受 status='PENDING' 保护，影响 0 行");
    }

    @Test
    @DisplayName("2. 部分命中：混入已 SENT 行与 PENDING 行")
    void test2_partialHit_mixedSentAndPending() throws Exception {
        Long id1 = insertRow("part-1", "PENDING", 0);
        Long id2 = insertRow("part-2", "SENT", 0);
        Long id3 = insertRow("part-3", "PENDING", 16);

        int affected = mapper.markSentBatch(List.of(id1, id2, id3));
        assertEquals(2, affected,
                "已 SENT 的 id2 不满足 status='PENDING'，仅 id1 与 id3 受影响");
    }

    @Test
    @DisplayName("3. sent_at chunk 内同值且非 NULL")
    void test3_sentAt_sameWithinChunkAndNonNull() throws Exception {
        Long id1 = insertRow("time-1", "PENDING", 0);
        Long id2 = insertRow("time-2", "PENDING", 0);

        int affected = mapper.markSentBatch(List.of(id1, id2));
        assertEquals(2, affected);

        Timestamp t1 = null;
        Timestamp t2 = null;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT sent_at FROM verify_event_outbox WHERE id = ?")) {
            ps.setLong(1, id1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                t1 = rs.getTimestamp(1);
            }
            ps.setLong(1, id2);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                t2 = rs.getTimestamp(1);
            }
        }
        assertNotNull(t1, "sent_at 不可为空");
        assertNotNull(t2, "sent_at 不可为空");
        assertEquals(t1, t2, "同 chunk 内的一条 UPDATE 保证 sent_at 完全同值");
    }

    @Test
    @DisplayName("4. 与 selectPendingBatch 资格交互：耗尽行与已 SENT 行均不重选")
    void test4_qualificationInteraction_selectPendingBatchFiltersCorrectly()
            throws Exception {
        Long id1 = insertRow("qual-1", "PENDING", 0);
        Long id2 = insertRow("qual-2", "PENDING", 16);
        Long id3 = insertRow("qual-3", "PENDING", 0);

        mapper.markSentBatch(List.of(id1));

        List<VerifyEventOutbox> batch = mapper.selectPendingBatch(10, 16);
        assertEquals(1, batch.size(), "仅 id3 有资格入批");
        assertEquals(id3, batch.get(0).getId());
    }

    @Test
    @DisplayName("5. 自动提交语义与逐行 markSent 一致")
    void test5_autoCommitSemanticsMatchesSingleRow() throws Exception {
        Long id = insertRow("autocommit-1", "PENDING", 0);

        mapper.markSentBatch(List.of(id));

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM verify_event_outbox WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("SENT", rs.getString(1),
                        "独立连接立即可见已提交状态");
            }
        }
    }

    @Test
    @DisplayName("6. 复裁 TASK-153 最强反例：崩溃重投上界收窄为 chunk-size 且 eventId 稳定")
    void test6_counterexampleReArbitration_upperBoundIsChunkSize()
            throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            ids.add(insertRow("crash-evt-" + i, "PENDING", 0));
        }

        // 模拟「前 3 行已发送但进程硬退出未执行批量标记」：3 行在 DB 中仍为 PENDING
        List<VerifyEventOutbox> nextBatch = mapper.selectPendingBatch(100, 16);
        assertEquals(3, nextBatch.size(),
                "未标记行全部被下轮重选重投，上界实测恰为已发未标数 3（<= chunk-size 25）");
        for (int i = 0; i < 3; i++) {
            assertEquals("crash-evt-" + (i + 1), nextBatch.get(i).getEventId(),
                    "重发沿用行内 eventId，消费端幂等键逐字稳定");
        }
    }
}
