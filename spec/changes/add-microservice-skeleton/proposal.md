# 提案：新增运动记录真实性校验系统 —— 微服务代码骨架

## Why

从「审批基线文档」进入「可运行代码」的首次落地：当前仓库只有需求与执行计划文档，没有任何可编译工程。需要一个锁定版本矩阵、可一键启动、可编译通过的空壳微服务工程，作为后续所有变更（校验引擎、好友、点赞、压测等）的稳定基线。

**背景**：
- 审批基线已锁定：4 微服务 + 独立库 + 版本矩阵 + 双向好友 + 校验阈值，16 项决策全数确认。
- 交付标准要求「compose 一键起 + mvn 编译通过」，必须先有工程骨架才能逐周叠加。
- 采用 openspec 规范驱动，首个变更只做骨架，遵循「一个提案一个关注点」。

**当前状态**：仓库无 `spec/`、无 `pom.xml`、无源码，仅 `docs/` 下的需求文档与执行计划。

**期望状态**：一个可 `mvn clean install` 通过、`docker compose up` 一键拉起中间件、Nacos 注册可见、网关路由连通、Feign 健康探活成功的多模块空壳工程。

## What Changes

- 新增父工程 `sport-verify-parent`：`dependencyManagement` 集中锁定版本矩阵（Java 21 / Boot 3.2.4 / Cloud 2023.0.1 / SCA 2023.0.1.0 / Nacos 2.3.2 / Sentinel 1.8.6 / mybatis-plus-spring-boot3-starter 3.5.7 / ShardingSphere-JDBC 5.4.x / redisson 3.27.x / rocketmq-spring-boot-starter 2.3.x / Caffeine 3.1.x / Lombok 1.18.30+），并声明 7 个子模块。
- 新增 `common` 模块：统一 `Result`、错误码枚举、全局异常处理器。
- 新增 `api` 模块：record-api / verify-api / user-api 三个包的 Feign 接口与 DTO（接口与实现分离）。
- 新增 4 个服务空壳 `gateway-service` / `user-service` / `record-service` / `verify-service`：bootstrap + application 配置、Nacos 注册、Actuator 健康端点、各服务接入独立数据源（user_db / record_db / verify_db）。
- 新增 `docker-compose.yml`：一键编排 Nacos 2.3.2 / MySQL×3 / Redis / RocketMQ。
- 新增 `sql/` 建表脚本：审批版 §6 定义的全部表（user / friend_request / friendship / sport_record / track_point / leaderboard_contribution / record_like / verification_result / appeal / rule_version）。
- 新增 README + ADR 骨架：技术选型决策记录（为什么 Boot 3.2.4 不升 3.3、为什么 RocketMQ 用独立 starter 2.3.x 等）。
- 引入但暂不实现业务的中间件依赖：ShardingSphere-JDBC、Redisson、RocketMQ starter、Caffeine、Sentinel（仅接入与最小配置，为后续变更铺路）。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 新增首个能力域规范基线（本提案 spec-delta 落地后生成）。

### 受影响的代码
- `pom.xml`（父工程）、`common/`、`api/`、`gateway-service/`、`user-service/`、`record-service/`、`verify-service/`、`docker-compose.yml`、`sql/`。

### 用户影响
- 无（内部工程基线，无对外用户）。

### API 变更
- 无破坏性变更；新增 Feign 接口签名（health 探活 + 预留 DTO），尚无业务端点。

### 需要迁移
- [x] 数据库迁移（首次建表，`sql/` 脚本幂等初始化）
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（README / ADR）

## 时间线评估

中等：约 3 周（W1-W3，对应执行计划）。

## 风险

- 版本矩阵冲突（Boot 3.3 与 SCA 2023 分支不兼容）→ 缓解：严格按官方矩阵锁定，不升 Boot 3.3；W1 首日即验证 JDK21 编译。
- ShardingSphere × MyBatis-Plus 分页插件冲突 → 缓解：本提案仅接入依赖，不实现分页，避免提前踩坑。
- 中间件本地资源占用与启动时序 → 缓解：docker-compose 配健康检查与依赖顺序，一键起。
- 技术卡壳导致排期顺延 → 缓解（已决策）：优先砍「可选增强」（好友点赞、排行榜落地），保住校验闭环主线。
