# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，最新实施提交为 `3cf0406`（C-01）与 `7c6d9ef`（C-02），封盘笔随本提交闭环。
- **外部 CI 门槛状态**：GitHub Actions run `38692154084`（`web` ✓30s / `build` ✓12m10s 全绿）。
- **工作树状态**：TASK-239 独立复核全绿，在途文件已完成台账归档。
- **当前在途任务数**：`0` 个在途（历史任务至 TASK-239 全部已闭环收口）。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 落地 **TASK-239**（好友榜查询高并发性能优化——Redis Pipeline 批量分数检索与单次往返，MEDIUM 档）：
     - `LeaderboardService.topFriends` 彻底移除原 `for (start += 500)` 对总榜 `leaderboard:overall` 循环批次扫描，基于去重好友 ID 集合（`distinctFriendIds`）通过 `stringRedisTemplate.executePipelined` 执行 `connection.zSetCommands().zScore` 一次性拉齐全体好友得分，网络往返由数十次/百次降至恰 1 次（RTT=1），时间复杂度由 $O(N_{zset})$ 降至 $O(N_{friends})$；
     - 内存过滤/排序/截断契约自洽：内存过滤未上榜（null）与非正分数，按分数降序（同分按 member 字符串降序 tie-breaker，100% 对齐 Redis ZREVRANGE 稳定排序），截取前 `size` 条交由 `assemble` 组装返回；
     - 业务契约 100% 保持：只显示好友（本人与非好友均不在榜）、无好友/未上榜/降级安全返回空榜，`fetchAllFriendIds` 降级与分页拉齐契约一字不动；
     - 单元测试全套适配：既有单测 `topFriend_filtersNonFriends` 与分页契约测试适配 Pipeline 打桩，改写批次扫描用例为 `topFriend_usesPipeline_batchRetrievesFriendScoresInSingleRoundTrip`（断言 Pipeline 恰 1 次且整榜扫描 0 次），新增 `topFriend_sortsFriendsByScoreDescending_andTiesByMemberDescending`（同分 member 降序、null 过滤、size 截断全对），并适配 `LeaderboardCacheInvocationTest` 缓存穿透调用断言；
     - 生产后端其他代码与配置 100% 零触碰，零新外部依赖。
  2. 落地 **TASK-238**（运动轨迹全链路真实化与反作弊合理性：长轨迹全量突破、GPX完整导出、SVG等比投影与闭环判定合理性，MEDIUM 档）：
     - 详情页长轨迹突破与 GPX 完整导出：`record-detail.page.vue` 的 `load()` 首包 500 后，若 `total > records.length` 则自动 `while (allPoints.length < totalPoints && allPoints.length < 20000)` 循环分页按批追加拉取至全量（每批 500 点，空批 break 防死循环），文案改为「共 X 个轨迹点（完整加载）…」并移除 500 点截断警告；`generateGpx(points.value)` 自然基于完整点集导出；
     - SVG 局部等距等比投影：`record-detail.page.vue` 结合 `avgLat` 余弦修正 `spanX`，统一缩放比 `scale=min(innerW/spanX, innerH/spanY)` 居中等比渲染（viewBox 800×400、PAD 16），全同坐标退化居中兜底，正方形/圆形轨迹不再拉伸变形；
     - R4 规则起征门槛保护：`R4DistanceRule.java` 在 `cumulative < minCumulativeMeters` 时直接返回 null 豁免判定，超门槛原比值判定一字未动；`VerifyProperties.java` 新增 `minCumulativeMeters = 500.0`；新建 `R4DistanceRuleTest.java` 覆盖短闭环豁免、长折返命中、正常直线不命中三用例；
     - 生产后端其他代码与配置 100% 零触碰，零新外部依赖。
  3. 落地 **TASK-237**（全链路闭环体验补齐：记录申诉前台打通、通知中心交互下钻与顶栏登录感知，MEDIUM 档）：
     - 首页被拒记录申诉直通：`index.page.vue` 列表操作列新增 `canAppeal(status)` 门控（1 校验中 / 3 拒绝 / 6 维持拒绝 / 7 人工审核）→「判定/申诉」链接，`goVerdict` 带 `query.id` 直达 `/verdict`，终结手动敲 URL 查因的断层体验；既有「查看轨迹」「删除」保留不变；
     - 记录详情页申诉引导：`record-detail.page.vue` 摘要卡片在 `verdict === 2`（拒绝）时渲染警告提示与直达按钮，一键带参直通 `/verdict?id=${recordId}`；
     - 通知中心交互下钻与自动已读：`notifications.page.vue` 表格 `title`/`sourceId` 列对可下钻通知渲染链接——`RECORD_*` 跳详情（`RECORD_REJECTED` 直达判定页）、`FRIEND_ACCEPTED` 跳好友页，未读项点击自动 `markNotificationRead` 并刷新角标（失败静默不阻断跳转）；
     - 顶栏登录态感知与设置入口：`App.vue` 顶栏根据 `loggedIn` 动态渲染，登录态展示「已登录: USER/ADMIN」身份标签 +「设置」（跳 `/settings`）+「退出」，未登录恢复「注册/登录」；
     - E2E 断言对齐：`top-nav.e2e.ts` 对齐「设置」导航验证与退出后登录态恢复断言，全量 12 测稳定全绿；
     - 生产后端代码与配置 100% 零触碰，零新外部依赖。
  3. 落地 **TASK-236**（社交与点赞交互补齐：动态流与详情页点赞交互与状态同步，MEDIUM 档）。
  4. 落地 **TASK-235**（核心链路缺陷修复与社交闭环：榜单结算窗口矫正、申诉判定回写、打卡时间对齐与待审申请一键处理，MEDIUM 档）。
  5. 落地 **TASK-234**（原有功能健壮化：物理合理性推导、榜单周期联调与目标边界防御，MEDIUM 档）。
  6. 落地 **TASK-233**（测试稳定性强化与 Web E2E 路由契约对齐，MEDIUM 小档）。
  7. 落地 **TASK-232**（个人统计 SQL 聚合下推与页面加载态解耦，MEDIUM 档）。
  8. 落地 **TASK-231**（个人统计服务端聚合端点 /my/stats 与页面历史文案清理，MEDIUM 档）。
  9. 落地 **TASK-230**（删除记录联动回撤榜单里程贡献，MEDIUM 档）与 **TASK-229**（web 交互面修整批，MEDIUM 档）。

---

## 3. 当前已知边界与挂账限制（避免重踩已定位的坑）

1. **子 Agent 边界**：子 Agent 严禁修改 `work/mailbox/PLAN.md` 与本文件，子 Agent 仅在自身 `work/mailbox/tasks/TASK-xxx/` 下提交回传。
2. **契约校验口径**：运行 `scripts/verify/mailbox-contract.sh` 必须在 Git Bash（`D:\git\Git\bin\bash.exe`）下执行；无参模式短路跳过判据 B，白名单一致性以开工 `--open` 与直接 `git diff` 为准。
3. **挂账候选事项**：
   - `H2` 生产部署姿态演练（CORS 真实注入/跨域 cookie/TLS 终止协同）；
   - 远端 CI 批量推送（当前本地领先 origin/main 6 笔提交，待推送触发外部终验）；
   - TASK-230 遗留提示：删除已提交后若 Feign 调用或锁失败，锚点可能残留 ACTIVE，依赖 WARN 日志人工排查（后续可安排离线对账补齐）。

---

## 4. 下一个主 Agent 的唯一推荐动作

- **推荐动作**：
  1. 执行 `git push origin main` 批量推送已封盘批次触发 GitHub Actions 远端全量 CI 外部终验；
  2. 或推进 **B6（拟立项 TASK-240：friendship 补充 user_high 索引）**，消灭好友查询在较大 ID 方向的全表扫描；
  3. 或推进 **B7（拟立项 TASK-241：Feed 点赞态批量下推）**，消除动态流 20 次并发 HTTP；
  4. 或推进 **H2（E5 生产部署姿态演练）**。
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


