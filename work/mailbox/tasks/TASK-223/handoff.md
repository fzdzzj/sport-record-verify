# TASK-223 handoff：记录删除/撤回与单事务级联清理（P5，FULL 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-223`（提案 `spec/changes/add-record-deletion/proposal.md`）。
- 分级：**FULL**（记录删除/撤回 + 单事务级联清理恰四表 + Redis best-effort 清理 + web 确认交互；独立 review 复核适用）。
- 开工基线：`08732b7`（TASK-223 派发笔，本地领先 origin 2 笔：5509813 TASK-222 封盘笔 + 08732b7 本课题派发笔，均随本课题批次推送，勿动）；开工前工作树干净。
- 离线基线（任务书 §6）：52/50/173/206/159/80/26（record=206；本次收口 record 达 217）。
- C-01 实施笔：`40ce0d3`（`feat(record): 记录删除与撤回及单事务级联清理（TASK-223）`，恰 11 文件，+388/−8）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/222 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI）待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 08732b7，恰 15 文件 = C-01 11 + C-02 4）

C-01（feat(record) 实施笔，恰 11 文件 = S4 白名单全等）：
- record-service/src/main/java/com/sportverify/record/mapper/RecordLikeMapper.java
- record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
- record-service/src/main/java/com/sportverify/record/controller/RecordController.java
- record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java
- record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
- web/src/api/client.ts
- web/src/pages/index.page.vue
- web/src/api/__tests__/client.test.ts
- web/README.md

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，恰 4 文件）：
- spec/changes/add-record-deletion/tasks.json
- work/mailbox/tasks/TASK-223/spec.md
- work/mailbox/tasks/TASK-223/handoff.md
- work/mailbox/PLAN.md

> 下列载体零触碰（G2 证据见 §5）：六服务白名单外 src/main、父/子 pom、.github/workflows/ci.yml、scripts/、docker-compose*、sql/（零 schema 零迁移变更——不加 deleted 列、不改状态机 CHECK）、web/package.json、web/vite.config.ts、web/src/typed-router.d.ts（零新路由）、api/user/verify/leaderboard/mapmatch 其余模块、既有读取面门（assertCanView / pagePoints / getVerifyResult / like 三端点一行不改）。词面门/探针/token/契约临时脚本均落 gitignored `.trae/tmp`、不入库。

## 3. 一句话结论

实现 `DELETE /api/records/{id}` 端点与单事务级联清理恰四表（主记录 `sport_record`、分片轨迹点 `track_point`、分片归档轨迹点 `track_point_archive`、点赞行 `record_like`），并在事务提交后（`afterCommit`）采用双层防御机制对 Redis 点赞计数及点赞成员集缓存进行 best-effort 清理（异常吞并留证，不阻断事务）。身份门与 `/my` 端点同构（auth 开启从 `X-User-Id` 头解析，缺头 1001；auth 关闭降级 `userId` 参数，缺失 400）；归属校验严格限制 caller == userId，越权拒绝 1002（HTTP 403）；记录不存在返回 3001（HTTP 404）。前端首页「我的记录」操作列新增 Popconfirm 二次确认弹窗与删除按钮，删除成功后刷新列表与连续打卡仪表盘。全流程零 schema 改造（不加 deleted 列/软删状态）、零状态机 CHECK 变动、零 MQ 补偿事件、零跨服务写；S1 侦察定稿榜单为自有聚合持久表，榜单贡献不实时回撤登记为 Dn 偏差与已知限制；既有读取面一行未改，删除后 404 为既有 `requireRecord` 自然语义。

## 4. 判别式红绿（R1-R6 / G1-G3 逐条）

- **R1（绿）归属门**：`SportRecordServiceTest.deleteRecord_nonOwner_throwsForbidden` 实证非 owner（caller=999L, record.userId=100L）抛 `BusinessException(FORBIDDEN, 1002)`；`deleteRecord_notFound_throwsNotFound` 实证记录不存在抛 `BusinessException(RECORD_NOT_FOUND, 3001)`；`RecordControllerTest.deleteRecord_authEnabled_success` 实证 owner caller=100L 成功返回 200；`deleteRecord_authEnabled_missingHeader_throwsUnauthorized` 实证缺头抛 1001；`deleteRecord_authDisabled_missingUserId_badRequest` 实证 auth 关闭缺参 400。
- **R2（绿）级联恰四表**：`SportRecordServiceTest.deleteRecord_success_cascadesAllFourTablesAndCleansCache` 实证单事务内按序恰好调用 4 表删除：`sportRecordMapper.delete(...)`（主记录）、`trackPointService.deleteByRecordId(100L, 1L)`（轨迹点携带 userId 路由物理分片）、`trackPointArchiveService.deleteByRecordId(100L, 1L)`（归档轨迹点携带 userId 路由物理分片）、`recordLikeMapper.deleteByRecordId(1L)`（点赞持久行），且方法标注 `@Transactional(rollbackFor = Exception.class)`。
- **R3（绿）Redis best-effort 清理**：`RecordLikeServiceTest.cleanupCache_deletesBothCountAndUsersKeys` 实证正常删除 `like:count:{id}` 与 `like:record:{id}:users` 两个 Redis 键；`cleanupCache_redisThrows_logsAndSwallowsException` 实证 Redis 抛出 `RedisConnectionException` 时内部捕获吞并、不抛出异常；`SportRecordServiceTest.deleteRecord_redisCleanupFailure_doesNotBreakDeletion` 实证在 `afterCommit` 回调中即便缓存清理抛出异常，双层防线仍吞并留证，数据库事务完全不受影响；对账路径天然容忍悬挂。
- **R4（绿）读取面 404 语义**：既有读取面（`requireRecord`、`assertCanView`、`pagePoints`、`getVerifyResult`、`like` 三端点、`feed`、`my`）源码零改动。记录被级联删除后，既有 `requireRecord` 查询返回 null 并抛出 `RECORD_NOT_FOUND(3001)`，自然向前端返回 404，属于既有读取面语义的预期自然体现，不涉及任何门逻辑弱化。
- **R5（绿）榜单传播留证**：S1 recon 定稿 `leaderboard-service` 为自有聚合持久表（`leaderboard_contribution` + `leaderboard_daily_summary` + Redis ZSet）。本课题严格遵守红线，不发 MQ 补偿事件，不做跨服务补偿写；已在 §8 登记 D1 偏差与已知限制「贡献不实时回撤，刷新窗口沿榜单既有口径」，S3 §4 停止条件未触发。
- **R6（绿）web 界面**：`client.ts` 导出 `delRecord` 封装；`index.page.vue` 最近记录列表操作列新增 `a-popconfirm` 确认弹窗与删除按钮，点击确认后调用 `delRecord`，并在成功后重载 `getMyRecords` 与 `getMyStreak` 刷新记录与连续打卡；`web/src/api/__tests__/client.test.ts` 增补测试验证请求路径；web 四件套（vitest 26 测、type-check 0 错误、build 成功、typed-router diff 0）全绿。真实浏览器点击未跑，登记未覆盖。
- **G1（绿）**：offline 全量 BUILD SUCCESS rc=0，七模块逐位 **52/50/173/217/159/80/26**——`record-service` 由基线 206 增加到 217（恰净增 11 测），其余六模块逐位完全不变，全 log 零 Failures/Errors/Skipped。
- **G2（绿）**：C-01 后 `git diff --name-only 08732b7` 恰白名单 11 文件；越界面（其余服务主码、pom、ci.yml、scripts、compose、sql、web/package.json、web/vite.config.ts、typed-router.d.ts）diff 全空。
- **G3（绿）**：`--static=record-service` rc=0（0 Checkstyle violations / SpotBugs Error size 0 / PMD BUILD SUCCESS）；web 四件套全绿；契约门双态 rc=0（在途 `--open TASK-223 --baseline=08732b7` + 收口无参）；token 29 项 2036 持平；词面门 CI-exact ZERO_HIT + 探针正控 HIT；`git diff --check` rc=0；改动文件保持基线行尾。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS**（`OFFLINE_RC=0`），七模块逐位 **52/50/173/217/159/80/26**（common 52 / gateway 50 / user 173 / record 217（基线 206 +11）/ verify 159 / leaderboard 80 / mapmatch 26，总用例数 757，每模块 Failures/Errors/Skipped 全 0）。
2. **新增/修改测试类隔离单跑**（D7 纪律，S0 强制）：每个承载新断言的测试类 `-Dtest=` 隔离单跑全绿——`RecordLikeServiceTest` **39 测 rc=0**（含 3 新清理测）、`RecordControllerTest` **29 测 rc=0**（含 4 新删除端点测）、`SportRecordServiceTest` **67 测 rc=0**（含 4 新级联删除与越权测）。测试类沿用 `TableInfoHelper.initTableInfo` 惯用法，无类序耦合。
3. **静态**：record-service（唯一改动模块）`--static=record-service` rc=0，`0 Checkstyle violations`，SpotBugs Error size 0、PMD BUILD SUCCESS。
4. **web 本机四件套**：`pnpm test` rc=0（vitest，4 套 26 测：token 6 / gpx 5 / pace 6 / client 9，净增 1 测）；TMP/TEMP 重定向 `.trae/tmp`；`pnpm type-check` rc=0（vue-tsc --noEmit）；`pnpm build` 成功（3283 modules）；`git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零新路由）。
5. **契约门双态**：在途 `--open TASK-223 --baseline=08732b7` **实测 rc=0**（判据 A 两件套 + TASK-223 待办放行；判据 B 一致）；收口无参 **rc=0**（见 §9）。
6. **token 29 项**：开工基线 08732b7 = **2036**、工作树 post-C01 = **2036**，持平且 ≥2034。改动集 11 文件逐项扫描 `SCAN_HIT=0`（ZERO_HIT）。
7. **词面门 CI-exact**：显式 ERE 口径（`git grep -n -I -iE -f <模式文件>`）对 C-01 改动集 **ZERO_HIT rc=1**；合成探针同模式 **HIT rc=0**，无假阴性。模式字面量与探针均落 gitignored `.trae/tmp`、不入库。
8. **`git diff --check`**：rc=0（工作树与暂存区双查 clean）。
9. **行尾**：改动文件保持基线行尾，无新增未跟踪文件。
10. **只改清单全等**：`git diff --name-only 08732b7` 对照恰 §2 的 15 文件（C-01 11 + C-02 4）；越界面 diff 全空（G2）。
11. **凭证自检**：改动集为记录删除、单事务级联、Redis 清理、前端按钮与台账文案，零真实凭证字面量、零受保护 token 字面量、零新正则字面量。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和）：开工（08732b7 基线）= **2036**、post-C01（C-01 提交后工作树）= **2036**（只增不减，且 ≥ 任务书 2034 基线）。本课题改动集（11 实施文件 + 4 台账文件）全部内容零真实凭证 / 零受保护 token 字面量。**口径说明**：沿用 `.trae/tmp/tokenlist_T219.txt`（29 行）复算，不枚举字面量以避免全仓计数自增失准（沿 TASK-188 N1 教训）。

## 7. 实现要点

- **RecordLikeMapper**：新增 `@Delete("DELETE FROM record_like WHERE record_id = #{recordId}") int deleteByRecordId(@Param("recordId") Long recordId);`。
- **RecordLikeService**：新增 `cleanupCache(Long recordId)`，以 `stringRedisTemplate.delete(List.of(COUNT_KEY, USERS_KEY))` best-effort 清理缓存，内部 `try-catch` 捕获异常留证不抛出。
- **SportRecordService**：
  - 构造函数与注入：保留核心 7 参构造，新增 `RecordLikeMapper` 与 `RecordLikeService` 通过 `@Autowired` setter 注入，遵守 Checkstyle `ParameterNumber` 最多 8 参家法，杜绝构造器膨胀与循环依赖。
  - 删除事务：`@Transactional(rollbackFor = Exception.class) public void deleteRecord(Long caller, Long recordId)`；校验记录存在性（不存在 3001）与归属人身份（caller == userId，否则 1002）；单事务内按序删除主记录、分片轨迹点（带 userId）、分片归档轨迹点（带 userId）、点赞持久行。
  - 缓存清理：注册 `TransactionSynchronization.afterCommit`，并在回调内双层 `try-catch` 调用 `recordLikeService.cleanupCache(recordId)`，确保 Redis 抖动绝不影响事务成功。
- **RecordController**：新增 `@DeleteMapping("/{id}")`，auth on 解析 `X-User-Id` 头（缺头 1001），auth off 降级 `userId` 参数（缺失 400），统一调用 `sportRecordService.deleteRecord`。
- **web**：`client.ts` 导出 `delRecord`；`index.page.vue` 我的记录操作列增加 `a-popconfirm` 确认弹窗与删除按钮，删除成功后刷新记录列表与打卡统计。

## 8. 异常与偏差登记

- **D1（榜单数据源定稿为自有聚合持久表，贡献不实时回撤）**：S1 侦察确认 `leaderboard-service` 采用自有持久表（`leaderboard_contribution` + `leaderboard_daily_summary` + Redis ZSet）。按照预注册设计与红线要求，不引入 MQ 补偿事件，不做跨服务写路径。榜单贡献在记录删除后不实时回撤，刷新窗口沿榜单既有口径，作为已知限制与偏差如实登记。
- **D2（Checkstyle ParameterNumber 家法下采用 setter 注入）**：Checkstyle 限制构造方法参数最多 8 个。`SportRecordService` 原有 7 个构造参数，若追加 2 个依赖将达到 9 个触发静态检查报错。采用与 `RecordLikeService` 对称的 `@Autowired` setter 注入，既满足 Checkstyle 规约，又彻底杜绝了构造器循环依赖风险，平滑兼容历史单测代码。
- **D3（token/词面门/契约/wordcheck 临时件落 `.trae/tmp`）**：临时脚本与模式文件均注入 gitignored `.trae/tmp`，不入库，命令行参数保持零中文。
- **D4（offline 日志噪音）**：offline 模式下 Nacos 连接未就绪告警为既有测试环境噪音，对 7 模块计数零影响。
- **D5（web build chunk 体积告警）**：`vite build` 提示 chunk > 500 kB 为既有非阻断 advisory，构建 rc=0。
- **停止条件（§3）核对**：无一按字面触发——
  - §3.1 侦察与实况相符：榜单为自有聚合表、点赞键形态一致、归档双表结构分片路由确认；
  - §3.2 offline 任何模块未下降，`record-service` 净增 11 测，其余六模块逐位不变；
  - §3.3 删除语义与既有乐观锁/归档分支/对账机制无冲突，无需改动既有行为；
  - §3.4 未引入跨服务补偿写路径，正确性严格闭环在预注册范围内；
  - §3.5 级联物理删除通过携带 `user_id` 定位单分片，无广播扫描，单测执行迅速（<1s），无性能红旗。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2036**，≥2034 基线；改动集 15 文件 token 扫描 ZERO_HIT。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；工作树 `git status` 干净。
- `git diff --name-only 08732b7` 对照恰 **15 文件**（C-01 11 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程未跑（未 push、未建 PR）；外部门槛待指导侧推动推送后补记。
- **真实浏览器「我的记录行内删除 + 二次确认弹窗 + 列表与打卡刷新」交互未跑**（沿 TASK-213/220/221/222 口径登记未覆盖）：验证由 vitest + type-check + build + 代码审查承载；不得据此推断在真实浏览器端到端交互可用。
- **真实跨服务与中间件联调未跑**（未起六服务 + auth.enabled=true 全栈）：真实 MySQL 分片删除性能与真实 Redis 键清除未在端到端环境执行；`--it` 未执行。删除与容错逻辑以单测 Mockito 验证承载。
- 未做陌生人越权在真实网关的集成测试断言（以 Controller 单元测试与既有鉴权过滤器规约承载）。
