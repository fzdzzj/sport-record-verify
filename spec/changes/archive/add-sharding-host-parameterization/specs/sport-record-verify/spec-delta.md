# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（sharding.yaml 的 MySQL host 参数化：
`${SHARDING_MYSQL_HOST:127.0.0.1}`，消除「容器内经 env 覆盖不到 host 的遗留缺口」）。

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

## MODIFIED Requirements

### Requirement: 真库端到端测试有确定路径

WHEN 存在需要真实数据库或其他真实中间件的端到端测试时,
系统 SHALL 提供一条被文档指路的定向执行入口，并 SHALL 声明其运行前提（所需环境变量与准备步骤）。
容器口径下 record-service 的 MySQL 连接 SHALL 经 `SHARDING_MYSQL_HOST` 指向 compose 内服务名，
SHALL NOT 依赖宿主回环地址；该覆盖在容器内执行时以服务名寻址成功且能查询 `record_db` 为准。

#### Scenario: 容器内 MySQL 可达

GIVEN record-service 容器与 mysql 容器同处 compose 网络，且注入 SHARDING_MYSQL_HOST=mysql
WHEN 在容器内按服务名连接 MySQL 并查询 record_db
THEN 连接成功且查询退出 0
AND 该判据区别于宿主回环寻址（同网络内以 127.0.0.1 连接失败）

---

## 备注

- 参数化不引入 Testcontainers / Flyway / 新 Maven 插件，仍是 `loadShardingYaml` 存量 `${VAR:default}` 机制。
- 不新增构建产物或环境变量名约定（`SHARDING_MYSQL_HOST` 仅容器编排注入，宿主直跑走默认值）。