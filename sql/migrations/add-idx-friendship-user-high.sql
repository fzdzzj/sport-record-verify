-- ============================================================
-- 迁移：friendship 表新增索引 idx_user_high (user_high)
-- （TASK-240 B6 存储慢查消灭：好友关系表 user_high 维度 OR 查询索引合并）
--
-- 背景：FriendService.listFriendIds / listFriends 构造条件
--        WHERE user_low = ? OR user_high = ?；主键 (user_low, user_high)
--        仅覆盖 user_low 前缀查找，user_high 维度缺失独立索引，OR 查询无法
--        索引合并（index merge），随关系数增长退化为全表扫描或扫尽全主键。
--        idx_user_high 使优化器可沿 user_high 索引做 range/ref 查找并 merge。
--
-- 边界说明：新建库路径为 sql/01-user-db.sql 内的同名 KEY 行；本脚本供存量库升级。
--   与 add-idx-record-status.sql 同口径：不进 docker-entrypoint-initdb.d，由
--   scripts/db/migrate.sh 按文件名顺序拾取；幂等，可重复执行。
-- ============================================================

USE user_db;

-- 幂等模式：information_schema 判断索引不存在才 ALTER（MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'friendship' AND index_name = 'idx_user_high') = 0,
    'ALTER TABLE `friendship` ADD INDEX `idx_user_high` (`user_high`)',
    'SELECT ''friendship.idx_user_high 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;