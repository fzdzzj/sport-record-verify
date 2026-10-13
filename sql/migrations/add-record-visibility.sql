-- ============================================================
-- 迁移：sport_record 表新增 visibility 字段（记录可见性三态，add-record-visibility，TASK-221）
--
-- 背景：sport_record 此前无可见性，任何登录调用方可读任意记录轨迹/判定/点过赞。
--       新增三态可见性（PUBLIC=0 公开默认 / FRIENDS=1 好友可见 / PRIVATE=2 仅自己）
--       作为读取面逐记录授权的前提（读取路径 pagePoints / getVerifyResult / 点赞三端点统一收敛）。
--
-- 边界说明：
--   · 存量行由 NOT NULL DEFAULT 0 自动回填为 PUBLIC（公开，向后兼容、零破坏）；
--   · 幂等（可重复执行），与 sql/migrations 下其他脚本同风格；
--   · 仅加列，不新增索引（可见性门是 PK 查找 + 内存判断，idx 红线不动）。
--   与 sql/02-record-db.sql 的 sport_record 建表该列声明必须逐字一致（新装双写约定）。
-- ============================================================

USE record_db;

-- 幂等模式：information_schema 判断列不存在才 ALTER（MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'sport_record' AND column_name = 'visibility') = 0,
    'ALTER TABLE `sport_record` ADD COLUMN `visibility` TINYINT NOT NULL DEFAULT 0 COMMENT ''可见性：0 公开（默认）/1 好友可见/2 仅自己（TASK-221）'' AFTER `status`',
    'SELECT ''sport_record.visibility 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
