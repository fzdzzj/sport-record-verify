-- ============================================================
-- 迁移：leaderboard_contribution 表新增索引 idx_status_settled (status, settled_at)
-- （TASK-242 B10 存储慢查消灭：周期榜范围聚合与定时结算整表扫描）
--
-- 背景：前台「今日/本周/本月」周期榜 GET /api/leaderboard/range 触发
--       LeaderboardContributionMapper.selectRangeSummaries，按
--       WHERE status = ? AND settled_at >= ? AND settled_at < ? 范围聚合；
--       后台每 10 分钟 settleAndReconcile 的 markSettled 执行
--       UPDATE ... WHERE status = ? AND settled_at IS NULL。两者均需
--       (status, settled_at) 复合索引，缺失时退化为全表扫描与临时表排序，
--       且 markSettled 会做整表排他锁扫描，易引发锁冲突。
--
-- 边界说明：新建库路径为 sql/02-record-db.sql 内的同名 KEY 行；本脚本供存量库升级。
--   与 add-idx-friendship-user-high.sql 同口径：不进 docker-entrypoint-initdb.d，由
--   scripts/db/migrate.sh 按文件名顺序拾取；幂等，可重复执行。
-- ============================================================

USE record_db;

-- 幂等模式：information_schema 判断索引不存在才 ALTER（MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'leaderboard_contribution' AND index_name = 'idx_status_settled') = 0,
    'ALTER TABLE `leaderboard_contribution` ADD INDEX `idx_status_settled` (`status`, `settled_at`)',
    'SELECT ''leaderboard_contribution.idx_status_settled 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;