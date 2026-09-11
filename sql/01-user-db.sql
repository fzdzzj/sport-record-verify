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
    CONSTRAINT `ck_friendship_low_lt_high` CHECK (`user_low` < `user_high`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='好友关系表（user_low < user_high）';
