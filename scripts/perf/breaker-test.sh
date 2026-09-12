#!/usr/bin/env bash
# 熔断降级「转人工」验证（spec 场景：verify 不可用 → 转人工，主链路不挂；恢复后自愈）
cd "$(dirname "$0")/../.."
JAVA_BIN="D:/develop1/jdk21/bin/java"
export MYSQL_PORT="${MYSQL_PORT:-3307}"   # 服务侧数据源端口必须与 compose 一致（本机 3306 被宿主 MySQL 占用）
MYSQL="docker exec sport-verify-mysql mysql -uroot -proot -N"

echo "== 1. 停止 verify-service（模拟校验依赖故障）=="
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'sport-verify-verify-service' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force; Write-Host ('  kill pid=' + \$_.ProcessId) }"
sleep 3

echo "== 2. 故障期间提交 30 条记录（观察主链路是否不挂）=="
"$JAVA_BIN" scripts/perf/LoadTest.java \
  --url http://127.0.0.1:8080/record/api/records \
  --method POST --concurrency 10 --total 30 --warmup 0 --timeout 30 \
  --body docs/perf/data/load-template.json --user-total 16 \
  --label breaker-submit-c10 \
  --out-prefix docs/perf/data/raw/breaker-submit

echo "== 3. 提交后立即查状态分布（应为 VERIFYING=1，等待异步判定）=="
$MYSQL -e "SELECT status, COUNT(*) FROM record_db.sport_record WHERE request_id LIKE 'lt%' GROUP BY status" 2>/dev/null
$MYSQL -e "SELECT MAX(id) FROM record_db.sport_record" 2>/dev/null

echo "== 4. 等待补偿任务（stuck-seconds=120s + 扫描周期 60s）：探测 verify 不可用 → 转人工 =="
sleep 200
echo "-- 200s 后状态分布（预期大量 status=7 MANUAL_REVIEW）--"
$MYSQL -e "SELECT status, COUNT(*) FROM record_db.sport_record WHERE request_id LIKE 'lt%' GROUP BY status" 2>/dev/null
echo "-- record 侧降级日志 --"
grep -a "转人工\|熔断降级" logs/record.log | tail -4

echo "== 5. 重启 verify-service，等待补偿自愈（重放 → 终判对账收敛）=="
nohup "$JAVA_BIN" -jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar > logs/verify.log 2>&1 &
sleep 130
echo "-- 恢复后状态分布（预期 2 PASSED / 3 REJECTED 为主，7 MANUAL_REVIEW 清零）--"
$MYSQL -e "SELECT status, COUNT(*) FROM record_db.sport_record WHERE request_id LIKE 'lt%' GROUP BY status" 2>/dev/null
