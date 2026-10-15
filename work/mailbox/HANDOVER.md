# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，最新实施提交为 `8f7601d`（C-01）与 `a6cd2c1`（C-02），封盘笔随本提交闭环。
- **外部 CI 门槛状态**：GitHub Actions run `38692154084`（`web` ✓30s / `build` ✓12m10s 全绿）。
- **工作树状态**：TASK-241 独立复核全绿，在途文件已完成台账归档。
- **当前在途任务数**：`1` 个（TASK-242 方案 A 开工在途：B10 周期榜复合索引 + B14 好友列表自加载与解除好友 + B19 目标次数整数化与近 30 天空态退化）。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 系统深度代码与业务常理全仓侦察：挖掘出 10 项深度技术债与优化候选（B10 至 B19），已归档入 [`optimization-notes.md`](file:///C:/Users/fzdzzj/.gemini/antigravity/brain/f7e0acfa-859e-405b-93c3-9689134c7dca/optimization-notes.md) 与 [`BACKLOG.md`](file:///d:/code/sports/work/mailbox/BACKLOG.md)；
  2. 落地 **TASK-241**（前台交互闭环与客户端幂等防重——申诉管理直达自动回填与运动记录提交表单requestId固化加固，MEDIUM 档）：
     - B8 申诉管理闭环与自动回填：`verdict.page.vue` 申诉成功 Alert 内渲染直通链接直达 `/admin-appeal?id=...`；`admin-appeal.page.vue` 挂载自动读取 `query.id` 或 `appealId` 回填 `form.id`，终判成功卡片提供直通链接直达 `/verdict?id=...`，形成端到端完整闭环；
     - B9 提交表单客户端 requestId 固化加固：`record-submit.page.vue` 在途防篡改（`:disabled="submitting"` + 短路）、提交防空有效性保障、失败重试保持 requestId 与防重指引、下一条轮换按钮（`prepareNextRecord` 刷新 requestId 并重置结果）、3004 友好拦截提示；
     - 测试强化：`client.test.ts` 新增 `submitAppeal`（2 例）与 `reviewAppeal`（2 例）契约单测；
     - 生产代码白名单外 100% 零触碰，零新外部依赖。
  3. 落地 **TASK-240**（存储性能与网络吞吐双重优化——friendship 表 user_high 索引补齐与动态流 Feed 点赞态批量下推，MEDIUM 档）：
     - B6 存储慢查消灭：`sql/01-user-db.sql` 为 `friendship` 补齐 `KEY idx_user_high (user_high)` 并新建幂等迁移 `sql/migrations/add-idx-friendship-user-high.sql`，消除 `listFriends` 在较大用户 ID 维度的全表扫描，支持 MySQL `index_merge`；
     - B7 服务端批量下推：`LikeController.java` 新增 `POST /api/records/likes/batch`；`RecordLikeService.java` 实现 `getLikesBatch`（空入参安全、去重保序、`assertCanView` 安全过滤、`executePipelined` 单次往返批量获取点赞数与状态、缓存 miss 回退 `readCount`）；
     - B7 前端单请求直出：`web/src/api/client.ts` 导出 `getLikesBatch`；`web/src/pages/feed.page.vue` 重构 `loadLikes()` 为单次批量请求，动态流 20 次并发 HTTP 压至 1 次；
     - 单元测试全套覆盖：`RecordLikeServiceTest` 新增 7 个单测，前端 `client.test.ts` 新增 R1 路由契约单测；
     - 生产后端其他代码与配置 100% 零触碰，零新外部依赖。
  4. 落地 **TASK-239**（好友榜查询高并发性能优化——Redis Pipeline 批量分数检索与单次往返，MEDIUM 档）：
     - `LeaderboardService.topFriends` 彻底移除原 `for (start += 500)` 对总榜 `leaderboard:overall` 循环批次扫描，基于去重好友 ID 集合（`distinctFriendIds`）通过 `stringRedisTemplate.executePipelined` 执行 `connection.zSetCommands().zScore` 一次性拉齐全体好友得分，网络往返由数十次/百次降至恰 1 次（RTT=1），时间复杂度由 $O(N_{zset})$ 降至 $O(N_{friends})$；
     - 内存过滤/排序/截断契约自洽：内存过滤未上榜（null）与非正分数，按分数降序（同分按 member 字符串降序 tie-breaker，100% 对齐 Redis ZREVRANGE 稳定排序），截取前 `size` 条交由 `assemble` 组装返回；
     - 业务契约 100% 保持：只显示好友（本人与非好友均不在榜）、无好友/未上榜/降级安全返回空榜，`fetchAllFriendIds` 降级与分页拉齐契约一字不动；
     - 单元测试全套适配：既有单测 `topFriend_filtersNonFriends` 与分页契约测试适配 Pipeline 打桩，改写批次扫描用例为 `topFriend_usesPipeline_batchRetrievesFriendScoresInSingleRoundTrip`（断言 Pipeline 恰 1 次且整榜扫描 0 次），新增 `topFriend_sortsFriendsByScoreDescending_andTiesByMemberDescending`（同分 member 降序、null 过滤、size 截断全对），并适配 `LeaderboardCacheInvocationTest` 缓存穿透调用断言；
     - 生产后端其他代码与配置 100% 零触碰，零新外部依赖。
  5. 落地 **TASK-238**（运动轨迹全链路真实化与反作弊合理性：长轨迹全量突破、GPX完整导出、SVG等比投影与闭环判定合理性，MEDIUM 档）。
  6. 落地 **TASK-237**（全链路闭环体验补齐：记录申诉前台打通、通知中心交互下钻与顶栏登录感知，MEDIUM 档）。
  7. 落地 **TASK-236**（社交与点赞交互补齐：动态流与详情页点赞交互与状态同步，MEDIUM 档）。
  8. 落地 **TASK-235**（核心链路缺陷修复与社交闭环：榜单结算窗口矫正、申诉判定回写、打卡时间对齐与待审申请一键处理，MEDIUM 档）。

---

## 3. 当前已知边界与挂账限制（避免重踩已定位的坑）

1. **子 Agent 边界**：子 Agent 严禁修改 `work/mailbox/PLAN.md` 与本文件，子 Agent 仅在自身 `work/mailbox/tasks/TASK-xxx/` 下提交回传。
2. **契约校验口径**：运行 `scripts/verify/mailbox-contract.sh` 必须在 Git Bash（`D:\git\Git\bin\bash.exe`）下执行；无参模式短路跳过判据 B，白名单一致性以开工 `--open` 与直接 `git diff` 为准。
3. **挂账候选事项**：
   - `H2` 生产部署姿态演练（CORS 真实注入/跨域 cookie/TLS 终止协同）；
   - 远端 CI 批量推送（当前本地领先 origin/main 13 笔提交，待推送触发外部终验）；
   - TASK-230 遗留提示：删除已提交后若 Feign 调用或锁失败，锚点可能残留 ACTIVE，依赖 WARN 日志人工排查（后续可安排离线对账补齐）。

---

## 4. 当前推进与唯一推荐动作

- **当前推进**：**方案 A（TASK-242，MEDIUM 档）**
  - B10：`leaderboard_contribution` 复合索引补齐与幂等迁移；
  - B14：`friends.page.vue` 挂载 `loadFriends()` 与 `DELETE /api/friends/{targetUserId}` 解除好友链路打通；
  - B19：`index.page.vue` 运动目标次数整数格式化与近 30 天无数据空态退化处理。
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


