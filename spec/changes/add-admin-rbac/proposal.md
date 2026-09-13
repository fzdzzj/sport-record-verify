# 提案：管理端鉴权与 RBAC 收口（治理面安全闭环）

## Why

JWT 鉴权闭环（add-jwt-auth）已覆盖业务面，但**治理面完全裸奔**：网关 `AuthGlobalFilter` 白名单直接放行 `/admin/**`，`RuleVersionController`（`/verify/rules/versions`）也注明「暂不接鉴权（项目骨架无 JWT）」。结果：

- 任何调用方可调 `/admin/**` 对申诉做**终判驳回/改判**——能推翻整个校验结论；
- 任何调用方可调规则版本接口把灰度比例、阈值、版本状态改掉——能篡改校验引擎本身。

这是比登录爆破更严重的安全漏洞：后者只影响单账号，前者能**改规则 + 翻案**。业务面认身份、治理面裸奔，是「鉴权只做了一半」的典型不一致，面试被追问「管理接口怎么保护」会直接露馅。

**当前状态**：`AuthGlobalFilter` 白名单含 `/admin/**`；`RuleVersionController` 无鉴权；角色体系不存在（User 无 role 字段）。

**期望状态**：管理端接口要求「管理员角色」，普通用户 token 访问返回 403；引入最小角色模型（USER/ADMIN），鉴权过滤器同时校验「已登录 + 角色足够」；`RuleVersionController` 与 `/admin/**` 一并纳入。

## What Changes

- **角色模型**：User 增 `role` 字段（或独立 user_role），默认 USER；管理员通过注册后手工/内部接口授予 ADMIN。
- **令牌携带角色**：JwtUtil 签发时写入 `role` claim；网关过滤器解析出 role。
- **治理面鉴权**：`/admin/**` 与规则版本接口要求 `role=ADMIN`，普通用户返回 403（1002）；未登录返回 401（1001）。
- **白名单收紧**：从 `AuthGlobalFilter` 白名单移除 `/admin/**` 的裸放行，改为「放行到过滤链但校验角色」。
- **内部接口隔离**：`/internal/**` 维持网内信任（不对公网暴露），但补一条「内网接口不改判业务」的约定（终判只走 `/admin/**`）。
- **可配开关**：`app.auth.admin.*`（是否启用角色校验），默认启用，保留灰度观察。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加治理面鉴权能力域（ADDED）：角色模型、管理端鉴权、越权防护。

### 受影响的代码
- `user-service`：User 实体 +role、JwtUtil 角色 claim、授予管理员的内部接口
- `gateway-service`：AuthGlobalFilter 角色校验、白名单收紧
- `verify-service`：RuleVersionController 加鉴权注解/校验
- `api`：TokenDTO 可能带 role、新增 AdminGrantDTO

### 用户影响
- 普通用户无法再碰管理接口（安全增强）；管理员需显式授予。

### API 变更
- 无破坏性业务端点变更；管理接口从「裸奔」变「需管理员 token」。

### 需要迁移
- [x] 数据库迁移（user 表 +role 字段，或新建 user_role 表）
- [x] API 版本提升（治理面鉴权语义）
- [ ] 用户沟通
- [x] 文档更新（README/速览/ADR-0007 补治理面鉴权）

## 时间线评估

较小：约 0.5-1 周（聚焦角色字段 + 网关过滤器 + 管理接口）。

## 风险

- **管理员种子怎么给**（首个 admin 如何产生）→ 缓解：注册默认 USER，提供内部/本地脚本授予首个 ADMIN；面试讲「运维初始化 + 最小权限」。
- **角色 claim 被伪造** → 缓解：角色由网关注入（同 X-User-Id 机制），下游不信任外部传入 role；token 签名防篡改。
- **过度设计 RBAC**（多角色多权限矩阵）→ 缓解：只做 USER/ADMIN 二态，够用且诚实；面试明确「角色模型刻意最简，续可用 Spring Security ACL」。
- **现有测试/演示脚本用无角色 token 调管理接口** → 缓解：授予管理员 token 供演示脚本，普通脚本不受影响（业务面不变）。

## 备注

- 这是「治理面 vs 业务面」安全边界收口，补上「鉴权只做了业务面」的不一致。
- 核心面试弹药：为什么管理端要独立鉴权、最小权限、角色不放大到业务数据。