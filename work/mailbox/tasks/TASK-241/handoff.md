# TASK-241 回传台账（C-02）

> 任务：前台交互闭环与客户端幂等防重——申诉管理直达自动回填与运动记录提交表单 requestId 固化加固（MEDIUM 档）
> 开工基线：92df499｜实施笔：C-01｜台账笔：C-02
> 回传方：实施侧 Agent

## 1. 改动清单（严格白名单，恰 6 文件）

- web/src/pages/verdict.page.vue
- web/src/pages/admin-appeal.page.vue
- web/src/pages/record-submit.page.vue
- web/src/api/__tests__/client.test.ts
- work/mailbox/tasks/TASK-241/spec.md
- work/mailbox/tasks/TASK-241/handoff.md

> 其余任何文件均零触碰：后端、网关、package.json、根 pom.xml、PLAN.md、HANDOVER.md 未改。

## 2. 改动明细与设计说明

### 2.1 B8 申诉复核管理后台端到端闭环（verdict.page.vue & admin-appeal.page.vue）

- **verdict.page.vue**：申诉成功（`appealResult.id` 存在）时，成功 alert 内以 `#message` 模板渲染「去管理后台审核 &rarr;」`router-link`，跳转 `/admin-appeal?id=${appealResult.id}`，管理员免盲打直达终判。
- **admin-appeal.page.vue**：
  - 引入 `useRoute`（来自 `vue-router/auto`）与 `onMounted`；挂载时读取 `route.query.id || route.query.appealId` 并 `String()` 回填至 `form.id`，消除手工盲打字；
  - 终判成功结果卡片中，基于 `result.recordId` 渲染「查看记录最新判定结果 &rarr;」`router-link`，跳转 `/verdict?id=${result.recordId}`，形成「verdict → admin-appeal → verdict」完整闭环。
- 设计说明：全部使用 `useRoute`/`onMounted`（与 verdict.page.vue 既有用法一致），未引入新依赖；回填与链接均判空防御。

### 2.2 B9 提交表单客户端 requestId 固化加固（record-submit.page.vue）

- **防空防窜**：`genRequestId()` 在 `submitting.value === true` 时短路由直接返回（在途轮换即破坏幂等键）；requestId 输入框与「重新生成」按钮加 `:disabled="submitting"`；`onSubmit()` 开头检查 `requestId` 空/空白则先 `genRequestId()`。
- **重试固化**：提交失败（网络超时/异常）时严格保持当前 `requestId` 不变；`submitError` 展示区新增 `#description` 文案：网络抖动或失败，保持当前 requestId 再次提交可安全重试（服务端幂等防重）。
- **下一条轮换**：提交成功且非 `duplicated` 时在结果区新增「准备提交下一条（刷新 requestId）」按钮，调用 `prepareNextRecord()`：重新生成 `requestId` 并清空 `submitResult` 与 `submitError`，避免后续同界面提交撞 3004。
- **3004 友好反馈**：捕获 `e.code === 3004` 时 `submitError` 明确展示「重复提交拦截（幂等命中，返回原记录）」，并顺带将 `e.data` 回填 `submitResult`（`duplicated=true`）便于展示原记录。
- 设计说明：requestId 一旦生成便作为幂等键自始至终不变（重试复用），仅在显式「准备下一条」时才轮换，与服务端幂等语义严格对齐；零新依赖。

### 2.3 契约与测试强化（client.test.ts）

新增两个 describe：

- **submitAppeal（TASK-241）**：校验请求打 `POST /record/api/records/{id}/appeal`，body 为 `{ recordId, reason }`；字符串 id 会被规范化进 URL 并 `Number()` 进 body。
- **reviewAppeal（TASK-241）**：校验请求打 `POST /admin/api/appeals/{id}/review`，body 为 `{ operator, pass, recheckResult? }`；`recheckResult` 缺失时不下发、`pass` 透传布尔值。
- 沿用现有 `adapter` 注入、`ok()` 构造响应、请求拦截器全真实的既有测试范式；未 mock axios 自身。

## 3. 门禁结果

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| 前端类型 | `pnpm run type-check`（web 目录） | 通过，0 错误 |
| 前端单测 | `pnpm test`（web 目录） | 通过，37/37 用例全绿（≥35） |
| 契约门禁 | `bash scripts/verify/mailbox-contract.sh --open=TASK-241 --baseline=92df499` | 通过，退出码 0 |
| 红线复核 | `git diff --name-only 92df499` | 严格全等于 6 个白名单文件 |

## 4. 交付提交

- **C-01 实施笔**（恰 4 代码与测试文件）：`feat(web): TASK-241 申诉管理自动回填与记录提交幂等固化（C-01 实施笔）`
- **C-02 台账笔**（恰 2 台账文件）：`docs(mailbox): TASK-241 申诉闭环与表单幂等加固验收与台账闭环（C-02 台账笔）`

## 5. 未决事项与遗留

- 无未决事项；B8 闭环与 B9 幂等加固均按 spec §3 判别式实现并通过全部门禁。