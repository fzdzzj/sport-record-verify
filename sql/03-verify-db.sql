-- ============================================================
-- verify_db 初始化脚本（幂等：可重复执行）
-- 表结构对应《运动记录校验系统需求文档（审批版）》§6.3
-- ============================================================

CREATE DATABASE IF NOT EXISTS verify_db
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE verify_db;

-- 校验结果表（一条记录一个判定，rule_hits 存证据明细）
CREATE TABLE IF NOT EXISTS `verification_result` (
    `record_id`  BIGINT       NOT NULL COMMENT '记录ID',
    `verdict`    TINYINT      NOT NULL COMMENT '判定：0 VERIFYING，1 PASSED，2 REJECTED',
    `score`      INT          DEFAULT NULL COMMENT '综合得分（0-100）',
    `rule_hits`  JSON         DEFAULT NULL COMMENT '命中规则证据明细（见 §5.2）',
    `checked_at` DATETIME     DEFAULT NULL COMMENT '判定时间',
    PRIMARY KEY (`record_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='校验结果表';

-- 申诉表（一条记录一个申诉单，唯一 record_id）
CREATE TABLE IF NOT EXISTS `appeal` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '申诉ID',
    `record_id`     BIGINT       NOT NULL COMMENT '申诉的记录',
    `user_id`       BIGINT       NOT NULL COMMENT '申诉用户',
    `reason`        VARCHAR(500) DEFAULT NULL COMMENT '申诉理由',
    `status`        TINYINT      NOT NULL COMMENT '状态：0 PENDING，1 RE_PASSED，2 RE_CONFIRMED',
    `operator`      VARCHAR(50)  DEFAULT NULL COMMENT '复核操作人',
    `recheck_result` TEXT        DEFAULT NULL COMMENT '复核结论（详细）',
    `created_at`    DATETIME     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_record` (`record_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='申诉表';

-- 规则版本表（规则+阈值快照，配合 Nacos 灰度，见 §7.4）
CREATE TABLE IF NOT EXISTS `rule_version` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '版本ID',
    `version`    VARCHAR(32) NOT NULL COMMENT '版本号（如 v20260413，唯一）',
    `rules_json` TEXT        NOT NULL COMMENT '规则+阈值快照',
    `gray_ratio` INT         NOT NULL DEFAULT 0 COMMENT '灰度比例 0-100',
    `status`     TINYINT     NOT NULL COMMENT '状态：0 GRAY，1 ACTIVE，2 RETIRED',
    `created_at` DATETIME    DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_version` (`version`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='规则版本表';
