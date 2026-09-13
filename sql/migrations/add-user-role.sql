-- ============================================================
-- 迁移：user 表新增 role 字段（治理面 RBAC，add-admin-rbac，见 ADR-0007）
--
-- 背景：治理面（/admin/**、规则版本接口）此前裸奔，任何调用方可翻案/改规则。
--       新增最小角色模型（USER/ADMIN 二态），以 role 字段承载；默认 USER，
--       ADMIN 仅经内部接口显式授予。网关按 role 判定管理端接口准入。
--
-- 边界说明：
--   · 存量行由 NOT NULL DEFAULT 'USER' 自动回填为普通用户（最小权限，安全默认）；
--   · 幂等（可重复执行），与 sql/migrations 下其他脚本同风格。
-- ============================================================

USE user_db;

-- 幂等模式：information_schema 判断列不存在才 ALTER（MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS）
SET @stmt := IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'user' AND column_name = 'role') = 0,
    'ALTER TABLE `user` ADD COLUMN `role` VARCHAR(20) NOT NULL DEFAULT ''USER'' COMMENT ''角色：USER 普通用户（默认）/ ADMIN 管理员（治理面接口准入，见 ADR-0007）'' AFTER `nickname`',
    'SELECT ''user.role 已存在，跳过'' AS msg');
PREPARE stmt FROM @stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;