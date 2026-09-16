# 规范差异：sport-record-verify

本文件包含对 `spec/specs/sport-record-verify/spec.md` 的规范变更（请求贯穿标识，全部为新增）。

## ADDED Requirements

### Requirement: HTTP 请求贯穿标识
WHEN 外部请求经网关进入系统,
系统 SHALL 保证存在 `X-Request-Id`：若请求已携带则沿用，否则生成 UUID；SHALL 写入响应头并透传至下游服务。

#### Scenario: 无入站 ID 时自动生成
GIVEN 客户端未携带 X-Request-Id
WHEN 请求经过 gateway-service
THEN 响应头包含非空 X-Request-Id
AND 下游服务请求头可见同一 X-Request-Id

#### Scenario: 客户端指定 ID 时沿用
GIVEN 客户端携带 X-Request-Id: client-fixed-id
WHEN 请求经过 gateway-service
THEN 响应头与下游透传头均为 client-fixed-id

### Requirement: 服务内日志 MDC
WHEN 业务服务（Servlet MVC）处理 HTTP 请求,
系统 SHALL 将 X-Request-Id 写入 MDC 键 `traceId`，并在请求结束时清理；日志 pattern SHALL 输出 `[%X{traceId}]`。

#### Scenario: 访问日志含 traceId
GIVEN 服务已配置统一 logging.pattern.console
WHEN 处理带 X-Request-Id 的请求并打业务日志
THEN 日志行包含该 traceId

### Requirement: MQ 跨服务透传
WHEN 生产者发布记录/校验事件,
系统 SHALL 将当前 traceId 写入消息 userProperty（键 X-Request-Id）；
WHEN 消费者处理消息,
系统 SHALL 读取该属性并还原到 MDC，处理结束后清理。

#### Scenario: 提交记录全链路同一 traceId
GIVEN 经网关提交一条运动记录且 MQ 链路正常
WHEN record 发 SUBMITTED、verify 消费并判定、verify 发 VERIFIED/REJECTED、leaderboard 消费入榜
THEN 上述各阶段业务日志可用同一 traceId 检索对齐

### Requirement: 不引入分布式追踪全家桶
WHEN 评估链路追踪方案,
系统 SHALL 保持 ADR-0003 决策：以 MDC 最小实现满足日志串联，不引入 SkyWalking / Zipkin / Sleuth。

#### Scenario: 依赖面无追踪中间件
GIVEN 本变更交付完成
WHEN 检查服务依赖与配置
THEN 无 SkyWalking / Zipkin / spring-cloud-sleuth 强制依赖
