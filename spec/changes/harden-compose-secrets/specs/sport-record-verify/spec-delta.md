# spec-delta：本地中间件编排口令 .env 化与端口回环绑定（TASK-190 harden-compose-secrets）

## ADDED 需求：docker-compose.yml 中间件栈口令必填注入与回环端口绑定

### 场景

1. docker-compose.yml 全部口令类配置改为 .env 必填插值：MYSQL_ROOT_PASSWORD / POSTGRES_PASSWORD / GRAFANA_ADMIN_PASSWORD 使用 `${VAR:?required: cp scripts/verify/env.example .env}` 形态——未配置或空值时 compose 解析期显式失败（报错自带 cp 指引），无弱默认入库；GRAFANA_ADMIN_USER 保持 `${GRAFANA_ADMIN_USER:-admin}` 默认（用户名非机密）；MySQL healthcheck 口令参数与容器口令同源插值（`-p${MYSQL_ROOT_PASSWORD}`）。端口类变量（MYSQL_PORT / POSTGRES_PORT）保持 `${VAR:-数字}` 默认形态。
2. 十处中间件端口映射全部加 `127.0.0.1` 前缀（nacos 8848/9848、mysql 3306、redis 6379、rocketmq-namesrv 9876、rocketmq-broker 10911/10909、postgis 5432、prometheus 9090、grafana 3000）：同网段主机不可直连中间件；宿主机服务（java -jar，各服务配置默认 127.0.0.1）与 compose 网络内容器互访（服务名直连，不经宿主端口映射）均不受影响；跨机访问走网关 8080（ADR-0007 口径）。
3. 服务侧数据源口令占位符补齐（沿 record sharding.yaml `${MYSQL_PASSWORD:root}` 既有先例）：user / verify / leaderboard application.yml 为 `${MYSQL_ROOT_PASSWORD:root}`、mapmatch 为 `${POSTGRES_PASSWORD:postgres}`——环境变量注入时生效，未注入时回退本地默认（仅当库口令真为该默认值时可用，与权威源 compose 的必填形态是刻意两态）；docker-compose.services.yml 既有 `env_file: [.env]` 机制零改动自动传递。
4. scripts/verify/env.example 占位值升级为强占位模式（`SvLocal-<组件>-<8位hex>-ChangeMe`，执行侧新生成，MYSQL_PASSWORD 与 MYSQL_ROOT_PASSWORD 保持同值）；变量名零增删；CI 既有 env.example→.env 占位机制在 ci.yml 零触碰下维持 compose parse 门。
5. Redis 不设口令、Nacos 不开鉴权为本地回环口径下的刻意取舍（防御主机制 = 端口回环绑定，同网段暴露面整体闭合）；生产环境要求以注释警示登记，实施留后续课题。
6. 口令变量仅数据卷首次初始化生效：存量卷换口令需 down -v 重建或库内改口令（env.example 头部既有说明，README 同步提示）；本课题不执行破坏性卷操作，运行时全栈联调登记 UNDETERMINED。

### 验收断言

- 单测（offline 确定性、纯 JVM 不启上下文，四服务同构各 2 例）：读真实 application.yml，Binder + PropertySourcesPlaceholdersResolver 绑定 DataSourceProperties——注入环境源时口令解析为注入值（回归字面量即红）；仅 yaml 源（隔离宿主机环境）时回退本地默认 root / postgres。
- compose 三判别式：正向（有 .env）`docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` rc=0（CI 同款命令）；插值读数十处端口 127.0.0.1 前缀 + 口令键插值成功（值脱敏留证，禁明文）；负向（无 .env 环境）`docker compose -f docker-compose.yml config -q` 失败且报错含必填变量名。
- 基线：Java offline 550 只增不减（+8 预计落 user / verify / leaderboard / mapmatch）；`--static=record-service` 811 不增；零 Java main 源码改动（git diff 核验仅 compose + env 样例 + 配置 + README + 测试）。
- 零触碰：ci.yml / docker-compose.services.yml / docker-compose.perf.yml / mvn-verify.sh / mailbox-contract.sh / gateway-service / web / sql/ / 父 pom / record-service / 仓库根 .env。
- 台账与 handoff 不含口令明文、不枚举受保护 token 字面量、不写安全收益数字。
