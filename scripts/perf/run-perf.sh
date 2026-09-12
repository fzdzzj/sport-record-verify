#!/usr/bin/env bash
# ============================================================
# 压测一键驱动（压测变更 spec「并发压测方法」「量化达标」「限流与熔断验证」）
#
# 用法（Git Bash / Linux / macOS）：
#   bash scripts/perf/run-perf.sh gen                 # 生成测试集（≥200 条正负各半）+ 压测模板
#   bash scripts/perf/run-perf.sh load 100 2000 base  # 100 并发 × 2000 请求，数据存 base 前缀
#   bash scripts/perf/run-perf.sh quality base        # 提交验收集，产出拦截率/通过率/端到端 P95
#   bash scripts/perf/run-perf.sh explain             # 取最新记录做 Explain 前置/后置采样
#   bash scripts/perf/run-perf.sh help
#
# 环境变量：
#   JAVA_BIN      JDK21 java 可执行（默认自动探测：JAVA_HOME → D:/develop1/jdk21 → java）
#   MYSQL_PORT    宿主机 MySQL 容器端口（默认 3307，本机 3306 被占；与 .env 保持一致）
#   GATEWAY       网关地址（默认 http://127.0.0.1:8080）
#
# 产出目录：docs/perf/data/（raw/ 为并发压测原始数据，quality-*.csv/json 为验收集结果）
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/../.."   # 始终以仓库根为工作目录

# ---------- JDK21 探测（压测程序需虚拟线程，JDK8 的 java 不行） ----------
resolve_java() {
  if [ -n "${JAVA_BIN:-}" ]; then echo "$JAVA_BIN"; return; fi
  local candidates=("${JAVA_HOME:-}/bin/java" "D:/develop1/jdk21/bin/java" "$(command -v java || true)")
  for c in "${candidates[@]}"; do
    if [ -n "$c" ] && [ -x "$c" ] && "$c" -version 2>&1 | head -1 | grep -qE 'version "21'; then
      echo "$c"; return
    fi
  done
  echo "ERROR: 未找到 JDK21 的 java，请设置 JAVA_BIN 环境变量" >&2
  exit 2
}
JAVA_BIN="$(resolve_java)"

export MYSQL_PORT="${MYSQL_PORT:-3307}"
GATEWAY="${GATEWAY:-http://127.0.0.1:8080}"
DATA_DIR="docs/perf/data"
RAW_DIR="$DATA_DIR/raw"
TEMPLATE="$DATA_DIR/load-template.json"

cmd="${1:-help}"; shift 2>/dev/null || true

case "$cmd" in

  gen)
    echo "== 生成测试集与压测模板 =="
    "$JAVA_BIN" scripts/perf/GenSamples.java --out-dir "$DATA_DIR" --quality 200 --points 300
    ;;

  load)
    C="${1:?并发数，如 100}"; TOTAL="${2:?总请求数，如 2000}"; PHASE="${3:-run}"
    mkdir -p "$RAW_DIR"
    echo "== 并发压测：concurrency=$C total=$TOTAL phase=$PHASE =="
    "$JAVA_BIN" scripts/perf/LoadTest.java \
      --url "$GATEWAY/record/api/records" \
      --method POST --concurrency "$C" --total "$TOTAL" --warmup 10 --timeout 60 \
      --body "$TEMPLATE" --user-total 16 \
      --label "${PHASE}-c${C}" \
      --out-prefix "$RAW_DIR/${PHASE}-c${C}"
    ;;

  quality)
    PHASE="${1:-run}"
    echo "== 量化验收实测（拦截率/通过率/端到端 P95）：phase=$PHASE =="
    "$JAVA_BIN" scripts/perf/SubmitSamples.java \
      --real "$DATA_DIR/quality-real.jsonl" --forged "$DATA_DIR/quality-forged.jsonl" \
      --gateway "$GATEWAY" --submit-concurrency 8 --timeout 120 \
      --out-prefix "$DATA_DIR/quality-${PHASE}"
    ;;

  explain)
    echo "== Explain 采样（取最新一条多点记录，物理分片直查）=="
    ROW=$(docker exec sport-verify-mysql mysql -uroot -proot -N -e \
      "SELECT CONCAT(id,' ',user_id) FROM record_db.sport_record ORDER BY id DESC LIMIT 1")
    RID="${ROW%% *}"; UID2="${ROW##* }"
    SHARD=$((UID2 % 16))
    echo "recordId=$RID userId=$UID2 → 物理表 track_point_$SHARD"
    docker exec sport-verify-mysql mysql -uroot -proot record_db -e \
      "EXPLAIN SELECT * FROM track_point_$SHARD WHERE user_id=$UID2 AND record_id=$RID ORDER BY seq"
    docker exec sport-verify-mysql mysql -uroot -proot record_db -e \
      "EXPLAIN ANALYZE SELECT * FROM track_point_$SHARD WHERE user_id=$UID2 AND record_id=$RID ORDER BY seq"
    ;;

  # ---- 优化侧一键切换（压测「连接池调优案例」：批量插入 + 连接池 10→30）----
  # 说明：仅重启 record-service；组合索引另行执行 sql/migrations/add-idx-record-seq.sql
  optimize-record)
    echo "== 停止 record-service =="
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'sport-verify-record-service' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force; Write-Host ('  kill pid=' + \$_.ProcessId) }"
    sleep 2
    echo "== 以优化参数重启 record-service（批量插入开启 / MYSQL_POOL_SIZE=30 / GC 日志切新文件）=="
    MYSQL_POOL_SIZE=30 nohup "$JAVA_BIN" \
      -Xms1g -Xmx1g \
      -Xlog:gc:file=logs/gc-record-optimized.log:time,uptime:filecount=3,filesize=10m \
      -jar record-service/target/sport-verify-record-service-0.1.0-SNAPSHOT.jar \
      --record.track.batch-insert-enabled=true > logs/record.log 2>&1 &
    sleep 22
    curl -s -m 3 http://127.0.0.1:8082/actuator/health | head -c 120; echo
    ;;

  # ---- 服务启停（起栈供压测；独立小节见交付说明）----
  start-services)
    echo "== 启动四个服务（日志在 logs/，GC 日志 logs/gc-*.log）=="
    mkdir -p logs
    JAVA_TOOL_OPTIONS=""
    nohup "$JAVA_BIN" -Xlog:gc:file=logs/gc-record.log:time,uptime:filecount=3,filesize=10m \
      -jar record-service/target/sport-verify-record-service-0.1.0-SNAPSHOT.jar > logs/record.log 2>&1 &
    nohup "$JAVA_BIN" -jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar > logs/verify.log 2>&1 &
    nohup "$JAVA_BIN" -jar user-service/target/sport-verify-user-service-0.1.0-SNAPSHOT.jar > logs/user.log 2>&1 &
    nohup "$JAVA_BIN" -jar gateway-service/target/sport-verify-gateway-service-0.1.0-SNAPSHOT.jar > logs/gateway.log 2>&1 &
    echo "已后台启动，等待 25s 后自检……"; sleep 25
    for p in 8080/health 8081/actuator/health 8082/actuator/health 8083/actuator/health; do
      code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${p%%/*}/${p#*/}" || true)
      echo "  ${p%%/*} → HTTP $code"
    done
    ;;

  stop-services)
    echo "== 停止四个 Java 服务（按 jar 名匹配，Windows 下按端口杀会漏僵尸进程）=="
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'sport-verify-(record|verify|user|gateway)-service' } | ForEach-Object { Write-Host ('  kill pid=' + \$_.ProcessId); Stop-Process -Id \$_.ProcessId -Force }"
    ;;

  help|--help|-h|*)
    grep '^#   ' "$0" | sed 's/^#   //'
    ;;
esac
