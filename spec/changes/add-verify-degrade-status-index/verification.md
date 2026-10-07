审核不通过，未提交。原因是同一 SQL 的 EXPLAIN ANALYZE 未证明变快，且 (status, created_at) 不能在 LIMIT 100 处停止。

# 验证记录：add-verify-degrade-status-index

实施日期：2026-09-25。scratch 库：`record_idx_scratch`（容器 `sport-verify-mysql`，MySQL **8.0.46**）。

## 范围

- 只新增 `sport_record.idx_status_created(status, created_at)`：新库路径 `sql/02-record-db.sql`，存量库路径 `sql/migrations/add-idx-record-status.sql`（幂等，经 `scripts/db/migrate.sh` 拾取）。
- 滞留扫描 SQL（`VerifyDegradeService.compensateStuckVerifying` 生成的 `WHERE (status = ? AND created_at < ?) ORDER BY id ASC LIMIT 100`）、滞留阈值（120s）、扫描上限（100）、状态迁移、补偿与转人工语义零改动。
- 未调整连接池、JVM，未改动其他索引。

## 前后 EXPLAIN（同一条 SQL、同一批数据、同一库）

scratch 库由 `git show HEAD:sql/02-record-db.sql`（改动前 DDL，仅 `sed 's/record_db/record_idx_scratch/'`）灌出；种子 16000 行（14560 PASSED / 640 REJECTED / 480 VERIFYING 且全部早于阈值 2026-09-25 06:00:00 / 320 MANUAL_REVIEW），前后两次 EXPLAIN 之间数据未变、仅 `ALTER TABLE ... ADD INDEX idx_status_created (status, created_at)`，各自先 `ANALYZE TABLE`。

SQL（VERIFYING code = 1）：

```sql
EXPLAIN SELECT id, request_id, user_id, sport_type, start_time, end_time, distance, duration, status, version, created_at
FROM sport_record
WHERE (status = 1 AND created_at < '2026-09-25 06:00:00')
ORDER BY id ASC
LIMIT 100;
```

### 加索引前（possible_keys 为空，只能沿主键序扫描过滤）

```
id | select_type | table        | type  | possible_keys | key     | key_len | ref  | rows | filtered | Extra
1  | SIMPLE      | sport_record | index | NULL          | PRIMARY | 8       | NULL | 100  | 3.33     | Using where

-> Limit: 100 row(s)  (cost=9.82 rows=3.33)
    -> Filter: ((sport_record.`status` = 1) and (sport_record.created_at < TIMESTAMP'2026-09-25 06:00:00'))  (cost=9.82 rows=3.33)
        -> Index scan on sport_record using PRIMARY  (cost=9.82 rows=100)
```

### 加索引后（range 走 idx_status_created）

```
id | select_type | table        | type  | possible_keys      | key                | key_len | ref  | rows | filtered | Extra
1  | SIMPLE      | sport_record | range | idx_status_created | idx_status_created | 7       | NULL | 480  | 100.00   | Using index condition; Using filesort

-> Limit: 100 row(s)  (cost=216 rows=100)
    -> Sort: sport_record.id, limit input to 100 row(s) per chunk  (cost=216 rows=480)
        -> Index range scan on sport_record using idx_status_created
           over (status = 1 AND NULL < created_at < '2026-09-25 06:00:00'),
           with index condition: ((sport_record.`status` = 1) and (sport_record.created_at < TIMESTAMP'2026-09-25 06:00:00'))
```

结论：加索引后该查询由 `key=PRIMARY` 的索引扫描+过滤变为 `type=range, key=idx_status_created, rows=480` 的索引范围扫描（filesort 仅对 480 条候选排序取前 100）。**只记录执行计划变化；本环境未做线上延迟压测，不声称延迟已改善。**

## 迁移脚本验证

`sql/migrations/add-idx-record-status.sql`（仅 `sed` 替换 USE 行指向 scratch 库）在改动前 DDL 的 scratch 库上连续执行两次：第一次建出索引，第二次输出 `sport_record.idx_status_created already exists, skip`（幂等）。执行后 `SHOW INDEX` 恰为四个索引：`PRIMARY(id)`、`uk_request_id(request_id)`、`idx_user_time(user_id, created_at)`、`idx_status_created(status, created_at)`。

## 测试与退出码

| 命令 | 覆盖 | 结果 |
| --- | --- | --- |
| `bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` | record-service 83 例 + 上游模块（常规套件，IT 类不收集） | BUILD SUCCESS，**退出码 0** |
| `RECORD_INDEX_IT_URL='jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true' RECORD_INDEX_IT_USER=root RECORD_INDEX_IT_PASSWORD=root mvn -B -ntp -o -s .mvn-settings.xml -pl record-service -am test -Dtest=SportRecordIndexMysqlIT -Dsurefire.failIfNoSpecifiedTests=false` | `record-service/src/test/java/com/sportverify/record/db/SportRecordIndexMysqlIT.java`（2 例） | Tests run: 2, Failures: 0, Errors: 0，**退出码 0** |

`SportRecordIndexMysqlIT`（需要真 MySQL，缺环境变量即 assume 跳过，不计为通过；连接的是仓库 MySQL 容器映射出的宿主机 3307 端口）：

1. **新库包含复合索引**：运行时读取仓库工作树的 `sql/02-record-db.sql`，机械改名 `record_db → record_idx_it_scratch` 后在真 MySQL 上建库，断言索引集合恰为四个，且 `idx_status_created` 为非唯一二级索引、列序 `(status, created_at)`，主键/幂等键/`idx_user_time` 原样保留。
2. **查询语义保持不变**：种子 105 条滞留 VERIFYING + 1 条新鲜 VERIFYING + 1 条滞留 MANUAL_REVIEW + 1 条滞留 PASSED，滞留扫描 SQL 恰好按 id 升序返回前 100 条滞留 VERIFYING；总行数（108）与状态分布（VERIFYING 106 / PASSED 1 / MANUAL_REVIEW 1）扫描后不变。

## 遗留说明

- 演示数据卷里的存量 `record_db` 未被本变更触碰；存量库升级由运维按 `sql/migrations/README.md` 执行 `bash scripts/db/migrate.sh` 补齐该索引。
- 未提交、未 push；`.trae/` 未触碰。

---

# attempt-2（2026-10-07，TASK-177）：双形态复测与索引复活落地

> 上文 attempt-1 的裁决与证据原样保留为历史，本段为其后第二轮的追加记录。本环境未做线上延迟压测：只登记访问路径（执行计划）变化与 ANALYZE 实读行数，不声称延迟已改善。

## 测量环境与正交性

- scratch 库 `record_idx_scratch`（容器 `sport-verify-mysql`，MySQL **8.0.46**），绝不触碰演示 `record_db`。
- scratch 由改动前 DDL 灌出（`git show 06c8e6133e8e87fb30186c56a670c9838821fb2d:sql/02-record-db.sql` 机械改名），另套用 TASK-175 存量迁移（`sql/05-track-point-archive-shards.sql` 的 sport_record 段）补 `archived` 列与 `idx_archive`，使索引环境与现网存量库一致（现网 sport_record 四索引：PRIMARY / uk_request_id / idx_user_time / idx_archive）。
- 被测 SQL（列清单与当前 `SportRecord` 实体列序一致，含 `archived`；WHERE / ORDER / LIMIT 沿 attempt-1 文本）：

```sql
SELECT id, request_id, user_id, sport_type, start_time, end_time, distance, duration, status, archived, version, created_at
FROM sport_record
WHERE (status = 1 AND created_at < '2026-10-07 15:00:00')
ORDER BY id ASC
LIMIT 100;
```

- 阈值字面量 T = `2026-10-07 15:00:00`。每形态两次捕获之间数据不变，仅差一步 `ALTER TABLE sport_record ADD INDEX idx_status_created (status, created_at)`；每次捕获前先 `ANALYZE TABLE sport_record`，EXPLAIN 与 EXPLAIN ANALYZE 双留档。

## 种子 SQL 全文

### S-sparse（生产形态，16000 行）

```sql
-- TASK-177 S-sparse seed, 16000 rows. Threshold literal T = '2026-10-07 15:00:00' (runtime-fixed).
-- Composition per TASK-177 spec 2.1: 14537 PASSED + 640 REJECTED + 320 MANUAL_REVIEW
-- (terminal created_at spread over T-48h..T) + 500 fresh VERIFYING (created_at = T+30min)
-- + 3 stuck VERIFYING (created_at = T-60min).
-- Interleaving: ord = ((n-1)*7919) mod 16000 + 1 is a bijection over 1..16000
-- (gcd(7919,16000)=1), so every status class is scattered across the id space; no contiguous blocks.
SET SESSION cte_max_recursion_depth = 100000;
USE record_idx_scratch;
INSERT INTO sport_record (request_id, user_id, sport_type, start_time, end_time, distance, duration, status, created_at)
WITH RECURSIVE
seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 16000
),
mapped AS (
    SELECT n, ((n - 1) * 7919) % 16000 + 1 AS ord FROM seq
),
shaped AS (
    SELECT n, ord,
        CASE
            WHEN ord <= 15497 THEN DATE_SUB('2026-10-07 15:00:00', INTERVAL (n % 2880) MINUTE)
            WHEN ord <= 15997 THEN DATE_ADD('2026-10-07 15:00:00', INTERVAL 30 MINUTE)
            ELSE DATE_SUB('2026-10-07 15:00:00', INTERVAL 60 MINUTE)
        END AS created_at
    FROM mapped
)
SELECT
    CONCAT('seed-sparse-', LPAD(n, 5, '0')),
    1000 + (n % 500),
    1,
    created_at,
    created_at,
    (n % 200) / 10.0,
    600 + (n % 3600),
    CASE
        WHEN ord <= 14537 THEN 2
        WHEN ord <= 15177 THEN 3
        WHEN ord <= 15497 THEN 7
        ELSE 1
    END,
    created_at
FROM shaped;
```

### S-dense（首轮对抗形态复刻，16000 行）

```sql
-- TASK-177 S-dense seed, 16000 rows (attempt-1 adversarial shape). Threshold literal T = '2026-10-07 15:00:00'.
-- Composition per TASK-177 spec 2.1: 14560 PASSED + 640 REJECTED + 320 MANUAL_REVIEW
-- (terminal created_at spread over T-48h..T) + 480 VERIFYING all stuck
-- (created_at spread over T-60min..T-2879min, all earlier than T).
-- Interleaving: same 7919-mod-16000 bijection over 1..16000; no contiguous blocks.
SET SESSION cte_max_recursion_depth = 100000;
USE record_idx_scratch;
INSERT INTO sport_record (request_id, user_id, sport_type, start_time, end_time, distance, duration, status, created_at)
WITH RECURSIVE
seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 16000
),
mapped AS (
    SELECT n, ((n - 1) * 7919) % 16000 + 1 AS ord FROM seq
),
shaped AS (
    SELECT n, ord,
        CASE
            WHEN ord <= 15520 THEN DATE_SUB('2026-10-07 15:00:00', INTERVAL (n % 2880) MINUTE)
            ELSE DATE_SUB('2026-10-07 15:00:00', INTERVAL (60 + (n % 2820)) MINUTE)
        END AS created_at
    FROM mapped
)
SELECT
    CONCAT('seed-dense-', LPAD(n, 5, '0')),
    1000 + (n % 500),
    1,
    created_at,
    created_at,
    (n % 200) / 10.0,
    600 + (n % 3600),
    CASE
        WHEN ord <= 14560 THEN 2
        WHEN ord <= 15200 THEN 3
        WHEN ord <= 15520 THEN 7
        ELSE 1
    END,
    created_at
FROM shaped;
```

### 采集脚本（每组合执行一次：ANALYZE → EXPLAIN → EXPLAIN ANALYZE → SHOW INDEX）

```sql
-- TASK-177 capture file: executed once per combination (sparse-before, sparse-after,
-- dense-before, dense-after). ANALYZE TABLE runs immediately before both EXPLAIN forms
-- in each execution; the only change between a before/after pair is the ADD INDEX.
USE record_idx_scratch;
SELECT VERSION() AS server_version;
SELECT status, COUNT(*) AS cnt FROM sport_record GROUP BY status ORDER BY status;
SELECT COUNT(*) AS total FROM sport_record;
ANALYZE TABLE sport_record;
EXPLAIN SELECT id, request_id, user_id, sport_type, start_time, end_time, distance, duration, status, archived, version, created_at FROM sport_record WHERE (status = 1 AND created_at < '2026-10-07 15:00:00') ORDER BY id ASC LIMIT 100;
EXPLAIN ANALYZE SELECT id, request_id, user_id, sport_type, start_time, end_time, distance, duration, status, archived, version, created_at FROM sport_record WHERE (status = 1 AND created_at < '2026-10-07 15:00:00') ORDER BY id ASC LIMIT 100;
SHOW INDEX FROM sport_record;
```

## 四组合读数（MySQL 8.0.46 实测）

### S-sparse 加索引前（状态分布 1=503 / 2=14537 / 3=640 / 7=320，合计 16000）

```
EXPLAIN：
id=1 SIMPLE sport_record type=index possible_keys=NULL key=PRIMARY key_len=8 ref=NULL rows=100 filtered=3.33 Extra=Using where

EXPLAIN ANALYZE：
-> Limit: 100 row(s)  (cost=9.82 rows=3.33) (actual time=2.35..3.36 rows=3 loops=1)
    -> Filter: ((sport_record.`status` = 1) and (sport_record.created_at < TIMESTAMP'2026-10-07 15:00:00'))  (cost=9.82 rows=3.33) (actual time=2.33..3.34 rows=3 loops=1)
        -> Index scan on sport_record using PRIMARY  (cost=9.82 rows=100) (actual time=0.0292..2.88 rows=16000 loops=1)
```

实读行数：主键序扫描 **16000 行**（全表级）。

### S-sparse 加索引后

```
EXPLAIN：
id=1 SIMPLE sport_record type=range possible_keys=idx_status_created key=idx_status_created key_len=7 ref=NULL rows=3 filtered=100.00 Extra=Using index condition; Using filesort

EXPLAIN ANALYZE：
-> Limit: 100 row(s)  (cost=1.61 rows=3) (actual time=0.297..0.297 rows=3 loops=1)
    -> Sort: sport_record.id, limit input to 100 row(s) per chunk  (cost=1.61 rows=3) (actual time=0.296..0.297 rows=3 loops=1)
        -> Index range scan on sport_record using idx_status_created over (status = 1 AND NULL < created_at < '2026-10-07 15:00:00'), with index condition: ((sport_record.`status` = 1) and (sport_record.created_at < TIMESTAMP'2026-10-07 15:00:00'))  (cost=1.61 rows=3) (actual time=0.0458..0.0533 rows=3 loops=1)
```

实读行数：idx_status_created 范围扫描 **3 行**（status=1 条目级，数百内）。

### S-dense 加索引前（状态分布 1=480 / 2=14560 / 3=640 / 7=320，合计 16000）

```
EXPLAIN：
id=1 SIMPLE sport_record type=index possible_keys=NULL key=PRIMARY key_len=8 ref=NULL rows=100 filtered=3.33 Extra=Using where

EXPLAIN ANALYZE：
-> Limit: 100 row(s)  (cost=9.82 rows=3.33) (actual time=0.0985..1.19 rows=100 loops=1)
    -> Filter: ((sport_record.`status` = 1) and (sport_record.created_at < TIMESTAMP'2026-10-07 15:00:00'))  (cost=9.82 rows=3.33) (actual time=0.0979..1.18 rows=100 loops=1)
        -> Index scan on sport_record using PRIMARY  (cost=9.82 rows=100) (actual time=0.0931..1.08 rows=3266 loops=1)
```

实读行数：主键序扫描读到 **3266 行**才凑满 100 条命中。

### S-dense 加索引后

```
EXPLAIN：
id=1 SIMPLE sport_record type=range possible_keys=idx_status_created key=idx_status_created key_len=7 ref=NULL rows=480 filtered=100.00 Extra=Using index condition; Using filesort

EXPLAIN ANALYZE：
-> Limit: 100 row(s)  (cost=216 rows=100) (actual time=1.22..1.23 rows=100 loops=1)
    -> Sort: sport_record.id, limit input to 100 row(s) per chunk  (cost=216 rows=480) (actual time=1.22..1.23 rows=100 loops=1)
        -> Index range scan on sport_record using idx_status_created over (status = 1 AND NULL < created_at < '2026-10-07 15:00:00'), with index condition: ((sport_record.`status` = 1) and (sport_record.created_at < TIMESTAMP'2026-10-07 15:00:00'))  (cost=216 rows=480) (actual time=0.134..1.05 rows=480 loops=1)
```

实读行数：idx_status_created 范围扫描 **480 行**（= status=1 全量候选）。

加索引后两形态 `SHOW INDEX` 均含 `idx_status_created`（Non_unique=1，列序 status → created_at）。

## 三支判定归属（任务书 §2.2 预注册）

逐条比对：

1. 两形态加索引后计划均 `key=idx_status_created (type=range)`——S-sparse / S-dense 均满足；
2. S-sparse 前置 ANALYZE 实读 16000 行（>15000 全表级），后置实读 3 行（status=1 条目级，数百内）——满足；
3. IT 2/2 绿（见下）——满足。

**判定：第一支（PASSED）。**

**S-dense 豁免登记（预注册）**：该形态加索引后估计成本（cost=216）高于前置（cost=9.82），按预注册条款不构成否决——索引服务生产形态（S-sparse），dense 为事故场景且 attempt-1 已证优化器会选中该索引。数字如实登记：前置主键序扫描读到 3266 行才凑满 100 条命中；后置范围扫描 480 条候选（filesort 输入规模 = 480）后取前 100。后置实读行数（480）小于前置（3266），代价估计升高来自 filesort 排序估计；两端末段单次 EXPLAIN ANALYZE 时间读数同量级（1.19ms vs 1.23ms，单次读数，非基准）。

**已考虑不采纳方案登记（任务书 §2.3）**：`(status, id)` 替代索引——created_at 不在索引内，滞留判定按 created_at 过滤，常见路径需对 status=1 的每一条回表核 created_at，本轮不实施。

## 迁移脚本验证

`sql/migrations/add-idx-record-status.sql`（仅 `sed` 替换 USE 行指向 scratch 库 `record_idx_scratch_mig`）在改动前 DDL 的 scratch 库上连续执行两次：第一次建出索引（rc=0），第二次输出 `sport_record.idx_status_created 已存在，跳过`（幂等，rc=0）。执行后 `SHOW INDEX` 恰为四个索引：`PRIMARY(id)`、`uk_request_id(request_id)`、`idx_user_time(user_id, created_at)`、`idx_status_created(status, created_at)`（Non_unique=1，列序 status → created_at）。

## 测试与退出码

| 命令 | 覆盖 | 结果 |
| --- | --- | --- |
| `TASK177_IT_URL='jdbc:mysql://127.0.0.1:3307/?useSSL=false&allowPublicKeyRetrieval=true' TASK177_IT_USER=root TASK177_IT_PASSWORD=root mvn -B -ntp -o -s .mvn-settings.xml -pl record-service -am test -Dtest=SportRecordIndexMysqlIT -Dsurefire.failIfNoSpecifiedTests=false` | `record-service/src/test/java/com/sportverify/record/db/SportRecordIndexMysqlIT.java`（2 例） | Tests run: 2, Failures: 0, Errors: 0, Skipped: 0，**退出码 0** |

- harness `--it` 分支硬编码 leaderboard IT 清单（`scripts/verify/mvn-verify.sh` L33-36），record-service IT 无法经该分支承载：按任务书 §0.1 以镜像 harness offline 纪律的完整命令直跑（唯一例外）；harness 缺口登记为 Notice。
- Checkstyle `--static=record-service`：814 处，与开工基线持平（`src/test` 不在 checkstyle 扫描面内，新文件零新增违规）。

`SportRecordIndexMysqlIT`（需要真 MySQL，缺环境变量即 assume 跳过，不计为通过；连接的是仓库 MySQL 容器映射出的宿主机 3307 端口）：

1. **新库包含复合索引**：运行时读取仓库工作树的 `sql/02-record-db.sql`，机械改名 `record_db → record_idx_it_scratch` 后在真 MySQL 上建库，断言索引集合恰为四个，且 `idx_status_created` 为非唯一二级索引、列序 `(status, created_at)`，主键/幂等键/`idx_user_time` 原样保留。
2. **查询语义保持不变**：自建库补 `archived` 列（对齐当前 `SportRecord` 实体列序）后，种子 105 条滞留 VERIFYING + 1 条新鲜 VERIFYING + 1 条滞留 MANUAL_REVIEW + 1 条滞留 PASSED，滞留扫描 SQL 恰好按 id 升序返回前 100 条滞留 VERIFYING（新鲜行被时间条件、非 VERIFYING 状态行被状态条件分别挡下）；扫描后总行数（108）与状态分布（VERIFYING 106 / PASSED 1 / MANUAL_REVIEW 1）不变。

## 结论与边界

- 遗留说明同 attempt-1：演示数据卷里的存量 `record_db` 未被本变更触碰；存量库升级由运维执行 `bash scripts/db/migrate.sh` 补齐该索引。
- 本轮读数仅限开发机容器环境、双形态 16000 行合成种子与单次 EXPLAIN / EXPLAIN ANALYZE 口径；未做线上压测，不得外推为线上延迟或业务耗时结论。
