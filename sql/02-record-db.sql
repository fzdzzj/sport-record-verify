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
    `visibility` TINYINT      NOT NULL DEFAULT 0 COMMENT '可见性：0 公开（默认）/1 好友可见/2 仅自己（TASK-221）',
    `version`    INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `created_at` DATETIME     DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_status_created` (`status`, `created_at`),
    -- E23 R5：COALESCE 函数索引——使 myStats/queryPeriodRecords 的
    --   COALESCE(start_time, created_at) >= {0} 过滤直接 sargable（MySQL 8.0.13+，
    --   时间口径统一为 start_time 回退 created_at），服务层与查询 SQL 零改动。
    --   与 sql/migrations/add-idx-record-coalesce-time.sql 双路径逐字一致
    KEY `idx_user_coalesce_time` (`user_id`, (COALESCE(`start_time`, `created_at`)))
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
    KEY `idx_user` (`user_id`),
    KEY `idx_status_settled` (`status`, `settled_at`)
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

-- 榜单每日快照表（TASK-108）：截止当日的 ACTIVE 累计里程快照，供报表只读查询
-- 与 sql/migrations/add-leaderboard-daily-summary.sql 的同名 DDL 必须逐字一致
CREATE TABLE IF NOT EXISTS `leaderboard_daily_summary` (
    `stat_date`      DATE          NOT NULL COMMENT '统计日期（服务器时区，快照当日累计）',
    `user_id`        BIGINT        NOT NULL COMMENT '用户ID',
    `total_distance` DECIMAL(10,2) NOT NULL COMMENT '截止当日 ACTIVE 累计里程（公里）',
    `record_count`   INT           NOT NULL DEFAULT 0 COMMENT '构成该累计的 ACTIVE 贡献条数',
    `updated_at`     DATETIME      NOT NULL COMMENT '本轮结算写入时间',
    PRIMARY KEY (`stat_date`, `user_id`),
    KEY `idx_date_score` (`stat_date`, `total_distance` DESC)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='榜单每日快照表（每轮结算幂等 upsert）';

-- 运动目标表（周/月运动目标，TASK-225）
CREATE TABLE IF NOT EXISTS `sport_goal` (
    `id`           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '目标ID',
    `user_id`      BIGINT         NOT NULL COMMENT '所属用户',
    `period_type`  TINYINT        NOT NULL COMMENT '周期类型：0 周，1 月',
    `metric`       TINYINT        NOT NULL COMMENT '指标类型：0 距离(km)，1 次数',
    `target_value` DECIMAL(10, 2) NOT NULL COMMENT '目标值',
    `created_at`   DATETIME       DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_period_metric` (`user_id`, `period_type`, `metric`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='运动目标表';

-- 用户勋章与成就表（TASK-269）
-- 与 sql/migrations/add-user-badge-table.sql 的同名 DDL 必须逐字一致
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

-- 跑团里程入账锚点表（TASK-278）：record_id 作入账/回退锚点，入账时点的团/周/里程成快照
-- 与 sql/migrations/add-club-distance-contribution.sql 的同名 DDL 必须逐字一致
CREATE TABLE IF NOT EXISTS `club_distance_contribution` (
    `record_id`       BIGINT        NOT NULL COMMENT '记录ID（入账/回退锚点，单列主键）',
    `club_id`         BIGINT        NOT NULL COMMENT '入账时点所在跑团',
    `user_id`         BIGINT        NOT NULL COMMENT '所属用户（成员贡献 ZSet member）',
    `distance_meters` DECIMAL(10,2) NOT NULL COMMENT '入账时点里程（米，恒正快照）',
    `week_start`      DATE          NOT NULL COMMENT '入账时点周锚点（东八口径周一）',
    `status`          TINYINT       NOT NULL COMMENT '锚点状态：1 CREDITED / 0 REVERSED',
    `created_at`      DATETIME      DEFAULT NULL COMMENT '入账时间',
    `reversed_at`     DATETIME      DEFAULT NULL COMMENT '回退时间（CREDITED 时恒 NULL）',
    PRIMARY KEY (`record_id`),
    KEY `idx_club_status` (`club_id`, `status`),
    KEY `idx_week` (`week_start`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='跑团里程入账锚点表（回退以本行快照为权威值）';
