# add-verify-degrade-status-index

## Why

`VerifyDegradeService` 每分钟扫描滞留在校验中的记录，条件是状态等于 VERIFYING 且创建时间早于阈值。`sport_record` 现有二级索引是 `(user_id, created_at)`，不能服务这个查询，优化器只能沿主键序全表扫描过滤。这是数据库访问因素，不需要先改业务规则或 JVM。

2026-09-25 首轮实施被否（见 verification.md 首行裁决）：当时只交付了 EXPLAIN 估计计划，且种子为 480/16000 全滞留的对抗形态，成本估计反而变差，未按生产形态证明收益；「(status, created_at) 不能在 LIMIT 100 处停止」被一并登记。本修订以 EXPLAIN ANALYZE 双形态证据协议重开：滞留补偿是罕见事件路径，生产形态下滞留记录稀疏，主键全表扫每 60s 一次才是该扫描的真实成本；全滞留形态作为事故复刻如实登记并预注册豁免（判定细则见任务书 §2）。

台账史实：该索引曾于 TASK-103 被虚报为已改动（PLAN.md 记录 `git log -S` 零代码落地），本次为该索引的首次真实落地尝试（第二轮）。

## What Changes

- 为 `sport_record` 增加 `(status, created_at)` 二级索引：新库路径 `sql/02-record-db.sql`，存量库路径新增幂等迁移脚本 `sql/migrations/add-idx-record-status.sql`。
- 用同一条滞留扫描 SQL 的 EXPLAIN 与 EXPLAIN ANALYZE，在双形态种子（S-sparse 生产形态 / S-dense 首轮对抗形态复刻）上做加索引前后对比，按预注册三支判定收口；S-dense 后置劣化属预注册豁免，如实登记不粉饰。
- 复活 `SportRecordIndexMysqlIT`（环境变量 `TASK177_IT_*`，缺变量 assume 跳过不视为通过）：新库索引集与查询语义两例。
- 不改变滞留阈值、扫描上限、状态迁移、补偿和转人工语义；不使用 index hints；不把该索引扩展到无关查询，也不调整连接池或 JVM。

## Impact

- 受影响代码：`sql/02-record-db.sql`（增一行 KEY）、`sql/migrations/add-idx-record-status.sql`（新文件）、`record-service/src/test/java/com/sportverify/record/db/SportRecordIndexMysqlIT.java`（新文件）。
- 受影响行为：只改变滞留扫描的访问路径。
- 不受影响：判定结果、幂等、权限、治理凭证、好友和榜单逻辑；`VerifyDegradeService.java` 与全部业务 Java 零改动；既有迁移脚本零修改。
- 证据边界：只登记访问路径变化与 EXPLAIN ANALYZE 实读行数，不声称任何线上延迟或业务耗时改善。
