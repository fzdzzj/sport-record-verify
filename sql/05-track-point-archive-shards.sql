-- ============================================================
-- record_db 轨迹点归档物理分片表 track_point_archive_0..15（幂等：可重复执行）
-- 逻辑表 track_point_archive 由 ShardingSphere 按 user_id % 16 路由到本脚本创建的物理表
-- 说明：docker-entrypoint-initdb.d 按文件名排序执行，本脚本（05）在 04（热表分片）之后
-- 注意：脚本尾部 sport_record 的 ALTER 非幂等，既有库需手动执行一次（重复执行报列已存在属预期），
--       新库经 initdb 全量执行自动获得
-- ============================================================

USE record_db;
-- 分片 0 ：user_id % 16 = 0
CREATE TABLE IF NOT EXISTS `track_point_archive_0` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 0';

-- 分片 1 ：user_id % 16 = 1
CREATE TABLE IF NOT EXISTS `track_point_archive_1` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 1';

-- 分片 2 ：user_id % 16 = 2
CREATE TABLE IF NOT EXISTS `track_point_archive_2` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 2';

-- 分片 3 ：user_id % 16 = 3
CREATE TABLE IF NOT EXISTS `track_point_archive_3` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 3';

-- 分片 4 ：user_id % 16 = 4
CREATE TABLE IF NOT EXISTS `track_point_archive_4` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 4';

-- 分片 5 ：user_id % 16 = 5
CREATE TABLE IF NOT EXISTS `track_point_archive_5` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 5';

-- 分片 6 ：user_id % 16 = 6
CREATE TABLE IF NOT EXISTS `track_point_archive_6` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 6';

-- 分片 7 ：user_id % 16 = 7
CREATE TABLE IF NOT EXISTS `track_point_archive_7` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 7';

-- 分片 8 ：user_id % 16 = 8
CREATE TABLE IF NOT EXISTS `track_point_archive_8` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 8';

-- 分片 9 ：user_id % 16 = 9
CREATE TABLE IF NOT EXISTS `track_point_archive_9` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 9';

-- 分片 10 ：user_id % 16 = 10
CREATE TABLE IF NOT EXISTS `track_point_archive_10` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 10';

-- 分片 11 ：user_id % 16 = 11
CREATE TABLE IF NOT EXISTS `track_point_archive_11` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 11';

-- 分片 12 ：user_id % 16 = 12
CREATE TABLE IF NOT EXISTS `track_point_archive_12` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 12';

-- 分片 13 ：user_id % 16 = 13
CREATE TABLE IF NOT EXISTS `track_point_archive_13` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 13';

-- 分片 14 ：user_id % 16 = 14
CREATE TABLE IF NOT EXISTS `track_point_archive_14` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 14';

-- 分片 15 ：user_id % 16 = 15
CREATE TABLE IF NOT EXISTS `track_point_archive_15` (
    `id`        BIGINT        NOT NULL COMMENT '轨迹点ID（应用雪花ID）',
    `record_id` BIGINT        NOT NULL COMMENT '所属记录',
    `user_id`   BIGINT        NOT NULL COMMENT '分片键（路由必须）',
    `seq`       INT           NOT NULL COMMENT '点序号',
    `lat`       DECIMAL(10,6) DEFAULT NULL COMMENT '纬度',
    `lng`       DECIMAL(10,6) DEFAULT NULL COMMENT '经度',
    `ts`        BIGINT        NOT NULL COMMENT '时间戳（毫秒）',
    `speed`     DECIMAL(6,2)  DEFAULT NULL COMMENT '瞬时速度（m/s）',
    PRIMARY KEY (`id`),
    KEY `idx_record` (`record_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '轨迹点归档分片表 15';

-- ============================================================
-- sport_record 归档标志列与扫描索引（非幂等：既有库手动执行一次）
-- 重复执行报「列已存在」属预期；新库经 docker-entrypoint-initdb.d 全量执行自动获得
-- ============================================================
ALTER TABLE `sport_record`
    ADD COLUMN `archived` TINYINT NOT NULL DEFAULT 0 COMMENT '轨迹点已归档（0 否 1 是）' AFTER `status`,
    ADD INDEX `idx_archive` (`archived`, `end_time`);
