# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（独立榜单服务能力域）。

## ADDED Requirements

### Requirement: 独立榜单服务
WHEN 系统初始化服务,
系统 SHALL 提供独立的 `leaderboard-service` 承载榜单读热与事件沉淀，与 record-service 的职责 SHALL 分离（record=记录读写，leaderboard=榜单）。

#### Scenario: 服务注册
GIVEN leaderboard-service 已启动
WHEN 查看 Nacos 服务列表
THEN 可见 leaderboard-service 独立实例
AND 独立端口（默认 8084）

#### Scenario: 路由可达
GIVEN 网关已配置 /leaderboard/** 路由
WHEN 客户端请求 /leaderboard/api/leaderboard?type=overall
THEN 返回总榜
AND 请求转发至 leaderboard-service

### Requirement: 榜单事件订阅独立
WHEN 校验产生 VERIFIED/REJECTED 事件,
系统 SHALL 由 leaderboard-service 以独立消费组订阅并沉淀榜单，record-service 的榜单消费者 SHALL 下线，避免双写。

#### Scenario: 独立消费组
GIVEN 记录通过校验并发 VERIFIED 事件
WHEN 事件被消费
THEN 仅 leaderboard-service 入榜
AND record-service 不再写入榜单

#### Scenario: 回滚由榜单服务处理
GIVEN 记录改判发 REJECTED 事件
WHEN leaderboard-service 消费
THEN 回滚榜单贡献
AND 结果与迁移前 T8 验收一致

### Requirement: 榜单数据依赖
WHEN leaderboard-service 沉淀榜单,
系统 SHALL 读取 `leaderboard_contribution` 表；该表默认复用 record_db，物理隔离到独立 leaderboard_db SHALL 列为可选。

#### Scenario: 复用 record_db
GIVEN 默认配置
WHEN leaderboard-service 读写贡献表
THEN 使用 record_db（与 record-service 共享该库中贡献表）
AND 榜单查询功能正常

#### Scenario: 独立 leaderboard_db（可选）
GIVEN 选择物理隔离
WHEN leaderboard-service 启动
THEN 连接独立 leaderboard_db
AND 贡献表迁入该库

---

## MODIFIED Requirements

### Requirement: 服务划分
**Previous**：系统由 4 个服务构成：gateway-service、user-service、record-service、verify-service（榜单内聚于 record-service）。

系统 SHALL 由 5 个服务构成：gateway-service、user-service、record-service、verify-service、leaderboard-service；WHEN 系统部署, 榜单职责 SHALL 由 leaderboard-service 独立承载。

#### Scenario: 服务数
GIVEN 系统完整部署
WHEN 查看服务实例
THEN 可见 5 个服务各自注册
AND 榜单职责不在 record-service 内

---

## 备注

- 本变更是架构重构：不新增业务功能，把已实现的榜单从 record-service 平移至独立服务，服务数 4→5。
- 「为什么 5 个服务」「榜单为什么独立」成为架构决策 ADR 与面试谈资（数据热点隔离、读多写少独立扩缩容、独立降级面）。
- 贡献表归属默认复用 record_db（最小改动），独立 leaderboard_db 为可选项；路由前缀 /record/api/leaderboard → /leaderboard/** 为破坏性变更，需兼容期过渡。