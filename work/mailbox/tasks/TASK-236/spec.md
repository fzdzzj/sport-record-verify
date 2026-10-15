# TASK-236: 社交与点赞交互补齐——动态流与记录详情页点赞交互组件与计数实时同步（MEDIUM 档）

> **开工基线**：69cb306（main 分支，工作树干净，TASK-235 封盘笔）
> **任务级别**：MEDIUM（前端核心交互与状态同步、边界防御与 E2E 强化）

## 1. 目标与背景

- **背景**：
  1. **后端完备但前端交互缺失**：系统后端早已具备高性能读热写冷点赞链路（`RecordLikeService`，Redis 成员集 + 计数、定时批量 Flush 落库、RocketMQ 事件通知、`LikeController` 权限门矩阵守卫，830 测全绿）；底层前端客户端 [`client.ts`](file:///d:/code/sports/web/src/api/client.ts) 也已封装 `likeRecord`、`unlikeRecord`、`getRecordLike` 三个标准方法；
  2. **动态流社交孤岛（feed.page.vue）**：动态流卡片展示了作者昵称、运动类型、距离、状态与「查看详情」入口，但**没有任何点赞按钮与点赞数展示**，无法进行社交点赞互动；
  3. **记录详情页点赞缺位（record-detail.page.vue）**：详情页展示了轨迹 SVG、摘要头与配速趋势，但**缺少点赞交互组件**，用户查阅记录时无法直观点赞并查看点赞统计；
  4. **业务边界与防呆缺失**：根据系统设计，只有审核通过（`status` 为 `PASSED(2)` 或 `RE_PASSED(5)`）的记录才允许点赞，若对校验中或拒绝的记录发起点赞后端会返回 `6001`（`RECORD_NOT_PASSED`），前端需在界面上给予直观的防呆禁用或提示。
- **目标**：
  1. 在好友动态流页面（[`feed.page.vue`](file:///d:/code/sports/web/src/pages/feed.page.vue)）各动态卡片中增加点赞交互组件（高亮已赞状态、点赞计数展示、点击点赞/取消点赞、加载当前页点赞态、未通过记录防呆）；
  2. 在记录轨迹详情页（[`record-detail.page.vue`](file:///d:/code/sports/web/src/pages/record-detail.page.vue)）操作区增加点赞交互组件（加载详情时调取点赞状态、点击点赞/取消点赞实时同步计数、未通过/未授权安全降级）；
  3. 完善 [`helpers.ts`](file:///d:/code/sports/web/e2e/helpers.ts) 的点赞 mock 状态记忆，在 [`feed.e2e.ts`](file:///d:/code/sports/web/e2e/feed.e2e.ts) 中增加点赞交互端到端测试断言，保证 E2E 全量全绿。

## 2. 修改白名单

严格限定在以下 6 个文件内：

- 前端生产页面与测试（共 4 文件）：
  - `web/src/pages/feed.page.vue`
  - `web/src/pages/record-detail.page.vue`
  - `web/e2e/feed.e2e.ts`
  - `web/e2e/helpers.ts`
- 台账两件套（共 2 文件）：
  - `work/mailbox/tasks/TASK-236/spec.md`
  - `work/mailbox/tasks/TASK-236/handoff.md`

> **红线约束**：
> - 生产后端代码（六服务 `src/main` 与 `src/test`）、`api/`、`common/`、根 pom、`package.json`、`playwright.config.ts`、`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 绝对零触碰！
> - 严禁引入任何新的第三方依赖库（直接复用 Ant Design Vue 的 `a-button`, `a-tooltip` 等基础组件与既有样式）。

## 3. 核心设计与判别式要求

### 3.1 动态流点赞交互与状态同步（web/src/pages/feed.page.vue）

- **UI 布局**：在每个动态卡片的底部操作行（与「查看详情」按钮并列）新增点赞操作按钮与点赞数显示（如 `👍 赞 (count)` 或 `已赞 (count)`，支持高亮态）；
- **初态加载**：
  - 当列表加载完毕（`rows` 填充）后，对当前页可见记录调取 `getRecordLike(item.id)` 获取点赞总数和当前用户点赞态；
  - 容错要求：采用 `Promise.allSettled` 或优雅降级，单条点赞查询失败不影响整页动态渲染与列表可用性；
- **交互与状态流转**：
  - 点击时防抖/防重复（`loading` 锁）；
  - 若已赞：调用 `unlikeRecord(item.id)`，成功后本地更新 `liked = false`，`likeCount = res.data.likeCount`；
  - 若未赞：调用 `likeRecord(item.id)`，成功后本地更新 `liked = true`，`likeCount = res.data.likeCount`；
  - 失败时弹出 Ant Design Vue `message.error` 提示；
- **边界防御**：
  - 未登录状态：点击提示「请先登录」或置灰；
  - 记录状态非通过（`status !== 2 && status !== 5`）：置灰禁用并 Tooltip 提示「未通过校验的记录不可点赞」（消除 6001 破窗）。

### 3.2 记录轨迹详情页点赞交互（web/src/pages/record-detail.page.vue）

- **UI 布局**：在摘要头或操作区域（与「导出 GPX」并列）增加点赞按钮与计数；
- **初态加载**：在 `load()` 中并发调取 `getRecordLike(recordId.value)`，获取点赞数与是否已赞；若请求失败（如 403 权限或非通过记录）静默降级，不阻断轨迹与配速渲染；
- **交互响应**：与动态流同构，点击一键切换点赞/取消，实时更新计数与按钮高亮状态。

### 3.3 E2E 强化与契约对齐（web/e2e/helpers.ts & web/e2e/feed.e2e.ts）

- **Mock 状态记忆**：`web/e2e/helpers.ts` 中针对 `/record/api/records/:id/like` 路由，增加简单的动态状态记忆（`POST` 赞 +1、`DELETE` 踩 -1、`GET` 返回当前态），避免由于固定 mock 导致连续操作断言失败；
- **E2E 断言**：在 `web/e2e/feed.e2e.ts` 中新增断言：
  - 验证动态卡片点赞按钮可见且展示点赞数；
  - 点击点赞后，断言发送了点赞 API 请求且按钮状态变为已赞；
  - 维持原有分页与列表断言不受影响，全量 12 测 100% 稳定通过。

### 3.4 红线与分级要求

- 级别：**MEDIUM**；
- 实施笔 C-01 恰 4 个白名单文件；台账笔 C-02 恰 2 个白名单文件；
- 前端 `pnpm --dir web type-check` 0 错误；`pnpm --dir web test` 32 测全过；`pnpm --dir web build` 成功；`pnpm --dir web e2e` 12 测全绿。

## 4. 验证命令

- 前端类型：`pnpm --dir web type-check`
- 前端单测：`pnpm --dir web test`
- 前端构建：`$env:TMP="D:\code\sports\.trae\tmp"; $env:TEMP="D:\code\sports\.trae\tmp"; pnpm --dir web build`
- 前端 E2E：`$env:TMP="D:\code\sports\.trae\tmp"; $env:TEMP="D:\code\sports\.trae\tmp"; pnpm --dir web e2e`
- 契约门禁：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-236 --baseline=69cb306`

---

### 回填（2026-10-15，C-02 台账笔）

**判别式红绿**

- R1 动态流点赞交互与状态同步：**绿**。`feed.page.vue` 每张动态卡片操作行（与「查看详情」并列）新增点赞按钮，`赞 (n)`/`已赞 (n)` 展示计数且已赞态 `type="primary"` 高亮；列表加载完毕后以 `Promise.allSettled` 异步调取当前页记录的 `getRecordLike`（单条失败不影响整页渲染）；点击经 per-card loading 锁防重，已赞 → `unlikeRecord` / 未赞 → `likeRecord`，成功后以服务端返回的 `liked/likeCount` 同步本地态；失败 `message.error`（6001 特化提示）；未登录或 `status` 非 2/5 置灰 + Tooltip 防呆（消除 6001 破窗）。
- R2 记录详情页点赞交互：**绿**。`record-detail.page.vue` 操作区（与「导出 GPX」并列）新增点赞按钮与计数；`load()` 中摘要与点赞态以 `Promise.allSettled` 并发调取，点赞态失败（403 权限/非通过 6001）静默降级不阻断轨迹与配速渲染；点击一键切换，实时同步计数与高亮；未登录或 verdict 已知且非 PASSED 置灰 + Tooltip 防呆，verdict 未知（摘要降级）时放行由后端权威判定。
- R3 E2E 强化与契约对齐：**绿**。`helpers.ts` 的 `/record/api/records/:id/like` 路由改为动态状态记忆（`Stores.likes` Map：GET 返回当前态、POST 赞 +1、DELETE 踩 -1，初始 0 赞/未赞）；`feed.e2e.ts` 新增断言：点赞按钮可见且展示计数 `赞 (0)` → 点击后按钮转 `已赞 (1)` 且 `POST /record/api/records/2000/like` 恰 1 次 → 再点恢复 `赞 (0)` 且 DELETE 恰 1 次；原有分页与列表断言不受影响，全量 **12 passed**。
- R4 红线：**绿**。`git diff --name-only 69cb306` + untracked 恰 6 文件全等于 §2 白名单；零新依赖（仅复用 ant-design-vue 基础组件与既有样式）；后端/`api/`/`common/`/根 pom/`package.json`/`playwright.config.ts`/`PLAN.md`/`HANDOVER.md` 零 diff。

**门禁留证**

- 前端 type-check：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，0 错误）。
- 前端单测：`pnpm --dir web test` → rc=0：`Test Files 4 passed (4)` / `Tests 32 passed (32)`（vitest）。
- 前端构建：`pnpm --dir web build`（`TMP/TEMP` 重定向 `.trae/tmp`）→ rc=0：`✓ built in 30.38s`（chunk >500 kB 为既有非阻断 advisory）。
- 前端 E2E 全量：`pnpm --dir web e2e`（同 TMP/TEMP 重定向）→ **`12 passed (1.3m)`，rc=0**（chromium 单 worker，vite preview 生产产物 + route-mock）。
- 契约门禁：`bash scripts/verify/mailbox-contract.sh --open=TASK-236 --baseline=69cb306` → 退出码 0（判据 A 两件套齐 + 判据 B 只改清单与实际改动集一致）。
