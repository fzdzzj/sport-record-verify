## Proposal: fix-web-router-boot (TASK-228, web 启动级路由缺陷修复)

### 缺陷
web/src/main.ts 从 `vue-router/auto` 取 createRouter 且未传 routes；仓内锁定 unplugin-vue-router@0.19.2 的 exports 无 `./auto`（只有 `vue-router/auto-routes`）→ 运行时 createRouterMatcher(undefined) 抛错，dev 与 production 均无法挂载。既有 shim 声明（web/src/vue-router-auto-shim.d.ts 的 `Omit<RouterOptions,'routes'>`）掩盖了类型检查。test/type-check/build 均不挂载应用，故从未暴露。

### 修复（恰 2 文件）
- web/src/main.ts：createRouter 显式传 routes（从 auto-routes 或等价导入）。
- web/src/vue-router-auto-shim.d.ts：删除掩盖缺陷的声明，改为 vue-router/auto-routes 的 routes 值声明。

### 验证
执行侧已在临时修复下实测 E2E 12/12 绿 + type-check/test/build 全绿（证据 .trae/tmp/t227-preserved/）。Non-Goal：vite proxy 误伤 preview 与 a-alert 插槽两发现（H4 候选）。
