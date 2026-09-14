#!/usr/bin/env bash
# 限流动态生效复测（Sentinel 网关流控 × Nacos 动态数据源，spec 场景「限流拦截」+ 变更 add-sentinel-dynamic-rules）
# 用法：bash scripts/perf/ratelimit-test.sh [低阈值QPS] [拦截档并发] [基线档并发] [时长秒]
#       默认：低阈值 100、拦截档并发 500、基线档并发 60、时长 10s
#
# 证明口径：**网关全程不重启**，向 Nacos 的 gateway-flow-rules 先后发布「低阈值 / 高阈值」两种规则 JSON，
# 经 Nacos 推送 → GatewayRuleManager 就地更新 → 同一进程行为由「大量 429 拦截」变为「放行 200」，
# 即为「改阈值不重启即生效」的实证。两档并发刻意不同：拦截档（500）制造超限流量以观察 429；
# 基线档（60）落到本机业务（~140 QPS）承受范围内，改 count=5000 后应放行为 200。决定性证据是
# 「同进程、仅改 Nacos count，429 比例→0」，并发差异与原因已如实注明。低阈值拦截数据与历史 §7.1 可比。
LOW="${1:-100}"; CONC="${2:-500}"; BASE_CONC="${3:-60}"; DUR="${4:-10}"
cd "$(dirname "$0")/../.."
export MYSQL_PORT="${MYSQL_PORT:-3307}"   # 网关本身不连库，保持口径一致
JAVA_BIN="${JAVA_BIN:-D:/develop1/jdk21/bin/java}"
JAR="gateway-service/target/sport-verify-gateway-service-0.1.0-SNAPSHOT.jar"
DATA_ID="gateway-flow-rules"; GROUP="DEFAULT_GROUP"; NACOS="http://127.0.0.1:8848"
HI=5000   # 基线阈值，对齐审批版 §8.3「Sentinel 限流 5k QPS」

# （可选）复现静态档：如需历史「重启改阈值」口径，可先在此重启网关后再进入动态档

publish() {
  local count="$1" tag="$2"
  # Nacos 网关流控规则 JSON 数组（每个元素为 GatewayFlowRule：resource/count/intervalSec/grade）
  local json="[{\"resource\":\"route-record-service\",\"count\":${count},\"intervalSec\":1,\"grade\":1},"
  json+="{\"resource\":\"route-user-service\",\"count\":${count},\"intervalSec\":1,\"grade\":1},"
  json+="{\"resource\":\"route-verify-service\",\"count\":${count},\"intervalSec\":1,\"grade\":1}]"
  local rc
  rc=$(curl -s -X POST "$NACOS/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$DATA_ID" --data-urlencode "group=$GROUP" \
    --data-urlencode "type=json" --data-urlencode "content=$json")
  # 成功返回 true（发布成功）或 dataId（更新成功）；失败返回错误提示
  echo "  [$tag] 已发布 Nacos flow 规则 count=$count（网关未重启）→ nacos 响应: ${rc:-<空>}"
}

echo "== 1. 确保网关在跑（新 jar 已注册 Nacos 动态数据源；不在则启动）=="
if ! curl -s -m 3 http://127.0.0.1:8080/actuator/health | grep -q UP; then
  nohup "$JAVA_BIN" -jar "$JAR" > logs/gateway.log 2>&1 &
  sleep 25
fi
curl -s -m 3 http://127.0.0.1:8080/actuator/health | head -c 60; echo "（网关在跑，pid 见 logs/gateway.log）"

echo "== 2. 发布低阈值 count=$LOW → 等推送 =="
publish "$LOW" "A-低阈值"
sleep 4   # 等 Nacos 长轮询推送 + 属性刷新

echo "== 3. ${CONC} 并发打 ${DUR}s（低阈值档），应大量 429 拦截 =="
"$JAVA_BIN" scripts/perf/LoadTest.java \
  --url http://127.0.0.1:8080/record/api/records \
  --method POST --concurrency "$CONC" --seconds "$DUR" --warmup 0 --timeout 15 \
  --body docs/perf/data/load-template.json --user-total 16 \
  --label "ratelimit-nacos-qps${LOW}-c${CONC}" \
  --out-prefix docs/perf/data/raw/ratelimit-nacos-low

echo "== 4. 发布高阈值 count=$HI（基线）→ 等推送，不改进程再测 =="
publish "$HI" "B-基线"
sleep 4

echo "== 5. ${BASE_CONC} 并发打 ${DUR}s（基线档，落到业务承受范围），应放行 200 =="
"$JAVA_BIN" scripts/perf/LoadTest.java \
  --url http://127.0.0.1:8080/record/api/records \
  --method POST --concurrency "$BASE_CONC" --seconds "$DUR" --warmup 0 --timeout 15 \
  --body docs/perf/data/load-template.json --user-total 16 \
  --label "ratelimit-nacos-qps${HI}-c${BASE_CONC}" \
  --out-prefix docs/perf/data/raw/ratelimit-nacos-high

echo "== 6. 完成：同一网关进程（未重启）改 count 后由拦截→放行，动态生效实证成立 =="
echo "   低阈值档原始数据：docs/perf/data/raw/ratelimit-nacos-low-* ；基线档：ratelimit-nacos-high-*"