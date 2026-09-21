# 提案：提交事件改为异步发送（不阻塞提交线程）

## Why

提交成功后 `RecordEventProducer.publishSubmitted` 走 `RocketMQTemplate.syncSend`，调用点在事务 `afterCommit` 里，仍占用提交请求线程。发送失败才降级 Feign。提交接口 P95 里会叠上一次同步 MQ RTT。

这与「校验本身异步」不是一回事：校验已由消费者做；挡住提交返回的是**同步发送**。

**背景**：
- ADR-0002 / 压测报告的提交吞吐瓶颈主因是逐条 INSERT（另案默认开启批量）。
- 突发校验 P95 已通过消费线程 32/40、单批 8 调过，本变更不改消费参数。
- `VerifyEventProducer.publish` 同样是 syncSend，但发生在校验线程，不挡提交 HTTP。

**当前状态**：SUBMITTED 同步发送；失败返回 false，submit 的 afterCommit 里立刻 Feign 直调。

**期望状态**：SUBMITTED 异步发送；失败仍走既有 Feign 降级（转人工）语义；traceId 必须带到异步线程；不改消费侧线程数。

## What Changes

- `RecordEventProducer.publishSubmitted` 改为异步发送（RocketMQ async 或等价），在回调里处理成功/失败。
- `SportRecordService.submit` 的 afterCommit：不再在请求线程上 syncSend；失败回调仍走 Feign 直调 → 4001 转人工。
- 异步线程恢复 MDC traceId（add-request-tracing 口径）。
- 单测：成功不阻塞；失败仍触发既有降级。
- VerifyEventProducer 本变更不动（不在提交 HTTP 路径上）。

**明确不做**：不改 consume-thread-*；不改批量插入开关；不加本地消息表；不上 Seata。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — ADDED「提交事件异步发布」。

### 受影响的代码
- `record-service`：RecordEventProducer、SportRecordService.afterCommit、相关测试

### 用户影响
- 无 API 变更。提交 HTTP 不再等待 MQ 同步 RTT；校验仍最终一致。

### API 变更
- 无。

### 需要迁移
- [ ] 数据库迁移
- [x] 文档更新（ADR-0002 补一句：提交发送异步，消费参数不变）

## 时间线评估

小：约 0.5 天。

## 风险

- **异步失败时 afterCommit 已结束，Feign 降级要在回调里做** → 缓解：把降级逻辑抽到可在回调调用的方法，单测锁定失败仍降级。
- **MDC traceId 丢失** → 缓解：发送前捕获，回调/异步线程写回。
- **把消费线程再加大当成本变更** → 缓解：明确禁止。
