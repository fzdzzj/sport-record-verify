#!/usr/bin/env bash
# 存量库迁移入口：仅执行 sql/migrations/*.sql（不跑 sql/0*.sql 首次建表）
# 在仓库根目录运行：bash scripts/db/migrate.sh
set -euo pipefail

# 定位仓库根（本脚本位于 scripts/db/）
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-sport-verify-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MIGRATIONS_DIR="${ROOT_DIR}/sql/migrations"

echo "== 存量库迁移入口 =="
echo "容器: ${MYSQL_CONTAINER}"
echo "脚本目录: ${MIGRATIONS_DIR}"

# 容器必须存在且健康，否则不假装迁移成功
if ! docker inspect "$MYSQL_CONTAINER" >/dev/null 2>&1; then
  echo "错误: 容器 ${MYSQL_CONTAINER} 不存在。请先在仓库根执行: docker compose up -d" >&2
  exit 1
fi

health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$MYSQL_CONTAINER" 2>/dev/null || true)"
if [[ "$health" != "healthy" && "$health" != "running" ]]; then
  echo "错误: 容器 ${MYSQL_CONTAINER} 状态为 ${health:-unknown}，未就绪。请先: docker compose up -d" >&2
  exit 1
fi

# 仅遍历 migrations（禁止 sql/0*.sql）；按文件名排序，保证确定顺序
shopt -s nullglob
files=("${MIGRATIONS_DIR}"/*.sql)
if [[ ${#files[@]} -eq 0 ]]; then
  echo "未找到 ${MIGRATIONS_DIR}/*.sql，无事可做"
  exit 0
fi

ok=0
total=0
# 用 sort 保证顺序；不用 mapfile 以兼容 Git Bash 旧版本
while IFS= read -r f; do
  [[ -z "$f" ]] && continue
  total=$((total + 1))
  base="$(basename "$f")"
  echo "-- 执行: ${base}"
  if docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" < "$f"; then
    echo "   结果: 成功 (${base})"
    ok=$((ok + 1))
  else
    echo "   结果: 失败 (${base})" >&2
    echo "迁移中止。已成功 ${ok} 个文件。" >&2
    exit 1
  fi
done < <(printf '%s\n' "${files[@]}" | LC_ALL=C sort)

echo "== 完成: ${ok}/${total} 个迁移脚本执行成功 =="