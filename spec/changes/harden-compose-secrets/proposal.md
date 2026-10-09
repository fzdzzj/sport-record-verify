# TASK-190 harden-compose-secrets 提案：中间件编排口令 .env 化与全端口回环绑定

## Why（现状与痛点）

findings F02（P0 安全）：docker-compose.yml 中间件栈弱口令硬编码 + 全端口绑定宿主机全接口——`MYSQL_ROOT_PASSWORD: root`（L43）、healthcheck `-proot`（L56）、`POSTGRES_PASSWORD: postgres`（L136）、Grafana admin/admin（L181-182）、Redis 无口令、Nacos 关鉴权（L23）；3306/6379/8848/9848/9876/10911/10909/5432/9090/3000 十处端口全部发布到宿主机所有网卡——同网段任意主机可直连数据库 / 缓存 / 注册中心 / 监控面。

现状实证（2026-10-09，指导侧亲核）：

- **.env 变量对中间件栈完全惰性**：`scripts/verify/env.example` 已定义 MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD / POSTGRES_PASSWORD / GRAFANA_ADMIN_USER / GRAFANA_ADMIN_PASSWORD，但 docker-compose.yml 无任何 `${}` 引用——口令键是字面量，.env 口令值被静默忽略（F02 成立的机制根源：不是没有 .env 机制，是 compose 从未消费它）
- **服务侧半参数化**：record sharding.yaml L34 已有 `${MYSQL_PASSWORD:root}` 占位（全仓唯一先例）；user（application.yml L23）/ verify（L46）/ leaderboard（L53）数据源口令为字面量 root、mapmatch（L36）为字面量 postgres——docker-compose.services.yml L59 注释声称的「yml `${MYSQL_ROOT_PASSWORD:root}` 占位」与事实不符（本课题补齐后该注释为真，该文件本身零改动）
- **本地 .env 已按目标态预置**（未入库文件，指导侧亲读）：强口令占位（SvLocal-* 模式）+ Redis 不设口令注释（口径：回环绑定后仅本机可达）+ 端口 3307/5433——本课题目标即把 tracked 侧对齐该口径
- **CI 机制核验**：ci.yml L42-48 将 env.example 复制为 .env 后跑 compose config 解析门——全部必填插值变量均已在 env.example 内，**ci.yml 零触碰可维持门绿**（memory 教训「无 .env 时 compose config 失败」已由该占位机制覆盖）
- **perf overlay 核验**：docker-compose.perf.yml 仅覆盖 mysql command（刷盘参数），无口令 / 端口重声明——零触碰安全

## What（方案）

**口令必填插值（compose 侧）+ 占位符补齐（服务侧）+ 十端口 127.0.0.1 回环绑定 + 头部警示 + 绑定判别式单测**，零 Java main 源码改动。

| 决策点 | 裁决 | 理由 |
| --- | --- | --- |
| Redis / Nacos 鉴权 | **不并入，留后续课题** | 防御主机制 = 端口回环绑定（同网段暴露面整体闭合）；Redis requirepass 涟漪 5 个 Redis 消费服务配置 + healthcheck + IT 口径，Nacos 鉴权涟漪全部 6 服务 nacos 配置 + token 管理——纵深防御另立课题；本地 .env 已按此口径预置（Redis 不设口令有明示注释） |
| compose 口令插值形态 | `${VAR:?}` 必填，**无弱默认入库** | 弱默认回退即 findings 原样延续；必填形态使「未配 .env」在 compose 解析期显式失败（报错自带 cp 指引）而非静默弱口令；端口类变量（MYSQL_PORT/POSTGRES_PORT）保持 `${VAR:-数字}` 默认（非敏感） |
| 服务侧 yml 占位符 | 带 `:root` / `:postgres` 本地回退默认 | 沿 record sharding.yaml `${MYSQL_PASSWORD:root}` 既有先例保持全仓一致；两形态并存是刻意裁决——compose 是口令权威源（必填），yml 是客户端回退（仅当库口令真为该默认值时可用），未导出环境变量的进程会拿到明确认证失败而非启动失败 |
| env.example 占位值 | 升级为 `SvLocal-<组件>-<8位hex>-ChangeMe` 模式（执行侧新生成，不复用任何本机 .env 实值） | 弱值 root/postgres/admin 留在入库样例 = 「默认弱口令」事实延续；-ChangeMe 后缀强制使用方有意识替换；MYSQL_PASSWORD 与 MYSQL_ROOT_PASSWORD 保持同值（record 分片数据源变量名约束） |
| 端口绑定 | 十处全前缀 `127.0.0.1` | 宿主机 java -jar 服务经回环直连不受影响（各服务配置默认 127.0.0.1）；compose 网络内容器互访走服务名不经宿主端口映射不受影响（services compose 头部既有口径）；跨机访问走网关 8080（ADR-0007 既有口径） |
| Grafana 账号 | USER 保持 `:-admin` 默认，PASSWORD 按口令类 `:?` 必填 | 用户名非机密可留默认；口令按口令类治理 |
| MySQL healthcheck | `-proot` 改 `-p${MYSQL_ROOT_PASSWORD}`（主机侧插值） | healthcheck 与容器口令同源；否则改 .env 后 healthcheck 永败 |

**改动面（11 文件，零 main Java 代码）**：

| 层 | 改动 |
| --- | --- |
| compose | docker-compose.yml：三处口令必填插值（MySQL/PostGIS/Grafana）+ healthcheck 口令插值 + 十端口回环前缀 + 头部警示 |
| env 样例 | scripts/verify/env.example：占位值升级 + 回环/Redis 口径注释（变量名零增删） |
| 服务配置 | user / verify / leaderboard application.yml 口令占位符（MySQL）；mapmatch application.yml 口令占位符（PostGIS） |
| 测试 | 四服务各 +1 绑定判别式测试类（各 2 例） |
| 文档 | README 快速开始：第 0 步 cp .env 引导 + 服务启动口令导出提示 |

**单测（四服务同构，预计 +8，offline 确定性、纯 JVM 不启上下文）**：

1. **注入解析**：读真实 application.yml，Binder + PropertySourcesPlaceholdersResolver 注入 MapPropertySource（口令变量=测试值）→ DataSourceProperties.getPassword() 为注入值——守护「占位符回归字面量」（字面量不解析环境变量，即红）
2. **回退默认**：仅装 yaml 源（隔离宿主机环境）→ getPassword() 为 root / postgres——守护回退默认语义

## 边界（明确不做）

- 零触碰：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose.services.yml / docker-compose.perf.yml / gateway-service / web / sql/ / 父 pom / record-service（sharding.yaml 已参数化无需再动）/ 仓库根 .env（使用方本地文件，已按目标态预置，禁改禁抄值）
- Redis requirepass / Nacos 鉴权 / RocketMQ ACL 不做（留后续课题，见决策表）
- 不执行破坏性卷操作（down -v / ALTER USER 属使用方迁移操作，只在文档登记指引）；口令变量仅数据卷首次初始化生效（env.example 头部既有说明）
- 口令值不入 tracked 文件与台账 / handoff（env.example 占位样例除外）；compose 插值留证一律脱敏
- **不 claim 任何安全收益数字**（无量化评估依据，只登记机制性事实）

## 风险

| 风险 | 缓解 |
| --- | --- |
| 存量数据卷口令不迁移：.env 强口令 vs 卷内旧口令 → 容器 healthy 失败（healthcheck 用新口令） | env.example 头部已注明 down -v 重建或 ALTER USER；README 补提示；属使用方操作不属门禁，运行时联调登记 UNDETERMINED |
| 无 .env 时 compose 命令一律报错（含 ps/down） | README 第 0 步 cp 引导；`:?` 报错信息自带 cp 指引文案；CI 已有占位 .env 机制 |
| compose 插值写错形态（`:-` 弱回退 / `$$` 转义错位 / healthcheck 未同源插值） | compose 三判别式留证：正向 config -q（CI 同款）+ 插值读数（十端口回环 + 口令已插值）+ 负向（无 .env 必失败且报错含变量名） |
| Binder 构造误用（直接 new Binder 不带 PlaceholderResolver → 占位符按字面量绑定出假绿） | 任务书 §2.4 预注册正确构造（PropertySourcesPlaceholdersResolver）与隔离要点（用例 2 不含系统环境源） |
| healthcheck 口令经 docker inspect 可见 | 本地开发可接受（environment 明文同理），登记说明项 |
| 端口回环后跨机访问中间件不可达 | by design（F02 修复目标）；跨机访问走网关 8080（既有口径） |

## 验收（摘要）

四服务占位符绑定单测全绿（+8，落 user / verify / leaderboard / mapmatch 各 2 例，以实测逐位登记）；Java offline 全量 550 只增不减（36/41/117/137/147/62/10 基线）；`--static=record-service` 811 不增（checkstyle 不扫 test 源码，TASK-188/189 实证）；compose 三判别式全过（正向 rc=0 / 插值读数十端口回环 + 口令脱敏留证 / 负向无 .env 必失败含变量名）；零 main 代码核验（git diff 仅 compose + 配置 + README + 测试）；契约门在途 rc=0；词面门 ZERO_HIT；token 29 项只增不减（新文档不枚举 token 字面量）；CI 第 32 次外部门槛绿（compose parse 门在 ci.yml 零触碰下维持）；运行时全栈联调（存量卷迁移后 up -d 全 healthy + 服务连通）登记 UNDETERMINED（沿 182/185/186/187/188/189 口径不判失败）。
