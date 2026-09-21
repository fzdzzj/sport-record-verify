# 提案：Web 控制台登录会话（注册/登录/刷新/路由守卫）

## Why

脚手架只解决「能起前端」。没有会话，页面只能裸调接口或依赖 `app.auth.enabled=false` 的旧「自报 userId」行为，演示不出网关鉴权。

本变更只做认证会话，不做记录提交或管理端。

**背景**：
- 注册 `POST /api/auth/register` 成功不发 token，需再登录。
- 登录 `POST /api/auth/login` 返回 accessToken、refreshToken、role、expiresIn；凭据错误 HTTP 401 + code 1001；锁定 403/1002。
- 刷新 `POST /api/auth/refresh` 轮换 refresh。
- 业务接口在 `app.auth.enabled=true` 时要求 `Authorization: Bearer`；网关注入 `X-User-Id`/`X-Role`，前端不得伪造这两头。
- 网关默认 `app.auth.enabled=false` 是为压测脚本。前端演示必须在文档中要求本地打开鉴权，本变更不改仓库默认值。

**当前状态**：无登录页逻辑；token 不落地。

**期望状态**：可注册、登录、刷新、退出；access 过期用 refresh 换新；401 清会话回登录；ADMIN/USER 角色仅用于后续管理端入口显示，本变更不实现管理页。

## What Changes

- 登录/注册页；本地存储 access/refresh/role（勿用参考工程的单 `token` 头）。
- Axios 请求拦截器加 `Authorization: Bearer <access>`；响应 401 尝试 refresh 一次，失败回登录。
- 路由守卫：未登录不可进控制台布局。
- 文档：演示前端时网关与各服务 `app.auth.enabled=true`（环境变量或本地配置），压测脚本仍可用默认 false。
- 不改 Java 鉴权实现。

**明确不做**：记录/好友/榜单页；管理端；CORS；改鉴权默认值。

## Impact

### 受影响的规范
- ADDED「Web 控制台登录会话」。

### 受影响的代码
- `web/` 认证页、token 工具、路由守卫、axios 拦截器；README 演示鉴权开关说明。

### 用户影响
- 可用手机号注册登录进入空壳控制台。

### API 变更
- 无新后端接口。

### 需要迁移
- [x] 文档更新（演示须开鉴权）

## 时间线评估

小：约 0.5 天。

## 风险

- **默认鉴权仍关闭导致「不带 token 也能进业务」** → 缓解：文档强制演示开启；页面仍始终带 Bearer，关闭鉴权时多带的头可被忽略。
- **refresh 死循环** → 缓解：401 只刷新一次，刷新接口本身 401 则退出。
- **把 role 当权限真相** → 缓解：前端只做入口隐藏；真正拒绝仍靠网关 403。
