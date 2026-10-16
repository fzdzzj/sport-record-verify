#!/usr/bin/env bash
# 临时自检脚本（交付后可删）：四个服务健康端点 + 网关路由冒烟
for p in 8080 8081 8082 8083; do
  printf "%s -> " "$p"
  curl -s -m 3 "http://127.0.0.1:$p/actuator/health" | head -c 160
  echo
done
echo "== gateway route smoke =="
# TASK-251 起网关对 internal 路径段一律 404（外部不可达语义），冒烟改走网关公开只读路由
curl -s -m 5 "http://127.0.0.1:8080/leaderboard/api/leaderboard?type=overall" | head -c 200; echo
