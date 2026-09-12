#!/usr/bin/env bash
# Explain 后置采样（优化后）
{
  echo "-- Explain AFTER: 加组合索引 idx_record_seq(record_id, seq) 后（recordId=2 shard=7）"
  docker exec sport-verify-mysql mysql -uroot -proot record_db -e \
    "EXPLAIN FORMAT=TREE SELECT * FROM track_point_7 WHERE user_id=7 AND record_id=2 ORDER BY seq" 2>/dev/null
  echo "-- EXPLAIN ANALYZE（含实际耗时）"
  docker exec sport-verify-mysql mysql -uroot -proot record_db -e \
    "EXPLAIN ANALYZE SELECT * FROM track_point_7 WHERE user_id=7 AND record_id=2 ORDER BY seq" 2>/dev/null
  echo "-- 表索引现状"
  docker exec sport-verify-mysql mysql -uroot -proot record_db -e "SHOW INDEX FROM track_point_7" 2>/dev/null
} | tee docs/perf/data/explain-after.txt
