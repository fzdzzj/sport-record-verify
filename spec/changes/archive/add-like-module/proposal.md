# 提案：新增点赞模块

## Why

好友模块落地后，社交互动仍缺一环——用户无法对他人通过校验的运动记录表达认可。本变更落地**点赞**：仅 PASSED 记录可赞、Redis INCR/DECR 计数 + 异步批量落库、`(record_id,user_id)` 联合主键天然幂等。它是 record-service「Redis 计数 + 定时任务」技术深度的载体，也是排行榜变更（后续）与前端互动的数据源之一。

**背景**：
- 审批版 §4.6 已定接口与约束，§6.2 已定 `record_like` 表（联合主键防重复赞），§7.5 已定定时任务用 Redisson 锁防重。
- 骨架已为 record-service 铺好：`record_like` 表、Redis 依赖、`@Scheduled` 可用、`RecordApi` 契约。
- 读热写冷 + 最终一致：计数走 Redis，落库异步批量，与校验引擎的「校验 P95<200ms」主链路解耦。

**当前状态**：record-service 无点赞端点；`record_like` 表已建但无写入；Redis 依赖已接入但无计数使用；校验引擎（add-verify-engine）落地后才有 PASSED 状态可赞。

**期望状态**：对 PASSED 记录点赞 → Redis 计数 +1 → 异步落库一条；重复点赞计数不变、落库仅一条；取消 → 计数 -1 → 异步删行；未通过记录点赞返回 6001。

## What Changes

- **record-service**：新增端点 `POST /api/records/{id}/like`（点赞）、`DELETE /api/records/{id}/like`（取消）、计数查询随记录详情返回。
- **点赞前置校验**：仅 `status=PASSED` 记录可赞，否则返回 6001。
- **点赞幂等**：`(record_id,user_id)` 联合主键 + Redis 成员集（`SADD`/`SREM`）双保险，重复点赞只计数一次。
- **计数读热写冷**：Redis `like:count:{recordId}` 用 `INCR`/`DECR` 维护，读取走 Redis，兜底 DB `COUNT(*)`。
- **异步批量落库**：点赞/取消产生 pending 操作，`@Scheduled` 定时任务批量 flush 到 `record_like`，用 Redisson 锁 `lock:like:flush` 防多实例重复执行。
- **最终一致**：计数（Redis）与落库（record_like 行）最终一致；对账任务兜底纠偏。
- **api 模块**：`RecordApi` 补点赞契约与 DTO（点赞数、是否已赞）。
- **common**：复用错误码 6001（记录未通过校验不可点赞）；已定义则直接用。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加点赞能力域需求（ADDED）：前置校验、幂等、计数、异步落库、最终一致。

### 受影响的代码
- `record-service`：+点赞 Controller/Service/Mapper（record_like）+Redis 计数 +定时 flush +Redisson 锁
- `api`：+`RecordApi` 点赞契约与 DTO
- `common`：错误码（复用 6001，无新码）

### 用户影响
- 用户可对 PASSED 记录点赞/取消，查看点赞数。

### API 变更
- 新增端点：点赞 / 取消（见 What Changes）；记录详情返回点赞数。
- 无破坏性变更。

### 需要迁移
- [x] 数据库迁移（`record_like` 表已建，本变更无新表/改表）
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（速览手册 §13 点赞行、README 后续变更待办勾选）

## 时间线评估

较小：约 1 周（可选增强，与好友同属 +1.5 周增强段）。

## 风险

- **计数与落库漂移** → 缓解：以 record_like 行为权威，Redis 为热读；定时 flush + 对账兜底纠偏。
- **异步落库丢失**（进程重启丢 pending）→ 缓解：pending 操作先写 Redis 队列/集合再批量 flush，重启后可重放；对账任务兜底。
- **多实例重复 flush** → 缓解：`lock:like:flush` Redisson 锁（30s 看门狗）保证仅一个实例执行。
- **点赞高并发击穿** → 缓解：计数走 Redis 原子 INCR/DECR，不碰 DB；读取有 DB 兜底。
- **技术卡壳超时**（既定决策）→ 缓解：点赞属可选增强，可与好友同段并行，若抢主线进度则后移。