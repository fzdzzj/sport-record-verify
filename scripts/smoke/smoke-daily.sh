#!/usr/bin/env bash
# 全栈 /daily 端到端冒烟（TASK-116，闭环 TASK-110 C 期）
# 链路：登录取 token（user-service）→ 携带 Authorization: Bearer 经网关 → leaderboard /daily
#        → 断言响应结构 + 抽取「当日快照 top 名次距离」机器信号与期望比对。
# 在仓库根目录运行：bash scripts/smoke/smoke-daily.sh
# 不依赖 jq；仅用 curl/sed/grep/date（Git Bash 自带）。
#
# 判据语义（"rule version 机器信号"）：/daily 返回 leaderboard_daily_summary 的当日快照行，
# 其 top 名次距离唯一取自 verify→leaderboard 结算管线写出的沉淀（无字面 ruleVersion 字段，
# 故以沉淀行 top distance 作为可断言、可红绿的机器信号，详见 TASK-116 spec.md）。
# 红绿：改错 SMOKE_DAILY_EXPECT_TOP 期望值 → 红；还原 → 绿。
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-sport-verify-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"

PHONE="${SMOKE_DAILY_PHONE:-13900009999}"
PASSWORD="${SMOKE_DAILY_PASSWORD:-smoke-daily-116}"
# 期望的 top-1 距离与判定日期（红绿改动点：把期望值改错则断言失败）
EXPECT_TOP="${SMOKE_DAILY_EXPECT_TOP:-88.05}"
# 预置进隔离岛的固定距离（固定值，不随 EXPECT_TOP 变化，保证红绿可独立翻转）
SEED_TOP="${SMOKE_DAILY_SEED_TOP:-88.05}"
# 判定日期默认取 3 天前：结算管线只写 CURDATE()（见 LeaderboardDailySummaryMapper），
# 用一个必然不落在今天的过去日期作隔离岛，保证该日快照行仅由本脚本预置、结果确定。
DATE="${SMOKE_DAILY_DATE:-$(date -d '3 days ago' +%F)}"
LOG_DIR="${LOG_DIR:-logs}"
mkdir -p "$LOG_DIR"

echo "== smoke-daily：登录取 token → 携带身份头 → /leaderboard/daily =="
echo "BASE_URL=${BASE_URL}  DATE=${DATE}  EXPECT_TOP=${EXPECT_TOP}"

# ---------- 0. 预置当日快照沉淀（隔离岛日期，先清后插，保证 top-1 唯一可红绿）----------
docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" \
  -e "DELETE FROM record_db.leaderboard_daily_summary WHERE stat_date='$DATE';
      INSERT INTO record_db.leaderboard_daily_summary (stat_date,user_id,total_distance,record_count,updated_at)
      VALUES ('$DATE',1,$SEED_TOP,5,NOW());" \
  > /dev/null 2>&1 || { echo "未就绪: 无法预置快照隔离岛（MySQL 不可达?）" >&2; exit 3; }

# ---------- 1. 登录取 token ----------
LOGIN_BODY="{\"phone\":\"${PHONE}\",\"password\":\"${PASSWORD}\"}"
set +e
LOGIN="$(curl -sS -w '\n%{http_code}' -X POST "${BASE_URL}/api/auth/login" \
  -H 'Content-Type: application/json' -d "$LOGIN_BODY" 2>"${LOG_DIR}/smoke-daily-login.err")"
LOGIN_EC=$?
set -e
if [[ $LOGIN_EC -ne 0 ]]; then
  echo "未就绪: 无法连接 ${BASE_URL}（gateway/user-service 未起）" >&2
  exit 3
fi
LOGIN_HTTP="$(printf '%s' "$LOGIN" | tail -n1)"
LOGIN_BODY="$(printf '%s' "$LOGIN" | sed '$d')"
if [[ "$LOGIN_HTTP" == "502" || "$LOGIN_HTTP" == "503" ]]; then
  echo "未就绪: 网关返回 ${LOGIN_HTTP}（service 未起，不是断言失败）" >&2
  exit 3
fi
TOKEN="$(printf '%s' "$LOGIN_BODY" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
if [[ -z "$TOKEN" ]]; then
  echo "断言失败: 登录未取到 accessToken（HTTP ${LOGIN_HTTP}，body=${LOGIN_BODY}）" >&2
  exit 1
fi
echo "登录成功：HTTP ${LOGIN_HTTP}，token 已获取"

# ---------- 2. 携带身份头经网关调 /daily ----------
set +e
DAILY="$(curl -sS -w '\n%{http_code}' -X GET \
  "${BASE_URL}/leaderboard/api/leaderboard/daily?date=${DATE}&size=10" \
  -H "Authorization: Bearer ${TOKEN}" 2>"${LOG_DIR}/smoke-daily.err")"
DAILY_EC=$?
set -e
if [[ $DAILY_EC -ne 0 ]]; then
  echo "未就绪: 调用 /daily 连接失败（网关/leaderboard 未起）" >&2
  exit 3
fi
DAILY_HTTP="$(printf '%s' "$DAILY" | tail -n1)"
DAILY_BODY="$(printf '%s' "$DAILY" | sed '$d')"
if [[ "$DAILY_HTTP" == "502" || "$DAILY_HTTP" == "503" ]]; then
  echo "未就绪: 网关返回 ${DAILY_HTTP}（leaderboard 未起，不是断言失败）" >&2
  exit 3
fi

# ---------- 3. 断言响应结构 ----------
if [[ "$DAILY_HTTP" != "200" ]]; then
  echo "断言失败: HTTP ${DAILY_HTTP}（期望 200；网关 403=鉴权/interceptor 异常）" >&2
  exit 1
fi
if ! printf '%s' "$DAILY_BODY" | grep -q '"code":0'; then
  echo "断言失败: body.code != 0（body=${DAILY_BODY}）" >&2
  exit 1
fi
if ! printf '%s' "$DAILY_BODY" | grep -q '"data":\[{'; then
  echo "断言失败: data 非数组（body=${DAILY_BODY}）" >&2
  exit 1
fi
echo "断言通过: HTTP 200 + code=0 + data 为数组"

# ---------- 4. 抽取 top-1 距离与期望比对（rule version 机器信号）----------
# 首个 data 条目即 rank=1；抽取其 distance。sed 容忍空格差异。
TOP_DIST="$(printf '%s' "$DAILY_BODY" \
  | sed -n 's/.*"data":\[{"rank":1,"userId":[0-9]*,"nickname":"[^"]*","distance":\([0-9.]*\).*/\1/p' \
  | head -n1)"
if [[ -z "$TOP_DIST" ]]; then
  echo "断言失败: 未能从 data 数组抽取 rank=1 的 distance（body=${DAILY_BODY}）" >&2
  exit 1
fi
if [[ "$TOP_DIST" != "$EXPECT_TOP" ]]; then
  echo "断言失败: top-1 距离 ${TOP_DIST} ≠ 期望 ${EXPECT_TOP}" >&2
  exit 1
fi
echo "断言通过: top-1 距离 ${TOP_DIST} == 期望 ${EXPECT_TOP}"
echo "== smoke-daily 通过 =="