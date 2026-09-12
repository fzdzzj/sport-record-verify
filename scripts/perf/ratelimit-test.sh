#!/usr/bin/env bash
# 限流验证（Sentinel 网关流控，spec 场景「限流拦截」）
# 用法：bash scripts/perf/ratelimit-test.sh [低阈值QPS]（默认 100）
QPS="${1:-100}"
cd "$(dirname "$0")/../.."
export MYSQL_PORT="${MYSQL_PORT:-3307}"   # 网关本身不连库，但保持口径一致避免后续子命令遗漏

echo "== 1. 以低阈值重启网关（验证拦截链路；5k QPS 为配置基线，本机压不出该量级）=="
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'sport-verify-gateway-service' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
sleep 2
JAVA_BIN="D:/develop1/jdk21/bin/java"
nohup "$JAVA_BIN" -jar gateway-service/target/sport-verify-gateway-service-0.1.0-SNAPSHOT.jar \
  --app.gateway.rate-limit.qps="$QPS" > logs/gateway.log 2>&1 &
sleep 20
curl -s -m 3 http://127.0.0.1:8080/actuator/health | head -c 60; echo "（网关已起，限流 qps=$QPS）"

echo "== 2. 500 并发打 20s，观察 429 拦截 =="
"$JAVA_BIN" scripts/perf/LoadTest.java \
  --url http://127.0.0.1:8080/record/api/records \
  --method POST --concurrency 500 --seconds 20 --warmup 0 --timeout 15 \
  --body docs/perf/data/load-template.json --user-total 16 \
  --label "ratelimit-qps${QPS}-c500" \
  --out-prefix docs/perf/data/raw/ratelimit-c500

echo "== 3. 恢复网关（默认 5000 QPS 基线）=="
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'sport-verify-gateway-service' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
sleep 2
nohup "$JAVA_BIN" -jar gateway-service/target/sport-verify-gateway-service-0.1.0-SNAPSHOT.jar > logs/gateway.log 2>&1 &
echo "网关已恢复默认配置（5000 QPS）"
