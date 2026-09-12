# 运动记录真实性校验系统（sport-verify）

基于审批版需求（[《运动记录校验系统需求文档（审批版）》](docs/运动记录校验系统需求文档（审批版）.md)）的微服务工程基线。
本仓库由 openspec 规范驱动，首个变更 `spec/changes/add-microservice-skeleton/` 只交付**可编译、可一键启动的空壳骨架**，
校验引擎、好友、点赞、排行榜等业务需求在后续变更中逐周叠加。

## 架构总览

```
                         ┌──────────────────────────────┐
                         │      gateway-service:8080     │  Spring Cloud Gateway + Sentinel 网关限流(5k QPS 基线)
                         └───────┬──────────┬───────────┘
                /user/**         │ /record/**│  /verify/**
        ┌────────┴────────┐ ┌────┴─────────┐ └───────┴────────┐
        │  user-service   │ │record-service│ │  verify-service│
        │      :8081      │ │    :8082     │ │     :8083      │
        └────────┬────────┘ └──────┬───────┘ └───────┬────────┘
        user_db (MySQL)   record_db(MySQL)  verify_db(MySQL)
        Redisson(Redis)   ShardingSphere*    RocketMQ* / Caffeine
                          RocketMQ* / Caffeine
                         (* 骨架阶段仅接入依赖，业务随后续变更启用)
        Nacos 2.3.2（注册中心 + 配置中心）｜ Redis 7 ｜ RocketMQ 5
```

**模块结构**（父工程 `sport-verify-parent` 集中锁定版本矩阵，子模块一律不写版本号）：

| 模块 | 职责 |
| --- | --- |
| `common` | 统一 `Result<T>`、错误码枚举、`BizException`、全局异常处理器 |
| `api` | 服务间 Feign 契约（record-api / verify-api / user-api），接口与实现分离 |
| `gateway-service` | 统一入口，路由 `/user/**` `/record/**` `/verify/**` 到对应服务 |
| `user-service` | 好友申请/同意/拒绝/列表（user_db 三表 + Redisson 锁防并发互加）；注册/登录待后续变更 |
| `record-service` | 记录提交/查询/申诉 + 点赞/取消/计数 + 总榜/好友榜（record_db + Redis ZSet/计数 + 事件消费 + 定时结算防重） |
| `verify-service` | 校验引擎/申诉（骨架：健康端点 + Feign 探活 + verify_db + RocketMQ/Caffeine 接入） |

## 快速开始

前置条件：JDK 21、Maven 3.9+、Docker（Compose v2）。

```bash
# 1. 一键拉起中间件（Nacos / MySQL×3库 / Redis / RocketMQ，含健康检查与依赖顺序）
docker compose up -d

# 2. 全量编译打包（父工程 + 6 个子模块）
mvn clean install

# 3. 启动四个服务（各开一个终端）
java -jar gateway-service/target/sport-verify-gateway-service-0.1.0-SNAPSHOT.jar
java -jar user-service/target/sport-verify-user-service-0.1.0-SNAPSHOT.jar
java -jar record-service/target/sport-verify-record-service-0.1.0-SNAPSHOT.jar
java -jar verify-service/target/sport-verify-verify-service-0.1.0-SNAPSHOT.jar
```

> `java` 必须是 JDK 21（PATH 上是 JDK 8 时会报 UnsupportedClassVersionError，改用绝对路径如
> `D:\develop1\jdk21\bin\java`）；宿主机 3306 被本机 MySQL 占用时，服务启动同样注入 `MYSQL_PORT=3307`。

## 冒烟验证

| 验证项 | 命令 | 期望 |
| --- | --- | --- |
| 中间件健康 | `docker compose ps` | 5 个容器 `healthy` |
| 注册可见 | 浏览器打开 `http://127.0.0.1:8848/nacos` 服务列表 | 4 个服务各 1 实例 |
| 网关路由 | `curl http://127.0.0.1:8080/user/internal/health` | `{"code":0,"message":"success","data":"user-service is alive"}` |
| Feign 探活 | `curl http://127.0.0.1:8080/verify/internal/probe/record` | `"record-service is alive"`（verify→record 跨服务调用） |

> 宿主机 3306 被本机 MySQL 占用时：仓库根目录建 `.env` 写入 `MYSQL_PORT=3307`（compose 与四个服务
> 的数据源端口均已参数化，默认仍 3306），服务侧同名变量见 `scripts/perf/run-perf.sh`。

## 监控与告警（Prometheus + Grafana，选型理由见 [ADR-0003](docs/adr/0003-监控选型.md)）

`docker compose up -d` 已包含监控栈（prometheus:9090 / grafana:3000），无需额外命令。各服务经
`/actuator/prometheus` 暴露 Micrometer 指标（JVM / HTTP / 连接池），Prometheus 抓取宿主机
`host.docker.internal:8080-8083`（服务以宿主机进程运行；容器化后改 target 为服务名即可，见 prometheus.yml 注释）。

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

## 关键决策（详见 [ADR-0001](docs/adr/0001-版本矩阵与技术选型.md) / [ADR-0002 压测与优化实录](docs/adr/0002-压测与优化实录.md) / [ADR-0003 监控选型](docs/adr/0003-监控选型.md) / [ADR-0004 规则灰度发布](docs/adr/0004-规则灰度发布.md)）

- 版本矩阵锁定：**Java 21 + Boot 3.2.4 + Cloud 2023.0.1 + SCA 2023.0.1.0**（不升 Boot 3.3，SCA 2023 分支不兼容）。
- Nacos 2.3.2 同时承担注册中心与配置中心（`spring.config.import: optional:nacos:*`，Nacos 不可用不阻塞启动）。
- MySQL 单实例三库（user_db/record_db/verify_db）逻辑隔离；ShardingSphere 按 user_id%16 分片 track_point。
- RocketMQ 5.2 + `rocketmq-spring-boot-starter 2.3.1` 独立集成；broker 配 `brokerIP1=127.0.0.1` 使宿主机服务可直连。
- 服务间熔断 Resilience4j（Feign 降级转人工）、入口限流 Sentinel（网关 5k QPS 基线）——限流管流量、熔断管依赖。
- 规则灰度：版本快照落库（灰度期隔离 Nacos 竞态）+ `userId%100` 采样（同用户恒定同分支）+ Redis 广播失效缓存（秒级回滚，TTL 兜底）；全量乐观迁移保证至多一个 ACTIVE（[ADR-0004](docs/adr/0004-规则灰度发布.md)）。
- 所有 JSON 接口统一 `{"code":0,"message":"success","data":...}` 结构（错误码表见审批版 §4.8）。

## 目录约定

```
spec/           openspec 规范（specs/ 能力域基线 + changes/ 变更提案与差异）
docs/           需求文档与 ADR；docs/perf/ 压测报告与原始数据（data/raw 为逐请求 CSV/JSON）
scripts/perf/   压测工具（零依赖 JDK21 单文件程序）与一键驱动脚本
sql/            各库幂等建表脚本（docker-entrypoint-initdb.d 首次自动执行）+ migrations/ 手动迁移
rocketmq/       Broker 本地配置
prometheus/     抓取配置（prometheus.yml，四服务 job + leaderboard 空位）与告警规则（alert-rules.yml）
grafana/        数据源/面板 provider 预置（provisioning/）与预置面板 JSON（dashboards/）
```

## 变更交付

校验引擎（`add-verify-engine`）、好友（`add-friend-module`）、点赞（`add-like-module`）、
排行榜（`add-leaderboard-module`）、压测与优化实录（`add-load-test-report`）、可观测性（`add-observability`）、
规则灰度发布（`add-rule-grayscale`）均以独立 openspec 变更交付，交付说明见各目录下 `交付说明.md`。

## 后续变更（待办）

运动类型阈值分级（骑行误拦治理）、Sentinel 规则 Nacos 动态化、突发场景消费调优——
均以独立 openspec 变更落地，见 `spec/changes/`。
