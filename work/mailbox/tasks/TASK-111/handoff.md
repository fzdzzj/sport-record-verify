# TASK-111 Handoff

**实现方：执行 agent。未 commit、未 push、未建 PR。** 开工基线 `9c90d32`。

## 只改清单

- record-service/src/main/resources/sharding.yaml
- record-service/src/test/resources/sharding.yaml
- record-service/src/test/java/com/sportverify/record/config/ShardingDataSourceConfigTest.java
- spec/changes/add-sharding-host-parameterization/proposal.md
- spec/changes/add-sharding-host-parameterization/tasks.json
- spec/changes/add-sharding-host-parameterization/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-111/spec.md
- work/mailbox/tasks/TASK-111/handoff.md
- work/mailbox/PLAN.md

## 环境核实

- 中间件六件套在位；MySQL 容器 `sport-verify-mysql`（镜像 `mysql:8.0`）在 compose 网络
  `sport-verify_sport-verify-net`，容器端口 3306、root 口令 root、`record_db` 存在；仓库根有 `.env`。
- 宿主壳 `SHARDING_MYSQL_HOST`/`MYSQL_PORT` 未设 → 单测默认分支走 `127.0.0.1`；此为宿主直跑口径。

## 红绿取证

- **红（单测）**：注入 `SHARDING_MYSQL_HOST=mysql` 运行 `--mode=offline test`，同时用例②
  `realHostVariable_defaultOrOverrideBranch` 临时改固定期望 `127.0.0.1` →
  `ShardingDataSourceConfigTest.realHostVariable_defaultOrOverrideBranch:70` `expected: <true> but was: <false>`；
  `record-service` BUILD FAILURE（record `Tests run: 80, Failures: 1`）。还原为分支断言 → 绿。
  *说明：本任务不用 `mockStatic(System.class)`——Mockito 对 java.lang.System 静态 mock 有类加载
  无限循环保护，直接写会抛 `It is not possible to mock static methods of java.lang.System`；
  红绿取证以上述「运行级注入 env + 临时期望编辑」复现，机制与断言仍覆盖默认/覆盖两分支。*
- **红（容器）**：`docker run --rm --network sport-verify_sport-verify-net mysql:8.0 mysql -h 127.0.0.1 -P 3306 -uroot -proot -e "SELECT 1"`
  → `ERROR 2003 (HY000): Can't connect to MySQL server on '127.0.0.1:3306' (111)`，退出 1（容器内回环不可达，
  证判据测的是服务名寻址，非宿主回环）。
- **绿（单测）**：`--mode=offline test` BUILD SUCCESS；`ShardingDataSourceConfigTest` `Tests run: 5`，
  record 合计 `Tests run: 80`，全仓 283。
- **绿（容器）**：`docker run --rm --network sport-verify_sport-verify-net mysql:8.0 mysql -h mysql -P 3306 -uroot -proot -e "USE record_db; SELECT 'reachable' AS verdict"`
  → 输出 `verdict / reachable`，退出 0。

## 实跑结论（规范口径，均经 `mvn-verify.sh`）

- `--mode=offline test`：BUILD SUCCESS，模块合计 17/19/31/**80**/81/49/6 = **283**（record 基线 78→80，
  全仓 281→283，只增不减）。
- 构建产物：`record-service/target/classes/sharding.yaml` L22 含
  `jdbc:mysql://${SHARDING_MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/record_db?...`
  （源资源真进了构建输出）。
- `docker compose -f docker-compose.yml -f docker-compose.services.yml config`：退出 0；record-service
  environment 展开含 `SHARDING_MYSQL_HOST: mysql`（services.yml 注入 + compose 合并生效）。
- 容器内可达（核心判据）：同网络一次性 mysql 客户端连 `mysql:3306` 服务名成功、`USE record_db` 可查、退出 0。

## 契约自证

- 收口提交前 `bash scripts/verify/mailbox-contract.sh --open=TASK-018,TASK-106` 脏树期望退出 1：
  历史 TASK-104/109 清单与公共文件（PLAN.md）过冲属预期；TASK-111 本身只改白名单 9 文件。
- 收口提交后 ACTUAL 空 → 该任务足迹不在工作树、视为已收口，契约退出 0。
  （当前行号以回传时实际输出为准；如实记录退出码与残余项。）

## 台账

- PLAN.md 已按收口清单五条追加 `sharding.yaml 的 MySQL host 参数化（TASK-111）` 验收记录。
- `spec/changes/add-sharding-host-parameterization/tasks.json` 已回填 `completed:true` / `passes:true`。

## 未决 / 分期

- 无遗留代码项。宿主全栈端到端 `/daily` 仍属 TASK-110 的分期未覆盖（本任务不重提）。
- 未达外部门槛：修订仅本地、未 push，无 CI run 编号可绑；结论均来自本地唯一入口实跑。