# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，最新实施提交为 `1d37ed0`（C-01）与 `8177e97`（C-02），封盘笔随本提交闭环。
- **外部 CI 门槛状态**：GitHub Actions run `38586443704`（`web` ✓31s / `build` ✓11m46s 全绿）。
- **工作树状态**：TASK-237 独立复核全绿，在途文件已完成台账归档。
- **当前在途任务数**：`0` 个在途（历史任务至 TASK-237 全部已闭环收口）。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 落地 **TASK-237**（全链路闭环体验补齐：记录申诉前台打通、通知中心交互下钻与顶栏登录感知，MEDIUM 档）：
     - 首页被拒记录申诉直通：`index.page.vue` 列表操作列新增 `canAppeal(status)` 门控（1 校验中 / 3 拒绝 / 6 维持拒绝 / 7 人工审核）→「判定/申诉」链接，`goVerdict` 带 `query.id` 直达 `/verdict`，终结手动敲 URL 查因的断层体验；既有「查看轨迹」「删除」保留不变；
     - 记录详情页申诉引导：`record-detail.page.vue` 摘要卡片在 `verdict === 2`（拒绝）时渲染警告提示与直达按钮，一键带参直通 `/verdict?id=${recordId}`；
     - 通知中心交互下钻与自动已读：`notifications.page.vue` 表格 `title`/`sourceId` 列对可下钻通知渲染链接——`RECORD_*` 跳详情（`RECORD_REJECTED` 直达判定页）、`FRIEND_ACCEPTED` 跳好友页，未读项点击自动 `markNotificationRead` 并刷新角标（失败静默不阻断跳转）；
     - 顶栏登录态感知与设置入口：`App.vue` 顶栏根据 `loggedIn` 动态渲染，登录态展示「已登录: USER/ADMIN」身份标签 +「设置」（跳 `/settings`）+「退出」，未登录恢复「注册/登录」；
     - E2E 断言对齐：`top-nav.e2e.ts` 对齐「设置」导航验证与退出后登录态恢复断言，全量 12 测稳定全绿；
     - 生产后端代码与配置 100% 零触碰，零新外部依赖。
  2. 落地 **TASK-236**（社交与点赞交互补齐：动态流与详情页点赞交互与状态同步，MEDIUM 档）：
     - 好友动态流点赞闭环：`feed.page.vue` 每张动态卡片操作行新增点赞按钮（已赞态 `type="primary"` 高亮 + `赞 (n)`/`已赞 (n)` 计数），列表加载后经 `Promise.allSettled` 并发异步拉取点赞态（单条失败静默降级不阻断整页）；点击经 per-card `likeLoadingMap` 锁防重，按态调用 `likeRecord`/`unlikeRecord` 并以服务端返回值同步本地，失败弹出 `message.error`（特化 6001 提示）；未登录或记录状态非 2/5 时置灰 + Tooltip 提示「未通过校验的记录不可点赞」防呆；
     - 记录详情页点赞闭环：`record-detail.page.vue` 操作区与「导出 GPX」并列点赞按钮，`load()` 时摘要与点赞态并发调取，点赞异常静默降级不阻断轨迹与配速渲染，点击一键切换实时同步，verdict 已知非 PASSED 或未登录置灰防呆；
     - E2E 强化与契约对齐：`helpers.ts` 的 like 路由升级为动态状态记忆（`Stores.likes` Map：GET 返回当前态、POST 赞 +1、DELETE 踩 -1），`feed.e2e.ts` 新增点赞按钮渲染与连续点击交互断言（0 赞 → POST 赞 +1 且高亮 → DELETE 踩 -1 且恢复 0 赞），全量 12 测稳定全绿；
     - 生产后端代码与配置 100% 零触碰，零新外部依赖。
  3. 落地 **TASK-235**（核心链路缺陷修复与社交闭环：榜单结算窗口矫正、申诉判定回写、打卡时间对齐与待审申请一键处理，MEDIUM 档）：
     - 榜单结算窗口矫正：`LeaderboardContributionMapper.markSettled` SQL 追加 `AND settled_at IS NULL`，结算仅盖戳未结算行、绝不覆盖历史结算时间，彻底解除了每 10 分钟定时任务将历史已结算贡献刷成当前时间导致今日/周/月周期范围榜失真的系统级缺陷；
     - 申诉改判状态闭环：`VerifyService.reviewAppeal` 在管理员终审改判后，显式执行 `verification_result.verdict` 回写并主动失效本地 Caffeine 缓存（`RESULT_CACHE_PREFIX + recordId`），消除记录表显示 `RE_PASSED` 但详情与判定接口仍展示 `REJECTED` 的状态割裂；
     - 打卡与统计时间口径对齐：`SportRecordService.myStreak` 与 `myStats` 统一按 `startTime` 归自然日（null 回退 `createdAt`），消除跨零点与离线补传导致的误断签，与 TASK-234 目标服务保持统一；
     - 社交待办一键闭环：`user-service` 新增 `GET /api/friends/requests` 查询待处理申请列表，前端 `friends.page.vue` 新增「收到的好友申请」卡片一键同意/拒绝并联动刷新，移除必须线下询问 `requestId` 手工输入的反人类交互。
  4. 落地 **TASK-234**（原有功能健壮化：物理合理性推导、榜单周期联调与目标边界防御，MEDIUM 档）。
  5. 落地 **TASK-233**（测试稳定性强化与 Web E2E 路由契约对齐，MEDIUM 小档）。
  6. 落地 **TASK-232**（个人统计 SQL 聚合下推与页面加载态解耦，MEDIUM 档）。
  7. 落地 **TASK-231**（个人统计服务端聚合端点 /my/stats 与页面历史文案清理，MEDIUM 档）。
  8. 落地 **TASK-230**（删除记录联动回撤榜单里程贡献，MEDIUM 档）与 **TASK-229**（web 交互面修整批，MEDIUM 档）。

---

## 3. 当前已知边界与挂账限制（避免重踩已定位的坑）

1. **子 Agent 边界**：子 Agent 严禁修改 `work/mailbox/PLAN.md` 与本文件，子 Agent 仅在自身 `work/mailbox/tasks/TASK-xxx/` 下提交回传。
2. **契约校验口径**：运行 `scripts/verify/mailbox-contract.sh` 必须在 Git Bash（`D:\git\Git\bin\bash.exe`）下执行；无参模式短路跳过判据 B，白名单一致性以开工 `--open` 与直接 `git diff` 为准。
3. **挂账候选事项**：
   - `H2` 生产部署姿态演练（CORS 真实注入/跨域 cookie/TLS 终止协同）；
   - 远端 CI 批量推送（当前本地领先 origin/main 28 笔提交，待推送触发外部终验）；
   - TASK-230 遗留提示：删除已提交后若 Feign 调用或锁失败，锚点可能残留 ACTIVE，依赖 WARN 日志人工排查（后续可安排离线对账补齐）。

---

## 4. 下一个主 Agent 的唯一推荐动作

- **推荐动作**：执行 `git push origin main` 批量推送已封盘批次触发 GitHub Actions 远端全量 CI 外部终验，或推进 **H2（拟立项 TASK-238：E5 生产部署姿态演练）**。
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


