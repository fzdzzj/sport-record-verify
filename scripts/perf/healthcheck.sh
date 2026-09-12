#!/usr/bin/env bash
# 临时自检脚本（交付后可删）：四个服务健康端点 + 网关路由冒烟
for p in 8080 8081 8082 8083; do
  printf "%s -> " "$p"
  curl -s -m 3 "http://127.0.0.1:$p/actuator/health" | head -c 160
  echo
done
echo "== gateway route smoke =="
curl -s -m 5 "http://127.0.0.1:8080/user/internal/health" | head -c 200; echo
curl -s -m 5 "http://127.0.0.1:8080/verify/internal/health" | head -c 200; echo
