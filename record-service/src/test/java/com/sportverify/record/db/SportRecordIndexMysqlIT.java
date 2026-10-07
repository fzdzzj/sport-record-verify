package com.sportverify.record.db;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * sport_record 滞留扫描复合索引 idx_status_created 的 MySQL 端到端集成测试（TASK-177）。
 *
 * <p>补的是纯单测照不到的两段：①改动后的 {@code sql/02-record-db.sql} 灌进真引擎后，
 * sport_record 的索引集恰为四个且 {@code idx_status_created} 列序为 {@code (status, created_at)}；
 * ②滞留补偿扫描（{@code WHERE status = 1 AND created_at < 阈值 ORDER BY id ASC LIMIT 100}，
 * 文本沿 attempt-1 记录并对当前 SportRecord 实体列序复核后固定，含 archived 列）在复合索引
 * 在场的真库上仍恰好按 id 升序返回前 100 条滞留 VERIFYING，且扫描不改动任何行。</p>
 *
 * <p><b>需要真 MySQL，默认不参与常规构建</b>：类名以 {@code IT} 结尾（surefire 默认不收集，
 * 只有 {@code -Dtest=} 显式指定才跑），并且要求三个环境变量，缺任一即 assume 跳过（不视为通过）：
 * {@code TASK177_IT_URL} / {@code TASK177_IT_USER} / {@code TASK177_IT_PASSWORD}。</p>
 *
 * <p><b>隔离纪律</b>：用例运行时读取仓库工作树的 {@code sql/02-record-db.sql}，机械改名
 * {@code record_db → record_idx_it_scratch} 后在自己的 scratch 库上重建，绝不触碰演示
 * {@code record_db}；跑完可清理：{@code DROP DATABASE IF EXISTS record_idx_it_scratch}。</p>
 *
 * <p>运行（完整命令与退出码留证于 spec/changes/add-verify-degrade-status-index/verification.md）：
 * <pre>
 * TASK177_IT_URL='jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true' \
 * TASK177_IT_USER=root TASK177_IT_PASSWORD=root \
 * mvn -B -ntp -o -s .mvn-settings.xml -pl record-service -am test \
 *   -Dtest=SportRecordIndexMysqlIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * </p>
 */
class SportRecordIndexMysqlIT {

    private static final String SCRATCH_DB = "record_idx_it_scratch";

    /** attempt-2 测量轮阈值 T（与 verification.md 记录的被测 SQL 同一字面量）。 */
    private static final String THRESHOLD = "2026-10-07 15:00:00";

    private static final int VERIFYING = 1;
    private static final int PASSED = 2;
    private static final int MANUAL_REVIEW = 7;

    private static String url;
    private static String user;
    private static String password;

    @BeforeAll
    static void requireEnv() {
        url = System.getenv("TASK177_IT_URL");
        user = System.getenv("TASK177_IT_USER");
        password = System.getenv("TASK177_IT_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "缺 TASK177_IT_URL/USER/PASSWORD 环境变量，跳过 MySQL 集成测试（不视为通过）");
    }

    @Test
    void newDatabaseHasExactlyFourIndexesAndCompositeColumnOrder() throws Exception {
        try (Connection c = openConnection()) {
            rebuildSchemaFromSql02(c);
            Map<String, IndexSpec> indexes = readSportRecordIndexes(c);

            assertEquals(Set.of("PRIMARY", "uk_request_id", "idx_user_time", "idx_status_created"),
                    indexes.keySet(), "sport_record 索引集必须恰为四个（新增 idx_status_created）");
            assertEquals(new IndexSpec(true, List.of("id")), indexes.get("PRIMARY"),
                    "PRIMARY(id) 须原样保留");
            assertEquals(new IndexSpec(true, List.of("request_id")), indexes.get("uk_request_id"),
                    "幂等键唯一索引须原样保留");
            assertEquals(new IndexSpec(false, List.of("user_id", "created_at")), indexes.get("idx_user_time"),
                    "idx_user_time 须原样保留");
            assertEquals(new IndexSpec(false, List.of("status", "created_at")), indexes.get("idx_status_created"),
                    "idx_status_created 须为非唯一二级索引且列序 (status, created_at)");
        }
    }

    @Test
    void stuckScanReturnsFirst100StuckVerifyingInIdOrderAndChangesNoRow() throws Exception {
        try (Connection c = openConnection()) {
            rebuildSchemaFromSql02(c);
            // 存量库经 TASK-175 迁移已含 archived 列；此处仅补列（不加该迁移的 idx_archive），
            // 使扫描 SQL 的列清单与当前 SportRecord 实体列序（archived 介于 status 与 version 之间）严格一致
            execute(c, "ALTER TABLE `sport_record` ADD COLUMN `archived` TINYINT NOT NULL DEFAULT 0 AFTER `status`");
            seedScanSemanticsRows(c);

            List<ScanRow> rows = runStuckScan(c);
            assertEquals(100, rows.size(), "扫描必须返回上限 100 条");
            for (int i = 0; i < rows.size(); i++) {
                assertEquals(4 + i, rows.get(i).id(), "第 " + (i + 1) + " 行必须是 id 升序的滞留 VERIFYING");
                assertEquals(VERIFYING, rows.get(i).status(), "扫描结果不得混入其他状态");
            }
            assertRowCountAndDistributionUnchanged(c);
        }
    }

    private static void seedScanSemanticsRows(Connection c) throws SQLException {
        StringBuilder values = new StringBuilder();
        // 非合格行先入库（id 1..3），被过滤原因各不相同：id=1 新鲜 VERIFYING（created_at 晚于阈值，
        // 被时间条件挡住）；id=2 滞留但状态 MANUAL_REVIEW；id=3 滞留但状态 PASSED（均被状态条件挡住）
        values.append("(1,'it-fresh-verifying',1,1,'2026-10-07 15:30:00','2026-10-07 15:30:00',5.00,600,1,0,0,'2026-10-07 15:30:00')");
        values.append(",(2,'it-stuck-manual-review',2,1,'2026-10-07 14:00:00','2026-10-07 14:00:00',5.00,600,7,0,0,'2026-10-07 14:00:00')");
        values.append(",(3,'it-stuck-passed',3,1,'2026-10-07 14:00:00','2026-10-07 14:00:00',5.00,600,2,0,0,'2026-10-07 14:00:00')");
        for (int i = 0; i < 105; i++) {
            long id = 4 + i;
            values.append(",(").append(id)
                    .append(",'it-stuck-verifying-").append(id).append("'")
                    .append(',').append(id).append(",1")
                    .append(",'2026-10-07 14:00:00','2026-10-07 14:00:00',5.00,600,")
                    .append(VERIFYING).append(",0,0,'2026-10-07 14:00:00')");
        }
        execute(c, "INSERT INTO `sport_record` (`id`,`request_id`,`user_id`,`sport_type`,`start_time`,`end_time`,"
                + "`distance`,`duration`,`status`,`archived`,`version`,`created_at`) VALUES " + values);
    }

    private static List<ScanRow> runStuckScan(Connection c) throws SQLException {
        List<ScanRow> rows = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(SCAN_SQL)) {
            while (rs.next()) {
                rows.add(new ScanRow(rs.getLong("id"), rs.getInt("status")));
            }
        }
        return rows;
    }

    private static void assertRowCountAndDistributionUnchanged(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM `sport_record`")) {
                rs.next();
                assertEquals(108, rs.getLong(1), "扫描不得增删行");
            }
            Map<Integer, Integer> distribution = new TreeMap<>();
            try (ResultSet rs = s.executeQuery("SELECT `status`, COUNT(*) FROM `sport_record` GROUP BY `status`")) {
                while (rs.next()) {
                    distribution.put(rs.getInt(1), rs.getInt(2));
                }
            }
            assertEquals(Map.of(VERIFYING, 106, PASSED, 1, MANUAL_REVIEW, 1), distribution,
                    "扫描不得改变状态分布（106 条 VERIFYING 含 105 滞留 + 1 新鲜 / 1 PASSED / 1 MANUAL_REVIEW）");
        }
    }

    private static final String SCAN_SQL =
            "SELECT `id`, `request_id`, `user_id`, `sport_type`, `start_time`, `end_time`, `distance`, "
                    + "`duration`, `status`, `archived`, `version`, `created_at` FROM `sport_record` "
                    + "WHERE (`status` = " + VERIFYING + " AND `created_at` < '" + THRESHOLD + "') "
                    + "ORDER BY `id` ASC LIMIT 100";

    private static void rebuildSchemaFromSql02(Connection c) throws SQLException, IOException {
        execute(c, "DROP DATABASE IF EXISTS `" + SCRATCH_DB + "`");
        Path sqlFile = findRepoFile("sql/02-record-db.sql");
        String script = new String(Files.readAllBytes(sqlFile), StandardCharsets.UTF_8)
                .replace("record_db", SCRATCH_DB);
        for (String statement : splitStatements(script)) {
            execute(c, statement);
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

    private static Path findRepoFile(String relativePath) {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 " + relativePath + "（自 " + Paths.get("").toAbsolutePath() + " 向上查找）");
    }

    private static Map<String, IndexSpec> readSportRecordIndexes(Connection c) throws SQLException {
        Map<String, Boolean> unique = new TreeMap<>();
        Map<String, List<String>> columns = new TreeMap<>();
        String sql = "SELECT `index_name`, `non_unique`, `column_name` FROM information_schema.statistics "
                + "WHERE `table_schema` = '" + SCRATCH_DB + "' AND `table_name` = 'sport_record' "
                + "ORDER BY `index_name`, `seq_in_index`";
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("index_name");
                unique.putIfAbsent(name, rs.getInt("non_unique") == 0);
                columns.computeIfAbsent(name, k -> new ArrayList<>()).add(rs.getString("column_name"));
            }
        }
        Map<String, IndexSpec> indexes = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : columns.entrySet()) {
            indexes.put(e.getKey(), new IndexSpec(unique.get(e.getKey()), e.getValue()));
        }
        return indexes;
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private record IndexSpec(boolean unique, List<String> columns) {
    }

    private record ScanRow(long id, int status) {
    }
}
