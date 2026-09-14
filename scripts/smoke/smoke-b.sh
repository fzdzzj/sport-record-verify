#!/usr/bin/env bash
# 灰度冒烟 Phase B：经网关提交记录 -> MQ -> verify 自动判定 -> 轮询判定结果
# 5.8 m/s 轨迹：基线(r1.speed=5.5) 应 REJECTED；灰度快照(6.6) 应 PASSED
set -u
# 前置：在仓库根目录运行；地址与路径均可由环境变量覆盖（仓库内无本机硬编码）
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
LOG_DIR=${LOG_DIR:-logs}
mkdir -p "$LOG_DIR"
TS=$(date +%s)

submit_and_wait() {
  local uid=$1 tag=$2 expect=$3
  local req="smoke-$tag-$TS"
  local body start end
  start=$(date -u +%Y-%m-%dT%H:%M:%S)
  end=$(date -u -d '+500 seconds' +%Y-%m-%dT%H:%M:%S)
  echo "---- 提交 userId=$uid（预期 verdict=$expect）----"
  printf '{"requestId":"%s","userId":%s,"sportType":1,"startTime":"%s","endTime":"%s","distance":2.9,"duration":500,"points":%s}' \
    "$req" "$uid" "$start" "$end" "$(cat "$LOG_DIR/smoke-track.json")" > "$LOG_DIR/smoke-submit-$tag.json"
  resp=$(curl -s -X POST "$BASE_URL/record/api/records" -H "Content-Type: application/json" --data-binary @"$LOG_DIR/smoke-submit-$tag.json")
  echo "提交响应: $resp"
  rid=$(echo "$resp" | sed -n 's/.*"recordId":\([0-9]*\).*/\1/p')
  if [ -z "$rid" ]; then echo "!! 未取到 recordId"; return 1; fi
  echo "recordId=$rid，轮询判定结果（最长 25s）..."
  for i in $(seq 1 25); do
    vr=$(curl -s "$BASE_URL/record/api/records/$rid/verify-result")
    v=$(echo "$vr" | sed -n 's/.*"verdict":\([0-9]*\).*/\1/p')
    if [ "$v" != "0" ] && [ -n "$v" ]; then
      echo "recordId=$rid 最终判定: $vr"
      break
    fi
    sleep 1
  done
  echo "$tag=$rid verdict=$v expect=$expect" >> "$LOG_DIR/smoke-verdicts.txt"
}

echo "== 5.灰度命中用户 105（105%100=5<10 → 快照 6.6，期望 PASSED=1）=="
submit_and_wait 105 hit 1
echo "== 6.未命中用户 150（50>=10 → 基线 5.5，期望 REJECTED=2）=="
submit_and_wait 150 miss 2
echo "== 汇总 =="
cat "$LOG_DIR/smoke-verdicts.txt"
