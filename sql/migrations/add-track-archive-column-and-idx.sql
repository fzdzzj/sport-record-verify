-- ============================================================
-- 迁移：sport_record 补 archived 归档标志列 + idx_archive 索引；
--       track_point_archive_0..15 逐分片补组合索引 idx_record_seq (record_id, seq)
-- （订正来源：E9 / TASK-253，归档列迁移漂移与归档分片缺索引）
--
-- 背景：
--   · sql/05-track-point-archive-shards.sql L238-240 是 sport_record.archived 列 +
--     idx_archive 索引全仓唯一出现处，且注释自证「非幂等：既有库手动执行一次」；
--     scripts/db/migrate.sh 会全量重跑 sql/migrations/*.sql，而 migrations 目录此前
--     无该列迁移 → 05 引入前初始化的存量库升级后 TrackPointArchiveService.sweepOnce
--     （WHERE archived=0）查询即 BadSqlGrammar。
--   · add-idx-record-seq.sql 只覆盖热表 track_point_0..15，track_point_archive 分片
--     仅 idx_record(record_id) 单列索引，缺 (record_id, seq) 复合（归档回读按
--     record_id + seq 排序访问）。
--
-- 双路径关系：
--   1) 新库：经 docker-entrypoint-initdb.d 全量执行 05 脚本自动获得列/索引，本迁移幂等跳过；
--   2) 存量库：经 scripts/db/migrate.sh 全量重跑本迁移获得（本文件即该路径的唯一入口）；
--   3) 已按 05 注释手动执行过列/索引的库：本迁移逐段幂等判断，已存在即跳过，可安全重复执行。
--
-- 边界说明：
--   · 幂等模式沿 add-record-visibility.sql / add-idx-record-seq.sql 先例
--     （information_schema 条件判断 + PREPARE/EXECUTE 动态 SQL；MySQL 8.0 无
--     ADD COLUMN / ADD INDEX IF NOT EXISTS）；
--   · 列定义与 05 脚本 L239-240 逐字一致（双写约定同 add-record-visibility 先例：
--     新装经 05 initdb 自动获得，存量库经本迁移获得）；
--   · 只加不删：归档分片既有 idx_record(record_id) 冗余保留（归档表记录级访问仍可用，
--     不属本课题清理面）；
--   · sql/05-track-point-archive-shards.sql 与 scripts/db/migrate.sh 零触碰。
-- ============================================================

USE record_db;

-- ============ 段 1：sport_record.archived 列 + idx_archive 索引（幂等） ============

-- 列：不存在才 ALTER（列定义与 05 脚本 L239 逐字一致）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'sport_record' AND column_name = 'archived') = 0,
    'ALTER TABLE `sport_record` ADD COLUMN `archived` TINYINT NOT NULL DEFAULT 0 COMMENT ''轨迹点已归档（0 否 1 是）'' AFTER `status`',
    'SELECT ''sport_record.archived 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 索引：不存在才 ALTER（索引定义与 05 脚本 L240 逐字一致）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'sport_record' AND index_name = 'idx_archive') = 0,
    'ALTER TABLE `sport_record` ADD INDEX `idx_archive` (`archived`, `end_time`)',
    'SELECT ''sport_record.idx_archive 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============ 段 2：track_point_archive_0..15 逐分片补 idx_record_seq (record_id, seq)（16 段幂等） ============

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_0' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_0` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_0.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_1' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_1` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_1.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_2' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_2` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_2.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_3' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_3` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_3.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_4' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_4` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_4.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_5' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_5` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_5.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_6' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_6` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_6.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_7' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_7` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_7.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_8' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_8` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_8.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_9' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_9` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_9.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_10' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_10` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_10.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_11' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_11` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_11.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_12' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_12` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_12.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_13' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_13` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_13.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_14' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_14` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_14.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'track_point_archive_15' AND index_name = 'idx_record_seq') = 0,
    'ALTER TABLE `track_point_archive_15` ADD INDEX `idx_record_seq` (`record_id`, `seq`)',
    'SELECT ''track_point_archive_15.idx_record_seq 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
