# 提案：新增双向好友模块

## Why

校验闭环（add-verify-engine）落地后，系统仍是「孤岛」——用户之间没有关系，好友榜（后续变更）也依赖好友列表做过滤。本变更落地**双向好友**：申请/同意/拒绝/列表 + 规范化存储防并发互加，是可选增强中依赖最靠前的一项，也是面试弹药 G（friendship 规范化存储从根上消除 A-B/B-A 重复行）与 F（Redisson 分布式锁）的代码载体。

**背景**：
- 审批版 §4.1 已定接口与约束，§6.1 已定 friend_request/friendship 表结构，§7.5 已定锁键 `lock:friend:{low}_{high}`。
- 骨架已为 user-service 铺好：`user_db` 三张表（user/friend_request/friendship）、Redisson starter 依赖（懒连接）、`UserApi` Feign 契约。
- 双向语义：一旦 ACCEPTED，双方互见（区别于单向关注）；关系存储强制 `user_low < user_high` 归一化。

**当前状态**：user-service 仅有健康端点；friend_request/friendship 表已建但无任何写入；Redisson 依赖已接入但无锁使用。

**期望状态**：用户 A 向 B 发申请 → B 同意 → 双方各查好友列表均见对方；并发互加只产生一条关系；重复申请/已存在关系返回 5001。

## What Changes

- **user-service**：新增好友端点 `POST /api/friends/requests`（申请）、`POST /api/friends/requests/{id}/accept`、`POST /api/friends/requests/{id}/reject`、`GET /api/friends?page=&size=`（列表）。
- **friend_request 状态机**：PENDING(0)/ACCEPTED(1)/REJECTED(2)/CANCELLED(3)；仅 PENDING 可流转，流转用乐观语义（`WHERE status=PENDING`）。
- **friendship 规范化存储**：ACCEPTED 时写 `(user_low, user_high)`，强制 `user_low < user_high`，主键唯一 + CHECK 约束，根上消除 A-B/B-A 重复行。
- **申请幂等去重**：同向 PENDING 重复 / 反向 PENDING 已存在 / 已存在关系 → 返回 5001 或原申请单。
- **Redisson 锁**：`lock:friend:{low}_{high}` 可重入锁（10s）包裹「检查-建单」，与规范化存储双保险，保证并发互加只产生一条关系。
- **api 模块**：`UserApi` 补好友契约 + `FriendRequestDTO`/`FriendshipDTO`（Feign 供 record-service 后续好友榜过滤）。
- **common**：复用错误码 5001（重复申请或已存在关系）/ 5002（关系不存在）；已定义则直接用。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加好友能力域需求（ADDED）：申请、状态机、规范化存储、并发唯一性、列表。

### 受影响的代码
- `user-service`：+好友 Controller/Service/Mapper（friend_request、friendship）+Redisson 锁逻辑
- `api`：+`UserApi` 好友契约与 DTO
- `common`：错误码（复用，无新码）

### 用户影响
- 用户可申请/同意/拒绝好友，查看好友列表（仅 ACCEPTED）。

### API 变更
- 新增端点：申请 / 同意 / 拒绝 / 好友列表（见 What Changes）。
- 无破坏性变更。

### 需要迁移
- [x] 数据库迁移（表已建，本变更无新表/改表）
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（速览手册 §13 好友行、README 后续变更待办勾选）

## 时间线评估

较小：约 +1.5 周（可选增强，对应执行计划 W6.5-W9 增强段）。

## 风险

- **并发互加产生两条关系** → 缓解：规范化存储主键 `(user_low,user_high)` + CHECK 双保险；Redisson `lock:friend:{low}_{high}` 串行化「检查-建单」。
- **反向申请未识别导致误报 5001 或重复单** → 缓解：建单前同时查 (from,to) 与 (to,from) 两个方向的 PENDING 与已存在关系。
- **锁键粒度** → 缓解：锁键统一用 `low_high`（min/max），与规范化存储键一致，避免 A-B 与 B-A 拿到不同锁。
- **技术卡壳超时**（既定决策）→ 缓解：好友属可选增强，若与校验引擎抢进度，优先保主线；本提案独立可后移。