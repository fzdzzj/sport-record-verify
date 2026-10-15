# TASK-237 handoff：全链路闭环体验补齐——记录申诉前台打通、通知中心交互下钻与顶栏登录感知（MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-237`（外约：`work/mailbox/tasks/TASK-237/spec.md`；本文件为回传）。
- 分级：**MEDIUM**（前端交互体验补全、信息流下钻打通与 E2E 验证）。
- 开工基线：`e745c25`（工作树干净；开工前仅本任务 `spec.md` 未跟踪）。
- C-01 实施笔：`1d37ed0`（提交信息 `feat(web): TASK-237 全链路闭环体验补齐——申诉直通、通知下钻与顶栏登录感知（C-01 实施笔）`，恰 5 文件 = 白名单实施面全等）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/224/231/232/233/234/235/236 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**。

## 2. 只改清单（相对开工基线 e745c25，恰 7 文件 = C-01 5 + C-02 2）

C-01（实施笔，恰 5 文件 = 白名单实施面全等）：

- web/src/pages/index.page.vue
- web/src/pages/record-detail.page.vue
- web/src/pages/notifications.page.vue
- web/src/App.vue
- web/e2e/top-nav.e2e.ts

C-02（`docs(mailbox)` 台账笔，恰 2 文件）：

- work/mailbox/tasks/TASK-237/spec.md
- work/mailbox/tasks/TASK-237/handoff.md

> 白名单外零触碰：生产后端六服务（`src/main` 与 `src/test`）、`api/`、`common/`、根 pom、`package.json`、`playwright.config.ts`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 均零 diff；零新依赖。临时验证日志落 gitignored `.trae/tmp/`，不入库。

## 3. 一句话结论

申诉前台闭环与通知下钻一次补齐：① `index.page.vue` 操作列对校验中/拒绝/维持拒绝/人工审核（`status ∈ {1,3,6,7}`）记录新增「判定/申诉」链接，`goVerdict` 带 `query.id` 直达 `/verdict`，既有「查看轨迹/删除」保留；② `record-detail.page.vue` 摘要区在 `verdict === 2`（拒绝）时渲染「未通过校验？点击查看反作弊判定明细并提交申诉」引导，`goAppeal` 一键带参跳 `/verdict?id=${recordId}`；③ `notifications.page.vue` 表格 `title`/`sourceId` 列对可下钻通知渲染链接——`RECORD_*` 跳记录详情（`RECORD_REJECTED` 直达判定页）、`FRIEND_ACCEPTED` 跳好友页，未读项点击先自动标已读并刷新未读角标（失败静默不阻断跳转）；④ `App.vue` 顶栏按登录态动态渲染：已登录展示「已登录: USER/ADMIN」身份标签 +「设置」（跳 `/settings`）+「退出」，隐藏冗余「注册/登录」，未登录恢复「注册/登录」；⑤ `top-nav.e2e.ts` 对齐「设置」导航验证与退出后登录态恢复断言，全量 **12 passed** 全绿。前端 type-check 0 错误 / 32 单测 / build / e2e 全绿，白名单恰 7 文件，零新依赖。

## 4. 判别式与验收要点

- **R1 首页与详情页申诉直通闭环（绿）**：`index.page.vue` 操作列 `actions` 模板以 `canAppeal(record.status)`（`1/3/6/7`）门控新增「判定/申诉」链接，点击 `goVerdict(record.id)` → `router.push({ path: '/verdict', query: { id: String(record.id) } })`，「查看轨迹」「删除」不变；`record-detail.page.vue` 摘要信息卡片在 `verifyResult.verdict === 2` 时渲染 `a-alert(type=warning)` 提示「未通过校验？点击查看反作弊判定明细并提交申诉」，`goAppeal` 带 `query.id` 跳 `/verdict`（与首页同范式、与 verdict 页 `route.query.id` 装载范式对齐）。
- **R2 通知中心交互下钻与自动已读（绿）**：`notifications.page.vue` 新增 `drillTarget(record)`（`RECORD_*` 含 `sourceId` → `/record-detail?id=`，`RECORD_REJECTED` → `/verdict?id=`；`FRIEND_ACCEPTED` → `/friends`；其余返回 null）与 `onDrill(record)`：`title`/`sourceId` 列对可下钻项渲染蓝色链接，点击未读项先 `markNotificationRead(record.id)` 置本地 `isRead=1` 并 `refreshUnread()` 刷新铃铛角标（失败静默不阻断跳转），再 `router.push` 目标；不可下钻项保持静态文本（`sourceId` 缺失显示 `—`）。
- **R3 顶栏状态感知与设置入口（绿）**：`App.vue` 引入 `loggedIn`/`role` 登录态（`ref` 承载，退出 `logout()` 置 false + 路由 `watch` 兜底重读——见 D3 偏差登记），顶栏 `v-if="loggedIn"` 渲染 `a-tag`「已登录: {{ roleLabel }}」（`role` 缺失兜底 `USER`）+「设置」（`goTo('/settings')`）+「退出」，`v-else` 渲染「注册/登录」；登录态切换即时反映到顶栏 DOM（无整页刷新）。
- **R4 E2E 断言对齐与契约门禁（绿）**：`top-nav.e2e.ts` 在登录会话下新增：点击「设置」→ 断言 `/settings` URL + 改密表单 `placeholder=当前登录密码` 可见；断言「注册/登录」按钮 `toHaveCount(0)` 且「设置/退出」可见；退出后断言 `/login`、`access_token` 清空、「注册/登录」恢复可见、「设置/退出」`toHaveCount(0)`；原「好友守卫拦回 /login」断言保留；全量 **12 passed**，`assertNoUnhandled(ctx)` 零未覆盖 API 请求。
- **R5 红线（绿）**：`git diff --name-only e745c25` + untracked 恰 7 文件全等于 §2 清单（无多报无漏报）；零新依赖（仅复用 Vue 3 / Ant Design Vue 既有组件与既有样式）；白名单外文件零 diff。

## 5. 验证命令与输出片段

1. **前端 type-check**：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，0 错误）。
2. **前端单测**：`pnpm --dir web test` → rc=0：`Test Files 4 passed (4)` / `Tests 32 passed (32)`（vitest）。
3. **前端构建**：`pnpm --dir web build`（`TMP/TEMP` 重定向 `.trae/tmp`，沿 TASK-224/231/232/233/234/235/236 同款处置）→ rc=0：`✓ built in 27.47s`（chunk >500 kB 为既有非阻断 advisory）。
4. **前端 E2E 全量**：`pnpm --dir web e2e`（同 TMP/TEMP 重定向）→ **`12 passed (1.1m)`，rc=0**（chromium 单 worker，vite preview 生产产物 + route-mock；`pnpm build` 由 webServer 命令内嵌执行）。
5. **契约门禁**：`bash scripts/verify/mailbox-contract.sh --open=TASK-237 --baseline=e745c25`（`D:\git\Git\bin\bash.exe`，Git Bash 可用实跑）→ 退出码 0：判据 A 两件套齐（spec.md + handoff.md 均在）+ 判据 B 只改清单与实际改动集一致（`git diff --name-only e745c25` + untracked 恰 7 文件全等）。

## 6. 未覆盖、遗留风险与不得推出的结论

- **通知下钻未入 E2E 断言**：白名单内无 notifications e2e 文件（`notifications.page.vue` 下钻交互由 type-check + 代码评审 + 既有通知页挂载断言承载）；新增下钻点击会触发 `/record-detail`、`/verdict`、`/friends` 挂载，其 API 均已在 route-mock 两前缀覆盖内（points/verify-result/like/friends 均有 mock），后续如需可新开任务补通知下钻 E2E。
- **顶栏登录态为「本页生命周期内」的 ref 同步**：登录/退出经 `logout()` 显式置位与路由 `watch` 兜底同步；若未来出现不经路由跳转的 token 变更路径（如后台定时刷新 token 键），需收敛为事件/响应式 store，当前无此路径故不扩展。
- **`--mode=online` / `--static`（Checkstyle/SpotBugs/PMD）CI 全流程未跑**（未 push、未建 PR），外部门禁待指导侧推动后补记。

## 7. 偏差登记

- **D1（行尾契约）**：本机 `core.autocrlf=true`，写入编辑器产生 bare LF，`git add` 时归一 CRLF（warning 属预期）；`git diff --numstat e745c25` 复核全为行级小 diff（index.page.vue +12/-0、record-detail.page.vue +14/-1、notifications.page.vue +45/-1、App.vue +39/-5、top-nav.e2e.ts +24/-8），无整文件重写残迹。
- **D2（e2e 与 build 串行规避 webServer 超时）**：沿 TASK-234 D3 / TASK-235 D4 / TASK-236 D2 先例，`pnpm --dir web build` 与 `pnpm --dir web e2e` 串行执行（CPU 抢占会导致 preview 未在 180s webServer 窗口内起服务）；本次 build 27.47s 后独立跑 e2e，`12 passed (1.1m)`。
- **D3（顶栏登录态承载方式偏离 spec 建议字面）**：spec §3.3 建议 `loggedIn = computed(() => hasAccessToken())`；实测 `computed` 直读 localStorage 无响应依赖会缓存陈旧值，退出后顶栏无法即时恢复未登录态（E2E「退出后注册/登录恢复可见」会红）。故以 `ref(hasAccessToken())` 承载，`logout()` 显式置 false + 路由 `watch` 兜底重读（覆盖登录页 `setTokens` 后跳转方向），判别式 R3/R4 全绿，行为等价且更稳。

## 8. 停止条件

白名单内实施与测试改动完成，验证命令全绿（前端 type-check 0 错误 + 32 单测 + build + e2e 12 passed），台账两件套落盘并经外约门禁退出码 0；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需推进 CI push、通知下钻 E2E、登录态响应式收敛，须新开任务，不在本任务扩大范围。
