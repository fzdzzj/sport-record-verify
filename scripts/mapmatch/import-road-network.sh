#!/usr/bin/env bash
# ============================================================
# OSM 路网导入脚本（mapmatch-service 路网匹配，见 ADR-0006）
#
# 流程：下载单城市 OSM 路网切片（Overpass API，默认上海·人民广场周边）
#       → PostGIS 建表 + GIST 索引（幂等 IF NOT EXISTS）
#       → TRUNCATE 重灌道路边表 → 抽样自验
#
# 幂等口径：建表幂等（IF NOT EXISTS）；数据重跑 = TRUNCATE 后覆盖刷新；
# 切片文件已存在则跳过下载（FORCE=1 强制重下）。整个脚本可反复执行。
#
# 用法：
#   bash scripts/mapmatch/import-road-network.sh
# 环境变量（均有默认值，可覆盖）：
#   OVERPASS_URL  Overpass API 地址（默认 https://overpass-api.de/api/interpreter，
#                 被限流时可换镜像 https://overpass.kumi.systems/api/interpreter）
#   BBOX          切片范围 south,west,north,east（默认覆盖上海人民广场 31.2304,121.4737
#                 —— 压测样本轨迹基准点，路网与既有正负样本同区域）
#   JAVA_BIN      JDK21+ 的 java 可执行文件（单文件源码启动需 11+；PATH 上是 JDK8 时必须显式指定，
#                 如 JAVA_BIN="D:/develop1/jdk21/bin/java"）
#   PG_CONTAINER / PG_USER / PG_DB   PostGIS 容器名 / 账号 / 库名
# 数据文件（切片 XML）不入库：落在 scripts/mapmatch/data/（.gitignore 排除），
# 可随时由本脚本重新生成。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DATA_DIR="$SCRIPT_DIR/data"
mkdir -p "$DATA_DIR"

OVERPASS_URL="${OVERPASS_URL:-https://overpass-api.de/api/interpreter}"
BBOX="${BBOX:-31.2050,121.4300,31.2550,121.5100}"
JAVA_BIN="${JAVA_BIN:-java}"
PG_CONTAINER="${PG_CONTAINER:-sport-verify-postgis}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-road_db}"
OSM_FILE="$DATA_DIR/shanghai-slice.osm"

psql() { docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 "$@"; }

echo "[1/4] 检查 PostGIS 容器与库连通性 ..."
if [ "$(docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" 2>/dev/null)" != "true" ]; then
  echo "错误：容器 $PG_CONTAINER 未运行。请先执行 docker compose up -d postgis" >&2
  exit 1
fi
psql -c "SELECT version();" > /dev/null
psql -c "SELECT postgis_full_version();" > /dev/null   # 确认 postgis 扩展镜像可用

echo "[2/4] 下载 OSM 路网切片（BBOX=$BBOX，已存在则跳过，FORCE=1 强制重下）..."
if [ ! -s "$OSM_FILE" ] || [ "${FORCE:-0}" = "1" ]; then
  # Overpass QL：取范围内白名单 highway 的 way（含步行/骑行道，降低沿河/公园真实轨迹误判），
  # (._;>;) 递归补齐 way 引用的 node，out body 输出完整 XML
  QUERY='[out:xml][timeout:180];way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road|pedestrian|footway|cycleway|path|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]('"$BBOX"');(._;>;);out body;'
  curl -sf --retry 2 -m 600 "$OVERPASS_URL" --data-urlencode "data=$QUERY" -o "$OSM_FILE"
  echo "      已下载：$(ls -lh "$OSM_FILE" | awk '{print $5}') → $OSM_FILE"
else
  echo "      复用既有切片：$OSM_FILE"
fi

echo "[3/4] 建表 + GIST 索引（幂等），TRUNCATE 后重灌道路边表 ..."
psql < "$SCRIPT_DIR/road_schema.sql" > /dev/null
psql -c "TRUNCATE road_edge RESTART IDENTITY;" > /dev/null
# 单文件源码启动跑导入器，SQL 直接管道进 psql（零中间文件）
"$JAVA_BIN" "$SCRIPT_DIR/OsmImporter.java" "$OSM_FILE" | psql > /dev/null

echo "[4/4] 导入自验：路网表总量 + 按类型抽样 ..."
psql -c "SELECT count(*) AS edges, count(DISTINCT osm_way_id) AS ways, round(sum(ST_Length(geom::geography))) AS total_m FROM road_edge;"
psql -c "SELECT highway_type, road_level, count(*) FROM road_edge GROUP BY highway_type, road_level ORDER BY count(*) DESC LIMIT 8;"
echo "完成。可用 curl -s -X POST http://127.0.0.1:8085/match -H 'Content-Type: application/json' -d '{...}' 验证匹配（需 mapmatch-service 已启动）"
