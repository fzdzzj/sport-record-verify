package com.sportverify.record.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sport_record 函数索引定义与迁移脚本语法契约测试（TASK-264）。
 *
 * <p>纯 JVM 单测，读取 sql/02-record-db.sql 与 sql/migrations/add-idx-record-coalesce-time.sql，
 * 断言两处均采用合法的 MySQL 8.0 复合函数索引语法 {@code `idx_user_coalesce_time` (`user_id`, (COALESCE(`start_time`, `created_at`)))}，
 * 且绝对不含导致 MySQL 报错 {@code ERROR 3762 (HY000)} 的列名双括号错误语法（如 {@code ((`user_id`)} 或 {@code ((user_id)}）。</p>
 */
class SportRecordSqlContractTest {

    private static final String EXPECTED_SYNTAX =
            "`idx_user_coalesce_time` (`user_id`, (COALESCE(`start_time`, `created_at`)))";

    @Test
    void functionalIndexSyntax_validAndFreeOfDoubleParentheses() throws IOException {
        List<String> targetFiles = List.of(
                "sql/02-record-db.sql",
                "sql/migrations/add-idx-record-coalesce-time.sql"
        );

        for (String relativePath : targetFiles) {
            Path file = findRepoFile(relativePath);
            String content = Files.readString(file, StandardCharsets.UTF_8);

            assertTrue(content.contains(EXPECTED_SYNTAX),
                    relativePath + " 必须包含合法的复合函数索引语法: " + EXPECTED_SYNTAX);

            assertFalse(content.contains("((`user_id`)"),
                    relativePath + " 绝对不得包含带双括号的列名表达式语法 ((`user_id`)");

            assertFalse(content.contains("((user_id)"),
                    relativePath + " 绝对不得包含带双括号的列名表达式语法 ((user_id)");
        }
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
}
