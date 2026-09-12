#!/usr/bin/env bash
# 压测数据留存：GC 基线切片 + Explain 前置采样
cp logs/gc-record.log docs/perf/data/raw/gc-record-baseline.log
echo "GC 基线已留存：docs/perf/data/raw/gc-record-baseline.log（行数 $(wc -l < docs/perf/data/raw/gc-record-baseline.log)）"

ROW=$(docker exec sport-verify-mysql mysql -uroot -proot -N -e \
  "SELECT CONCAT(id,' ',user_id) FROM record_db.sport_record WHERE id=2")
RID="${ROW%% *}"; UID2="${ROW##* }"; SHARD=$((UID2 % 16))
echo "== Explain BEFORE（recordId=$RID shard=$SHARD，无组合索引）=="
{
  echo "-- Explain BEFORE: SELECT * FROM track_point_$SHARD WHERE user_id=$UID2 AND record_id=$RID ORDER BY seq"
  docker exec sport-verify-mysql mysql -uroot -proot record_db -e \
    "EXPLAIN FORMAT=TREE SELECT * FROM track_point_$SHARD WHERE user_id=$UID2 AND record_id=$RID ORDER BY seq" 2>/dev/null
  echo "-- EXPLAIN ANALYZE（含实际耗时）"
  docker exec sport-verify-mysql mysql -uroot -proot record_db -e \
    "EXPLAIN ANALYZE SELECT * FROM track_point_$SHARD WHERE user_id=$UID2 AND record_id=$RID ORDER BY seq" 2>/dev/null
  echo "-- 表索引现状"
  docker exec sport-verify-mysql mysql -uroot -proot record_db -e "SHOW INDEX FROM track_point_$SHARD" 2>/dev/null
} | tee docs/perf/data/explain-before.txt
