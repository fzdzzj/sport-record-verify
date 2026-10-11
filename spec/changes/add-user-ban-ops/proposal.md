# 提案：超管功能面补全——用户封禁/解封端点（TASK-203 add-user-ban-ops，含 N4 顺带）

## 侦察实证（2026-10-12）

- 课题3 原疑「申诉链路只有提交无处理」——**已证伪**：处理端点已存在（VerifyController:33 POST /api/appeals/{id}/review，治理面 ADMIN，服务侧 TASK-201 签名凭证二道防线已立），零改动。
- **实质缺口**：User.status 封禁消费侧完整（login:157-158 拒登录、refresh TASK-202 拒续签、README:202 口径），但**设置侧不存在**——全仓无 ban/unban 端点，status 仅注册时置 1（AuthService:118），封禁只能直改库，「紧急封禁」承诺无操作闭环。
- **N4**：web/README.md:154 仍存 ADMIN 授予「需重新登录」旧表述（TASK-202 复核登记，web/ 零触碰面致上课题未修）。

## 方案（预注册）

- **A（主项）**：`AuthService.banUser(Long userId)` / `unbanUser(Long userId)`（同构 grantAdmin:190-208/revokeAdmin:219-231 范式：null→IllegalArgumentException、不存在→USER_NOT_FOUND、已是目标态幂等跳过、改库 status=0/1）+ `InternalAuthController` POST `/internal/auth/ban`、`/internal/auth/unban`（复用 AdminGrantRequestDTO，X-Internal-Token 面既有）。封禁时效语义 javadoc 明确：login/refresh 即拒（TASK-202 双消费侧），存量 access ≤15min 自然过期（F12 口径），不吊销 refresh 键（续签校验已拦截，无 SCAN 必要）。
- **B（N4 顺带）**：web/README.md:154「需重新登录」→「重新登录或 refresh 续签后生效（最长延迟=access 剩余时效）」，与根 README TASK-202 订正口径对齐。
- **不做**：封禁连带吊销 refresh 键（续签校验已收敛）；内容治理（record 删除/审核，另立课题）；申诉流转（已存在）。

## 验收核心

R1 ban 不存在用户→USER_NOT_FOUND / R2 unban 不存在→USER_NOT_FOUND / G1 ban status=1→0 + 幂等（已是 0 跳过）/ G2 unban 0→1 + 幂等 / G3 封禁后 login/refresh 双拒语义引用（不重复实现，断言 ban 改库后 status 值即可）/ N4 web/README 无「需重新登录」残留。offline 633 基线上 user 136 净增、其余六模块逐位不变；token 2030；不涉网关/common/静态门/compose。
