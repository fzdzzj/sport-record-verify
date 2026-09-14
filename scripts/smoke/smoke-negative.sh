#!/usr/bin/env bash
# 灰度冒烟 Phase N：规则版本接口的负向用例（契约错误码校验）
set -u
# 前置：在仓库根目录运行；地址与路径均可由环境变量覆盖（仓库内无本机硬编码）
BASE_URL=${BASE_URL:-http://127.0.0.1:8080}

echo "== N1.对 ACTIVE 版本调灰度（应 4003 拒绝）=="
curl -s -X PATCH "$BASE_URL/verify/rules/versions/1/gray" -H "Content-Type: application/json" -d '{"grayRatio":50}'
echo
echo "== N2.重复版本号（应 4004 冲突）=="
curl -s -X POST "$BASE_URL/verify/rules/versions" -H "Content-Type: application/json" \
  -d '{"version":"v-smoke-1","grayRatio":0}'
echo
echo "== N3.比例越界（应 4004 拒绝）=="
curl -s -X PATCH "$BASE_URL/verify/rules/versions/1/gray" -H "Content-Type: application/json" -d '{"grayRatio":150}'
echo
echo "== N4.不存在版本（应 4002）=="
curl -s -X PATCH "$BASE_URL/verify/rules/versions/999/gray" -H "Content-Type: application/json" -d '{"grayRatio":0}'
echo
