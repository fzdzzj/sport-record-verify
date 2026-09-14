#!/usr/bin/env bash
# 灰度冒烟 Phase E：抽取库表终态与 verify 日志广播记录作为验收证据
set -u
# 前置：在仓库根目录运行；容器名/路径/记录 ID 均可由环境变量覆盖（仓库内无本机硬编码）
MYSQL_CONTAINER=${MYSQL_CONTAINER:-sport-verify-mysql}
LOG_DIR=${LOG_DIR:-logs}
# 冒烟产生的记录 ID（从 $LOG_DIR/smoke-verdicts.txt 取最新一次运行值）
RECORD_IDS=${RECORD_IDS:-19497,19498,19499,19500}

echo "== rule_version 终态 =="
docker exec "$MYSQL_CONTAINER" mysql -uroot -proot -e "SELECT id,version,status,gray_ratio FROM verify_db.rule_version;" 2>/dev/null
echo "== verification_result（冒烟记录）=="
docker exec "$MYSQL_CONTAINER" mysql -uroot -proot -e "SELECT record_id,verdict,score FROM verify_db.verification_result WHERE record_id IN ($RECORD_IDS);" 2>/dev/null
echo "== sport_record 状态（1=VERIFYING? 3=PASSED? 4=REJECTED?）=="
docker exec "$MYSQL_CONTAINER" mysql -uroot -proot -e "SELECT id,user_id,status FROM record_db.sport_record WHERE id IN ($RECORD_IDS);" 2>/dev/null
echo "== verify 日志中的广播记录 =="
grep -aF "失效广播" "$LOG_DIR/smoke-verify.log" | head -6
grep -aF "订阅已注册" "$LOG_DIR/smoke-verify.log" | head -2
