# 提案：新增 JWT 鉴权闭环（注册/登录 + 网关鉴权 + 数据隔离）

## Why

项目目前所有服务**无任何认证鉴权**——6+ 处代码注释明确写着「骨架无认证鉴权，userId 由调用方显式携带，接 JWT 后改从 token 解析」。这意味着任何调用方可以伪造任意 userId，越权访问他人记录、点赞、好友、榜单。这是安全边界的硬缺口，也是大厂面试「鉴权怎么做」「越权怎么防」的必问点。执行计划 W2 就列了「注册/登录/JWT + 网关鉴权过滤器」但一直未落地。

**背景**：
- 审批版 §4.1 已定：注册（BCrypt、手机号唯一、重复→2001）、登录（access+refresh token、错密码→401 并计失败）。
- 错误码 1001（token 无效/过期）、1002（无权限/越权）已在 `ResultCode` 定义好但无代码使用。
- User 表已有 `password_hash`/`phone` 唯一/`status`，且 `InternalUserController` 已有手机号脱敏惯例——数据基础就绪。
- 副项目 life-habit-assistant 已实践 JWT refresh-token rotation，经验可复用。

**当前状态**：user-service 无注册/登录/Auth 端点、无 JWT 工具；gateway 无鉴权过滤器；所有业务 controller 靠「调用方显式携带 userId」这个骨架约定。

**期望状态**：注册/登录签发 JWT → 网关统一校验并解析 userId 透传下游 → 业务侧从 token 解出真实 userId（不再信任调用方传入）→ 越权访问返回 403（1002）。形成完整鉴权闭环。

## What Changes

- **user-service 认证域**：新增 `POST /api/auth/register`（BCrypt、手机号唯一，重复→2001）、`POST /api/auth/login`（校验密码，错密码→401 并计失败次数）、`POST /api/auth/refresh`（refresh token 换新 access）。
- **JWT 工具**：`JwtUtil`（签发/校验/解析 userId），HS256；access token 短时效（如 15min）、refresh token 长时效（如 7d）+ rotation（刷新后旧 refresh 作废）。
- **网关鉴权过滤器**：gateway-service 新增 GlobalFilter，校验 `Authorization: Bearer <token>`，失败返回 401（1001）；通过后解析 userId 写入请求头（如 `X-User-Id`）透传下游；白名单放行 `/api/auth/**`、内部端点 `/internal/**`、`/actuator/**`。
- **数据隔离改造**：各业务 controller 的 userId 改从网关透传的 `X-User-Id` 读取（不再信任请求体/参数传入的 userId）；越权访问他人资源返回 403（1002）。
- **降级兼容**：为兼容无 JWT 的本地调试/压测脚本，保留一个可配置开关（如 `auth.enabled=false` 时降级为显式携带 userId 的旧行为），默认关闭降低迁移成本。
- **api 契约**：`AuthApi`（注册/登录/刷新）+ `LoginRequestDTO`/`TokenDTO`/`RegisterRequestDTO`。
- **common**：复用 1001/1002 错误码，无新增。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加鉴定能力域需求（ADDED）：注册/登录、JWT 签发、网关鉴权、数据隔离、越权防护。

### 受影响的代码
- `user-service`：+AuthController、+UserService（注册/登录/刷新）、+JwtUtil、+refresh token 存储
- `gateway-service`：+鉴权 GlobalFilter、+白名单配置
- `record/verify/leaderboard` 各 controller：userId 来源改造（从 X-User-Id 读）
- `api`：+AuthApi、+TokenDTO 等
- `common`：无改动（1001/1002 已存在）

### 用户影响
- 用户需先注册/登录拿 token 才能调用业务接口（安全增强）；无 token 访问受保护接口返回 401。

### API 变更
- 新增 `/api/auth/register`、`/api/auth/login`、`/api/auth/refresh`。
- **破坏性变更**：受保护接口从「调用方携带 userId」改为「网关从 token 注入 userId」，旧的直接传 userId 调用将失效（除非 auth.enabled=false）。

### 需要迁移
- [x] 数据库迁移（可能新增 refresh_token 表或复用 Redis 存 refresh，二选一）
- [x] API 版本提升（鉴权语义破坏性变更）
- [ ] 用户沟通
- [x] 文档更新（README、速览手册、ADR 鉴权选型）

## 时间线评估

中等：约 1 周（P0 安全边界，优先级高于 P2 功能项）。

## 风险

- **接入即破坏现有压测/演示脚本** → 缓解：auth.enabled 开关，默认 false 先落地代码，验证通过后再切 true；切换后更新压测脚本带 token。
- **refresh token 存储选型**（Redis vs DB）→ 缓解：默认 Redis 存 refresh（可设 TTL、易作废），数据库落法作为可选；面试讲「Redis 存 refresh + rotation 防重放」。
- **网关透传 userId 被伪造**（下游直接信任 X-User-Id 头）→ 缓解：仅网关注入该头、下游不信任外部传入同名头（覆盖或校验来源）；面试讲「内网信任边界 + 网关唯一入口」。
- **token 过期刷新的并发**（并发 refresh 导致旧 refresh 误作废）→ 缓解：rotation 用「新旧 token 短暂共存」或原子替换，复用 life-habit-assistant 已验证做法。
- **技术卡壳超时**（既定决策）→ 缓解：本项是安全边界，不可砍；若刷壳复杂，先「access token + 网关鉴权」跑通，refresh rotation 作为进阶补充。

## 备注

- 这是把「骨架无认证」这一横跨多服务的技术债一次性收口的提案，锚点是全项目 6+ 处「接 JWT 后改从 token 解析」注释。
- refresh token rotation 复用副项目已验证经验，面试可用「两项目同一套 JWT 设计哲学」串联。
- 越权 403（1002）是本提案核心价值：从「谁调用谁自报家门」升级为「网关统一认定身份」。