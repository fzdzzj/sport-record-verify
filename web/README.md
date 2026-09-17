# Web 控制台脚手架

Vue 3 + Vite + TypeScript + Ant Design Vue 4 + Tailwind 4 + Axios + @tanstack/vue-query + vue-router + unplugin-vue-router（基于 `.page.vue` 文件路由）。

## 启动

```bash
cd web
pnpm install
pnpm dev
```

默认端口 5173。浏览器访问 http://localhost:5173

开发代理已配置，将 `/api`、`/user`、`/record`、`/leaderboard`、`/verify`、`/admin`、`/mapmatch` 原样转发到 `http://127.0.0.1:8080`（changeOrigin=true，无 rewrite 剥前缀）。

## 认证会话（add-web-auth-session）

- 注册页：`/register` ，POST /api/auth/register ，成功不返回 token，提示去登录；重复手机号 2001。
- 登录页：`/login` ，POST /api/auth/login ，成功保存 accessToken/refreshToken/role（分别存储），进入控制台。
- 401 单次 refresh：业务请求 401 时，用 refreshToken 调用 /api/auth/refresh 一次，成功更新 token 并重试；失败清存储跳登录（防死循环）。
- 路由守卫：无 accessToken 不能进入控制台（/ 等）；已登录访问 /login 自动跳转控制台；退出清 localStorage。
- 请求头：仅 `Authorization: Bearer <accessToken>` ；**不发送** headers.token、X-User-Id、X-Role（由网关注入）。

**演示须开启鉴权**：
- 启动网关及各服务（user-service 等）时，**必须设置 `app.auth.enabled=true`**（可通过环境变量、启动参数或本地 yml 覆盖）。
- 仓库默认配置仍为 `false`（兼容压测脚本裸调用），**本变更及前端演示文档均不修改任何 Java 默认配置文件**。
- 开启后，前端带 Bearer 的请求会被网关校验；关闭时多带头可被忽略（向下兼容）。
- 登录/注册/刷新走白名单 `/api/auth/**` ，不依赖鉴权开关。

## 探活

- 登录页：真实对接登录。
- 探活页：点击按钮调用 `GET /leaderboard/api/leaderboard?type=overall&size=2`，展示 `{code, message}` 解析结果。

Axios 统一响应处理：`code === 0` 成功，否则抛出 `message`。

## 注意

- 仅脚手架。业务功能（记录提交、榜单、管理端入口等）在后续独立变更中实现。本变更仅实现认证会话与守卫。
- 后端网关需运行在 8080 端口以测试代理。
- 包管理：pnpm。
- 不包含 Vuex / ECharts / OpenAPI 生成 / Playwright。
- 参考技术栈选型来自同类工程，但代理与响应码严格按本仓规范（无 rewrite、code=0）。

**口径自检**：已按 spec-delta 实现；未改 Java app.auth.enabled 默认；token 分别存储；仅 Bearer；README 强调演示开启鉴权。
