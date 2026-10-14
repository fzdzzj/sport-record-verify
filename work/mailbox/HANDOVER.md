# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，实施提交为 `4bae3fe`，对应台账封盘提交可通过 `git log -1 --oneline` 确认。
- **外部 CI 门槛状态**：GitHub Actions run `38586443704`（`web` ✓31s / `build` ✓11m46s 全绿）。
- **工作树状态**：TASK-230 本地闭环，在途文件已完成台账归档。
- **当前在途任务数**：`0` 个在途（历史任务至 TASK-230 全部已闭环收口）。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 落地 **TASK-230**（删除记录联动回撤榜单里程贡献，F5 候选，TASK-223 D1 挂账，MEDIUM 档）：
     - 契约打通：`LeaderboardApi` 新增 `POST /internal/leaderboard/records/{recordId}/rollback` 内部端点，写路径 fallback 显式抛 4008 拒绝静默；
     - 服务端复用：`InternalLeaderboardController` 承接既有 `leaderboardService.rollbackOnRejected`，复用其分布式锁与乐观锁状态机；
     - 记录端协同：`SportRecordService` 经 setter 注入 `LeaderboardApi`（构造方法维持 7 参符合 Checkstyle 家法），在 `deleteRecord` 事务 `afterCommit` 内 best-effort 调用，异常吞并留证，坚守最终一致性（ADR-0009）；
     - 独立复核通过：offline 单元测试全绿（common 52 / record 255 / leaderboard 85 全通过），Checkstyle 0 违规，契约门无参退出码 0。
  2. 落地 **TASK-229**（web 交互面修整批，MEDIUM 档）：
     - 精确化 Vite proxy 前缀并增加 `text/html` bypass，解耦地址栏直载 SPA 路由被误代理问题；
     - `record-submit.page.vue` 的 `a-alert` 详情内容包覆于 `<template #description>`，解决 antdv 4.2.6 下详情未落屏问题。
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
   - TASK-230 遗留提示：删除已提交后若 Feign 调用或锁失败，锚点可能残留 ACTIVE，依赖 WARN 日志人工排查（后续可安排离线对账补齐）。

---

## 4. 下一个主 Agent 的唯一推荐动作

- **推荐动作**：从 [`work/mailbox/BACKLOG.md`](BACKLOG.md) 取下一个优先级任务进行立项（当前候选首位为 **H2 部署姿态演练**）。
- **开工准备**：向用户确认是否启动 H2 候选任务；确认后按 `docs/internal/AGENTS.md` 的「极简任务派发包模板」为子 Agent 派发。

---

## 5. 新主 Agent 接手操作规约（3 步启动法）

当用户在新的对话窗口中输入接手指令时，新主 Agent 必须按以下 3 步执行：
1. **核验状态**：执行 `git status --short` 与 `git log -1 --oneline`，核实是否与本文件第 1 节一致；
2. **拒绝冗余**：**绝不新建任何交接类 Markdown 文件**；
3. **极简汇报**：直接向用户输出以下结构性回答：
   - **接手状态**：当前 HEAD 与最近闭环任务；
   - **拟执行动作**：从第 4 节推荐动作确认下一步计划；
   - **需用户决策**：如有产品决策或授权需求提出提问，否则给出下一步任务包并请用户拍板派发。
