#!/usr/bin/env bash
# 冒烟前置：登录路径 schema 漂移探测（错误凭据）
# 在仓库根目录运行：bash scripts/smoke/smoke-schema.sh
# 不依赖 jq；不加内部凭证头
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
PHONE="${SMOKE_SCHEMA_PHONE:-13900001111}"
PASSWORD="${SMOKE_SCHEMA_PASSWORD:-wrongpass}"
URL="${BASE_URL}/api/auth/login"
BODY="{\"phone\":\"${PHONE}\",\"password\":\"${PASSWORD}\"}"

echo "== smoke-schema：POST ${URL}（错误凭据）=="

# 捕获 HTTP 码与 body；连接失败时 curl 非 0
set +e
HTTP_AND_BODY="$(curl -sS -w '\n%{http_code}' -X POST "$URL" \
  -H 'Content-Type: application/json' \
  -d "$BODY" 2>"${TMPDIR:-/tmp}/smoke-schema-curl.err")"
CURL_EC=$?
set -e

if [[ $CURL_EC -ne 0 ]]; then
  err="$(cat "${TMPDIR:-/tmp}/smoke-schema-curl.err" 2>/dev/null || true)"
  echo "失败: 无法连接 ${URL}（服务未就绪）。请先启动网关与 user-service。" >&2
  [[ -n "$err" ]] && echo "curl: $err" >&2
  exit 1
fi

HTTP_CODE="$(printf '%s' "$HTTP_AND_BODY" | tail -n1)"
RESP_BODY="$(printf '%s' "$HTTP_AND_BODY" | sed '$d')"

echo "HTTP ${HTTP_CODE}"
echo "body: ${RESP_BODY}"

# 从 JSON 中粗提取 "code": 数字（不依赖 jq）
CODE=""
if [[ "$RESP_BODY" =~ \"code\"[[:space:]]*:[[:space:]]*(-?[0-9]+) ]]; then
  CODE="${BASH_REMATCH[1]}"
fi
echo "code=${CODE:-<missing>}"

# 服务未就绪：502/503
if [[ "$HTTP_CODE" == "502" || "$HTTP_CODE" == "503" ]]; then
  echo "失败: HTTP ${HTTP_CODE}，服务未就绪（网关或 user-service）。不是 schema 缺列。" >&2
  exit 1
fi

# 账号锁定：403 或 code=1002（不得判为缺列，也不得判通过）
if [[ "$HTTP_CODE" == "403" || "$CODE" == "1002" ]]; then
  echo "失败: 账号锁定（HTTP ${HTTP_CODE}, code=${CODE}）。请换未锁定手机号（SMOKE_SCHEMA_PHONE），不是 schema 缺列。" >&2
  exit 1
fi

# 硬失败：HTTP 500 或 code=9999（schema/系统错误）
if [[ "$HTTP_CODE" == "500" || "$CODE" == "9999" ]]; then
  echo "硬失败: schema/系统错误（HTTP ${HTTP_CODE}, code=${CODE}）。请先 bash scripts/db/migrate.sh 对齐存量库。" >&2
  exit 1
fi

# 通过：HTTP 401 且 code=1001
if [[ "$HTTP_CODE" == "401" && "$CODE" == "1001" ]]; then
  echo "通过: HTTP 401 + code=1001（凭据错误路径已跑通，schema 可用）"
  exit 0
fi

echo "失败: 未识别响应（HTTP ${HTTP_CODE}, code=${CODE:-<missing>}）。期望 401+1001；500/9999 为硬失败；403/1002 锁定；502/503 未就绪。" >&2
exit 1