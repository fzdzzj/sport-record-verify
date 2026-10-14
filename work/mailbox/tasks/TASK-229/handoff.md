# TASK-229 handoff：web 交互面修整批（H4 候选，MEDIUM 档）

## 1. 编号、基线与外部状态

- 课题：TASK-229
- 任务书：`work/mailbox/tasks/TASK-229/spec.md`
- 开工基线：`2829f34`（main 分支，工作树干净）
- 级别：MEDIUM 档

## 2. 改动清单

- web/src/pages/record-submit.page.vue
- web/vite.config.ts
- work/mailbox/tasks/TASK-229/spec.md
- work/mailbox/tasks/TASK-229/handoff.md
- work/mailbox/BACKLOG.md
- work/mailbox/HANDOVER.md
- work/mailbox/PLAN.md

> 零后端改动，零依赖包改动，零其他页面与组件触碰。

## 3. 关键改动

1. **Vite 代理与直载 SPA 路由解耦（web/vite.config.ts）**：
   - 将原容易模糊匹配的代理前缀精确到斜杠路径（`/record/`、`/admin/`）；
   - 为各微服务代理规则增加 `bypass: (req) => (req.headers.accept?.includes('text/html') ? req.url : undefined)`，当浏览器直接通过地址栏访问深链页面（如 `/record-submit`、`/record-detail`、`/admin`、`/admin-appeal`、`/admin-rules`）时，`Accept: text/html` 自动 bypass 网关代理，直接由 Vite SPA 正常回落渲染前端应用，消除了无后端时地址栏直载抛 ECONNREFUSED/500 的问题。
2. **Alert 插槽落屏修正（web/src/pages/record-submit.page.vue）**：
   - 将提交结果 `a-alert` 的默认插槽内容包裹在 `<template #description>` 具名插槽中，符合 `ant-design-vue` 4.2.6 的组件规约，确保 `recordId`、状态及操作按钮在提交后稳定渲染落屏。

## 4. 验证结果

- **类型检查**：`pnpm --dir web type-check` 退出码 0，无任何类型错误。
- **单元测试**：`pnpm --dir web test` 4 个测试套件（31 tests）全部通过，退出码 0。
- **生产构建**：`pnpm --dir web build` 顺利产出 3317 modules，退出码 0（应用 TEMP 重定向到 gitignored 临时目录规避 Windows esbuild 锁）。
- **路由类型契约**：`git diff --exit-code -- web/src/typed-router.d.ts` 零 diff，退出码 0。
- **判别式断言**：PowerShell 校验 `record-submit.page.vue` 的 `#description` 结构与 `vite.config.ts` 的 `/record/`、`/admin/` 及 `bypass` 逻辑全绿。

## 5. 遗留风险与未覆盖点

- 本地验证通过，未向远端进行 `git push`（待统一封盘）。
- E2E 真实浏览器验证批可按需在下阶段连同其他业务链路整体验收。
