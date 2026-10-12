# 提案：越权反例收口——id 类端点归属校验补全（TASK-204 harden-idor-ownership）

## 侦察实证（2026-10-12，指导侧全仓普查，零代码改动）

对 6 个对外服务 + 网关路由面全部对外 REST 端点逐点判定授权形态，实测 4 处「缺归属校验的 id 类端点」反例（均以 `app.auth.enabled=true` 为前提；默认 false 时全部端点裸放行为既有已知降级口径，本课题不改判）：

- **R1（高，位置 PII）**：`GET /record/api/records/{id}/points` → `SportRecordService.pagePoints`。仅查记录存在，未比对 `record.userId` 与 X-User-Id，任意登录用户可遍历 recordId 拉取**他人完整轨迹点（经纬度）**。
- **R2（高，越权状态变更）**：`POST /user/api/friends/requests/{id}/accept` → `FriendService.accept`。未断言调用者 == 申请 `to_user`，任意用户可替他人同意好友申请（建不期望关系、触发通知）。
- **R3（中）**：`POST /user/api/friends/requests/{id}/reject` → `FriendService.reject`，同 R2。
- **R4（中）**：`POST /record/api/records/{id}/appeal` → `SportRecordService.appeal`。`resolveUserId` 已解析出调用者，但**从未与 `record.userId` 比对**，任意用户可替他人 REJECTED 记录发起申诉。

正面范式（本课题照抄同构，不新造框架）：控制器层 `resolveUserId(request, claimed)`（FriendController/RecordController/LikeController/NotificationController/LeaderboardController 逐字同源，ADR-0007）；服务层按 `(id, user_id)` 双条件归属隔离（`NotificationService.markRead`）；治理面 ADMIN 断言（网关 admin.paths + GovernanceApiAuthFilter）。

## 方案（预注册）

统一范式：**归属校验仅在 `app.auth.enabled=true` 时生效**（对齐 ADR-0007 降级范式，false 时保持旧行为零扰动），延续既有 `resolveUserId` + 局部异常映射（1002→HTTP 403）。

- **R1（record-service）**：`RecordController.points` 增 `HttpServletRequest`，`Long caller = authEnabled ? resolveUserId(request, null) : null`，下传 `pagePoints(recordId, caller, page, size)`；`SportRecordService.pagePoints` 在 `record` 空判后增 `if (caller != null && !caller.equals(record.getUserId())) throw FORBIDDEN`。`listPoints`（内部 Feign 路径，无调用者上下文）**零改动**。
- **R4（record-service）**：`RecordController.appeal` 改传 `Long caller = authEnabled ? resolveUserId(request, dto.getUserId()) : null`；`SportRecordService.appeal` 在 `record` 空判后增同样比对。建申诉单仍以 `record.getUserId()` 为权威（不变）。
- **R2/R3（user-service）**：`FriendController.accept/reject` 增 `HttpServletRequest`，`Long caller = authEnabled ? resolveUserId(request, null) : null`，下传 `accept(id, caller)` / `reject(id, caller)`；`FriendService.accept/reject` 在申请空判后增 `if (caller != null && !caller.equals(request.getToUser())) throw FORBIDDEN`（同时天然阻断 from_user 自同意）。`InternalFriendController`（/internal/**，X-Internal-Token 网内信任，无调用者上下文）显式传 `null` 并 javadoc 注明。

## 验收核心

红绿判别式（纯 JVM 服务层单测）：
- 红：R1 caller≠记录归属人→403(1002)；R4 caller≠记录归属人→403；R2 caller≠to_user→403；R3 同 R2。
- 绿：caller==归属人正常返回/流转；caller==null 降级放行（旧行为）。

offline 638 基线（52/50/141/151/159/70/15）上 user-record 两模块只增不减、其余五模块逐位不变；token 2030 持平；零网关/common/api 契约/config/compose/脚本改动；`listPoints` 对外内部契约与行为不变。

## 不做

- 不新增统一鉴权框架/注解/拦截器；不建反向的 filter 层。
- 不改网关（资源归属校验属服务侧职责，网关不承担）。
- 不改 `/internal/**` 语义（既有 X-Internal-Token 网内信任面）。
- 不改 api 模块 Feign 契约（`listPoints` 不动；`pagePoints`/`appeal` 非契约面）。
- 不引入缓存/异步/new 依赖。