package com.sportverify.leaderboard.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.sportverify.leaderboard.entity.LeaderboardDailySummary;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 榜单每日快照 Mapper 的 MySQL 端到端集成测试（TASK-108 收口项）。
 *
 * <p>补的是 Mockito 照不到的那段：注解 SQL 真的被 MyBatis 解析并绑定参数、
 * {@code ON DUPLICATE KEY UPDATE} 与 {@code DELETE ... LEFT JOIN} 在真引擎上的效果、
 * {@code LocalDate} 参数与 {@code DECIMAL(10,2)} 结果的映射。
 * 单元测试里 {@code #{activeStatus}} 从来没变成过 JDBC 占位符，所以"SQL 手工跑通"
 * 与"框架跑得通"是两件事。</p>
 *
 * <p><b>需要真 MySQL，默认不参与常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集，
 * 只有 {@code -Dtest=} 显式指定才跑），并且要求三个环境变量，缺任一即 assume 跳过：
 * {@code TASK108_IT_URL} / {@code TASK108_IT_USER} / {@code TASK108_IT_PASSWORD}。</p>
 *
 * <p>准备库（跑完可 DROP）：把仓库里的 {@code sql/02-record-db.sql} 机械改名灌进 scratch 库，
 * 因此验的就是提交里那份 DDL 本身，不是副本：
 * <pre>
 * sed 's/record_db/task108_it/g' sql/02-record-db.sql \
 *   | docker exec -i sport-verify-mysql mysql -uroot -proot
 * </pre>
 * 运行：
 * <pre>
 * mvn -B -ntp -o -s .mvn-settings.xml -pl leaderboard-service -am test \
 *   -Dtest=LeaderboardDailySummaryMapperMysqlIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * </p>
 */
class LeaderboardDailySummaryMapperMysqlIT {

    private static final int ACTIVE = 0;
    private static final int ROLLED_BACK = 1;

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TASK108_IT_URL");
        String user = System.getenv("TASK108_IT_USER");
        String password = System.getenv("TASK108_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "缺 TASK108_IT_URL/USER/PASSWORD 环境变量，跳过 MySQL 集成测试（不视为通过）");

        UnpooledDataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, user, password);
        try (Connection c = dataSource.getConnection(); java.sql.Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM leaderboard_daily_summary");
            s.executeUpdate("DELETE FROM leaderboard_contribution");
            s.executeUpdate("INSERT INTO leaderboard_contribution (record_id, user_id, distance, status) VALUES "
                    + "(1,100,42.50,0),(2,100,45.55,0),(3,101,88.05,0),(4,102,10.00,1)");
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("mysql", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(LeaderboardDailySummaryMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private List<LeaderboardDailySummary> top(int limit) {
        return topAt(LocalDate.now(), limit);
    }

    private List<LeaderboardDailySummary> topAt(LocalDate date, int limit) {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(LeaderboardDailySummaryMapper.class).selectTopByDate(date, limit);
        }
    }

    private int upsert() {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(LeaderboardDailySummaryMapper.class)
                    .upsertFromActiveContributions(ACTIVE);
        }
    }

    private int purgeStaleToday() {
        try (SqlSession session = factory.openSession(true)) {
            return session.getMapper(LeaderboardDailySummaryMapper.class).deleteStaleToday(ACTIVE);
        }
    }

    @Test
    void upsertAggregatesActiveRowsAndIsIdempotent() {
        upsert();
        List<LeaderboardDailySummary> today = top(20);

        // 只有 ACTIVE 进报表：102 的 ROLLED_BACK 贡献被 WHERE status=0 挡掉
        assertEquals(2, today.size(), "应只有 100/101 两名 ACTIVE 用户入快照");
        // 并列分数由 user_id ASC 决定次序，报表才可复现
        assertEquals(100L, today.get(0).getUserId());
        assertEquals(2, today.get(0).getRecordCount());
        assertEquals(0, new BigDecimal("88.05").compareTo(today.get(0).getTotalDistance()));
        assertEquals(2, today.get(0).getTotalDistance().scale(), "DECIMAL(10,2) 须原样映回两位小数");
        assertEquals(101L, today.get(1).getUserId());

        // 再跑一次：联合主键幂等，不多出行、值仍对
        upsert();
        assertEquals(2, top(20).size(), "upsert 必须幂等（同一日同一用户只留一行）");
    }

    @Test
    void deleteStaleTodayDropsUsersWhoseContributionsWereAllRolledBack() throws Exception {
        upsert();
        assertEquals(2, top(20).size());

        try (Connection c = factory.getConfiguration().getEnvironment().getDataSource().getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE leaderboard_contribution SET status = " + ROLLED_BACK + " WHERE user_id = 101");
        }

        upsert();
        purgeStaleToday();

        List<LeaderboardDailySummary> today = top(20);
        assertEquals(1, today.size(), "全量回滚的用户必须从当日快照清掉，否则报表挂着幽灵里程");
        assertEquals(100L, today.get(0).getUserId());
    }

    @Test
    void selectTopByDateIsolatesDatesAndHonoursLimit() throws Exception {
        upsert();
        try (Connection c = factory.getConfiguration().getEnvironment().getDataSource().getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO leaderboard_daily_summary (stat_date, user_id, total_distance, record_count, "
                    + "updated_at) VALUES (DATE_SUB(CURDATE(), INTERVAL 1 DAY), 555, 7.00, 1, NOW())");
        }

        List<LeaderboardDailySummary> yesterday = topAt(LocalDate.now().minusDays(1), 20);
        assertEquals(1, yesterday.size(), "查昨日不应看见今日快照");
        assertEquals(555L, yesterday.get(0).getUserId());

        List<LeaderboardDailySummary> capped = top(1);
        assertEquals(1, capped.size(), "LIMIT 参数必须由 #{limit} 真正下推到 MySQL");
        assertTrue(capped.get(0).getUpdatedAt() != null);
    }
}
