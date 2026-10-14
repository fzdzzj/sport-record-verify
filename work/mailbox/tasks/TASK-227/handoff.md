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

---

# TASK-227 续批回传（重启后完成，C-02 台账笔，2026-10-14）

## 8. 只改清单（续批，相对开工基线 138ec97，恰 22 文件 = C-01 18 + C-02 4，逐字符声明）

C-01（feat(e2e) 实施笔，恰 18 文件 = S4 白名单全等）：

- web/.gitignore
- web/README.md
- web/package.json
- web/pnpm-lock.yaml
- web/e2e/playwright.config.ts
- web/e2e/helpers.ts
- web/e2e/delete-record.e2e.ts
- web/e2e/feed.e2e.ts
- web/e2e/filter-pagination.e2e.ts
- web/e2e/goal-card.e2e.ts
- web/e2e/login-redirect.e2e.ts
- web/e2e/record-list.e2e.ts
- web/e2e/record-submit.e2e.ts
- web/e2e/top-nav.e2e.ts
- web/e2e/fixtures/feed.ts
- web/e2e/fixtures/goals.ts
- web/e2e/fixtures/notifications.ts
- web/e2e/fixtures/records.ts

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，恰 4 文件）：

- spec/changes/e2e-browser-batch/tasks.json
- work/mailbox/tasks/TASK-227/spec.md
- work/mailbox/tasks/TASK-227/handoff.md
- work/mailbox/PLAN.md

> 下列载体零触碰（R6 证据见 §11）：`.github/workflows/ci.yml`、`web/vite.config.ts`、`web/tsconfig.app.json`、`web/src/**`（含 `typed-router.d.ts`，零新路由故零 diff）、一切后端 Java/sql/pom、`scripts/`、`docker-compose*`。E2E 明列不入 CI；证据与 trace 落 gitignored `.trae/tmp`，不入库、不计入改动集。
> 本续批开工基线 `138ec97` = TASK-228 C-02 台账笔（启动级路由缺陷修复 `05bcae4` + 台账 `138ec97`），缺陷未修则 12 用例全数阻断，故 227 必须在 228 之后。

## 9. 续批结论

首轮 S3.3 停止的根因（应用启动级路由缺陷）已由 TASK-228 修复；本续批从保留件恢复 e2e 套件、依赖与忽略规则后，8 条 route-mock spec / 12 用例在真 chromium（生产构建产物 + `vite preview`）下 **12 passed、rc=0**；两 API 前缀之外零真实网络请求（`assertNoUnhandled` 断言 12 用例全覆盖）；web 四件套不回归（单测 31 持平、type-check 零错、build 成功、typed-router 零 diff）；后端与 CI/vite 配置零 diff。E2E 仅本地执行，不入 CI。

## 10. 续批判别式 R1-R6

- **R1 绿（依赖落地与版本锁定）**：`pnpm install --frozen-lockfile` rc=0，输出 `Lockfile is up to date, resolution step is skipped`（`.trae/tmp/t227-install.log`）；安装后 `pnpm-lock.yaml` 零 diff；`web/package.json` devDependencies 为 `"@playwright/test": "1.64.0"`（精确版本、无范围符号），lock 内 importer specifier 与包版本同为 1.64.0；浏览器二进制落系统用户缓存，不入库。
- **R2 绿（12 用例全绿，逐条断言核心交互）**：`pnpm e2e` rc=0，`Running 12 tests using 1 worker` → `12 passed`（`.trae/tmp/t227-e2e-run.log`）。逐条见 §11。
- **R3 绿（route-mock 零真实网络请求）**：`web/e2e/helpers.ts` 内 `installRouteMocks` 以 `page.route('**/record/api/**')` 与 `page.route('**/user/api/**')` 返回固定夹具；同时 `page.on('request')` 按后端前缀白名单 `/^\/(api|record|user|leaderboard|verify|admin|mapmatch)\//` 记录全部 API 请求，**两前缀之外一律记入 `unhandled`**，`assertNoUnhandled` 以 `expect(ctx.unhandled).toEqual([])` 断言为空——12 用例共 20 处调用，实测零命中；两前缀自身由 mock 响应，故对后端零真实请求。
- **R4 绿（web 四件套不回归）**：`pnpm test` rc=0（4 文件 31 测全过，与基线持平）／`pnpm type-check` rc=0／`pnpm build` rc=0（3317 modules transformed / built）／`git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零 diff）。数值口径订正见 §14-D5。
- **R5 绿（证据留痕）**：运行输出落 `.trae/tmp/t227-e2e-run.log`；`web/e2e/playwright.config.ts` 将 `outputDir` 指向 gitignored `.trae/tmp/e2e-results`，本批实测 **12 个用例目录 + 12 份 trace `.zip`**（config `trace: 'on'`，每用例必产），登记于 §11 与本节。
- **R6 绿（后端与配置零 diff）**：相对开工基线 `138ec97` 的改动集全部位于 `web/` 且限于 §8 所列 22 文件；`git diff --name-only 4596df2 -- '*.java' '*.sql' 'pom.xml' '*/pom.xml' '.github/workflows/ci.yml' 'scripts/' 'docker-compose*' 'web/vite.config.ts' 'web/tsconfig.app.json'` 为空。

## 11. 12 用例逐条清单与门禁留证（S6）

逐条（`pnpm e2e` 实测，chromium 单浏览器，`vite preview` 生产产物，baseURL 127.0.0.1:4179）：

1. login-redirect：未登录访问控制台 → 守卫跳转登录页 ✓
2. login-redirect：已登录访问 `/login` → 自动跳转首页并渲染记录列表 ✓
3. record-list：统计卡与表格渲染（记录数/总里程/通过率/连续打卡/可见性列）✓
4. record-submit：清空后提交按钮禁用（校验门），重新加载样例后恢复 ✓
5. record-submit：选择骑行类型提交 → 结果卡片渲染（recordId/status/duplicated）✓
6. filter-pagination：翻页 → 筛选跑步 → page 重置为 1 且列表缩减 → 重置恢复 20 行 ✓
7. delete-record：取消不发删除请求；确认后删除并刷新列表 ✓
8. feed：第 1 页卡片渲染，翻页显示第 2 页条目 ✓
9. goal-card：目标卡片渲染（周/月标签、进度条、已达成徽标）✓
10. goal-card：设置目标弹窗 upsert 新目标并刷新列表 ✓
11. goal-card：编辑弹窗更新目标值；删除 Popconfirm 移除目标 ✓
12. top-nav：导航到各业务页并断言内容，退出清会话 ✓

门禁逐项（读数落 `.trae/tmp/t227r-gates.log` 与 `t227-*.log`）：

1. **web 四件套**：`pnpm test` rc=0（31/31）／`pnpm type-check` rc=0／`pnpm build` rc=0（3317 modules）／typed-router 零 diff rc=0。
2. **E2E 套件**：`pnpm e2e` rc=0（12 passed，8 文件）；`--list` 交叉核对 `Total: 12 tests in 8 files` rc=0。
3. **零触碰**：后端/ci.yml/vite.config/tsconfig.app/web/src 相对开工基线零 diff（见 R6）。
4. **token 门**：29 项字面量在 `4596df2` 与工作树的 tracked 计数和逐位持平；另对 15 个新增未跟踪文件逐项固定串扫描 **0 命中**（tracked 之外的补充口径，读数见日志）。
5. **词面门**：改动集（20 文件）`git grep -iE -f pattern` **ZERO_HIT rc=1**；新增未跟踪文件显式扫描 0 命中；探针阳性对照 **HIT rc=0**。
6. **`git diff --check`**：工作树 rc=0 且暂存区 rc=0。
7. **行尾**：改动集 20 文件逐文件核对 **bad=0**——新增 15 文件（`web/.gitignore` + 14 个 `web/e2e/**`）纯 LF、末尾 0x0A、无 BOM；改动 4 个既有文件（`web/README.md`/`web/package.json`/`web/pnpm-lock.yaml`，及 TASK-228 的 `web/src/*.ts`）保持基线 autocrlf CRLF 工作树形态、blob LF、末尾 0x0A、无 BOM。
8. **契约门双态**：见 §14-D2/D3 的口径登记与读数。

## 12. token 时点读数

29 项字面量：`4596df2` 与工作树 tracked 计数和持平、逐项未变；新增未跟踪文件 0 命中。字面量清单与数值按硬约束不写入本 handoff、不入任何 tracked 文件；读数见 `.trae/tmp/t227r-gates.log`。

## 13. 首轮停止史与停止笔链（如实登记）

- 首轮本课题在 S3.3 停止上报：E2E 无法在真实浏览器跑通，根因定位为**应用启动级路由缺陷**（`unplugin-vue-router@0.19.2` 不再向 `vue-router/auto` 注入文件路由表，`main.ts` 的 `createRouter({history})` 使 `routes` 为 `undefined`，`createRouterMatcher` 在 `routes.forEach` 抛错，应用挂载失败）——即 E2E 批的**头号价值发现**。
- 停止笔链：`e1610de`（停止上报）→ `69602b0`（停止报告补记；补记同时给出「完整修复候选 = 恰 2 文件」并实测全绿）。两笔为纯文档笔，本续批未改写、未 revert。
- 缺陷另立 TASK-228（`4596df2` 派发），已先于本续批落地（`05bcae4` + `138ec97`），本续批基线 `138ec97`。
- 保留件：`.trae/tmp/t227-preserved/`（`full-fix-candidate.patch` / `e2e/` 套件 14 文件 / `tracked-changes.patch`（README + package.json + pnpm-lock）/ `web.gitignore` / `FIX-CANDIDATE.md` / 两份全绿日志）与 `.trae/tmp/e2e-results/`（首轮 12 份 trace，已于续批前整体另存 `.trae/tmp/t227-preserved/e2e-results-round1/`，本轮 trace 覆盖写回原目录）。

## 14. 异常与偏差

- **D1（保留件缺口：e2e 脚本无法自洽发现配置）**：保留件的 `web/package.json` 写 `"e2e": "playwright test"`，README 亦记「`cd web && pnpm e2e`」；但 Playwright 只在**当前目录**寻找 `playwright.config.ts`，而配置在 `web/e2e/` 子目录下，故原样执行时配置未被发现——默认 `testDir` 退回 `web/`，误扫 `web/src/**/__tests__/*.test.ts`（vitest 单测），报 4 条 `Vitest failed to access its internal state.` + `No tests found`，rc=1（`.trae/tmp/t227-e2e-run.log` 首跑记录）。据 S2「`scripts.e2e`」意图补 `--config e2e/playwright.config.ts` 一行（白名单内 `web/package.json`），改后 `pnpm e2e` rc=0 且与 `--list` 的 12 用例交叉一致，README 所述运行方式成立。旁证：首轮全绿日志只含 `[WebServer]` 输出、无 `pnpm e2e` 包脚本横幅，说明首轮并非以保留件所记脚本形态运行；首轮若存在根级配置则未被保留，该调用形态无法复原。本偏差不涉 `vite.config.ts`/路由/后端，非 S3.3 停止条件。
- **D2（契约门基线口径）**：用户指定 `--open TASK-227 --baseline=4596df2`。实测该基线**必然为红**：判据 B 用**单一**改动集对**每个**在途回传做全量比对，而 `4596df2` 跨越 TASK-228 的 6 文件，已收口的 TASK-228 handoff（只声明自身 6 文件）会被判「改动集未声明」若干项——这是跨课题基线与工具全局比对语义的冲突，非契约违反。故沿 TASK-224/225/226 先例，以本课题**真实开工时点** `138ec97`（TASK-228 C-02）为基线复跑；两版读数均如实登记于 `.trae/tmp/t227-contract-*.log`。
- **D3（契约门提取器扩展名白名单）**：判据 B 的清单提取器只承认固定扩展名集合（`md|json|ya?ml|ts|vue|java|sql|...`），**不含无扩展名 dotfile**，故 `web/.gitignore` 无法被表达为清单 token，默认基线复跑将多出 1 项「改动集未声明：web/.gitignore」。该文件在 S4 白名单内（`web/.gitignore（如需）`）且已逐字符登记于 §8；为得到可解释的判据 B 读数，另以 `--diff-file` 显式给出由**真实改动集减去该不可表达项**（21 项）构成的清单复跑，得 rc=0。两版读数均登记。
- **D4（临时件）**：挂载/门禁脚本、日志、trace、契约读数均落 gitignored `.trae/tmp`，不入库。
- **D5（R4 数值口径订正）**：S2.5 预注册的 build 期望值 3283 系 TASK-228 修复**之前**的读数。TASK-228 使 `vue-router/auto-routes` 模块首次被真正引用并打入产物，本续批正确读数为 3317 modules（TASK-228 handoff 已登记同一事实）。R4 的**内容面**不回归结论不变（31 测持平 / type-check 零错 / typed-router 零 diff / build 成功）。
- **D6**：C-02 自指哈希沿先例（§1 内的 C-01/C-02 哈希以 `git log` 为准）。

## 15. 未覆盖与不得推出的结论

- **未覆盖**：真实后端全栈联调（`--it` 层，仍留 H2/后续）；CI 集成（E2E 明列不入 CI，`ci.yml` 零触碰）；多浏览器（仅 chromium，未跑 firefox/webkit）与移动视口、可访问性；榜单页交互（`/leaderboard/api/*` 不在两前缀 mock 范围，避免真实网络请求，候选后续扩展）；WebSocket（`/user/ws-notifications`）推送路径；`--mode=online` 依赖解析。
- **不得推出**：不得据 12 用例绿得出「后端链路正确」的结论（全为 route-mock 夹具，后端零参与）；不得得出「跨浏览器兼容」结论（单浏览器）；不得得出「可入 CI」结论（未接 CI，且明列不入）；不得据本机单次运行得出稳定性/性能结论（`retries: 0`、`workers: 1`、单次跑）。
