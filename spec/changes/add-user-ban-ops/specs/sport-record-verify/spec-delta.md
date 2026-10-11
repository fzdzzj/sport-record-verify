# spec-delta：用户封禁/解封管理端点（TASK-203 add-user-ban-ops）

## ADDED 需求：banUser/unbanUser 管理操作（user-service）

AuthService SHALL 新增 `banUser(Long userId)` 与 `unbanUser(Long userId)`：null → IllegalArgumentException；目标不存在 → USER_NOT_FOUND；已是目标状态（status=0 / status=1）→ log 幂等跳过；否则改库 status。InternalAuthController SHALL 新增 POST `/internal/auth/ban` 与 `/internal/auth/unban`（复用 AdminGrantRequestDTO，同构 grantAdmin 端点范式，/internal/** X-Internal-Token 保护面既有）。javadoc SHALL 注明时效语义：封禁后 login/refresh 即拒（双消费侧既有），存量 access ≤15min 自然过期，不吊销 refresh 键。

## MODIFIED 需求：web/README 角色时效表述（N4）

web/README.md ADMIN 授予段 SHALL 与根 README TASK-202 订正口径对齐：「重新登录或 refresh 续签后生效（最长延迟=access 剩余时效）」，消除「需重新登录」单义旧表述。

## 验收断言

R1/R2 红、G1-G3 绿（见 proposal 与任务书 §2.5）；offline user 模块 136 基线只增不减、其余六模块逐位不变；申诉 review 端点零改动（侦察已证存在）；零网关/common/配置/compose 改动。
