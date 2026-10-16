# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，最新已封盘任务为 **TASK-250**（D 系卫生与纵深批——D4 改密吊销 KEYS→SCAN + D5 WS STOMP Origin 纵深 + D7 点赞对账分页化 + D8 R5 连续降级告警，FULL 档；C-01 `32c1d8f` / C-02 `f1dd3e4`，指导亲核 + 独立复核五块 PASS，终裁 ACCEPT）。**D 系 8 项候选已全部收口**（TASK-249 封 D1/D2/D3/D6 + TASK-250 封 D4/D5/D7/D8）。
- **外部 CI 门槛状态**：TASK-250 封盘批次（5 笔：TASK-249 补记笔 `623d4d1` + TASK-250 立项笔 `3b56032` + C-01 `32c1d8f` + C-02 `f1dd3e4` + 封盘笔 `b0a6cbb`）推送后经 CI run `38849720261` 全绿（`web` ✓29s / `build` ✓12m12s，HEAD `b0a6cbb`），外部终验达成，**TASK-250 完整闭环**（本行由补记笔落库，读数时点 2026-10-16）。运行 annotations 双确认：actions/checkout@v4 + setup-node@v4 被强制跑 Node 24（Node 20 已退役）、`ubuntu-latest` 将于 2026-10-19 起迁移 Ubuntu 26——与 E4 候选证据互证。
- **工作树状态**：干净（本地领先笔：TASK-250 补记笔等，随下一课题批次统一推送，禁单独推送）。
- **当前在途任务数**：`0` 个。

---

## 2. 上一会话交付成果

- **刚闭环事项**：
  1. 落地 **TASK-249**（D 系推荐序 ①②③ 合并——D1 总榜缓存失效联动 + D3 结算 scan 单遍重构 + D2 appeal 待审索引 + D6 mapmatch 候选集查询防护，FULL 档多模块）：
     - R1（D1）：`LeaderboardService` 注入 `CacheManager` + best-effort `evictOverallCache()`（异常仅 warn），三调用点恰各一次（`applyVerified` credit=true / `rollbackOnRejected` rows==1 / `settleAndReconcile` 纠偏完成），幂等路径不调用；**刻意不引入 @CacheEvict/@Cacheable 代理注解**（ADR-0009 哨兵保持零代理），显式经管理器清 L1+L2——修复「驳回/删除回撤后总榜最长 5min 显示旧里程」一致性盲区；
     - R2（D3）：结算残留清理重写为单次 `Cursor` try-with-resources 单遍迭代 + 批式 flush，删除 `scanPage()` 启发式——**修复成员 ≥500 时推进判据恒真的不可终止循环（潜伏 P1）**，语义（stale 判据/批 500/pruned/锁边界）逐项保持；
     - R3（D2）：`appeal` 表补 `KEY idx_status_id (status, id)`（03-verify-db.sql 建表 + 幂等迁移脚本双路径），零 Java 改动；
     - R4（D6）：`MatchProperties` 新增 `maxQuerySpanMeters=2000`/`maxCandidateEdges=2000`，`fetchCandidates` 自适应中点二分递归（子块并集超集等价），候选超限仅告警严禁 LIMIT 截断；
     - 改动恰 13 文件白名单全等；后端全模块 rc=0（leaderboard 94→104、mapmatch 26→29，只增不减）+ 前端 61 测 + 契约/token（2011→2014 只增不减）/词面门全过；指导亲核三块 PASS + 独立复核审查官五块全 PASS（零阻断项，N1-N4 非阻断登记，受限口径三项如实登记）；受限声明：多实例 L1 不广播沿单实例口径、D2 收益静态推论、D6 仅 mock 层验证。
  1. 落地 **TASK-248**（B23 覆盖增强——好友榜 15s 本地缓存「空榜不缓存防背锅」机制补齐显式单测断言，MEDIUM 档纯测试新增）：
     - R1 `emptyBoardFromDegradation_isNotCached_secondCallRefreshesOnRecovery`（核心防背锅语义）：`listFriends` 首次 `thenThrow` → 第一调断空榜；沿 `LeaderboardServiceTest` L380 先例以 `doReturn(...).when(...)` 重打桩恢复 → 第二调断非空；`verify(userApi, times(2))` 锁定「降级空榜未进缓存、两次均真实穿透 Feign」——若误去掉 `isEmpty` 判定即断言失败；Pipeline 计数按实际执行路径校正 `times(1)`（首次降级未触达 Pipeline，经用户确认登记 handoff §4）；
     - R2 `emptyBoardFromNoFriends_isNotCached_secondCallPenetratesAgain`：无好友空页（total=0）连续两调均空、`times(2)` 同口径穿透；
     - 零生产代码改动、零新外部依赖、未删弱任何既有断言；白名单恰 3 文件全等；后端 leaderboard-service 94 测全绿（`LeaderboardCacheInvocationTest` 3→5，只增不减）+ 前端 61 测 + 契约/token（2011 持平）/词面门全过；MEDIUM 档免独立复核子agent，指导侧亲核三块 PASS（紧凑 Git 摘要 + R1/R2 定向抽查 + 回报一致性裁决）。
  1. 落地 **TASK-247**（候选 H2：E5 生产部署姿态演练——CORS 动态多域名注入、跨域 Cookie 凭证透传与 TLS 终止协同，MEDIUM 档）：
     - R1 CORS 生产多域名动态注入：真实网关上下文（RANDOM_PORT）注入 `CORS_ALLOWED_ORIGINS=https://sports.example.com,https://admin.example.com`，真实 HTTP 预检 OPTIONS 断言白名单 Origin 回显 `Access-Control-Allow-Origin` + `Access-Control-Allow-Credentials: true`，恶意 Origin（`https://attacker.com`）不返回 CORS 头；
     - R2 反向代理 TLS 终止与协议感知：`server.forward-headers-strategy: framework` 落地，`ForwardedHeaderTransformer`（framework 等价适配器）断言 `X-Forwarded-Proto/Host/Port` → scheme https，application.yml 资源文件断言声明 framework；
     - R3 跨域 Cookie 凭证透传：真实全局过滤链（RequestIdGlobalFilter → AuthGlobalFilter 白名单透传分支）断言 `Cookie: refresh_token` 下游请求头无损保留；
     - R4 Compose 与文档对齐：`docker-compose.services.yml` gateway environment 注入 `CORS_ALLOWED_ORIGINS` 占位（本地默认 `http://127.0.0.1:5173,http://localhost:5173`）、`scripts/verify/env.example` 补生产配置说明、`README.md` 补反向代理（Nginx 等）`X-Forwarded-Proto` 协同部署章节；
     - 消除 TASK-205 长期挂账技术债（refresh_token HttpOnly Cookie 迁移后的生产姿态未覆盖项）；改动恰 7 文件白名单（5 业务/测试 + 2 台账），四大业务微服务与根/子 pom.xml、package.json 100% 零触碰，零新外部依赖；本地五门全绿（后端 62 测 / compose config / 前端类型 0 错 / 前端 61 测 / 契约门 0）；独立复核审查官五块全 PASS（阻断项 0，只读自证 porcelain 空）后封盘。
  2. 落地 **TASK-246**（方案 E：后台架构韧性与存储生命周期治理——B20 Outbox历史清理与毒丸重放、B21 通知历史清理与索引、B23 好友榜多级缓存防穿透、B24 网关统一异常标准包装，MEDIUM 档）：
     - B20 Outbox 历史清理与毒丸人工重放：`VerifyEventOutboxMapper` 新增 `selectExhausted`、`replayExhausted`（幂等重放）、`cleanSentEvents`（分批清理）；`VerifyController` 新增 `GET /api/outbox/exhausted`、`POST /api/outbox/exhausted/{id}/replay`、`DELETE /api/outbox/sent`；前端 `admin.page.vue` 增加死信看板卡片与表格；
     - B21 通知中心历史清理与索引补齐：`sql/01-user-db.sql` 补齐 `KEY idx_read_created (is_read, created_at)` 并提供幂等迁移；`NotificationMapper/Service/Controller` 补齐分批过期清理与用户本人一键清空已读（`DELETE /api/notifications/read`）；前端 `notifications.page.vue` 增加「清空已读」交互；
     - B23 好友榜多级缓存防穿透：`LeaderboardService` 引入 15s 本地 Caffeine 缓存（Key=`userId:size`），`topFriends` 优先命中本地缓存，空榜防背锅不缓存，根除高并发下 Feign 穿透打爆 `user-service`；
     - B24 网关未捕获异常统一标准包装：`gateway-service` 新增 `GatewayExceptionHandler`（Order=-1），拦截各类微服务不可达/超时/404 等未捕获异常，统一输出符合 Result 契约的 JSON，消除前端白屏与非标错误；零 common 模块依赖边界严格遵守；
     - 生产 record-service、根与子 pom.xml、package.json 等 100% 零触碰，零新外部依赖。
  2. 落地 **TASK-245**（方案 D：前台端到端业务与全功能体验增强闭环——记录详情指标与可见性修改、申诉全链路进度条与管理外链修复、用户资料与好友批量、动态流视觉与GPX导入，MEDIUM 档）：
     - B22+B29 记录详情全能卡与可见性修改：`RecordController` 新增 `PATCH /records/{id}/visibility`（0/1/2 严格校验，非本人 403）与 `GET /records/{id}`；`SportRecordService` 实现值域校验与归属防护；`record-detail.page.vue` 补齐运动指标卡（🏃/🚴/🚶 中文类型、里程 km、耗时、平均配速）+ 原地可见性下拉切换即保存；SVG 起终点高亮特征颜色（绿色起点 `#52c41a`、红色终点 `#ff4d4f`）；
     - B26 申诉全链路透明追踪：`SportRecordService` 新增 `getAppeal` 由记录状态权威推导申诉进度/结论；`verdict.page.vue` 彻底移除跳转管理后台（403）的荒谬外链，引入 `<a-steps>` 进度条组件，已有申诉自动回显并禁用重复提交防 3005 冲突；
     - B25 用户资料维护与好友批量操作：`UserController` 新增 `GET/PUT /api/user/profile` 支持查看与修改用户昵称；`FriendService.listPendingRequests` 批量补齐申请人真实昵称 `fromNickname`；`FriendController` 新增 `batch-accept` 与 `batch-reject` 端点；`settings.page.vue` 增加个人资料卡片；`friends.page.vue` 表格开启行多选复选框并支持「批量同意」与「全部忽略」；
     - B27 动态流视觉与空态引导：`feed.page.vue` 卡片头部增加首字主题彩色头像与中文运动类型徽标；空态采用 `<a-result>` 丰富展示并提供「去添加好友」「去记录运动」引导按钮；
     - B28 轨迹 GPX 文件导入解析：`gpx.ts` 新增纯正则实现的 `parseGpx`（零依赖，鲁棒提取经纬度并补齐 seq/speed/ts 递推）；`record-submit.page.vue` 增加「导入 GPX 文件」支持拖拽/选择文件自动解析填入表单并推导时间与距离；
     - 生产 verify-service、leaderboard-service、gateway-service、pom.xml、package.json 等 100% 零触碰，零新外部依赖。
  2. 落地 **TASK-244**（方案 C：治理闭环、存储优化与高并发防爆——申诉待审列表驱动、通知索引慢查消除与批量已读、总榜Redis内存防爆与定时结算对账补偿，MEDIUM 档）：
     - B15 申诉待审闭环：`VerifyController` 增加 `GET /api/appeals/pending`；前端 `admin-appeal.page.vue` 增加表格驱动待审列表，支持「填入审核」一键联动与自动重载，彻底消除盲打 ID；
     - B16 删除回撤离线对账自愈：`LeaderboardContributionMapper` 增加 `selectOrphanContributions`（NOT EXISTS 查已物理删除记录）；`LeaderboardService` 在定时结算期间自动识别孤儿行并调用 `updateStatus` 置为 `ROLLED_BACK` 扣回里程，实现跨服务删除失败后的定时离线自动补偿；
     - B17 通知中心索引优化与批量标记：`sql/01-user-db.sql` 补齐 `KEY idx_user_id (user_id, id)` 消除慢排序，并提供幂等迁移；后端增加 `PATCH /api/notifications/batch-read` 批量标记，前端表格支持行多选与批量已读；
     - B18 总榜 Redis 内存防爆与倾倒重构：重构定时结算移除 `range(OVERALL_ZSET_KEY, 0, -1)` 全量 dump 进 JVM，改为基于 `opsForZSet().scan(...)` 游标分批遍历比对（带 null 安全保护）；末尾执行 `removeRange` 容量截断保留前 10,000 名，防止未活跃成员无限堆积打爆 Redis 内存；
     - 生产 record-service、网关、pom.xml、package.json 等 100% 零触碰，零新外部依赖。
  3. 落地 **TASK-243**（方案 B：核心服务高可用与容灾加固——Outbox死信隔离、规则缓存防击穿硬化与好友申请超时防死锁，MEDIUM 档）：
     - B11 Outbox 毒丸隔离：`sql/03-verify-db.sql` 补充 `EXHAUSTED` 终态说明；`VerifyEventOutboxMapper.java` 新增 `markExhausted(id)` 端点；`VerifyOutboxRelay.java` 抽取公共 `onRowFailure`，在达到 `maxRetry`（16 次）时将消息流转为 `EXHAUSTED` 终态并记录 error 告警（`totals.exhausted++`），彻底移出 `PENDING` 扫描集消除 I/O 放大；新增 4 个单测全绿；
     - B12 规则缓存防击穿：`RuleCacheService.java` 未获锁分支增加至多 3 次（50ms 间隔）轮询重读 Redis 机制，命中有效值或 EMPTY 哨兵直接回填本地 Caffeine 并返回，避免并发线程打穿 `rule_version` DB；单测断言 `dbLoader.get()` 触发 0 次；
     - B13 好友申请防死锁：`FriendService.java` 对创建时间早于 7 天前的同向/反向 PENDING 申请做惰性流转至 `CANCELLED(3)`，解除互斥阻断并允许创建新单，根除双向永久死锁；`listPendingRequests` 增加 7 天内有效过滤；单测全覆盖；
     - 生产前端、网关、其他微服务代码 100% 零触碰，零新外部依赖。
  4. 落地 **TASK-242**（方案 A：存储慢查消灭与社交闭环及体验提升——周期榜复合索引补齐、解除好友全链路打通与运动目标空态优化，MEDIUM 档）。
  5. 系统深度代码与业务常理全仓侦察：挖掘出 10 项深度技术债与优化候选（B10 至 B19），已归档入 [`optimization-notes.md`](file:///C:/Users/fzdzzj/.gemini/antigravity/brain/f7e0acfa-859e-405b-93c3-9689134c7dca/optimization-notes.md) 与 [`BACKLOG.md`](file:///d:/code/sports/work/mailbox/BACKLOG.md)；
  6. 落地 **TASK-241**（前台交互闭环与客户端幂等防重——申诉管理直达自动回填与运动记录提交表单requestId固化加固，MEDIUM 档）。
  7. 落地 **TASK-240**（存储性能与网络吞吐双重优化——friendship 表 user_high 索引补齐与动态流 Feed 点赞态批量下推，MEDIUM 档）。
  8. 落地 **TASK-239**（好友榜查询高并发性能优化——Redis Pipeline 批量分数检索与单次往返，MEDIUM 档）。
  9. 落地 **TASK-238**（运动轨迹全链路真实化与反作弊合理性：长轨迹全量突破、GPX完整导出、SVG等比投影与闭环判定合理性，MEDIUM 档）。
  10. 落地 **TASK-237**（全链路闭环体验补齐：记录申诉前台打通、通知中心交互下钻与顶栏登录感知，MEDIUM 档）。
  11. 落地 **TASK-236**（社交与点赞交互补齐：动态流与详情页点赞交互与状态同步，MEDIUM 档）。
  12. 落地 **TASK-235**（核心链路缺陷修复与社交闭环：榜单结算窗口矫正、申诉判定回写、打卡时间对齐与待审申请一键处理，MEDIUM 档）。

---

## 3. 当前已知边界与挂账限制（避免重踩已定位的坑）

1. **子 Agent 边界**：子 Agent 严禁修改 `work/mailbox/PLAN.md` 与本文件，子 Agent 仅在自身 `work/mailbox/tasks/TASK-xxx/` 下提交回传。
2. **契约校验口径**：运行 `scripts/verify/mailbox-contract.sh` 必须在 Git Bash（`D:\git\Git\bin\bash.exe`）下执行；无参模式短路跳过判据 B，白名单一致性以开工 `--open` 与直接 `git diff` 为准。
3. **挂账候选事项**：
   - `TASK-247` 补记笔（CI run `38782194561` 外部终验登记，`c975f24`）已随 TASK-248 封盘批次统一推送（2026-10-16）；
   - `B23 覆盖增强`（好友榜缓存空榜防背锅的显式单测断言）：已封盘 **TASK-248**（2026-10-16，`1ed6f57` / `d7fa60d`，指导亲核 PASS；CI run `38790795113` 全绿，外部终验达成，读数由补记笔登记）；
   - **D 系批次收口**：D1/D2/D3/D6 已封盘 **TASK-249**（2026-10-16，CI run `38839626456` 全绿）；D4/D5/D7/D8 已封盘 **TASK-250**（2026-10-16，C-01 `32c1d8f` / C-02 `f1dd3e4`，独立复核五块 PASS 零阻断，N1-N5 非阻断登记；授权偏差 `RecordLikeServiceTraceIdTest` 追加白名单第 15 项经用户显式授权并在 C-02 登记）。非阻断留痕：`RecordLikeMapper.java` L64/L73 旧 Javadoc 注释滞后（后续触碰该文件时顺手统一口径）；台账措辞「新增 WebSocketNotificationConfigTest」实为扩展存量类（后续台账顺手澄清）；
   - **CI runner 预警**：GitHub `ubuntu-latest` 将于 2026-10-19 起迁移至 Ubuntu 26（当前 run 已附 deprecation 注解：Node 20 actions 强制跑于 Node 24、`actions/setup-java@v4` 停更），迁移后首个 CI run 需重点观察，若红按环境签名归因登记，不盲目重试刷绿；
   - 申诉审核意见（verify_db）透出限制：受限与 verify-service 红线，当前前台申诉进度条由记录状态推导，结论权威可靠。

---

## 4. 当前推进与唯一推荐动作

- **当前推进**：**TASK-250 已封盘（D 系卫生与纵深批——D4 改密吊销 KEYS→SCAN + D5 WS STOMP Origin 纵深 + D7 点赞对账分页化 + D8 R5 连续降级告警，FULL 档，指导亲核三块 PASS + 独立复核五块全 PASS 零阻断，终裁 ACCEPT）**；D 系 8 项候选全部收口（TASK-249 + TASK-250）
  - **唯一推荐动作**：观察 TASK-250 封盘批次（5 笔）CI 外部终验结果，绿则收口达成、补记笔落库随下一批次统一推送（沿先例）；若红按签名归因（注意 2026-10-19 `ubuntu-latest` → Ubuntu 26 runner 迁移预警，红则按环境签名归因，不盲目重试刷绿）；
  - **下一候选**：新批次立项侦察待用户发起（D 系已清空；可选方向：全仓新深度侦察 / B 系遗留观察项 / 生产姿态类课题）。



---

## 5. 新主 Agent 接手操作规约（3 步启动法）

当用户在新的对话窗口中输入接手指令时，新主 Agent 必须按以下 3 步执行：
1. **核验状态**：执行 `git status --short` 与 `git log -1 --oneline`，核实是否与本文件第 1 节一致；
2. **拒绝冗余**：**绝不新建任何交接类 Markdown 文件**；
3. **极简汇报**：直接向用户输出以下结构性回答：
   - **接手状态**：当前 HEAD 与最近闭环任务；
   - **拟执行动作**：从第 4 节推荐动作确认下一步计划；
   - **需用户决策**：如有产品决策或授权需求提出提问，否则给出下一步任务包并请用户拍板派发。


