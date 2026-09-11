# 运动记录真实性校验系统（sport-verify）

基于审批版需求（[《运动记录校验系统需求文档（审批版）》](docs/运动记录校验系统需求文档（审批版）.md)）的微服务工程基线。
本仓库由 openspec 规范驱动，首个变更 `spec/changes/add-microservice-skeleton/` 只交付**可编译、可一键启动的空壳骨架**，
校验引擎、好友、点赞、排行榜等业务需求在后续变更中逐周叠加。

## 架构总览

```
                         ┌──────────────────────────────┐
                         │      gateway-service:8080     │  Spring Cloud Gateway + Sentinel(预留)
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
| `user-service` | 账号/好友（骨架：健康端点 + user_db + Redisson 接入） |
| `record-service` | 记录/榜单/点赞（骨架：健康端点 + record_db + ShardingSphere/RocketMQ/Caffeine 接入） |
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

## 冒烟验证

| 验证项 | 命令 | 期望 |
| --- | --- | --- |
| 中间件健康 | `docker compose ps` | 5 个容器 `healthy` |
| 注册可见 | 浏览器打开 `http://127.0.0.1:8848/nacos` 服务列表 | 4 个服务各 1 实例 |
| 网关路由 | `curl http://127.0.0.1:8080/user/internal/health` | `{"code":0,"message":"success","data":"user-service is alive"}` |
| Feign 探活 | `curl http://127.0.0.1:8080/verify/internal/probe/record` | `"record-service is alive"`（verify→record 跨服务调用） |

## 关键决策（详见 [ADR](docs/adr/0001-版本矩阵与技术选型.md)）

- 版本矩阵锁定：**Java 21 + Boot 3.2.4 + Cloud 2023.0.1 + SCA 2023.0.1.0**（不升 Boot 3.3，SCA 2023 分支不兼容）。
- Nacos 2.3.2 同时承担注册中心与配置中心（`spring.config.import: optional:nacos:*`，Nacos 不可用不阻塞启动）。
- MySQL 单实例三库（user_db/record_db/verify_db）逻辑隔离；ShardingSphere 仅接入依赖默认关闭，分片随校验引擎变更启用。
- RocketMQ 5.2 + `rocketmq-spring-boot-starter 2.3.1` 独立集成；broker 配 `brokerIP1=127.0.0.1` 使宿主机服务可直连。
- 所有 JSON 接口统一 `{"code":0,"message":"success","data":...}` 结构（错误码表见审批版 §4.8）。

## 目录约定

```
spec/           openspec 规范（specs/ 能力域基线 + changes/ 变更提案与差异）
docs/           需求文档与 ADR
sql/            各库幂等建表脚本（docker-entrypoint-initdb.d 首次自动执行）
rocketmq/       Broker 本地配置
```

## 后续变更（待办）

校验引擎判定（含分片启用）、好友双向、点赞、排行榜、压测与优化实录 —— 均以独立 openspec 变更落地，见 `spec/changes/`。
