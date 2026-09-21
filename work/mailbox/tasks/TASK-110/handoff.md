# TASK-110 Handoff

**实现方：执行 agent（事项 2/5）。未 commit、未 push、未建 PR。** 开工基线 `14d2600`。

## 只改清单

- scripts/verify/env.example
- scripts/verify/mvn-verify.sh
- leaderboard-service/src/test/java/com/sportverify/leaderboard/config/LeaderboardL2RedisRoundTripIT.java
- leaderboard-service/src/test/java/com/sportverify/leaderboard/mq/RocketMqBrokerRoundTripIT.java
- spec/changes/add-middleware-it-coverage/proposal.md
- spec/changes/add-middleware-it-coverage/tasks.json
- spec/changes/add-middleware-it-coverage/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-110/spec.md
- work/mailbox/tasks/TASK-110/handoff.md
- work/mailbox/PLAN.md

## 环境核实

- **Redis 实例归属**：IT 连 `127.0.0.1:16379`（容器 `sport-verify-redis` 经 override 映射宿主 16379，容器内 6379）。IT 打印 `INFO server`：`run_id=d6f6ee451462b48ca165082afa519ad3d09cdfdf tcp_port=6379`；与 `docker exec sport-verify-redis redis-cli -p 6379 INFO server` 输出逐字一致（同实例）。本机 6379 另常驻原生 `redis-server.exe`（run_id 与之不同），IT 连的是容器实例，无连错。
- **端口**：6379 原生占用 → override 只暴露 16379；9876/10911/10909/16379 空闲无冲突。
- **scratch 库**：`task108_it`（MySQL 容器内 `sed 's/record_db/task108_it/g'` 灌 `02-record-db.sql`；未碰 `record_db`）。

## 红绿取证

- **Redis 红**（改 scale 期望 2→3）：`org.opentest4j.AssertionFailedError: BigDecimal scale 必须在真 Redis 往返中存活 ==> expected: <3> but was: <2>`，`LeaderboardL2RedisRoundTripIT.java:119`；BUILD FAILURE。还原 → 绿。
- **Redis 绿**：`Tests run: 1, Failures: 0, Errors: 0`，打印 `[TASK110] redis instance run_id=d6f6ee... tcp_port=6379`。
- **RocketMQ 红**（订阅 Tag 只 `SUBMITTED`、发送 `VERIFIED`）：`received.poll` 超时 → `LeaderboardL2RedisRoundTripIT` 绿、`RocketMqBrokerRoundTripIT.java:122` `expected: not <null>` 红、BUILD FAILURE。还原 → 绿。
- **RocketMQ 绿**：`Tests run: 1, Failures: 0, Errors: 0`（耗时约 22s）。
- **缺 env 跳过**（只留 `TASK108_*`，去掉全部 `TASK110_*`）：
  ```
  [INFO] Running ...LeaderboardL2RedisRoundTripIT
  [WARNING] Tests run: 1, ..., Skipped: 1
  [INFO] Running ...LeaderboardDailySummaryMapperMysqlIT
  [INFO] Tests run: 3, ..., Skipped: 0
  [INFO] Running ...RocketMqBrokerRoundTripIT
  [WARNING] Tests run: 1, ..., Skipped: 1
  [WARNING] Tests run: 5, ..., Skipped: 2
  BUILD SUCCESS
  ```
  Redis/RocketMQ 各 `Skipped:1`（assume 跳过，按未覆盖记账）；MySQL 3 绿。

## 实跑结论（规范口径，均经 `mvn-verify.sh`）

- `--mode=offline test`：BUILD SUCCESS，模块合计 17/19/31/78/81/49/6 = **281**（与基线一致，新增 `*IT` 未被常规收集）。
- `--it` 真中间件在位：`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS / IT_END_RC=0。
- `--mode=offline --it` 内部命令：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl leaderboard-service -am -Dtest=LeaderboardDailySummaryMapperMysqlIT,LeaderboardL2RedisRoundTripIT,RocketMqBrokerRoundTripIT -Dsurefire.failIfNoSpecifiedTests=false`。
- **`mailbox-contract.sh --open=TASK-018,TASK-106` 契约自证**：当前未收口工作树下预知退出 1——TASK-110 判据 B 仅余 `scripts/verify/env.example` 一项"改动集未声明"，根源是契约提取正则扩展名白名单不含 `.example`（非告警、属契约盲区，`mailbox-contract.sh` 明确不动不可补）；另历史 TASK-104/109 因与本包共占公共文件（PLAN.md/mvn-verify.sh/env.example）在脏树下必然过冲。对照前一轮复跑：本轮已消除 `docker-compose.override.yml` 与裸 `mvn-verify.sh` 两项误报（`只改清单`节已收紧为 10 行纯路径、附录节标题剔除 `改动`/`只改` 触发器）。契约退出 0 在指导侧收口提交后成立——届时 ACTUAL 空、各任务 `both=0` 即"足迹不在工作树，视为已收口"不重审（判据 B=0）→ 契约 0。

## 台账

- PLAN.md 已按收口清单追加 `真中间件路径的覆盖缺口（TASK-110，事项 2/5）` 验收记录（见下节）。
- `spec/changes/add-middleware-it-coverage/tasks.json` 已回填 `completed:true` / `passes:true`。

## 未决 / 分期

- **C 全栈 `/daily` 端到端：本期未覆盖 + 分期理由**。判据形态设计为独立 `scripts/smoke/smoke-daily.sh`（宿主六服务 8080–8085 模式：登录取 token + 携带身份头请求 `/daily`，断言返回 rule version 等机器信号）。因宿主全栈拉起门槛本环境不具备（mapmatch/PostGIS、record 容器内 sharding 为已知遗留，属另一事项范围），未交付未经红绿自证的脚本；`smoke-evidence.sh` 未改。A/B 两条真中间件判据为可运行机器判据。
- **未达外部门槛**：本修订未推送，上述结论均为本地实跑；外部门槛来源缺省。

## 附录：mvn-verify.sh 的 --it 段 diff（回传逐行核对）

`mvn-verify.sh` 改动逐行：仅 `--it` 段（`run_it=1` 分支）——第 27 行 `IT_CLASSES` 逗号清单（既有 MySQL Mapper IT + 2 新 IT）、第 26/28 行 `IT_MODULE`/`IT_ENV_VARS`（并入 `TASK110_IT_*`）、第 134 行 `-Dtest=$IT_CLASSES`；第 132-137 常规 `--pl`/`--it` 分派与第 143-151 的缺 env 提示均只在 `--it` 生效。常规 `test/verify/package` 路径不读该清单。临时环境文件（`docker-compose.override.yml`、`logs/run-it-*.sh`）已删除，未留工作树。