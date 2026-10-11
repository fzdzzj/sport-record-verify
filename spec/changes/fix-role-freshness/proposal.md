# 提案：角色变更时效一致性与封禁续签缺口修复（TASK-202 fix-role-freshness）

## 背景（课题2立项侦察实证，2026-10-11）

- **预判反转**：候选课题2原假设「refresh 不带 role → 续签角色取数链路待实证」。实证结果：`issueTokenPair`（AuthService.java:282-283）**每次续签实时读库取 role**——降权/提权窗口本就收敛为 access TTL 15min（F12/TASK-193 已声明接受的口径），无「无限持权」问题。
- **缺口α（真缺陷，声明与实现不符）**：README.md:202 明文承诺「紧急封禁（user.status=0）拒新登录/刷新」，但 `refresh()` 主流程（:223-243）与 `issueTokenPair`（:279-296）**均不校验 status**——封禁用户的 refresh 存活期内（默认 7 天）可无限续签 access，仅 login（:157-158）有 status 校验。封禁被续签路径绕过。
- **缺口β（文档不符）**：grantAdmin javadoc（:188）称「生效需重新登录」——不准确，refresh 续签即生效（issueTokenPair 实时读库）。
- **缺口γ（操作面）**：仅有 grantAdmin 授予端点（InternalAuthController:57），无撤销端点——降权只能直改库，「角色变更时效」缺乏操作闭环。

## 方案（预注册）

- **A1（主修复）**：`refresh()` 在原子消费旧 jti 之后、`issueTokenPair` 之前查库校验：用户不存在或 status=0 → 401「账号已被禁用」（旧 jti 已被 GETDEL 消费，封禁即强制下线闭环；不泄漏封禁与不存在差异可从简，统一 401 口径对齐 login 防探测精神）。
- **A2（文档修正）**：grantAdmin javadoc 与 README 角色时效表述改为「重新登录或 refresh 续签后生效（最长延迟=access 剩余时效，主动续签即时）」。
- **A3（操作闭环）**：`AuthService.revokeAdmin`（同构 grantAdmin：存在性校验、已是 USER 幂等跳过、改库 role=USER）+ `InternalAuthController` POST `/internal/auth/revoke-admin`（复用 AdminGrantRequestDTO，X-Internal-Token 保护面既有）。时效语义：撤销后旧 access ≤15min 自然过期（F12 口径），续签即时降权。
- **不做**：access jti 黑名单（TASK-193 已裁定不采用，不重开）；网关侧实时查角色（新增网关→user 故障面，被否先例）；撤销时 SCAN 吊销全部 refresh 键（续签读库已收敛，无必要）。

## 验收核心

R1 status=0 合法 refresh → 401 且重放同 refresh → 401（jti 已消费）/ R2 库中用户已删 → 401 / R3 revokeAdmin 不存在用户 → USER_NOT_FOUND / G1 正常用户轮换不回归 / G2 revokeAdmin 幂等 + 撤销后续签返回 USER / G3 grantAdmin 后立即续签返回 ADMIN。offline 627 基线上 user 模块只增不减（130→净增，其余六模块逐位不变）；token 2030 持平；契约门双态；不涉 compose/static 双模块（user 不在静态门）。
