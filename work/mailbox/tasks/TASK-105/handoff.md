# TASK-105 handoff

## 状态
完成（含文档同步；两项待决定已由用户拍板：host 默认 localhost 保留、MYSQL_PORT:3306 覆盖行保留）

## 做了什么
- 确认占位解析路径：ShardingSphere 5.4.1 的 YAML 引擎不解析 `${}`，但既有 `ShardingDataSourceConfig.loadShardingYaml()`（record-service/src/main/java/com/sportverify/record/config/ShardingDataSourceConfig.java:55-78）已在读取阶段手动替换 `${VAR:default}` / `${VAR}`，故无需改 Java。
- sharding.yaml:26 jdbcUrl 的 host 由硬编码改为 `${SHARDING_MYSQL_HOST:localhost}`，port/口令沿用既有 `${MYSQL_PORT:3306}` / `${MYSQL_PASSWORD:root}` 口径，并更新注释。
- docker-compose.services.yml：record-service environment 段补 `SHARDING_MYSQL_HOST: mysql` 与 `MYSQL_PORT: 3306`（env_file 会把 .env 的宿主机映射端口 MYSQL_PORT=3307 注入容器，须显式覆盖为容器端口，否则连不上），并删除文件头与段内两处「地址硬编码 127.0.0.1 / 暂时连不上」的已知限制注释。
- 文档同步（用户追加要求）：README.md 快速开始的环境变量说明段补记 record-service 分片数据源的参数化口径（SHARDING_MYSQL_HOST/MYSQL_PORT/MYSQL_PASSWORD 与容器化编排注入值），此前 README 完全未提 docker-compose.services.yml。

## 改动文件
- record-service/src/main/resources/sharding.yaml（jdbcUrl 一行 + 注释，sharding.yaml:26）
- docker-compose.services.yml（头注释 10-12 行；record-service environment 段 86-90 行，仅补行）
- README.md（快速开始 blockquote 补 4 行，README.md:91-94；纯文档）
- 测试未改：ShardingDataSourceConfigTest 用 src/test/resources/sharding.yaml（哨兵变量自足），无需同步

## 验收
- `mvn -s .mvn-settings.xml -q -pl record-service -am test`（JDK_JAVA_OPTIONS 已设 allowAttachSelf）：EXIT=0，80 个用例全绿（含 ShardingDataSourceConfigTest 3 个）
- `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q`：EXIT=0（文档改动后复跑仍过）
- `Select-String sharding.yaml -Pattern '127.0.0.1'`：main/test 两份 sharding.yaml 均无命中
- 完整输出：work/mailbox/tasks/TASK-105/verify.log
- README 为纯文档改动，不影响上述验收结果

## 未完成
- 无（容器内实际连通性未做运行时冒烟，仅静态验证 compose 配置合法）

## 待主 agent 决定
- 无（原两项已由用户拍板关闭：1 接受 localhost 默认；2 保留 MYSQL_PORT:3306 覆盖行）

## 风险
1. `localhost` 默认在仅绑定 127.0.0.1 的 MySQL 上可能解析到 ::1 导致连接失败（MySQL 8 默认监听全部接口，本工程 compose/常规安装不受影响）。
