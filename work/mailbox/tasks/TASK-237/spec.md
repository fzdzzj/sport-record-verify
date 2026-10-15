# TASK-237: 全链路闭环体验补齐——记录申诉前台打通、通知中心交互下钻与顶栏登录感知（MEDIUM 档）

> **开工基线**：e745c25（main 分支，工作树干净，TASK-236 封盘笔）
> **任务级别**：MEDIUM（前端交互体验补全、信息流下钻打通与 E2E 验证）

## 1. 目标与背景

- **背景**：
  1. **被拒记录申诉无门（index.page.vue & record-detail.page.vue）**：首页「我的记录」列表操作列只有「查看轨迹」与「删除」。当用户记录被反作弊规则拒绝（`status=3`）或处于人工审核（`status=7`）时，**完全没有任何入口引导用户查看命中规则详情或发起申诉**；轨迹详情页同样缺少申诉引导。用户若想申诉，必须在浏览器地址栏手动敲 `/verdict` 并手动输入 recordId，属于严重的交互断点；
  2. **通知中心死胡同（notifications.page.vue）**：通知列表虽然有 `type`、`title`、`sourceId`，但均为静态文本不可点击。用户收到「记录审核驳回/通过」「收到点赞」或「好友申请通过」通知时，无法点击跳转到对应实体查看，亦无直观反馈；
  3. **顶栏登录感知弱且缺设置入口（App.vue）**：不论是否登录，顶栏始终展示「注册」「登录」；且完全缺少「设置（改密与偏好）」入口，改密功能在顶栏处于隐身状态。
- **目标**：
  1. 首页列表针对被拒绝（`status=3` / `status=6`）及校验中/人工审核（`status=1` / `status=7`）记录，操作列补充「判定/申诉」直达链接（`/verdict?id=${record.id}`）；
  2. 记录详情页在摘要区被判定为拒绝（`verdict === 2`）时，增加「查看判定详情与申诉」引导入口；
  3. 通知中心支持点击下钻：记录类通知（`RECORD_*`）展示为链接，点击一键跳转至 [`/record-detail`](file:///d:/code/sports/web/src/pages/record-detail.page.vue) 或 [`/verdict`](file:///d:/code/sports/web/src/pages/verdict.page.vue) 并自动标已读；好友类通知（`FRIEND_ACCEPTED`）点击直达好友页；
  4. 顶栏根据登录态动态渲染：已登录状态展示「用户身份标签 + 设置（跳转 `/settings`）+ 退出」，未登录展示「登录 / 注册」；
  5. 扩展 [`web/e2e/top-nav.e2e.ts`](file:///d:/code/sports/web/e2e/top-nav.e2e.ts) 验证「设置」导航入口与登录态感知，确保 12 条 E2E 全绿。

## 2. 修改白名单

严格限定在以下 7 个文件内：

- 前端生产代码与测试（共 5 文件）：
  - `web/src/pages/index.page.vue`
  - `web/src/pages/record-detail.page.vue`
  - `web/src/pages/notifications.page.vue`
  - `web/src/App.vue`
  - `web/e2e/top-nav.e2e.ts`
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-237/spec.md`
  - `work/mailbox/tasks/TASK-237/handoff.md`

> **红线约束**：
> - 生产后端代码（六服务 `src/main` 与 `src/test`）、`api/`、`common/`、根 pom、`package.json`、`playwright.config.ts`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 零新外部依赖（复用既有 Vue 3、Ant Design Vue 组件）。

## 3. 核心设计与判别式要求

### 3.1 首页与详情页申诉直通闭环（web/src/pages/index.page.vue & record-detail.page.vue）

- **首页列表操作列**：
  - 在 `actions` 模板中，若 `record.status === 3 || record.status === 6 || record.status === 1 || record.status === 7`，增加「判定/申诉」链接，点击调用 `router.push({ path: '/verdict', query: { id: String(record.id) } })`；
  - 既有「查看轨迹」与「删除」保留不变。
- **详情页摘要区申诉指引**：
  - 在摘要信息卡片中，若 `verifyResult?.verdict === 2`（拒绝），渲染一条提示性操作（如「未通过校验？点击查看反作弊判定明细并提交申诉」），点击一键带参跳转至 `/verdict?id=${recordId}`。

### 3.2 通知中心交互下钻与自动已读（web/src/pages/notifications.page.vue）

- **点击下钻**：
  - 在 `table` 的 `title` 或 `sourceId` 列提供点击交互（可设为带有样式的超链接或增加「查看详情」操作）：
    - 若 `record.type.startsWith('RECORD_')` 且包含 `sourceId`：点击跳转至 `/record-detail?id=${record.sourceId}`（若为 `RECORD_REJECTED` 亦可支持直达 `/verdict?id=${record.sourceId}`）；
    - 若 `record.type === 'FRIEND_ACCEPTED'`：点击跳转至 `/friends`；
  - 跳转前若该通知 `isRead === 0`，自动调用 `markNotificationRead(record.id)` 标为已读并刷新未读角标。

### 3.3 顶栏状态感知与设置入口（web/src/App.vue）

- **导航项动态性**：
  - 引入 `loggedIn = computed(() => hasAccessToken())` 与 `role = computed(() => getRole())`；
  - 增加「设置」导航按钮（`@click="goTo('/settings')"`），让修改密码和通知偏好随时可达；
  - 当 `loggedIn` 为 true 时：展示已登录身份标识（如 `a-tag` 或文字「已登录: {{ role || 'USER' }}」），保留「退出」与「设置」，隐藏冗余的「登录」与「注册」按钮；未登录时展示「登录」与「注册」。

### 3.4 E2E 断言对齐与契约门禁

- **E2E 验证**：
  - 更新 `web/e2e/top-nav.e2e.ts`：在登录会话下断言「设置」按钮可见并可点击跳转至 `/settings`；在退出会话后断言跳转至 `/login` 且「登录/注册」按钮恢复可见；
  - 全量 12 测 100% 稳定通过，无 unhandled 请求；
- **分笔要求**：
  - C-01 实施笔：恰 5 个前端文件；
  - C-02 台账笔：恰 2 个台账文件。

## 4. 验证命令

- 前端类型：`pnpm --dir web type-check`
- 前端单测：`pnpm --dir web test`
- 前端构建：`$env:TMP="D:\code\sports\.trae\tmp"; $env:TEMP="D:\code\sports\.trae\tmp"; pnpm --dir web build`
- 前端 E2E：`$env:TMP="D:\code\sports\.trae\tmp"; $env:TEMP="D:\code\sports\.trae\tmp"; pnpm --dir web e2e`
- 契约门禁：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-237 --baseline=e745c25`

---

### 回填（2026-10-15，C-02 台账笔）

**判别式红绿**

- R1 首页与详情页申诉直通闭环：**绿**。`index.page.vue` 操作列在 `record.status ∈ {1,3,6,7}`（校验中/拒绝/维持拒绝/人工审核）时新增「判定/申诉」链接（`canAppeal` 门控），点击 `goVerdict` 带 `query.id` 跳 `/verdict`；既有「查看轨迹」「删除」保留不变。`record-detail.page.vue` 摘要区在 `verifyResult.verdict === 2`（拒绝）时渲染「未通过校验？点击查看反作弊判定明细并提交申诉」提示，`goAppeal` 一键带参跳 `/verdict?id=${recordId}`（与首页同范式）。
- R2 通知中心交互下钻与自动已读：**绿**。`notifications.page.vue` 表格 `title`/`sourceId` 列对可下钻通知渲染蓝色链接：`RECORD_*`（含 `sourceId`）→ `/record-detail?id=`（`RECORD_REJECTED` 直达 `/verdict?id=`），`FRIEND_ACCEPTED` → `/friends`；`onDrill` 在 `isRead === 0` 时先 `markNotificationRead` 标已读并 `refreshUnread()` 刷新角标（失败静默不阻断跳转），再路由跳转。
- R3 顶栏状态感知与设置入口：**绿**。`App.vue` 顶栏按登录态动态渲染：已登录展示「已登录: {{ role || 'USER' }}」身份标签（`a-tag`）+「设置」（跳 `/settings`）+「退出」，隐藏冗余「注册/登录」；未登录展示「注册/登录」。登录态用 `ref` 承载（`hasAccessToken`/`getRole` 直读 localStorage 非响应式，`computed` 会缓存陈旧值——见 handoff 偏差登记 D3），退出与路由变更时同步刷新。
- R4 E2E 断言对齐与契约门禁：**绿**。`top-nav.e2e.ts` 在登录会话下断言「设置」可见可点、跳转 `/settings` 并渲染改密表单，且「注册/登录」隐藏、「设置/退出」可见；退出后断言跳转 `/login`、token 清空、「注册/登录」恢复可见、「设置/退出」隐藏；全量 **12 passed**，无 unhandled 请求。
- R5 红线：**绿**。C-01 恰 5 个前端文件、C-02 恰 2 个台账文件；`git diff --name-only e745c25` + untracked 恰 7 文件全等于 §2 白名单；零新依赖（复用 Vue 3 / Ant Design Vue 既有组件）；后端六服务、`api/`、`common/`、根 pom、`package.json`、`playwright.config.ts`、`PLAN.md`、`HANDOVER.md` 零 diff。

**门禁留证**

- 前端 type-check：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，0 错误）。
- 前端单测：`pnpm --dir web test` → rc=0：`Test Files 4 passed (4)` / `Tests 32 passed (32)`（vitest）。
- 前端构建：`pnpm --dir web build`（`TMP/TEMP` 重定向 `.trae/tmp`）→ rc=0：`✓ built in 27.47s`（chunk >500 kB 为既有非阻断 advisory）。
- 前端 E2E 全量：`pnpm --dir web e2e`（同 TMP/TEMP 重定向）→ **`12 passed (1.1m)`，rc=0**（chromium 单 worker，vite preview 生产产物 + route-mock，含顶栏导航新断言）。
- 契约门禁：`bash scripts/verify/mailbox-contract.sh --open=TASK-237 --baseline=e745c25` → 退出码 0（判据 A 两件套齐 + 判据 B 只改清单与实际改动集一致）。
