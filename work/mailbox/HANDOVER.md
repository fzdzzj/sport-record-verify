# 主 Agent 跨会话无缝交接看板（HANDOVER.md）

> **【新主 Agent 接手必读】**
> 本文件为**唯一常驻、原地滚动更新（In-Place Rolling Update）**的跨会话交接看板。
> 严禁因更换会话窗口而新建任何带日期或别名的交接文档（如 `交接-xxx.md`）。所有交接信息在此处就地维护。

---

## 1. 当前代码基线与外部状态

- **当前分支与 HEAD**：`main` 分支，最新已终裁任务为 **TASK-259**（G 系批 G-1——G1 时区语义全链统一，FULL 档；C-01 `48a0a45` / C-02 `692073b`，指导亲核三块 + 独立复核五块双 PASS 零阻断，偏差①比对帧牵连带与偏差②两文件口径追认，N-1~N-6 文字层笔误登记；surefire record 302→308；封盘批次 6 笔推送后 CI 外部终验读数由补记笔登记）。前一封盘 TASK-257（E 系批4b-2，CI run `38931526812` 全绿完整闭环）、TASK-256（批4b-1，`38917734857`）、TASK-255（批4a-2，`38903763620`）、TASK-254（批4a-1，`38892885813`）、TASK-253（批3，`38879776160`）、TASK-252（批2，`38869656012`）、TASK-251（批1，`38864817657`）。**E 系批1~批4b-2 全部封盘 31 项闭环；G 系批 G-1 TASK-259（G1 时区语义全链统一，FULL 档）已终裁 ACCEPT 待 CI 外部终验；当前在途：**TASK-260**（G 系批 G-2——G2+G5+G9+G11+G13 record/leaderboard 口径小批五项，FULL 档已立项待派发）；E13 拆出拟 TASK-258（编号保留占位）**。
- **外部 CI 门槛状态**：TASK-257 封盘批次经 CI run `38931526812` 权威核验 conclusion=success 全 job 绿（`build` ✓ 10m44s / `web` ✓ 26s、probe-ubuntu26 skipped=if 门禁正确生效旁证；两条 varargs annotations 为既有测试 javac 警告非阻断）——批4b-2 外部终验达成完整闭环，**E 系七批 31 项候选全部完整闭环**（2026-10-17 补记笔登记）。前一批次：TASK-256 经 `38917734857` 全绿（build ✓ 10m56s / web ✓ 21s）；TASK-255 经 `38903763620` 全绿；TASK-254 经 `38892885813` 全绿；TASK-253 经 `38879776160` 全绿；TASK-252 经 `38869656012` 全绿；TASK-251 经 `38864817657` 全绿。
- **工作树状态**：TASK-259 封盘批次（6 笔：TASK-257 补记笔 `a21ee02` + G 系登记笔 `50d1877` + 立项笔 `b18c9fe` + C-01 `48a0a45` + C-02 `692073b` + 封盘笔 `552bbb9`）已推送与 origin/main 平齐；本地新增 TASK-260 立项笔（待 TASK-259 CI 补记笔落库后共领先 2 笔，仅本地保留随下一批次统一推送）。
- **当前在途任务数**：`1` 个——**TASK-260（G 系批 G-2：G2+G5+G9+G11+G13 record/leaderboard 口径小批五项，FULL 档已立项待派发）**；TASK-259（G 系批 G-1）已终裁 ACCEPT，CI 外部终验读数由补记笔登记；G 系余 22 项在册（G2/G5/G9/G11/G13 已切分入 TASK-260，余 17 项待后续批次）；E13 拆出拟 TASK-258（编号保留占位）。

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
   - **CI runner 预警**：GitHub `ubuntu-latest` 将于 2026-10-19 起迁移至 Ubuntu 26（当前 run 已附 deprecation 注解：Node 20 actions 强制跑于 Node 24、`actions/setup-java@v4` 停更），迁移后首个 CI run 需重点观察，若红按环境签名归因登记，不盲目重试刷绿——**已立项 TASK-252 预备治理（2026-10-16）**；
   - **E 系批1/批2 立项（2026-10-16）**：TASK-251（E1+E2+E3 认证/鉴权面三处破口，FULL 档）+ TASK-252（E4+E31 CI runner 迁移预备，MEDIUM 档，串行第二批）；
   - **E 系批1 收口（2026-10-16）**：TASK-251 已终裁 ACCEPT 予以封盘（C-01 `124c93e` / C-02 `fd14459`，独立复核五块 PASS 零阻断；token 门 3 项各 −1 为课题目标性口径替换经用户显式追认；非阻断登记：N1 handoff §0 quotePath 陈旧叙述（已随封盘笔订正）、N2 compose 显式 5 处 vs 叙述四服务（网关条目系冗余加强）、N3 token 29 项清单仓内无追踪定义（历史清单在 TASK-181 §6 与归档 PLAN-history，后续派发包直接附该指引）、N4 token 台账记账粒度 1-2 计数出入；后续顺手项：healthcheck.sh L9-10 与项目速览手册 L197-199 旧 8080 口径经网关打 internal 现 404（零触碰面、README 已订正）、handoff §6 词面门表述瑕疵（权威口径零命中已双验）；lessons：契约工具 CJK 提取缺口（mailbox-contract.sh 升级需专门授权））；
   - **E 系批2 收口（2026-10-16）**：TASK-252 已终裁 ACCEPT 予以封盘（C-01 `66d0ec3` / C-02 `49b02c7`，MEDIUM 档免独立复核，指导亲核三块 PASS 含 typed-router 步骤移位重排存疑解除；偏差登记：spec §4.1 surefire 预期为 TASK-251 开工前旧值、以收口实测 52/71/205/300/182/104/29 持平为准、probe job 纳入 setup-java 步骤、probe Trivy 仅门禁跑次、契约门 .trivyignore 需 ./. 前缀形态沿 TASK-195 先例）；CI run `38869656012` 全绿（web ✓27s / build ✓10m27s，probe-ubuntu26 skipped = if 门禁正确生效旁证）完整闭环；**受限：ubuntu-26.04 真实试跑未做，待 2026-10-19 标签可用后手动 workflow_dispatch probe26=true 验证（读数随补记笔登记，此事 10-19 后必做）**；
   - **E 系批3 立项（2026-10-16）**：TASK-253（E5+E6+E7+E9 数据一致性，FULL 档）立项笔落库——立项侦察亲验四项全部坐实：E5 恰 4 @Scheduled + yml 零池配置 + archiveSweep 无 initialDelay 启动即跑；E6 record-service 零 verify 事件消费者（状态迁移唯一路径同步 Feign 无补偿）+ reconcileCallback L168 白名单不含 APPEALING；E7 applyVerified 先 DB 后 ZSet（二次复核修法覆盖主竞态窗口）；E9 05 脚本注释自证非幂等 + migrate.sh 全量重跑无 archived 迁移；
   - **E 系批3 收口（2026-10-16）**：TASK-253 已终裁 ACCEPT 予以封盘（C-01 `1c35a27` / C-02 `22a0802`，FULL 档：指导亲核三块 PASS + 独立复核五块全 PASS 零阻断，N1-N4 非阻断登记——N1 单测 6 用例较 spec 五类多 RE_CONFIRMED 映射属增强、N2 spec 行文 `_00..15` vs 仓内实际表名 `_0..15` 无零前缀（后续任务书书写统一实际分片表名形态）、N3 本机无 Nacos/MySQL 环境噪声、N4 yml→properties 条件项授权链独立坐实）；改动恰 10 文件白名单双向全等（条件项：record-service 实际为 application.properties，文件头自证 snakeyaml 冲突禁改 yml，属性键语义等价）；后端 surefire verify 182→188、leaderboard 104→108 只增不减，token 门 28 持平 + 1 项 +1（新单测里程值子串命中）无一减少；CI run `38879776160` 全绿（`web` ✓27s / `build` ✓11m15s，HEAD `d791b7a`，probe-ubuntu26 skipped = if 门禁正确生效旁证）外部终验达成完整闭环，读数由补记笔落库；非阻断留痕：R4 scratch 库实测留证未做（可选受限，幂等形态沿两先例背书）；
   - **E 系批4a-1 立项（2026-10-16）**：TASK-254（E10+E11+E12+E16 网关与高危簇 + 两顺手项，FULL 档——E11 触碰 verify 判定核心链路、E16 触碰网关限流安全语义禁降级）立项笔落库——立项侦察亲验四项坐实：E10 gateway yml 全文无 `spring.cloud.gateway.httpclient.*` 超时段（对照 verify Feign connect 1000/read 3000）；E11 前半 RuleVersionService 零规则阈值校验（R1SpeedRule L50 `sum/(window-1)`——windowPoints=1 除零 NaN/Infinity、=0 时 windowAvg[-1] 越界）+ 后半 VerifyEngine L78-83 规则链零 try/catch；E12 LikeController `likes/batch` `List<Long>` 零上限 + RecordLikeService 逐 id selectById+assertCanView N 次放大；E16 toGatewayFlowRules 合法空数组得空集覆盖兜底（应沿解析失败「保持上一版」语义返 null）。**E27 反证关闭**（GatewayProbeTest 真实网关全上下文断言 /actuator/health/liveness|readiness 经网关 200/UP 基线全绿——无路由时落入 actuator 端点映射非 404，白名单为有效配置，本课题零 E27 改动）；两顺手项搭车（RecordLikeMapper L64/L73 Javadoc 订正为键集分页游标口径 + healthcheck.sh L9-10 与速览手册旧 8080 口径）；修法路线：E11 程序化校验落 RuleVersionService（不动 VerifyProperties/DTO/Controller 避免 @Validated 对 Nacos 配置绑定语义外溢）、E12 落 RecordLikeService 服务层拒绝式（沿 SportRecordService 数学钳制先例域）；
   - **E 系批4a-1 收口（2026-10-16）**：TASK-254 已终裁 ACCEPT 予以封盘（C-01 `342bd9a` / C-02 `fb34665`，FULL 档：指导亲核三块 PASS + 独立复核五块全 PASS 零阻断，N1-N3 非阻断登记——N1 任务书两处零触碰面路径描述性笔误（filter/AuthGlobalFilter→auth/、model/VerifyProperties→config/）实存均零 diff、N2 token SUM 计数口径差异（实施侧行命中 1989 / 复核侧文件出现次数 2017，29 项逐位持平红线一致，建议后续统一口径）、N3 契约门判据 B rc=1 确认为 CJK 路径工具缺口沿 TASK-251 N1 处置）；改动恰 15 文件白名单双向全等（工具判据 + 等价判据双通道）；后端 surefire gateway 71→75、record 300→302、verify 188→195 只增不减，token 门逐位持平，词面门 ZERO_HIT+探针对照；偏差授权链：D1 R4 复用 record 域既有码 SPORT_TYPE_INVALID（common 零触碰不可加码）、D2 E16 包私有可见性 C-02 登记、D3 checkstyle 4 项基线既有 delta=0；封盘批次 5 笔已推送，CI 外部终验读数由补记笔落库；N1/N2 治理建议随课题收口记入项目记忆；
   - **E 系批4a-1 外部终验（2026-10-16）**：TASK-254 封盘批次 CI run `38892885813` 权威核验 conclusion=success 全 job 绿（`build` ✓11m19s / `web` ✓29s / `probe-ubuntu26` skipped=if 门禁正确生效旁证），外部终验达成完整闭环；补记笔落库（PLAN.md TASK-254 门槛行翻转「已到达」+ TASK-254/handoff.md CI 登记 + 本文件 §1/§3），仅本地保留随下一批次统一推送；
   - **E 系批4a-2 立项（2026-10-16）**：TASK-255（E17+E21+E23+E24+E29 配置性能余项，FULL 档——E21 触碰 common 全局异常契约面跨服务 HTTP 状态码语义、E24 触碰榜单结算归窗正确性、E17/E29 触碰 mapmatch 判定链路，多模块 common/mapmatch/record/leaderboard + scripts/sql 禁降级）立项——五项 ◎ 复验**全部坐实**：E17 三半（DB 超时三处缺失 / TRUNCATE ACCESS EXCLUSIVE 分钟级重灌窗口致 R5 全量误拒 / thin 零下界——max=0 NaN fail-open、max=1 除零，与 chunkSize 有 Math.max(1) 保护不对称）；E21 三半并补现状（common 全局 handleBizException 恒 200 影响 verify/mapmatch；415/406/缺头三类框架异常落兜底 500；同码 1002 网关 403 vs 服务 200 不一——**record/user/leaderboard 八个 controller 局部 handler 已正确映射为先例，公共层缺位**，前端 client.ts 静态核对 1001→401 激活 refresh/1002→403 兼容）；E23 坐实**含前提订正**（selectDailyDistanceSince 系 TASK-235 后失用非「全仓无调用（从未）」，现况 main 零调用 grep 实证；退化点 myStats L932-938 与 GoalService L198-199）；E24 缺陷链完整推演（updateStatus 只 SET status + markSettled 仅盖 IS NULL → 改判里程永久归 T1 旧周期漏计当周/当月榜）；E29 三点（addAll 无去重/单叶判据/每点每边重复 WKT 解析）。修法 R1-R7 定案（超时三件套 PG JDBC 秒单位 / 影子表 staging+单事务 RENAME 原子切换 / thin Math.max(2,max) / common 全局 code 映射+三类框架 Handler（八个局部 handler 保留不动，机制收拢另立候选）/ MySQL 函数索引纯索引路径（服务层零改动）双路径迁移 / reactivate 清 settled_at / LinkedHashSet 去重+累计判据+WKT 预解析行为等价硬约束）；白名单 16 项（14 业务/测试/SQL + 2 台账）；两件套落库、BACKLOG 五行翻转含 E23 口径订正与推荐序滚动；立项笔落库，实施派发包已生成待用户派发；
   - **E 系批4a-2 收口（2026-10-16）**：TASK-255 实施回报（C-01 `ce4aa4a` 恰 14 文件 / C-02 `1ac8c60` 台账）→ 指导亲核三块 PASS（Git 摘要 16 条全等；高风险抽查 GlobalExceptionHandler 枚举映射 + reactivate SQL 逐字一致并坐实 leaderboard +0 登记真实性；回报一致性零矛盾）→ 独立复核五块全 PASS 零阻断（七门禁全量复跑：surefire 59/75/205/302/195/108/35 只增不减、token SUM=1353 逐位持平、契约门收口态 rc=1 属 C-02 台账固有语义等价判据 16 项全等、词面门 ZERO_HIT+探针对照、checkstyle delta=0）N1-N3 非阻断登记（N1 C-01 计数誊录偏差封盘笔订正 +452/−112→+426/−86、N2 旧 git clean 假阳性三重核证、N3 控制台乱码显示 artifact）→ 指导侧终裁 **ACCEPT**：五看板滚动（PLAN TASK-255 验收小节 + BACKLOG 五行封盘翻转 + spec/handoff 状态行 + N1 订正）、封盘笔落库，含 TASK-254 补记笔共 5 笔推送 origin/main，CI 触发待观察（全绿后补记笔登记运行号）；
   - **E 系批4a-2 外部终验（2026-10-16）**：TASK-255 封盘批次 CI run `38903763620` 权威核验 conclusion=success 全 job 绿（`build` ✓ / `web` ✓、`probe-ubuntu26` skipped=if 门禁正确生效旁证），headSha `f1b0dcf` 与封盘批次匹配；补记笔落库（PLAN.md TASK-255 门槛行翻转「已到达」+ TASK-255/handoff.md 状态行 CI 运行号与终裁依据登记 + 本文件 §1/§3），仅本地保留随下一批次统一推送；
   - **E 系批4b 立项（2026-10-16）**：批4b 测试可观测 12 项切分立项——指导侧逐项亲验全部坐实（◎ 8 项复验含行号级证据；E15 三处证据链闭合：TASK-193 spec-delta「5→20 兜底」已批准语义 vs user yml L78 显式 threshold:5 覆盖打回 vs README L223 滞后——修法为向已批准语义对齐非新设计决策）：**TASK-256**（批4b-1：E15+E18+E19+E20+E22+E25+E28+E30 后端治理八项，FULL 档——E19 触事务原子性/E20 触凭证面/E28 触全链入口净化禁降级，白名单 22 项含 1 条件项，两件套落库待派发）+ **TASK-257**（批4b-2：E8+E14+E26 前端测试可观测三项，MEDIUM 档，白名单 10+2 项，串行 256 封盘后派发）+ **E13 拆出**（真实 PostGIS 库复验路径异质——scratch 容器实测 vs 纯 JVM，且结果未知可能引出修复批；拟 TASK-258 待批4b-1/4b-2 封盘后立项）；BACKLOG 12 行翻转 + 推荐序滚动、HANDOVER §1/§4 滚动、立项笔落库；
   - **E 系批4b-1 收口（2026-10-16）**：TASK-256 实施回报（C-01 `a89d095` 恰 23 文件 +542/−32 / C-02 `2a2f11f` 台账）→ 指导亲核三块 PASS（Git 摘要 25 项全等；高风险定向抽查 E15 yml/README 与 spec R1 逐字吻合 + E28 双实现镜像同规则且净化正则字面量仅落 Java 代码；回报一致性裁决坐实 N-a VerifyControllerTest 白名单偏差根因为立项笔 §2 清单漏列（§3 R5 明文要求其扩展）、N-b record 持平为适配性改动非删测、N-c §2 计数笔误）→ 独立复核五块全 PASS 零阻断（surefire 63/78/215/302/200/108/35 只增不减、token 门 29 项 BASE==HEAD 逐位相等改动集零命中、契约门收口态 rc=1 等价判据 25 条全等、词面门 ZERO_HIT+探针阳性对照、checkstyle delta=0、零触碰面目录级核证无假阴性）4 项非阻断登记 + N-a~N-d 定向核证全部成立 → 指导侧终裁 **ACCEPT**（N-a 追认入白名单 + N-c 封盘笔订正）：五看板滚动（PLAN TASK-256 验收小节 + BACKLOG 八行封盘翻转与推荐序 + spec/handoff 状态行 + spec §2 计数订正与第 23 项追认补录 + C-02 哈希回填）、封盘笔落库，含 TASK-255 补记笔与批4b 立项笔共 5 笔推送 origin/main，CI 触发待观察（全绿后补记笔登记运行号）；
   - **E 系批4b-1 外部终验（2026-10-17）**：TASK-256 封盘批次 CI run `38917734857` 权威核验 conclusion=success 全 job 绿（`build` ✓ 10m56s / `web` ✓ 21s、`probe-ubuntu26` skipped=if 门禁正确生效旁证；两条 varargs annotations 为既有测试文件 javac 警告非阻断），headSha `1772102` 与封盘批次匹配；补记笔落库（PLAN.md TASK-256 门槛行翻转「已到达」+ TASK-256/handoff.md 状态行 CI 运行号与终裁依据登记 + 本文件 §1/§3），随 TASK-257 封盘批次统一推送；
   - **E 系批4b-2 收口（2026-10-17）**：TASK-257 实施回报（C-01 `033110a` 恰 18 文件 +431/−37 = 白名单 10 + 条件项 8 / C-02 `5788184` 台账，首笔 6e30a81 因契约判据 B 要求 handoff 逐行清单 amend）→ 指导亲核三块 PASS（①Git 摘要恰 2 笔 porcelain 空；②高风险抽查 3 处：E8 sharedRefresh 单例/finally 清引用/_retry 语义与 R1 逐条吻合、基线红订正 3 断言与 feed.page.vue L137 '🏃 跑步'（TASK-245 B27）、index.page.vue L470 toFixed(2)（TASK-242 B19）页面实况逐字吻合属向演进同步断言非掩盖回归、notifications.e2e.ts L56-58 落点注释登记完整；③回报一致性：vitest 61→63、E2E 14/14=12+2、surefire 持平零后端文件、token SUM 2017 持平、契约收口 rc=0 由 handoff §2 逐行 20 项清单支撑，零矛盾）→ MEDIUM 档免独立复核：五看板滚动（PLAN TASK-257 验收小节 + BACKLOG E8/E14/E26 三行翻转与推荐序 E 系收官 + spec/handoff 状态行）、补记笔 `1dd8ab8` 与封盘笔落库，批次 4 笔推送 origin/main，CI 触发待观察（全绿后补记笔登记运行号）；**E 系七批 31 项候选全部收口**；
   - **E 系批4b-2 外部终验（2026-10-17）**：TASK-257 封盘批次 CI run `38931526812` 权威核验 conclusion=success 全 job 绿（`build` ✓ 10m44s / `web` ✓ 26s、`probe-ubuntu26` skipped=if 门禁正确生效旁证；两条 varargs annotations 为既有测试文件 javac 警告非阻断），headSha `b1d763c` 与封盘批次匹配；补记笔落库（PLAN.md TASK-257 门槛行翻转「已到达」+ TASK-257/handoff.md 状态行 CI 运行号登记 + 本文件 §1/§3），仅本地保留随下一批次统一推送——**E 系批1~批4b-2 七批 31 项候选全部完整闭环，当前在途 0**；
   - **G 系优化点专项侦察归档（2026-10-17）**：A–M 十四面全量过筛产出 23 项候选（HIGH 1 / MEDIUM 7 / LOW 15）登记 BACKLOG 新批次——G1 时区语义全链缺失（HIGH/FULL，归日/周期/契约核心语义）居首；混合侦察口径如实登记（K 面只读子代理完成 + J/L/M 面子代理三次容量拒绝改侦察侧本人定点自扫；G6 证据经侦察侧对 RecordController L334-343 抽查坐实）；Nacos 运行时配置未核、全部结论静态阅读——◎ 标注项与「需 EXPLAIN/压测确认」项（G13/G22/G23 等）立项前须亲验/实测；推荐组合批 G-1（G1 单独 FULL）~G-5 + 轻量杂项 G18/G19 供用户拍板批次切分（G 系登记笔与 TASK-257 补记笔分笔落库）；
   - **G 系批 G-1 立项（2026-10-17）**：用户拍板按推荐方案先立项 G1——**TASK-259（G1 时区语义全链统一，FULL 档）**立项笔落库：指导侧亲验 8 处全部坐实（spec §1 行号级：三帧分裂矩阵——中间件 TZ 东八 + JDBC serverTimezone 7 处东八 vs 六服务容器 JVM=UTC vs 前端浏览器本地解析；created_at L226 vs 推导 L1068-1069 显式 UTC；归日链 myStreak/myStats/GoalService；feed L169/leaderboard L72-83）；测试适配面收口恰 1 处（SportRecordServiceTest L530-532 两断言 UTC 期望值，其余 setStartTime 均入参构造自洽）；修法定案=全链东八墙钟统一（方案 A）：R1 compose 六服务 TZ 注入 + R2 推导改区 BUSINESS_ZONE 常量（唯一生产代码改动）+ R3 断言适配（向演进同步非删弱）+ R4 边界用例 ≥4（固定 Instant + 显式 ZoneId，runner 时区无关：跨日提交/23:59 边界/晨跑归日/周月窗口）；受限预登记四项（真实容器 JVM 运行态读数/存量数据不迁移/非东八浏览器边界/Nacos 未核）；白名单 4+5 项（compose 单文件 + record-service 两文件 + 台账 5，零触碰 api/其余五服务/web/sql/scripts）；两件套落库、PLAN/BACKLOG/HANDOVER 滚动、实施派发包提示词已生成待用户派发执行子agent；
   - **G 系批 G-1 收口（2026-10-17）**：TASK-259 实施回报（C-01 `48a0a45` 恰 3 文件 +243/−14 / C-02 `692073b` 恰 2 文件 +53/−16）→ 指导亲核三块 PASS（R2 BUSINESS_ZONE 落点、偏差① L1114-1125 三元分支「推导值东八帧/显式值 JVM 帧」语义精细正确、R1 TZ 恰 6 处 + R4 六测落点坐实；N-1 C-02 回报行数 +67/−30 为笔误实测 +53/−16）→ 独立复核五块全 PASS 零阻断（七门禁 7/7 独立复现全绿 63/78/215/308/200/108/35、白名单外 17 项显式路径全零 diff、偏差①与修前基线 L1107 对照成立 + 全文 ZoneOffset 零残留、UTC runner 反向实测 -Duser.timezone=UTC 308 全绿与东八常态跑构成双向 runner 时区无关性坐实、handoff §2 五行同序全等；N-1~N-6 非阻断均文字层笔误集中登记封盘笔不退改 handoff 原文）→ 指导侧终裁 **ACCEPT**（偏差①红→绿证据链完整+三元分支语义正确追认；偏差② compose 两文件 CI 权威口径 overlay 既有行为追认）：五看板滚动（PLAN TASK-259 验收小节实施/亲核/复核/门槛/状态五行收口 + BACKLOG G1 行与推荐序批 G-1 封盘翻转 + spec/handoff 状态行终裁），封盘批次 6 笔（TASK-257 补记笔 `a21ee02` + G 系登记笔 `50d1877` + 立项笔 `b18c9fe` + C-01 + C-02 + 封盘笔）统一推送，CI 触发待观察（全绿后补记笔登记运行号）；**G 系 23 项候选第 1 项收口，余 22 项在册**；
   - **G 系批 G-2 立项（2026-10-17）**：用户拍板按推荐方案切分批 G-2 先做亲验抽查——**TASK-260（G2+G5+G9+G11+G13 record/leaderboard 口径小批五项，FULL 档——G2 触碰核心服务逻辑并发安全语义就高禁降级）**立项笔落库：指导侧亲验五项全部坐实（G2 SportRecordService L609-617 整行回写 selectById→setVisibility→updateById + record-service 主代码 `@Version` 零命中坐实无乐观锁，E30 同根因新落点；G5 ContributionMapper L118 `ORDER BY totalDistance DESC` 无次序键 + 日榜先例 DailySummaryMapper L61 已带 `, user_id ASC`；G9 两处注释滞后——L112-113「settled_at 无专用索引」vs DDL 02-record-db.sql L63 idx_status_settled 已建 + mapmatch application.yml L92「仅依赖 MySQL」实为 PostgreSQL/PostGIS；G11 L723-727 my 记录过滤裸 getStartTime vs myStats L937/GoalService L194 COALESCE 口径错位；G13 L778-787 pageFeed 查询形态，filesort 与否留 R5 前置 EXPLAIN 双结局——坐实仅改查询形态**禁加索引**，不坐实零改动登记）；修法 R1-R5（R1 LambdaUpdateWrapper 单列 set 沿 E30 修法范式 + R2 补 user_id ASC + R3 两处注释订正 + R4 COALESCE 口径统一 + R5 EXPLAIN scratch 库双结局）；白名单 5+5 项（零触碰 sql/DDL——G9 索引已建无需补）；两件套落库、PLAN/BACKLOG 滚动，实施派发包提示词已生成待用户派发执行子agent；
   - 申诉审核意见（verify_db）透出限制：受限与 verify-service 红线，当前前台申诉进度条由记录状态推导，结论权威可靠。

---

## 4. 当前推进与唯一推荐动作

- **当前推进**：**G 系批 G-1 TASK-259 已终裁 ACCEPT 封盘**（CI run `38990356530` 观察中，全绿后补记笔登记）；**G 系批 G-2 TASK-260 已立项**——亲验五项全部坐实（G2 整行回写/@Version 零命中、G5 无 tie-break、G9 注释滞后两处、G11 口径错位、G13 条件项留 EXPLAIN），FULL 档（G2 并发安全语义就高），修法 R1-R5、白名单 5+5 项、两件套 + 实施派发包就绪，当前在途 1。
  - **唯一推荐动作**：①**派发 TASK-260 实施子agent**（实施派发包提示词已生成，由用户亲自派发；开工基线以 `git rev-parse HEAD` 实测回填为 TASK-260 立项笔 hash）；②观察 TASK-259 封盘批次 CI run `38990356530`（后台 watch 中；权威核验 `gh run view 38990356530` 确认 conclusion=success 全 job 绿，禁凭口头收口；若红按签名归因禁重试刷绿）→ 全绿后落补记笔（PLAN 门槛行翻转 + TASK-259/handoff 运行号 + 本文件 §1/§3，仅本地保留随下一批次推送）；
  - **10-19 必做事项（沿挂账）**：ubuntu-26.04 标签可用后，手动 `workflow_dispatch` 触发 probe26=true 试跑 probe-ubuntu26 job，读数随补记笔登记；通过后另立课题平移 runs-on 并摘除钉扎注释；
  - **治理挂账（非阻断，随下一课题/批次顺手）**：token 计数口径统一（TASK-254 N2：逐项 git grep -cF 文件出现次数为权威口径）、零触碰面路径书写以仓内实存路径为准（N1）、八个局部 BizException handler 机制收拢（TASK-255 E21 修复后局部与全局并存——**G6 已入候选覆盖此项**）、E5 类「X-Request-Id 透传」等散件见 BACKLOG 排除声明。



---

## 5. 新主 Agent 接手操作规约（3 步启动法）

当用户在新的对话窗口中输入接手指令时，新主 Agent 必须按以下 3 步执行：
1. **核验状态**：执行 `git status --short` 与 `git log -1 --oneline`，核实是否与本文件第 1 节一致；
2. **拒绝冗余**：**绝不新建任何交接类 Markdown 文件**；
3. **极简汇报**：直接向用户输出以下结构性回答：
   - **接手状态**：当前 HEAD 与最近闭环任务；
   - **拟执行动作**：从第 4 节推荐动作确认下一步计划；
   - **需用户决策**：如有产品决策或授权需求提出提问，否则给出下一步任务包并请用户拍板派发。


