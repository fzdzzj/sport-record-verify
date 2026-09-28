package com.sportverify.verify.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-156 判别 IT（test-only，不入生产路径）：outbox relay {@code markSent} 单行自动提交的并发标度。
 *
 * <p><strong>只裁决、不实施优化</strong>：在逐行语义完全不变（每行仍各自一次自动提交、各自
 * {@code status} PENDING→SENT、各自 {@code sent_at}）的前提下，把 {@code markSent} 从单线程改为
 * N 路并发（N ∈ {1,2,4,8}，按 {@code id % N} 划分互不相交行集，避免同行锁竞争混淆组提交效应），
 * 判「总投递吞吐是否随 N 显著上升」。本类只产出直接读数（墙钟、聚合吞吐、单行墙钟分布、
 * {@code Com_update} 增量、digest 计数增量），<strong>不做标度裁决断言</strong>——裁决按预注册三支
 * （S(8)≥2.0 / ≤1.2 / 其余）在 {@code docs/perf/判别-outbox-relay-markSent-并发标度.md} 落档。</p>
 *
 * <p>协议（镜像 TASK-154 {@code VerifyEventOutboxMarkSentWaitAttributionMysqlIT} 口径）：</p>
 * <ul>
 *   <li>SQL 逐字取自生产 {@link VerifyEventOutboxMapper#markSent(Long)} 的 {@code @Update} 注解
 *       （运行时反射渲染，{@code #{id}} → {@code ?}，不增删 WHERE 条件）。</li>
 *   <li>每臂每轮 M=2000 行全部重置 PENDING（一条批量 UPDATE，重置成本不计入臂墙钟、不落入计数窗口）；
 *       第 N 臂 N 个线程、每线程一条独立 {@code DriverManager} 连接、{@code autoCommit=true}
 *       （与生产「一调用一提交」同语义）；CyclicBarrier 同步起跑、{@code join} 收尾；
 *       臂总墙钟 = 起跑到全部线程完成。</li>
 *   <li>每轮 3 个硬判据（任一不满足 = harness 缺陷，直接失败、不得报告该臂标度）：
 *       ① {@code Com_update} 增量精确 = M（前值在重置 PENDING 之后、barrier 起跑之前取；
 *       后值在最后线程 join 之后、任何收尾 SELECT 之前取）；② 窗口内 {@code Com_insert}/{@code Com_delete}
 *       增量 = 0（污染证据）；③ 收尾 {@code SENT}=M 且 {@code PENDING}=0（在后值读取之后核对）。</li>
 *   <li>每轮起跑前用 {@code performance_schema.threads} 与 {@code SHOW PROCESSLIST} 记录在跑会话，
 *       要求除本臂自有 N+1 条客户会话外无外来前台会话（有则等待其结束，超限即失败）。本实例
 *       （MySQL 8.0.46）的 TYPE='FOREGROUND' 计数含两条系统 Daemon 线程（event_scheduler、
 *       compress_gtid_table，实测），故客户会话按 NAME='thread/sql/one_connection' 计数，
 *       FOREGROUND 原始计数与 dump 照记为证据。</li>
 *   <li>{@code Com_*} 只用 {@code SHOW GLOBAL STATUS} 读取——本 scratch 实例的
 *       {@code performance_schema.global_status} 不列 {@code Com_update}/{@code Com_insert}（TASK-154 实测）。</li>
 *   <li>N=1 基线合理性：单行墙钟应落在「单行自动提交条件 UPDATE」合理量级；本任务在 scratch 实例
 *       （宿主 13318）运行，与 TASK-152 演示实例（3307）不同机不同窗，<strong>不得</strong>把本任务
 *       N=1 的绝对 ms/行等同于 TASK-152 的 18.0 ms/行；判别量是同实例内的标度比 S(N)。</li>
 * </ul>
 *
 * <p><b>需要真 MySQL，默认不参与常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集，
 * 只有 {@code -Dtest=} 显式指定才跑），要求三个环境变量，缺任一即 assume 跳过（跳过不算通过）：
 * {@code TASK156_IT_URL} / {@code TASK156_IT_USER} / {@code TASK156_IT_PASSWORD}。
 * 库名必须解析为专用 {@code task156_concurrency_scratch}，否则直接失败（防止误指演示库）。</p>
 *
 * <p>准备库（与 TASK-147/148/149/153/154 同一机械程序；未触碰演示 {@code verify_db} 与其他既有 scratch schema）：
 * <pre>
 * sed 's/verify_db/task156_concurrency_scratch/g' sql/03-verify-db.sql \
 *   | docker exec -i task131-scratch-mysql mysql -uroot -proot --default-character-set=utf8mb4
 * </pre>
 * 运行（唯一入口 + 仓库根 {@code .mvn/maven.config} 带外通道给出 {@code -Dtest=}——该脚本无 -D 透传、
 * {@code --it} 硬编码 leaderboard-service，无法运行本 IT；maven.config 两行：
 * {@code -Dtest=VerifyEventOutboxRelayConcurrencyScalingMysqlIT} 与
 * {@code -Dsurefire.failIfNoSpecifiedTests=false}，任何 git add/commit 之前必须删除该文件）：
 * <pre>
 * &amp; 'D:\git\Git\bin\bash.exe' scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test
 * </pre>
 * （环境变量在同一 PowerShell 会话用 {@code $env:TASK156_IT_URL/USER/PASSWORD} 设置后调用。）
 * </p>
 *
 * <p><b>不得推出</b>：scratch 真库 + test-only {@code DriverManager} IT ≠ 生产 relay（Spring/Hikari +
 * Redisson 全局 {@code tryLock(0)} 单跑）；正标度结果只是必要非充分——本 IT 隔离的是 {@code markSent}
 * 的 DB 提交成本，未测并发 {@code syncSend}/RocketMQ、未测 relay 锁改造、未测多实例竞争；不得据此改
 * relay-interval/batch/并发/锁/SQL/索引/事务/池/JVM/MQ/{@code innodb_flush_log_at_trx_commit} 任何默认值；
 * 不得宣称生产延迟或吞吐收益；不得翻案 TASK-153；消费端幂等不自动等于授权改 relay。</p>
 */
class VerifyEventOutboxRelayConcurrencyScalingMysqlIT {

    private static final String SCHEMA = "task156_concurrency_scratch";
    private static final int M = 2000;
    private static final int[] ARMS = {1, 2, 4, 8};
    private static final int ROUNDS = 3;
    private static final int SEED_BATCH = 250;
    private static final long CONNECT_TIMEOUT_SECONDS = 60;
    private static final long BARRIER_TIMEOUT_SECONDS = 120;
    private static final int FOREIGN_SESSION_WAIT_TRIES = 10;

    @Test
    void markSentConcurrencyScaling_disjointIdShards_oneAutocommitPerRow() throws Exception {
        String url = System.getenv("TASK156_IT_URL");
        String user = System.getenv("TASK156_IT_USER");
        String password = System.getenv("TASK156_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "缺 TASK156_IT_URL/USER/PASSWORD 环境变量，跳过 scratch MySQL markSent 并发标度判别（不视为通过）");
        assertTrue(url.contains(SCHEMA), "JDBC URL 必须显式含 " + SCHEMA + "：url=" + url);

        String markSentSql = realSql(VerifyEventOutboxMapper.class.getMethod("markSent", Long.class));

        try (Connection control = DriverManager.getConnection(url, user, password)) {
            assertTrue(control.getAutoCommit(), "控制连接应为 autocommit");
            assertSchemaIsScratch(control);
            evidence("markSent.sql", markSentSql);
            registerServerConfig(control);

            seedPendingRows(control);

            Map<Integer, ArmResult> arms = new LinkedHashMap<>();
            for (int n : ARMS) {
                arms.put(n, runArm(url, user, password, control, markSentSql, n));
            }

            double base = arms.get(1).medianThroughputPerSec;
            evidence("scaling.throughputBasePerSec", String.format("%.2f", base));
            StringBuilder s = new StringBuilder("TASK156-SCALING ");
            for (Map.Entry<Integer, ArmResult> e : arms.entrySet()) {
                double sn = e.getValue().medianThroughputPerSec / base;
                s.append(String.format("S%d=%.4f ", e.getKey(), sn));
                evidence("scaling.S" + e.getKey(), String.format("%.4f", sn));
            }
            System.out.println(s.toString().trim());
        }
    }

    // ------------------------------------------------------------------ 单臂（N 线程 × 3 轮）

    private ArmResult runArm(String url, String user, String password, Connection control,
                             String markSentSql, int n) throws Exception {
        double[] walls = new double[ROUNDS];
        double[] throughputs = new double[ROUNDS];
        List<long[][]> rowNanosPooled = new ArrayList<>();
        long cuDeltaTotal = 0;
        long digestDeltaTotal = 0;
        boolean digestReadable = true;

        for (int round = 1; round <= ROUNDS; round++) {
            RoundResult r = runRound(url, user, password, control, markSentSql, n, round);
            walls[round - 1] = r.wallMs;
            throughputs[round - 1] = r.throughputPerSec;
            rowNanosPooled.add(r.rowNanos);
            cuDeltaTotal += r.cuDelta;
            if (r.digestDelta >= 0) {
                digestDeltaTotal += r.digestDelta;
            } else {
                digestReadable = false;
            }
            System.out.printf(
                    "TASK156-ROUND arm=%d round=%d wallMs=%.3f throughputPerSec=%.2f rowMeanMs=%.3f rowMedianMs=%.3f"
                            + " cuDelta=%d ciDelta=%d cdDelta=%d sentCount=%d pendingCount=%d digestDelta=%d foreground=%d%n",
                    n, round, r.wallMs, r.throughputPerSec, r.rowMeanMs, r.rowMedianMs,
                    r.cuDelta, r.ciDelta, r.cdDelta, r.sentCount, r.pendingCount, r.digestDelta, r.foreground);
        }

        double[] sortedWalls = walls.clone();
        Arrays.sort(sortedWalls);
        double medianWallMs = sortedWalls[ROUNDS / 2];
        double medianThroughputPerSec = 1000.0 * M / medianWallMs;
        double rangeWallMs = sortedWalls[ROUNDS - 1] - sortedWalls[0];
        double pooledRowMedianMs = medianNanos(rowNanosPooled.toArray(new long[0][][])) / 1e6;
        ArmResult result = new ArmResult(n, walls, medianWallMs, medianThroughputPerSec,
                rangeWallMs, pooledRowMedianMs, cuDeltaTotal, digestReadable ? digestDeltaTotal : -1);
        System.out.printf("TASK156-ARM arm=%d roundWallMs=%s medianWallMs=%.3f medianThroughputPerSec=%.2f"
                        + " rangeWallMs=%.3f rowMedianPooledMs=%.3f cuDeltaTotal=%d digestDeltaTotal=%d%n",
                n, formatWalls(walls), medianWallMs, medianThroughputPerSec, rangeWallMs,
                pooledRowMedianMs, cuDeltaTotal, result.digestDeltaTotal);
        return result;
    }

    private RoundResult runRound(String url, String user, String password, Connection control,
                                 String markSentSql, int n, int round) throws Exception {
        // 每轮新建 N 个工作线程、每线程一条独立连接（连接建立在窗口外，先连接后起跑）
        List<Worker> workers = new ArrayList<>();
        CountDownLatch connected = new CountDownLatch(n);
        AtomicLong t0 = new AtomicLong();
        // barrier 同步起跑：action 在放行前记录 t0（nanoTime 原点任意，差值才是墙钟）
        CyclicBarrier startBarrier = new CyclicBarrier(n + 1, () -> t0.set(System.nanoTime()));
        for (int shard = 0; shard < n; shard++) {
            Worker w = new Worker(url, user, password, markSentSql, shard, n, connected, startBarrier, t0);
            workers.add(w);
            w.start();
        }
        assertTrue(connected.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "工作线程在超时内未完成连接与 schema 校验");

        // 窗口外重置：全部 M 行回 PENDING（一条批量 UPDATE；成本不计入臂墙钟、不落入计数窗口）
        resetAllPending(control);

        // 计数窗口前置门：除本臂自有 n+1 条客户会话外无外来前台会话（等其结束，超限即失败）
        int foreground = waitOutForeignSessions(control, n);

        // 前值：重置之后、barrier 起跑之前（重置语句不落入窗口）
        long cu0 = comCounter(control, "Com_update");
        long ci0 = comCounter(control, "Com_insert");
        long cd0 = comCounter(control, "Com_delete");
        long d0 = markSentDigestCount(control);

        // barrier 同步起跑（action 在放行前记录 t0）→ join 收尾
        startBarrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (Worker w : workers) {
            w.join();
        }
        long wallNanos = System.nanoTime() - t0.get();

        // 后值：最后一个线程 join 之后、任何收尾 SELECT 之前
        long cu1 = comCounter(control, "Com_update");
        long ci1 = comCounter(control, "Com_insert");
        long cd1 = comCounter(control, "Com_delete");
        long d1 = markSentDigestCount(control);

        long cuDelta = cu1 - cu0;
        long ciDelta = ci1 - ci0;
        long cdDelta = cd1 - cd0;

        // 先查工作线程自身异常（第一因优先），再执行 harness 缺陷硬判据
        for (Worker w : workers) {
            if (w.error != null) {
                throw new IllegalStateException("工作线程 shard=" + w.shard + " 失败", w.error);
            }
        }
        assertEquals(M, cuDelta,
                "arm=" + n + " round=" + round + "：Com_update 增量必须精确 = M（每行恰好一次单行自动提交 UPDATE，"
                        + "偏离 = harness 缺陷，不得报告该臂标度）；实测=" + cuDelta);
        assertEquals(0, ciDelta, "arm=" + n + " round=" + round + "：窗口内 Com_insert 增量应为 0；实测=" + ciDelta);
        assertEquals(0, cdDelta, "arm=" + n + " round=" + round + "：窗口内 Com_delete 增量应为 0；实测=" + cdDelta);

        // 收尾核对 SELECT（在后值读取之后）：每行恰好一次成功标记、无重复无丢失
        int sentCount = countByStatus(control, "SENT");
        int pendingCount = countByStatus(control, "PENDING");
        assertEquals(M, sentCount, "arm=" + n + " round=" + round + "：收尾 SENT 应 = M；实测=" + sentCount);
        assertEquals(0, pendingCount, "arm=" + n + " round=" + round + "：收尾 PENDING 应 = 0；实测=" + pendingCount);

        long[][] pooled = new long[workers.size()][];
        for (int i = 0; i < workers.size(); i++) {
            pooled[i] = workers.get(i).rowNanos;
        }
        double rowMeanMs = meanNanos(pooled) / 1e6;
        double rowMedianMs = medianNanos(new long[][][]{pooled}) / 1e6;
        double wallMs = wallNanos / 1e6;
        double throughputPerSec = 1000.0 * M / wallMs;
        return new RoundResult(wallMs, throughputPerSec, rowMeanMs, rowMedianMs,
                cuDelta, ciDelta, cdDelta, sentCount, pendingCount,
                (d0 >= 0 && d1 >= 0) ? d1 - d0 : -1, foreground, pooled);
    }

    // ------------------------------------------------------------------ 工作线程

    /** 单工作线程：一条独立连接、按 id % shards 领取互不相交行集，逐行一次自动提交 markSent。 */
    private static final class Worker extends Thread {
        private final String url;
        private final String user;
        private final String password;
        private final String markSentSql;
        private final int shard;
        private final int shards;
        private final CountDownLatch connected;
        private final CyclicBarrier startBarrier;
        private final AtomicLong t0;
        private final long[] ids;
        private long[] rowNanos;
        private volatile Throwable error;

        Worker(String url, String user, String password, String markSentSql,
               int shard, int shards, CountDownLatch connected, CyclicBarrier startBarrier, AtomicLong t0) {
            super("task156-worker-" + shard);
            this.url = url;
            this.user = user;
            this.password = password;
            this.markSentSql = markSentSql;
            this.shard = shard;
            this.shards = shards;
            this.connected = connected;
            this.startBarrier = startBarrier;
            this.t0 = t0;
            List<Long> mine = new ArrayList<>();
            for (long id = (shard == 0 ? shards : shard); id <= M; id += shards) {
                mine.add(id);
            }
            this.ids = new long[mine.size()];
            for (int i = 0; i < mine.size(); i++) {
                this.ids[i] = mine.get(i);
            }
        }

        @Override
        public void run() {
            try (Connection conn = DriverManager.getConnection(url, user, password)) {
                assertTrue(conn.getAutoCommit(), "工作连接应为 autocommit（与生产一调用一提交同语义）");
                assertSchemaIsScratch(conn);
                rowNanos = new long[ids.length];
                connected.countDown();
                startBarrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                int k = 0;
                try (PreparedStatement ps = conn.prepareStatement(markSentSql)) {
                    for (long id : ids) {
                        ps.setLong(1, id);
                        long s = System.nanoTime();
                        int updated = ps.executeUpdate();
                        rowNanos[k++] = System.nanoTime() - s;
                        if (updated != 1) {
                            throw new IllegalStateException(
                                    "markSent 应恰好命中 1 行（PENDING→SENT）：id=" + id + " updated=" + updated);
                        }
                    }
                }
            } catch (Throwable e) {
                if (error == null) {
                    error = e;
                }
                connected.countDown();
            }
        }
    }

    // ------------------------------------------------------------------ 窗口门与计数读取（只读）

    /**
     * 外来前台会话门：要求客户会话恰好 n+1 条（本臂自有连接）。本实例（MySQL 8.0.46）P_S 的
     * TYPE='FOREGROUND' 计数<strong>含两条系统 Daemon 线程</strong>（thread/sql/event_scheduler、
     * thread/sql/compress_gtid_table，实测），故按客户会话线程名 NAME='thread/sql/one_connection'
     * 计数（与 TASK-154 的连接线程判据同源），并以 SHOW PROCESSLIST（排除 Daemon 与本门自身连接）
     * 交叉核对恰好 n 条；FOREGROUND 原始计数与完整 dump 照记为证据。有外来会话则等其结束，超限即失败。
     */
    private int waitOutForeignSessions(Connection control, int n) throws Exception {
        long selfId = queryLong(control, "SELECT CONNECTION_ID()");
        String dump = "";
        for (int attempt = 0; attempt < FOREIGN_SESSION_WAIT_TRIES; attempt++) {
            dump = processListDump(control);
            long fgRaw = queryLong(control,
                    "SELECT COUNT(*) FROM performance_schema.threads WHERE TYPE = 'FOREGROUND'");
            long clientSessions = queryLong(control,
                    "SELECT COUNT(*) FROM performance_schema.threads WHERE NAME = 'thread/sql/one_connection'");
            long foreignClients = 0;
            try (Statement s = control.createStatement(); ResultSet rs = s.executeQuery("SHOW PROCESSLIST")) {
                while (rs.next()) {
                    boolean daemon = "Daemon".equals(rs.getString(5));
                    boolean self = rs.getLong(1) == selfId;
                    if (!daemon && !self) {
                        foreignClients++;
                    }
                }
            }
            if (clientSessions == n + 1 && foreignClients == n) {
                evidence("gate.foreground.rawCount", String.valueOf(fgRaw));
                evidence("gate.clientSessions.p_s_one_connection", String.valueOf(clientSessions));
                evidence("gate.processlist.foreignClients", String.valueOf(foreignClients));
                evidence("gate.processlist", dump);
                return (int) clientSessions;
            }
            Thread.sleep(1_000L);
        }
        throw new IllegalStateException("存在外来前台会话（要求客户会话恰好 " + (n + 1) + " 条=本臂自有 n+1 连接、"
                + "processlist 非自有客户会话恰好 " + n + " 条）：SHOW PROCESSLIST = " + dump);
    }

    private long queryLong(Connection control, String sql) throws SQLException {
        try (Statement s = control.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), "查询应返回一行：" + sql);
            return rs.getLong(1);
        }
    }

    private String processListDump(Connection control) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement s = control.createStatement(); ResultSet rs = s.executeQuery("SHOW PROCESSLIST")) {
            while (rs.next()) {
                String info = rs.getString(8);
                rows.add("Id=" + rs.getLong(1) + " User=" + rs.getString(2) + " db=" + rs.getString(4)
                        + " Command=" + rs.getString(5) + " Time=" + rs.getString(6)
                        + " Info=" + (info == null ? "NULL" : info.substring(0, Math.min(info.length(), 60))));
            }
        }
        return String.join(" | ", rows);
    }

    /** 用 SHOW GLOBAL STATUS：本实例的 performance_schema.global_status 不列 Com_*（TASK-154 实测）。 */
    private long comCounter(Connection control, String name) throws SQLException {
        try (PreparedStatement ps = control.prepareStatement("SHOW GLOBAL STATUS LIKE ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "SHOW GLOBAL STATUS 应含 " + name);
                return Long.parseLong(rs.getString(2));
            }
        }
    }

    /** 目标 digest 语句计数（若可读）；不可读返回 -1 如实记录，不作判据。 */
    private long markSentDigestCount(Connection control) {
        try (PreparedStatement ps = control.prepareStatement(
                "SELECT IFNULL(SUM(COUNT_STAR), 0) FROM performance_schema.events_statements_summary_by_digest "
                        + "WHERE SCHEMA_NAME = ? AND DIGEST_TEXT LIKE 'UPDATE%verify_event_outbox%'")) {
            ps.setString(1, SCHEMA);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (SQLException e) {
            return -1;
        }
    }

    private int countByStatus(Connection control, String status) throws SQLException {
        try (PreparedStatement ps = control.prepareStatement(
                "SELECT COUNT(*) FROM verify_event_outbox WHERE status = ?")) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "COUNT(*) 查询应返回一行");
                return (int) rs.getLong(1);
            }
        }
    }

    // ------------------------------------------------------------------ 数据准备与只读登记

    /** 种子：TRUNCATE 后分批插入 M=2000 行 PENDING（合成 event_id/payload；自增 id 连续 1..M）。 */
    private void seedPendingRows(Connection control) throws SQLException {
        try (Statement s = control.createStatement()) {
            s.execute("TRUNCATE TABLE verify_event_outbox");
        }
        String prefix = "INSERT INTO verify_event_outbox "
                + "(event_id, topic, tag, payload, trace_id, status, retry_count, created_at, sent_at) VALUES ";
        int seq = 0;
        while (seq < M) {
            StringBuilder sql = new StringBuilder(prefix);
            int end = Math.min(seq + SEED_BATCH, M);
            for (int i = seq + 1; i <= end; i++) {
                if (i > seq + 1) {
                    sql.append(", ");
                }
                sql.append("('t156-seed-").append(i)
                        .append("', 'record-verify-events', 'VERIFIED', '{\"seed\":1}', NULL, 'PENDING', 0, NOW(), NULL)");
            }
            try (Statement s = control.createStatement()) {
                s.executeUpdate(sql.toString());
            }
            seq = end;
        }
        try (Statement s = control.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT COUNT(*), MIN(id), MAX(id), SUM(status = 'PENDING'), SUM(status = 'SENT') "
                        + "FROM verify_event_outbox")) {
            assertTrue(rs.next(), "种子核对应返回一行");
            assertEquals(M, rs.getLong(1), "种子行数应 = M");
            assertEquals(1L, rs.getLong(2), "种子最小 id 应 = 1（id 连续）");
            assertEquals((long) M, rs.getLong(3), "种子最大 id 应 = M（id 连续）");
            assertEquals(M, rs.getLong(4), "种子应全部 PENDING");
            assertEquals(0L, rs.getLong(5), "种子不应有 SENT");
        }
        evidence("seed.rows", String.valueOf(M));
    }

    /** 窗口外重置：一条批量 UPDATE 把全部行拉回 PENDING（harness 管道 SQL，非被测语句）。 */
    private void resetAllPending(Connection control) throws SQLException {
        try (Statement s = control.createStatement()) {
            int rows = s.executeUpdate("UPDATE verify_event_outbox SET status = 'PENDING', sent_at = NULL");
            assertEquals(M, rows, "重置应影响全部 M 行；实测=" + rows);
        }
    }

    /** 只读登记组提交相关服务器配置（不改任何配置）。 */
    private void registerServerConfig(Connection control) throws SQLException {
        for (String name : new String[]{"version", "innodb_flush_log_at_trx_commit", "sync_binlog",
                "log_bin", "event_scheduler"}) {
            try (PreparedStatement ps = control.prepareStatement("SHOW VARIABLES LIKE ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "SHOW VARIABLES 应含 " + name);
                    evidence("server." + name, rs.getString(2));
                }
            }
        }
    }

    /** 防误指：连接必须落在本任务专用 scratch schema 上（每条连接都校验）。 */
    private static void assertSchemaIsScratch(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT DATABASE()")) {
            assertTrue(rs.next(), "SELECT DATABASE() 应返回当前库");
            assertEquals(SCHEMA, rs.getString(1),
                    "JDBC URL 必须指向专用 " + SCHEMA + "；本用例绝不触碰演示库");
        }
    }

    /** 从生产 Mapper 注解取真实 SQL 文本，{@code #{id}} 换成 JDBC 占位符（不增删 WHERE 条件）。 */
    private static String realSql(Method method) {
        Update update = method.getAnnotation(Update.class);
        assertNotNull(update, method.getName() + " 应是 @Update 注解 SQL");
        return String.join(" ", update.value()).replace("#{id}", "?");
    }

    // ------------------------------------------------------------------ 统计与输出工具

    private static double meanNanos(long[][] pooled) {
        long total = 0;
        long count = 0;
        for (long[] arr : pooled) {
            for (long v : arr) {
                total += v;
            }
            count += arr.length;
        }
        return (double) total / count;
    }

    private static double medianNanos(long[][][] pooled) {
        List<Long> all = new ArrayList<>();
        for (long[][] group : pooled) {
            for (long[] arr : group) {
                for (long v : arr) {
                    all.add(v);
                }
            }
        }
        long[] sorted = new long[all.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = all.get(i);
        }
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    private static String formatWalls(double[] walls) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < walls.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(String.format("%.3f", walls[i]));
        }
        return sb.toString();
    }

    private static void evidence(String key, String value) {
        System.out.println("TASK156-EVIDENCE " + key + "=" + value);
    }

    private static final class RoundResult {
        final double wallMs;
        final double throughputPerSec;
        final double rowMeanMs;
        final double rowMedianMs;
        final long cuDelta;
        final long ciDelta;
        final long cdDelta;
        final int sentCount;
        final int pendingCount;
        final long digestDelta;
        final int foreground;
        final long[][] rowNanos;

        RoundResult(double wallMs, double throughputPerSec, double rowMeanMs, double rowMedianMs,
                    long cuDelta, long ciDelta, long cdDelta, int sentCount, int pendingCount,
                    long digestDelta, int foreground, long[][] rowNanos) {
            this.wallMs = wallMs;
            this.throughputPerSec = throughputPerSec;
            this.rowMeanMs = rowMeanMs;
            this.rowMedianMs = rowMedianMs;
            this.cuDelta = cuDelta;
            this.ciDelta = ciDelta;
            this.cdDelta = cdDelta;
            this.sentCount = sentCount;
            this.pendingCount = pendingCount;
            this.digestDelta = digestDelta;
            this.foreground = foreground;
            this.rowNanos = rowNanos;
        }
    }

    private static final class ArmResult {
        final int n;
        final double[] walls;
        final double medianWallMs;
        final double medianThroughputPerSec;
        final double rangeWallMs;
        final double pooledRowMedianMs;
        final long cuDeltaTotal;
        final long digestDeltaTotal;

        ArmResult(int n, double[] walls, double medianWallMs, double medianThroughputPerSec,
                  double rangeWallMs, double pooledRowMedianMs, long cuDeltaTotal, long digestDeltaTotal) {
            this.n = n;
            this.walls = walls;
            this.medianWallMs = medianWallMs;
            this.medianThroughputPerSec = medianThroughputPerSec;
            this.rangeWallMs = rangeWallMs;
            this.pooledRowMedianMs = pooledRowMedianMs;
            this.cuDeltaTotal = cuDeltaTotal;
            this.digestDeltaTotal = digestDeltaTotal;
        }
    }
}
