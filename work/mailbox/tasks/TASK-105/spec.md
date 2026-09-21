# TASK-105 sharding.yaml 中间件地址参数化（F20 遗留）

## 目标
record-service 的 sharding.yaml 里 ShardingSphere 数据源地址硬编码 127.0.0.1，容器化部署时无法经环境变量覆盖（docker-compose.services.yml 已为此留了注释声明）。把它参数化为环境变量占位，与其他服务口径一致。

## 范围外
- 不改任何 Java 代码、不改 pom.xml、不改其他 yml
- 不改 docker-compose.yml / docker-compose.services.yml 的结构（如发现需要补 env，只允许在 record-service 的 environment 段补行）

## 先读文件
- record-service/src/main/resources/sharding.yaml（现状，含硬编码地址与口令变量用法）
- record-service/src/main/resources/application.properties（对照 MYSQL_PORT 等既有占位口径）
- record-service/src/main/java/com/sportverify/record/config/ShardingDataSourceConfig.java（确认 sharding.yaml 如何被加载、占位符是否会被 Spring 环境解析）
- docker-compose.services.yml 中 record-service 段（现有注释声明）
- .env（口令变量名：MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD 须一致）

## 只改文件
- record-service/src/main/resources/sharding.yaml
- docker-compose.services.yml（仅 record-service 的 environment 段补必要的地址 env；若不需要则不动）
- record-service/src/test/**（仅当既有 ShardingDataSourceConfigTest 需要同步时）

## 要做的修改
1. sharding.yaml 的 jdbcUrl / host / port / 口令改为 ${VAR:默认} 占位（host 默认 127.0.0.1、port 默认 ${MYSQL_PORT:3306} 同口径、口令变量名与 .env 对齐）。先确认 ShardingSphere 加载路径会经过 Spring 环境占位解析；若不解析，用 ShardingDataSourceConfig 先做环境变量替换再喂给 ShardingSphere——若需要改 Java，写入 handoff「待主 agent 决定」并停下，不得擅自改 Java。
2. docker-compose.services.yml 的 record-service 段补对应 env（如 SHARDING_MYSQL_HOST=mysql），并删除/更新之前的「硬编码限制」注释。
3. 既有测试保持绿。

## 验收命令
1. `mvn -s .mvn-settings.xml -q -pl record-service -am test` 全绿（本机沙箱需先设 $env:JDK_JAVA_OPTIONS='-Djdk.attach.allowAttachSelf=true'）
2. `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` 通过
3. `Select-String sharding.yaml -Pattern '127.0.0.1'` 无命中

## 完成定义
- 验收命令全过；写 work/mailbox/tasks/TASK-105/handoff.md：改动点（文件:行）/ 占位解析路径说明 / 「待主 agent 决定」清单（没有写无）
- 回报 ≤200 字：产出 / 校验结果 / 待决策项

## 约束
- 你是上述文件的唯一写入者；最多 1 次修复重试
- 不准猜测，缺信息写 handoff「待主 agent 决定」
