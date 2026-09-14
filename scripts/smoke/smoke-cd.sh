#!/usr/bin/env bash
# 灰度冒烟 Phase C+D：回滚(gray=0)立即生效 -> 全量发布(activate)旧版本退役
set -u
# 前置：在仓库根目录运行；地址与路径均可由环境变量覆盖（仓库内无本机硬编码）
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
LOG_DIR=${LOG_DIR:-logs}
REDIS_CONTAINER=${REDIS_CONTAINER:-sport-verify-redis}
mkdir -p "$LOG_DIR"
TS=$(date +%s)

submit_and_wait() {
  local uid=$1 tag=$2 expect=$3
  local req="smoke-$tag-$TS"
  local start end
  start=$(date -u +%Y-%m-%dT%H:%M:%S)
  end=$(date -u -d '+500 seconds' +%Y-%m-%dT%H:%M:%S)
  echo "---- 提交 userId=$uid（预期 verdict=$expect）----"
  printf '{"requestId":"%s","userId":%s,"sportType":1,"startTime":"%s","endTime":"%s","distance":2.9,"duration":500,"points":%s}' \
    "$req" "$uid" "$start" "$end" "$(cat "$LOG_DIR/smoke-track.json")" > "$LOG_DIR/smoke-submit-$tag.json"
  resp=$(curl -s -X POST "$BASE_URL/record/api/records" -H "Content-Type: application/json" --data-binary @"$LOG_DIR/smoke-submit-$tag.json")
  echo "提交响应: $resp"
  rid=$(echo "$resp" | sed -n 's/.*"recordId":\([0-9]*\).*/\1/p')
  [ -z "$rid" ] && { echo "!! 未取到 recordId"; return 1; }
  for i in $(seq 1 25); do
    vr=$(curl -s "$BASE_URL/record/api/records/$rid/verify-result")
    v=$(echo "$vr" | sed -n 's/.*"verdict":\([0-9]*\).*/\1/p')
    if [ "$v" != "0" ] && [ -n "$v" ]; then echo "recordId=$rid 最终判定: $vr"; break; fi
    sleep 1
  done
  echo "$tag=$rid verdict=$v expect=$expect" >> "$LOG_DIR/smoke-verdicts.txt"
}

echo "== 7.后台订阅广播，然后回滚 gray=0（应收到 versionId=1 广播）=="
docker exec "$REDIS_CONTAINER" sh -c 'timeout 6 redis-cli subscribe verify:rules:invalidate' > "$LOG_DIR/smoke-redis-sub2.txt" 2>&1 &
sleep 1
curl -s -X PATCH "$BASE_URL/verify/rules/versions/1/gray" -H "Content-Type: application/json" -d '{"grayRatio":0}'
echo; sleep 6
echo "Redis 广播捕获："
cat "$LOG_DIR/smoke-redis-sub2.txt"

echo "== 8.回滚后命中用户 105 再提交（应立即回归基线 → REJECTED=2）=="
submit_and_wait 105 rollback 2

echo "== 9.全量发布 activate（gray=100 + ACTIVE，广播）=="
curl -s -X POST "$BASE_URL/verify/rules/versions/1/activate"
echo

echo "== 10.全量后未命中用户 150 再提交（新基线 6.6 → PASSED=1）=="
submit_and_wait 150 full 1

echo "== 汇总 =="
cat "$LOG_DIR/smoke-verdicts.txt"
