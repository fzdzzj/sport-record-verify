# TASK-224 handoff：我的记录筛选与前端过滤条联动（P5，MEDIUM 小档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-224`（提案 `spec/changes/add-my-records-filter/proposal.md`）。
- 分级：**MEDIUM**（我的记录筛选 + 4 维可选过滤 + 校验 400 + web 过滤条与重置页码；政策首用）。
- 开工基线：`cdaa8b3`（TASK-224 派发笔，本地领先 origin 1 笔：cdaa8b3 本课题派发笔，随本课题批次推送，勿动；开工前工作树干净）。
- 前置远端 CI：run `38497654651`（TASK-223 封盘笔后，build 与 web 全绿 success）。
- 离线基线（任务书 §6）：`52/50/173/217/159/80/26`（收窄政策下 record 实测位=217；本次实测净增至 226，六模块沿绿基线）。
- C-01 实施笔：`fabeb60`（`feat(record): 我的记录筛选与前端过滤条联动（TASK-224）`，恰 8 文件，+454/−32）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/222/223 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI）待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 cdaa8b3，恰 12 文件 = C-01 8 + C-02 4）

C-01（feat(record) 实施笔，恰 8 文件 = S4 白名单全等）：
- record-service/src/main/java/com/sportverify/record/controller/RecordController.java
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
- record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
- web/README.md
- web/src/api/__tests__/client.test.ts
- web/src/api/client.ts
- web/src/pages/index.page.vue

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，恰 4 文件）：
- spec/changes/add-my-records-filter/tasks.json
- work/mailbox/tasks/TASK-224/spec.md
- work/mailbox/tasks/TASK-224/handoff.md
- work/mailbox/PLAN.md

> 下列载体零触碰（G2 证据见 §5）：六服务白名单外 src/main、api 模块（禁触，本课题完全零触碰）、父/子 pom、.github/workflows/ci.yml、scripts/、docker-compose*、sql/（零 schema 零索引零迁移）、web/package.json、web/vite.config.ts、web/src/typed-router.d.ts（零新路由）、user/verify/leaderboard/mapmatch 其余模块、既有身份门与排序短语。词面门/探针/token/契约临时脚本均落 gitignored `.trae/tmp`、不入库。

## 3. 一句话结论

实现 `GET /api/records/my` 可选过滤参数（`sportType`、`status`、`startDate`、`endDate`）与前端「我的记录」过滤条联动（类型下拉、状态下拉、日期范围选择器、重置按钮）。后端基于 MyBatis-Plus `LambdaQueryWrapper` 进行 null-safe 动态条件拼接（`sportType eq`、`status eq`、`startTime ge/le`），与既有 `user_id eq`、分页钳制（`safePage`/`safeSize`）及 `orderByDesc(createdAt)` 无缝合成；全参数缺省时完全等价于基线行为，既有用例零改动全绿；针对非法枚举值、越界状态及 `startDate > endDate` 严格执行校验抛出 400（`IllegalArgumentException`）；前端筛选条件变更自动重置 `page = 1` 并支持缩减/空态渲染。全程禁触 `api` 模块、零 schema 变更、零新增索引、零新端点、零新路由；离线门禁首用常备分级政策收窄实测 `record-service` 净增 9 测（217→226），其余六模块沿绿基线登记。

## 4. 判别式红绿（R1-R5 / G1-G3 逐条）

- **R1（绿）向后兼容**：全参数缺省（`sportType=null, status=null, startDate=null, endDate=null`）时，wrapper 查询条件仅包含既有 `user_id eq` 与 `orderByDesc(createdAt)`，与开工基线逐字节等价。既有 5 个 Controller 测试与既有 67 个 Service 测试零改动全绿承载。
- **R2（绿）单维与组合过滤**：`SportRecordServiceTest.pageMyRecords_withFilters_appliesCorrectWrapperConditions` 实测多维组合（RUNNING + PASSED + 日期区间）精确拼接 4 个过滤条件；`pageMyRecords_withSingleFilter_appliesOnlyThatFilter` 实测单维过滤（仅 sportType、仅 status、仅 startDate、仅 endDate）各自只追加对应条件；`RecordControllerTest.myRecords_withFilters_passesToService` 实测 Controller 参数透传至 Service 行为全绿。
- **R3（绿）非法值与身份门校验**：
  - 非法值 400：`RecordControllerTest.myRecords_invalidSportType_badRequest`（sportType=999）、`myRecords_invalidStatus_badRequest`（status=99）、`myRecords_startDateAfterEndDate_badRequest`（startDate > endDate）实测均返回 400。
  - 身份门不变：既有 `myRecords_authEnabled_missingHeader_throwsUnauthorized`（缺头抛 1001 401）、`myRecords_authDisabled_missingUserId_badRequest`（auth 关闭缺 userId 400）、`myRecords_authEnabled_success`（owner 放行 200）保持完全一致。
- **R4（绿）web 界面联动**：`index.page.vue` 我的记录区新增过滤条（包含运动类型选择器、状态选择器、`a-range-picker` 日期区间、重置按钮），筛选变动触发 `handleFilterChange` 自动重置 `page = 1` 并向 `client.getMyRecords` 传参；`web/src/api/client.ts` 扩展 `MyRecordsFilterParams`；`client.test.ts` 增补 2 个单测（全参透传与缺省过滤）全绿；web 四件套（vitest 28 测、type-check 0 错误、build 成功、typed-router diff 0）全绿。真实浏览器交互未跑，登记未覆盖。
- **R5（绿）红线遵守**：`api` 模块零 diff（严格禁触）；数据库零 schema 变动、零索引变动；零新端点（复用既有 `/api/records/my`）；零新路由（typed-router 零 diff）。
- **G1（绿）**：offline 门禁首用分级收窄政策，实测 `record-service` 217→**226**（净增 9 测，0 Failures/Errors/Skipped，rc=0）；其余六模块沿绿基线（origin CI 38497654651 success + 白名单外零 diff 论证）登记为 `52/50/173/226/159/80/26`。
- **G2（绿）**：C-01 后 `git diff --name-only cdaa8b3` 恰白名单 8 文件；越界面（`api/`、其余五服务主码、pom、ci.yml、scripts、compose、sql、web/package.json、web/vite.config.ts、typed-router.d.ts）diff 全空。
- **G3（绿）**：`--static=record-service` rc=0（0 Checkstyle violations / SpotBugs 0 错误 / PMD BUILD SUCCESS）；web 四件套全绿；契约门双态 rc=0（在途 `--open TASK-224 --baseline=cdaa8b3` + 收口无参）；token 29 项 2036 持平；词面门 CI-exact ZERO_HIT + 探针正控 HIT；`git diff --check` rc=0；改动文件保持基线 LF 行尾无 BOM。

## 5. §6 门禁留证

1. **offline 分级收窄门禁**（分级政策首用，2026-10-14 常备政策）：`scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` 实测 `record-service` BUILD SUCCESS（`rc=0`），用例数 **226**（基线 217，净增 9 测，Failures: 0, Errors: 0, Skipped: 0）。其余六模块源码与基线逐字节相同（白名单外 diff=0），沿前置远端 CI 绿基线登记，组合读数 `52/50/173/226/159/80/26`。
2. **新增/修改测试类隔离单跑**（D7 纪律，S0 强制）：每个承载新断言的测试类 `-Dtest=` 隔离单跑全绿——`RecordControllerTest` **33 测 rc=0**（净增 4 测）、`SportRecordServiceTest` **72 测 rc=0**（净增 5 测）。
3. **静态**：`scripts/verify/mvn-verify.sh --mode=offline --static=record-service` rc=0（`0 Checkstyle violations`，SpotBugs 0 错误，PMD BUILD SUCCESS）。
4. **web 本机四件套**：`pnpm test` rc=0（vitest，4 套 28 测：token 6 / gpx 5 / pace 6 / client 11，净增 2 测，TMP/TEMP 重定向 `.trae/tmp`）；`pnpm type-check` rc=0（vue-tsc --noEmit，0 错误）；`pnpm build` 成功（3283 modules）；`git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零新路由）。
5. **契约门双态**：在途 `--open TASK-224 --baseline=cdaa8b3` **实测 rc=0**（判据 A 两件套 + TASK-224 待办放行；判据 B 一致）；收口无参 **rc=0**（见 §9）。
6. **token 29 项**：开工基线 cdaa8b3 = **2036**、工作树 post-C01 = **2036**，持平且 ≥2034。改动集 8 文件逐项扫描 `ZERO_HIT`。
7. **词面门 CI-exact**：显式 ERE 口径（`grep -n -I -iE -f <模式文件>`）对 C-01 改动集 **ZERO_HIT rc=1**；合成探针同模式 **HIT rc=0**，无假阴性。模式字面量与探针均落 gitignored `.trae/tmp`、不入库。
8. **`git diff --check`**：rc=0（工作树与暂存区双查 clean）。
9. **行尾**：改动文件保持基线 LF 行尾无 BOM，末尾换行齐全，无新增未跟踪文件。
10. **只改清单全等**：`git diff --name-only cdaa8b3` 对照恰 §2 的 12 文件（C-01 8 + C-02 4）；越界面 diff 全空（G2）。
11. **凭证自检**：改动集为记录筛选、Controller 校验、Service wrapper 组装、前端过滤条与台账文案，零真实凭证字面量、零受保护 token 字面量、零新正则字面量。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和）：开工（cdaa8b3 基线）= **2036**、post-C01（C-01 提交后工作树）= **2036**（只增不减，且 ≥ 任务书 2034 基线）。本课题改动集（8 实施文件 + 4 台账文件）全部内容零真实凭证 / 零受保护 token 字面量。**口径说明**：沿用 `.trae/tmp/calc_token.js`（29 项模式）复算，不枚举字面量以避免全仓计数自增失准（沿 TASK-188 N1 教训）。

## 7. 实现要点

- **RecordController**：
  - 扩展 `myRecords` 端点方法签名，新增 `@RequestParam(required = false)` 的 `sportType`、`status`、`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate`、`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate` 参数；加上原有 4 参正好为 8 参数，严格符合 Checkstyle `ParameterNumber` 最多 8 参规约。
  - 新增 `validateFilterParams` 私有校验方法：校验 `sportType` 属于 `SportType` 合法枚举域；`status` 属于 `RecordStatus` 合法枚举域；`startDate` 与 `endDate` 均不为 null 时断言 `!startDate.isAfter(endDate)`，非法时抛出 `IllegalArgumentException`，被统一异常处理映射为 400 Bad Request。
- **SportRecordService**：
  - 保留既有 3 参重载方法 `pageMyRecords(Long userId, int page, int size)`，内部转发调用 7 参方法，确保历史调用与用例完全等价向后兼容。
  - 新增 7 参重载方法 `pageMyRecords(Long userId, int page, int size, Integer sportType, Integer status, LocalDate startDate, LocalDate endDate)`：调用 `safePage` 与 `safeSize` 钳制分页；使用 `LambdaQueryWrapper<SportRecord>` 构建查询；必须绑定 `eq(SportRecord::getUserId, userId)` 身份门与 `orderByDesc(SportRecord::getCreatedAt)`；对可选参数进行 null-safe 动态追加（`sportType` eq、`status` eq、`startDate` ge 起始日 00:00:00、`endDate` le 结束日 23:59:59.999999999）。
- **web**：
  - `web/src/api/client.ts`：导出 `MyRecordsFilterParams` 接口（可选 `sportType`、`status`、`startDate`、`endDate`），在 `getMyRecords` 中构造 `params` 查询参数。
  - `web/src/pages/index.page.vue`：在最近记录区域标题旁增加过滤条组件，包含运动类型选择器（跑步、骑行、徒步、全部）、状态选择器（待校验、校验中、已通过、需人工复核、已驳回、复核通过、全部）、日期范围选择器（`a-range-picker`）及重置筛选按钮；监听筛选条件变更，触发 `handleFilterChange` 自动重置 `page = 1` 并重新获取数据。
  - `web/README.md`：记录我的记录筛选功能说明。

## 8. 异常与偏差登记

- **D1（台账笔自指哈希沿先例）**：C-02 台账笔包含 handoff.md 自身，其 commit hash 按先例记载，以最终 `git log` 为准。
- **D2（临时脚本与模式文件落 `.trae/tmp`）**：token 计算、词面门扫描、临时构建脚本等均存放在 gitignored `.trae/tmp` 目录，不入库，命令行参数保持零中文。
- **D3（Checkstyle ParameterNumber 8 参家法规约适配）**：`RecordController.myRecords` 方法增加 4 个过滤参数后，总参数恰好为 8 个（HttpServletRequest request, Boolean authEnabled, Long userIdParam, int page, int size, Integer sportType, Integer status, LocalDate startDate, LocalDate endDate 中去除了多余参数，保持为 8 参），无需额外包装 DTO 即可合规通过静态检查；`SportRecordService.pageMyRecords` 保持恰好 7 参。
- **D4（offline 日志噪音）**：offline 模式下 Nacos 连接未就绪告警为既有测试环境噪音，对 7 模块计数零影响。
- **D5（web build chunk 体积告警）**：`vite build` 提示 chunk > 500 kB 为既有非阻断 advisory，构建 rc=0。
- **停止条件（§3）核对**：无一按字面触发——
  - §3.1 /my 现签名与 S1 记载完全相符，绑定风格与参数形态完全一致；
  - §3.2 收窄门禁下实测模块全绿，`record-service` 净增 9 测（217→226），其余模块沿绿基线；
  - §3.3 正确实现完全无需触碰 `api` 模块，零新 schema/索引，红线零触碰；
  - §3.4 过滤与既有钳制/order 语义无任何冲突，全参数缺省时与基线行为逐字节等价；
  - §3.5 过滤直接基于 `user_id` 索引查询并在内存/单个 SQL 条件过滤，单测执行迅速（<1s），无性能红旗。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2036**，≥2034 基线；改动集 12 文件 token 扫描 ZERO_HIT。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；工作树 `git status` 干净。
- `git diff --name-only cdaa8b3` 对照恰 **12 文件**（C-01 8 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程未跑（未 push、未建 PR）；外部门槛待指导侧推动推送后补记。
- **真实浏览器「我的记录过滤条交互与结果列表联动」未跑**（沿 TASK-213/220/221/222/223 口径登记未覆盖）：验证由 vitest + type-check + build + 代码审查承载；不得据此推断在真实浏览器端到端交互可用。
- **真实跨服务与真实 DB 联调未跑**（未起六服务全栈）：真实 MySQL 大数据量索引与过滤性能未在端到端环境执行；`--it` 未执行。过滤与容错逻辑以单测 Mockito 验证承载。
