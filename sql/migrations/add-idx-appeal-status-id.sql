-- ============================================================
-- 迁移：appeal 表新增索引 idx_status_id (status, id)
-- （TASK-249 D2 管理端待审列表查询防护：申诉待审全表扫描 → 覆盖索引）
--
-- 背景：管理端待审列表按 WHERE status = ? ORDER BY id 取申诉单
--       （appeal 表原仅 id 主键 + uk_record(record_id) 两个索引，待审查询
--       无可用前缀，退化为全表扫描 + 临时表排序）；补 (status, id) 复合索引
--       后 status 等值 + id 有序扫描可由索引直接承担。
--
-- 边界说明：新建库路径为 sql/03-verify-db.sql 内 appeal 建表 `KEY idx_status_id (status, id)`
--   同名行（本脚本供存量库升级）；与 add-idx-leaderboard-contribution-status-settled.sql
--   同口径：不进 docker-entrypoint-initdb.d，由 scripts/db/migrate.sh 按文件名顺序拾取；
--   幂等，可重复执行。
-- ============================================================

USE verify_db;

-- 幂等模式：information_schema 判断索引不存在才 ALTER（MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'appeal' AND index_name = 'idx_status_id') = 0,
    'ALTER TABLE `appeal` ADD INDEX `idx_status_id` (`status`, `id`)',
    'SELECT ''appeal.idx_status_id 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
