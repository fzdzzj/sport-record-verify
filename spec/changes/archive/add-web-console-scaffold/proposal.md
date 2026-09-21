# 提案：Web 控制台工程脚手架（Vue3 + Vite）

## Why

后端 6 服务已可经网关演示，但仓库没有浏览器端。录屏只能靠 curl / 脚本，无法展示登录、提交、判定、榜单的完整操作面。

本变更只搭工程骨架，不实现业务页。业务页、鉴权会话、管理端、网关 CORS 分独立变更，避免一次把技术选型、登录、业务、治理面混在一起。

**背景**：
- 既有前端工程采用 Vue 3 + Vite + TypeScript + 基于页面文件的路由 + Ant Design Vue + Tailwind + Axios + TanStack Vue Query。
- 本仓库网关路径是分前缀的：`/api/auth/**`、`/user/**`、`/record/**`、`/leaderboard/**`、`/verify/**`、`/admin/**`、`/mapmatch/**`。不能照搬「把 `/api` 前缀剥掉再转发」的代理。
- 本仓库统一响应是 `{code, message, data}`，成功 `code=0`。不能照搬成功码为 1、请求头叫 `token` 的客户端约定。
- 仓库当前无 OpenAPI 描述文件，本变更不引入代码生成客户端。

**当前状态**：无 `web/` 目录；演示走 curl 与 `scripts/smoke/`。

**期望状态**：`web/` 可 `pnpm dev` 起 Vite；开发代理把浏览器请求原样转到 `http://127.0.0.1:8080`；有空壳布局与健康探活页；CI 不强制前端门槛（本变更不改 Java CI）。

## What Changes

- 新增 `web/`：Vue 3.5 + Vite + TypeScript + `unplugin-vue-router`（`.page.vue`）+ Ant Design Vue 4 + Tailwind 4 + Axios + Vue Query。
- 包管理器：pnpm（与参考工程一致）。
- Vite `server.proxy`：**不 rewrite、不 StripPrefix**，按网关前缀转发 `/api`、`/user`、`/record`、`/leaderboard`、`/verify`、`/admin`、`/mapmatch`。
- Axios 封装：成功判断 `body.code === 0`；业务失败抛错并展示 `message`；不把 CRM 的 `code===1` / `headers.token` 拷进来。
- 页面：登录占位（仅路由，无真实登录逻辑——登录属下一变更）、探活页（调网关已有内部/健康接口以外的公开探活，例如榜单或登录页本身不强制调 internal）。
- README / 速览补「前端目录与启动」一句。
- 不改 Java、不改网关鉴权默认值、不加 CORS（属独立变更）。

**明确不做**：
- 不实现注册/登录/refresh（`add-web-auth-session`）。
- 不实现记录提交/判定/好友/点赞/榜单页（`add-web-record-console`）。
- 不实现规则灰度/申诉终判管理页（`add-web-admin-console`）。
- 不改 `app.auth.enabled` 默认 false。
- 不引入 Vuex、ECharts、OpenAPI codegen、Playwright（后续可另开）。
- 不把参考工程的源码复制进本仓。

## Impact

### 受影响的规范
- ADDED「Web 控制台工程脚手架」。

### 受影响的代码
- 新增 `web/`；根 README / 速览手册目录说明。

### 用户影响
- 开发者可单独启动前端开发服务器；无业务能力。

### API 变更
- 无。

### 需要迁移
- [x] 文档更新

## 时间线评估

小到中：约 0.5–1 天。

## 风险

- **代理误 StripPrefix** → 缓解：提案写死不 rewrite；用登录接口或榜单探活验证路径到达网关。
- **把参考工程成功码/请求头拷进来** → 缓解：本仓 `code=0`、`Authorization: Bearer`（Bearer 头在下一变更才强制使用）。
- **一次做完全部页面** → 缓解：本变更只脚手架。
