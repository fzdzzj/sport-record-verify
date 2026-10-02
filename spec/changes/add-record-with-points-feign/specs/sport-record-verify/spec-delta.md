# 变更增量规格：新增拉取记录与轨迹聚合 Feign 契约

## 1. 变更背景与架构依据

- **背景与消除冗余**：当前 `VerifyService.verify(recordId)` 在判定前执行两次独立的 Feign 调用：`recordApi.getRecord(recordId)` 与 `recordApi.listPoints(recordId)`。由于 `track_point` 表分片键为 `user_id`，`record-service` 在处理 `listPoints` 时再次查询 `sport_record` 表解析 `user_id`。两次 Feign 调用不仅带来多一次网络往返，更在数据库侧产生对同一记录的重复 `selectById`。
- **契约向后兼容**：保留既有的 `getRecord` 与 `listPoints` 接口及实现，向后完全兼容；新增聚合接口供校验链路使用。
- **容错不可软降级**：严格延续既有规格「不可软降级」红线（`spec/specs/sport-record-verify/spec.md:208` 对应场景）：当 `record-service` 不可用时，Feign Fallback 必须抛出 `RECORD_SERVICE_UNAVAILABLE(4007)`，严禁返回空轨迹或伪造成功。

---

## 2. 需求增量（Delta）

### ADDED Requirement: 运动记录与轨迹聚合拉取契约

WHEN `verify-service` 开展记录校验判定时，
系统 SHALL 支持通过 `RecordApi.getRecordWithPoints(recordId)` 单次拉取记录详情与全部轨迹点列表，
在 `record-service` 侧单次查询 `sport_record` 获取元数据并解析 `user_id`，再以 `(record_id, user_id)` 单分片查询 `track_point` 表并返回聚合结果，
消除分开调用 `getRecord` 与 `listPoints` 产生的额外跨服务 HTTP 往返与对 `sport_record` 的重复查询。

#### Scenario: 成功单次拉取记录详情与轨迹列表
GIVEN 记录 ID 存在且包含轨迹点
WHEN 调用 `RecordApi.getRecordWithPoints(recordId)`
THEN 返回 `RecordWithPointsDTO` 包含完整的 `SportRecordDTO` 与按 `seq` 升序排列的 `List<TrackPointDTO>`
AND `record-service` 侧仅对 `sport_record` 执行一次主键查询，且轨迹查询严格携带分片键 `user_id`

#### Scenario: 记录不存在抛出 RECORD_NOT_FOUND
GIVEN 记录 ID 在数据库中不存在
WHEN 调用 `RecordApi.getRecordWithPoints(recordId)`
THEN 抛出业务异常 `RECORD_NOT_FOUND`（错误码 3001）
AND 不执行轨迹点分片查询

#### Scenario: 聚合契约不可软降级
GIVEN `record-service` 服务不可用（如网络拒绝、超时或熔断）
WHEN `verify-service` 调用 `RecordApi.getRecordWithPoints(recordId)`
THEN Feign 回退工厂 `RecordApiFallback` MUST 抛出 `RECORD_SERVICE_UNAVAILABLE`（错误码 4007）
AND MUST NOT 返回空数据或伪造成功
AND 触发消息消费重试或进入死信队列
