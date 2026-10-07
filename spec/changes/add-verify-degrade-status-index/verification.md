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
