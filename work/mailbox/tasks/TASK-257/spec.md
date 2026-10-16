# TASK-257 任务书（spec）

> **TASK**：E 系批4b-2——前端测试可观测三项（E8 并发 401 共享刷新 + E14 E2E mock 兜底可见性与通知写路径 + E26 陈旧响应序号守卫，MEDIUM 档）
> **开工基线**：`1772102`（TASK-256 封盘合并后 main HEAD；开工自证 `git rev-parse HEAD` = 1772102f437c1f9cc23f655da2103f842f454f78、`git status --porcelain` 为空，实施侧已回填）
> **方案**：2026-10-16 E 系批4b 指导侧立项侦察三项全部坐实；批4b-2 为纯前端批次，与批4b-1（TASK-256 后端八项）串行执行
> **档位**：MEDIUM（纯前端界面性/测试可观测改动，零核心逻辑零安全语义——派发 + 白名单 + 门禁（offline 全量/契约/token/词面照跑）+ 指导侧亲核，免独立复核；CI 外部终验即收口）
> **状态**：已立项待派发（2026-10-16 立项笔落库，串行 TASK-256 之后）

## 1. 背景与侦察结论（2026-10-16 指导侧亲验，三项全部坐实）

- **E8 坐实（并发 401 互踢）**：`web/src/api/client.ts` L124-153——每个 401 各自 `await api.post('/api/auth/refresh')` 无共享 in-flight Promise；后端 refresh 原子消费旧 jti（Lua 取走并删除，并发仅一赢家），败者 catch → `triggerAuthFail()` 把赢家刚写入的 access 一并清 → 并发页（多组件同时 401，如首页统计+连续打卡+目标并发拉取）必然整页踢回登录。
- **E14 坐实（mock 兜底假安全）**：`web/e2e/helpers.ts` 两前缀内未映射请求兜底 404 `code:9999 mock unmapped`（L175/L226）不入 unhandled 断言（只有两前缀之外的请求才进 unhandled）；`DELETE /user/api/notifications/read`（清空已读）与 `PATCH /user/api/notifications/batch-read`（批量已读）两写端点无 mock；通知页三按钮写路径零 E2E 覆盖（e2e 目录恰 8 文件无 notifications 用例）。
- **E26 坐实（陈旧响应覆盖）**：`leaderboard.page.vue` loadLb（L97-114）无请求序号/AbortController——快速切换类型/模式/条数时旧响应覆盖新态、finally 提前置 loading=false；feed/index/notifications 分页加载同款（E 系侦察行号级证据 + 本轮 loadLb 亲验坐实）。

## 2. 改动文件白名单（10 项，双向全等硬约束）

业务/测试 10 项：

1. web/src/api/client.ts（E8 R1 共享刷新）
2. web/src/api/__tests__/client.test.ts（E8 R1 扩展）
3. web/README.md（E8 R1 语义同步）
4. web/e2e/helpers.ts（E14 R2 unmocked 可见性 + 补 mock）
5. web/e2e/fixtures/notifications.ts（E14 R2 状态化改造）
6. web/e2e/notifications.e2e.ts（E14 R2 新建）
7. web/src/pages/leaderboard.page.vue（E26 R3）
8. web/src/pages/feed.page.vue（E26 R3）
9. web/src/pages/index.page.vue（E26 R3）
10. web/src/pages/notifications.page.vue（E26 R3）

台账 2 项（C-02 阶段）：work/mailbox/tasks/TASK-257/spec.md、work/mailbox/tasks/TASK-257/handoff.md。

条件项：E14 清障过程中若既有 8 个 e2e 用例暴露其他未映射端点，允许将实际需补 mock 的既有 fixture 文件追加白名单（C-01 前登记——沿 TASK-250 条件项先例）。

零触碰声明（白名单外全仓零 diff）：后端全部模块与 api、typed-router.d.ts（无页面增删路由不变）、package.json / pnpm-lock.yaml（零新依赖）、playwright.config.ts、其余 pages / components / utils / e2e 用例文件（既有 8 文件断言面除 unmocked 扩展外不动——如需动以条件项登记）、PLAN.md、HANDOVER.md、BACKLOG.md。

## 3. 实现要求（判别式）

### R1（E8）并发 401 共享刷新

- `client.ts` 模块级共享 in-flight：单例 `refreshPromise`，首个 401 发起 refresh，并发 401 await 同一 Promise；settled 后清引用（finally）；`/api/auth/*` 跳过清单与 `_retry` 单请求单次语义不变；失败路径 triggerAuthFail 幂等保持（多败者并发触发不放大）；
- `client.test.ts` 新增：两并发 401 → 恰一次 refresh 调用 + 两原请求均以新 token 重试成功；refresh 失败 → 登出触发、原请求不重试；
- `web/README.md` L51/L61 语义同步（「并发 401 共享单次刷新，败者不再清赢家 token」）。

### R2（E14）mock 兜底可见性与通知写路径

- `helpers.ts`：ApiCtx 增 `unmocked` 列表——两前缀内未映射请求在兜底 404 前记入；既有 assertNoUnhandled 之外新增 no-unmocked 断言并接入全部用例；
- 补齐两写端点 mock：`DELETE /user/api/notifications/read`（清空已读）与 `PATCH /user/api/notifications/batch-read`（批量已读）——`fixtures/notifications.ts` 由静态常量改 `createNotificationStore` 状态记忆（沿 records store 先例：列表/未读数随写操作流转）；
- 新建 `notifications.e2e.ts`：预置登录态 → 通知页 → 列表渲染 + 三按钮（标记已读/批量已读/清空已读）真实点击交互断言（store 状态驱动的 UI 变化 + 未读徽标收敛）；
- 清障全量跑：任何既有用例暴露的其他未映射端点补 mock 至全量零 unmocked（暴露面以既有用例真实调用为界，条件项登记）。

### R3（E26）陈旧响应序号守卫

- 四页面加载函数加请求序号守卫：页面内局部计数器，加载前置自增捕获当前序号，await 返回后序号不一致则丢弃（不写数据态、不写错误态、不清 loading）；一致才写态，finally 仅当前序号清 loading；
- 内联实现不抽公共 composable（每页四行，避免过早抽象）；错误分支同守卫（陈旧请求的错误不覆盖新态提示）；
- `leaderboard` 新增 E2E 快速切换用例：首请求延迟 fulfill、快速切换类型，断言终态为后发请求数据（route mock 延迟控制）——若时序控制不稳定，降级为静态审查 + 受限登记（§5 预注册）。

## 4. 测试与门禁要求

- 后端 offline 全量七模块持平基线（59/75/205/302/195/108/35，白名单零后端文件凭零 diff 论证不变）；
- 前端 type-check（vue-tsc 0 错）+ vitest（既有 4 文件 61 测 + client 新增全绿）+ E2E 全量（既有 8 用例文件 + notifications 新增全绿，零真实网络请求断言保持）；
- 契约门 --open=TASK-257 --baseline=&lt;开工基线&gt;；token 门 29 项只增不减；词面门模式文件法 + 探针阳性对照；diff 净（新文件 LF+末尾换行）。

## 5. 预注册受限与授权

1. E8 真实弱网并发未实测（单测以 mock axios 时间片收敛语义承载，真实浏览器多组件并发 401 场景无 E2E 覆盖）。
2. E26 E2E 时序模拟若不稳定（延迟 fulfill 竞态），降级为组件行为静态审查 + 受限登记。
3. E14 unmocked 清障以既有用例真实调用面为界——未覆盖页面（无 e2e 用例者）不做穷举扩面。

## 6. 纪律

- 双提交：C-01 实施（恰上述 10 项业务/测试文件 + 条件项按 C-01 前登记）+ C-02 台账（spec/handoff 纯追加回填，含开工基线回填）；提交消息 type(scope): description（TASK-257）。
- 新文件纯 LF + 完整末尾换行；改文件保持既有行尾；git diff --check 净。
- 禁 stash / add -A / 改归档 / 动 .codex 与 .trae；临时件放 .trae/tmp/ 用后删。
- Bash 脚本仅经 D:\git\Git\bin\bash.exe 执行。
- 停止条件：任一判别式无法按本任务书落地、需触碰零触碰面、E2E 套件既有用例无法保持全绿（除 unmocked 清障目标性订正外）、offline 基线回退——立即停止修改并汇报，不得自行变通。
