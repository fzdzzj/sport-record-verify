USE record_db;
-- ============================================================
-- 迁移：record_db 新增用户勋章与成就表（TASK-269 运动徽章与成就体系）
--
-- 背景：平台缺少用户可见的成就勋章墙与正向激励反馈，新增 user_badge 承载
--       9 大核心成就的解锁与佩戴状态（读时结算 + 增量幂等持久化模式）。
--
-- 边界说明：
--   · 建表幂等（CREATE TABLE IF NOT EXISTS），可重复执行；
--   · 唯一键 uk_user_badge (user_id, badge_code) 保证每勋章至多一行，
--     服务层并发解锁以 DuplicateKeyException 捕获兜底；
--   · 首行 USE record_db; 为 migrate.sh 逐文件灌库的库限定要求（TASK-267 契约）。
--   与 sql/02-record-db.sql 末尾同名建表 DDL 必须逐字一致（新装双写约定）。
-- ============================================================

CREATE TABLE IF NOT EXISTS `user_badge` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL COMMENT '所属用户ID',
    `badge_code`  VARCHAR(64)  NOT NULL COMMENT '勋章编码',
    `is_equipped` TINYINT      NOT NULL DEFAULT 0 COMMENT '是否佩戴：0 否，1 是',
    `unlocked_at` DATETIME     NOT NULL COMMENT '解锁时间',
    `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_badge` (`user_id`, `badge_code`),
    KEY `idx_user_equipped` (`user_id`, `is_equipped`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户勋章与成就表';
