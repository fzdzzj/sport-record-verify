package com.sportverify.verify.mapper;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * markSent 引起的等待能否归因到「执行它的客户端连接线程」——只读 Performance Schema 观测（TASK-154）。
 *
 * <p>本类<strong>不改生产代码、不改 MySQL 配置、不开关任何 P_S 仪器、不重置计数器</strong>；
 * 只从<strong>已有</strong>的 P_S 汇总表读增量：目标连接线程的 waits 汇总
 * （{@code events_waits_summary_by_thread_by_event_name}）、语句汇总
 * （{@code events_statements_summary_by_thread_by_event_name}）、按库的语句 digest
 * （{@code events_statements_summary_by_digest}），以及后台 innodb 线程 waits 汇总与全局 waits 汇总。</p>
 *
 * <p>协议：目标连接（autocommit，与生产「一调用一提交」同语义）只执行真实
 * {@link VerifyEventOutboxMapper#markSent(Long)} 的参数化 SQL（SQL 文本由注解反射取得，
 * {@code #{id}} → {@code ?}）；独立观察连接先按 {@code CONNECTION_ID()} → P_S THREAD_ID 建立映射，
 * 在每次目标语句前后各读一次（前快照 / 后快照），逐次核对：线程身份、影响行数、行状态、
 * 语句计数增量（=1，证明观察查询没有落到目标线程）、markSent digest 计数增量。
 * 负对照：零行 UPDATE 两种（不存在 id / 已 SENT 行）、同线程非目标 SQL（{@code SELECT 1}、
 * 同表 SELECT、真实 incrRetry UPDATE）。</p>
 *
 * <p>判读纪律：所读均为<strong>服务端 P_S 原始计数</strong>，不得据此换算「fsync / 锁 / 纯 SQL 占比」；
 * 若线程身份或负对照无法闭合（含后台 innodb 线程同时段存在非零等待增量导致归属不明），
 * 结论只能是 NO-GO 或受限 GO，见 TASK-154 报告。</p>
 *
 * <p><b>需要真 MySQL，默认不参与常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集，
 * 只有 {@code -Dtest=} 显式指定才跑），要求三个环境变量，缺任一即 assume 跳过（跳过不算通过）：
 * {@code TASK154_IT_URL} / {@code TASK154_IT_USER} / {@code TASK154_IT_PASSWORD}。
 * 库名必须解析为专用 {@code task154_wait_scratch}，否则直接失败（防止误指演示库）。</p>
 *
 * <p>准备库（与 TASK-147/148/149/153 同一机械程序；未触碰演示 {@code verify_db} 与其他既有 scratch schema）：
 * <pre>
 * sed 's/verify_db/task154_wait_scratch/g' sql/03-verify-db.sql \
 *   | docker exec -i task131-scratch-mysql mysql -uroot -proot --default-character-set=utf8mb4
 * </pre>
 * 运行（唯一入口 + {@code MAVEN_ARGS} 注入目标类选择器）：
 * <pre>
 * MAVEN_ARGS="-Dtest=VerifyEventOutboxMarkSentWaitAttributionMysqlIT -Dsurefire.failIfNoSpecifiedTests=false" \
 *   bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test
 * </pre>
 * </p>
 */
class VerifyEventOutboxMarkSentWaitAttributionMysqlIT {

    private static final String SCHEMA = "task154_wait_scratch";
    /** TASK-152 在管理员窗口上记录的 markSent 语句 digest（同一条生产 SQL 的规范化指纹）。 */
    private static final String MARK_SENT_DIGEST =
            "6b07036bdfeae6b8f12d602eb37af86bfe73e3f9dac3600006dfd8cde12b05b7";
    private static final int REPEAT_WINDOWS = 5;
    private static final long SETTLE_MS = 200L;
    private static final String UPDATE_EVENT = "statement/sql/update";
    private static final String SELECT_EVENT = "statement/sql/select";

    private HikariDataSource helperDs;
    private Connection observer;
    private Connection target;
    private long targetProcessListId;
    private long targetThreadId;
    private long observerThreadId;
    private String markSentSql;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TASK154_IT_URL");
        String user = System.getenv("TASK154_IT_USER");
        String password = System.getenv("TASK154_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "缺 TASK154_IT_URL/USER/PASSWORD 环境变量，跳过 scratch MySQL 等待归因观测（不视为通过）");
        assertTrue(url.contains(SCHEMA), "JDBC URL 必须显式含 " + SCHEMA + "：url=" + url);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("task154-wait-it");
        helperDs = new HikariDataSource(cfg);

        // 防误指：环境变量必须落在本任务专用 scratch schema 上，否则直接失败（不静默跳过）。
        String catalog;
        try (Connection c = helperDs.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DATABASE()")) {
            assertTrue(rs.next(), "SELECT DATABASE() 应返回当前库");
            catalog = rs.getString(1);
        }
        assertEquals(SCHEMA, catalog,
                "TASK154_IT_URL 必须指向专用 " + SCHEMA + "（当前=" + catalog + "）；本用例绝不触碰演示库");

        exec("TRUNCATE TABLE verify_event_outbox");

        markSentSql = realSql(VerifyEventOutboxMapper.class.getMethod("markSent", Long.class));

        observer = DriverManager.getConnection(url, user, password);
        target = DriverManager.getConnection(url, user, password);
        assertTrue(target.getAutoCommit(), "目标连接应为 autocommit（与生产一调用一提交同语义）");

        targetProcessListId = queryLong(target, "SELECT CONNECTION_ID()");
        observerThreadId = mapThread(queryLong(observer, "SELECT CONNECTION_ID()"));
        targetThreadId = mapThread(targetProcessListId);
        evidence("schema", SCHEMA);
        evidence("markSent.sql", markSentSql);
        evidence("target.processlistId", String.valueOf(targetProcessListId));
        evidence("target.threadId", String.valueOf(targetThreadId));
        evidence("observer.threadId", String.valueOf(observerThreadId));
    }

    @AfterEach
    void tearDown() {
        closeQuietly(target);
        closeQuietly(observer);
        if (helperDs != null) {
            helperDs.close();
        }
    }

    // ------------------------------------------------------------------ 用例

    @Test
    void markSentWaitAttribution_threadScopedRawDeltas() throws Exception {
        calibrateTimerUnit();

        // 预热：让 markSent digest 行与目标线程 waits 行先建立，再取基线
        assertEquals(1, executeMarkSent(insertPending("t154-warmup")), "预热标记应命中 1 行");
        Map<String, long[]> targetWaitsAtStart = readThreadWaits(targetThreadId);
        evidence("target.waits.startEvents", formatMap(targetWaitsAtStart, 0));
        assertTrue(targetWaitsAtStart.values().stream().anyMatch(v -> v[0] > 0),
                "目标线程在 waits 汇总里应已有行（否则等待不可观测，直接判 NO-GO 的前置事实）");

        Map<String, long[]> bgBefore = readBackgroundWaits();
        Map<String, long[]> globalBefore = readGlobalWaits();
        Map<String, Long> statusBefore = readGlobalStatus();

        for (int i = 1; i <= REPEAT_WINDOWS; i++) {
            measure("w" + i, "markSent.pending", markSentSql, insertPending("t154-w" + i),
                    1, UPDATE_EVENT, true, true);
        }
        // 负对照：零行 UPDATE ×2（不存在 id 无行可读；已 SENT 行条件不命中）
        measure("n1-missingId", "markSent.zeroRow.missingId", markSentSql, 999_999_999L,
                0, UPDATE_EVENT, true, false);
        measure("n2-alreadySent", "markSent.zeroRow.alreadySent", markSentSql,
                pendingRowIdOf("t154-w1"), 0, UPDATE_EVENT, true, true);
        // 负对照：同线程非目标 SQL
        measure("n3-select1", "nonTarget.select1", "SELECT 1", null, -1, SELECT_EVENT, false, false);
        measure("n4-sameTableSelect", "nonTarget.sameTableSelect",
                "SELECT status, sent_at FROM verify_event_outbox WHERE id = ?",
                pendingRowIdOf("t154-w2"), -1, SELECT_EVENT, false, false);
        measure("n5-incrRetry", "nonTarget.incrRetry",
                realSql(VerifyEventOutboxMapper.class.getMethod("incrRetry", Long.class)),
                insertPending("t154-n5"), 1, UPDATE_EVENT, false, true);

        Map<String, long[]> bgAfter = readBackgroundWaits();
        Map<String, long[]> globalAfter = readGlobalWaits();
        Map<String, Long> statusAfter = readGlobalStatus();

        evidence("background.innodb.waits.nonzeroRows", formatMap(bgAfter, 12));
        evidence("background.innodb.waits.delta", diff(bgBefore, bgAfter, 8));
        evidence("global.waits.delta", diff(globalBefore, globalAfter, 8));
        evidence("global.status.comUpdateDelta", String.valueOf(statusAfter.get("Com_update") - statusBefore.get("Com_update")));
        evidence("global.status.comInsertDelta", String.valueOf(statusAfter.get("Com_insert") - statusBefore.get("Com_insert")));

        long relisted = mapThread(targetProcessListId);
        assertEquals(targetThreadId, relisted, "序列结束后 PROCESSLIST_ID → THREAD_ID 映射必须不变（否则增量不可归属）");
        evidence("target.identityStable", "true");

        evidence("target.history.lastStatements", String.join(" | ", historyRows(targetThreadId, 6)));
    }

    // ------------------------------------------------------------------ 单窗口测量

    private void measure(String name, String kind, String sql, Long rowId,
                         int expectedAffected, String stmtEvent, boolean expectMarkSentDigest,
                         boolean expectRow) throws SQLException {
        Map<String, long[]> waitsBefore = readThreadWaits(targetThreadId);
        Map<String, long[]> stmtBefore = readThreadStatements(targetThreadId);
        DigestRow digestBefore = readMarkSentDigest();

        long t0 = System.nanoTime();
        int affected;
        if ("SELECT 1".equals(sql) || sql.startsWith("SELECT")) {
            try (PreparedStatement ps = target.prepareStatement(sql)) {
                if (rowId != null) {
                    ps.setLong(1, rowId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "负对照 SELECT 应返回一行");
                }
            }
            affected = -1;
        } else {
            try (PreparedStatement ps = target.prepareStatement(sql)) {
                ps.setLong(1, rowId);
                affected = ps.executeUpdate();
            }
        }
        long wallNanos = System.nanoTime() - t0;
        settle();

        Map<String, long[]> waitsAfter = readThreadWaits(targetThreadId);
        Map<String, long[]> stmtAfter = readThreadStatements(targetThreadId);
        DigestRow digestAfter = readMarkSentDigest();

        long stmtTotalDelta = sumCounts(stmtAfter) - sumCounts(stmtBefore);
        long stmtEventDelta = countOf(stmtAfter, stmtEvent) - countOf(stmtBefore, stmtEvent);
        assertEquals(1, stmtTotalDelta,
                name + "：目标线程语句总数增量应为 1（观测查询不得落到目标线程）；实测=" + stmtTotalDelta);
        assertEquals(1, stmtEventDelta,
                name + "：" + stmtEvent + " 计数增量应为 1；实测=" + stmtEventDelta);
        if (expectedAffected >= 0) {
            assertEquals(expectedAffected, affected, name + "：影响行数");
        }
        if (expectRow) {
            RowState state = readRowState(rowId);
            evidence(name + ".row.status", state.status());
            evidence(name + ".row.sentAtNull", String.valueOf(state.sentAtNull()));
        }
        if (expectMarkSentDigest) {
            long digestCountDelta = digestAfter.count() - digestBefore.count();
            long digestRowsDelta = digestAfter.rowsAffected() - digestBefore.rowsAffected();
            assertEquals(1, digestCountDelta, name + "：markSent digest 计数增量应为 1；found=" + digestAfter.found());
            assertEquals(expectedAffected, (int) digestRowsDelta,
                    name + "：markSent digest 累计影响行数增量应等于 " + expectedAffected);
            evidence(name + ".digest.found", String.valueOf(digestAfter.found()));
        }

        evidence(name + ".kind", kind);
        evidence(name + ".affected", String.valueOf(affected));
        evidence(name + ".clientWallUs", String.format("%.1f", wallNanos / 1000.0));
        if (expectMarkSentDigest) {
            evidence(name + ".digest.countDelta", "1");
            evidence(name + ".digest.timerDeltaUs",
                    String.format("%.1f", (digestAfter.timer() - digestBefore.timer()) / 1_000_000.0));
            evidence(name + ".digest.cumulativeCount", String.valueOf(digestAfter.count()));
        }
        evidence(name + ".stmt.totalCountDelta", String.valueOf(stmtTotalDelta));
        evidence(name + ".waits.nonIdle.delta", diff(waitsBefore, waitsAfter, 8, true));
        evidence(name + ".waits.idle.delta", diff(waitsBefore, waitsAfter, 4, false));
    }

    /**
     * 计时单位经验校准：在观察连接上执行一条 {@code SELECT SLEEP(0.5)}，再从该线程的语句历史
     * 读回它自己的语句事件计时——应为 ~0.5e12（ps）。不用线程汇总前后差：SELECT 的汇总计数
     * 在结果发完后才落账，会把「前快照那次读」自己算进来（本实现首跑实测 delta=2 即此原因）。
     */
    private void calibrateTimerUnit() throws SQLException {
        try (Statement s = observer.createStatement()) {
            s.execute("SELECT SLEEP(0.5)");
        }
        List<HistoryRow> rows = readHistory(observerThreadId, 5);
        HistoryRow sleep = rows.stream()
                .filter(r -> r.digestText() != null && r.digestText().toUpperCase().contains("SLEEP"))
                .reduce((first, second) -> second)
                .orElse(null);
        assertNotNull(sleep, "观察连接语句历史里应能找到 SLEEP 语句行；rows=" + rows.size());
        assertTrue(sleep.timerWait() > 100_000_000_000L && sleep.timerWait() < 2_000_000_000_000L,
                "SLEEP(0.5) 的语句事件计时应为 ~0.5e12（ps 单位）：实测=" + sleep.timerWait());
        evidence("timer.calibration.sleepStatementTimerWaitPs", String.valueOf(sleep.timerWait()));
        evidence("timer.calibration.sleepDigestText", String.valueOf(sleep.digestText()));
        evidence("timer.unit", "picoseconds(empirical)");
    }

    // ------------------------------------------------------------------ P_S 只读读取

    private long mapThread(long processListId) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT THREAD_ID, NAME, TYPE FROM performance_schema.threads WHERE PROCESSLIST_ID = ?")) {
            ps.setLong(1, processListId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "观察连接应能按 PROCESSLIST_ID=" + processListId + " 映射到线程");
                assertEquals("FOREGROUND", rs.getString("TYPE"), "目标连接应是前台线程");
                assertEquals("thread/sql/one_connection", rs.getString("NAME"), "目标连接应是客户端连接线程");
                return rs.getLong("THREAD_ID");
            }
        }
    }

    private Map<String, long[]> readThreadWaits(long threadId) throws SQLException {
        return readCountTimer("SELECT EVENT_NAME, COUNT_STAR, SUM_TIMER_WAIT "
                + "FROM performance_schema.events_waits_summary_by_thread_by_event_name WHERE THREAD_ID = ?", threadId);
    }

    private Map<String, long[]> readThreadStatements(long threadId) throws SQLException {
        return readCountTimer("SELECT EVENT_NAME, COUNT_STAR, SUM_TIMER_WAIT "
                + "FROM performance_schema.events_statements_summary_by_thread_by_event_name WHERE THREAD_ID = ?", threadId);
    }

    private Map<String, long[]> readBackgroundWaits() throws SQLException {
        Map<String, long[]> out = new LinkedHashMap<>();
        try (Statement s = observer.createStatement();
             ResultSet rs = s.executeQuery("SELECT t.THREAD_ID, t.NAME, w.EVENT_NAME, w.COUNT_STAR, w.SUM_TIMER_WAIT "
                     + "FROM performance_schema.threads t "
                     + "JOIN performance_schema.events_waits_summary_by_thread_by_event_name w "
                     + "ON w.THREAD_ID = t.THREAD_ID WHERE t.NAME LIKE 'thread/innodb/%'")) {
            while (rs.next()) {
                out.put(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getString(3),
                        new long[]{rs.getLong(4), rs.getLong(5)});
            }
        }
        return out;
    }

    private Map<String, long[]> readGlobalWaits() throws SQLException {
        Map<String, long[]> out = new LinkedHashMap<>();
        try (Statement s = observer.createStatement();
             ResultSet rs = s.executeQuery("SELECT EVENT_NAME, COUNT_STAR, SUM_TIMER_WAIT "
                     + "FROM performance_schema.events_waits_summary_global_by_event_name WHERE COUNT_STAR > 0")) {
            while (rs.next()) {
                out.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
            }
        }
        return out;
    }

    /** 用 SHOW GLOBAL STATUS：本实例的 performance_schema.global_status 不列 Com_update/Com_insert（实测）。 */
    private Map<String, Long> readGlobalStatus() throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Statement s = observer.createStatement(); ResultSet rs = s.executeQuery("SHOW GLOBAL STATUS")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if ("Com_update".equals(name) || "Com_insert".equals(name)) {
                    out.put(name, Long.parseLong(rs.getString(2)));
                }
            }
        }
        assertEquals(2, out.size(), "SHOW GLOBAL STATUS 应含 Com_update 与 Com_insert");
        return out;
    }

    private DigestRow readMarkSentDigest() throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT COUNT_STAR, SUM_TIMER_WAIT, SUM_ROWS_AFFECTED "
                        + "FROM performance_schema.events_statements_summary_by_digest "
                        + "WHERE DIGEST = ? AND SCHEMA_NAME = ?")) {
            ps.setString(1, MARK_SENT_DIGEST);
            ps.setString(2, SCHEMA);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new DigestRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), true);
                }
                return new DigestRow(0, 0, 0, false);
            }
        }
    }

    private List<String> historyRows(long threadId, int limit) throws SQLException {
        return readHistory(threadId, limit).stream()
                .map(h -> "ev" + h.eventId() + " digest=" + h.digest()
                        + " rows=" + h.rowsAffected() + "/" + h.rowsSent()
                        + " err=" + h.mysqlErrno()
                        + " timerUs=" + String.format("%.1f", h.timerWait() / 1_000_000.0)
                        + " text=" + String.valueOf(h.digestText()).replace("\n", " "))
                .collect(Collectors.toList());
    }

    private List<HistoryRow> readHistory(long threadId, int limit) throws SQLException {
        List<HistoryRow> out = new ArrayList<>();
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT EVENT_ID, DIGEST, DIGEST_TEXT, TIMER_WAIT, ROWS_AFFECTED, ROWS_SENT, MYSQL_ERRNO "
                        + "FROM performance_schema.events_statements_history WHERE THREAD_ID = ? "
                        + "ORDER BY EVENT_ID DESC LIMIT ?")) {
            ps.setLong(1, threadId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HistoryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getLong(5), rs.getLong(6), rs.getLong(7)));
                }
            }
        }
        return out;
    }

    private Map<String, long[]> readCountTimer(String sql, long threadId) throws SQLException {
        Map<String, long[]> out = new LinkedHashMap<>();
        try (PreparedStatement ps = observer.prepareStatement(sql)) {
            ps.setLong(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 数据准备与工具

    private long insertPending(String eventId) throws SQLException {
        String sql = "INSERT INTO verify_event_outbox "
                + "(event_id, topic, tag, payload, trace_id, status, retry_count, created_at, sent_at) "
                + "VALUES (?, 'record-verify-events', 'VERIFIED', ?, NULL, 'PENDING', 0, NOW(), NULL)";
        try (Connection c = helperDs.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, eventId);
            ps.setString(2, "{\"eventId\":\"" + eventId + "\",\"recordId\":1,\"userId\":1}");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "插入应返回自增 id");
                return keys.getLong(1);
            }
        }
    }

    private long pendingRowIdOf(String eventId) throws SQLException {
        try (Connection c = helperDs.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM verify_event_outbox WHERE event_id = ?")) {
            ps.setString(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "应存在行：event_id=" + eventId);
                return rs.getLong(1);
            }
        }
    }

    private int executeMarkSent(long rowId) throws SQLException {
        try (PreparedStatement ps = target.prepareStatement(markSentSql)) {
            ps.setLong(1, rowId);
            return ps.executeUpdate();
        }
    }

    private RowState readRowState(long rowId) throws SQLException {
        try (Connection c = helperDs.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status, sent_at FROM verify_event_outbox WHERE id = ?")) {
            ps.setLong(1, rowId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "应存在行：id=" + rowId);
                return new RowState(rs.getString(1), rs.getObject(2) == null);
            }
        }
    }

    private long queryLong(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "查询应返回一行：" + sql);
            return rs.getLong(1);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = helperDs.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    /** 从生产 Mapper 注解取真实 SQL 文本，{@code #{id}} 换成 JDBC 占位符。 */
    private static String realSql(Method method) {
        Update update = method.getAnnotation(Update.class);
        assertNotNull(update, method.getName() + " 应是 @Update 注解 SQL");
        return String.join(" ", update.value()).replace("#{id}", "?");
    }

    private static long sumCounts(Map<String, long[]> map) {
        return map.values().stream().mapToLong(v -> v[0]).sum();
    }

    private static long countOf(Map<String, long[]> map, String eventName) {
        long[] v = map.get(eventName);
        return v == null ? 0 : v[0];
    }

    /** 按「事件=计数增量/计时增量µs」格式输出差集；excludeIdle=true 时排除 wait/idle 噪声行。 */
    private static String diff(Map<String, long[]> before, Map<String, long[]> after, int limit, boolean excludeIdle) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, long[]> e : after.entrySet()) {
            long[] b = before.get(e.getKey());
            long dc = e.getValue()[0] - (b == null ? 0 : b[0]);
            long dt = e.getValue()[1] - (b == null ? 0 : b[1]);
            if (dc == 0 && dt == 0) {
                continue;
            }
            if (excludeIdle && "idle".equals(e.getKey())) {
                continue;
            }
            parts.add(e.getKey() + "=" + dc + "/" + String.format("%.1f", dt / 1_000_000.0) + "us");
        }
        if (parts.isEmpty()) {
            return "(no delta)";
        }
        long totalDt = after.entrySet().stream().filter(e -> !excludeIdle || !"idle".equals(e.getKey()))
                .mapToLong(e -> e.getValue()[1] - (before.get(e.getKey()) == null ? 0 : before.get(e.getKey())[1])).sum();
        String head = limit > 0 && parts.size() > limit
                ? parts.subList(0, limit).stream().collect(Collectors.joining("; ")) + "; ...(+" + (parts.size() - limit) + ")"
                : String.join("; ", parts);
        return head + " || sumTimer=" + String.format("%.1f", totalDt / 1_000_000.0) + "us";
    }

    private static String diff(Map<String, long[]> before, Map<String, long[]> after, int limit) {
        return diff(before, after, limit, false);
    }

    private static String formatMap(Map<String, long[]> map, int limit) {
        List<String> parts = map.entrySet().stream()
                .filter(e -> e.getValue()[0] > 0)
                .map(e -> e.getKey() + "=" + e.getValue()[0] + "/" + String.format("%.1f", e.getValue()[1] / 1_000_000.0) + "us")
                .sorted()
                .collect(Collectors.toList());
        if (limit > 0 && parts.size() > limit) {
            return String.join("; ", parts.subList(0, limit)) + "; ...(+" + (parts.size() - limit) + ")";
        }
        return parts.isEmpty() ? "(none)" : String.join("; ", parts);
    }

    private static void settle() {
        try {
            Thread.sleep(SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // 清理失败不掩盖用例结论
            }
        }
    }

    private static void evidence(String key, String value) {
        System.out.println("TASK154-EVIDENCE " + key + "=" + value);
    }

    private record DigestRow(long count, long timer, long rowsAffected, boolean found) {
    }

    private record RowState(String status, boolean sentAtNull) {
    }

    private record HistoryRow(long eventId, String digest, String digestText, long timerWait,
                              long rowsAffected, long rowsSent, long mysqlErrno) {
    }
}
