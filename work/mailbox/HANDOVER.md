# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，最新实施提交为 `5e675e1`（C-01）与 `64459c8`（C-03），对应台账已闭环（详见 `git log -3 --oneline`）。
- **外部 CI 门槛状态**：GitHub Actions run `38586443704`（`web` ✓31s / `build` ✓11m46s 全绿）。
- **工作树状态**：TASK-233 独立复核全绿并通过 C-03 订正闭环，在途文件已完成台账归档。
- **当前在途任务数**：`0` 个在途（历史任务至 TASK-233 全部已闭环收口）。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 落地 **TASK-233**（测试稳定性强化与 Web E2E 路由契约对齐，MEDIUM 小档）：
     - 后端计时测试稳定化：放宽 `firstCloseMs/doubleCloseMs < 3000ms` 消除调度抖动，且经独立复核 BLOCKER-1 订正（C-03 笔 `64459c8`），将 `setConnectionTimeout` 上调至 `10000ms`（并联动 `join(15s)`），恢复了 3.3 倍以上的反死锁真实判别力，健康 close 保持 `< 2ms`；
     - Web E2E 路由契约对齐：`helpers.ts` 补齐 `GET /record/api/records/my/stats` mock（返回 25 条测试夹具的全量 24.0% / 194.00 统计数据），同步更新 `record-list.e2e.ts` 断言；
     - 严格模式修复：`record-submit.e2e.ts` 定位器收窄至 `.ant-alert`，消除 TASK-229 引入的同名元素冲突；Playwright E2E 本地全量实跑 **12 passed** 100% 全绿；
     - 生产与配置零触碰：生产代码（全部 `src/main` 与 `web/src`）及红线文件 100% 零触碰。
  2. 落地 **TASK-232**（个人统计 SQL 聚合下推与页面加载态解耦，MEDIUM 档）：
     - 服务端下推：`SportRecordMapper` 新增 `selectAggByUserId` 注解 SQL，单行查出 `COUNT(*)`、`COALESCE(SUM(distance),0)`、`COUNT(CASE WHEN status IN (...) THEN 1 END)`；
     - 30 天每日里程窗口下推：`selectDailyDistanceSince` 增加时间窗下界过滤，命中既有复合索引 `idx_user_time (user_id, created_at)` 做范围扫描，彻底消灭全历史记录回表与大内存缓冲开销；
     - 前端解耦体验：`index.page.vue` 拆分 `loadRecords()` 与 `loadStats()`，列表翻页仅调用轻量 `loadRecords()`，不再重复打统计与打卡接口；解耦 `tableLoading` 与首屏 `loading`。
  3. 落地 **TASK-231**（个人统计服务端聚合端点 /my/stats 与页面历史文案清理，MEDIUM 档）：
     - 契约端点：`GET /record/api/records/my/stats`，单查全量轻量投影并在内存聚合，输出 `MyStatsDTO`，清偿 TASK-211 的 500 条截断技术债；
     - 前端解耦：统计卡与每日里程条只读聚合结果；彻底清洗页面中陈旧的「无列表 API」历史文案。
  4. 落地 **TASK-230**（删除记录联动回撤榜单里程贡献，MEDIUM 档）与 **TASK-229**（web 交互面修整批，MEDIUM 档）。
- **历史业务批次（TASK-226 ~ TASK-228）**：
  - TASK-228：修复 Web 启动级路由缺陷；
  - TASK-227：E2E 真实浏览器验证批全绿（12 用例 route-mock 达成）；
  - TASK-226：LIGHT 清理批落地。

---

## 3. 当前已知边界与挂账限制（避免重踩已定位的坑）

1. **子 Agent 边界**：子 Agent 严禁修改 `work/mailbox/PLAN.md` 与本文件，子 Agent 仅在自身 `work/mailbox/tasks/TASK-xxx/` 下提交回传。
2. **契约校验口径**：运行 `scripts/verify/mailbox-contract.sh` 必须在 Git Bash（`D:\git\Git\bin\bash.exe`）下执行；无参模式短路跳过判据 B，白名单一致性以开工 `--open` 与直接 `git diff` 为准。
3. **挂账候选事项**：
   - `H2` 生产部署姿态演练（CORS 真实注入/跨域 cookie/TLS 终止协同）；
   - TASK-230 遗留提示：删除已提交后若 Feign 调用或锁失败，锚点可能残留 ACTIVE，依赖 WARN 日志人工排查（后续可安排离线对账补齐）。

---

## 4. 下一个主 Agent 的唯一推荐动作

- **推荐动作**：立项 **H2（E5 生产部署姿态演练）**，推进上生产前的跨域/TLS/Cookie 验证（其余待办已全部清零）。
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
