-- ============================================================
-- 迁移：notification 表新增复合索引 idx_user_id (user_id, id)
-- （TASK-244 B17 通知中心索引优化：消除分页慢查 Using filesort）
--
-- 背景：NotificationService.pageNotifications 构造
--       WHERE user_id = ? ORDER BY id DESC；既有索引
--       idx_user_read (user_id, is_read, id) 在查询条件跳过 is_read 时
--       无法为 (user_id, id) 前缀提供覆盖，MySQL 走 idx_user_read 前缀
--       user_id 后再对 id 排序，执行计划出现 Using filesort 慢排序。
--       idx_user_id (user_id, id) 提供精准备序索引，彻底消除 filesort。
--
-- 边界说明：新建库路径为 sql/01-user-db.sql 内的同名 KEY 行；本脚本供存量库升级。
--   与 add-idx-friendship-user-high.sql 同口径：不进 docker-entrypoint-initdb.d，由
--   scripts/db/migrate.sh 按文件名顺序拾取；幂等，可重复执行。
-- ============================================================

USE user_db;

-- 幂等模式：information_schema 判断索引不存在才 ALTER（MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'notification' AND index_name = 'idx_user_id') = 0,
    'ALTER TABLE `notification` ADD INDEX `idx_user_id` (`user_id`, `id`)',
    'SELECT ''notification.idx_user_id 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;