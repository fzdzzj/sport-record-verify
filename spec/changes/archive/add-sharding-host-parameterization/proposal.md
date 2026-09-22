# 提案：sharding.yaml 的 MySQL host 参数化

## Why

`record-service` 数据源走 `sharding.yaml`（`ShardingDataSourceConfig` 经 `YamlShardingSphereDataSourceFactory`
加载），其中 `jdbcUrl` 的 host 硬编码 `127.0.0.1`。占位符机制已在 `loadShardingYaml()` 就位
（`${VAR:default}`，现用于 `MYSQL_PASSWORD` / `MYSQL_PORT` / `MYSQL_POOL_SIZE`），唯独 host 未接入，
导致**服务容器内连不上 MySQL**（TASK-104 handoff「未解决项」第 1 条遗留原文：record-service 数据源走
sharding.yaml……env 覆盖不到，容器内仍连不上 MySQL——需后续参数化 sharding.yaml）。

`docker-compose.services.yml` 的 record-service 段已预注入 `SHARDING_MYSQL_HOST: mysql` 与
`MYSQL_PORT: 3306`，其头部注释也声称「地址已参数化」——本提案即把注释声称的实现补齐，消除「容器口径
注释与实现不一致」。

**期望状态**：`sharding.yaml` 的 host 用 `${SHARDING_MYSQL_HOST:127.0.0.1}` 占位；宿主直跑（未设 env）
仍走默认 `127.0.0.1`（口径零变化）；容器内注入 `SHARDING_MYSQL_HOST: mysql` 实现服务名寻址。

## What Changes

- `record-service/src/main/resources/sharding.yaml` 的 `jdbcUrl` 行：
  `jdbc:mysql://127.0.0.1:${MYSQL_PORT:3306}/record_db` → `jdbc:mysql://${SHARDING_MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/record_db`。
  默认值保持 `127.0.0.1`。
- 单测加固：
  - `record-service/src/test/resources/sharding.yaml` 增一行真变量名哨兵 `sharding-host: ${SHARDING_MYSQL_HOST:127.0.0.1}`；
  - `ShardingDataSourceConfigTest` 新增 2 用例（%%的哨兵在 test classpath 覆盖同名资源，
    `loadShardingYaml` 替换后非残留占位、结果与当前 env 或默认值一致、默认分支 127.0.0.1 存活/Override 生效）。
    注：Mockito 禁止 `mockStatic(System.class)`（类加载无限循环），故红绿取证改为**运行级注入
    `SHARDING_MYSQL_HOST` 环境变量 + 临时改断言**复现（见「红线复演」），不依赖静态 mock。
- 台账：`spec/changes/add-sharding-host-parameterization/{proposal.md,tasks.json,specs/sport-record-verify/spec-delta.md}`、
  `work/mailbox/tasks/TASK-111/{spec.md,handoff.md}`、`work/mailbox/PLAN.md` 追加验收记录。

**明确不做**：

- 不改 `docker-compose.services.yml`（已含所需注入）、`ShardingDataSourceConfig.java`（机制已够用）、
  `env.example`（该变量只在容器编排注入，宿主直跑走默认值）、任何 pom。
- 不引入新插件/依赖；不 push、不建 PR、不改 CI。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — MODIFIED「真库端到端测试有确定路径」：
  「容器口径 MySQL 可达」从「env 覆盖不到」的遗留缺口改为「容器内以服务名寻址成功」，并 ADDED
  宿主直跑默认 `127.0.0.1` 口径零变化的分句。

### 受影响的代码
- `record-service/src/main/resources/sharding.yaml`（1 行 jdbcUrl）
- `record-service/src/test/resources/sharding.yaml`（1 行哨兵）
- `record-service/src/test/java/.../config/ShardingDataSourceConfigTest.java`（+2 用例）

### 用户影响
- 宿主直跑无需额外配置（默认 127.0.0.1 不变）；record-service 容器化部署现可在容器内经
  `SHARDING_MYSQL_HOST: mysql` 服务名连上 MySQL。

### API 变更 / 需要迁移
- 无。

## 时间线评估
- 核心改动主 yaml 一行 + 单测加固 + 台账，约半天（含红绿取证与容器口径实测）。

## 风险与缓解

- **默认值漂移**（误把默认值改成别的主机口径）→ 缓解：哨兵测试断言未设 env 时保留 `127.0.0.1`。
- **容器寻址无误判** → 缓解：容器内一次性 mysql 客户端同网络红绿对（`-h 127.0.0.1` 红 / `-h mysql` 绿）。
- **静态 mock System 不可用** → 缓解：红绿取证用运行级 env 注入 + 临时断言编辑复现，不写依赖
  `mockStatic(System.class)` 的用例（Mockito 明确禁止）。

## 备注

- 红线复演两对：单测（注入 `SHARDING_MYSQL_HOST=mysql` + 临时期望 127.0.0.1 → 红；还原绿）、
  容器（同网络 `-h 127.0.0.1` 红 / `-h mysql` 绿）。红绿两条证据都进回传。
- 不 push、不建 PR；未达外部门槛时在台账显式标注。