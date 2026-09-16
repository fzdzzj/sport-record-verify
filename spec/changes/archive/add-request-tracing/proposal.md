# 提案：跨服务 / 跨 MQ 请求贯穿标识（MDC + X-Request-Id）

## Why

指标面（Prometheus/Grafana/告警）已齐，但日志维度断开：一次「提交记录」经 gateway → record → RocketMQ → verify → RocketMQ → leaderboard，出问题只能按时间戳人工对齐。全仓 MDC / traceId / Sleuth / micrometer-tracing 实测零命中。ADR-0003 已否决 SkyWalking/Zipkin 全家桶（当前无完整 APM 需求，引入即超配）；本变更用最小 MDC 实现即可。

**当前状态**：无请求 ID 透传、无 MDC、MQ 消息无关联属性。
**期望状态**：网关保证 `X-Request-Id`；各服务 HTTP 请求写入 MDC `traceId`；MQ 发送/消费透传同一 ID；日志统一输出 `[%X{traceId}]`，可用同一 ID 串起提交全链路。

## What Changes

- gateway：WebFlux `GlobalFilter` 读取/生成 `X-Request-Id`，写响应头并透传下游。
- common：`TraceIds` 常量 + `TraceIdFilter`（OncePerRequestFilter → MDC，finally 清理）；各 MVC 服务经 `scanBasePackages=com.sportverify` 自动生效。
- api：Feign 拦截器把当前 MDC traceId 注入出站 `X-Request-Id`（降级直调不丢链）。
- MQ：`RecordEventProducer` / `VerifyEventProducer` 写入 userProperty；`VerifyEventConsumer` / `LeaderboardEventConsumer` 还原 MDC。
- 各服务统一 `logging.pattern.console` 含 `[%X{traceId}]`。
- 不引入 Sleuth/Zipkin/SkyWalking；**不**把 UUID traceId 打成 Micrometer 高基数 tag（避免指标爆炸）。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` — 新增请求贯穿标识能力（本变更 spec-delta）。

### 受影响的代码
- `common/**` TraceIds + Filter；`gateway-service` RequestIdGlobalFilter；`api` Feign 透传；record/verify/leaderboard 的 MQ producer/consumer；各服务日志 pattern。

### 用户影响
- 响应多 `X-Request-Id` 头；客户端可主动传入以便自关联。排障可按同一 ID grep 全服务日志。

### API 变更
- 响应头新增 `X-Request-Id`（无路径变更）。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [x] 文档/openspec 变更目录

## 时间线评估

较小：约 0.5 天。

## 风险

- WebFlux MDC 线程切换可能丢上下文 → 网关仍保证头透传；业务链路在 MVC + MQ 手动 put/clear。
- RocketMQ userProperty 键字符集 → 使用与 HTTP 一致的 `X-Request-Id`。
- 旧消息无属性 → 消费侧缺省生成临时 ID，不阻断消费。
