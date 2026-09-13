# 提案：新增排行榜模块

## Why

点赞落地后，用户行为有了「记录通过校验」这一可信数据源，但缺一个把它沉淀为**公平榜单**的收口——总榜 + 好友榜。本变更落地排行榜：仅 PASSED 里程入榜、Redis ZSet 秒级查询、`leaderboard_contribution` 贡献快照作为回滚锚点、改判自动回滚，形成「提交→校验→判定→申诉→榜单沉淀」闭环的最后一环，也是 ZSet 有序集合、事件驱动最终一致、定时任务防重的代码载体。

**背景**：
- 审批版 §4.5 已定接口与约束，§6.2 已定 `leaderboard_contribution` 表（record_id 主键=回滚锚点），§7.1 已定 VERIFIED/REJECTED/REVERSED 事件与榜单刷新消费者，§7.5 已定 `lock:scheduler:leaderboard` 与 `lock:rollback:{recordId}`。
- 校验引擎（add-verify-engine）已发 VERIFIED/REJECTED 事件；好友模块（add-friend-module）已交付 `UserApi` 好友列表契约。
- 读多写少：榜单读走 Redis ZSet（成员=用户数，秒级），写走事件消费，改判回滚由事件驱动。

**当前状态**：record-service 无榜单端点与消费者；`leaderboard_contribution` 表已建但无写入；事件 topic 已定义但无榜单刷新消费者；好友列表 Feign 契约已备。

**期望状态**：PASSED/RE_PASSED 记录入榜（累计 pass 里程）→ 总榜/好友榜秒级查询；RE_CONFIRMED/改判自动回滚该记录贡献；定时结算对账纠偏；多实例不重复执行。

## What Changes

- **record-service**：新增查询端点 `GET /api/leaderboard?type=overall`（总榜）、`GET /api/leaderboard?type=friend`（好友榜）。
- **事件驱动入榜**：消费 VERIFIED 事件 → `ZINCRBY leaderboard:overall {distance} {userId}` → 写 `leaderboard_contribution`（record_id 主键，status=ACTIVE）；eventId SETNX 幂等。
- **改判回滚**：消费 REJECTED/REVERSED 事件，若该 recordId 存在有效贡献 → `ZINCRBY leaderboard:overall {-distance}` 回滚 → contribution status=ROLLED_BACK；`lock:rollback:{recordId}` 防回滚与入榜并发。
- **好友榜过滤**：`type=friend` 时 Feign 调 user-service 取好友列表，ZSet 结果按好友过滤，只显示好友。
- **快照结算定时任务**：`@Scheduled` + Redisson 锁 `lock:scheduler:leaderboard`（30s 看门狗）保证多实例仅一个执行；对账纠偏 ZSet 与 contribution 汇总，标记 settled_at。
- **仅 pass 里程**：任何未 PASSED/RE_PASSED 的记录不入榜。
- **api 模块**：`RecordApi` 补榜单契约与 `LeaderboardDTO`（rank/userId/nickname/distance）。
- **common**：复用既有错误码，无新码。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加榜单能力域需求（ADDED）：入榜、回滚、总榜/好友榜、结算防重。

### 受影响的代码
- `record-service`：+榜单 Controller/消费者（VERIFIED/REJECTED/REVERSED）+leaderboard_contribution Mapper +Redis ZSet +定时结算 +Redisson 锁
- `api`：+`RecordApi` 榜单契约与 `LeaderboardDTO`
- 依赖：`UserApi`（好友列表）、`VerifyApi`/事件（校验引擎已发）

### 用户影响
- 用户可查看总榜与好友榜（仅 pass 里程），改判后榜单自动一致。

### API 变更
- 新增端点：总榜 / 好友榜查询（见 What Changes）。
- 无破坏性变更。

### 需要迁移
- [x] 数据库迁移（`leaderboard_contribution` 表已建，本变更无新表/改表）
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（速览手册 §13 排行榜行、README 后续变更待办勾选）

## 时间线评估

中等：约 1 周（可选增强，与好友/点赞同属增强段）。

## 风险

- **改判回滚与入榜并发脏写** → 缓解：`lock:rollback:{recordId}` 与 contribution 状态字段双保险；回滚幂等（已 ROLLED_BACK 不再重复回滚）。
- **ZSet 与 contribution 漂移** → 缓解：定时结算对账，以 contribution 汇总为准纠偏 ZSet。
- **好友榜跨服务放大延迟** → 缓解：好友列表一次 Feign 拉取后在内存过滤，不逐条远程调用；好友列表规模小（演示 1000 用户）。
- **多实例重复结算** → 缓解：`lock:scheduler:leaderboard` Redisson 锁，仅一个实例执行。
- **技术卡壳超时**（既定决策）→ 缓解：榜单属可选增强，若与主线抢进度则后移；回滚逻辑可先降级为「全量重建 ZSet」兜底。