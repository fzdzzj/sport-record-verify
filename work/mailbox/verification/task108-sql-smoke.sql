-- TASK-108 SQL smoke test on a throwaway schema (never touches record_db).
-- Run: docker exec -i sport-verify-mysql mysql -uroot -proot --table < this-file

DROP DATABASE IF EXISTS task108_verify;
CREATE DATABASE task108_verify DEFAULT CHARSET utf8mb4;
USE task108_verify;

-- copy of the production DDL under test (sql/02-record-db.sql)
CREATE TABLE IF NOT EXISTS `leaderboard_contribution` (
    `record_id`  BIGINT        NOT NULL,
    `user_id`    BIGINT        NOT NULL,
    `distance`   DECIMAL(8, 2) NOT NULL,
    `status`     TINYINT       NOT NULL,
    `settled_at` DATETIME      DEFAULT NULL,
    PRIMARY KEY (`record_id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- THE NEW DDL: proves the descending index suffix parses on this server
CREATE TABLE IF NOT EXISTS `leaderboard_daily_summary` (
    `stat_date`      DATE          NOT NULL,
    `user_id`        BIGINT        NOT NULL,
    `total_distance` DECIMAL(10,2) NOT NULL,
    `record_count`   INT           NOT NULL DEFAULT 0,
    `updated_at`     DATETIME      NOT NULL,
    PRIMARY KEY (`stat_date`, `user_id`),
    KEY `idx_date_score` (`stat_date`, `total_distance` DESC)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO leaderboard_contribution (record_id, user_id, distance, status) VALUES
    (1, 100, 42.50, 0), (2, 100, 45.55, 0), (3, 101, 88.05, 0), (4, 102, 10.00, 1);

SELECT 'A. upsert #1 (mapper SQL, activeStatus=0)' AS step;
INSERT INTO leaderboard_daily_summary
    (stat_date, user_id, total_distance, record_count, updated_at)
SELECT CURDATE(), user_id, SUM(distance), COUNT(*), NOW()
FROM leaderboard_contribution WHERE status = 0
GROUP BY user_id
ON DUPLICATE KEY UPDATE total_distance = VALUES(total_distance),
    record_count = VALUES(record_count), updated_at = VALUES(updated_at);

SELECT user_id, total_distance, record_count FROM leaderboard_daily_summary ORDER BY total_distance DESC;

SELECT 'B. upsert #2 (idempotency: still 2 rows, no duplicate)' AS step;
INSERT INTO leaderboard_daily_summary
    (stat_date, user_id, total_distance, record_count, updated_at)
SELECT CURDATE(), user_id, SUM(distance), COUNT(*), NOW()
FROM leaderboard_contribution WHERE status = 0
GROUP BY user_id
ON DUPLICATE KEY UPDATE total_distance = VALUES(total_distance),
    record_count = VALUES(record_count), updated_at = VALUES(updated_at);

SELECT COUNT(*) AS row_count_after_second_upsert FROM leaderboard_daily_summary;

SELECT 'C. value update reflected (add distance to user 100)' AS step;
UPDATE leaderboard_contribution SET distance = 100.00 WHERE record_id = 1;
INSERT INTO leaderboard_daily_summary
    (stat_date, user_id, total_distance, record_count, updated_at)
SELECT CURDATE(), user_id, SUM(distance), COUNT(*), NOW()
FROM leaderboard_contribution WHERE status = 0
GROUP BY user_id
ON DUPLICATE KEY UPDATE total_distance = VALUES(total_distance),
    record_count = VALUES(record_count), updated_at = VALUES(updated_at);

SELECT user_id, total_distance FROM leaderboard_daily_summary ORDER BY total_distance DESC;

SELECT 'D. deleteStaleToday (mapper SQL): user 101 fully rolled back' AS step;
UPDATE leaderboard_contribution SET status = 1 WHERE user_id = 101;
DELETE s FROM leaderboard_daily_summary s
LEFT JOIN (SELECT user_id FROM leaderboard_contribution WHERE status = 0
           GROUP BY user_id) a ON a.user_id = s.user_id
WHERE s.stat_date = CURDATE() AND a.user_id IS NULL;

SELECT user_id, total_distance FROM leaderboard_daily_summary ORDER BY total_distance DESC;

SELECT 'E. selectTopByDate (mapper SQL, limit 20) + index usage' AS step;
SELECT stat_date AS statDate, user_id AS userId, total_distance AS totalDistance,
       record_count AS recordCount, updated_at AS updatedAt
FROM leaderboard_daily_summary WHERE stat_date = CURDATE()
ORDER BY total_distance DESC, user_id ASC LIMIT 20;

EXPLAIN SELECT stat_date, user_id, total_distance
FROM leaderboard_daily_summary WHERE stat_date = CURDATE()
ORDER BY total_distance DESC LIMIT 20;

DROP DATABASE task108_verify;
SELECT 'Z. scratch database dropped' AS step;
