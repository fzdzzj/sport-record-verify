# 提案：拆分独立榜单微服务（leaderboard-service，服务数 4→5）

## Why

榜单当前沉淀在 record-service 里（`LeaderboardService`/`LeaderboardController`/`LeaderboardEventConsumer` 等一整套），与「记录分片存储」「点赞」耦合在同一服务。执行计划 P2 明确列出「第 5 个榜单服务」为可选深化项。拆出独立 `leaderboard-service` 后：① 服务职责更单一（record=记录读写，leaderboard=榜单读热+事件沉淀）；② 榜单读多写少、可独立扩缩容（读流量不再挤占 record 服务的 DB 连接池/CPU）；③ 面试弹药升级——「为什么 5 个服务」「榜单为什么独立」变成可讲的架构决策（数据热点隔离、读写分离、独立降级面）。

这是纯「架构重构」型深化，不新增业务能力，但对面试说服力提升最大，也让「为什么微服务」的回答更有依据。

**背景**：
- 审批版 §2.2 服务划分原为 4 服务（leaderboard-service 设计-only）；执行计划 P2「第 5 个榜单服务」列为可选。
- 榜单已完整实现于 record-service（含 ZSet 热读、contribution 锚点、事件消费 VERIFIED/REJECTED、定时结算、好友榜 Feign）。
- 榜单读路径：ZSet 秒级；写路径：事件驱动最终一致；与 record 主链路完全解耦——拆分的耦合面极小，主要是「反向依赖」：榜单消费的 VERIFIED/REJECTED 事件由 record-service 生产，榜单查询好友列表仍调 user-service。

**当前状态**：榜单代码、表、Redis 键全在 record-service；`leaderboard_contribution` 表在 record_db；网关路由 `/record/api/leaderboard` 也走 record-service。

**期望状态**：独立 `leaderboard-service`（新服务 + 独立或复用 record_db 的只读视角 + 消费 record 发布的事件入榜 + 独立网关路由 `/leaderboard/**`），record-service 卸下榜单职责；服务数 4→5，职责边界清晰。

## What Changes

- **新模块 `leaderboard-service`**：父 pom `modules` 增加第 7 个子模块；独立端口（如 8084）、Nacos 注册、配置中心。
- **平移榜单代码**：`LeaderboardService`/`LeaderboardController`/`LeaderboardEventConsumer`/`LeaderboardContribution`/`LeaderboardContributionMapper`/`ContributionStatus` 从 record-service 迁入 leaderboard-service（包名 `com.sportverify.leaderboard`）。
- **事件订阅**：leaderboard-service 作为独立消费者组订阅 `record-verify-events` 的 VERIFIED/REJECTED Tag（消费组可复用或改为 `leaderboard-consumer-group` 保持独立）。
- **数据依赖**：`leaderboard_contribution` 表归属——保持 `record_db`（由 record 服务写记录、榜单服务读该库的贡献表）或拆到独立 `leaderboard_db`；本提案采用「贡献表随 leaderboard-service 迁到独立 leaderboard_db 或复用只读数据源」两条路线，默认**复用 record_db 只读视图**（最低改动），数据库物理隔离作为可选。
- **网关路由**：新增 `/leaderboard/**` 路由指向 leaderboard-service（804）；移除 `/record/api/leaderboard` 旧路由。
- **向下兼容**：record-service 移除榜单相关类与依赖（SportRecordMapper 对贡献表的访问、UserApi 榜单调用等），保留记录/点赞职责。
- **api 契约**：`LeaderboardDTO` 仍在 api 模块（跨服务共享），leaderboard-service 与 gateway 引用。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加「独立榜单服务」与「服务划分」能力域需求（ADDED + MODIFIED 服务划分语义）。

### 受影响的代码
- 父 `pom.xml`（+leaderboard-service 模块）
- 新增 `leaderboard-service/`（全量榜单代码迁移）
- `record-service`（删榜单相关类、路由、依赖）
- `gateway-service`（+`/leaderboard/**` 路由，-`/record/api/leaderboard`）
- `api`（LeaderboardDTO 位置不变，供两服务引用）
- `docker-compose.yml`（如拆 leaderboard_db 则加库）

### 用户影响
- 榜单查询路径变更（网关 `/leaderboard/**`），功能行为不变。

### API 变更
- 榜单查询端点从 `/record/api/leaderboard` 迁移为 `/leaderboard/**`（破坏性路由变更，需更新调用方与前端契约）。
- 无业务字段破坏。

### 需要迁移
- [x] 数据库迁移（贡献表复用 record_db 或迁至 leaderboard_db，二选一，默认复用）
- [x] API 版本提升（榜单路由前缀变更）
- [ ] 用户沟通
- [x] 文档更新（README 架构图、速览手册、ADR 服务划分理由）

## 时间线评估

较大：约 1.5-2 周（P2 架构重构项）。

## 风险

- **服务拆分引入分布式边界 Bug**（事件消费、数据源割裂）→ 缓解：先「平移代码 + 复用 record_db 只读」跑通，再谈物理隔离；迁移前后各跑一遍 T8（改判回滚）冒烟。
- **路由前缀破坏性变更** → 缓解：兼容期内 record-service 保留 `/record/api/leaderboard` 302 重定向或网关同时转发两路由，前端切换后再下掉旧的。
- **事件消费重复**（record 与 leaderboard 两个消费者组可能都订阅）→ 缓解：迁移期确保只有 leaderboard-service 订阅 VERIFIED/REJECTED，record-service 原消费者下线，避免双写榜单。
- **数据源拆分复杂度** → 缓解：默认复用 record_db（贡献表仍在该库），仅服务进程拆分；独立 leaderboard_db 列为可选项，避免一次性引入迁移成本。
- **技术卡壳超时**（既定决策）→ 缓解：本项是 P2 重度项，若抢主线则降级为「服务拆分 + 复用库」最小闭环交付，数据库物理隔离讲设计。