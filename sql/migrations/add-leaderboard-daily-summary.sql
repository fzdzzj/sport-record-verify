-- 榜单每日快照表（TASK-108）：截止当日的 ACTIVE 累计里程快照，供报表只读查询。
-- 口径为「累计快照」而非「当日增量」：增量由相邻两日相减得到。
-- 联合主键 (stat_date, user_id) 即幂等保证——每轮结算重复 upsert 同一日同一用户只留一行。
-- 与 sql/02-record-db.sql 末尾的同名 DDL 必须逐字一致（本仓无 Flyway/Liquibase，两处人工同步）。

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
