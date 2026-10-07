-- ============================================================
-- 迁移：sport_record 新增复合索引 idx_status_created (status, created_at)
-- （add-verify-degrade-status-index 变更实施面；双形态 EXPLAIN / EXPLAIN ANALYZE
--   前后对比与实读行数见 spec/changes/add-verify-degrade-status-index/verification.md）
--
-- 背景：滞留 VERIFYING 补偿扫描（VerifyDegradeService.compensateStuckVerifying）的
--       访问模式固定为
--         WHERE status = 1 AND created_at < 阈值 ORDER BY id ASC LIMIT 100
--       无该索引时优化器沿主键序扫描逐行过滤（生产形态实读行数≈全表）；
--       复合索引 (status, created_at) 使其转为该索引的范围扫描，再对候选集排序取前 100。
--
-- 边界说明：新建库路径为 sql/02-record-db.sql 内的同名 KEY 行；本脚本供存量库升级。
--   与 add-idx-record-seq.sql 同口径：不进 docker-entrypoint-initdb.d，由
--   scripts/db/migrate.sh 按文件名顺序拾取；幂等，可重复执行。
-- ============================================================

USE record_db;

-- 幂等模式：information_schema 判断索引不存在才 ALTER（MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'sport_record' AND index_name = 'idx_status_created') = 0,
    'ALTER TABLE `sport_record` ADD INDEX `idx_status_created` (`status`, `created_at`)',
    'SELECT ''sport_record.idx_status_created 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
