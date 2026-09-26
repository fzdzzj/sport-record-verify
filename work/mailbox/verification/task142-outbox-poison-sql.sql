-- TASK-142 真实 SQL 判别：outbox 满批重试耗尽行堵住后续可投递行。
-- 运行（隔离 scratch 容器，绝不触碰 verify_db / 演示数据）：
--   docker exec -i <scratch-container> mysql -uroot -proot --table < work/mailbox/verification/task142-outbox-poison-sql.sql
-- 本脚本只读判定：除 DROP/CREATE 本脚本自有 scratch 库与灌种子外，不 UPDATE/DELETE 任何行。
--
-- 旧 SQL（本仓 HEAD 7d44134a 的 VerifyEventOutboxMapper.selectPendingBatch 逐字）：
--   SELECT * FROM verify_event_outbox WHERE status = 'PENDING' ORDER BY id LIMIT #{limit}
-- 新 SQL（本次改动后）：
--   SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < #{maxRetry} ORDER BY id LIMIT #{limit}
--
-- 判别口径（断言一律按 event_id，不依赖自增值）：
--   红 = 旧 SQL 首轮取不到 evt-live-0101（被前 100 条耗尽行遮挡）
--   绿 = 新 SQL 首轮取到 evt-live-0101 与 evt-bound-15，且不含 evt-bound-16 / evt-sent-0104

DROP DATABASE IF EXISTS task142_outbox_scratch;
CREATE DATABASE task142_outbox_scratch DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE task142_outbox_scratch;

-- 生产 DDL 副本（sql/03-verify-db.sql 的 verify_event_outbox，逐字）
CREATE TABLE `verify_event_outbox` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `event_id`    VARCHAR(64)  NOT NULL COMMENT '事件ID（消费端 eventId 去重锚点；relay 重发沿用）',
    `topic`       VARCHAR(128) NOT NULL COMMENT '目标 Topic（record-verify-events）',
    `tag`         VARCHAR(64)  NOT NULL COMMENT '事件 Tag（VERIFIED / REJECTED）',
    `payload`     JSON         NOT NULL COMMENT '事件体 JSON（VerifyEventDTO）',
    `trace_id`    VARCHAR(64)  DEFAULT NULL COMMENT '写入时链路追踪ID（relay 投递透传到消息 userProperty）',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待投递，SENT 已投递',
    `retry_count` INT          NOT NULL DEFAULT 0 COMMENT '投递失败次数（超阈值保留行供人工处理）',
    `created_at`  DATETIME     DEFAULT NULL COMMENT '创建时间',
    `sent_at`     DATETIME     DEFAULT NULL COMMENT '投递成功时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    KEY `idx_status_id` (`status`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='校验事件本地消息表';

-- 种子 1：id=1..100，最小 ID 的满批 100 条 PENDING，retry_count=16（= 默认 maxRetry，全部重试耗尽）
INSERT INTO verify_event_outbox (id, event_id, topic, tag, payload, status, retry_count, created_at)
SELECT s.n,
       CONCAT('evt-exh-', LPAD(s.n, 4, '0')),
       'record-verify-events',
       'VERIFIED',
       JSON_OBJECT('eventId', CONCAT('evt-exh-', LPAD(s.n, 4, '0'))),
       'PENDING',
       16,
       NOW()
FROM (SELECT a.d + b.d * 10 + 1 AS n
      FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
      CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
            UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b) s
WHERE s.n <= 100;

-- 种子 2：ID 更大的边界与正常行
--   id=101 evt-live-0101  PENDING retry_count=0  → 正常可投递行（被 100 条耗尽行遮挡的受害者）
--   id=102 evt-bound-15   PENDING retry_count=15 → 阈值边界 maxRetry-1，仍有资格
--   id=103 evt-bound-16   PENDING retry_count=16 → 阈值边界 maxRetry，应被排除
--   id=104 evt-sent-0104  SENT    retry_count=0  → 已投递行，两种 SQL 都不应选中
INSERT INTO verify_event_outbox (id, event_id, topic, tag, payload, status, retry_count, created_at) VALUES
    (101, 'evt-live-0101', 'record-verify-events', 'VERIFIED', JSON_OBJECT('eventId', 'evt-live-0101'), 'PENDING', 0,  NOW()),
    (102, 'evt-bound-15',  'record-verify-events', 'VERIFIED', JSON_OBJECT('eventId', 'evt-bound-15'),  'PENDING', 15, NOW()),
    (103, 'evt-bound-16',  'record-verify-events', 'VERIFIED', JSON_OBJECT('eventId', 'evt-bound-16'),  'PENDING', 16, NOW()),
    (104, 'evt-sent-0104', 'record-verify-events', 'VERIFIED', JSON_OBJECT('eventId', 'evt-sent-0104'), 'SENT',    0,  NOW());

ANALYZE TABLE verify_event_outbox;

-- ============================================================
-- 前置核对：数据形态
-- ============================================================
SELECT 'P1. 种子形态（期望：PENDING retry>=16 = 101 行，PENDING retry<16 = 2 行，SENT = 1 行）' AS step;
SELECT status,
       CASE WHEN retry_count >= 16 THEN 'retry>=16' ELSE 'retry<16' END AS bucket,
       COUNT(*) AS rows_cnt, MIN(id) AS min_id, MAX(id) AS max_id
FROM verify_event_outbox GROUP BY status, bucket ORDER BY status, bucket;

-- ============================================================
-- A. 旧 SQL 行为（红：满批耗尽行遮挡 evt-live-0101）
-- ============================================================
SELECT 'A1. 旧 SQL 首轮返回（期望遮挡：100 行、id 1..100、contains_live=0）' AS step;
SELECT COUNT(*) AS returned_rows, MIN(id) AS min_id, MAX(id) AS max_id,
       SUM(event_id = 'evt-live-0101') AS contains_live,
       SUM(event_id = 'evt-sent-0104') AS contains_sent,
       SUM(retry_count >= 16) AS returned_exhausted
FROM (SELECT * FROM verify_event_outbox WHERE status = 'PENDING' ORDER BY id LIMIT 100) t;

-- ============================================================
-- B. 新 SQL 行为（绿：耗尽行不占批次，live/bound-15 进入前 N 条）
-- ============================================================
SELECT 'B1. 新 SQL 首轮返回（期望 2 行：contains_live=1, contains_bound15=1, bound16=0, sent=0）' AS step;
SELECT COUNT(*) AS returned_rows, MIN(id) AS min_id, MAX(id) AS max_id,
       GROUP_CONCAT(event_id ORDER BY id) AS event_ids,
       SUM(event_id = 'evt-live-0101') AS contains_live,
       SUM(event_id = 'evt-bound-15') AS contains_bound15,
       SUM(event_id = 'evt-bound-16') AS contains_bound16,
       SUM(event_id = 'evt-sent-0104') AS contains_sent
FROM (SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < 16 ORDER BY id LIMIT 100) t;

-- ============================================================
-- C. 边界与保留语义
-- ============================================================
SELECT 'C1. 边界/正常行原样保留（retry 15/16/0 与 SENT 均未改写）' AS step;
SELECT id, event_id, status, retry_count FROM verify_event_outbox WHERE id IN (101, 102, 103, 104) ORDER BY id;

SELECT 'C2. 新 SQL 不含任何非 PENDING 行（期望 0）' AS step;
SELECT COUNT(*) AS non_pending_selected
FROM (SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < 16 ORDER BY id LIMIT 100) t
WHERE t.status <> 'PENDING';

SELECT 'C3. 耗尽行仍留库供人工处理（期望 101 行 PENDING retry>=16，未删除/未重置）' AS step;
SELECT COUNT(*) AS exhausted_rows_kept FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count >= 16;

-- ============================================================
-- D. 访问路径与实测扫描（只作记录，不声称提速）
-- ============================================================
SELECT 'D1. EXPLAIN 旧 SQL' AS step;
EXPLAIN SELECT * FROM verify_event_outbox WHERE status = 'PENDING' ORDER BY id LIMIT 100;

SELECT 'D2. EXPLAIN 新 SQL' AS step;
EXPLAIN SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < 16 ORDER BY id LIMIT 100;

SELECT 'D3. EXPLAIN ANALYZE 旧 SQL' AS step;
EXPLAIN ANALYZE SELECT * FROM verify_event_outbox WHERE status = 'PENDING' ORDER BY id LIMIT 100;

SELECT 'D4. EXPLAIN ANALYZE 新 SQL' AS step;
EXPLAIN ANALYZE SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < 16 ORDER BY id LIMIT 100;

SELECT 'DONE. scratch 数据未被 UPDATE/DELETE（除本脚本自有库外无副作用）' AS step;
