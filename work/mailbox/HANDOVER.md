# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，最新实施提交为 `169e367`（C-01）与 `54977f5`（C-02），对应台账已闭环（详见 `git log -3 --oneline`）。
- **外部 CI 门槛状态**：GitHub Actions run `38586443704`（`web` ✓31s / `build` ✓11m46s 全绿）。
- **工作树状态**：TASK-234 独立复核全绿，在途文件已完成台账归档。
- **当前在途任务数**：`0` 个在途（历史任务至 TASK-234 全部已闭环收口）。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 落地 **TASK-234**（原有功能健壮化：物理合理性推导、榜单周期联调与目标边界防御，MEDIUM 档）：
     - 记录物理合理性与自动推导：`SportRecordService.submit` 增加防倒流（`startTime <= endTime`）与防未来时间校验；未显式提供且 points 非空时，首末点毫秒（UTC 墙钟）推导时间/时长，相邻点 Haversine 球面距离累加推导 distance，根除库内关键字段存 NULL 破窗；前端提交页 `record-submit.page.vue` 同步带齐推导字段；
     - 榜单周期死逻辑修复：`leaderboard.page.vue` 重构为 `lbMode`（`range` 视图与 `zset` 视图），激活「今日 / 本周 / 本月」对 `getLeaderboardRange` 的真实调用，死代码彻底消除；
     - 目标边界与整数性收紧：`GoalService.upsertGoal` 对 `metric=1`（次数）采用 `stripTrailingZeros().scale() <= 0` 强制要求正整数（兼容 `8.00` 等尾零数值），设置 `<= 10000` 合理上限防溢出；周期窗口过滤由 `createdAt` 改为 `COALESCE(start_time, created_at)`，离线补传运动归属周期得到修正；
     - 前端展示补齐：`index.page.vue` 可见性列补齐 `visibility === 0` 绿色「公开」标签。
  2. 落地 **TASK-233**（测试稳定性强化与 Web E2E 路由契约对齐，MEDIUM 小档）：
     - 后端计时测试稳定化：放宽 `firstCloseMs/doubleCloseMs < 3000ms` 消除调度抖动，且经独立复核 BLOCKER-1 订正（C-03 笔 `64459c8`），将 `setConnectionTimeout` 上调至 `10000ms`（并联动 `join(15s)`），恢复了 3.3 倍以上的反死锁真实判别力，健康 close 保持 `< 2ms`；
     - Web E2E 路由契约对齐：`helpers.ts` 补齐 `GET /record/api/records/my/stats` mock（返回 25 条测试夹具的全量 24.0% / 194.00 统计数据），同步更新 `record-list.e2e.ts` 断言；
     - 严格模式修复：`record-submit.e2e.ts` 定位器收窄至 `.ant-alert`，消除 TASK-229 引入的同名元素冲突；Playwright E2E 本地全量实跑 **12 passed** 100% 全绿；
     - 生产与配置零触碰：生产代码（全部 `src/main` 与 `web/src`）及红线文件 100% 零触碰。
  3. 落地 **TASK-232**（个人统计 SQL 聚合下推与页面加载态解耦，MEDIUM 档）。
  4. 落地 **TASK-231**（个人统计服务端聚合端点 /my/stats 与页面历史文案清理，MEDIUM 档）。
  5. 落地 **TASK-230**（删除记录联动回撤榜单里程贡献，MEDIUM 档）与 **TASK-229**（web 交互面修整批，MEDIUM 档）。

---

## 3. 当前已知边界与挂账限制（避免重踩已定位的坑）

1. **子 Agent 边界**：子 Agent 严禁修改 `work/mailbox/PLAN.md` 与本文件，子 Agent 仅在自身 `work/mailbox/tasks/TASK-xxx/` 下提交回传。
2. **契约校验口径**：运行 `scripts/verify/mailbox-contract.sh` 必须在 Git Bash（`D:\git\Git\bin\bash.exe`）下执行；无参模式短路跳过判据 B，白名单一致性以开工 `--open` 与直接 `git diff` 为准。
3. **挂账候选事项**：
   - `B1` 核心链路缺陷修复（榜单结算全量覆盖摧毁周期榜、R4 操场/闭环跑误杀、申诉通过判定未回写、打卡时间对齐）；
   - `B2` 社交与点赞交互补齐（好友申请待审列表、动态/详情点赞交互）；
   - `H2` 生产部署姿态演练（CORS 真实注入/跨域 cookie/TLS 终止协同）；
   - TASK-230 遗留提示：删除已提交后若 Feign 调用或锁失败，锚点可能残留 ACTIVE，依赖 WARN 日志人工排查（后续可安排离线对账补齐）。

---

## 4. 下一个主 Agent 的唯一推荐动作

- **推荐动作**：立项 **TASK-235（核心链路缺陷修复：排行榜结算时间窗口矫正、R4 操场跑防误杀与申诉判定回写）**。
- **开工准备**：按 `docs/internal/AGENTS.md` 的「极简任务派发包模板」为子 Agent 派发。


---

## 5. 新主 Agent 接手操作规约（3 步启动法）

当用户在新的对话窗口中输入接手指令时，新主 Agent 必须按以下 3 步执行：
1. **核验状态**：执行 `git status --short` 与 `git log -1 --oneline`，核实是否与本文件第 1 节一致；
2. **拒绝冗余**：**绝不新建任何交接类 Markdown 文件**；
3. **极简汇报**：直接向用户输出以下结构性回答：
   - **接手状态**：当前 HEAD 与最近闭环任务；
   - **拟执行动作**：从第 4 节推荐动作确认下一步计划；
   - **需用户决策**：如有产品决策或授权需求提出提问，否则给出下一步任务包并请用户拍板派发。
