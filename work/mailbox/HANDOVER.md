# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，最新实施提交为 `b50829a`（C-01）与 `3f6856c`（C-02），封盘笔随本提交闭环。
- **外部 CI 门槛状态**：GitHub Actions run `38692154084`（`web` ✓30s / `build` ✓12m10s 全绿）。
- **工作树状态**：TASK-243 独立复核全绿，在途文件已完成台账归档。
- **当前在途任务数**：`0` 个在途（历史任务至 TASK-243 全部已闭环收口）。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 落地 **TASK-243**（方案 B：核心服务高可用与容灾加固——Outbox死信隔离、规则缓存防击穿硬化与好友申请超时防死锁，MEDIUM 档）：
     - B11 Outbox 毒丸隔离：`sql/03-verify-db.sql` 补充 `EXHAUSTED` 终态说明；`VerifyEventOutboxMapper.java` 新增 `markExhausted(id)` 端点；`VerifyOutboxRelay.java` 抽取公共 `onRowFailure`，在达到 `maxRetry`（16 次）时将消息流转为 `EXHAUSTED` 终态并记录 error 告警（`totals.exhausted++`），彻底移出 `PENDING` 扫描集消除 I/O 放大；新增 4 个单测全绿；
     - B12 规则缓存防击穿：`RuleCacheService.java` 未获锁分支增加至多 3 次（50ms 间隔）轮询重读 Redis 机制，命中有效值或 EMPTY 哨兵直接回填本地 Caffeine 并返回，避免并发线程打穿 `rule_version` DB；单测断言 `dbLoader.get()` 触发 0 次；
     - B13 好友申请防死锁：`FriendService.java` 对创建时间早于 7 天前的同向/反向 PENDING 申请做惰性流转至 `CANCELLED(3)`，解除互斥阻断并允许创建新单，根除双向永久死锁；`listPendingRequests` 增加 7 天内有效过滤；单测全覆盖；
     - 生产前端、网关、其他微服务代码 100% 零触碰，零新外部依赖。
  2. 落地 **TASK-242**（方案 A：存储慢查消灭与社交闭环及体验提升——周期榜复合索引补齐、解除好友全链路打通与运动目标空态优化，MEDIUM 档）。
  3. 系统深度代码与业务常理全仓侦察：挖掘出 10 项深度技术债与优化候选（B10 至 B19），已归档入 [`optimization-notes.md`](file:///C:/Users/fzdzzj/.gemini/antigravity/brain/f7e0acfa-859e-405b-93c3-9689134c7dca/optimization-notes.md) 与 [`BACKLOG.md`](file:///d:/code/sports/work/mailbox/BACKLOG.md)；
  4. 落地 **TASK-241**（前台交互闭环与客户端幂等防重——申诉管理直达自动回填与运动记录提交表单requestId固化加固，MEDIUM 档）。
  5. 落地 **TASK-240**（存储性能与网络吞吐双重优化——friendship 表 user_high 索引补齐与动态流 Feed 点赞态批量下推，MEDIUM 档）。
  6. 落地 **TASK-239**（好友榜查询高并发性能优化——Redis Pipeline 批量分数检索与单次往返，MEDIUM 档）。
  7. 落地 **TASK-238**（运动轨迹全链路真实化与反作弊合理性：长轨迹全量突破、GPX完整导出、SVG等比投影与闭环判定合理性，MEDIUM 档）。
  8. 落地 **TASK-237**（全链路闭环体验补齐：记录申诉前台打通、通知中心交互下钻与顶栏登录感知，MEDIUM 档）。
  9. 落地 **TASK-236**（社交与点赞交互补齐：动态流与详情页点赞交互与状态同步，MEDIUM 档）。
  10. 落地 **TASK-235**（核心链路缺陷修复与社交闭环：榜单结算窗口矫正、申诉判定回写、打卡时间对齐与待审申请一键处理，MEDIUM 档）。

---

## 3. 当前已知边界与挂账限制（避免重踩已定位的坑）

1. **子 Agent 边界**：子 Agent 严禁修改 `work/mailbox/PLAN.md` 与本文件，子 Agent 仅在自身 `work/mailbox/tasks/TASK-xxx/` 下提交回传。
2. **契约校验口径**：运行 `scripts/verify/mailbox-contract.sh` 必须在 Git Bash（`D:\git\Git\bin\bash.exe`）下执行；无参模式短路跳过判据 B，白名单一致性以开工 `--open` 与直接 `git diff` 为准。
3. **挂账候选事项**：
   - `H2` 生产部署姿态演练（CORS 真实注入/跨域 cookie/TLS 终止协同）；
   - 远端 CI 批量推送（当前本地领先 origin/main 18 笔提交，待推送触发外部终验）；
   - TASK-230 遗留提示：删除已提交后若 Feign 调用或锁失败，锚点可能残留 ACTIVE，依赖 WARN 日志人工排查（后续可安排离线对账补齐）。

---

## 4. 下一个主 Agent 的唯一推荐动作

- **推荐动作**：
  1. 推进 **方案 C（TASK-244 拟，MEDIUM 档）**：B15（申诉待审列表与表格驱动）+ B16（删除回撤离线对账补偿）+ B17（通知批量处理与索引优化）+ B18（总榜 Redis 裁剪与倾倒重构）；
  2. 或执行 `git push origin main` 批量推送已封盘批次触发 GitHub Actions 远端全量 CI 外部终验（当前本地领先 origin/main 18 笔提交）。
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


