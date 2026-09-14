#!/usr/bin/env bash
# 灰度冒烟 Phase A：创建版本 -> 调灰度10（捕获 Redis 广播）-> 生成命中/未命中判定轨迹
set -u
# 前置：在仓库根目录运行；地址与路径均可由环境变量覆盖（仓库内无本机硬编码）
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}
LOG_DIR=${LOG_DIR:-logs}
REDIS_CONTAINER=${REDIS_CONTAINER:-sport-verify-redis}
mkdir -p "$LOG_DIR"

echo "== 0.后台订阅 Redis 失效广播频道（容器内 redis-cli，8s 超时）=="
docker exec "$REDIS_CONTAINER" sh -c 'timeout 8 redis-cli subscribe verify:rules:invalidate' > "$LOG_DIR/smoke-redis-sub.txt" 2>&1 &
SUB_PID=$!
sleep 1

echo "== 1.创建版本（gray=0，快照 r1.speed=6.6）=="
curl -s -X POST "$BASE_URL/verify/rules/versions" -H "Content-Type: application/json" \
  -d '{"version":"v-smoke-1","grayRatio":0,"rules":{"rules":{"r1":{"speed":6.6}},"policy":{}}}'
echo

echo "== 2.调灰度比例=10（应触发 Redis 广播）=="
curl -s -X PATCH "$BASE_URL/verify/rules/versions/1/gray" -H "Content-Type: application/json" -d '{"grayRatio":10}'
echo
sleep 8

echo "== 3.Redis 广播捕获结果 =="
cat "$LOG_DIR/smoke-redis-sub.txt"

echo "== 4.生成 5.8 m/s 匀速轨迹（基线 5.5 应拒、灰度快照 6.6 应过）=="
awk 'BEGIN{base=systime()*1000-300000; printf "["; for(i=0;i<60;i++){lat=i*29.0/111320.0; if(i>0)printf ","; printf "{\"seq\":%d,\"lat\":%.7f,\"lng\":0.0,\"ts\":%d}", i, lat, base+i*5000} printf "]"}' > "$LOG_DIR/smoke-track.json"
wc -c "$LOG_DIR/smoke-track.json"
