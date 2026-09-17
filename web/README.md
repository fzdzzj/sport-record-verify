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

## 探活

- 登录页：仅占位，无真实登录。
- 探活页：点击按钮调用 `GET /leaderboard/api/leaderboard?type=overall&size=2`，展示 `{code, message}` 解析结果。

Axios 统一响应处理：`code === 0` 成功，否则抛出 `message`。

## 注意

- 仅脚手架。业务功能（登录、提交、榜单、管理）在后续独立变更中实现。
- 后端网关需运行在 8080 端口以测试代理。
- 包管理：pnpm。
- 不包含 Vuex / ECharts / OpenAPI 生成 / Playwright。

参考技术栈选型来自同类工程，但代理与响应码严格按本仓规范（无 rewrite、code=0）。
