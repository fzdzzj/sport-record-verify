# sql/migrations — 存量库结构升级

## 两条路径（不要混用）

| 场景 | 走哪条路径 | 说明 |
| --- | --- | --- |
| **新建库** | `sql/01-*.sql` ~ `sql/04-*.sql` | 由 MySQL 容器 `docker-entrypoint-initdb.d` 在**数据卷首次初始化时**执行；`01-user-db.sql` 等已含当前代码所需列（含 `user.role`）。 |
| **存量库** | 本目录 + `bash scripts/db/migrate.sh` | 数据卷已存在时，再次 `docker compose up` **不会**重跑 initdb.d。必须显式执行迁移入口，把本目录脚本按文件名顺序注入运行中的 MySQL。 |

## 执行方式

在仓库根目录：

```bash
docker compose up -d   # 确保 sport-verify-mysql 健康
bash scripts/db/migrate.sh
```

- 默认容器 / 账号：`sport-verify-mysql` / `root` / `root`（可用 `MYSQL_CONTAINER`、`MYSQL_USER`、`MYSQL_PASSWORD` 覆盖）。
- 只遍历 `sql/migrations/*.sql`，**不会**执行 `sql/0*.sql` 首次建表脚本。
- 脚本已幂等，可重复执行；失败即停。

## 不要挂进 initdb.d

**禁止**把本目录挂进 `docker-entrypoint-initdb.d` 或复制进首次建表路径：

1. 存量升级与首次建表是两条路径，混挂会导致语义混乱。
2. `add-idx-record-seq.sql` **故意不进首次 initdb**，以便在全新卷、且**不跑** `migrate.sh` 的前提下复现「无索引」压测基线。默认 `migrate.sh` 仍会执行该脚本，给已有库补上索引。

## 当前脚本

| 文件 | 作用 |
| --- | --- |
| `add-idx-record-seq.sql` | 轨迹点等索引（存量默认升级；新装无索引基线时跳过 migrate） |
| `add-sport-type-threshold.sql` | 运动类型阈值相关结构 |
| `add-user-role.sql` | `user.role` 列（治理面 RBAC） |

新增迁移时：在本目录放幂等 `.sql`，由 `migrate.sh` 按文件名顺序自动拾取；不要改 `sql/01-04` 来「补」已有卷。