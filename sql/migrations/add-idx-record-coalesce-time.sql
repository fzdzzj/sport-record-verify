-- ============================================================
-- 迁移：sport_record 新增 COALESCE 函数索引 idx_user_coalesce_time ((user_id), (COALESCE(start_time, created_at)))
-- （E23 R5，纯索引路径；服务层与查询 SQL 零改动）
--
-- 背景：SportRecordService.myStats 与 GoalService.queryPeriodRecords 的窗口过滤为
--       WHERE ... COALESCE(start_time, created_at) >= {0} ...
--       列被函数包裹，普通索引 idx_user_time(user_id, created_at) 只可用 user_id 前缀，
--       按用户全量回表。MySQL 8.0.13+ 支持的函数索引把 (user_id, COALESCE(start_time, created_at))
--       建成独立索引，使既有 COALESCE 表达式直接 sargable，时间口径语义零漂移（见 spec §1 E23）。
--
-- 边界说明：新建库路径为 sql/02-record-db.sql 内同名 KEY 行（双路径一致，见 "关键要点"）；
--   本脚本供存量库升级。与 add-idx-record-status.sql 同口径：不进 docker-entrypoint-initdb.d，
--   由 scripts/db/migrate.sh 按文件名顺序拾取（migrate.sh 本身不改）；幂等，可重复执行。
-- ============================================================

USE record_db;

-- 幂等模式：information_schema 判断索引不存在才 ALTER（MySQL 8.0 不支持 ADD INDEX IF NOT EXISTS）
-- 函数索引表达式含括号/逗号，与参考 add-idx-record-status.sql 的普通索引同构（PREPARE/EXECUTE 动态 SQL）。
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'sport_record' AND index_name = 'idx_user_coalesce_time') = 0,
    'ALTER TABLE `sport_record` ADD INDEX `idx_user_coalesce_time` ((`user_id`), (COALESCE(`start_time`, `created_at`)))',
    'SELECT ''sport_record.idx_user_coalesce_time 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
