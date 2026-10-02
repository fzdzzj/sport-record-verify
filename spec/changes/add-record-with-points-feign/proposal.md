# 变更提案：新增拉取记录与轨迹聚合 Feign 契约（getRecordWithPoints）

## 1. Why

在现行生产架构中，`verify-service` 的校验消费者 `VerifyEventConsumer` 在接收到 `RecordVerifyEvents.TAG_SUBMITTED` 消息后，调用 `VerifyService.verify(recordId)` 开展规则判定。当前实现执行以下调用链：
1. `recordApi.getRecord(recordId)`（经 Feign 发送 HTTP GET `/internal/records/{recordId}`）；
   - 在 `record-service` 侧：调用 `sportRecordMapper.selectById(recordId)` 查询运动记录元数据；
2. `recordApi.listPoints(recordId)`（经 Feign 发送 HTTP GET `/internal/records/{recordId}/points`）；
   - 在 `record-service` 侧：由于 `track_point` 表以 `user_id` 为分片键（ShardingSphere 单分片路由），必须先通过 `sportRecordMapper.selectById(recordId)` 解析出 `user_id`，再以 `(record_id, user_id)` 单分片查询 `trackPointMapper.selectList`；
3. 规则引擎校验与判定事件落库；
4. `recordApi.statusCallback(...)`（经 Feign 回调记录状态迁移）。

由此可见：
- **网络开销**：每次记录校验在判定前都需要执行 2 次独立的跨服务 Feign HTTP 往返；
- **数据库查询冗余**：在 `record-service` 侧，`sportRecordMapper.selectById(recordId)` 被完全重复执行了两次。

为消除判定前重复的 HTTP 网络往返及对 `sport_record` 表的重复查询，本提案提出在 `record-api` 与 `record-service` 中新增聚合契约 `getRecordWithPoints`，一次性获取记录元数据与轨迹点列表，并由 `verify-service` 的主判定链路接入使用。

---

## 2. What Changes

1. **Feign 契约层（`api` 模块）**：
   - 新增聚合 DTO `RecordWithPointsDTO`（包含 `SportRecordDTO record` 与 `List<TrackPointDTO> points`，支持无参/全参构造与序列化）；
   - `RecordApi` 接口声明 `@GetMapping("/records/{recordId}/with-points")` 返回 `Result<RecordWithPointsDTO>`；
   - `RecordApiFallback` 落地显式失败（抛出 `RECORD_SERVICE_UNAVAILABLE(4007)`，不可软降级，契合既有架构约束）。
   - 保留原有的 `getRecord` 与 `listPoints` 接口及实现，向后完全兼容。

2. **服务端实现（`record-service` 模块）**：
   - `SportRecordService` 增加 `getRecordWithPoints(Long recordId)`：
     - 单次调用 `sportRecordMapper.selectById(recordId)`；若不存在则直接抛出 `RECORD_NOT_FOUND(3001)`；
     - 获取 `record.getUserId()` 后，单分片路由查询 `trackPointMapper.selectList`；
     - 组装为 `RecordWithPointsDTO` 返回。
   - `InternalRecordController` 暴露 `@GetMapping("/records/{recordId}/with-points")`。

3. **消费端接入（`verify-service` 模块）**：
   - `VerifyService.verify(recordId)` 将前序的两次单独 Feign 调用替换为单次 `recordApi.getRecordWithPoints(recordId)`；
   - 提取其中的 `record` 与 `points`，后续规则引擎判定与事务性事件落库逻辑一字不动；
   - 结果已存在时的补偿回调路径 `reconcileCallback` 继续保持原有轻量 `getRecord` 调用不变。

---

## 3. Impact

- **生产行为与性能**：
  - 判定前 Feign HTTP 往返次数由 2 次降为 1 次；
  - `record-service` 侧对 `sport_record` 表的 SELECT 查询次数由 2 次降为 1 次；
  - 轨迹点查询仍严格携带分片键 `user_id`，ShardingSphere 保持单分片路由，绝不退化为全库广播；
  - 对外暴露的原有接口完全不变，向后兼容。
- **降级与容错**：
  - 继承既有 `RecordApiFallback`「不可软降级」红线：服务不可用时严禁返回伪造空轨迹，统一抛出 4007 驱动 MQ 重试或进入死信。

---

## 4. 判定与停止条件

1. 若 `getRecordWithPoints` 缺少 `user_id` 导致 `track_point` 触发跨分片全路由广播 ⇒ **停止**。
2. 若 `record-service` 不可用时返回软降级假数据而非 4007 异常 ⇒ **停止**。
3. 若破坏已有单测或全模块测试失败 ⇒ **停止**。
4. 若 Checkstyle 违规数增加（超过基线 862） ⇒ **停止**。
