# TASK-264 立项任务书（批 G-6：SQL 函数索引订正与查询形态收尾群）

> 状态：**已立项待派发**（2026-10-17 指导侧立项；开工基线 main @ 44d5056；MEDIUM 档，指导亲核三块把关，免独立复核子 agent）

## 1. 证据链（指导侧亲验坐实，2026-10-17）

1. **N2 主项·函数索引双括号语法阻断建库与迁移**：
   - `sql/02-record-db.sql` L34 与 `sql/migrations/add-idx-record-coalesce-time.sql` L23 声明为：
     `KEY `idx_user_coalesce_time` ((`user_id`), (COALESCE(`start_time`, `created_at`)))`
   - MySQL 8.0 将 `(`user_id`)` 视为只含列名的表达式索引，直接报错：
     `ERROR 3762 (HY000): Functional index on a column is not supported. Consider using a regular index instead.`
   - 阻断后果：新容器 `docker-entrypoint-initdb.d` 在执行到 `02-record-db.sql` L34 时建表崩溃中断；存量库执行迁移脚本 `add-idx-record-coalesce-time.sql` 时 100% 失败报错；真实演示库 `record_db` 中 `idx_user_coalesce_time` 从未成功建出，E23 R5 的 sargable 范围扫描优化收益未兑现；
   - 订正语法：复合函数索引中普通列不可带外层括号，仅表达式需要外层括号：
     `KEY `idx_user_coalesce_time` (`user_id`, (COALESCE(`start_time`, `created_at`)))`
   - 指导侧真机实测验证：在 `sport-verify-mysql` 容器中测试该订正语法成功建出索引，且 `EXPLAIN SELECT * FROM sport_record WHERE user_id = 11 AND COALESCE(start_time, created_at) >= '2026-10-01 00:00:00'` 证实 `key: idx_user_coalesce_time`、`key_len: 14`、`type: range`，实现高效索引范围扫描。
2. **R5 决策·查询形态收尾与临时库销毁**：
   - TASK-260 在 167,365 行 `task260_scratch` 库实测 EXPLAIN 证实：在遵守「全局禁加索引红线」的前提下，`pageFeed` 的 filesort 无法通过 Java wrapper 查询改写消除（消除必须追加 `(visibility, created_at)` 联合索引）；
   - 经架构决策裁定：保持既有 Java wrapper 实现不变，作为已知有界性能特征（LIMIT 20，内存占用极小）受限关闭；
   - 彻底销毁清理 TASK-260 留存的临时数据库 `task260_scratch`（执行 `DROP DATABASE IF EXISTS task260_scratch;`）。

## 2. 改动白名单（仅限下列文件，双向全等）

- **C-01 业务/配置/测试（3 文件）**：
  1. `sql/02-record-db.sql`（R1 N2 修正 sport_record 函数索引定义语法）
  2. `sql/migrations/add-idx-record-coalesce-time.sql`（R1 N2 修正迁移脚本中 ALTER TABLE 函数索引定义语法与头注释）
  3. `record-service/src/test/java/com/sportverify/record/db/SportRecordSqlContractTest.java`（R1 新增纯 JVM 契约测试，断言两 SQL 文件中函数索引语法正确且无双括号阻断）
- **C-02 台账（1 文件）**：
  4. `work/mailbox/tasks/TASK-264/handoff.md`（实施回填）

零触碰面声明：除上述 3+1 文件外，全仓代码、其它 sql 脚本、pom.xml、package.json、web 前端、docker-compose 等 100% 零触碰；Java 业务代码零触碰（`pageFeed` 保持原状）。

## 3. 修法要求

- **R1（N2 函数索引语法订正）**：
  - `sql/02-record-db.sql` L34 修正为：
    `    KEY `idx_user_coalesce_time` (`user_id`, (COALESCE(`start_time`, `created_at`)))`
  - `sql/migrations/add-idx-record-coalesce-time.sql` L2 注释与 L23 修正为：
    `    'ALTER TABLE `sport_record` ADD INDEX `idx_user_coalesce_time` (`user_id`, (COALESCE(`start_time`, `created_at`)))',`
  - 新增 `record-service/src/test/java/com/sportverify/record/db/SportRecordSqlContractTest.java`：纯 JVM 读取两 SQL 文件内容（利用向上迭代寻找仓库根目录工具方法），断言其包含正确形态 `idx_user_coalesce_time` (`user_id`, (COALESCE(`start_time`, `created_at`))) 且绝对不含 `((`user_id`)` 或 `((user_id)`；
  - 在真实 MySQL 8.0 容器 `sport-verify-mysql` 中测试迁移脚本执行通过，并通过 EXPLAIN 断言 `COALESCE(start_time, created_at) >= ?` 走 `idx_user_coalesce_time` 范围扫描；
- **R2（R5 查询形态收尾决策与 scratch 库清理）**：
  - `SportRecordService.pageFeed` 保持现有实现零触碰，正式作为已知边界收敛；
  - 在 MySQL 容器中执行 `DROP DATABASE IF EXISTS task260_scratch;` 释放磁盘空间。

## 4. 门禁与验证要求（MEDIUM 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（经 `D:\git\Git\usr\bin\bash.exe --login`），全量七模块，surefire 基线 **63/78/221/320/203/110/35** 只增不减（record 320 -> 321，净增 1 测）；
- 契约门：`scripts/verify/mailbox-contract.sh --open=TASK-264 --baseline=44d5056`；
- token 门 29 项逐位持平零减少（期望值口径：**C-02 树 = 2104 + 29 = 2133**）；
- 词面门模式文件法 + 探针阳性对照（四 locale 全 ZERO_HIT）；
- checkstyle delta=0；
- `git diff --check` 净；
- 修改文件保持既有行尾（LF/CRLF），新测试文件纯 LF 结尾；
- 双提交 C-01（业务/配置/测试）/ C-02（台账）；`git status --porcelain` 收口为空。

## 5. 受限声明（预登记）

1. 全局禁加索引红线严格遵守，pageFeed 不引入新索引；
2. MySQL 8.0.13+ 函数索引特性在低版本（如 MySQL 5.7）不兼容，本项目依赖 MySQL 8.0+ 运行环境；
3. 演示库 `record_db` 存量数据演进留存，不影响单元测试。

## 6. 停止条件与升级路径

- 若在真 MySQL 执行迁移或 DDL 仍出现语法错误立即停手；
- 若 surefire 任何模块测试失败或低于基线立即停手；
- 严禁触碰白名单外任何文件。

## 7. 验收判据

- C-01+C-02 双提交落库、白名单双向全等；
- 七门禁全绿、surefire 净增 +1（record 320→321）；
- 真实 MySQL 迁移通过与 EXPLAIN 验证通过；
- task260_scratch 成功清理；
- 指导亲核三块全 PASS。
