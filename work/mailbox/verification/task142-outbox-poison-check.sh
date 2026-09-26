#!/usr/bin/env bash
# TASK-142 outbox 满批重试耗尽行饥饿：真实 SQL 判别（隔离 scratch MySQL，不碰 verify_db）。
#
# 用法：
#   bash work/mailbox/verification/task142-outbox-poison-check.sh
#   TASK142_SCRATCH_CONTAINER=<容器名> bash ...（默认 task131-scratch-mysql，MySQL 8.0.46，宿主 13318）
#
# 退出码：0 判别通过（旧 SQL 遮挡已复现 且 新 SQL 放行耗尽行之后的正常行）
#         1 判别不成立（红未复现或绿未达成）
#         3 环境不可用/差异来源不可判定（docker 或 scratch 容器缺失、SQL 执行失败）——不是通过
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
CONTAINER="${TASK142_SCRATCH_CONTAINER:-task131-scratch-mysql}"
SQL_FILE="$REPO_ROOT/work/mailbox/verification/task142-outbox-poison-sql.sql"
MAPPER="$REPO_ROOT/verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java"
RELAY="$REPO_ROOT/verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java"
DB="task142_outbox_scratch"
MAX_RETRY=16
BATCH=100

if ! command -v docker >/dev/null 2>&1; then
  echo "[task142] 环境不可用：docker 缺失（退出码 3）" >&2
  exit 3
fi
if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" != "true" ]; then
  echo "[task142] 环境不可用：scratch 容器 $CONTAINER 未运行（退出码 3）" >&2
  exit 3
fi
if [ ! -f "$SQL_FILE" ]; then
  echo "[task142] 判别 SQL 缺失：$SQL_FILE（退出码 3）" >&2
  exit 3
fi

q() { docker exec -i "$CONTAINER" mysql -uroot -proot -N -B -e "$1" 2>/dev/null; }

echo "[task142] scratch 容器=$CONTAINER 库=$DB 批大小=$BATCH 阈值=$MAX_RETRY"
echo "[task142] 灌种子并执行红/绿/边界/EXPLAIN 查询"
docker exec -i "$CONTAINER" mysql -uroot -proot --table < "$SQL_FILE" 2>/dev/null
sql_rc=$?
if [ "$sql_rc" -ne 0 ]; then
  echo "[task142] 判别 SQL 执行失败（rc=$sql_rc，退出码 3）" >&2
  exit 3
fi

OLD="SELECT * FROM $DB.verify_event_outbox WHERE status = 'PENDING' ORDER BY id LIMIT $BATCH"
NEW="SELECT * FROM $DB.verify_event_outbox WHERE status = 'PENDING' AND retry_count < $MAX_RETRY ORDER BY id LIMIT $BATCH"

old_rows="$(q "SELECT COUNT(*) FROM ($OLD) t;")"
old_live="$(q "SELECT COUNT(*) FROM ($OLD) t WHERE event_id = 'evt-live-0101';")"
new_rows="$(q "SELECT COUNT(*) FROM ($NEW) t;")"
new_live="$(q "SELECT COUNT(*) FROM ($NEW) t WHERE event_id = 'evt-live-0101';")"
new_b15="$(q "SELECT COUNT(*) FROM ($NEW) t WHERE event_id = 'evt-bound-15';")"
new_b16="$(q "SELECT COUNT(*) FROM ($NEW) t WHERE event_id = 'evt-bound-16';")"
new_sent="$(q "SELECT COUNT(*) FROM ($NEW) t WHERE event_id = 'evt-sent-0104';")"
kept="$(q "SELECT COUNT(*) FROM $DB.verify_event_outbox WHERE status = 'PENDING' AND retry_count >= $MAX_RETRY;")"

echo "[task142] 旧 SQL：rows=$old_rows contains_live=$old_live"
echo "[task142] 新 SQL：rows=$new_rows contains_live=$new_live contains_bound15=$new_b15 contains_bound16=$new_b16 contains_sent=$new_sent"
echo "[task142] 耗尽行留库=$kept"

fail=0
[ "$old_rows" = "$BATCH" ] || { echo "[task142] 红未复现：旧 SQL 应返回满批 $BATCH 行，实际 $old_rows" >&2; fail=1; }
[ "$old_live" = "0" ]      || { echo "[task142] 红未复现：旧 SQL 不应取到 evt-live-0101，实际 $old_live" >&2; fail=1; }
[ "$new_rows" = "2" ]      || { echo "[task142] 绿未达成：新 SQL 应返回 2 行（live/bound-15），实际 $new_rows" >&2; fail=1; }
[ "$new_live" = "1" ]      || { echo "[task142] 绿未达成：新 SQL 应取到 evt-live-0101，实际 $new_live" >&2; fail=1; }
[ "$new_b15" = "1" ]       || { echo "[task142] 绿未达成：retry_count=15（阈值-1）应仍有资格，实际 $new_b15" >&2; fail=1; }
[ "$new_b16" = "0" ]       || { echo "[task142] 绿未达成：retry_count=16（阈值）不应入选，实际 $new_b16" >&2; fail=1; }
[ "$new_sent" = "0" ]      || { echo "[task142] 绿未达成：SENT 行不应入选，实际 $new_sent" >&2; fail=1; }
[ "$kept" = "101" ]        || { echo "[task142] 保留语义不符：耗尽行应留库 101 行，实际 $kept" >&2; fail=1; }

# 代码接线判别：取批资格条件在位、relay 把当前上限传入取批查询
# （未修复实现上这两条为 0 命中 → 本脚本退出码 1，与 scratch SQL 红同向）
if ! grep -qF "retry_count < #{maxRetry}" "$MAPPER"; then
  echo "[task142] 代码红：Mapper 取批未见资格条件 retry_count < #{maxRetry}" >&2; fail=1
fi
if ! grep -qF "selectPendingBatch(batchSize, maxRetry)" "$RELAY"; then
  echo "[task142] 代码红：relay 未把当前重试上限传入取批查询" >&2; fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "[task142] 判别不成立（退出码 1）" >&2
  exit 1
fi
echo "[task142] 判别通过（退出码 0）：旧 SQL 遮挡已复现，新 SQL 放行后一正常行，耗尽行留库"
exit 0
