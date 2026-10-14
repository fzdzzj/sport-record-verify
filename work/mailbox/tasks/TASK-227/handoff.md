# TASK-227 STOP 报告：E2E 真实浏览器验证批（S3.3 触发，未完成）

> 本文件是**停止上报**（任务书 §3 停止条件 3 触发），不是交付回传：本课题**零改动提交**，C-01/C-02 均未形成，tasks.json 未勾选、spec.md 未回填 §7。E2E 套件已写好并在临时修复下实测全绿，但按 S0 白名单不得入库（见 §4/§5）。

## 1. 结论

- **触发条件**：S3.3「需改 vite.config/路由/后端才能跑通 → 停止上报」——**已触发**。
- **阻断根因（预存在缺陷，非本课题引入）**：`web/src/main.ts` 第 3 行 `import { createRouter } from 'vue-router/auto'` + 第 10 行 `createRouter({ history: createWebHistory() })` **没有 routes**；而仓内锁定的 `unplugin-vue-router@0.19.2` **已不再提供 `vue-router/auto` 别名**（改为提供 `vue-router/auto-routes` 虚拟模块，见 §2.2），故 `vue-router/auto` 落到 vue-router 4.6.4 自身的空占位子路径（只 `export *`，无路由注入）。结果：运行时 `createRouterMatcher(undefined)` 抛 `TypeError: Cannot read properties of undefined (reading 'forEach')`，**应用在任何浏览器下都无法挂载**（dev 与 production build 两种形态均复现）。
- **为何此前所有门槛未发现**：`pnpm test`（vitest 只测工具/拦截器）、`pnpm type-check`（被 `src/vue-router-auto-shim.d.ts` 的类型声明掩盖，该垫片把 `createRouter` 声明为 `Omit<RouterOptions,'routes'>`，等于把"插件会自动注入 routes"的过时假设写进了类型）、`pnpm build`（编译产物存在即可）三者都不执行应用本体；而 TASK-213/218/219/220/221/222/223/224/225 连续九课题均登记「真实浏览器交互未跑」。**本课题的真实浏览器路线首次运行即暴露该启动级缺陷**——这正是本批要还的债。
- **可修复性（已实测）**：临时把 routes 显式传入后，应用正常启动，**已写好的 12 条 E2E 全绿（12 passed, rc=0）**（§3）。但修复落在 `web/src/main.ts` 与类型声明面，**均在本课题 S0/S4 白名单之外**（白名单仅 `web/package.json`+`pnpm-lock.yaml`+`web/e2e/**`+`web/.gitignore`+`README`+台账 4 文件），按 S3.3 停止上报，不越界改码。

## 2. 证据链（逐条可复现）

1. **依赖版本 = 锁定版本（非本机漂移）**：`web/pnpm-lock.yaml`（基线 11364af）记 `unplugin-vue-router@0.19.2`、`vue-router@4.6.4`；本机安装目录名同为 `unplugin-vue-router@0.19.2...`、`pnpm list` 显示 `vue-router 4.6.4`。基线 `git diff 11364af -- web/src vite.config.ts web/tsconfig.app.json` 为空——**源码与配置与基线逐字节一致，缺陷预存在**。
2. **插件能力面**：`unplugin-vue-router@0.19.2` 的 `package.json#exports` **无 `./auto` 入口**；其 dist 内常量 `MODULE_ROUTES_PATH = "vue-router/auto-routes"`（另有 `auto-resolver`、`auto/route-block` 虚拟模块），全包检索**无 `vue-router/auto` 精确别名**。即：新版插件只提供「显式 routes 模块」，不再注入 `createRouter`。
3. **dev 形态实证**：dev server 转换后的 `/src/main.ts` 为 `import { createRouter } from "/node_modules/.vite/deps/vue-router_auto.js"` + `const router = createRouter({ history: createWebHistory() });`（无 routes 参数、无别名替换）；该 dep 文件内容即 vue-router 的 `export *` 空占位。
4. **运行时实证**：Chromium 打开应用 → `pageerror: TypeError: Cannot read properties of undefined (reading 'forEach')`，栈为 `createRouterMatcher → createRouter → /src/main.ts:10:16`（production bundle 内为 `A7 → V7 → entry`，`#app` 内 HTML 长度 0 = 未挂载）。三种场景（无 mock 无种子 / 仅 mock / 仅种子）**同一错误**，排除 route-mock 与本课题脚手架因素。
5. **vite 代理旁证（附带发现，登记不修）**：`vite.config.ts#server.proxy` 的 `/record`、`/verify`、`/admin` 等前缀规则**同时作用于 dev 与 preview**（Vite 7 `preview.proxy` 缺省继承 `server.proxy`），故整页直载 `/record-submit`、`/record-detail`、`/admin*` 等路由会被转发到网关（无后端时 500 / `ECONNREFUSED 127.0.0.1:8080`）。套件已改用顶栏客户端导航覆盖这些页面（真实用户路径），并把该现象写入套件注释。
6. **附带发现（登记不修）**：`record-submit` 页 `a-alert` 的默认插槽内容（`status:`/`duplicated:` 行）在 antdv 4.2.6 下不透出，仅 `#message`（`提交结果 (code=0)`）渲染；`recordId` 实际由「本会话记录」列表承载。套件断言按真实渲染面改写。

## 3. 临时修复与套件实测（验证性，未入库）

- 临时改动（**仅本地验证，已还原**）：`web/src/main.ts` 改为 `import { createRouter } from 'vue-router'` + `import { routes } from 'vue-router/auto-routes'` + `createRouter({ history: createWebHistory(), routes })`。
- 实测：应用正常挂载；**12/12 E2E 全绿**（`12 passed (39.4s)`，rc=0，chromium 单浏览器，trace 12 份落 `.trae/tmp/e2e-results/`）。
- 逐条红绿（临时修复下）：① 登录跳转（未登录拦回 /login；已登录访问 /login 自动跳首页）绿；② 记录列表渲染（统计卡 记录数 25 / 总里程 157.00 / 通过率 25.0% / 连续打卡「当前 5 天 · 最长 12 天」+ 20 行表格 + 可见性标签 + 分页器）绿；③ 提交表单校验与提交（清空→按钮禁用且零请求；加载样例→可提交；提交→POST 载荷 sportType=2/10 点 + 结果 message + 会话列表 recordId 9001）绿；④ 筛选联动+重置页码（翻页 page=2 → 选跑步 page 回落 1 且 9 行 → 重置回 20 行且无过滤参数）绿；⑤ 删除确认与刷新（Popconfirm 取消零 DELETE；确认后 DELETE 发出、首行 1000→1001）绿；⑥ feed 卡片与分页（20 卡片渲染 → 第 2 页 跑友20/21）绿；⑦ 目标卡片与对话框（周/月标签、进度条 ×2、已达成；设置目标 upsert → 3 条；编辑 50→60；Popconfirm 删除 → 1 条）绿；⑧ 顶栏导航（提交/判定/好友/动态/通知/探活/注册/登录守卫/退出清 token 全部断言通过）绿。
- **修复不是「两行」**：临时改动虽能让运行时跑通，但 `pnpm type-check` 实测 **rc=2 / `src/main.ts(4,10): error TS2305: Module '"vue-router/auto-routes"' has no exported member 'routes'`**——生成的 `src/typed-router.d.ts` 对 `vue-router/auto-routes` 只声明了 `RouteNamedMap` 类型，无 `routes` 值导出；且 `src/vue-router-auto-shim.d.ts` 仍在为过时的 `vue-router/auto` 背书。故完整修复至少涉及 `web/src/main.ts` + 类型声明面（垫片或 tsconfig `types`/插件 dts 配置），甚至可能需要 `vite.config.ts`——**全部越界**（S0/S4 白名单外），强化 S3.3 判定。

## 4. 提交边界与保留件（本课题未提交任何文件）

- **只改清单（本课题）**：**零改动**——不提交 `web/package.json`、`web/pnpm-lock.yaml`、`web/e2e/**`、`web/.gitignore`、`README` 任何一项；`web/` 工作树已还原到基线态（`git status` 干净）。本停止报告是唯一新增文件。
- **保留件（均在 gitignored `.trae/tmp/`，未入库）**：
  - `.trae/tmp/t227-preserved/e2e/`：完整套件（`playwright.config.ts` + `helpers.ts` + `fixtures/` 4 文件 + 8 条 `*.e2e.ts`，含本轮全部修正）。
  - `.trae/tmp/t227-preserved/tracked-changes.patch`：`web/package.json`（`@playwright/test` **1.64.0** 精确锁定 + `scripts.e2e`）、`web/pnpm-lock.yaml`（+28 行）、`web/README.md`（E2E 小节 + 过时 Playwright 表述订正）三文件的完整 diff。
  - `.trae/tmp/t227-preserved/routes-fix.patch`：临时 routes 修复 diff（验证用）。
  - `.trae/tmp/t227-preserved/web.gitignore`：`test-results/`、`playwright-report/`、`blob-report/` 忽略项（证据目录统一落 `.trae/tmp`）。
  - `.trae/tmp/t227-preserved/e2e-final-12of12-green.log`：12/12 全绿运行日志；`.trae/tmp/e2e-results/**/trace.zip`：12 份 trace；`.trae/tmp/debug*.log`：根因定位全过程日志。
  - 恢复方式（后续授权后）：`Copy-Item .trae/tmp/t227-preserved/e2e web/e2e -Recurse` + `git apply .trae/tmp/t227-preserved/tracked-changes.patch` + `git apply .trae/tmp/t227-preserved/routes-fix.patch` + `pnpm --dir web install`。
- **环境留痕**：Playwright 1.64.0 + chromium（浏览器二进制落 `%LOCALAPPDATA%\ms-playwright`，不入库）；安装与下载**未被网络阻断**（S3.1 未触发）。

## 5. 建议后续（供指导侧裁决）

1. **新开一个路由启动修复课题**（非本批）：修 `web/src/main.ts` 用 `vue-router/auto-routes` 的 `routes`，并同步类型面（垫片/tsconfig/插件 dts）；建议同时把「应用可挂载」纳入门槛（例如该课题自带一条冒烟 E2E），修完再撤销/更新过时的 `vue-router-auto-shim.d.ts` 说明。
2. **随后重派 TASK-227**：直接取用 `.trae/tmp/t227-preserved/`（套件在修复下已实测全绿），仅需按当时的端口/形态微调 `playwright.config.ts`。
3. **两处附带发现**（vite preview 继承 server.proxy 导致深链直载被代理；`a-alert` 默认插槽不透出）登记为候选小课题，不属本批红线内。

## 6. 停止条件核对

- §3.1（Playwright 安装/chromium 下载被网络阻断且无镜像）：**未触发**——安装与二进制下载均成功。
- §3.2（route-mock 无法承载某交互需真实后端）：**部分相关但非主因**——两前缀 route-mock 实际可承载全部 8 条交互（12 条用例实测全绿），仅「榜单」页交互按两前缀边界未覆盖（`/leaderboard/api/*` 不在 mock 范围，登记未覆盖，不引入后端）。
- §3.3（需改 vite.config/路由/后端才能跑通）：**已触发——即本次停止原因**（应用启动级路由缺陷，修复面越出 S0/S4 白名单）。

## 7. 补记（完整修复候选已实测，仍待授权入库）

停止后继续把「修复要多大」坐实，结论是**恰 2 文件**且全部门槛转绿（补记不改本课题停止结论，改动仍未入库）：

- **修复内容**（`web/src/main.ts` + `web/src/vue-router-auto-shim.d.ts`）：
  1. `main.ts`：`createRouter` 改自 `vue-router` 取，新增 `import { routes } from 'vue-router/auto-routes'`，并以 `createRouter({ history: createWebHistory(), routes })` 传入路由表。
  2. 垫片：`declare module 'vue-router/auto'` 只保留 `export * from 'vue-router'`（App.vue 与各页面仍从该子路径取 `useRouter` 的类型），**删除会掩盖该缺陷的 `createRouter(options: Omit<RouterOptions,'routes'>)` 声明**；另新增 `declare module 'vue-router/auto-routes' { export const routes: RouteRecordRaw[] }`（生成的 `typed-router.d.ts` 只声明 `RouteNamedMap` 等类型、无 `routes` 值导出——这正是「只改 main.ts」朴素修法 `pnpm type-check` 报 TS2305 的原因）。
- **实测（本机，2026-10-14）**：`pnpm type-check` **rc=0**；`pnpm test` **rc=0**（4 套 31 测）；`pnpm build` **rc=0**（3317 modules，基线 3283）；`git diff --exit-code -- web/src/typed-router.d.ts` **rc=0**；`pnpm e2e` **12 passed rc=0**（本停止报告所附套件同一份，未改一行为绿）。
- **保留件**：`.trae/tmp/t227-preserved/full-fix-candidate.patch`（恰 2 文件 diff）、`FIX-CANDIDATE.md`（修复说明）、`fullfix-gates.log`、`e2e-12of12-green-under-fullfix.log`；恢复套件见同目录 `e2e/`、`tracked-changes.patch`、`web.gitignore`。
- **判定**：该修复落在 `web/src`（S0/S4 白名单外），故仍按 §3.3 停止上报，**不由本课题入库**；授权后可作为独立修复课题一次成形（套件与补丁均已就绪）。
