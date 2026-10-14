# TASK-229: web 交互面修整批（H4 候选，MEDIUM 档）

> **开工基线**：2829f34（main 分支，工作树干净）
> **任务级别**：MEDIUM

## 1. 目标与背景

在 TASK-227 E2E 真实浏览器验证批中附带发现并登记的两个体验问题：
1. **Vite proxy 前缀直载误伤**：`web/vite.config.ts` 中的 `proxy` 配置键使用了无尾随斜杠的前缀（如 `'/record'`、`'/admin'`），在 `preview` 或 `dev` 模式下直接通过地址栏访问前端页面深链（如 `/record-submit`、`/record-detail`、`/admin`、`/admin-appeal`、`/admin-rules`）时，由于前缀匹配，请求被误代理到网关端口（若后端未启动则报错 ECONNREFUSED 或 500）；需要精确化代理前缀并增加页面导航 bypass 处理。
2. **a-alert 插槽落屏缺失**：`web/src/pages/record-submit.page.vue` 中的提交结果 `a-alert` 组件内部详情信息（`recordId`、`status`、`duplicated`、操作按钮）直接写在默认插槽中，而在 `ant-design-vue` 4.2.6 下 `a-alert` 辅助详情必须声明在 `#description` 插槽内，导致详情未正常渲染到页面上；需调整为具名插槽 `<template #description>`。

## 2. 修改白名单

严格限定在以下 2 个前端文件（加台账文件）：
1. `web/vite.config.ts`
2. `web/src/pages/record-submit.page.vue`
3. 台账文件：`work/mailbox/tasks/TASK-229/spec.md`、`work/mailbox/tasks/TASK-229/handoff.md`、`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md`、`work/mailbox/HANDOVER.md`

零后端改动，零依赖包改动。

## 3. 核心判别式与验证要求

- **判别式 R1**：`web/vite.config.ts` 的代理前缀精确到斜杠层级（`/record/`、`/admin/` 等）并提供针对 `text/html` 的 SPA 回落机制，解决 `/record-submit` 等前缀同名页面路由的被拦截问题。
- **判别式 R2**：`record-submit.page.vue` 将详情内容正确包覆在 `<template #description>` 中，确保 antdv 4.2.6 下落屏。
- **验证命令**：
  - `pnpm --dir web type-check`
  - `pnpm --dir web test`
  - `pnpm --dir web build`
  - `git diff --exit-code -- web/src/typed-router.d.ts`
  - 契约门禁：`bash scripts/verify/mailbox-contract.sh`

## 4. 停止边界

- 若需引入新的外部依赖包，或改动后端 Gateway / Record 服务配置，立即停手回报。
- 若修改超出白名单文件范围，立即停手。
