# TASK-261 立项任务书（批 G-3：跨服务与查询边界 G3+G4+G10）

> 状态：**已立项待派发**（2026-10-17 用户拍板批 G-3；指导侧亲验三项全部坐实，修法裁决=G3 复用既有 listFriendIds 契约零新增端点）

## 1. 证据链（指导侧亲验坐实，2026-10-17，TASK-260 合并后实测行号）

1. **G3 批内 N+1 双落点**：RecordLikeService L358-372 `getLikesBatch` 循环内逐条 `sportRecordMapper.selectById(id)`（L362）+ `sportRecordService.assertCanView(record, userId)`（L366）——上限 LIKES_BATCH_MAX_SIZE=50（L187，E12 已设）内最坏 50 次串行 DB RTT；assertCanView（SportRecordService L1166-1193）FRIENDS 分支 L1184-1185 逐条 `userApi.areFriends(...)` Feign——批内最坏 50 次串行 Feign RTT（feed 单页 20 条常态 20+20）。调用链 LikeController L88-92 `POST /likes/batch`（B7 前端 feed 点赞态批量下推消费方）；既有测试基线 RecordLikeServiceTest L722-816 恰 8 个 getLikesBatch 用例（含 filtersInaccessible/allInaccessible/cacheMiss/dedupPreservesOrder/userIdNull）；
2. **G3 修法前提（零新增契约）**：`UserApi.listFriendIds` 契约已存在（api/user/UserApi.java L98，UserApiFallback L73-77 降级空列表）；feed 路径先例 SportRecordService L789-794 `listFeedFriendIds(caller)` 一次拉齐好友集（TASK-222）——批量可见性判断可复用既有契约，禁新增 api 契约与内部端点；
3. **G4 四处 size 直通**：RecordController L253-257 `/points`（defaultValue 1/20）→ SportRecordService L531 `pagePoints` 直通 `new Page<>(page, size)`（L542 归档分支与热分支均直通——可传 10 万一页拉全轨迹，ShardingSphere 内存合并放大）；NotificationService L112-121 `pageNotifications` 直通；FriendService L343-353 `listPendingRequests`（L347）与 L428-437 `listFriends`（L432）直通（内部端点 InternalFriendController L54-57 同走 listFriends，服务层钳制天然收敛）；范式先例 MAX_MY_RECORDS_SIZE=500（SportRecordService L1001）+ 双钳制写法 L724-725/L779-780 `safePage=Math.max(page,1L); safeSize=Math.min(Math.max(size,1L),MAX)`；
4. **G10 无界全量**：VerifyService L230-235 `listPendingAppeals` selectList 无 limit（B15 语义「所有 PENDING」）；同文件控制器先例 EXHAUSTED_LIMIT_MAX=200 / EXHAUSTED_LIMIT_DEFAULT=50（VerifyController L38-42，B20 防手滑整表倾倒）——防御性钳制值域可循。

## 2. 改动白名单（仅限下列文件，双向全等）

1. `record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java`（R1 批量重构）
2. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（R1 assertCanView 批量承载 + R2a pagePoints 钳制）
3. `record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java`（R1 测试适配与新增）
4. `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`（R2a 钳制测试，允许并入）
5. `user-service/src/main/java/com/sportverify/user/service/NotificationService.java`（R2b 钳制）
6. `user-service/src/main/java/com/sportverify/user/service/FriendService.java`（R2c/d 两处钳制）
7. `user-service/src/test/java/...`（R2b/c/d 钳制测试，文件由执行侧按既有测试布局选定并在 C-01 前登记增补）
8. `verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java`（R3 LIMIT）
9. `verify-service/src/test/java/...`（R3 测试落点，同上执行侧选定登记）
10. 台账：`work/mailbox/PLAN.md`、`work/mailbox/BACKLOG.md`、`work/mailbox/HANDOVER.md`、`work/mailbox/tasks/TASK-261/spec.md`、`work/mailbox/tasks/TASK-261/handoff.md`

零触碰：api 模块（G3 修法前提即零新增契约）、全部控制器层（LikeController/RecordController/NotificationController/FriendController/VerifyController/InternalFriendController——钳制落服务层沿范式先例）、gateway/leaderboard/mapmatch 服务 src/test、全部 pom.xml、package.json、sql/、scripts/、web/、docker-compose*.yml、.github/、状态机/MQ/批量/索引参数。

## 3. 修法要求

- **R1（G3 主项）**：
  - DB 批查：`selectBatchIds(distinctIds)` 单次批量替代循环逐条 `selectById`（FriendService L360/L444 selectBatchIds 先例）；authorized 仍按 distinctIds 去重保序（LikeController L85-86 契约「返回顺序与去重后的可见记录顺序一致」逐字保持）；
  - Feign 批判：好友集懒拉取一次——仅当 caller 非空且批内存在「非归属人 + FRIENDS 可见性」记录时 `userApi.listFriendIds(caller)` 一次（PUBLIC-only 批零额外 Feign）；FRIENDS 判定改好友集 contains 内存判断；
  - assertCanView 既有公共签名与门矩阵语义不变（pagePoints L539/getVerifyResult/点赞单条等既有调用点零改动）；批量路径以新增私有方法或重载承载，门矩阵四分支（caller null 放行/归属人放行/PUBLIC·null 放行/PRIVATE 403）语义逐字保持，仅 FRIENDS 分支数据源从逐条 Feign 换为好友集；
  - fail-safe 对齐：listFriendIds 降级空列表 → FRIENDS 记录按非好友跳过（与 areFriends fallback false 同语义，UserApiFallback 先例）；
  - 测试：既有 8 用例全绿（mock 适配 selectBatchIds 属向演进同步非删弱，断言语义保持）；新增断言 ≥3：批内 DB 单查（selectById 零调用/selectBatchIds 恰一次）、好友集单拉（areFriends 零调用/listFriendIds 恰一次）、降级空列表 FRIENDS 全跳过；
- **R2（G4 四处服务层双钳制）**：沿 L724-725 先例写法 `safePage=Math.max(page,1L); safeSize=Math.min(Math.max(size,1L),MAX)`——R2a pagePoints（新常量 MAX_POINTS_PAGE_SIZE=500，注释引 B4 前端 500 点/页消费口径）、R2b pageNotifications、R2c listPendingRequests、R2d listFriends（user-service 各自模块常量，值 500 对齐范式）；控制器 defaultValue 零改动；每处至少 1 条钳制断言（size 超限收敛到 500 / page<1 收敛到 1）；
- **R3（G10）**：listPendingAppeals 加 `LIMIT 200`（常量 PENDING_APPEALS_LIMIT_MAX=200 沿 EXHAUSTED_LIMIT_MAX 值域；wrapper.last 或等价 MyBatis-Plus 形态，常量硬编码无注入面），orderByDesc(id) 截断保最新；Javadoc「所有 PENDING」订正为「最新至多 200 条（id DESC 截断）」并登记 B15 语义收窄；测试断言 SQL 末段 LIMIT 形态与截断保最新语义。

## 4. 门禁与验证要求（FULL 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（经 `D:\git\Git\usr\bin\bash.exe --login`），跨 record/user/verify 三模块 → 七模块全量逐位验证，surefire 基线 **63/78/215/310/200/109/35**（TASK-260 新基线）只增不减；
- 契约门（`--open=TASK-261 --baseline=<开工基线>`，handoff §2 逐行列全改动集）、token 门 29 项只增不减、词面门模式文件法+探针阳性对照、checkstyle delta=0（本笔三服务主类均有 diff，静态门以纯净 worktree 双向坐实）、`git diff --check` 净；
- 双提交 C-01/C-02；`git status --porcelain` 收口为空。

## 5. 受限声明（预登记）

1. G3 收益（N 次串行 RTT → 常数次）为静态推论，无真实压测——mock 语义断言承载（沿 B5/D7 先例）；
2. 好友集懒拉取时机（仅批内存在 FRIENDS 记录才拉）的等价性由单测承载，无线上观测；
3. G4 上限 500 为范式对齐值非压测最优值；
4. G10「所有 PENDING」→「最新 200 条」为防御性语义收窄，管理后台正常待审规模远小于 200，超限积压本身属异常态；
5. Nacos 运行时配置未核（沿 G 系口径）。

## 6. 验收判据

C-01+C-02 落库、七门禁全绿、白名单双向全等、R1 批量断言（selectBatchIds 单查 + listFriendIds 单拉 + areFriends 零调用 + 降级语义）+ R2 四处钳制断言 + R3 LIMIT 断言全绿；独立复核五块 PASS 后指导侧终裁；CI 外部终验读数由补记笔登记（随下一批次推送）。
