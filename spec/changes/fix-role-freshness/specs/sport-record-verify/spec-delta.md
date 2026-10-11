# spec-delta：角色变更时效一致性与封禁续签缺口（TASK-202 fix-role-freshness）

## MODIFIED 需求：refresh 轮换的封禁与存续校验（user-service AuthService.refresh）

refresh 主流程在原子消费旧 jti（GETDEL）之后、签发新 token 对之前 SHALL 查库校验用户状态：用户不存在或 status=0 → 401（1001 语义，消息「账号已被禁用」或统一无效口径），旧 jti 保持已消费（重放同 refresh 亦 401）——封禁/删号在续签路径强制下线，对齐 README「紧急封禁拒新登录/刷新」承诺与 login 既有 status 校验。status=1 用户轮换语义（原子消费/新对签发/存活键 TTL）SHALL 等价保持。

## MODIFIED 需求：grantAdmin 生效口径文档修正（javadoc/README）

grantAdmin javadoc 与 README 角色时效表述 SHALL 修正为：角色变更（授予/撤销）在**重新登录或 refresh 续签**后写入新 access 的 role claim，最长延迟=当前 access 剩余时效，主动续签即时生效；access 15min 无状态窗口为声明接受权衡（维持 F12 口径不变）。

## ADDED 需求：revokeAdmin 撤销端点（user-service）

AuthService SHALL 新增 `revokeAdmin(Long userId)`：目标不存在 → USER_NOT_FOUND；已是 USER → 幂等跳过；否则改库 role=USER。InternalAuthController SHALL 新增 POST `/internal/auth/revoke-admin`（请求体复用 AdminGrantRequestDTO，X-Internal-Token 服务侧保护面既有，同构 grantAdmin 端点范式）。撤销后时效：旧 access ≤15min 自然过期，续签即时降权（issueTokenPair 实时读库，无需吊销 refresh 键）。

## 验收断言

R1/R2/R3 红、G1/G2/G3 绿（见 proposal 验收核心与任务书 §2.5）；offline user 模块 130 基线只增不减、其余六模块逐位不变；README 修正后无「需重新登录才生效」残留表述；零网关/common/配置改动。
