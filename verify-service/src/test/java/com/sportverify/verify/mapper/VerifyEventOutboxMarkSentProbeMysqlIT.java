package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * markSent 下层计时的<strong>测试专用、目标调用限定</strong>候选接线判别（TASK-147）。
 *
 * <p>要回答的问题不是「markSent 为什么慢」，而是更小的一个：在<strong>真 MySQL + 本仓真实 Mapper
 * 装配</strong>里，能否把一次 {@code markSent} 调用与它下面某一层的客户端墙钟<strong>一一配对</strong>，
 * 且不改变语义。候选只有一条：按 {@code MappedStatement.id} 限定的 MyBatis {@code StatementHandler.update}
 * 插件（{@link MarkSentProbe}），只注册在本测试自建的 {@link SqlSessionFactory} 上——
 * <strong>不</strong>接生产配置、<strong>不</strong>替换生产 DataSource、不写高基数/敏感日志。</p>
 *
 * <p><b>可测边界</b>：目标 statement 的<strong>客户端 JDBC 调用墙钟</strong>，即
 * {@code PreparedStatement.execute()} + {@code getUpdateCount()} 这段（含客户端 JDBC 处理、网络往返、
 * 服务端 UPDATE 与自动提交的隐式 commit）。<b>不可测</b>：连接获取（Hikari 取连接在
 * {@code update} 之前完成）、参数绑定/准备（在 {@code prepare} 阶段）、显式 {@code commit}（
 * {@code SqlSession.commit}）、以及服务端 SQL 执行与网络往返的<strong>拆分</strong>——本任务不得
 * 把该值称为纯服务端 SQL、纯 fsync 或纯池等待。</p>
 *
 * <p><b>需要真 MySQL，默认不参与常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集，
 * 只有 {@code -Dtest=} 显式指定才跑），并要求三个环境变量，缺任一即 assume 跳过：
 * {@code TASK147_IT_URL} / {@code TASK147_IT_USER} / {@code TASK147_IT_PASSWORD}。</p>
 *
 * <p>准备库（跑完可 DROP）：把仓库里的 {@code sql/03-verify-db.sql} 机械改名灌进 scratch 库，
 * 因此验的就是提交里那份 DDL 本身，不是副本：
 * <pre>
 * sed 's/verify_db/task147_marksent_scratch/g' sql/03-verify-db.sql \
 *   | docker exec -i task131-scratch-mysql mysql -uroot -proot
 * </pre>
 * 运行（仍走仓库唯一入口，用 {@code MAVEN_ARGS} 注入目标类选择器）：
 * <pre>
 * MAVEN_ARGS="-Dtest=VerifyEventOutboxMarkSentProbeMysqlIT -Dsurefire.failIfNoSpecifiedTests=false" \
 *   bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test
 * </pre>
 * </p>
 *
 * <p><b>反例（若成立即停止该候选）</b>：目标调用与读数无法一一配对；非目标调用混入样本；开关关闭仍有读数；
 * 失败后作用域污染后续样本；异常类型/更新行数/最终状态/auto-commit 可见性/连接释放与无探针基线不一致。
 * 本类小样本只证明「此层可配对测量」，<strong>不</strong>等于已定位生产瓶颈或证明吞吐收益。</p>
 */
class VerifyEventOutboxMarkSentProbeMysqlIT {

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TASK147_IT_URL");
        String user = System.getenv("TASK147_IT_USER");
        String password = System.getenv("TASK147_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "缺 TASK147_IT_URL/USER/PASSWORD 环境变量，跳过 scratch MySQL 接线试验（不视为通过）");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("task147-marksent-it");
        dataSource = new HikariDataSource(cfg);

        // 真实 DDL 建出的 scratch 表；只在本进程内重置数据行，不触碰 demo/verify_db。
        exec("TRUNCATE TABLE verify_event_outbox");
        exec("INSERT INTO verify_event_outbox (event_id, topic, tag, payload, trace_id, status, retry_count, created_at) VALUES "
                + "('task147-evt-1','record-verify-events','VERIFIED','{}',NULL,'PENDING',0,NOW()),"
                + "('task147-evt-2','record-verify-events','REJECTED','{}',NULL,'PENDING',0,NOW()),"
                + "('task147-evt-3','record-verify-events','VERIFIED','{}',NULL,'PENDING',0,NOW()),"
                + "('task147-evt-4','record-verify-events','REJECTED','{}',NULL,'PENDING',0,NOW()),"
                + "('task147-evt-5','record-verify-events','VERIFIED','{}',NULL,'PENDING',0,NOW()),"
                + "('task147-evt-6','record-verify-events','REJECTED','{}',NULL,'PENDING',0,NOW())");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("scratch", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(VerifyEventOutboxMapper.class);
        // 探针只挂在这台测试专用 factory 上：生产配置、生产 DataSource、生产插件链都不动。
        configuration.addInterceptor(new MarkSentProbe());
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        MarkSentProbe.reset();
    }

    @AfterEach
    void tearDown() {
        MarkSentProbe.reset();
        if (dataSource != null) {
            // 兜底：若失败路径中断在改名中途，恢复 scratch 表名，避免污染后续用例。
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("RENAME TABLE verify_event_outbox_probe_broken TO verify_event_outbox");
            } catch (Exception ignored) {
                // 通常不存在该临时名（正常路径已恢复），忽略。
            }
            dataSource.close();
        }
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void targetCall_pairsExactlyOneLowerSample_andStateMatchesBaseline() throws Exception {
        long id = idAt(1);

        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();
        int rows;
        try (SqlSession session = factory.openSession(true)) {
            rows = session.getMapper(VerifyEventOutboxMapper.class).markSent(id);
        }
        List<MarkSentProbe.Sample> samples = MarkSentProbe.drain();

        assertEquals(1, rows, "markSent 命中一行 PENDING，应返回更新行数 1");
        assertEquals(1, samples.size(), "一次目标调用应恰好配到一条下层样本");
        MarkSentProbe.Sample s = samples.get(0);
        assertEquals(MarkSentProbe.TARGET_ID, s.statementId(), "样本须来自 markSent 的目标 statement id");
        assertFalse(s.failed(), "成功调用不得标记为失败");
        assertEquals(rows, s.rows(), "样本内更新行数须与 markSent 返回值一致");
        assertTrue(s.nanos() >= 0, "客户端 JDBC 调用墙钟不得为负");
        assertEquals("SENT", statusOf(id), "目标行须按原 SQL 从 PENDING 变 SENT");
        assertTrue(sentAtOf(id) != null, "sent_at 须被 SQL 置为当前时间");

        // 关掉探针再跑一次等价调用：更新行数与最终状态须与有探针时一致，且不产生读数。
        long id2 = idAt(1);
        MarkSentProbe.disableOnCurrentThread();
        MarkSentProbe.clear();
        int baseRows;
        try (SqlSession session = factory.openSession(true)) {
            baseRows = session.getMapper(VerifyEventOutboxMapper.class).markSent(id2);
        }
        assertEquals(rows, baseRows, "有无探针的更新行数须一致");
        assertEquals("SENT", statusOf(id2), "有无探针的最终状态须一致");
        assertEquals(0, MarkSentProbe.drain().size(), "探针关闭时不得产出任何读数");
    }

    @Test
    void nonTargetCalls_recordNoSample() throws Exception {
        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();

        try (SqlSession session = factory.openSession(true)) {
            List<?> pending = session.getMapper(VerifyEventOutboxMapper.class).selectPendingBatch(10, 16);
            assertFalse(pending.isEmpty(), "取批应命中预置 PENDING 行");
            int incr = session.getMapper(VerifyEventOutboxMapper.class).incrRetry(idAt(1));
            assertEquals(1, incr, "incrRetry 命中一行");
        }

        assertEquals(0, MarkSentProbe.drain().size(),
                "非目标 Mapper（selectPendingBatch/incrRetry）不得进入样本队列，避免串样本");
    }

    @Test
    void consecutiveCalls_pairInOrder_andInterleavedNonTargetDoesNotInsertSample() throws Exception {
        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();

        long a = idAt(1);
        long b = idAt(2);
        long c = idAt(3);
        try (SqlSession session = factory.openSession(true)) {
            VerifyEventOutboxMapper mapper = session.getMapper(VerifyEventOutboxMapper.class);
            assertEquals(1, mapper.markSent(a));
            mapper.selectPendingBatch(5, 16); // 夹一次非目标调用
            assertEquals(1, mapper.markSent(b));
            assertEquals(1, mapper.markSent(c));
        }

        List<MarkSentProbe.Sample> samples = MarkSentProbe.drain();
        assertEquals(3, samples.size(), "连续三次目标调用应配到三条样本（中间非目标调用不入队）");
        for (MarkSentProbe.Sample s : samples) {
            assertEquals(MarkSentProbe.TARGET_ID, s.statementId());
            assertFalse(s.failed());
            assertEquals(1, s.rows());
        }
        assertArrayEquals(new String[]{"SENT", "SENT", "SENT"},
                new String[]{statusOf(a), statusOf(b), statusOf(c)}, "三行都须投递成功标记为 SENT");
    }

    @Test
    void autoCommitVisibility_isUnchangedByProbe() throws Exception {
        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();
        long id = idAt(1);

        // 会话仍打开、未调用 commit 时，用另一条连接读：auto-commit 应在 execute 时即已提交可见。
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(1, session.getMapper(VerifyEventOutboxMapper.class).markSent(id));
            assertEquals("SENT", statusFromSeparateConnection(id),
                    "openSession(true) 下 markSent 应在另一连接上立即可见（auto-commit 语义不变）");
        }
        assertEquals(1, MarkSentProbe.drain().size());

        // 无探针基线同验一次，语义须一致。
        MarkSentProbe.disableOnCurrentThread();
        MarkSentProbe.clear();
        long id2 = idAt(1);
        try (SqlSession session = factory.openSession(true)) {
            assertEquals(1, session.getMapper(VerifyEventOutboxMapper.class).markSent(id2));
            assertEquals("SENT", statusFromSeparateConnection(id2), "无探针基线的 auto-commit 可见性须一致");
        }
        assertEquals(0, MarkSentProbe.drain().size());
    }

    @Test
    void failedTargetCall_propagatesSameException_recordsOneSample_andCleansScope() throws Exception {
        long id = idAt(1);

        // 无探针基线：把目标表临时改名，制造真实 SQL 失败，记录异常类型（id 已在改名之前取好）。
        MarkSentProbe.disableOnCurrentThread();
        MarkSentProbe.clear();
        renameOutbox("verify_event_outbox", "verify_event_outbox_probe_broken");
        Class<? extends Throwable> baselineType;
        try {
            baselineType = assertThrows(Throwable.class, () -> {
                try (SqlSession session = factory.openSession(true)) {
                    session.getMapper(VerifyEventOutboxMapper.class).markSent(id);
                }
            }).getClass();
        } finally {
            renameOutbox("verify_event_outbox_probe_broken", "verify_event_outbox");
        }
        assertEquals(0, MarkSentProbe.drain().size(), "探针关闭时失败调用不得产出读数");

        // 探针开启：同一真实失败须抛出同类型异常，并留下恰好一条失败样本、作用域随即清理。
        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();
        renameOutbox("verify_event_outbox", "verify_event_outbox_probe_broken");
        Throwable probed;
        try {
            probed = assertThrows(Throwable.class, () -> {
                try (SqlSession session = factory.openSession(true)) {
                    session.getMapper(VerifyEventOutboxMapper.class).markSent(id);
                }
            });
        } finally {
            renameOutbox("verify_event_outbox_probe_broken", "verify_event_outbox");
        }
        assertEquals(baselineType, probed.getClass(), "探针不得改变异常类型");
        List<MarkSentProbe.Sample> failedSamples = MarkSentProbe.drain();
        assertEquals(1, failedSamples.size(), "失败的目标调用应留下一条失败样本");
        assertTrue(failedSamples.get(0).failed(), "该样本须标记为失败");
        assertEquals(-1, failedSamples.get(0).rows(), "失败时无更新行数");

        // 表恢复后紧接着一次成功调用：须恰好一条干净样本，不被前一次失败污染。
        MarkSentProbe.clear();
        int rows;
        try (SqlSession session = factory.openSession(true)) {
            rows = session.getMapper(VerifyEventOutboxMapper.class).markSent(id);
        }
        List<MarkSentProbe.Sample> afterRecovery = MarkSentProbe.drain();
        assertEquals(1, rows);
        assertEquals(1, afterRecovery.size(), "失败后的下一次调用应只有自身一条样本（作用域已清理）");
        assertFalse(afterRecovery.get(0).failed(), "恢复后的调用不得残留上一次失败标记");
        assertEquals(1, afterRecovery.get(0).rows());
        assertEquals("SENT", statusOf(id));
    }

    @Test
    void connectionRelease_matchesBaseline() throws Exception {
        // 先借还一条连接，使 HikariPoolMXBean 可用（池启动后才非空）。
        try (Connection c = dataSource.getConnection()) {
            assertTrue(c.isValid(2), "scratch 连接应可用");
        }
        assertEquals(0, pool().getActiveConnections(), "借还后不得残留占用连接");

        MarkSentProbe.enableOnCurrentThread();
        MarkSentProbe.clear();
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(VerifyEventOutboxMapper.class).markSent(idAt(1));
        }
        assertEquals(0, pool().getActiveConnections(), "有探针时会话关闭后不得残留占用连接");
        int totalWithProbe = pool().getTotalConnections();

        MarkSentProbe.disableOnCurrentThread();
        try (SqlSession session = factory.openSession(true)) {
            session.getMapper(VerifyEventOutboxMapper.class).markSent(idAt(1));
        }
        assertEquals(0, pool().getActiveConnections(), "无探针基线同样不得残留占用连接");
        assertEquals(totalWithProbe, pool().getTotalConnections(),
                "有无探针的连接释放行为须一致（不因探针多占或少还连接）");
    }

    // ---------------------------------------------------------------- 基础设施

    private HikariPoolMXBean pool() {
        return dataSource.getHikariPoolMXBean();
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private long idAt(int index) throws Exception {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT id FROM verify_event_outbox WHERE status = 'PENDING' ORDER BY id LIMIT "
                             + index + ",1")) {
            assertTrue(rs.next(), "预置 PENDING 行不足，取第 " + index + " 行失败");
            return rs.getLong(1);
        }
    }

    private String statusOf(long id) throws Exception {
        return statusFrom(id, dataSource.getConnection());
    }

    private String statusFromSeparateConnection(long id) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            return statusFrom(id, c);
        }
    }

    private String statusFrom(long id, Connection c) throws Exception {
        try (c; Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT status FROM verify_event_outbox WHERE id = " + id)) {
            assertTrue(rs.next(), "目标行应存在：id=" + id);
            return rs.getString(1);
        }
    }

    private Object sentAtOf(long id) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT sent_at FROM verify_event_outbox WHERE id = " + id)) {
            assertTrue(rs.next());
            return rs.getObject(1);
        }
    }

    private void renameOutbox(String from, String to) throws Exception {
        exec("RENAME TABLE " + from + " TO " + to);
    }

    /**
     * 测试专用探针：按 {@code MappedStatement.id} 限定的 {@code StatementHandler.update} 拦截器。
     *
     * <p>配对办法：目标 statement 每次 {@code update} 恰好入队一条样本，样本槽是
     * <strong>线程本地</strong>（测试同线程调用，故一一配对）；非目标 statement 不入队；探针关闭时
     * 不做任何采样。客户端边界 = {@code PreparedStatement.execute()} + {@code getUpdateCount()}；
     * 连接获取、参数绑定/准备、显式提交与服务端 SQL/网络往返拆分都在该层之外，记未知。</p>
     */
    @Intercepts(@Signature(type = StatementHandler.class, method = "update", args = {Statement.class}))
    static final class MarkSentProbe implements Interceptor {

        /** 目标 MappedStatement id（与 {@code @Update} 注解方法同源）。 */
        static final String TARGET_ID = "com.sportverify.verify.mapper.VerifyEventOutboxMapper.markSent";

        private static final ThreadLocal<Boolean> ENABLED = ThreadLocal.withInitial(() -> Boolean.FALSE);
        private static final ThreadLocal<Deque<Sample>> SAMPLES = ThreadLocal.withInitial(ArrayDeque::new);

        /** 一条下层样本：目标 statement id + 客户端 JDBC 调用墙钟 + 更新行数 + 是否失败。 */
        record Sample(String statementId, long nanos, int rows, boolean failed) {}

        static void enableOnCurrentThread() {
            ENABLED.set(Boolean.TRUE);
        }

        static void disableOnCurrentThread() {
            ENABLED.set(Boolean.FALSE);
        }

        static void clear() {
            SAMPLES.get().clear();
        }

        static List<Sample> drain() {
            List<Sample> out = new ArrayList<>(SAMPLES.get());
            SAMPLES.get().clear();
            return out;
        }

        static void reset() {
            ENABLED.remove();
            SAMPLES.remove();
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (!Boolean.TRUE.equals(ENABLED.get())) {
                return invocation.proceed(); // 关闭：零采样
            }
            MetaObject metaObject = SystemMetaObject.forObject(invocation.getTarget());
            MappedStatement ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
            if (!TARGET_ID.equals(ms.getId())) {
                return invocation.proceed(); // 非目标：不入队，避免串样本
            }
            long start = System.nanoTime();
            int rows = -1;
            boolean failed = false;
            try {
                Object result = invocation.proceed();
                rows = (Integer) result;
                return result;
            } catch (Throwable t) {
                failed = true;
                throw t;
            } finally {
                SAMPLES.get().addLast(new Sample(TARGET_ID, System.nanoTime() - start, rows, failed));
            }
        }
    }

    private HikariDataSource dataSource;
    private SqlSessionFactory factory;
}