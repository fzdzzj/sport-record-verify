package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.sportverify.verify.mapper.VerifyEventOutboxMarkSentProbeMysqlIT.MarkSentProbe;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.core.env.MapPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** TASK-148: restricted Spring/MyBatis-Plus starter slice against dedicated scratch MySQL; never a production plugin. */
class VerifyEventOutboxMarkSentSpringMysqlIT {
    private static final String SCHEMA = "task148_marksent_scratch";
    private static String url;
    private static String user;
    private static String password;
    private static ConfigurableApplicationContext baseline;
    private static ConfigurableApplicationContext probed;

    @SpringBootConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
    @MapperScan(basePackageClasses = VerifyEventOutboxMapper.class)
    static class Slice {
        @Bean
        @ConditionalOnProperty(name = "task148.probe.enabled", havingValue = "true")
        Interceptor task148OnlyProbe() {
            return new MarkSentProbe(); // reuse TASK-147's exact id-filtered test-only candidate
        }
    }

    @BeforeAll
    static void start() throws Exception {
        url = System.getenv("TASK148_IT_URL");
        user = System.getenv("TASK148_IT_USER");
        password = System.getenv("TASK148_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "scratch variables absent: skipped != real-DB success");
        Assumptions.assumeTrue(url.matches("^jdbc:mysql://[^/?]+/" + SCHEMA + "(?:\\?.*)?$"),
                "only the dedicated task148 scratch schema is permitted");
        try (Connection c = independent()) {
            assertEquals(SCHEMA, c.getCatalog(), "never run this test against demo verify_db");
            try (ResultSet r = c.getMetaData().getTables(SCHEMA, null, "verify_event_outbox", null)) {
                assertTrue(r.next(), "load the repository sql/03-verify-db.sql into scratch before running IT");
            }
        }
        baseline = context(false);
        try {
            probed = context(true);
        } catch (RuntimeException e) {
            baseline.close();
            baseline = null;
            throw e;
        }
    }

    private static ConfigurableApplicationContext context(boolean enabled) throws Exception {
        // No application.yml/config import: this slice may only wire Boot JDBC + the repository MP starter.
        // Default properties lose to application.yml; a highest-priority source pins every JDBC operation to scratch.
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "task148-scratch-only", Map.of("spring.datasource.url", url,
                "spring.datasource.username", user,
                "spring.datasource.password", password,
                "spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver",
                "spring.datasource.hikari.maximum-pool-size", "4",
                "task148.probe.enabled", String.valueOf(enabled))));
        ctx.register(Slice.class);
        ctx.refresh(); // selective Boot auto-config only; no SpringApplication/config-data/Nacos/bootstrap
        HikariDataSource pool = (HikariDataSource) ctx.getBean(javax.sql.DataSource.class);
        if (!url.equals(pool.getJdbcUrl())) {
            ctx.close();
            throw new IllegalStateException("Spring DataSource did not bind to TASK148 scratch; refusing Mapper calls");
        }
        try (Connection c = pool.getConnection()) {
            if (!SCHEMA.equals(c.getCatalog())) {
                ctx.close();
                throw new IllegalStateException("Spring Mapper pool does not point to TASK148 scratch");
            }
        }
        return ctx;
    }
    @AfterAll
    static void close() {
        MarkSentProbe.reset();
        if (probed != null) probed.close();
        if (baseline != null) baseline.close();
    }

    @BeforeEach
    void seed() throws Exception {
        MarkSentProbe.reset();
        // This schema belongs to TASK-148 only. Do not truncate or alter demo verify_db or TASK-147's schema.
        try (Connection c = independent(); Statement s = c.createStatement()) {
            s.executeUpdate("TRUNCATE TABLE verify_event_outbox");
            for (int i = 1; i <= 6; i++) {
                s.executeUpdate("INSERT INTO verify_event_outbox (event_id,topic,tag,payload,status,retry_count,created_at) "
                        + "VALUES ('task148-" + i + "','record-verify-events','VERIFIED','{}','PENDING',0,NOW())");
            }
        }
    }

    @Test
    void starterWiringIsSpringManagedAndIsolated() {
        for (ConfigurableApplicationContext ctx : List.of(baseline, probed)) {
            assertInstanceOf(HikariDataSource.class, ctx.getBean(javax.sql.DataSource.class));
            assertInstanceOf(SqlSessionTemplate.class, ctx.getBean(SqlSessionTemplate.class));
            assertSame(ctx.getBean(SqlSessionFactory.class), ctx.getBean(SqlSessionTemplate.class).getSqlSessionFactory());
            assertInstanceOf(SpringManagedTransactionFactory.class,
                    ctx.getBean(SqlSessionFactory.class).getConfiguration().getEnvironment().getTransactionFactory());
            assertInstanceOf(DataSourceTransactionManager.class, ctx.getBean(PlatformTransactionManager.class));
            assertNotNull(ctx.getBean(VerifyEventOutboxMapper.class));
            assertInstanceOf(MapperFactoryBean.class, ctx.getBean("&verifyEventOutboxMapper"));
            System.out.printf("TASK148_WIRING datasource=%s mapperFactory=%s template=%s sessionFactory=%s txFactory=%s txManager=%s pluginCount=%d%n",
                    ctx.getBean(javax.sql.DataSource.class).getClass().getName(),
                    ctx.getBean("&verifyEventOutboxMapper").getClass().getName(),
                    ctx.getBean(SqlSessionTemplate.class).getClass().getName(),
                    ctx.getBean(SqlSessionFactory.class).getClass().getName(),
                    ctx.getBean(SqlSessionFactory.class).getConfiguration().getEnvironment().getTransactionFactory().getClass().getName(),
                    ctx.getBean(PlatformTransactionManager.class).getClass().getName(),
                    ctx.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().size());
            assertTrue(ctx.getBeanFactory().getBeanDefinition("sqlSessionFactory").getResourceDescription()
                    .contains("MybatisPlusAutoConfiguration"), "starter must construct the factory");
        }
        assertEquals(0, baseline.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().size(),
                "no-plugin Spring baseline must exist");
        assertEquals(1, probed.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().size());
        assertSame(probed.getBean(Interceptor.class),
                probed.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().get(0));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void oneCallPairsAndIndependentConnectionSeesSameStateAsNoPlugin() throws Exception {
        MarkSentProbe.enableOnCurrentThread();
        long a = id(1), b = id(2);
        assertEquals(1, baseline.getBean(VerifyEventOutboxMapper.class).markSent(a));
        assertTrue(MarkSentProbe.drain().isEmpty(), "baseline has no plugin even while thread switch is on");
        assertEquals(1, probed.getBean(VerifyEventOutboxMapper.class).markSent(b));
        List<MarkSentProbe.Sample> samples = MarkSentProbe.drain();
        assertEquals(1, samples.size());
        assertEquals(MarkSentProbe.TARGET_ID, samples.get(0).statementId());
        assertEquals(1, samples.get(0).rows());
        assertFalse(samples.get(0).failed());
        assertTrue(samples.get(0).nanos() >= 0);
        assertEquals("SENT", state(a));
        assertEquals("SENT", state(b));
        assertTrue(sentAt(a));
        assertTrue(sentAt(b));
        assertEquals(0, active(baseline));
        assertEquals(0, active(probed));
    }

    @Test
    void disabledAndNonTargetProduceNoSamples() throws Exception {
        MarkSentProbe.disableOnCurrentThread();
        assertEquals(1, probed.getBean(VerifyEventOutboxMapper.class).markSent(id(1)));
        assertTrue(MarkSentProbe.drain().isEmpty());
        MarkSentProbe.enableOnCurrentThread();
        assertFalse(probed.getBean(VerifyEventOutboxMapper.class).selectPendingBatch(10, 16).isEmpty());
        assertEquals(1, probed.getBean(VerifyEventOutboxMapper.class).incrRetry(id(2)));
        assertTrue(MarkSentProbe.drain().isEmpty());
        assertEquals(0, active(probed));
    }

    @Test
    void consecutiveCallsDoNotCrossPair() throws Exception {
        MarkSentProbe.enableOnCurrentThread();
        for (int i = 1; i <= 3; i++) {
            assertEquals(1, probed.getBean(VerifyEventOutboxMapper.class).markSent(id(i)));
            if (i == 1) probed.getBean(VerifyEventOutboxMapper.class).selectPendingBatch(10, 16);
        }
        List<MarkSentProbe.Sample> samples = MarkSentProbe.drain();
        assertEquals(3, samples.size());
        assertTrue(samples.stream().allMatch(s -> !s.failed() && s.rows() == 1));
        for (int i = 1; i <= 3; i++) assertEquals("SENT", state(id(i)));
        assertEquals(0, active(probed));
    }

    @Test
    void realSqlFailureHasSameTranslatedTypeAndDoesNotLeak() throws Exception {
        long a = id(1), b = id(2);
        MarkSentProbe.enableOnCurrentThread();
        try (Connection c = independent(); Statement s = c.createStatement()) {
            s.executeUpdate("RENAME TABLE verify_event_outbox TO verify_event_outbox_task148_broken");
        }
        Throwable without, with;
        try {
            without = assertThrows(RuntimeException.class,
                    () -> baseline.getBean(VerifyEventOutboxMapper.class).markSent(a));
            assertTrue(MarkSentProbe.drain().isEmpty());
            with = assertThrows(RuntimeException.class,
                    () -> probed.getBean(VerifyEventOutboxMapper.class).markSent(a));
        } finally {
            restoreTable();
        }
        assertEquals(without.getClass(), with.getClass(), "Spring exception translation must not change");
        System.out.println("TASK148_FAILURE translatedType=" + with.getClass().getName());
        List<MarkSentProbe.Sample> failed = MarkSentProbe.drain();
        assertEquals(1, failed.size());
        assertTrue(failed.get(0).failed());
        assertEquals(-1, failed.get(0).rows());
        assertEquals(0, active(baseline));
        assertEquals(0, active(probed));
        assertEquals(1, probed.getBean(VerifyEventOutboxMapper.class).markSent(b));
        List<MarkSentProbe.Sample> next = MarkSentProbe.drain();
        assertEquals(1, next.size());
        assertFalse(next.get(0).failed());
        assertEquals("SENT", state(b));
        assertEquals(0, active(probed));
    }

    private static void restoreTable() throws Exception {
        try (Connection c = independent(); Statement s = c.createStatement()) {
            s.executeUpdate("RENAME TABLE verify_event_outbox_task148_broken TO verify_event_outbox");
        }
    }

    private static int active(ConfigurableApplicationContext ctx) {
        return ((HikariDataSource) ctx.getBean(javax.sql.DataSource.class))
                .getHikariPoolMXBean().getActiveConnections();
    }

    private static Connection independent() throws Exception {
        return DriverManager.getConnection(url, user, password);
    }

    private static long id(int offset) throws Exception {
        try (Connection c = independent(); PreparedStatement p = c.prepareStatement(
                "SELECT id FROM verify_event_outbox ORDER BY id LIMIT ?,1")) {
            p.setInt(1, offset - 1);
            try (ResultSet r = p.executeQuery()) {
                assertTrue(r.next());
                return r.getLong(1);
            }
        }
    }

    private static String state(long id) throws Exception {
        try (Connection c = independent(); PreparedStatement p = c.prepareStatement(
                "SELECT status FROM verify_event_outbox WHERE id=?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    private static boolean sentAt(long id) throws Exception {
        try (Connection c = independent(); PreparedStatement p = c.prepareStatement(
                "SELECT sent_at FROM verify_event_outbox WHERE id=?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                assertTrue(r.next());
                return r.getTimestamp(1) != null;
            }
        }
    }
}
