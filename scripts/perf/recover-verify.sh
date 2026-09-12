#!/usr/bin/env bash
# 恢复步骤：正确带 MYSQL_PORT 重启 verify，清理重试计数，等补偿自愈收敛
cd "$(dirname "$0")/../.."
export MYSQL_PORT="${MYSQL_PORT:-3307}"
JAVA_BIN="D:/develop1/jdk21/bin/java"

echo "== 1. 停掉错配的 verify，按正确环境重启 =="
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'sport-verify-verify-service' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
sleep 2
nohup "$JAVA_BIN" -jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar > logs/verify.log 2>&1 &
sleep 25
curl -s -m 3 http://127.0.0.1:8083/actuator/health | head -c 80; echo

echo "== 2. 清理消费重试计数（此前错连库失败已累计，防止再进死信）=="
docker exec sport-verify-redis redis-cli --scan --pattern 'verify:retry:*' | head -40 | while read -r k; do docker exec sport-verify-redis redis-cli del "$k" > /dev/null; done
echo "重试计数键已清理"

echo "== 3. 等下一轮补偿（60s 周期）重放转人工记录 → verify 终判收敛 =="
sleep 140
docker exec sport-verify-mysql mysql -uroot -proot -N -e \
  "SELECT status, COUNT(*) FROM record_db.sport_record WHERE request_id LIKE 'lt%' GROUP BY status" 2>/dev/null
echo "（期望：7 MANUAL_REVIEW 清零，主体为 2 PASSED / 3 REJECTED）"
grep -a "判定完成" logs/verify.log | tail -3
