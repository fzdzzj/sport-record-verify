# 提案：新增运动记录校验引擎

## Why

骨架基线已可编译、可一键启动，但**没有任何业务逻辑**——提交一条运动记录不会产生任何判定。本变更落地系统的核心灵魂：R1-R4 规则链 + 预处理漂移过滤 + 审核状态机，让「提交→校验→判定→（驳回后）申诉→终判」闭环真正跑通。这是整个反作弊项目的主线，也是规则算法与状态机/一致性设计的代码载体。

**背景**：
- 审批版 §5.1 状态机与 §5.2 校验算法已定稿（阈值、级别、证据 JSON 结构均已确认）。
- 骨架已为记录域铺好：`sport_record.version` 乐观锁、`request_id` 幂等键、`track_point` 分片键、`verification_result` 证据表、`appeal` 申诉表、record↔verify 的 Feign 契约（`RecordApi`）与 RocketMQ producer 依赖。
- 交付标准：伪造拦截率 ≥90%、通过率 ≥95%、校验 P95 <200ms；本变更是达成该标准的先决条件。

**当前状态**：record/verify 服务仅有健康端点与 Feign 探活，无判定；ShardingSphere 依赖已接入但 `enabled=false`（未分片）；RocketMQ 生产者依赖已接入但无消息。

**期望状态**：record 提交 → 轨迹按 `user_id%16` 分片落库 → MQ 事件触发 verify → 预处理+R1-R4 → 证据落 `verification_result` → 乐观锁驱动状态迁移 →（REJECTED）可申诉 → 管理员终判，全程幂等可重放。

## What Changes

- **启用轨迹分片**：`track_point` 开启 ShardingSphere `user_id % 16`（16 库/表），打通 MyBatis-Plus 分页插件接入 ShardingSphere 代理数据源（经典坑）。
- **record-service**：轨迹提交（`request_id` 幂等）→ 状态 SUBMITTED→VERIFYING → 发 RocketMQ 事件（Tag `SUBMITTED`）→ Feign 回调更新状态。`sport_record` 乐观锁迁移。
- **verify-service**：消费事件 → 拉轨迹（Feign `RecordApi`）→ 执行预处理 + R1-R4 规则链 → 判定聚合（SCORE/HARD/SOFT）→ 证据落库 → 发 `VERIFIED`/`REJECTED` 事件 → 回调 record 更新 PASSED/REJECTED。
- **校验算法核心类**：预处理漂移过滤（`V_DRIFT=20`、`Δt<0.1s`、`driftRatio>30%`）、R1 速度（滑动 10 点均速 >5.5 m/s 持续 ≥10 点，HARD）、R2 加速度（`Δv/Δt>3 m/s²` ×3，SOFT）、R3 停留（≥5min 位移<5m 段占比>40%，HARD）、R4 距离一致性（累计/直线 >3.0，SOFT）。
- **判定聚合**：命中 HARD→REJECTED；仅 SOFT→默认 REJECTED（可配）；无命中→PASSED；`score=50+20×HARD+10×SOFT`。
- **状态机与申诉**：REJECTED→APPEALING→RE_PASSED/RE_CONFIRMED；管理员复核终判；改判触发回滚/入榜事件（本变更只实现状态迁移与证据，榜单/贡献快照留待排行榜变更）。
- **幂等与一致性**：`request_id` 唯一键、校验结果按 recordId 缓存不重算、事件按 eventId SETNX 去重、乐观锁冲突报 3003。
- **规则阈值可配**：`verify.rules.*` 抽到 Nacos 配置（规则版本/灰度属后续 Nacos 灰度变更，本变更仅阈值外置）。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加校验引擎能力域需求（ADDED）：规则链、状态机、幂等、事件、阈值可配。

### 受影响的代码
- `record-service`：+轨迹提交/查询 +状态机 +乐观锁迁移 +MQ 生产者 +ShardingSphere 分片配置
- `verify-service`：+规则链引擎 +判定聚合 +证据落库 +MQ 消费者/生产者 +Feign 拉轨迹
- `api`：+`RecordApi`/`VerifyApi` 补充提交/判定/申诉契约与 DTO
- `common`：+错误码（3003 状态冲突、4001 校验不可用等，已定义则复用）

### 用户影响
- 用户提交运动记录后可见真实判定（PASSED/REJECTED 及证据摘要）；REJECTED 可发起申诉。

### API 变更
- 新增端点：`POST /api/records`（提交）、`GET /api/records/{id}/verify-result`（查询判定）、`POST /api/records/{id}/appeal`（申诉）、`POST /api/admin/appeals/{id}/review`（终判）。
- 无破坏性变更（骨架上无业务端点）。

### 需要迁移
- [x] 数据库迁移（`sql/` 表已建，本变更无新表；启用分片需 ShardingSphere 配置，不重建表）
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（速览手册 §13 校验引擎行、ADR 补分片启用决策）

## 时间线评估

中等偏大：约 2 周（W4-W5，对应执行计划「核心闭环 W3-W6」中的判定与状态机部分）。

## 风险

- **ShardingSphere × MyBatis-Plus 分页插件冲突** → 缓解：分页插件显式绑定 ShardingSphere 代理 DataSource；W3 已锁定 `shardingsphere-jdbc-core 5.4.1`。
- **状态机并发脏写** → 缓解：乐观锁 `WHERE status AND version`，影响行 0 重试或报 3003。
- **跨库最终一致**（record_db ↔ verify_db）→ 缓解：MQ 事件 + 状态机中间态 + 幂等重试，不引入 Seata。
- **规则误杀/漏杀影响 90%/95% 指标** → 缓解：阈值 Nacos 外置可调，压测变更统一校准。
- **技术卡壳超时**（既定决策）→ 缓解：先砍可选增强，本变更是主线，不后移；若分片拖慢则先以单表跑通规则链，分片建独立变更补。