#!/usr/bin/env bash
# ============================================================
# PostGIS 路网空间库一键复验脚本（TASK-258 R3，运维工具）
#
# 职责：对 mapmatch-service 依赖的真实空间库做四步核验——
#   [1/4] 容器状态（运行中 + 库连通 + PostGIS 扩展可用）
#   [2/4] 路网边计数核对（默认 7780，与真实 OSM 切片数据集契约一致）
#   [3/4] GIST 空间索引存在性（idx_road_edge_geom ON road_edge USING GIST）
#   [4/4] EXPLAIN 执行计划核验（候选边预筛必须命中 GIST 索引，绝不允许 Seq Scan）
# 追加 --it 参数时，四步核验通过后定向执行真实库集成测试 MapMatchPostgisIT
#（--Dtest 显式点名，常规 surefire 不隐式收集；缺容器/口令时该测试 assumeTrue 跳过）。
#
# 用法：
#   bash scripts/mapmatch/verify-postgis.sh          # 四步核验
#   bash scripts/mapmatch/verify-postgis.sh --it     # 四步核验 + 真实库 IT
# 环境变量（均有默认值，可覆盖，口径同 import-road-network.sh）：
#   PG_CONTAINER / PG_USER / PG_DB   PostGIS 容器名 / 账号 / 库名
#   EXPECTED_EDGES                  期望路网边数（默认 7780）
#   POSTGIS_HOST / POSTGIS_PORT / POSTGIS_PASSWORD / POSTGIS_USER / POSTGIS_DB
#                                   传递给 MapMatchPostgisIT 的连接参数（见该类注释）
# 退出码：0 全部核验通过（--it 含测试绿）；1 任一核验失败。
# 注意：--it 走 offline Maven（-o -s .mvn-settings.xml），需本地仓已有兄弟模块
#   SNAPSHOT 构件（先成功跑过一次 scripts/verify/mvn-verify.sh --mode=offline test）。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

PG_CONTAINER="${PG_CONTAINER:-sport-verify-postgis}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-road_db}"
EXPECTED_EDGES="${EXPECTED_EDGES:-7780}"
RUN_IT=0
if [ "${1:-}" = "--it" ]; then
  RUN_IT=1
elif [ $# -gt 0 ]; then
  echo "用法: bash scripts/mapmatch/verify-postgis.sh [--it]" >&2
  exit 1
fi

psql() { docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -t -A "$@"; }

echo "[1/4] 检查 PostGIS 容器与库连通性 ..."
if [ "$(docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" 2>/dev/null || true)" != "true" ]; then
  echo "错误：容器 $PG_CONTAINER 未运行。请先执行 docker compose up -d postgis" >&2
  exit 1
fi
docker inspect -f '  容器状态: {{.State.Status}}（health: {{.State.Health.Status}}）端口映射: {{range $p, $conf := .NetworkSettings.Ports}}{{(index $conf 0).HostIp}}:{{$p}}->{{end}}' "$PG_CONTAINER"
psql -c "SELECT 1;" > /dev/null
psql -c "SELECT postgis_full_version();" | head -1 | sed 's/^/  PostGIS: /'
echo "  库连通与 PostGIS 扩展均正常"

echo "[2/4] 路网边计数核对（期望 $EXPECTED_EDGES）..."
EDGES="$(psql -c 'SELECT COUNT(*) FROM road_edge;')"
echo "  实测 road_edge 计数: $EDGES"
if [ "$EDGES" != "$EXPECTED_EDGES" ]; then
  echo "错误：路网边计数 $EDGES != 期望 $EXPECTED_EDGES（数据集被重灌或未完整导入）" >&2
  exit 1
fi

echo "[3/4] GIST 空间索引存在性核验 ..."
GIST_DEF="$(psql -c "SELECT indexname || ' => ' || indexdef FROM pg_indexes WHERE tablename = 'road_edge' AND indexdef ILIKE '%USING gist%';")"
echo "  $GIST_DEF"
if ! printf '%s' "$GIST_DEF" | grep -q 'idx_road_edge_geom'; then
  echo "错误：road_edge 缺少 GIST 空间索引 idx_road_edge_geom（候选边预筛将退化为全表扫描）" >&2
  exit 1
fi

echo "[4/4] EXPLAIN 执行计划核验（必须命中 GIST，绝不允许 Seq Scan）..."
PLAN="$(psql -c "EXPLAIN SELECT ST_AsText(geom) FROM road_edge WHERE ST_DWithin(geom, ST_MakeEnvelope(121.4700, 31.2300, 121.4750, 31.2350, 4326), 300.0 / 85000.0);")"
printf '%s\n' "$PLAN" | sed 's/^/  /'
if ! printf '%s\n' "$PLAN" | grep -q 'idx_road_edge_geom'; then
  echo "错误：执行计划未命中 idx_road_edge_geom 空间索引" >&2
  exit 1
fi
if printf '%s\n' "$PLAN" | grep -q 'Seq Scan on road_edge'; then
  echo "错误：执行计划出现全表 Seq Scan on road_edge" >&2
  exit 1
fi

echo "四步核验全部通过：容器健康 / $EXPECTED_EDGES 条路网 / GIST 索引在位 / 执行计划命中 idx_road_edge_geom"

if [ "$RUN_IT" -eq 1 ]; then
  echo "[--it] 定向执行真实库集成测试 MapMatchPostgisIT（offline）..."
  cd "$ROOT_DIR"
  mvn -o -s .mvn-settings.xml --pl mapmatch-service test \
      -Dtest=MapMatchPostgisIT -Dsurefire.failIfNoSpecifiedTests=false
  echo "MapMatchPostgisIT 测试通过"
fi
