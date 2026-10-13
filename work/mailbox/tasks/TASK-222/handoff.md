# TASK-222 handoff：好友动态订阅与跨服务聚合读路径（P4，FULL 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-222`（提案 `spec/changes/add-friend-feed/proposal.md`）。
- 分级：**FULL**（跨服务聚合读路径 + 好友 ID 双向还原 + feed 预过滤与门等价论证；独立 review 复核适用）。
- 开工基线：`84a6e2d`（TASK-221 封盘笔）；开工前 `git status` 干净、本地领先 origin 1 笔（84a6e2d 随本课题批次推送，勿动）、未推送。
- 离线基线（任务书 §6）：52/50/170/196/159/80/26（record=196，user=170；D7 后 user 仍 170）。
- C-01 实施笔：`9db644a`（`feat(feed): 好友动态订阅与跨服务聚合读路径（TASK-222）`，恰 16 文件，+730，2 新文件）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI）待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 84a6e2d，恰 20 文件 = C-01 16 + C-02 4）

C-01（feat(feed) 实施笔，16 文件 = S4 白名单全等）：
- api/src/main/java/com/sportverify/api/user/UserApi.java（+ listFriendIds：GET /internal/friends/ids，不分页拉全量好友 ID）
- api/src/main/java/com/sportverify/api/user/UserApiFallback.java（+ listFriendIds 软降级空列表，feed 退化为仅本人 fail-safe）
- api/src/main/java/com/sportverify/api/record/dto/FeedItemDTO.java（新，RecordSummaryDTO 全字段 + authorUserId + authorNickname）
- user-service/src/main/java/com/sportverify/user/service/FriendService.java（+ listFriendIds：双向还原，low 端取 high / high 端取 low，distinct 去重）
- user-service/src/main/java/com/sportverify/user/controller/InternalFriendController.java（+ GET /internal/friends/ids，纯委托）
- user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java（+ 3 测：双向还原 / 去重 / 空与 null）
- record-service/src/main/java/com/sportverify/record/controller/RecordController.java（+ GET /api/records/feed，身份门与 /my 同构）
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java（+ pageFeed / listFeedFriendIds / buildFeedItems / fetchNicknames）
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java（+ 7 测：过滤/组装/昵称/降级/钳制）
- record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java（+ 3 测：feed 身份门）
- web/src/api/client.ts（+ FeedItemDTO 类型 + getFeed 封装）
- web/src/pages/feed.page.vue（新，/feed 路由页：作者昵称+可见性徽标+类型/距离/状态+相对时间，a-pagination 分页）
- web/src/App.vue（顶栏导航 +「动态」入口）
- web/src/api/__tests__/client.test.ts（+ 1 测：getFeed 打 /record/api/records/feed 带 page/size）
- web/src/typed-router.d.ts（再生成物：build 新增 /feed 路由，+13 行，沿 TASK-213 先例随路由提交）
- web/README.md（一句登记 feed 页）

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，4 文件）：
- spec/changes/add-friend-feed/tasks.json（勾选）
- work/mailbox/tasks/TASK-222/spec.md（§7 回填）
- work/mailbox/tasks/TASK-222/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

> 下列载体零触碰（G2 证据见 §5）：六服务白名单外 src/main、父/子 pom、.github/workflows/ci.yml、scripts/、docker-compose*、sql/ 既有文件、web/package.json、web/vite.config.ts、既有 web/src/pages/*（除新增 feed.page.vue 与改 App.vue）、api/record/verify/leaderboard/mapmatch 其余模块、既有读取面门（assertCanView / verify-result / points / like 三端点一行不改）。词面门/探针/token/契约/tokencheck 临时脚本均落 gitignored `.trae/tmp`、不入库。

## 3. 一句话结论

为好友链补上「看见朋友动态」的消费面（提议背景里社交链最后一块）：新增 `GET /api/records/feed`（record-service，归属即身份，身份门与 /my 同构，缺头 1001）。语义：auth on 时 `user_id IN (self ∪ friendIds)` 且 `(user_id=self OR visibility∈{0,1})`——本人任意可见性 + 好友 PUBLIC/FRIENDS，createdAt DESC 分页；**陌生人记录即使 PUBLIC 也不入 feed**（feed ≠ explore）。degrade（caller=null，auth.enabled=false）→ `visibility=PUBLIC` 全站时间线。跨服务：user-service 新增 `GET /internal/friends/ids`（FriendService.listFriendIds 双向还原，一次拉全量供 IN 子句），昵称批量**复用既有** `listUsersByIds`（recon 确认已存在，S1「有则复用」）；UserApi 增 `listFriendIds`，Fallback 软降级空列表 → feed 退化为仅本人（fail-safe），昵称空 → web 回退 authorUserId 字符串。web 新增 `/feed` 路由页（卡片=作者昵称+可见性徽标+类型/距离/状态+相对时间，分页 a-pagination）+ 顶栏「动态」入口。FeedItemDTO = RecordSummaryDTO 全字段 + author + authorNickname。零 schema、零新索引、零 MQ、既有 visibility 门一行不改（feed 预过滤与 assertCanView 对 feed 返回记录等价 = 返回集 ⊆ 门可读集，不越过门）。

## 4. 判别式红绿（R1-R6 / G1-G3 逐条）

- **R1（绿）过滤与排序**：auth on feed 恰含 own（任意 visibility）+ friends（PUBLIC/FRIENDS）。`SportRecordServiceTest.pageFeed_authOn_filtersSelfAndFriendAuthors` 实证 wrapper 携带 `user_id IN` 且候选集含 self(200)+好友(201,202,500)，陌生人不在候选；`pageFeed_authOn_visibilityConstraint` 实证 `visibility IN` 与「本人任意 OR 好友可见性」分支；createdAt DESC 排序在 service 查询；掌控下数据 `pageFeed_authOn_assemblesDtoWithNickname` 实证 createdAt DESC 返回序。
- **R2（绿）degrade**：`pageFeed_degrade_callerNull_publicOnly` 实证 caller=null 时查询 `visibility=PUBLIC(0)` 全站（不区分归属）且 `verifyNoInteractions(userApi)`（零好友/昵称调用）；`RecordControllerTest.feed_authEnabled_resolvesCallerFromHeader`（auth 开 X-User-Id→caller）、`feed_authEnabled_missingHeader_throwsUnauthorized`（缺头→1001，/my 同构）、`feed_authDisabled_degrade_callerNull`（auth 关 caller=null→PUBLIC）。
- **R3（绿）好友 ids 双向还原**：`listFriendIds_bidirectionalRestore` 实证本人 low 端取 high、high 端取 low（friendship(1001,1002)→1002、friendship(1003,1001)→1003），一次非分页 `selectList`；`UserApiFallback.listFriendIds` 软降级空列表（feed 退化为仅本人，由 `SportRecordServiceTest.pageFeed_userApiDown_degradesToSelfOnly` 实证——listFriendIds 抛异常被 catch → 候选集不含好友、仍执行 self 作用域 IN 查询，不崩）。
- **R4（绿）作者昵称**：当页 distinct author 批量 `listUsersByIds`——`pageFeed_authOn_assemblesDtoWithNickname` 实证 author=201 昵称「B」正确映射、`idsCap` 捕获实参含本人+好友且去重；`pageFeed_nicknameFallback_null` 实证软降级空 → authorNickname=null（web `authorName` 回退 authorUserId 字符串渲染，feed.page.vue 实现）。
- **R5（绿）web**：/feed 页卡片要素齐（作者昵称 authorName、可见性徽标 1/2 tag+0「公开」、类型/距离/状态、相对时间 relativeTime、详情入口 goTrack 链到 record-detail）+ 顶栏「动态」导航入口（App.vue）+ a-pagination 分页（onPageChange/onSizeChange）。验证由 type-check rc=0 + build 成功 + vitest rc=0（getFeed 请求 URL/params 断言）+ 代码审查承载；**真实浏览器交互不跑，登记未覆盖**（沿 TASK-213/220/221 口径）。
- **R6（绿）门不弱化等价性**：既有读取面门零 diff（`assertCanView` / pagePoints / getVerifyResult / like 三端点一行未动）。feed 预过滤等价论证：feed 返回集 S = {self 记录（任意可见性）} ∪ {好友记录（PUBLIC/FRIENDS）}。对任一 r∈S：若归属 self，assertCanView(owner) 恒放行；若归属好友且 visibility∈{PUBLIC,FRIENDS}，assertCanView(reader=caller) 中 caller 是归属人好友（friendIds 来源于 friendship）→ FRIENDS 门好友放行、PUBLIC 门放行。⇒ S ⊆ assertCanView 可读集，feed 不越过门（陌生人被 user_id IN 排除 = 门对陌生人 FRIENDS/PRIVATE 403 子集的规避）。**never 返回门将 403 的记录**（门零弱化，feed 是门的严格子集读面）。
- **G1（绿）**：offline 全量 BUILD SUCCESS rc=0，七模块逐位 **52/50/173/206/159/80/26**——user 170→173 恰净增 3、record 196→206 恰净增 10，其余五模块逐位不变；全 log 零 非零 Failures/Errors/Skipped。
- **G2（绿）**：C-01 后 `git diff --name-only 84a6e2d` + untracked 恰白名单 16 文件（14 M + 2 ??）；越界面（其余服务主码、pom、ci.yml、scripts、compose、sql、web/package.json、web/vite.config.ts）diff 全空。
- **G3（绿）**：`--static=record-service` rc=0（0 Checkstyle violations / SpotBugs Error size 0 / PMD BUILD SUCCESS——唯一声明并强制三件套的改动模块）；web pnpm test rc=0（4 套 25 测，client.test 7→8）、type-check rc=0、build 成功（3283 modules）、typed-router.d.ts 再生成 +13 行且二次构建逐字节一致（idempotent，diff 不抖）；契约门双态 rc=0（在途 `--open TASK-222 --baseline=84a6e2d` + 收口无参）；token 29 项两点 2036/2036 只增不减且 ≥2034；词面门改动集 CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；新文件纯 LF + 末尾换行、无 BOM，改文件保持基线行尾。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS**（`OFFLINE_RC=0`），逐位 **52/50/173/206/159/80/26**（common 52 / gateway 50 / user 173（基线 170 +3）/ record 206（基线 196 +10）/ verify 159 / leaderboard 80 / mapmatch 26，每模块 Failures/Errors/Skipped 全 0）。log 零 任何 `Failures: [1-9]|Errors: [1-9]|Skipped: [1-9]` 行。
2. **新增/修改测试类隔离单跑**（D7 纪律，S0 强制）：每个承载新断言的测试类 `-Dtest=` 隔离单跑绿——`SportRecordServiceTest` **63 rc=0**（含 7 新 feed 测）、`RecordControllerTest` **25 rc=0**（含 3 新 feed 测）、`FriendServiceTest` **31 rc=0**（含 3 新 listFriendIds 测）。隔离单跑不依赖类序（各测试类内部在 @BeforeEach 沿仓内 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), 实体)` 惯用法注册列缓存，避免 D7 类同类序问题）。
3. **静态**：record-service（唯一改动且声明 checkstyle/spotbugs/pmd 的模块）`--static=record-service` rc=0，`0 Checkstyle violations`，SpotBugs Error size 0、PMD BUILD SUCCESS。`--static=user-service`/`--static=api` 非本仓声明插件模块——用通用 checkstyle 配置实测存在**基线违规**（见 §8 D3，非本课题新增、不属收口门）。
4. **web 本机四件套**：`pnpm test` rc=0（vitest，4 套 25 测：token 6 / gpx 5 / pace 6 / client 8）；TMP/TEMP/TMPDIR 重定向 `.trae/tmp` 防 esbuild access denied；`pnpm type-check` rc=0（vue-tsc --noEmit）；`pnpm build` 成功（3283 modules）；`typed-router.d.ts` 再生成 +13 行（含 /feed 路由），二次构建 numstat 恒 13/0 逐字节一致（idempotent）。
5. **契约门双态**：在途 `--open TASK-222 --baseline=84a6e2d` **实测 rc=0**（判据 A 两件套 + TASK-222 待办进行中放行；判据 B 一致）；收口无参 **rc=0**（见 §9）。
6. **token 29 项**：两点时点基线 84a6e2d = **2036**、工作树 post-C01 = **2036**（见 §6），只增不减且 ≥任务书 2034。改动集 16 文件逐项 `git grep -cF` 扫描 **ZERO_HIT**（真实凭证/受保护 token 字面量零入库）。
7. **词面门 CI-exact**：显式 ERE 口径（`git grep -n -I -iE -f <模式文件>`，`-E` 显式；模式经 UTF-8 无 BOM 字节级拷贝注入 .trae/tmp 以规避命令行中文）对 C-01 改动文件集（16 文件）**ZERO_HIT rc=1**；合成探针（gitignored 探针文件含禁用措辞）同模式 **HIT rc=0**（命中探针行），证明 `-i -E` 口径有效、无假阴性。C-02 后对含台账四文件的改动集复扫（见 §9）仍 ZERO_HIT。模式字面量与探针均落 gitignored `.trae/tmp`、不入库（沿 TASK-184 F1 教训，本报告不落正则字面量）。
8. **`git diff --check`** rc=0（工作树与暂存区双查；`LF will be replaced by CRLF` 为 `core.autocrlf=true` 信息性告警，非空白错误）。
9. **行尾**：两新文件（FeedItemDTO.java、feed.page.vue）字节级校验纯 LF（CR=0）+ 末尾换行（末字节 0A）+ 无 BOM；改文件保持基线行尾。
10. **只改清单全等**：`git diff --name-only 84a6e2d` + untracked 对照恰 §2 的 20 文件（C-01 16 + C-02 4）；越界面 diff 全空（G2）。
11. **凭证自检**：改动集为 Feign 契约/好友 ID 还原/feed 查询/前端卡片/台账文案，零真实凭证字面量、零受保护 token 字面量（16 文件逐项 `git grep -cF` 复测 SCAN_HIT=0）、零新正则字面量（沿 TASK-188 N1 教训，字面量不枚举入本报告与任何 tracked 新文档）。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和，rev-aware 基线以 `git grep -cF <token> 84a6e2d` 对基线提交计）：开工（84a6e2d 基线）= **2036**、post-C01（C-01 提交后工作树）= **2036**（只增不减，且 ≥ 任务书 2034 基线）。本课题改动集（16 实施文件 + 4 台账文件）全部内容零真实凭证 / 零受保护 token 字面量。**口径说明**：29 项权威清单由指导侧持有复测（沿 TASK-213/215/217/218/219/220/221 同口径）；本报告沿用 .trae/tmp/tokenlist_T219.txt（29 行）复算，不枚举字面量以避免全仓计数自增失准（沿 TASK-188 N1 教训）。

## 7. 实现要点

- **api UserApi.listFriendIds**：`@GetMapping("/friends/ids")` 单参 `@RequestParam userId` → `Result<List<Long>>`，**不分页**一次返回全部好友 ID（feed 依赖完整集合做 IN 过滤，分页截断会丢记录）。`UserApiFallback` 软降级 `Result.success(emptyList())`（fail-safe：feed 退化为仅本人，与 listUsersByIds/listFriends 空成功结果同口径）。昵称面**复用既有** `listUsersByIds`（已存在，无需新 briefs 端点或 UserBriefDTO）。
- **FeedItemDTO**：`extends RecordSummaryDTO`（继承 id/sportType/distance/status/visibility/createdAt）+ `authorUserId` + `authorNickname`，`@Data @EqualsAndHashCode(callSuper=true)`。
- **user-service**：`FriendService.listFriendIds(Long)` — null→空、双向还原（low 端取 high / high 端取 low）、`distinct()` 去重、非分页 `selectList`；`InternalFriendController GET /internal/friends/ids` 纯委托。
- **record-service**：`SportRecordService.pageFeed(caller, page, size)` — clamp 同 /my（page≥1,size 1..500）；caller=null → `visibility=PUBLIC` 全站；caller≠null → `user_id IN(self ∪ friendIds)` 且 `.and(eq(self).or().in(visibility,0,1))`、createdAt DESC；`listFeedFriendIds` catch 一切异常 → 空（仅本人）；`buildFeedItems` 当页 distinct author → `listUsersByIds` 批量昵称（异常/空 → null）、`fetchNicknames` 映射 id→nickname。`RecordController GET /api/records/feed` — auth on resolveUserId(request,null)（缺头 1001）、auth off caller=null（degrade）。
- **web**：client.ts `FeedItemDTO extends RecordSummaryDTO` + `getFeed`；`feed.page.vue` 卡片（authorName 回退、可见性徽标复用 index 页 tag 形态、sportTypeLabel/statusLabel/relativeTime、goTrack→record-detail、a-pagination）；App.vue 顶栏「动态」；typed-router.d.ts 随 build 再生成含 /feed（沿 TASK-213 先例提交生成物）；README 一句登记。
- **零依赖确认**：api/user/record/web 全零新 Maven/npm 依赖；零新表、零新列、零新索引（user_id 索引仍为 TASK-211 已知限制单列议题未解禁）；零 MQ/事件；既有读取面门（detail/points/verify-result/like）与 assertCanView 一行不改；`web/package.json`、`pnpm-lock`、`vite.config.ts` 未动。

## 8. 异常与偏差登记

- **D1（feed 卡片不显示时长）**：proposal S2 的 FeedItemDTO 契约 = RecordSummaryDTO 全字段 + authorUserId + authorNickname，**不含 duration**（时长在 SportRecord 实体上，但不在契约 DTO 内）。proposal R5 述及「时长摘要」但 DTO 无此字段。处理：/feed 卡片展示可用字段（类型/距离/状态/相对时间），**时长不展示**，据此如实登记（非设计变更，严格按 S2 DTO 契约实现，不擅自扩 DTO 暴露 duration——那会引入新的不必要的字段暴露）。
- **D2（token/词面门/契约/tokencheck 临时件）**：中文模式、29 项字面量、契约 log、tokencheck 脚本均经 gitignored `.trae/tmp` 注入（`Copy-Item`/`[IO.File]::WriteAllText` UTF-8 无 BOM 防编码二次转义），不入库、不落 tracked 文档；命令行参数零中文（token 经脚本文件读 29 行 + CR 剥离，不在命令行展开中文）。
- **D3（`--static=user-service`/`--static=api` 基线违规，非收口门）**：user-service 与 api 均**未声明** checkstyle/spotbugs/pmd 插件（仅 leaderboard-service、record-service 声明）。本课题对 api 与 user-service 做了合理的小改动（接口增方法、friend 还原、一个控制器端点 + 一个 DTO + 测试），但这两个模块的静态门不是本变更收口范围（沿 TASK-221 D3 同型：模块未声明插件、通用 checkstyle 配置测得基线违规集中在未触碰的既有代码）。**结论**：本课题静态门以唯一声明并强制的改动模块 **record-service** 为准且 rc=0 / 0 violations；api/user-service 未期满不记入本变更收口（如实登记，不假装绿）。所改 api/user-service 代码应力求符合已有模块相继风格（javadoc 齐全、final 参数、无裸 MagicNumber），本实现符合。
- **D4（offline 日志噪音）**：offline 模式 Nacos 连接失败/熔断 / grpc 探测等日志系既有测试环境噪音（服务未起），对七模块计数零影响，`Tests run` 汇总逐位如 §5.1。
- **D5（web build chunk 体积告警）**：`vite build`「chunks larger than 500 kB」为既有非阻断 advisory（入口 JS ~1.6MB 系基线量级，与本课题正交），build rc=0。
- **D6（recon 关键确认：昵称批量面复用既有、typed-router 生成物随路由提交）**：recon 确认 user-service 已存在批量用户概要面 `listUsersByIds`（InternalUserController GET /internal/users → `List<UserDTO>`，id+nickname），S1「有则复用」下**不复加** `/internal/users/briefs` 与 UserBriefDTO（S2 亦列「或复用既有返回型」）；`/feed` 新增路由使 `web/src/typed-router.d.ts` 再生成 +13 行，沿 TASK-213 先例（`7e4be36` 新增 record-detail 页时即在同笔提交生成物）随路由提交，非"手动改生成物"。
- **停止条件（§3）核对**：无一按字面触发——
  - §3.1 recon 与代码实况相符：无既有 feed 面、无昵称批量面语义冲突（复用既有 listUsersByIds）、分页钳制与 /my 一致（recon 后定稿 page≥1, size 1..500）；
  - §3.2 offline 任何模块测试数未下降，user/record 均净增，五模块逐位不变；
  - §3.3 degrade 与 fail-safe 口径沿先例：caller=null → PUBLIC-only 全站（assertCanView caller=null 放行同源）、listFriendIds 空表 fail-safe 与 listUsersByIds/listFriends 空成功结果/昵称占位符同口径，未冲突；
  - §3.4 未发现 feed 绕过 visibility 门或新读取面暴露——feed 是门的严格子集读面（R6 论证），陌生人被 user_id IN 排除；
  - §3.5 性能红旗未触发：listFriendIds 非分页 `selectList` 按 friendship 归一两端过滤，无全扫、单测无超时；`user_id` 索引按既有 TASK-211 单列议题登记、不在本课题解禁。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2036**（见 §6 口径，≥2034）；改动集（C-01 16 + C-02 4）token 扫描 ZERO_HIT。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT + 探针阳性对照 HIT（已留证）；`git diff --check` rc=0；工作树 `git status` 干净（`.trae/tmp` 临时件 gitignored，无 override）。
- `git diff --name-only 84a6e2d` + untracked 对照恰 **20 文件**（C-01 16 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程未跑（未 push、未建 PR）；外部门槛待指导侧推动推送后补记。
- **真实浏览器「/feed 页卡片渲染 + 分页 + 顶栏入口」交互未跑**（沿 TASK-213/220/221 口径登记未覆盖）：R5 验证由 type-check + build + vitest（getFeed 请求角断言）+ 代码审查承载；不得据此推断 feed 页在真实浏览器端到端可用。
- **真实跨服务联调未跑**（未起六服务 + auth.enabled=true 全栈）：`listFriendIds`/`listUsersByIds` 到 user-service 的真实 Feign 链路、feed 的真实 SQL 过滤在真实 MySQL 上的行为未覆盖；`--it` 未执行。门逻辑以单测 mock 承载（SportRecordServiceTest 打桩 userApi.listFriendIds/listUsersByIds；FriendServiceTest 打桩 FriendshipMapper）。
- **`--static=user-service`/`--static=api`** 因模块未声明三件套插件而存在基线违规，未把它写成通过（见 §8 D3）；本变更只以 record-service 静态为收口门。
- 未做陌生人/降级态在真实浏览器的视觉断言行（PUBLIC-only 全站时间线在 auth 关闭态的真实渲染未覆盖）。
- 本机环境为本地处置（D2 临时件），未入库，不影响对外交付物。
