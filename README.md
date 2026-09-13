# 运动记录真实性校验系统（sport-verify）

基于审批版需求（[《运动记录校验系统需求文档（审批版）》](docs/运动记录校验系统需求文档（审批版）.md)）的微服务工程基线。
本仓库由 openspec 规范驱动，首个变更 `spec/changes/add-microservice-skeleton/` 只交付**可编译、可一键启动的空壳骨架**，
校验引擎、好友、点赞、排行榜等业务需求在后续变更中逐周叠加。

## 架构总览

```
                        ┌──────────────────────────────────────────────┐
                        │             gateway-service:8080             │  Spring Cloud Gateway + Sentinel 网关限流(5k QPS 基线)
                        └──┬────────┬─────────┬────────────┬─────────┬─┘
             /user/**      │ /record/**  │ /verify/**   │ /leaderboard/**   │ /mapmatch/**
        ┌──────────────┐ ┌───────────────┐ ┌──────────────┐ ┌────────────────────┐ ┌──────────────────┐
        │ user-service │ │ record-service│ │verify-service│ │leaderboard-service │ │ mapmatch-service │
        │    :8081     │ │     :8082     │ │    :8083     │ │       :8084        │ │      :8085       │
        └──────┬───────┘ └───────┬───────┘ └──────┬───────┘ └─────────┬──────────┘ └────────┬─────────┘
       user_db (MySQL)    record_db(MySQL)  verify_db(MySQL)   record_db 复用         road_db (PostGIS)
       Redisson(Redis)    ShardingSphere    RocketMQ / Caffeine 只读快照+贡献表自写       GIST 空间索引
                           RocketMQ 事件发布                    Redis ZSet/RocketMQ 消费     OSM 路网+最近边投影
                                                              (事件驱动入榜/回滚+定时结算)   (匹配接口 /match)
        Feign 契约：verify→record 拉轨迹 ｜ verify→mapmatch R5 路网匹配(熔断降级) ｜ record→verify 拉结果 ｜ leaderboard→user 补昵称
        Nacos 2.3.2（注册中心 + 配置中心）｜ Redis 7 ｜ RocketMQ 5 ｜ PostGIS 16（OSM 路网空间库）
```

**模块结构**（父工程 `sport-verify-parent` 集中锁定版本矩阵，子模块一律不写版本号）：

| 模块 | 职责 |
| --- | --- |
| `common` | 统一 `Result<T>`、错误码枚举、`BizException`、全局异常处理器 |
| `api` | 服务间 Feign 契约（record-api / verify-api / user-api / leaderboard-api / mapmatch-api / auth-api），接口与实现分离 |
| `gateway-service` | 统一入口，路由 `/api/auth/**` `/user/**` `/record/**` `/verify/**` `/leaderboard/**` `/mapmatch/**` 到对应服务；鉴权 GlobalFilter（校验 Bearer → 注入 X-User-Id 透传下游，见 ADR-0007） |
| `user-service` | 好友申请/同意/拒绝/列表（user_db 三表 + Redisson 锁防并发互加）+ **认证域**（注册/登录/刷新，BCrypt + HS256 双 token，refresh 轮换存 Redis，见 ADR-0007） |
| `record-service` | 记录提交/查询/申诉 + 点赞/取消/计数（record_db 分片存储 + Redis 计数；榜单职责已拆出，见 ADR-0005） |
| `verify-service` | 校验引擎/申诉（判定/终判 + 规则灰度发布 + verify_db + RocketMQ 事件发布）；R5 离路规则远程调 mapmatch 做空间真实性判定（见 ADR-0006） |
| `leaderboard-service` | 榜单读热 + 事件沉淀：总榜/好友榜（Redis ZSet 秒级）+ VERIFIED/REJECTED 消费入榜/回滚 + 定时快照结算防重（复用 record_db 贡献表，见 ADR-0005） |
| `mapmatch-service` | 道路拓扑匹配（服务数 5→6，见 ADR-0006）：真实 OSM 路网预载 PostGIS + 最近边投影匹配算法，`POST /match` 返回偏离路网指标 |

## 快速开始

前置条件：JDK 21、Maven 3.9+、Docker（Compose v2）。

```bash
# 1. 一键拉起中间件（Nacos / MySQL×3库 / Redis / RocketMQ / PostGIS 路网库，含健康检查与依赖顺序）
docker compose up -d

# 2. 导入 OSM 路网（首次执行：Overpass 下载上海切片 → PostGIS 建表 + GIST 索引，幂等可重跑；
#    PATH 上是 JDK8 时需指定 JAVA_BIN，如 JAVA_BIN="D:\develop1\jdk21\bin\java"）
bash scripts/mapmatch/import-road-network.sh

# 3. 全量编译打包（父工程 + 8 个子模块）
mvn clean install

# 4. 启动六个服务（各开一个终端）
java -jar gateway-service/target/sport-verify-gateway-service-0.1.0-SNAPSHOT.jar
java -jar user-service/target/sport-verify-user-service-0.1.0-SNAPSHOT.jar
java -jar record-service/target/sport-verify-record-service-0.1.0-SNAPSHOT.jar
java -jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar
java -jar leaderboard-service/target/sport-verify-leaderboard-service-0.1.0-SNAPSHOT.jar
java -jar mapmatch-service/target/sport-verify-mapmatch-service-0.1.0-SNAPSHOT.jar
```

> `java` 必须是 JDK 21（PATH 上是 JDK 8 时会报 UnsupportedClassVersionError，改用绝对路径如
> `D:\develop1\jdk21\bin\java`）；宿主机 3306 被本机 MySQL 占用时，服务启动同样注入 `MYSQL_PORT=3307`；
> 宿主机 5432 被本机 PostgreSQL 占用时注入 `POSTGRES_PORT=5433`（仓库根 `.env` 已按此口径配置）。

## 冒烟验证

| 验证项 | 命令 | 期望 |
| --- | --- | --- |
| 中间件健康 | `docker compose ps` | 6 个容器 `healthy`（含 postgis） |
| 注册可见 | 浏览器打开 `http://127.0.0.1:8848/nacos` 服务列表 | 6 个服务各 1 实例 |
| 网关路由 | `curl http://127.0.0.1:8080/user/internal/health` | `{"code":0,"message":"success","data":"user-service is alive"}` |
| Feign 探活 | `curl http://127.0.0.1:8080/verify/internal/probe/record` | `"record-service is alive"`（verify→record 跨服务调用） |
| 榜单查询 | `curl http://127.0.0.1:8080/leaderboard/api/leaderboard?type=overall` | `{"code":0,...,"data":[...]}`（总榜；`type=friend&userId=` 查好友榜） |
| 路网匹配 | `curl -X POST http://127.0.0.1:8080/mapmatch/match -H 'Content-Type: application/json' -d '{"points":[{"seq":0,"lat":31.23,"lng":121.4626,"ts":1700000000000},{"seq":1,"lat":31.2293,"lng":121.4627,"ts":1700000165000},{"seq":2,"lat":31.2287,"lng":121.4628,"ts":1700000428000}]}'` | `data.offRoadRatio≈0`（点位在南北高架路上，悬浮点位如黄浦江江心则 ≈1） |

> 宿主机 3306 被本机 MySQL 占用时：仓库根目录建 `.env` 写入 `MYSQL_PORT=3307`（compose 与四个服务
> 的数据源端口均已参数化，默认仍 3306），服务侧同名变量见 `scripts/perf/run-perf.sh`。

## JWT 鉴权闭环（注册/登录 + 网关统一鉴权 + 数据隔离，选型理由见 [ADR-0007](docs/adr/0007-鉴权设计.md)）

**从「调用方自报家门」升级为「网关统一认定身份」**：全项目 6+ 处「骨架无认证、userId 显式携带」
的技术债一次性收口。设计要点：

- **签发端**（user-service）：`POST /api/auth/register`（BCrypt 哈希 + 手机号唯一 → 2001）、
  `POST /api/auth/login`（错密码 → 401 + 计失败）、`POST /api/auth/refresh`（refresh 换新 + 轮换作废旧 refresh）。
- **校验端**（gateway）：GlobalFilter 校验 `Authorization: Bearer`（失败 401/1001），解析 userId 注入
  `X-User-Id` 头透传下游（覆盖外部伪造同名头）；白名单 `/api/auth/**` `/internal/**` `/actuator/**` `/admin/**` 放行。
- **数据隔离**（record/user/leaderboard）：业务 controller 的 userId 已改从 `X-User-Id` 读取，
  显式携带值不一致 → 403（1002 越权）。
- **refresh 轮换**：refresh 存 Redis（`auth:refresh:{userId}:{jti}`，TTL=7d），刷新时 GETDEL 原子作废旧 jti → 防重放。
- **降级开关**：`app.auth.enabled`（默认 false）= 旧行为（显式携带 userId，压测脚本不破坏）；
  切 true 强制鉴权 + 数据隔离（需同步更新压测脚本带 token）。
- **登录失败锁定**（add-login-lockout）：窗口（15min）内连续登录失败达阈值（5）→ 写 `auth:lock:{phone}`
  （Redis，TTL=锁定时长），登录入口前置检查直接拒绝（403/1002「账号已临时锁定」，不校验密码、防撞库 + 省 BCrypt）；
  到期自动解锁、登录成功清零计数与锁定；`app.auth.lock.*` 可配，`enabled=false` 回退仅计数告警，Redis 故障降级仅告警不阻断登录（见 ADR-0007）。

冒烟验证（默认开关关闭时）：先登录拿 token，再带 token 调业务接口。

```bash
# 注册 → 登录拿双 token
curl -X POST http://127.0.0.1:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"phone":"13800000001","password":"pass123","nickname":"demo"}'
curl -X POST http://127.0.0.1:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"phone":"13800000001","password":"pass123"}'
# → {"code":0,"data":{"accessToken":"...","refreshToken":"...","tokenType":"Bearer","expiresIn":900}}
# 带 token 调业务接口（auth.enabled=true 时强制；false 时旧行为仍可显式携带 userId）
curl http://127.0.0.1:8080/user/api/friends?page=1\&size=20 \
  -H "Authorization: Bearer <accessToken>"
# 无 token → 401（1001）；A 的 token 带 B 的 userId 参数 → 403（1002）
```

## 监控与告警（Prometheus + Grafana，选型理由见 [ADR-0003](docs/adr/0003-监控选型.md)）

`docker compose up -d` 已包含监控栈（prometheus:9090 / grafana:3000），无需额外命令。各服务经
`/actuator/prometheus` 暴露 Micrometer 指标（JVM / HTTP / 连接池），Prometheus 抓取宿主机
`host.docker.internal:8080-8085`（服务以宿主机进程运行；容器化后改 target 为服务名即可，见 prometheus.yml 注释）。

| 访问入口 | 地址 | 说明 |
| --- | --- | --- |
| Prometheus 控制台 | http://127.0.0.1:9090 | Status → Targets 看抓取状态；Alerts 看告警 firing/pending |
| Grafana 面板 | http://127.0.0.1:3000 | 账号 `admin/admin`，打开预置面板「运动记录校验系统 · 全局监控总览」 |
| 指标端点（示例） | `curl http://127.0.0.1:8081/actuator/prometheus` | 任意服务均可，含 `jvm_` / `http_server_requests_` 前缀指标 |

预置面板 8 块：服务可用性（up）、HTTP QPS、P95 延迟（200ms 红线，对齐审批版 §8.2）、5xx 错误率（5% 红线）、
JVM 堆已用/上限、GC 暂停速率、HikariCP 连接池、JVM 线程数；数据源与面板均 provisioning 预置，零手工配置。

告警规则（`prometheus/alert-rules.yml`，firing 目前在 Prometheus 控制台可见，通知渠道 Alertmanager 属后续项）：

| 告警 | 条件 | 级别 |
| --- | --- | --- |
| ServiceInstanceDown | `up == 0` 连续 1 分钟（停任一服务即可演示 firing） | critical |
| HttpErrorRateHigh | 5xx 占比 >5% 连续 2 分钟 | warning |
| HttpP95LatencyHigh | P95 >200ms 连续 2 分钟（§8.2 延迟预算） | warning |
| JvmHeapUsageHigh | 堆使用率 >80% 连续 5 分钟 | warning |

## 规则灰度发布（版本快照 + 采样路由 + 秒级回滚，选型理由见 [ADR-0004](docs/adr/0004-规则灰度发布.md)）

新规则不再「一改全体生效」：管理员把候选阈值创建为版本（`rules_json` 快照落库），按
`userId%100 < gray_ratio` 采样小流量验证；异常置 0 秒级回滚（本实例即时失效 + Redis 广播扇出，
上界 60s TTL 收敛）；稳定后全量发布，旧版本自动 RETIRED。灰度期间执行库内快照而非 Nacos 实时配置，
消除观察期配置漂移；同一用户采样键恒定，分支不抖动。

| 操作 | 接口（经网关，管理端暂无鉴权） | 说明 |
| --- | --- | --- |
| 创建版本 | `POST /verify/rules/versions` | body 可带 `rules`（新阈值快照）与初始 `grayRatio`；缺省快照当前基线 |
| 调灰度比例 | `PATCH /verify/rules/versions/{id}/gray` | 0-100；**0 = 秒级回滚** |
| 全量发布 | `POST /verify/rules/versions/{id}/activate` | gray=100 + ACTIVE，旧版本全部 RETIRED |

冒烟实录（同一 5.8 m/s 轨迹，基线阈值 5.5 / 灰度快照 6.6，走 提交→MQ→校验 真实链路）：灰度 10% 时
userId=105（%100=5）PASSED、userId=150 REJECTED；回滚置 0 后 105 立即 REJECTED；全量后 150 也 PASSED。
完整实录见 [交付说明](spec/changes/add-rule-grayscale/交付说明.md)。

## 榜单独立服务（服务数 4→5，选型理由见 [ADR-0005](docs/adr/0005-服务划分.md)）

榜单读热与事件沉淀拆为独立 `leaderboard-service`（8084）：record-service 保留记录分片 + 点赞，
榜单查询经网关 `/leaderboard/api/leaderboard?type=overall|friend`；VERIFIED/REJECTED 事件由
独立消费组 `leaderboard-consumer-group` 在 leaderboard-service 消费入榜/回滚（record 侧消费者已下线，
无双写）；贡献表 `leaderboard_contribution` 复用 record_db（物理隔离拆库为可选项）；
对 `sport_record` 仅只读快照查询（Mapper 结构上无写方法）。

## 道路拓扑匹配（服务数 5→6，选型理由见 [ADR-0006](docs/adr/0006-空间匹配.md)）

校验引擎从「规则链」升级为「规则链 + 空间真实性」双保险：R1-R4 检测轨迹自身反常（匀速/加速度/
停留/距离），识别不了「轨迹不在任何真实道路上」的悬浮伪造。R5 离路规则（verify-service 首个
远程规则）经 Feign 调独立 `mapmatch-service`（8085）把轨迹吸附到真实 OSM 路网，以离路比例
`offRoadRatio` 与阈值比较：>0.5 记 SOFT、>0.8 升级 HARD（整段悬浮海面/楼顶，直接拒绝）。

- 路网：真实 OSM 单城市切片（Overpass 下载上海人民广场周边，与压测样本同基准），PostGIS
  `road_edge` 道路边表 + GIST 空间索引；导入脚本幂等可重跑（`scripts/mapmatch/import-road-network.sh`）。
- 匹配：最近边投影两段式——`ST_DWithin`+GIST 索引预筛候选边，Java 局部投影逐线段算垂距；
  采样上限 200 点防超时；HMM 全局匹配为进阶项。
- 降级：mapmatch 不可用时 Resilience4j 熔断 → R5 降级「不命中」记 warn，校验主链路不挂（对照 T11）。
- 冒烟实录：沿南北高架轨迹 PASSED（hits 空）；黄浦江江面悬浮轨迹 REJECTED
  （`R5_OFFROAD/HARD off-road ratio 1.00, avg 143m, max 161m`，score 70）；停 mapmatch 后提交
  校验正常完成（R5 降级不命中）。

## 压测结果摘要（完整数据与因果见 [docs/perf/压测报告.md](docs/perf/压测报告.md) / [ADR-0002](docs/adr/0002-压测与优化实录.md)）

本地单机实测（Ultra 7 255HX / 15.4GB / Docker Desktop；环境快照与口径见报告 §2）：

| 验收项 | 目标 | 实测 |
| --- | --- | --- |
| 伪造拦截率 | ≥90% | **100%**（200 条正负样本集，五类伪造模式全拦截） |
| 真实通过率 | ≥95% | **100%** |
| 校验链路端到端 P50 | <200ms（P95 口径） | **20.3ms**（突发 200 条时消费调度尾部 ~0.5s，成因与改进见报告 §4.2） |
| 提交吞吐 @100 并发 | — | **35.5 → 136.8 QPS（3.9×）**，P95 3.76s→1.60s |
| 错误率 @500 并发 | — | 6.85% → **0%** |
| Sentinel 网关限流 | 5k QPS 配置基线 | 100 QPS 档实测 **99.35% 超限 429 拦截**（网关侧拒绝 P50 2.2ms） |
| 熔断降级转人工 | verify 挂→转人工，主链路不挂 | 故障期 30/30 提交成功；熔断器 OPEN 实证；恢复后 30/30 自愈收敛 |

优化动作（前后对比与因果）：轨迹逐条 INSERT→单分片批量 INSERT（开关可回退）+ 连接池 10→30；
组合索引 `idx_record_seq` 消除轨迹查询 filesort（EXPLAIN 实测 3.71→1.85ms，Sort 节点消失）。

```bash
# 一键复现（生成样本→基线→优化→复测→限流/熔断验证，详见压测报告 §9）
bash scripts/perf/run-perf.sh gen
bash scripts/perf/run-perf.sh start-services
bash scripts/perf/run-perf.sh quality base && bash scripts/perf/run-perf.sh load 100 2000 base
```

## 关键决策（详见 [ADR-0001](docs/adr/0001-版本矩阵与技术选型.md) / [ADR-0002 压测与优化实录](docs/adr/0002-压测与优化实录.md) / [ADR-0003 监控选型](docs/adr/0003-监控选型.md) / [ADR-0004 规则灰度发布](docs/adr/0004-规则灰度发布.md) / [ADR-0005 服务划分](docs/adr/0005-服务划分.md) / [ADR-0006 空间匹配](docs/adr/0006-空间匹配.md)）

- 版本矩阵锁定：**Java 21 + Boot 3.2.4 + Cloud 2023.0.1 + SCA 2023.0.1.0**（不升 Boot 3.3，SCA 2023 分支不兼容）。
- Nacos 2.3.2 同时承担注册中心与配置中心（`spring.config.import: optional:nacos:*`，Nacos 不可用不阻塞启动）。
- MySQL 单实例三库（user_db/record_db/verify_db）逻辑隔离；ShardingSphere 按 user_id%16 分片 track_point。
- RocketMQ 5.2 + `rocketmq-spring-boot-starter 2.3.1` 独立集成；broker 配 `brokerIP1=127.0.0.1` 使宿主机服务可直连。
- 服务间熔断 Resilience4j（Feign 降级转人工）、入口限流 Sentinel（网关 5k QPS 基线）——限流管流量、熔断管依赖。
- 规则灰度：版本快照落库（灰度期隔离 Nacos 竞态）+ `userId%100` 采样（同用户恒定同分支）+ Redis 广播失效缓存（秒级回滚，TTL 兜底）；全量乐观迁移保证至多一个 ACTIVE（[ADR-0004](docs/adr/0004-规则灰度发布.md)）。
- 服务划分 4→5：榜单读热/事件沉淀独立为 leaderboard-service（数据热点隔离、读写分离、独立降级面），贡献表复用 record_db，对记录域只读防双写（[ADR-0005](docs/adr/0005-服务划分.md)）。
- 服务划分 5→6 + 空间真实性：道路拓扑匹配独立为 mapmatch-service（PostGIS 真实 OSM 路网 + 最近边投影，HMM 进阶），R5 远程规则接入既有规则链，熔断降级不命中不阻断校验（[ADR-0006](docs/adr/0006-空间匹配.md)）。
- 鉴权闭环：网关统一鉴权（唯一入口 = 唯一信任边界，注入 X-User-Id 且覆盖伪造同名头）+ 双 token（access 15min / refresh 7d）+ refresh 轮换存 Redis（GETDEL 原子作废防重放）+ BCrypt 密码哈希（独立 spring-security-crypto，不拉全家桶）+ 登录失败锁定（达阈值写 auth:lock 临时锁定，见 ADR-0007）+ auth.enabled 降级开关（默认 false 兼容压测，见 [ADR-0007](docs/adr/0007-鉴权设计.md)）。
- 所有 JSON 接口统一 `{"code":0,"message":"success","data":...}` 结构（错误码表见审批版 §4.8）。

## 目录约定

```
spec/           openspec 规范（specs/ 能力域基线 + changes/ 变更提案与差异）
docs/           需求文档与 ADR；docs/perf/ 压测报告与原始数据（data/raw 为逐请求 CSV/JSON）
scripts/perf/   压测工具（零依赖 JDK21 单文件程序）与一键驱动脚本
scripts/mapmatch/  OSM 路网导入脚本（Overpass 下载 + 零依赖导入器 + 建表 SQL，幂等可重跑；data/ 为生成物不入库）
sql/            各库幂等建表脚本（docker-entrypoint-initdb.d 首次自动执行）+ migrations/ 手动迁移
rocketmq/       Broker 本地配置
prometheus/     抓取配置（prometheus.yml，六服务 job）与告警规则（alert-rules.yml）
grafana/        数据源/面板 provider 预置（provisioning/）与预置面板 JSON（dashboards/）
```

## 变更交付

校验引擎（`add-verify-engine`）、好友（`add-friend-module`）、点赞（`add-like-module`）、
排行榜（`add-leaderboard-module`）、压测与优化实录（`add-load-test-report`）、可观测性（`add-observability`）、
规则灰度发布（`add-rule-grayscale`）、榜单独立服务（`add-leaderboard-service`）、
道路拓扑匹配（`add-mapmatch-service`）、JWT 鉴权闭环（`add-jwt-auth`，注册/登录 + 网关统一鉴权 + 数据隔离，见 ADR-0007）均以独立 openspec 变更交付，交付说明见各目录下 `交付说明.md`。

## 后续变更（待办）

运动类型阈值分级（骑行误拦治理）、Sentinel 规则 Nacos 动态化、突发场景消费调优——
均以独立 openspec 变更落地，见 `spec/changes/`。
