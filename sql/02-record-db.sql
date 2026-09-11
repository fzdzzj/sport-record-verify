-- ============================================================
-- record_db 初始化脚本（幂等：可重复执行）
-- 表结构对应《运动记录校验系统需求文档（审批版）》§6.2
-- ============================================================

CREATE DATABASE IF NOT EXISTS record_db
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE record_db;

-- 运动记录表（状态机见审批版 §5.1，乐观锁 version 防并发状态迁移）
CREATE TABLE IF NOT EXISTS `sport_record` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `request_id` VARCHAR(64)  NOT NULL COMMENT '客户端幂等键（唯一）',
    `user_id`    BIGINT       NOT NULL COMMENT '所属用户',
    `sport_type` TINYINT      NOT NULL COMMENT '运动类型：1 RUNNING，2 CYCLING ...',
    `start_time` DATETIME     DEFAULT NULL COMMENT '开始时间',
    `end_time`   DATETIME     DEFAULT NULL COMMENT '结束时间',
    `distance`   DECIMAL(8, 2) DEFAULT NULL COMMENT '距离（公里）',
    `duration`   INT          DEFAULT NULL COMMENT '时长（秒）',
    `status`     TINYINT      NOT NULL COMMENT '审核状态（状态机见 §5.1）',
    `version`    INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `created_at` DATETIME     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    KEY `idx_user_time` (`user_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='运动记录表';

-- 轨迹点表（ShardingSphere 分片键：user_id % 16）
-- 骨架阶段先以逻辑表建出；物理分片表 track_point_0..15 随校验引擎变更启用分片配置时创建
CREATE TABLE IF NOT EXISTS `track_point` (
    `id`        BIGINT       NOT NULL COMMENT '轨迹点ID（分布式生成）',
    `record_id` BIGINT       NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT       NOT NULL COMMENT '冗余分片键（路由必须）',
    `seq`       INT          NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10, 6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10, 6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT       NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6, 2) DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='轨迹点表（分片键 user_id % 16）';

-- 榜单贡献表（record_id 作回滚锚点，驳回/改判时据此回滚榜单）
CREATE TABLE IF NOT EXISTS `leaderboard_contribution` (
    `record_id`  BIGINT        NOT NULL COMMENT '记录ID（回滚锚点）',
    `user_id`    BIGINT        NOT NULL COMMENT '所属用户',
    `distance`   DECIMAL(8, 2) NOT NULL COMMENT '贡献里程（仅 PASSED 记录入榜）',
    `status`     TINYINT       NOT NULL COMMENT '当前贡献状态（回滚/改判依据）',
    `settled_at` DATETIME      DEFAULT NULL COMMENT '结算时间',
    PRIMARY KEY (`record_id`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='榜单贡献表';

-- 点赞表（联合主键幂等防重复赞）
CREATE TABLE IF NOT EXISTS `record_like` (
    `record_id`  BIGINT   NOT NULL COMMENT '被赞记录',
    `user_id`    BIGINT   NOT NULL COMMENT '点赞用户',
    `created_at` DATETIME DEFAULT NULL COMMENT '点赞时间',
    PRIMARY KEY (`record_id`, `user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='记录点赞表';
