package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.sportverify.verify.mapper.VerifyEventOutboxMarkSentProbeMysqlIT.MarkSentProbe;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-149：Spring 管理路径下「外层 Mapper 调用墙钟」与「内层整个
 * {@code StatementHandler.update} 调用墙钟」的<strong>同调用逐次配对</strong>成本判别。
 *
 * <p>要回答的问题：在 TASK-148 已证明的受限 Spring 切片（{@code MybatisPlusAutoConfiguration} 自动装配、
 * Spring 注入真实 Mapper、{@code SqlSessionTemplate}/{@code SpringManagedTransactionFactory}、
 * Hikari、调用者无 {@code @Transactional}）里，能否对 <strong>同一次</strong> {@code markSent} 调用，
 * 在<strong>同一线程</strong>逐次同时取得外层墙钟与内层读数、逐次求「外层减内层」残余，且不改变语义。
 * 探针<strong>只沿用</strong> TASK-147 的同一种 test-only、按 {@code MappedStatement.id} 精确限定的
 * {@link MarkSentProbe}；未新增第二种探针、未注册生产插件链、未替换生产 DataSource。</p>
 *
 * <p><b>残余语义（不得命名拆项）</b>：只对成功配对的同一调用计算 {@code outer - inner}。该残余混合
 * 连接获取、prepare/绑定、MyBatis/Spring 会话与代理调用、以及自动提交相关的 commit/close 等，
 * <strong>不得</strong>命名为连接获取、prepare、提交或纯服务端 SQL 中的任何单项；也不得用两组独立 P50 相减代替。</p>
 *
 * <p><b>有界小样本</b>：预热 + 3 轮受测（每轮 100 次目标调用），另加 1 轮<strong>无插件基线</strong>外层对照；
 * 不含 c100×2000、无常驻服务大负载。</p>
 *
 * <p><b>需要真 MySQL，默认不参与常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集；只有 {@code -Dtest=}
 * 显式指定才跑），并要求三个环境变量，缺任一即 assume 跳过（跳过不计为真库通过）：
 * {@code TASK149_IT_URL} / {@code TASK149_IT_USER} / {@code TASK149_IT_PASSWORD}，且库名须为专用
 * {@code task149_marksent_scratch}（不得连演示 {@code verify_db}）。</p>
 */
class VerifyEventOutboxMarkSentSpringPairedCostMysqlIT {

    private static final String SCHEMA = "task149_marksent_scratch";
    private static final int WARMUP = 30;
    private static final int MEASURED = 100;
    private static final int ROUNDS = 3;

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
        @ConditionalOnProperty(name = "task149.probe.enabled", havingValue = "true")
        Interceptor task149OnlyProbe() {
            return new MarkSentProbe(); // 复用 TASK-147 的同一种、按 MappedStatement.id 限定的 test-only 探针
        }
    }

    @BeforeAll
    static void start() throws Exception {
        url = System.getenv("TASK149_IT_URL");
        user = System.getenv("TASK149_IT_USER");
        password = System.getenv("TASK149_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "缺 TASK149_IT_URL/USER/PASSWORD，跳过 scratch MySQL 配对成本试验（不视为通过）");
        Assumptions.assumeTrue(url.matches("^jdbc:mysql://[^/?]+/" + SCHEMA + "(?:\\?.*)?$"),
                "只允许专用 task149 scratch schema");
        try (Connection c = independent()) {
            assertEquals(SCHEMA, c.getCatalog(), "不得对演示 verify_db 运行本测试");
            try (ResultSet r = c.getMetaData().getTables(SCHEMA, null, "verify_event_outbox", null)) {
                assertTrue(r.next(), "先用仓库 sql/03-verify-db.sql 机械改库名灌入 scratch");
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
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "task149-scratch-only", Map.of("spring.datasource.url", url,
                "spring.datasource.username", user,
                "spring.datasource.password", password,
                "spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver",
                "spring.datasource.hikari.maximum-pool-size", "4",
                "spring.datasource.hikari.pool-name", "task149-spring-it-" + enabled,
                "task149.probe.enabled", String.valueOf(enabled))));
        ctx.register(Slice.class);
        ctx.refresh();
        HikariDataSource pool = (HikariDataSource) ctx.getBean(javax.sql.DataSource.class);
        if (!url.equals(pool.getJdbcUrl())) {
            ctx.close();
            throw new IllegalStateException("Spring DataSource 未绑定到 TASK149 scratch，拒绝 Mapper 调用");
        }
        try (Connection c = pool.getConnection()) {
            if (!SCHEMA.equals(c.getCatalog())) {
                ctx.close();
                throw new IllegalStateException("Spring Mapper 池未指向 TASK149 scratch");
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
    void resetProbeScope() {
        MarkSentProbe.reset();
    }

    // ---------------------------------------------------------------- 装配与探针范围

    @Test
    void wiringIsSpringManaged_andProbeOnlyInProbedContext() {
        for (ConfigurableApplicationContext ctx : List.of(baseline, probed)) {
            assertInstanceOf(HikariDataSource.class, ctx.getBean(javax.sql.DataSource.class));
            assertInstanceOf(SqlSessionTemplate.class, ctx.getBean(SqlSessionTemplate.class));
            assertSame(ctx.getBean(SqlSessionFactory.class), ctx.getBean(SqlSessionTemplate.class).getSqlSessionFactory());
            assertInstanceOf(SpringManagedTransactionFactory.class,
                    ctx.getBean(SqlSessionFactory.class).getConfiguration().getEnvironment().getTransactionFactory());
            assertNotNull(ctx.getBean(VerifyEventOutboxMapper.class));
            System.out.println("TASK149_WIRING datasource=" + ctx.getBean(javax.sql.DataSource.class).getClass().getName()
                    + " template=" + ctx.getBean(SqlSessionTemplate.class).getClass().getName()
                    + " sessionFactory=" + ctx.getBean(SqlSessionFactory.class).getClass().getName()
                    + " txFactory=" + ctx.getBean(SqlSessionFactory.class).getConfiguration()
                            .getEnvironment().getTransactionFactory().getClass().getName()
                    + " mapperBean=" + ctx.getBean(VerifyEventOutboxMapper.class).getClass().getName()
                    + " pluginCount=" + ctx.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().size());
        }
        assertEquals(0, baseline.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().size(),
                "无插件基线不得注册任何 MyBatis 插件");
        assertEquals(1, probed.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().size(),
                "有插件切片只注册 TASK-147 的一个目标限定探针");
        assertSame(probed.getBean(Interceptor.class),
                probed.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().get(0));
    }

    // ---------------------------------------------------------------- 同调用逐次配对测量

    @Test
    void pairedCost_threeRounds_withWarmup_andNoMissingOrMultiPair() throws Exception {
        long[] roundResidualP50 = new long[ROUNDS];
        long[] roundOuterP50 = new long[ROUNDS];
        long[] roundInnerP50 = new long[ROUNDS];
        for (int r = 1; r <= ROUNDS; r++) {
            RoundStats s = measureProbedRound(r);
            assertEquals(0, s.missing(), "第 " + r + " 轮出现缺配（外层调用未配到内层样本）");
            assertEquals(0, s.multi(), "第 " + r + " 轮出现多配（一次外层调用配到多条内层样本）");
            assertEquals(MEASURED, s.n(), "第 " + r + " 轮受测样本数不足");
            assertEquals(0, s.negative(), "内层墙钟不应大于其所属外层墙钟（残余不得为负）");
            roundResidualP50[r - 1] = s.residualP50();
            roundOuterP50[r - 1] = s.outerP50();
            roundInnerP50[r - 1] = s.innerP50();
        }
        System.out.println("TASK149_SUMMARY rounds=" + ROUNDS
                + " residual_p50_us=" + Arrays.toString(usArray(roundResidualP50))
                + " outer_p50_us=" + Arrays.toString(usArray(roundOuterP50))
                + " inner_p50_us=" + Arrays.toString(usArray(roundInnerP50)));
    }

    @Test
    void baselineOuterControl_noProbe() throws Exception {
        RoundStats s = measureBaseline();
        assertEquals(MEASURED, s.n(), "无插件对照样本数不足");
        System.out.println("TASK149_BASELINE n=" + s.n()
                + " outer_p50_us=" + us(s.outerP50())
                + " outer_p95_us=" + us(s.outerP95()));
    }

    // ---------------------------------------------------------------- 语义不变

    @Test
    void semanticsUnchanged_underPairedProbe() throws Exception {
        long[] two = seedIds(2);
        long a = two[0];
        long b = two[1];

        // 目标调用最终状态 / sent_at / 独立连接可见性。
        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();
        long t0 = System.nanoTime();
        int rows = probed.getBean(VerifyEventOutboxMapper.class).markSent(a);
        long outer = System.nanoTime() - t0;
        List<MarkSentProbe.Sample> samples = MarkSentProbe.drain();
        assertEquals(1, rows);
        assertEquals(1, samples.size());
        assertTrue(outer >= samples.get(0).nanos(), "内层必须落在外层窗口内");
        assertEquals("SENT", state(a));
        assertNotNull(sentAt(a), "sent_at 须置位");
        assertEquals("SENT", state(a), "独立连接须立即看见已提交状态");
        assertEquals(0, active(probed), "调用返回后不得残留占用连接");

        // 真实 SQL 失败：异常类型有无探针一致，失败样本一条且不污染下一次调用。
        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();
        try (Connection c = independent(); Statement st = c.createStatement()) {
            st.executeUpdate("RENAME TABLE verify_event_outbox TO verify_event_outbox_task149_broken");
        }
        Throwable withoutProbe;
        Throwable withProbe;
        try {
            withoutProbe = assertThrows(RuntimeException.class,
                    () -> baseline.getBean(VerifyEventOutboxMapper.class).markSent(b));
            withProbe = assertThrows(RuntimeException.class,
                    () -> probed.getBean(VerifyEventOutboxMapper.class).markSent(b));
        } finally {
            try (Connection c = independent(); Statement st = c.createStatement()) {
                st.executeUpdate("RENAME TABLE verify_event_outbox_task149_broken TO verify_event_outbox");
            }
        }
        assertEquals(withoutProbe.getClass(), withProbe.getClass(), "探针不得改变 Spring 路径下的异常类型");
        List<MarkSentProbe.Sample> failed = MarkSentProbe.drain();
        assertEquals(1, failed.size(), "失败的目标调用应留下一条失败样本");
        assertTrue(failed.get(0).failed());
        assertEquals(-1, failed.get(0).rows());
        assertEquals(0, active(baseline));
        assertEquals(0, active(probed));

        MarkSentProbe.clear();
        assertEquals(1, probed.getBean(VerifyEventOutboxMapper.class).markSent(b));
        List<MarkSentProbe.Sample> afterRecovery = MarkSentProbe.drain();
        assertEquals(1, afterRecovery.size(), "失败后下一次调用只应留自身一条样本");
        assertFalse(afterRecovery.get(0).failed());
        assertEquals("SENT", state(b));
        assertEquals(0, active(probed));
    }

    // ---------------------------------------------------------------- 测量内核

    private RoundStats measureProbedRound(int round) throws Exception {
        long[] ids = seedIds(WARMUP + MEASURED);
        HikariDataSource ds = (HikariDataSource) probed.getBean(javax.sql.DataSource.class);
        VerifyEventOutboxMapper mapper = probed.getBean(VerifyEventOutboxMapper.class);
        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();
        for (int i = 0; i < WARMUP; i++) { // 预热：读数丢弃，只让池/JIT 到位
            mapper.markSent(ids[i]);
            MarkSentProbe.drain();
        }
        int activeBefore = ds.getHikariPoolMXBean().getActiveConnections();
        long gc0 = gcCount();
        long gcT0 = gcTimeMs();
        long cpu0 = cpuNanos();

        long[] outer = new long[MEASURED];
        long[] inner = new long[MEASURED];
        long[] residual = new long[MEASURED];
        int missing = 0;
        int multi = 0;
        int negative = 0;
        for (int i = 0; i < MEASURED; i++) {
            long id = ids[WARMUP + i];
            long t0 = System.nanoTime();
            int rows = mapper.markSent(id);
            outer[i] = System.nanoTime() - t0;
            List<MarkSentProbe.Sample> s = MarkSentProbe.drain();
            if (rows != 1 || s.size() != 1 || s.get(0).failed()) {
                if (s.isEmpty()) {
                    missing++;
                } else if (s.size() > 1) {
                    multi++;
                }
                inner[i] = -1;
                residual[i] = -1;
                continue;
            }
            inner[i] = s.get(0).nanos();
            residual[i] = outer[i] - inner[i];
            if (residual[i] < 0) {
                negative++;
            }
            System.out.println("TASK149_RAW round=" + round + " i=" + i
                    + " outer_us=" + us(outer[i]) + " inner_us=" + us(inner[i])
                    + " residual_us=" + us(residual[i]) + " rows=" + rows + " pairs=" + s.size());
        }
        int activeAfter = ds.getHikariPoolMXBean().getActiveConnections();
        RoundStats stats = new RoundStats(round, MEASURED, missing, multi, negative,
                percentile(outer, 0.50), percentile(outer, 0.95), percentile(outer, 0.99),
                percentile(inner, 0.50), percentile(inner, 0.95), percentile(inner, 0.99),
                percentile(residual, 0.50), percentile(residual, 0.95), percentile(residual, 0.99),
                activeBefore, activeAfter, gcCount() - gc0, gcTimeMs() - gcT0,
                cpu0 < 0 ? -1 : cpuNanos() - cpu0);
        System.out.println(stats.line());
        return stats;
    }

    private RoundStats measureBaseline() throws Exception {
        long[] ids = seedIds(WARMUP + MEASURED);
        HikariDataSource ds = (HikariDataSource) baseline.getBean(javax.sql.DataSource.class);
        VerifyEventOutboxMapper mapper = baseline.getBean(VerifyEventOutboxMapper.class);
        // 无插件基线：即使线程开关打开也不应产生读数。
        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();
        for (int i = 0; i < WARMUP; i++) {
            mapper.markSent(ids[i]);
        }
        assertTrue(MarkSentProbe.drain().isEmpty(), "无插件基线上下文不得产出任何读数");
        int activeBefore = ds.getHikariPoolMXBean().getActiveConnections();
        long[] outer = new long[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            long t0 = System.nanoTime();
            mapper.markSent(ids[WARMUP + i]);
            outer[i] = System.nanoTime() - t0;
        }
        int activeAfter = ds.getHikariPoolMXBean().getActiveConnections();
        return new RoundStats(0, outer.length, 0, 0, 0,
                percentile(outer, 0.50), percentile(outer, 0.95), percentile(outer, 0.99),
                -1, -1, -1, -1, -1, -1, activeBefore, activeAfter, 0, 0, -1);
    }

    private static long us(long nanos) {
        return nanos < 0 ? -1 : nanos / 1000;
    }

    /** 把纳秒数组逐项折算为微秒，避免 SUMMARY 行标签与单位不一致。 */
    private static long[] usArray(long[] nanos) {
        long[] out = new long[nanos.length];
        for (int i = 0; i < nanos.length; i++) {
            out[i] = us(nanos[i]);
        }
        return out;
    }

    /** 最近秩分位数；空数组返回 -1。 */
    private static long percentile(long[] values, double p) {
        long[] v = new long[values.length];
        int k = 0;
        for (long x : values) {
            if (x >= 0) {
                v[k++] = x;
            }
        }
        if (k == 0) {
            return -1;
        }
        long[] sorted = Arrays.copyOf(v, k);
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p * k) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= k) {
            idx = k - 1;
        }
        return sorted[idx];
    }

    private static long gcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (b.getCollectionCount() > 0) {
                c += b.getCollectionCount();
            }
        }
        return c;
    }

    private static long gcTimeMs() {
        long t = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (b.getCollectionTime() > 0) {
                t += b.getCollectionTime();
            }
        }
        return t;
    }

    private static long cpuNanos() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            return sun.getProcessCpuTime();
        }
        return -1;
    }

    // ---------------------------------------------------------------- 真库基础设施（不触碰演示库）

    /** TRUNCATE + 插入 n 条 PENDING 行，返回按 id 升序排列的 id 数组。 */
    private static long[] seedIds(int n) throws Exception {
        try (Connection c = independent(); Statement s = c.createStatement()) {
            s.executeUpdate("TRUNCATE TABLE verify_event_outbox");
            StringBuilder sb = new StringBuilder(
                    "INSERT INTO verify_event_outbox (event_id,topic,tag,payload,status,retry_count,created_at) VALUES ");
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("('task149-").append(i)
                        .append("','record-verify-events','VERIFIED','{}','PENDING',0,NOW())");
            }
            s.executeUpdate(sb.toString());
        }
        long[] ids = new long[n];
        int k = 0;
        try (Connection c = independent(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id FROM verify_event_outbox WHERE status='PENDING' ORDER BY id")) {
            while (rs.next()) {
                assertTrue(k < n, "PENDING 行数多于请求");
                ids[k++] = rs.getLong(1);
            }
        }
        assertEquals(n, k, "预置 PENDING 行数不足");
        return ids;
    }

    private static int active(ConfigurableApplicationContext ctx) {
        return ((HikariDataSource) ctx.getBean(javax.sql.DataSource.class))
                .getHikariPoolMXBean().getActiveConnections();
    }

    private static Connection independent() throws Exception {
        return java.sql.DriverManager.getConnection(url, user, password);
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

    private static Object sentAt(long id) throws Exception {
        try (Connection c = independent(); PreparedStatement p = c.prepareStatement(
                "SELECT sent_at FROM verify_event_outbox WHERE id=?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                assertTrue(r.next());
                return r.getObject(1);
            }
        }
    }

    /** 一轮读数：n/缺配/多配/负残余 + 外层/内层/残余分位数（微秒）+ 池水位与 GC/CPU 增量。 */
    record RoundStats(int round, int n, int missing, int multi, int negative,
                      long outerP50, long outerP95, long outerP99,
                      long innerP50, long innerP95, long innerP99,
                      long residualP50, long residualP95, long residualP99,
                      int activeBefore, int activeAfter,
                      long gcCountDelta, long gcTimeMsDelta, long cpuNanosDelta) {

        String line() {
            return "TASK149_ROUND round=" + round + " n=" + n + " missing=" + missing + " multi=" + multi
                    + " negative=" + negative
                    + " outer_p50_us=" + us(outerP50) + " outer_p95_us=" + us(outerP95) + " outer_p99_us=" + us(outerP99)
                    + " inner_p50_us=" + us(innerP50) + " inner_p95_us=" + us(innerP95) + " inner_p99_us=" + us(innerP99)
                    + " residual_p50_us=" + us(residualP50) + " residual_p95_us=" + us(residualP95)
                    + " residual_p99_us=" + us(residualP99)
                    + " active_before=" + activeBefore + " active_after=" + activeAfter
                    + " gc_count_delta=" + gcCountDelta + " gc_time_ms_delta=" + gcTimeMsDelta
                    + " cpu_ns_delta=" + cpuNanosDelta;
        }
    }
}