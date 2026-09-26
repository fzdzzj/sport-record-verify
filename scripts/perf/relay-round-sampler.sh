#!/usr/bin/env bash
# TASK-145 relay 测量轮只读资源采样（每轮 before/after 各一次，同一时间轴）。
#
# 用法：
#   bash scripts/perf/relay-round-sampler.sh <label> <before|after>
#
# 只读：抓 verify /actuator/prometheus 的 JVM/GC/内存/线程/Hikari/HTTP 指标，MySQL 全局状态，
# 以及 RocketMQ broker 可得读数。不传输或采集任何密钥，不改配置、不写库、不清数据；
# 某类读数取不到时由输出显式标记 FAILED/UNAVAILABLE（报告中记「未知」，不以「无异常」代替）。
set -uo pipefail
cd "$(dirname "$0")/../.."

LABEL="${1:?用法: relay-round-sampler.sh <label> <before|after>}"
PHASE="${2:?用法: relay-round-sampler.sh <label> <before|after>}"
RAW="docs/perf/data/raw"
mkdir -p "$RAW"
OUT="$RAW/task145-${LABEL}-${PHASE}-resources.txt"
TS="$(date '+%Y-%m-%d %H:%M:%S.%3N' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S')"

{
  echo "== TASK-145 resource sample: label=$LABEL phase=$PHASE ts=$TS =="
  echo "-- verify /actuator/prometheus (selected) --"
  if ! curl -s -m 5 http://127.0.0.1:8083/actuator/prometheus \
      | grep -E '^(process_cpu_usage|process_uptime_seconds|jvm_memory_used_bytes|jvm_memory_max_bytes|jvm_threads_live_threads|jvm_gc_pause_seconds_(count|sum)|jvm_gc_memory_allocated_bytes_total|jvm_gc_memory_promoted_bytes_total|hikaricp_connections_(active|idle|pending|max|timeout_total)|http_server_requests_seconds_count)'; then
    echo "PROM_SCRAPE_FAILED"
  fi
  echo "-- mysql global status --"
  if ! docker exec sport-verify-mysql mysql -uroot -proot -N -e \
      "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Queries','Com_select','Com_update','Innodb_rows_read','Innodb_row_lock_waits','Innodb_row_lock_time');" 2>/dev/null; then
    echo "MYSQL_STATUS_FAILED"
  fi
  echo "-- rocketmq broker status (best-effort) --"
  if ! docker exec sport-verify-rocketmq-broker sh -lc \
      'mqadmin brokerStatus -n 127.0.0.1:9876 2>&1 | head -40' 2>/dev/null; then
    echo "MQ_BROKER_STATUS_UNAVAILABLE"
  fi
} | tee "$OUT"