-- ============================================================
-- 迁移：notification 表新增复合索引 idx_read_created (is_read, created_at)
-- （TASK-246 B21 通知中心历史已读清理：消除历史已读扫描慢查）
--
-- 背景：NotificationService.cleanExpiredReadNotifications 构造
--       WHERE is_read = 1 AND created_at < ?；既有 idx_user_read (user_id, is_read, id)
--       以 user_id 为前缀，纯 is_read 条件无法走前缀，需新增 is_read 引导的
--       复合索引支撑「已读 + 时间」过滤的批删，避免全表扫描拖垮在线写入。
--
-- 边界说明：新建库路径为 sql/01-user-db.sql 内的同名 KEY 行；本脚本供存量库升级。
--   与 add-idx-notification-user-id.sql 同口径：不进 docker-entrypoint-initdb.d，由
--   scripts/db/migrate.sh 按文件名顺序拾取；幂等，可重复执行。
-- ============================================================

USE user_db;

-- 幂等模式：information_schema 判断索引不存在才 ALTER（MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'notification' AND index_name = 'idx_read_created') = 0,
    'ALTER TABLE `notification` ADD INDEX `idx_read_created` (`is_read`, `created_at`)',
    'SELECT ''notification.idx_read_created 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;