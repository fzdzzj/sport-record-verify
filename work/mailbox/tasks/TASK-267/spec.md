# TASK-267 立项任务书（N4 存量库迁移脚本断链缺陷修复与预检加固）

> 状态：**已立项待派发**（2026-10-18 指导侧立项；开工基线 main @ 970c404；MEDIUM 档，指导亲核三块把关，免独立复核子 agent）

## 1. 证据链（压测实测暴露与指导侧亲验坐实，2026-10-18）

1. **故障现象（TASK-265 真实压测环境暴露）**：
   - 在 TASK-265 压测期间，排查存量 MySQL 卷元数据时发现，执行 `scripts/db/migrate.sh` 会在 `add-leaderboard-daily-summary.sql` 处抛出 `ERROR 1046 (3D000) at line 6: No database selected`，导致全量迁移执行直接中断（已在 `TASK-265/handoff.md:40` 留档登记）；
   - 由于脚本开启了 `set -euo pipefail`，该错误导致其后按字母序排列的 4 个关键迁移脚本（`add-record-visibility.sql`、`add-sport-type-threshold.sql`、`add-track-archive-column-and-idx.sql`、`add-user-role.sql`）被静默漏执行；
   - 随之引发 `sport_record.visibility` 列在存量卷上缺失，进而导致 Feed 流与点赞接口首探 500（`ShardingSphere UnknownColumn`），当时在环境上临时手动单跑该脚本弥补。
2. **根因定位**：
   - 审查 `sql/migrations/` 下全部 13 个 `.sql` 文件：其余 12 个文件均显式声明了 `USE <db>;`（如 `USE record_db;`、`USE user_db;`、`USE verify_db;`）；
   - 唯独 `sql/migrations/add-leaderboard-daily-summary.sql` 缺少 `USE record_db;`，直接执行 `CREATE TABLE IF NOT EXISTS leaderboard_daily_summary...`；
   - `scripts/db/migrate.sh` 逐个执行脚本时未指定默认数据库（`docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" < "$f"`），因此无 `USE` 语句即触发 MySQL `No database selected`；
   - 并且 `scripts/db/migrate.sh` 缺少对迁移文件的静态预检（preflight validation），未能事前拦截不合规的 SQL 脚本。
3. **修法方案**：
   - **R1（迁移脚本补齐 USE 语句）**：在 `sql/migrations/add-leaderboard-daily-summary.sql` 头部补充 `USE record_db;`，与 `sql/02-record-db.sql` 及其他迁移文件风格完全对齐；
   - **R2（migrate.sh 预检防御）**：在 `scripts/db/migrate.sh` 循环执行前增加快速静态预检：逐个校验待执行的 `.sql` 脚本必须包含 `USE ` 语句，若发现缺少则友好报错并退出（fail-fast），杜绝未来任何无库限定词脚本混入；
   - **R3（自动化契约单测）**：在 `record-service` 新增纯 JVM 契约单测 `SqlMigrationContractTest.java`，自动化校验：
     1. `sql/migrations/` 下所有 `.sql` 文件必须声明合法的 `USE (record_db|user_db|verify_db);`；
     2. `add-leaderboard-daily-summary.sql` 必须声明 `USE record_db;`，且表结构声明与 `sql/02-record-db.sql` 逐字保持一致；
     3. 确保 surefire `record-service` 测试计数由 322 净增至 **323**（只增不减）。

## 2. 改动白名单（仅限下列文件，双向全等）

- **C-01 业务/脚本/测试（3 文件）**：
  1. `sql/migrations/add-leaderboard-daily-summary.sql`（R1 补齐 USE record_db;）
  2. `scripts/db/migrate.sh`（R2 迁移脚本静态预检防御）
  3. `record-service/src/test/java/com/sportverify/record/db/SqlMigrationContractTest.java`（R3 自动化契约单测）
- **C-02 台账（1 文件）**：
  4. `work/mailbox/tasks/TASK-267/handoff.md`（实施回填）

零触碰面声明：除上述 3+1 文件外，全仓其他微服务生产 Java 源码、既有单测、pom.xml、package.json、前端 web/、docker-compose、.env 等 100% 零触碰。

## 3. 修法要求

- **R1（迁移文件补充 USE）**：
  - 在 `sql/migrations/add-leaderboard-daily-summary.sql` 头部注释后、`CREATE TABLE` 之前，添加 `USE record_db;`；保持原有表定义及字段注释不变。
- **R2（migrate.sh 增加预检）**：
  - 在 `scripts/db/migrate.sh` 遍历执行前，增加预检逻辑：检查每个 `.sql` 文件是否包含 `^[[:space:]]*USE[[:space:]]+[a-zA-Z0-9_]+;`；若未包含则输出明确错误信息并 `exit 1`。
- **R3（自动化契约单测）**：
  - 新增 `SqlMigrationContractTest.java`，纯 JVM 单测，通过向上查找仓库根目录定位 `sql/migrations/` 目录与 `sql/02-record-db.sql`；
  - 断言 `sql/migrations/*.sql` 每一个文件均包含合法的 `USE` 库名语句；
  - 断言 `add-leaderboard-daily-summary.sql` 包含 `USE record_db;`；
  - surefire record-service 测试计数 322 -> **323**（只增不减）。

## 4. 门禁与验证要求（MEDIUM 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`，全量七模块 surefire 基线 **63/78/221/322/203/110/36** 只增不减（record-service 322→323，净增 1 测，总计 1034 测，零失败零跳过）；
- 契约门：`scripts/verify/mailbox-contract.sh --open=TASK-267 --baseline=970c404`；
- token 门 29 项逐位持平零减少（期望值口径：**C-02 树 = 2220 + 29 = 2249**）；
- 词面门模式文件法 + 探针阳性对照（四 locale 全 ZERO_HIT）；
- checkstyle delta=0；
- `git diff --check` 净；
- 修改文件保持既有行尾（LF/CRLF），新测试文件纯 LF 结尾；
- 双提交 C-01（业务/脚本/测试）/ C-02（台账）；`git status --porcelain` 收口为空；
- MEDIUM 档由指导侧亲核三块（Git 摘要、高风险定向抽查、门禁与读数裁决）把关，免独立复核子 agent。

## 5. 受限声明（预登记）

1. 本课题聚焦修复 `migrate.sh` 脚本及其迁移文件的无库限定词缺陷并增加自动化防护网，不变更既有表结构或业务逻辑；
2. 保持依赖边界纯净，不引入任何新的外部库。

## 6. 停止条件与升级路径

- 若修改导致既有测试断言失败，立即停手排查；
- 若 surefire 离线门禁任何模块测试失败或计数减少，立即停手；
- 严禁触碰白名单外任何文件。

## 7. 验收判据

- C-01+C-02 双提交落库、白名单双向全等；
- 七门禁全绿，surefire record-service 净增 +1（322→323，总测试 1034）；
- 新增契约测试稳定通过；
- 指导亲核三块全 PASS。
