-- ============================================================
-- 迁移：sport_record 表 sport_type 列加默认值 + 注释（add-sport-type-threshold）
--
-- 背景：sport_type 列骨架阶段已建（建表 TINYINT NOT NULL），但无默认值，也未声明
--       枚举约束。本变更明确其语义为 SportType.code（1 RUNNING / 2 CYCLING / 3 WALKING）：
--       · 提交缺省回退 RUNNING（DTO 层处理，此处补注释与约束）；
--       · 存量/直插数据缺省回退 RUNNING，保持历史行为不变。
--
-- 幂等（可重复执行），与 sql/migrations 下其他脚本同风格。
-- ============================================================

USE record_db;

-- 幂等模式：取列默认值做存量刷新（/信息 SCHEMA 判断），MySQL 不支持 ADD DEFAULT IF NOT EXISTS
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'sport_record' AND column_name = 'sport_type') > 0,
    'ALTER TABLE `sport_record`
       MODIFY COLUMN `sport_type` TINYINT NOT NULL DEFAULT 1
       COMMENT ''运动类型（SportType.code：1 RUNNING，2 CYCLING，3 WALKING；缺省回退 RUNNING）''',
    'SELECT ''sport_record.sport_type 不存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;