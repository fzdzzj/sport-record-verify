package com.sportverify.verify.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.sportverify.verify.entity.VerifyEventOutbox;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-161 判别 IT（test-only，不入生产路径）：生产形态 Hikari 池（生效默认 10 连接）下，
 * outbox relay {@code markSent} 批内并发的 {@code S_prod(N)} 还剩多少。
 *
 * <p><strong>只裁决、不实施优化、不改任何默认值</strong>。唯一问题：TASK-156 用「每线程一条独立
 * {@code DriverManager} 自动提交连接（完全无连接池）」实测 S(2)=1.8612 / S(4)=3.3066 / S(8)=5.7056，
 * 该数字经过一个真实 10 连接 Hikari 池（生产 {@code application.yml} 未设
 * {@code hikari.maximum-pool-size} ⇒ 生效值 10，N 个 relay worker 与 32~40 个消费线程共享它）
 * 之后还剩多少。本类只产出直接读数（墙钟、聚合吞吐、{@code Com_update} 增量、池 MXBean 轮内
 * 最大值），<strong>不做任何标度裁决断言</strong>——裁决按预注册三支（{@code S_prod(4)@J=0 ≥ 2.0
 * 且 S_prod(4)@J=8 ≥ 1.8 ⇒ 池不是硬约束；任一 ≤ 1.3 ⇒ 池是硬约束；其间/噪声大/非单调 ⇒
 * 证据不足}）在 {@code docs/perf/判别-outbox-relay-池内并发标度.md} 落档，不得事后放宽。</p>
 *
 * <p>协议：</p>
 * <ul>
 *   <li><strong>装配</strong>：真实 {@link HikariDataSource}——{@code setMaximumPoolSize(10)}
 *       （必须是 10 = 生产生效默认值）、{@code setPoolName("task161-pool-it")}、其余全部用 Hikari
 *       默认（<strong>不</strong>设 {@code connectionTimeout}/{@code minimumIdle}/{@code maximumLifetime}
 *       等「让它更好过」的参数）；经该 DataSource 建真实 MyBatis {@link SqlSessionFactory}
 *       （照 TASK-153 范式）；逐行走生产 {@link VerifyEventOutboxMapper#markSent(Long)} 的
 *       <strong>Mapper 代理逐字 SQL</strong>（不另写任何 UPDATE），每次 Mapper 调用
 *       {@code factory.openSession(true)} 从<strong>同一个池</strong>取还一条 autoCommit 连接
 *       （与生产 MyBatis-Spring 无 Spring 事务时「一调用一提交」同语义）。</li>
 *   <li><strong>切分</strong>：先经生产 {@code selectPendingBatch(M, maxRetry)} 取「已取列表」，
 *       再按列表下标 {@code i % N} 切成不重不漏的 N 份（与 TASK-160 生产代码
 *       {@code shards.get(i % sendConcurrency).add(batch.get(i))} 同一切分法）。</li>
 *   <li><strong>臂与轮</strong>：M=2000 行/轮、每臂 3 轮。A 组（无池压力，J=0）N ∈ {1,2,4,8}；
 *       B 组（池占用压力，证伪臂）N=4、J=8——从<strong>同一个</strong>池额外签出 8 条连接在整轮
 *       持有不放（空闲即可），使可用连接 10−8=2 &lt; N=4，强制排队。<strong>J 是「池占用代理
 *       （occupancy proxy）」，不是消费者行为模型</strong>：不得声称它等价于 32~40 个真实消费
 *       线程的负载。</li>
 *   <li><strong>标度量</strong>：{@code S_prod(N) = 中位吞吐(N) / 中位吞吐(1)}；A 组内
 *       S_prod(2)/S_prod(4)/S_prod(8) 以 A 组 N=1 为基；B 组 S_prod(4)@J=8 以同一基线
 *       （A 组 N=1，同实例同装配同池）相除——这是预登记裁决要求的唯一跨组比值，
 *       <strong>除此之外 A 组与 B 组之间不得互相换算或相减</strong>（不得算 B−A 当作排队成本、
 *       不得用 A 的倍数校正 B）。绝对吞吐不在判据内。</li>
 *   <li><strong>每窗口硬判据（A 组 4×3 + B 组 1×3 = 15 个窗口，全过才算数）</strong>：
 *       ① {@code SHOW GLOBAL STATUS} 的 {@code Com_update} 增量精确 = M（前值在重置/取列表/
 *       barrier 之前取，后值在 join 之后、任何收尾 SELECT 之前取——本实例
 *       {@code performance_schema.global_status} 不列 Com_*，TASK-154 实测）；② 窗口内
 *       {@code Com_insert} = {@code Com_delete} = 0；③ 收尾 SENT=M 且 PENDING=0；
 *       ④ 会话门（本轮口径与 TASK-156 不同）：池会常驻持有连接，判据为「除本 IT 自己的池连接
 *       （≤10，按 {@link HikariPoolMXBean#getTotalConnections()} 实读）+ 1 条控制连接外，不得有
 *       任何外来会话」——按 {@code performance_schema.threads} 的 {@code NAME='thread/sql/one_connection'}
 *       计数并用 {@code SHOW PROCESSLIST} 交叉核对（非 Daemon、非自身的会话必须全部落在
 *       scratch 库上），<strong>必须排除</strong> {@code event_scheduler} 与 {@code compress_gtid_table}
 *       两条系统 Daemon 线程（本实例 TYPE='FOREGROUND' 计数含它们，实测），FOREGROUND 原始计数
 *       照记为证据。</li>
 *   <li><strong>池指标（直接证据）</strong>：每轮由采样线程（5ms 间隔）记录
 *       {@code getActiveConnections()}/{@code getIdleConnections()}/{@code getTotalConnections()}/
 *       {@code getThreadsAwaitingConnection()} 的<strong>轮内最大值</strong>。B 组自证门：
 *       {@code getThreadsAwaitingConnection()} 轮内最大值必须 &gt; 0，否则说明 J=8 没真正造成
 *       排队，<strong>该臂作废重做，不得据以裁决第一支</strong>；A 组 N=1 应为 0。口径提醒
 *       （TASK-146）：Hikari 指标是池级聚合、<strong>不可</strong>配对单次调用——本轮只用它证明
 *       有无排队，不用它拆解单行 {@code markSent} 内部构成，也不据此命名 fsync/锁/纯 SQL 占比。</li>
 *   <li><strong>三档退出码（照 TASK-156）</strong>：① 变异红——注入「某 worker 漏标一行」变异，
 *       必须被 {@code Com_update == M} 断言抓住（rc=1），随后字节还原（{@code cmp} rc=0）；
 *       ② 还原复绿——独立全量重跑 rc=0，S_prod 与主证据 run 相互印证；③ 缺变量 skipped——
 *       不设 {@code TASK161_IT_*} ⇒ {@code Tests run: 1, Skipped: 1}、rc=0 但不记真库通过。</li>
 * </ul>
 *
 * <p><b>需要真 MySQL，默认不参与常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集，
 * 只有 {@code -Dtest=} 显式指定才跑），要求三个环境变量，缺任一即 assume 跳过（跳过不算通过）：
 * {@code TASK161_IT_URL} / {@code TASK161_IT_USER} / {@code TASK161_IT_PASSWORD}。
 * 库名必须解析为专用 {@code task161_pool_scratch}，否则直接失败（防止误指演示库 3307）。</p>
 *
 * <p>准备库（与 TASK-147/153/156 同一机械程序；未触碰演示 {@code verify_db} 与其他既有 scratch schema）：
 * <pre>
 * sed 's/verify_db/task161_pool_scratch/g' sql/03-verify-db.sql \
 *   | docker exec -i task131-scratch-mysql mysql -uroot -proot --default-character-set=utf8mb4
 * </pre>
 * 运行（唯一入口；{@code --it} 分支结构上跑不到 verify-service 的 IT——mvn-verify.sh 硬编码
 * IT_CLASSES 与 IT_MODULE=leaderboard-service——只能用 TASK-156 同款带外通道：仓库根
 * {@code .mvn/maven.config} 两行 {@code -Dtest=VerifyOutboxRelayPoolConcurrencyScalingMysqlIT} 与
 * {@code -Dsurefire.failIfNoSpecifiedTests=false}，<strong>任何 git add/commit 之前必须删除</strong>；
 * 不用 {@code MAVEN_OPTS}、不改 mvn-verify.sh、不改任何 pom、不裸用 mvn）：
 * <pre>
 * bash scripts/verify/mvn-verify.sh --mode=offline --pl verify-service test
 * </pre>
 * （环境变量在同一 shell 会话内设置后调用。）
 * </p>
 *
 * <p><b>不得推出</b>：① 本轮零生产行为变化、零已测收益——未连 RocketMQ、未跑
 * {@code syncSend}，对「(a) 并发 syncSend/broker 吞吐」<strong>零信息</strong>；未起四服务、
 * 未跑负载。② J 是池占用代理，不是消费者行为模型。③ 本轮 {@code S_prod(N)} 与 TASK-156 的
 * S(N)（DriverManager 无池装配）<strong>不可并列成「优化前后」</strong>，与 TASK-152 的
 * 18.0 ms/行（演示实例 3307 不同实例不同窗）同样不可比；判别量只有同实例同装配内的
 * {@code S_prod(N)}。④ 不得据此在生产开启 {@code relay-send-concurrency > 1}——relay 生产
 * 池还与 32~40 个消费线程共享，J=8 的占用代理不代表其负载；{@code relay-send-concurrency}
 * 保持默认 1。⑤ 不改 relay-interval/batch/并发/锁/SQL/索引/事务/池/JVM/MQ/
 * {@code innodb_flush_log_at_trx_commit} 任何默认值，不翻案 TASK-153/154，不改写 TASK-152/156
 * 的任何数字。⑥ 推演表（5000ms 下并发单独仅 +16%）的四条算术假设
 * （① syncSend 不并行；② 满批 rows=100；③ S(N) 能从 IT 迁移到生产——正是本轮要验的；
 * ④ 周期 = interval + 锁内）以 spec 预登记为准，随结果一并落档。</p>
 */
class VerifyOutboxRelayPoolConcurrencyScalingMysqlIT {

    private static final String SCHEMA = "task161_pool_scratch";
    private static final int M = 2000;
    private static final int ROUNDS = 3;
    /** 生产生效默认值（application.yml 未设 hikari maximum-pool-size ⇒ Hikari 默认 10）。 */
    private static final int PRODUCTION_EFFECTIVE_POOL_SIZE = 10;
    private static final int MAX_RETRY = 16;
    private static final int SEED_BATCH = 250;
    /** B 组池占用代理：额外签出并整轮持有的连接数（10 − 8 = 2 可用 &lt; N=4）。 */
    private static final int OCCUPANCY_PROXY_CONNECTIONS = 8;
    /** B 组固定 N=4（预登记裁决只引用 S_prod(4)@J=8）。 */
    private static final int GROUP_B_ARM_N = 4;
    private static final int[] ARMS = {1, 2, 4, 8};
    private static final long WARMUP_WAIT_SECONDS = 60;
    private static final long CONNECT_TIMEOUT_SECONDS = 60;
    private static final long BARRIER_TIMEOUT_SECONDS = 120;
    private static final int FOREIGN_SESSION_WAIT_TRIES = 10;
    private static final long SAMPLER_INTERVAL_MS = 5;

    private HikariDataSource dataSource;
    private SqlSessionFactory factory;
    private VerifyEventOutboxMapper perCallSessionMapper;

    @Test
    void markSentPoolConcurrencyScaling_sharedHikariPool_iModNShards() throws Exception {
        String url = System.getenv("TASK161_IT_URL");
        String user = System.getenv("TASK161_IT_USER");
        String password = System.getenv("TASK161_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "缺 TASK161_IT_URL/USER/PASSWORD 环境变量，跳过 scratch MySQL 池内并发标度判别（不视为通过）");
        assertTrue(url.contains(SCHEMA), "JDBC URL 必须显式含 " + SCHEMA + "：url=" + url);

        String markSentSql = realSql(VerifyEventOutboxMapper.class.getMethod("markSent", Long.class));
        try (Connection control = DriverManager.getConnection(url, user, password)) {
            assertTrue(control.getAutoCommit(), "控制连接应为 autocommit");
            assertSchemaIsScratch(control);
            evidence("markSent.sql", markSentSql);
            evidence("markSent.source", "production VerifyEventOutboxMapper#markSent via mapper proxy"
                    + "（每次调用 openSession(true) 从共享池取还连接），无自写 UPDATE");
            registerServerConfig(control);

            setUpSharedPool(url, user, password);

            seedPendingRows(control);

            Map<Integer, ArmResult> groupA = new LinkedHashMap<>();
            for (int n : ARMS) {
                groupA.put(n, runArm(control, "A", 0, n));
            }
            ArmResult groupB = runArm(control, "B", OCCUPANCY_PROXY_CONNECTIONS, GROUP_B_ARM_N);

            double base = groupA.get(1).medianThroughputPerSec;
            evidence("scaling.baseMedianTputPerSec(A.N1.J0)", String.format("%.2f", base));
            StringBuilder s = new StringBuilder("TASK161-SCALING group=A j=0 ");
            for (Map.Entry<Integer, ArmResult> e : groupA.entrySet()) {
                if (e.getKey() == 1) {
                    continue;
                }
                double sn = e.getValue().medianThroughputPerSec / base;
                s.append(String.format("S%d=%.4f ", e.getKey(), sn));
                evidence("scaling.S" + e.getKey() + "@J=0", String.format("%.4f", sn));
            }
            System.out.println(s.toString().trim());
            double s4AtJ8 = groupB.medianThroughputPerSec / base;
            evidence("scaling.S4@J=8", String.format("%.4f", s4AtJ8));
            System.out.printf("TASK161-SCALING group=B j=%d S4=%.4f （基线=A组N=1同实例同装配；"
                            + "除该预登记比值外不得做任何 A↔B 换算或相减）%n",
                    OCCUPANCY_PROXY_CONNECTIONS, s4AtJ8);
        }
    }

    // ------------------------------------------------------------------ 池与 MyBatis 装配

    /** 真实 10 连接 Hikari 池（其余参数全默认）+ 真实 MyBatis factory + 逐次会话 Mapper 代理。 */
    private void setUpSharedPool(String url, String user, String password) throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(PRODUCTION_EFFECTIVE_POOL_SIZE);
        cfg.setPoolName("task161-pool-it");
        // 其余全部 Hikari 默认：不设 connectionTimeout/minimumIdle/maximumLifetime 等。
        dataSource = new HikariDataSource(cfg);

        // 池预热就绪：Hikari 默认 minimumIdle = maximumPoolSize = 10，生产池稳态即常驻 10 条连接；
        // 这个等待只决定「何时开始测量」（稳态），不改变任何池参数、不算「让它更好过」。
        try (Connection c = dataSource.getConnection()) {
            assertSchemaIsScratch(c);
            assertTrue(c.getAutoCommit(), "池连接默认应为 autocommit");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WARMUP_WAIT_SECONDS);
        while (pool().getTotalConnections() < PRODUCTION_EFFECTIVE_POOL_SIZE) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("池在 " + WARMUP_WAIT_SECONDS + "s 内未达到 "
                        + PRODUCTION_EFFECTIVE_POOL_SIZE + " 条连接（total="
                        + pool().getTotalConnections() + "），无法按生产稳态测量");
            }
            Thread.sleep(200L);
        }
        evidence("pool.warmTotalConnections", String.valueOf(pool().getTotalConnections()));
        evidence("pool.maximumPoolSize", String.valueOf(PRODUCTION_EFFECTIVE_POOL_SIZE));
        evidence("pool.otherParams", "Hikari defaults only（未设 connectionTimeout/minimumIdle/maximumLifetime）");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("task161-pool-scratch",
                new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(VerifyEventOutboxMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        perCallSessionMapper = newPerCallSessionMapper();
    }

    /**
     * 逐行会话代理：每次调用打开一个 autoCommit 会话并从共享池取还一条连接
     * （与 MyBatis-Spring 在无 Spring 事务时「一调用一提交」的语义一致，沿用 TASK-153 口径）。
     */
    private VerifyEventOutboxMapper newPerCallSessionMapper() {
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

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private HikariPoolMXBean pool() {
        return dataSource.getHikariPoolMXBean();
    }

    // ------------------------------------------------------------------ 单臂（N 线程 × 3 轮）

    private ArmResult runArm(Connection control, String group, int j, int n) throws Exception {
        double[] walls = new double[ROUNDS];
        long awaitMaxOverall = 0;
        long cuDeltaTotal = 0;
        for (int round = 1; round <= ROUNDS; round++) {
            RoundResult r = runRound(control, group, j, n, round);
            walls[round - 1] = r.wallMs;
            cuDeltaTotal += r.cuDelta;
            awaitMaxOverall = Math.max(awaitMaxOverall, r.awaitMax);
        }
        double[] sortedWalls = walls.clone();
        Arrays.sort(sortedWalls);
        double medianWallMs = sortedWalls[ROUNDS / 2];
        double medianThroughputPerSec = 1000.0 * M / medianWallMs;
        double rangeWallMs = sortedWalls[ROUNDS - 1] - sortedWalls[0];
        ArmResult result = new ArmResult(group, j, n, walls, medianWallMs,
                medianThroughputPerSec, rangeWallMs, cuDeltaTotal, awaitMaxOverall);
        System.out.printf("TASK161-ARM group=%s j=%d arm=%d roundWallMs=%s medianWallMs=%.3f"
                        + " medianThroughputPerSec=%.2f rangeWallMs=%.3f cuDeltaTotal=%d awaitMaxOverall=%d%n",
                group, j, n, formatWalls(walls), medianWallMs, medianThroughputPerSec,
                rangeWallMs, cuDeltaTotal, awaitMaxOverall);
        if ("B".equals(group)) {
            // B 组自证门：J=8 必须造成真实排队，否则该臂作废重做，不得据以裁决第一支。
            assertTrue(awaitMaxOverall > 0,
                    "B 组自证门失败：getThreadsAwaitingConnection() 轮内最大值全为 0，"
                            + "说明 J=" + j + " 没有真正造成池排队，该臂作废重做，不得据以裁决");
        }
        if ("A".equals(group) && n == 1) {
            assertEquals(0L, awaitMaxOverall,
                    "A 组 N=1（单 worker 串行取还连接）不应出现池排队；实测 awaitMaxOverall="
                            + awaitMaxOverall + "（harness 缺陷）");
        }
        return result;
    }

    private RoundResult runRound(Connection control, String group, int j, int n, int round) throws Exception {
        // 窗口外重置：全部 M 行回 PENDING（一条批量 UPDATE；成本不计入臂墙钟、不落入计数窗口）
        resetAllPending(control);

        // 生产 relay 同款取批：先经生产 Mapper selectPendingBatch 取「已取列表」，再按 i % N 切分
        List<Long> pendingIds = fetchPendingIdsThroughPool();
        assertEquals(M, pendingIds.size(),
                "已取列表应恰好 M 行；实测=" + pendingIds.size());
        List<List<Long>> shards = new ArrayList<>(n);
        for (int s = 0; s < n; s++) {
            shards.add(new ArrayList<>());
        }
        for (int i = 0; i < pendingIds.size(); i++) {
            shards.get(i % n).add(pendingIds.get(i));
        }

        // 工作线程：每线程一个逐次会话 Mapper 代理（每行一次自动提交 markSent，从共享池取还连接）
        List<PoolWorker> workers = new ArrayList<>();
        CountDownLatch connected = new CountDownLatch(n);
        AtomicLong t0 = new AtomicLong();
        CyclicBarrier startBarrier = new CyclicBarrier(n + 1, () -> t0.set(System.nanoTime()));
        for (int shard = 0; shard < n; shard++) {
            PoolWorker w = new PoolWorker(perCallSessionMapper, shards.get(shard), shard,
                    connected, startBarrier, t0);
            workers.add(w);
            w.start();
        }
        assertTrue(connected.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "工作线程在超时内未完成连接侧 schema 校验");

        // 会话门：除本 IT 自己的池连接（≤10，MXBean 实读）+ 1 条控制连接外不得有任何外来会话
        int gateClientSessions = waitOutForeignSessions(control);

        // B 组：从同一个池签出 J 条占用连接，整轮持有不放（空闲即可），强制 N 个 worker 排队。
        // J 是「池占用代理（occupancy proxy）」，不是消费者行为模型。
        List<Connection> holders = new ArrayList<>();
        try {
            for (int h = 0; h < j; h++) {
                Connection held = dataSource.getConnection();
                assertSchemaIsScratch(held);
                holders.add(held);
            }
            evidence("occupancy.heldConnections",
                    "group=" + group + " j=" + j + "（池占用代理 occupancy proxy，非消费者行为模型）");

            // 前值：重置/取列表/占用签出之后、barrier 起跑之前
            long cu0 = comCounter(control, "Com_update");
            long ci0 = comCounter(control, "Com_insert");
            long cd0 = comCounter(control, "Com_delete");

            PoolMax poolMax = new PoolMax();
            AtomicBoolean sampling = new AtomicBoolean(true);
            Thread sampler = new Thread(() -> {
                while (sampling.get()) {
                    poolMax.update(pool());
                    try {
                        Thread.sleep(SAMPLER_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "task161-pool-sampler");
            sampler.start();

            // barrier 同步起跑（action 在放行前记录 t0）→ join 收尾
            try {
                startBarrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                for (PoolWorker w : workers) {
                    if (w.error != null) {
                        throw new IllegalStateException("工作线程 shard=" + w.shard + " 起跑前失败", w.error);
                    }
                }
                throw e;
            }
            for (PoolWorker w : workers) {
                w.join();
            }
            long wallNanos = System.nanoTime() - t0.get();

            // 后值：最后一个线程 join 之后、任何收尾 SELECT 之前
            long cu1 = comCounter(control, "Com_update");
            long ci1 = comCounter(control, "Com_insert");
            long cd1 = comCounter(control, "Com_delete");
            long cuDelta = cu1 - cu0;
            long ciDelta = ci1 - ci0;
            long cdDelta = cd1 - cd0;

            for (PoolWorker w : workers) {
                if (w.error != null) {
                    throw new IllegalStateException("工作线程 shard=" + w.shard + " 失败", w.error);
                }
            }
            assertEquals(M, cuDelta,
                    "group=" + group + " j=" + j + " arm=" + n + " round=" + round
                            + "：Com_update 增量必须精确 = M（每行恰好一次单行自动提交 UPDATE，"
                            + "偏离 = harness 缺陷，不得报告该臂标度）；实测=" + cuDelta);
            assertEquals(0, ciDelta, "group=" + group + " arm=" + n + " round=" + round
                    + "：窗口内 Com_insert 增量应为 0；实测=" + ciDelta);
            assertEquals(0, cdDelta, "group=" + group + " arm=" + n + " round=" + round
                    + "：窗口内 Com_delete 增量应为 0；实测=" + cdDelta);

            // 收尾核对 SELECT（在后值读取、占用连接释放之后）：每行恰好一次成功标记、无重复无丢失
            int sentCount = countByStatus(control, "SENT");
            int pendingCount = countByStatus(control, "PENDING");
            assertEquals(M, sentCount, "group=" + group + " arm=" + n + " round=" + round
                    + "：收尾 SENT 应 = M；实测=" + sentCount);
            assertEquals(0, pendingCount, "group=" + group + " arm=" + n + " round=" + round
                    + "：收尾 PENDING 应 = 0；实测=" + pendingCount);

            sampling.set(false);
            sampler.join();
            double wallMs = wallNanos / 1e6;
            double throughputPerSec = 1000.0 * M / wallMs;
            RoundResult r = new RoundResult(wallMs, throughputPerSec, cuDelta, ciDelta, cdDelta,
                    sentCount, pendingCount, gateClientSessions, poolMax.await);
            System.out.printf("TASK161-ROUND group=%s j=%d arm=%d round=%d wallMs=%.3f tputPerSec=%.2f"
                            + " cuDelta=%d ciDelta=%d cdDelta=%d sent=%d pending=%d"
                            + " gateClientSessions=%d awaitMax=%d activeMax=%d idleMax=%d totalMax=%d%n",
                    group, j, n, round, r.wallMs, r.throughputPerSec, r.cuDelta, r.ciDelta,
                    r.cdDelta, r.sentCount, r.pendingCount, r.gateClientSessions,
                    poolMax.await, poolMax.active, poolMax.idle, poolMax.total);
            return r;
        } finally {
            for (Connection held : holders) {
                try {
                    held.close();
                } catch (SQLException closeError) {
                    // 占用连接释放失败不吞掉主流程异常之外的信息；池 close 时兜底
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工作线程

    /**
     * 单工作线程：一个逐次会话 Mapper 代理、按已取列表下标 {@code i % N} 领取互不相交行集，
     * 逐行一次自动提交 {@code markSent}（每次调用从共享池取还一条连接，与生产 relay 同语义）。
     */
    private final class PoolWorker extends Thread {
        private final VerifyEventOutboxMapper mapper;
        private final long[] ids;
        private final int shard;
        private final CountDownLatch connected;
        private final CyclicBarrier startBarrier;
        private final AtomicLong t0;
        private volatile Throwable error;

        PoolWorker(VerifyEventOutboxMapper mapper, List<Long> shardIds, int shard,
                   CountDownLatch connected, CyclicBarrier startBarrier, AtomicLong t0) {
            super("task161-worker-" + shard);
            this.mapper = mapper;
            this.ids = new long[shardIds.size()];
            for (int i = 0; i < shardIds.size(); i++) {
                this.ids[i] = shardIds.get(i);
            }
            this.shard = shard;
            this.connected = connected;
            this.startBarrier = startBarrier;
            this.t0 = t0;
        }

        @Override
        public void run() {
            try {
                // 每条工作路径的硬校验：从池里实际取出的连接必须落在 scratch 库上
                try (SqlSession session = factory.openSession(true)) {
                    assertSchemaIsScratch(session.getConnection());
                }
                connected.countDown();
                startBarrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                for (long id : ids) {
                    int updated = mapper.markSent(id);
                    if (updated != 1) {
                        throw new IllegalStateException(
                                "markSent 应恰好命中 1 行（PENDING→SENT）：id=" + id + " updated=" + updated);
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
     * 外来前台会话门（本轮口径）：池会常驻持有连接，客户会话数不再等于 N。判据为
     * {@code NAME='thread/sql/one_connection'} 计数 == 本 IT 池连接实读（MXBean
     * {@code getTotalConnections()}）+ 1 条控制连接；SHOW PROCESSLIST 交叉核对——
     * 非 Daemon、非自身的会话必须数量一致且全部落在 scratch 库上。本实例
     * （MySQL 8.0.46）TYPE='FOREGROUND' 计数含两条系统 Daemon 线程（event_scheduler、
     * compress_gtid_table，实测），FOREGROUND 原始计数照记为证据。有外来会话则等其结束，
     * 超限即失败。
     */
    private int waitOutForeignSessions(Connection control) throws Exception {
        long selfId = queryLong(control, "SELECT CONNECTION_ID()");
        String dump = "";
        for (int attempt = 0; attempt < FOREIGN_SESSION_WAIT_TRIES; attempt++) {
            dump = processListDump(control);
            long fgRaw = queryLong(control,
                    "SELECT COUNT(*) FROM performance_schema.threads WHERE TYPE = 'FOREGROUND'");
            long clientSessions = queryLong(control,
                    "SELECT COUNT(*) FROM performance_schema.threads WHERE NAME = 'thread/sql/one_connection'");
            long poolTotal = pool().getTotalConnections();
            List<String> foreignDbs = new ArrayList<>();
            try (Statement s = control.createStatement(); ResultSet rs = s.executeQuery("SHOW PROCESSLIST")) {
                while (rs.next()) {
                    boolean daemon = "Daemon".equals(rs.getString(5));
                    boolean self = rs.getLong(1) == selfId;
                    if (!daemon && !self) {
                        foreignDbs.add(String.valueOf(rs.getString(4)));
                    }
                }
            }
            boolean crossCheckOk = foreignDbs.size() == poolTotal;
            for (String db : foreignDbs) {
                crossCheckOk &= SCHEMA.equals(db);
            }
            if (clientSessions == poolTotal + 1 && crossCheckOk) {
                evidence("gate.foreground.rawCount", String.valueOf(fgRaw));
                evidence("gate.clientSessions.p_s_one_connection", String.valueOf(clientSessions));
                evidence("gate.poolConnections.mxbeanTotal", String.valueOf(poolTotal));
                evidence("gate.processlist", dump);
                return (int) clientSessions;
            }
            Thread.sleep(1_000L);
        }
        throw new IllegalStateException("存在外来前台会话（要求客户会话 = 池连接实读 + 1 条控制连接，"
                + "且非 Daemon 非自身会话全部落在 " + SCHEMA + " 上）：SHOW PROCESSLIST = " + dump);
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
                sql.append("('t161-seed-").append(i)
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

    /** 生产 relay 同款取批：经生产 Mapper selectPendingBatch(M, maxRetry) 从共享池取「已取列表」。 */
    private List<Long> fetchPendingIdsThroughPool() {
        try (SqlSession session = factory.openSession(true)) {
            List<VerifyEventOutbox> batch = session.getMapper(VerifyEventOutboxMapper.class)
                    .selectPendingBatch(M, MAX_RETRY);
            List<Long> ids = new ArrayList<>(batch.size());
            for (VerifyEventOutbox row : batch) {
                ids.add(row.getId());
            }
            return ids;
        }
    }

    /** 只读登记持久配置（不改任何配置；与 TASK-156 同四项 + event_scheduler）。 */
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

    /** 防误指：连接必须落在本任务专用 scratch schema 上（每条直取连接都校验）。 */
    private static void assertSchemaIsScratch(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT DATABASE()")) {
            assertTrue(rs.next(), "SELECT DATABASE() 应返回当前库");
            assertEquals(SCHEMA, rs.getString(1),
                    "JDBC URL 必须指向专用 " + SCHEMA + "；本用例绝不触碰演示库");
        }
    }

    /** 从生产 Mapper 注解取真实 SQL 文本，{@code #{id}} 换成 JDBC 占位符（不增删 WHERE 条件）；仅作证据回显。 */
    private static String realSql(Method method) {
        Update update = method.getAnnotation(Update.class);
        assertNotNull(update, method.getName() + " 应是 @Update 注解 SQL");
        return String.join(" ", update.value()).replace("#{id}", "?");
    }

    // ------------------------------------------------------------------ 统计与输出工具

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
        System.out.println("TASK161-EVIDENCE " + key + "=" + value);
    }

    /** 池指标轮内最大值采样容器（池级聚合；只用于证明有无排队，不配对单次调用——TASK-146 口径）。 */
    private static final class PoolMax {
        private long await;
        private long active;
        private long idle;
        private long total;

        synchronized void update(HikariPoolMXBean bean) {
            await = Math.max(await, bean.getThreadsAwaitingConnection());
            active = Math.max(active, bean.getActiveConnections());
            idle = Math.max(idle, bean.getIdleConnections());
            total = Math.max(total, bean.getTotalConnections());
        }
    }

    private static final class RoundResult {
        final double wallMs;
        final double throughputPerSec;
        final long cuDelta;
        final long ciDelta;
        final long cdDelta;
        final int sentCount;
        final int pendingCount;
        final int gateClientSessions;
        final long awaitMax;

        RoundResult(double wallMs, double throughputPerSec, long cuDelta, long ciDelta, long cdDelta,
                    int sentCount, int pendingCount, int gateClientSessions, long awaitMax) {
            this.wallMs = wallMs;
            this.throughputPerSec = throughputPerSec;
            this.cuDelta = cuDelta;
            this.ciDelta = ciDelta;
            this.cdDelta = cdDelta;
            this.sentCount = sentCount;
            this.pendingCount = pendingCount;
            this.gateClientSessions = gateClientSessions;
            this.awaitMax = awaitMax;
        }
    }

    private static final class ArmResult {
        final String group;
        final int j;
        final int n;
        final double[] walls;
        final double medianWallMs;
        final double medianThroughputPerSec;
        final double rangeWallMs;
        final long cuDeltaTotal;
        final long awaitMaxOverall;

        ArmResult(String group, int j, int n, double[] walls, double medianWallMs,
                  double medianThroughputPerSec, double rangeWallMs, long cuDeltaTotal,
                  long awaitMaxOverall) {
            this.group = group;
            this.j = j;
            this.n = n;
            this.walls = walls;
            this.medianWallMs = medianWallMs;
            this.medianThroughputPerSec = medianThroughputPerSec;
            this.rangeWallMs = rangeWallMs;
            this.cuDeltaTotal = cuDeltaTotal;
            this.awaitMaxOverall = awaitMaxOverall;
        }
    }
}
