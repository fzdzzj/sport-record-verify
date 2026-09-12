-- ============================================================
-- 迁移：track_point 系列表新增组合索引 idx_record_seq (record_id, seq)
-- （压测变更 spec「瓶颈优化实录」Explain 慢查询案例的优化动作）
--
-- 背景：轨迹分页/校验拉轨迹的访问模式固定为
--       WHERE user_id=? AND record_id=? ORDER BY seq
--       仅有 idx_record(record_id) 时，回表后还需 filesort 按 seq 排序；
--       组合索引 (record_id, seq) 让过滤与排序同源，消除 filesort。
--       Explain 前后对比与耗时数据见 docs/perf/压测报告.md §6。
--
-- 边界说明（重要）：
--   本脚本不放入 docker-entrypoint-initdb.d——首次 initdb 若直接带上索引，
--   「无索引基线」将无法复现。复现口径：
--     1) 全新卷起库（无本索引）→ 跑基线压测 + Explain 前置采样；
--     2) 执行本脚本 → 跑优化后压测 + Explain 后置采样；
--     3) 生产/演示环境直接执行本脚本一次即可（幂等，可重复执行）。
-- ============================================================

USE record_db;

-- 幂等模式：information_schema 判断索引不存在才 ALTER（MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_0' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_0` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_0.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_1' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_1` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_1.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_2' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_2` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_2.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_3' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_3` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_3.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_4' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_4` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_4.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_5' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_5` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_5.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_6' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_6` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_6.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_7' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_7` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_7.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_8' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_8` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_8.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_9' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_9` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_9.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_10' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_10` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_10.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_11' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_11` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_11.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_12' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_12` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_12.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_13' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_13` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_13.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_14' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_14` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_14.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_15' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_15` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_15.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
