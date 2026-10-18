package com.sportverify.record.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存量库迁移脚本 USE 库限定词契约测试（TASK-267）。
 *
 * <p>纯 JVM 单测：动态扫描 sql/migrations/*.sql 全部迁移文件，断言每个文件均声明合法的
 * {@code USE (record_db|user_db|verify_db);} 库限定语句（本仓无 Flyway/Liquibase，migrate.sh
 * 逐文件灌库执行，缺 USE 即触发 ERROR 1046 No database selected 并中断全量迁移，
 * 造成其后脚本被静默漏执行）；并针对 add-leaderboard-daily-summary.sql 断言其显式声明
 * {@code USE record_db;}，且建表 DDL 与 sql/02-record-db.sql 末尾同名表逐字一致（两处人工同步契约）。</p>
 */
class SqlMigrationContractTest {

    /** 与 scripts/db/migrate.sh 预检正则同口径：行首 USE + 合法库名 + 分号。 */
    private static final Pattern USE_STATEMENT =
            Pattern.compile("(?m)^\\s*USE\\s+(record_db|user_db|verify_db);");

    private static final String MIGRATIONS_DIR = "sql/migrations";

    private static final String LEADERBOARD_MIGRATION = MIGRATIONS_DIR + "/add-leaderboard-daily-summary.sql";

    private static final String LEADERBOARD_TABLE = "leaderboard_daily_summary";

    private static final String BADGE_MIGRATION = MIGRATIONS_DIR + "/add-user-badge-table.sql";

    private static final String BADGE_TABLE = "user_badge";

    @Test
    void everyMigrationFileDeclaresUseStatement_andLeaderboardSnapshotDdlMatchesMainDdl() throws IOException {
        Path migrationsDir = findRepoPath(MIGRATIONS_DIR);
        List<Path> sqlFiles;
        try (Stream<Path> list = Files.list(migrationsDir)) {
            sqlFiles = list
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        assertTrue(sqlFiles.size() >= 14,
                MIGRATIONS_DIR + " 下应至少存在 14 个迁移脚本，实际 " + sqlFiles.size());

        for (Path sqlFile : sqlFiles) {
            String relativePath = MIGRATIONS_DIR + "/" + sqlFile.getFileName();
            String content = Files.readString(sqlFile, StandardCharsets.UTF_8);
            assertTrue(USE_STATEMENT.matcher(content).find(),
                    relativePath + " 缺少合法的 USE (record_db|user_db|verify_db); 库限定语句，"
                            + "migrate.sh 执行将触发 No database selected 中断全量迁移");
        }

        String migrationContent = Files.readString(findRepoPath(LEADERBOARD_MIGRATION), StandardCharsets.UTF_8);
        assertTrue(migrationContent.contains("USE record_db;"),
                LEADERBOARD_MIGRATION + " 必须显式声明 USE record_db;");
        assertTrue(migrationContent.contains("`" + LEADERBOARD_TABLE + "`"),
                LEADERBOARD_MIGRATION + " 必须包含表名 " + LEADERBOARD_TABLE);

        String mainDdlContent = Files.readString(findRepoPath("sql/02-record-db.sql"), StandardCharsets.UTF_8);
        assertEquals(extractCreateTable(mainDdlContent, "sql/02-record-db.sql", LEADERBOARD_TABLE),
                extractCreateTable(migrationContent, LEADERBOARD_MIGRATION, LEADERBOARD_TABLE),
                LEADERBOARD_MIGRATION + " 的建表 DDL 必须与 sql/02-record-db.sql 末尾同名表逐字一致（两处人工同步）");
    }

    /** 新装双写契约（TASK-269）：user_badge 建表 DDL 在主 DDL 与迁移脚本两处逐字一致。 */
    @Test
    void userBadgeMigrationDeclaresUseRecordDb_andBadgeDdlMatchesMainDdl() throws IOException {
        String migrationContent = Files.readString(findRepoPath(BADGE_MIGRATION), StandardCharsets.UTF_8);
        assertTrue(migrationContent.contains("USE record_db;"),
                BADGE_MIGRATION + " 必须显式声明 USE record_db;");
        assertTrue(migrationContent.contains("`" + BADGE_TABLE + "`"),
                BADGE_MIGRATION + " 必须包含表名 " + BADGE_TABLE);

        String mainDdlContent = Files.readString(findRepoPath("sql/02-record-db.sql"), StandardCharsets.UTF_8);
        assertEquals(extractCreateTable(mainDdlContent, "sql/02-record-db.sql", BADGE_TABLE),
                extractCreateTable(migrationContent, BADGE_MIGRATION, BADGE_TABLE),
                BADGE_MIGRATION + " 的建表 DDL 必须与 sql/02-record-db.sql 末尾同名表逐字一致（两处人工同步）");
    }

    private static String extractCreateTable(String content, String source, String table) {
        int start = content.indexOf("CREATE TABLE IF NOT EXISTS `" + table + "`");
        assertTrue(start >= 0, source + " 未找到 " + table + " 的 CREATE TABLE 语句");
        int end = content.indexOf(';', start);
        assertTrue(end > start, source + " 的 " + table + " CREATE TABLE 语句缺少收尾分号");
        return content.substring(start, end + 1);
    }

    private static Path findRepoPath(String relativePath) {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isDirectory(candidate) || Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 " + relativePath + "（自 " + Paths.get("").toAbsolutePath() + " 向上查找）");
    }
}
