USE record_db;
-- ============================================================
-- 迁移：record_db 新增跑团里程入账锚点表（TASK-278 跑团里程对账与回退补偿）
--
-- 背景：TASK-276 的跑团里程幂等态与入账快照（团、周、距离）只存放在 24h TTL 的 Redis
--       幂等键里——键过期即静默跳过回退、回退按「当前」所在团定位（换团后错扣）、周榜
--       按调用时点归一（跨周改判错扣），并且没有任何持久化权威源供对账自愈。
--       本表把入账快照落库为锚点行（record_id 单列主键），状态迁移走乐观 UPDATE（影响行数
--       = 操作是否真正生效），与 leaderboard_contribution 同构。
--
-- 边界说明：
--   · 建表幂等（CREATE TABLE IF NOT EXISTS），可重复执行；
--   · 写入方仅 record-service（入账/回退旁路），leaderboard-service 只读本表做汇总对账
--     （跨服务同库只读沿 ADR-0005 先例，榜单侧对本表零写入）；
--   · 首行 USE record_db; 为 migrate.sh 逐文件灌库的库限定要求（TASK-267 契约）。
--   与 sql/02-record-db.sql 末尾同名建表 DDL 必须逐字一致（新装双写约定）。
-- ============================================================

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
