# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，HEAD 对应最新封盘提交（可通过 `git log -1 --oneline` 确认）。
- **外部 CI 门槛状态**：GitHub Actions run `38586443704`（`web` ✓31s / `build` ✓11m46s 全绿）。
- **工作树状态**：TASK-229 本地闭环，在途文件已完成台账归档。
- **当前在途任务数**：`0` 个在途（历史任务至 TASK-229 全部已闭环收口）。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 完成了全仓 Markdown 资产治理与主子 Agent 协作机制重构；
  2. `work/mailbox/PLAN.md` 完成瘦身并就地滚动维护，历史记录完整归档至 `work/mailbox/archive/PLAN-history-001-210.md`；
  3. `docs/internal/AGENTS.md` 确立唯一长效指导标准；
  4. 落地 **TASK-229**（web 交互面修整批，MEDIUM 档）：
     - 精确化 Vite proxy 前缀并增加 `text/html` bypass，解决直接在地址栏直载 `/record-submit`、`/record-detail`、`/admin*` 等深链时误代理到网关的缺陷；
     - 将 `record-submit.page.vue` 的 `a-alert` 详情内容包覆于 `<template #description>`，解决 antdv 4.2.6 下详情未落屏问题；
     - 本地全绿（type-check / 31 test / build 3317 modules / typed-router 零 diff / 契约无参 rc=0）。
- **历史业务批次（TASK-226 ~ TASK-228）**：
  - TASK-228：修复 Web 启动级路由缺陷（`createRouter` 显式注入 routes）；
  - TASK-227：E2E 真实浏览器验证批全绿（12 用例 route-mock 达成）；
  - TASK-226：LIGHT 清理批落地。

---

## 3. 当前已知边界与挂账限制（避免重踩已定位的坑）

1. **子 Agent 边界**：子 Agent 严禁修改 `work/mailbox/PLAN.md` 与本文件，子 Agent 仅在自身 `work/mailbox/tasks/TASK-xxx/` 下提交回传。
2. **契约校验口径**：运行 `scripts/verify/mailbox-contract.sh` 必须在 Git Bash（`D:\git\Git\bin\bash.exe`）下执行。
3. **挂账候选事项**：
   - `H2` 生产部署姿态演练（CORS 真实注入/跨域 cookie/TLS 终止协同）；
   - `F5` 榜单贡献回撤（TASK-223 D1 挂账）。

---

## 4. 下一个主 Agent 的唯一推荐动作

- **推荐动作**：从 [`work/mailbox/BACKLOG.md`](BACKLOG.md) 取下一个优先级任务进行立项（当前候选首位为 **H2 部署姿态演练** 或 **F5 榜单贡献回撤**）。
- **开工准备**：向用户确认是否启动 H2 或 F5 候选任务；确认后按 `docs/internal/AGENTS.md` 的「极简任务派发包模板」开工执行。

---

## 5. 新主 Agent 接手操作规约（3 步启动法）

当用户在新的对话窗口中输入接手指令时，新主 Agent 必须按以下 3 步执行：
1. **核验状态**：执行 `git status --short` 与 `git log -1 --oneline`，核实是否与本文件第 1 节一致；
2. **拒绝冗余**：**绝不新建任何交接类 Markdown 文件**；
3. **极简汇报**：直接向用户输出以下结构性回答：
   - **接手状态**：当前 HEAD 与最近闭环任务；
   - **拟执行动作**：从第 4 节推荐动作确认下一步计划；
   - **需用户决策**：如有产品决策或授权需求提出提问，否则给出下一步任务包并请用户拍板派发。
