-- ============================================================
-- user_db 初始化脚本（幂等：可重复执行）
-- 表结构对应《运动记录校验系统需求文档（审批版）》§6.1
-- 执行方式：docker-entrypoint-initdb.d 首次自动执行，或手动 mysql < 本文件
-- ============================================================

CREATE DATABASE IF NOT EXISTS user_db
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE user_db;

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `phone`         VARCHAR(20)  NOT NULL COMMENT '手机号（登录账号，唯一）',
    `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    `nickname`      VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `role`          VARCHAR(20)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER 普通用户（默认）/ ADMIN 管理员（治理面接口准入，见 ADR-0007）',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用，1 正常',
    `created_at`    DATETIME     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- 好友申请表
CREATE TABLE IF NOT EXISTS `friend_request` (
    `id`         BIGINT    NOT NULL AUTO_INCREMENT COMMENT '申请ID',
    `from_user`  BIGINT    NOT NULL COMMENT '申请人',
    `to_user`    BIGINT    NOT NULL COMMENT '被申请人',
    `status`     TINYINT   NOT NULL COMMENT '状态：0 PENDING，1 ACCEPTED，2 REJECTED，3 CANCELLED',
    `created_at` DATETIME  DEFAULT NULL COMMENT '创建时间',
    `updated_at` DATETIME  DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_to_status` (`to_user`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='好友申请表';

-- 好友关系表（规范化存储：强制 user_low < user_high，主键天然去重 A-B / B-A）
CREATE TABLE IF NOT EXISTS `friendship` (
    `user_low`   BIGINT   NOT NULL COMMENT '较小用户ID',
    `user_high`  BIGINT   NOT NULL COMMENT '较大用户ID',
    `created_at` DATETIME DEFAULT NULL COMMENT '建立时间',
    PRIMARY KEY (`user_low`, `user_high`),
    KEY `idx_user_high` (`user_high`),
    CONSTRAINT `ck_friendship_low_lt_high` CHECK (`user_low` < `user_high`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='好友关系表（user_low < user_high）';

-- 通知表（TASK-182 add-notification-center）
CREATE TABLE IF NOT EXISTS `notification` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '通知ID',
    `user_id`    BIGINT       NOT NULL COMMENT '收件人',
    `type`       VARCHAR(30)  NOT NULL COMMENT '类型：RECORD_VERIFIED 记录通过 / RECORD_REJECTED 记录驳回 / FRIEND_ACCEPTED 好友通过',
    `source_id`  BIGINT       NOT NULL COMMENT '聚合根ID（recordId / requestId）',
    `title`      VARCHAR(100) NOT NULL COMMENT '标题',
    `content`    VARCHAR(255) DEFAULT NULL COMMENT '内容',
    `is_read`    TINYINT      NOT NULL DEFAULT 0 COMMENT '已读：0 未读，1 已读',
    `dedup_key`  VARCHAR(80)  NOT NULL COMMENT '幂等键：判定事件=MQ eventId；好友=FRIEND_ACCEPTED:{requestId}',
    `created_at` DATETIME     NOT NULL COMMENT '创建时间',
    `read_at`    DATETIME     DEFAULT NULL COMMENT '已读时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dedup` (`dedup_key`),
    KEY `idx_user_read` (`user_id`, `is_read`, `id`),
    KEY `idx_user_id` (`user_id`, `id`),
    KEY `idx_read_created` (`is_read`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='通知表';

-- 通知偏好表（TASK-187 add-notification-preference）
CREATE TABLE IF NOT EXISTS `notification_preference` (
    `user_id`    BIGINT      NOT NULL COMMENT '用户ID',
    `type`       VARCHAR(30) NOT NULL COMMENT '通知类型（NotificationType 三类）',
    `enabled`    TINYINT     NOT NULL DEFAULT 1 COMMENT '是否接收：1 开启，0 关闭',
    `updated_at` DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`user_id`, `type`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='通知偏好表';

-- 跑团表（TASK-275 add-running-club）
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

-- 跑团成员关系表（TASK-275 add-running-club）
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
