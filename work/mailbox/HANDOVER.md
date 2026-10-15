# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，最新实施提交为 `ceec5c6`，对应台账已闭环（详见 `git log -2 --oneline`）。
- **外部 CI 门槛状态**：GitHub Actions run `38586443704`（`web` ✓31s / `build` ✓11m46s 全绿）。
- **工作树状态**：TASK-232 独立复核全绿通过并已封盘，在途文件已完成台账归档。
- **当前在途任务数**：`0` 个在途（历史任务至 TASK-232 全部已闭环收口）。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 落地 **TASK-232**（个人统计 SQL 聚合下推与页面加载态解耦，MEDIUM 档）：
     - 服务端下推：`SportRecordMapper` 新增 `selectAggByUserId` 注解 SQL，采用 `<script>+<foreach>` 动态绑定 `PASS_STATUS_CODES` 枚举，单行查出 `COUNT(*)`、`COALESCE(SUM(distance),0)`、`COUNT(CASE WHEN status IN (...) THEN 1 END)`，映射至新增 `RecordAggDTO`；
     - 30 天每日里程窗口下推：`selectDailyDistanceSince(userId, windowStart)` 增加时间窗下界过滤，命中既有复合索引 `idx_user_time (user_id, created_at)` 做范围扫描，仅拉取近 30 天记录，彻底消灭全历史记录回表与大内存缓冲开销；
     - 边界健壮性：`SportRecordService.myStats` 增加聚合行缺失全 0 兜底，`setScale(2, HALF_UP)` 归一化；
     - 前端解耦体验：`index.page.vue` 拆分 `loadRecords()` 与 `loadStats()`，列表翻页（`onPageChange`）与筛选变更（`onFilterChange`, `resetFilters`）仅调用轻量 `loadRecords()`，不再重复打统计与打卡接口；解耦 `tableLoading` 与首屏 `loading`，翻页时不整卡闪烁；
     - 独立复核通过：用户派发独立复核子 Agent 终审四块全过（总体 PASS，MyBatis 3.5.16 注解 SQL 解析验证通过，7 文件白名单全等，偏差 D1/D2 与 N1~N5 提示留痕在案）。
  2. 落地 **TASK-231**（个人统计服务端聚合端点 /my/stats 与页面历史文案清理，MEDIUM 档）：
     - 契约端点：`GET /record/api/records/my/stats`（与 `/my`、`/my/streak` 同构鉴权），`SportRecordService.myStats` 投影查询单查全量内存聚合，输出 `MyStatsDTO`，彻底清偿 TASK-211 的 500 条截断技术债；
     - 前端解耦：`client.getMyStats` 导出，`index.page.vue` 统计卡三项与每日里程条只读聚合结果，表格翻页与过滤不再影响统计卡；
     - 文案清理：彻底清除 `index.page.vue` 与 `record-submit.page.vue` 中陈旧的「无列表 API」历史文案。
  3. 落地 **TASK-230**（删除记录联动回撤榜单里程贡献，F5 候选，TASK-223 D1 挂账，MEDIUM 档）：
     - 契约打通：`LeaderboardApi` 新增 `POST /internal/leaderboard/records/{recordId}/rollback` 内部端点，写路径 fallback 显式抛 4008 拒绝静默；
     - 服务端复用：`InternalLeaderboardController` 承接既有 `leaderboardService.rollbackOnRejected`，复用其分布式锁与乐观锁状态机；
     - 记录端协同：`SportRecordService` 经 setter 注入 `LeaderboardApi`，在 `deleteRecord` 事务 `afterCommit` 内 best-effort 调用，坚守最终一致性（ADR-0009）。
  4. 落地 **TASK-229**（web 交互面修整批，MEDIUM 档）：
     - 精确化 Vite proxy 前缀并增加 `text/html` bypass，解耦地址栏直载 SPA 路由被误代理问题；
     - `record-submit.page.vue` 的 `a-alert` 详情内容包覆于 `<template #description>`，解决 antdv 4.2.6 下详情未落屏问题。
- **历史业务批次（TASK-226 ~ TASK-228）**：
  - TASK-228：修复 Web 启动级路由缺陷（`createRouter` 显式注入 routes）；
  - TASK-227：E2E 真实浏览器验证批全绿（12 用例 route-mock 达成）；
  - TASK-226：LIGHT 清理批落地。

---

## 3. 当前已知边界与挂账限制（避免重踩已定位的坑）

1. **子 Agent 边界**：子 Agent 严禁修改 `work/mailbox/PLAN.md` 与本文件，子 Agent 仅在自身 `work/mailbox/tasks/TASK-xxx/` 下提交回传。
2. **契约校验口径**：运行 `scripts/verify/mailbox-contract.sh` 必须在 Git Bash（`D:\git\Git\bin\bash.exe`）下执行；无参模式短路跳过判据 B，白名单一致性以开工 `--open` 与直接 `git diff` 为准。
3. **挂账候选事项**：
   - `T1` TimingHikari 计时测试稳定化（TASK-232 复核 D1 发现：`TimingHikariDataSourceWiringTest` 1000ms 墙钟硬编码阈值在宿主高负载下 1012ms 偶发抖动红，需提高容差或改用更稳定的断言方式）；
   - `H5` Web E2E 路由 mock 同步与断言对齐（清偿 TASK-231 N-3 遗留：`web/e2e/helpers.ts` 补 `/my/stats`，同步 `record-list.e2e.ts` 三断言）；
   - `H2` 生产部署姿态演练（CORS 真实注入/跨域 cookie/TLS 终止协同）；
   - TASK-230 遗留提示：删除已提交后若 Feign 调用或锁失败，锚点可能残留 ACTIVE，依赖 WARN 日志人工排查（后续可安排离线对账补齐）。

---

## 4. 下一个主 Agent 的唯一推荐动作

- **推荐动作**：
  1. 首选推荐：立项 **T1（TimingHikari 计时测试稳定化）**，LIGHT 档，单文件微调，消除既有测试债引起的 CI 偶发红风险；
  2. 次选推荐：立项 **H5（Web E2E 路由 mock 同步与断言对齐，清偿 TASK-231 遗留漂移）**，范围小（仅 `helpers.ts` + `record-list.e2e.ts`）、确定性极高，迅速消除 E2E 潜在断言红线；
  3. 推进生产：立项 **H2（E5 生产部署姿态演练）**，推进上生产前的跨域/TLS/Cookie 验证。
- **开工准备**：向用户确认下一步任务选择，确认后出具标准「极简任务派发包」供用户派发执行子 Agent。

---

## 5. 新主 Agent 接手操作规约（3 步启动法）

当用户在新的对话窗口中输入接手指令时，新主 Agent 必须按以下 3 步执行：
1. **核验状态**：执行 `git status --short` 与 `git log -1 --oneline`，核实是否与本文件第 1 节一致；
2. **拒绝冗余**：**绝不新建任何交接类 Markdown 文件**；
3. **极简汇报**：直接向用户输出以下结构性回答：
   - **接手状态**：当前 HEAD 与最近闭环任务；
   - **拟执行动作**：从第 4 节推荐动作确认下一步计划；
   - **需用户决策**：如有产品决策或授权需求提出提问，否则给出下一步任务包并请用户拍板派发。
