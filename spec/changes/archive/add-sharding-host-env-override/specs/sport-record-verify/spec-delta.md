# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（TASK-115 冲突停手项的独立
ADDED 需求单开并入：sharding 数据源 host 可由环境变量覆盖；本变更零 MODIFIED，不改既有需求文本）。

## ADDED Requirements

### Requirement: sharding 数据源 host 可由环境变量覆盖

WHEN record-service 的数据源经 `sharding.yaml` 由 `ShardingDataSourceConfig` 加载时,
系统 SHALL 采用 `${SHARDING_MYSQL_HOST:<默认>}` 形式的 host 占位，使服务容器内能经环境变量把
host 指向 compose 中的 MySQL 服务名；SHALL NOT 把 host 硬编码为宿主回环地址而使容器口径失效。
宿主直跑未设该环境变量时, 系统 SHALL 使用默认值 `127.0.0.1`，SHALL NOT 改动宿主直跑口径。

#### Scenario: 宿主直跑保留默认 host

GIVEN 运行环境未设置 SHARDING_MYSQL_HOST
WHEN record-service 读取 sharding.yaml 的数据源 jdbcUrl
THEN host 解析为默认值 127.0.0.1
AND 与参数化前的宿主直跑口径完全一致

#### Scenario: 容器内经服务名寻址

GIVEN compose 为 record-service 注入 SHARDING_MYSQL_HOST=mysql
WHEN record-service 在容器内读取 sharding.yaml 的数据源 jdbcUrl
THEN host 解析为 mysql（服务名）
AND 容器内能以该服务名连上 MySQL（如 `-h mysql -P 3306` 的 USE/查询成功）

---

## 备注

- 本变更仅含上面一条 ADDED，零 MODIFIED：原 `add-sharding-host-parameterization` 的 MODIFIED
  「真库端到端测试有确定路径」基线已被 `add-middleware-it-coverage`（`4be7929`）先并入覆盖，
  按 TASK-115「冲突即停」整体停手；其独立 ADDED 不受阻挡，故以本变更单开并入，原变更整体
  归档保留历史、其 MODIFIED 不再并入。
- 代码侧已由 TASK-111 落地（`SHARDING_MYSQL_HOST`，commit `75ec1dd`），本变更仅补规范口径，
  不碰代码/脚本/Dockerfile/compose/迁移脚本。
- 参数化不引入 Testcontainers / Flyway / 新 Maven 插件，仍是 `loadShardingYaml` 存量
  `${VAR:default}` 机制。
- 不新增构建产物或环境变量名约定（`SHARDING_MYSQL_HOST` 仅容器编排注入，宿主直跑走默认值）。
