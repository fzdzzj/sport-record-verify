# TASK-261 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-17 执行侧 G3+G4+G10 三项全落、七门禁实测回填 §3；独立复核五块与指导侧终裁待做）

## 1. 开工基线

- 开工基线：main @ `3e9b4db29a0a1e68ffe4166043e89585a9ac16a9`（TASK-261 立项笔；`git rev-parse HEAD` 实测逐位一致，开工时 `git status --porcelain` 实测为空）
- 领先笔数实测：`origin/main..HEAD` = **1 笔**（仅立项笔 `3e9b4db`）——派发书「本地领先 2 笔（1dda602 封盘笔 + 3e9b4db 立项笔）」口径已过时：封盘笔 `1dda602` 已随前批次推送至 origin，实测 `git rev-list --count origin/main..HEAD` = 1。禁推送约束不变，本笔零推送（推送由指导侧批次统一执行）。
- 任务书：`work/mailbox/tasks/TASK-261/spec.md`（白名单/修法/门禁/受限声明以 spec 为准）

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 3e9b4db` 双向全等）

- 改动清单（12 项 = C-01 业务/测试 10 + C-02 台账 2，逐行路径 token 供判据 B 机器比对）：
  - record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java
  - record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
  - record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java
  - record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
  - user-service/src/main/java/com/sportverify/user/service/NotificationService.java
  - user-service/src/main/java/com/sportverify/user/service/FriendService.java
  - user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java
  - user-service/src/test/java/com/sportverify/user/service/NotificationServiceTest.java
  - verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java
  - verify-service/src/test/java/com/sportverify/verify/service/VerifyServiceTest.java
  - work/mailbox/tasks/TASK-261/spec.md
  - work/mailbox/tasks/TASK-261/handoff.md
- 未跟踪文件核验（C-01 前 `git ls-files -o --exclude-standard`）：恰 1 项 = 本回传 handoff 文件本体（白名单台账项、C-02 入库；全路径即上方清单末行）；业务/测试 10 项全为既有文件原地修改零新建；`.trae/tmp` 临时件属 gitignore 不纳入比对。
- spec §2 白名单双向全等自证：白名单 9 项业务落点逐项落改（第 7 项 user 测试落点执行侧选定 = 上方清单第 7、8 行两文件，第 9 项 verify 测试落点 = 上方清单第 10 行，均按既有测试布局同类就近，C-01 前登记于此）；台账 5 项中 spec/handoff 由本笔 C-02 回填，PLAN/BACKLOG/HANDOVER 本笔零动（沿 TASK-259/260 先例由指导侧封盘笔滚动）；白名单外全仓零 diff（api 模块、全部控制器层 Like/Record/Notification/Friend/Verify/InternalFriend、gateway/leaderboard/mapmatch 服务 src/test、全部 pom.xml、package.json、sql/、scripts/、web/、docker-compose*.yml、.github/ 均零触碰）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（经 D:\git\Git\usr\bin\bash.exe --login） | 0 | BUILD SUCCESS（08:32 min，2026-10-17 12:59 终树第三跑；前两跑 08:12/08:50 同读数）；surefire **63/78/218/318/202/109/35**（common/gateway/user/record/verify/leaderboard/mapmatch）——基线 63/78/215/310/200/109/35 只增不减：user 215→218 恰 +3（R2b/R2c/R2d 钳制断言）、record 310→318 恰 +8（R1 批量门六测 + R2a 钳制一测 + R1 DB 单查一测）、verify 200→202 恰 +2（R3 LIMIT 判别式 + 行映射测），零删减零跳过；mapmatch 35 持平（本笔零 mapmatch 文件，照跑） |
| 2 | 契约门 | `mailbox-contract.sh --open=TASK-261 --baseline=3e9b4db` | 0 | 三时点实测：C-01 前工作树态 rc=0（判据 B 恰 12 路径双向全等，2026-10-17 12:20）；C-01 后（`93312b9`，台账 2 文件在途）**首跑 rc=1**——「未跟踪文件核验」行内裸 `handoff.md` 被提取为认领 token 判清单多报（extract_claims 对改动清单节内全部列表行提取 path.ext token，非路径叙述行亦不豁免），改写该行与自证行为无 token 措辞后复跑 rc=0（登记见 §4）；C-02 后终态复跑 rc=0（判据 B 12 路径双向全等） |
| 3 | token 门 29 项 | `git grep -cF` 逐项（清单沿 TASK-181 handoff §6，全仓匹配行累加口径沿 TASK-257） | 0 | 基线 3e9b4db = 工作树（终树复测 2026-10-17 12:58）：SUM **2046 = 2046**，29 项逐位持平零增减；改动集 10 业务文件联合查询零命中（rc=1 通过态）。逐位明细（基线=工作树）：13.4=155、18.0=171、73.93=112、68.8=107、6315=87、1.8612=86、3.3066=86、5.7056=92、9.408=77、36525962432=70、36586847965=67、36438897772=69、36399582548=67、36098038547=65、2806=107、598=104、36736221648=64、36808102571=52、36821040708=46、36845152965=43、36871294588=41、36880083885=43、36958994260=42、36976873215=37、36992632143=34、36995450125=32、37008317295=31、37021305016=30、37591580687=29（较 TASK-260 表各 +1 = 其 C-02 台账 token 表入库效应，非本笔新增） |
| 4 | 词面门（模式文件法 + 探针阳性对照） | `git grep -n -I -iE -f <模式文件>`（CI 权威口径：8 禁词形态 + 三排除路径） | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1（终树复测）；模式文件 od 实测 LF 无 \r；阳性探针（仓根 probe261.txt 非命令行中文）**含三排除路径** `--untracked` rc=0 恰 1 行命中后即删、复跑 rc=1 状态复原——词面机器活性坐实（首轮探针漏排三路径出现 ci.yml 自匹配与 archive 恒命中共 3 行，修正口径后复测，如实登记） |
| 5 | checkstyle delta=0 | `mvn-verify.sh --static=<模块>` + 纯净 worktree（.trae/tmp/task261-baseline @ 3e9b4db）双向对照 | 1（存量 rot 形态） | 三服务主类均有 diff 故双向实测：record 工作树 **4** 违规 = 基线 **4**（JavadocType DerivedTrackMetrics 同集合，L1059→L1087 行位移 +28 = TASK-260 笔 +17 与本笔 import/钳制/常量插入量）；verify 工作树 **976** = 基线 **976** 逐位持平（新增行违规交集为空；唯一落我行的 JavadocStyle 系原 listPendingAppeals javadoc 既有违规同 javadoc 行位移，类计数持平）；user 工作树 **1172** vs 基线 **1175** = **净减 3**（新增行违规交集为空；三处 selectPage 既有长行因 safePage/safeSize 形参加长超限，换行收敛后违规消失，属顺手收敛）。三模块 checkstyle failOnViolation 于第 1 goal 中止故 spotbugs/pmd 未及（沿 TASK-254/255/259/260 record 静态门同形态，停手条件核验非七门禁） |
| 6 | diff 净与行尾 | `git diff --check` / `git ls-files --eol` | 0 | diff --check 净（rc=0，实测两轮）；10 个修改文件工作树全保持既有行尾 w/crlf、blobs 恒 i/lf（`git ls-files --eol` 逐文件实测；NotificationService 两文件曾于编辑中被整文件转 LF，perl raw-IO 归一回 CRLF 后终态 w/crlf，见 §4）；零新建文件（新文件 LF 判据本笔不适用） |
| 7 | mapmatch 照跑 | offline 全量内含 | 0 | mapmatch **35 持平**（本笔零 mapmatch 文件；BUILD SUCCESS 内含） |

## 4. 条件项与非阻断登记（如实回填）

- **R1 批量承载形式登记**：授权批量路径以 `assertCanView(List<SportRecord>, Long)` 公共**重载**承载（spec「新增私有方法或重载」的重载口径），好友集懒拉以私有 `loadFriendIdSet(Long)` 承载。选重载而非纯私有方法的原因：SportRecordService 含 `@Transactional` 方法，容器内注入到 RecordLikeService 的引用是 CGLIB 代理实例——非 public 方法经代理调用的委派语义存在歧义风险（若代理未覆写而以代理实例空字段执行，`userApi` 为 null 会被 fail-safe 吞掉，FRIENDS 记录在生产静默剔除）；public 重载消除该面。硬约束逐字满足：单条 `assertCanView(SportRecord, Long)` 公共签名与门矩阵语义零改动，pagePoints/getVerifyResult/like/unlike/getLike 五个既有调用点零改动（批路径独立承载）。
- **R1 语义等价登记（存量行边界）**：归属人 userId=null × FRIENDS 可见性的存量行——单条门走 `areFriends(null, caller)` → FriendService null 入参返回 false → 403 跳过；批量重载走 `contains(null)` = false → 剔除，语义等价。门矩阵四分支顺序（caller null 放行 → 归属人放行 → PUBLIC/null 可见性放行 → PRIVATE 剔除 → FRIENDS contains 判定）与单条门逐字保持。
- **R1 spec 新增断言落位**：批内 DB 单查（`getLikesBatch_singleBatchedDbQuery_noPerIdSelect`：selectBatchIds 恰一次 + selectById 零调用）；好友集单拉（`assertCanViewBatch_friends_listFriendIdsOnce_areFriendsNever`：listFriendIds 恰一次 + areFriends 零调用）；降级空列表 FRIENDS 全跳过（`assertCanViewBatch_degradeEmptyFriendSet_friendsAllSkipped`，另含抛错同语义测）；懒拉触发条件（PUBLIC-only 批 `verifyNoInteractions(userApi)` + 混批整批恰一次）；六测落 SportRecordServiceTest、一测落 RecordLikeServiceTest，共 +8 与 surefire record 310→318 对账一致。
- **R1 mock 适配属向演进登记**：RecordLikeServiceTest 既有 getLikesBatch 用例实测 **9 个**（派发书口径「8 个」对应 spec §1 引 L722-816 区段；区段实含 E12 两测共 9 方法）全部适配批量形态零删弱——selectById 打桩换 selectBatchIds（入参为去重后精确列表，等价承载「去重发生在批量加载之前」判据）、批量门打桩为过滤/透传（等价原 doThrow 403 形态，断言语义保持）；`getLikesBatch_over51RawInput_rejected` 增补 selectBatchIds 零调用断言。
- **R2 常量与写法登记**：record `MAX_POINTS_PAGE_SIZE=500`（注释引 B4 前端轨迹图 500 点/页消费口径）、user `MAX_NOTIFICATION_PAGE_SIZE=500` 与 `MAX_FRIEND_PAGE_SIZE=500`（各自模块独立声明，值 500 对齐 MAX_MY_RECORDS_SIZE 范式）；四处均为服务层双钳制 `safePage=Math.max(page,1L); safeSize=Math.min(Math.max(size,1L),MAX)`，控制器层零改动（InternalFriendController 内部端点同走 listFriends 服务层钳制天然收敛）。
- **R3 掩写登记**：listPendingAppeals Javadoc 订正移除的字符串在台账内以掩写形「所有?PENDING」登记；零残留实测：verify-service **service 面** rc=1 零残留（被订正 Javadoc 新文本不逐字引用旧串）；`VerifyController.java:48` 仍有同措辞——控制器层属零触碰白名单（既有先例措辞，本笔不动）；台账侧 spec 立项证据链原文（3e9b4db 已入库）与 TASK-142 历史台账各存引用，均非本笔新增。逐字串仅存本笔 C-01 commit body。
- **checkstyle 收敛技法登记（delta=0 保障）**：user/verify 两模块在 sun_checks 下存量违规面大（1175/976），本笔新增行以「违规交集为零」判据收口——常量 javadoc 采用多行形并首句以 ASCII 句点收尾（绕开 JavadocStyle 首句判据）、超长 selectPage/safeSize 行换行（顺带收敛存量 3 条：user 净 −3）；verify listPendingAppeals javadoc 首句仍无 ASCII 句点系既有违规行位移（类计数持平 976=976）。纯净 worktree 判据由 `.trae/tmp/task261-baseline` 双向坐实，用后 `git worktree remove`。
- **EOL 中途归一登记**：NotificationService.java 与 NotificationServiceTest.java 编辑中被编辑器整文件转 LF（其余 8 文件保持 CRLF），以 perl raw-IO 归一回 CRLF——字节级行尾归一、内容零变化，committed blob 恒为 i/lf 与 CI 编译物逐位一致；offline 门禁第一跑（08:12 读数）曾横跨该归一时点，为保读数纯净在最终树复跑两轮（08:50 / 08:32 同读数），终读数取 08:32 轮。
- **契约门判据 B 提取陷阱登记（非阻断，先例补遗）**：extract_claims 对「改动清单」节内**全部列表行**（不限路径行）提取 path.ext token——叙述行内出现裸文件名（如核验行写「本 handoff.md」）会被当作认领判「清单多报」；含「零触碰/未碰/不改/禁触」句式的行整行豁免。本笔 C-01 后复跑曾因此 rc=1 一次，已按无 token 措辞改写并复跑 rc=0；后续任务写 handoff 的改动清单节时，非路径叙述行应避免出现任何 path.ext 形态串。
- **受限声明沿 spec §5 五项**：G3 收益为静态推论（mock 语义断言承载，沿 B5/D7 先例）；好友集懒拉时机等价性由单测承载；G4 上限 500 为范式对齐值非压测最优值；R3 语义收窄为防御性（正常待审规模远小于 200）；Nacos 运行时配置未核（沿 G 系口径）。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；`.trae/tmp` 临时件（offline 三轮日志 / static 五读数日志 / token 清单与读数 / 词面模式与掩写模式 / sigdiff·violpath·violline awk 脚本 / msg261 两笔 msg / 基线纯净 worktree `git worktree remove` / 探针 probe261.txt 已即删）收口清理；基线纯净 worktree 已 remove。
- 未推送：`origin/main..HEAD` 收口后 = 3 笔（`1dda602` 前批封盘笔已在 origin、本笔 C-01/C-02 + 立项笔 `3e9b4db` 未推）——推送由指导侧批次统一执行，本笔零推送。
- spec/handoff 状态行已回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 12 路径双向全等）。
