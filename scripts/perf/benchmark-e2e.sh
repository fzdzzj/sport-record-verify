#!/usr/bin/env bash
# ============================================================
# 全链路端到端一键压测编排（TASK-265 R2，配套 LoadTest.java 多链路内核）
#
# 覆盖四大核心业务路径（经网关，鉴权降级态 app.auth.enabled=false）：
#   ① submit      记录提交写链路  POST /record/api/records（写事务 + 异步 verify 链）
#   ② feed        动态流读取链路  GET  /record/api/records/feed（G3 批量权限 + G13 分页）
#   ③ likes       批量点赞查询    POST /record/api/records/likes/batch（G3 批量查 + Feign 懒拉）
#   ④ leaderboard 排行榜聚合查询  GET  /leaderboard/api/leaderboard?type=overall（D1/G12 L1+L2 缓存）
#
# 用法（Git Bash / Linux / macOS）：
#   bash scripts/perf/benchmark-e2e.sh                          # 默认四场景 × 100/300/500 三档
#   bash scripts/perf/benchmark-e2e.sh --scenarios=feed,leaderboard --tiers=100,300
#   bash scripts/perf/benchmark-e2e.sh --total=2000             # 各档固定总量
#   bash scripts/perf/benchmark-e2e.sh --skip-sampler           # 只压测，不采中间件水位
#
# 环境变量：
#   JAVA_BIN          JDK21 java（默认自动探测：JAVA_HOME → D:/develop1/jdk21 → PATH）
#   GATEWAY           网关地址（默认 http://127.0.0.1:8080）
#   MYSQL_CONTAINER   MySQL 容器名（默认 sport-verify-mysql，凭据取容器内 MYSQL_ROOT_PASSWORD）
#   REDIS_CLI         redis-cli 路径（默认自动探测 PATH → D:/develop1/Redis-x64-3.0.504）
#   REDIS_HOST/REDIS_PORT  默认 127.0.0.1:6379
#   USER_TOTAL        {userId} 轮转分片数（默认 16，与既有压测口径一致）
#
# 产出（docs/perf/data/raw/ 已在 .gitignore，不入库）：
#   docs/perf/data/raw/bench-e2e-<runid>/<scenario>-c<C>-summary.json / -raw.csv
#   docs/perf/data/raw/bench-e2e-<runid>/sampler.tsv（MySQL 线程/连接、HikariCP 活跃/排队、Redis 客户端）
#   docs/perf/data/raw/bench-e2e-<runid>/cpu-mem.tsv（各档首末 java 进程 CPU 秒/工作集 MB 快照）
#   docs/perf/data/raw/bench-e2e-<runid>/rollup.csv（全部档位一行一读数的汇总表）
#
# 说明：运行时控制台消息用 ASCII（本机 Git Bash 控制台为 GBK 代码页，UTF-8 中文会乱码；
#       LoadTest.java 经 JVM 输出的中文不受影响）。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/../.."   # 始终以仓库根为工作目录

# ---------- 参数 ----------
SCENARIOS="submit,feed,likes,leaderboard"
TIERS="100,300,500"
TOTAL_OVERRIDE=""
RUN_ID=""
SKIP_SAMPLER=0

for a in "$@"; do
  case "$a" in
    --scenarios=*) SCENARIOS="${a#--scenarios=}" ;;
    --tiers=*)     TIERS="${a#--tiers=}" ;;
    --total=*)     TOTAL_OVERRIDE="${a#--total=}" ;;
    --label=*)     RUN_ID="${a#--label=}" ;;
    --skip-sampler) SKIP_SAMPLER=1 ;;
    -h|--help)     sed -n '2,38p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "[bench] unknown arg: $a (see --help)" >&2; exit 2 ;;
  esac
done

# ---------- JDK21 探测（虚拟线程需要，JDK8 的 java 不行） ----------
resolve_java() {
  if [ -n "${JAVA_BIN:-}" ]; then echo "$JAVA_BIN"; return; fi
  local candidates=("${JAVA_HOME:-}/bin/java" "D:/develop1/jdk21/bin/java" "$(command -v java || true)")
  for c in "${candidates[@]}"; do
    if [ -n "$c" ] && [ -x "$c" ] && "$c" -version 2>&1 | head -1 | grep -qE 'version "21'; then
      echo "$c"; return
    fi
  done
  echo "ERROR: JDK21 java not found, set JAVA_BIN" >&2
  exit 2
}
JAVA_BIN="$(resolve_java)"

GATEWAY="${GATEWAY:-http://127.0.0.1:8080}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-sport-verify-mysql}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
USER_TOTAL="${USER_TOTAL:-16}"
DATA_DIR="docs/perf/data/raw"

resolve_redis_cli() {
  if [ -n "${REDIS_CLI:-}" ]; then echo "$REDIS_CLI"; return; fi
  if command -v redis-cli >/dev/null 2>&1; then command -v redis-cli; return; fi
  for c in "D:/develop1/Redis-x64-3.0.504/redis-cli.exe"; do
    [ -x "$c" ] && { echo "$c"; return; }
  done
  echo ""
}

[ -n "$RUN_ID" ] || RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$DATA_DIR/bench-e2e-$RUN_ID"
mkdir -p "$OUT_DIR"

log() { echo "[bench $(date +%H:%M:%S)] $*"; }

# ---------- 预检 ----------
log "preflight: JDK=$( "$JAVA_BIN" -version 2>&1 | head -1 )"

health_body="$(curl -sf --max-time 5 "$GATEWAY/actuator/health" 2>/dev/null || true)"
if ! echo "$health_body" | grep -q '"status":"UP"'; then
  echo "ERROR: gateway $GATEWAY not healthy. Start the service stack first." >&2
  exit 3
fi
log "preflight: gateway $GATEWAY health OK"

MYSQL_Q() { # 容器内取 MYSQL_ROOT_PASSWORD 环境变量做凭据（宿主机 .env 口令与容器一致）
  docker exec "$MYSQL_CONTAINER" sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "$1"' bench "$1" 2>/dev/null
}
if ! MYSQL_Q "SELECT 1" | grep -q 1; then
  echo "ERROR: MySQL container $MYSQL_CONTAINER not reachable (docker exec failed)" >&2
  exit 3
fi
log "preflight: MySQL container $MYSQL_CONTAINER query path OK"

REDIS_CLI_BIN="$(resolve_redis_cli)"
if [ -n "$REDIS_CLI_BIN" ] && "$REDIS_CLI_BIN" -h "$REDIS_HOST" -p "$REDIS_PORT" PING 2>/dev/null | grep -q PONG; then
  log "preflight: Redis $REDIS_HOST:$REDIS_PORT PONG"
else
  REDIS_CLI_BIN=""
  log "WARN: redis-cli unavailable, redis sampling skipped"
fi

# ---------- 提交模板 ----------
TEMPLATE="docs/perf/data/load-template.json"
if [ ! -f "$TEMPLATE" ]; then
  log "load template missing, generating via GenSamples (docs/perf/data, gitignored)"
  "$JAVA_BIN" scripts/perf/GenSamples.java --out-dir docs/perf/data --quality 200 --points 300
fi
[ -f "$TEMPLATE" ] || { echo "ERROR: template still missing: $TEMPLATE" >&2; exit 4; }

# ---------- 点赞批量记录 ID（真实 id 热集，20 条；空库自动先播种 20 条提交再重取） ----------
LIKES_BODY="$OUT_DIR/likes-body.json"
fetch_record_ids() { MYSQL_Q "SELECT id FROM record_db.sport_record ORDER BY id DESC LIMIT 20" | tr '\n' ',' | sed 's/,$//'; }
prepare_likes_body() {
  local ids
  ids="$(fetch_record_ids)"
  if [ -z "$ids" ]; then
    log "no records in db, seeding 20 submits first (concurrency=4 warmup=2)"
    "$JAVA_BIN" scripts/perf/LoadTest.java \
      --url "$GATEWAY/record/api/records" --method POST \
      --concurrency 4 --total 20 --warmup 2 --timeout 60 \
      --body "$TEMPLATE" --user-total "$USER_TOTAL" \
      --label seed --out-prefix "$OUT_DIR/seed"
    ids="$(fetch_record_ids)"
  fi
  [ -n "$ids" ] || { echo "ERROR: still no record ids after seeding, likes scenario aborted" >&2; exit 4; }
  # [id1,id2,...]（List<Long> 请求体，占位符 {userId} 走 URL 轮转）
  printf '[%s]\n' "$ids" > "$LIKES_BODY"
  log "likes body ready: $LIKES_BODY (20 real record ids hot set)"
}

# ---------- 中间件水位采样器（1 次/2s 追加 TSV；逐段容错，单点失败不杀循环） ----------
# 每档独立文件（sampler-<scenario>-c<tier>.tsv）：start_sampler 的表头重定向是截断写，
# 共享单文件会被下一档抹掉上一档样本；record-service 为 ShardingSphere 包装数据源，
# Spring 不绑定内层 HikariCP（无 hikaricp* 指标），池水位用 record_db processlist 连接数口径佐证。
SAMPLER_PID=""
start_sampler() {
  [ "$SKIP_SAMPLER" -eq 1 ] && return 0
  (
    echo -e "ts\tscenario\ttier\tmysql_threads_running\tmysql_threads_connected\trecorddb_processlist_conns\tverify_hikari_active\tleaderboard_hikari_active\tredis_connected_clients" \
      > "$OUT_DIR/sampler-$1-c$2.tsv"
    while true; do
      ts="$(date +%H:%M:%S)"
      mysql_line="$(MYSQL_Q "SHOW GLOBAL STATUS WHERE Variable_name LIKE 'Threads_%'; SELECT COUNT(*) FROM information_schema.processlist WHERE DB = 'record_db'" 2>/dev/null \
        | tr '\t' '=' | tr '\n' ' ' || true)"
      th_running="$(echo "$mysql_line" | grep -o 'Threads_running=[0-9]*' | cut -d= -f2 || true)"
      th_connected="$(echo "$mysql_line" | grep -o 'Threads_connected=[0-9]*' | cut -d= -f2 || true)"
      pl_conns="$(echo "$mysql_line" | grep -oE '(^| )[0-9]+( |$)' | tr -d ' ' | tail -1 || true)"
      hik() { curl -sf --max-time 2 "http://127.0.0.1:$1/actuator/prometheus" 2>/dev/null \
        | grep -E "^hikaricp_connections_$2(\\{[^}]*\\})? " | awk '{print $NF}' | head -1 || true; }
      ver_a="$(hik 8083 active || true)"; ldb_a="$(hik 8084 active || true)"
      redis_c=""
      if [ -n "$REDIS_CLI_BIN" ]; then
        redis_c="$("$REDIS_CLI_BIN" -h "$REDIS_HOST" -p "$REDIS_PORT" INFO clients 2>/dev/null \
          | grep -oE 'connected_clients:[0-9]+' | cut -d: -f2 | tr -d '\r' || true)"
      fi
      echo -e "$ts\t$1\t$2\t${th_running:-}\t${th_connected:-}\t${pl_conns:-}\t${ver_a:-}\t${ldb_a:-}\t${redis_c:-}" \
        >> "$OUT_DIR/sampler-$1-c$2.tsv"
      sleep 2
    done
  ) &
  SAMPLER_PID=$!
}
stop_sampler() {
  if [ -n "$SAMPLER_PID" ]; then kill "$SAMPLER_PID" 2>/dev/null || true; wait "$SAMPLER_PID" 2>/dev/null || true; fi
  SAMPLER_PID=""
  return 0
}

# ---------- CPU/内存快照（java 进程族，Windows 用 PowerShell，其它平台跳过） ----------
CPU_TSV="$OUT_DIR/cpu-mem.tsv"
echo -e "phase\tjava_cpu_seconds\tjava_workingset_mb" > "$CPU_TSV"
cpu_snapshot() { # $1=phase 标记
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      powershell.exe -NoProfile -Command '
        $p = Get-Process java -ErrorAction SilentlyContinue
        if ($p) {
          $cpu = ($p | Measure-Object CPU -Sum).Sum
          $ws  = (($p | Measure-Object WorkingSet64 -Sum).Sum) / 1MB
          "{0:N1}`t{1:N0}" -f $cpu, $ws
        } else { "0`t0" }' 2>/dev/null | tr -d '\r' | sed "s/^/$1\t/" >> "$CPU_TSV" || true
      ;;
    *) echo -e "$1\tna\tna" >> "$CPU_TSV" ;;
  esac
}

# ---------- 场景定义（url/method/body 模板） ----------
scenario_url() {
  case "$1" in
    submit)      echo "$GATEWAY/record/api/records" ;;
    feed)        echo "$GATEWAY/record/api/records/feed?page=1&size=20" ;;
    likes)       echo "$GATEWAY/record/api/records/likes/batch?userId={userId}" ;;
    leaderboard) echo "$GATEWAY/leaderboard/api/leaderboard?type=overall&size=50" ;;
    *) echo "" ;;
  esac
}
scenario_method() {
  case "$1" in
    submit|likes) echo "POST" ;;
    *)            echo "GET" ;;
  esac
}
scenario_body() {
  case "$1" in
    submit) echo "$TEMPLATE" ;;
    likes)  echo "$LIKES_BODY" ;;
    *)      echo "" ;;
  esac
}
# 各档默认总量（采样数下限保障：c100≥20/线程）
tier_total() {
  if [ -n "$TOTAL_OVERRIDE" ]; then echo "$TOTAL_OVERRIDE"; return; fi
  case "$1" in
    100) echo 2000 ;;
    300) echo 3000 ;;
    500) echo 5000 ;;
    *)   echo 3000 ;;
  esac
}

# ---------- 汇总表 ----------
ROLLUP="$OUT_DIR/rollup.csv"
echo "scenario,tier,total,ok,limited429,errors,errRate,wallSeconds,qps,p50ms,p95ms,p99ms,maxMs" > "$ROLLUP"
jval() { grep -o "\"$1\": [0-9.]*" "$2" | head -1 | grep -o '[0-9.]*$' || true; }

# ---------- 主循环 ----------
for SC in $(echo "$SCENARIOS" | tr ',' ' '); do
  [ -n "$SC" ] || continue
  URL="$(scenario_url "$SC")"
  if [ -z "$URL" ]; then
    echo "ERROR: unknown scenario $SC (valid: submit feed likes leaderboard)" >&2
    exit 2
  fi
  METHOD="$(scenario_method "$SC")"
  BODY="$(scenario_body "$SC")"
  [ "$SC" = "likes" ] && prepare_likes_body

  BODY_ARGS=()
  [ -n "$BODY" ] && BODY_ARGS=(--body "$BODY")

  for C in $(echo "$TIERS" | tr ',' ' '); do
    [ -n "$C" ] || continue
    TOTAL="$(tier_total "$C")"
    PREFIX="$OUT_DIR/$SC-c$C"
    log "== scenario $SC: concurrency=$C total=$TOTAL method=$METHOD =="
    cpu_snapshot "${SC}-c${C}-before"
    start_sampler "$SC" "$C"
    set +e
    "$JAVA_BIN" scripts/perf/LoadTest.java \
      --url "$URL" --method "$METHOD" \
      --concurrency "$C" --total "$TOTAL" --warmup 20 --timeout 60 \
      --user-total "$USER_TOTAL" \
      --label "bench-e2e-$SC-c$C" \
      --out-prefix "$PREFIX" \
      "${BODY_ARGS[@]+"${BODY_ARGS[@]}"}"
    RC=$?
    set -e
    stop_sampler
    cpu_snapshot "${SC}-c${C}-after"
    if [ $RC -ne 0 ] || [ ! -f "$PREFIX-summary.json" ]; then
      echo "ERROR: scenario $SC c$C failed (LoadTest rc=$RC), aborting remaining tiers" >&2
      exit 5
    fi
    echo "$SC,$C,$(jval total "$PREFIX-summary.json"),$(jval ok "$PREFIX-summary.json"),$(jval limited429 "$PREFIX-summary.json"),$(jval errors "$PREFIX-summary.json"),$(jval errRate "$PREFIX-summary.json"),$(jval wallSeconds "$PREFIX-summary.json"),$(jval qps "$PREFIX-summary.json"),$(jval p50ms "$PREFIX-summary.json"),$(jval p95ms "$PREFIX-summary.json"),$(jval p99ms "$PREFIX-summary.json"),$(jval maxMs "$PREFIX-summary.json")" \
      >> "$ROLLUP"
    log "== scenario $SC c$C done: QPS=$(jval qps "$PREFIX-summary.json") P95=$(jval p95ms "$PREFIX-summary.json")ms =="
  done
done

log "all done. output dir: $OUT_DIR"
log "rollup:"
cat "$ROLLUP"
