USE user_db;
-- ============================================================
-- 迁移：user_db 新增跑团俱乐部与团队里程榜表（TASK-275 跑团俱乐部与团队里程榜系统）
--
-- 背景：平台此前只有个人维度的运动记录与榜单（总榜/好友榜/赛段 KOM），缺少团队与
--       群体层面的组织载体。新增 running_club（跑团主表）与 club_member（成员关系表），
--       承载跑团创建/加入/退出与团队累计里程榜（总榜/周榜）的组织维度。
--
-- 边界说明：
--   · 建表幂等（CREATE TABLE IF NOT EXISTS），可重复执行；
--   · club_member 唯一键 uk_user_club (user_id) 物理保障「一人一团」，
--     服务层并发加入以 DuplicateKeyException 捕获兜底；
--   · 首行 USE user_db; 为 migrate.sh 逐文件灌库的库限定要求（TASK-267 契约）。
--   与 sql/01-user-db.sql 末尾同名建表 DDL 必须逐字一致（新装双写约定）。
-- ============================================================

CREATE TABLE IF NOT EXISTS `running_club` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '跑团ID',
    `name`         VARCHAR(64)  NOT NULL COMMENT '跑团名称（唯一）',
    `description`  VARCHAR(255) DEFAULT '' COMMENT '跑团简介',
    `badge_url`    VARCHAR(255) DEFAULT '' COMMENT '团徽 URL',
    `creator_id`   BIGINT       NOT NULL COMMENT '团长用户ID',
    `member_count` INT          NOT NULL DEFAULT 1 COMMENT '成员数（含团长，创建即 1）',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常，0 解散（仅剩团长一人退团时置 0）',
    `created_at`   DATETIME     NOT NULL COMMENT '创建时间',
    `updated_at`   DATETIME     DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='跑团表';

CREATE TABLE IF NOT EXISTS `club_member` (
    `id`        BIGINT      NOT NULL AUTO_INCREMENT COMMENT '成员记录ID',
    `club_id`   BIGINT      NOT NULL COMMENT '跑团ID',
    `user_id`   BIGINT      NOT NULL COMMENT '成员用户ID',
    `role`      VARCHAR(20) NOT NULL DEFAULT 'MEMBER' COMMENT '角色：CAPTAIN 团长 / MEMBER 团员',
    `joined_at` DATETIME    NOT NULL COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_club` (`user_id`),
    KEY `idx_club_role` (`club_id`, `role`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='跑团成员关系表（一人一团）';
