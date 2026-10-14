# TASK-225 handoff：周月运动目标与前端目标卡片（P5，MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-225`（提案 `spec/changes/add-sport-goal/proposal.md`）。
- 分级：**MEDIUM**（周月运动目标 + sport_goal 幂等表 + CRUD + pull-on-read 进度 + 前端目标卡片联动）。
- 开工基线：`06d17f3`（TASK-225 派发笔，其下 77230c0 为 TASK-224 封盘笔；开工前工作树干净）。
- 前置远端 CI：run `38511006257`（TASK-224 封盘笔后，build 与 web 全绿 success）。
- 离线基线（任务书 §6）：`52/50/173/226/159/80/26`（sql 触碰按分级政策保守读法执行 offline 全量逐位，本次实测 record 增至 250，其余六模块逐位不变）。
- C-01 实施笔：`b7f3029`（`feat(record): 周月运动目标与前端目标卡片（TASK-225）`，恰 14 文件，+1302/−2）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/222/223/224 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI）待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 06d17f3，恰 18 文件 = C-01 14 + C-02 4，逐字符声明）

C-01（feat(record) 实施笔，恰 14 文件 = S4 白名单全等）：
- record-service/src/main/java/com/sportverify/record/controller/GoalController.java
- record-service/src/main/java/com/sportverify/record/dto/GoalDTO.java
- record-service/src/main/java/com/sportverify/record/dto/GoalUpsertDTO.java
- record-service/src/main/java/com/sportverify/record/dto/package-info.java
- record-service/src/main/java/com/sportverify/record/entity/Goal.java
- record-service/src/main/java/com/sportverify/record/mapper/GoalMapper.java
- record-service/src/main/java/com/sportverify/record/service/GoalService.java
- record-service/src/test/java/com/sportverify/record/controller/GoalControllerTest.java
- record-service/src/test/java/com/sportverify/record/service/GoalServiceTest.java
- sql/02-record-db.sql
- web/README.md
- web/src/api/__tests__/client.test.ts
- web/src/api/client.ts
- web/src/pages/index.page.vue

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，恰 4 文件）：
- spec/changes/add-sport-goal/tasks.json
- work/mailbox/tasks/TASK-225/spec.md
- work/mailbox/tasks/TASK-225/handoff.md
- work/mailbox/PLAN.md

> 下列载体零触碰（G2 证据见 §5）：六服务白名单外 src/main、api 模块（禁触，本课题完全零触碰）、父/子 pom、.github/workflows/ci.yml、scripts/、docker-compose*、sql/01/03/04/05 基线文件与 02-record-db.sql V21-V23 冻结区、web/package.json、web/vite.config.ts、web/src/typed-router.d.ts（零新路由）、user/verify/leaderboard/mapmatch 其余模块、既有 /my、/my/streak、feed、删除、筛选、visibility 门源码。词面门/探针/token/契约/scratch 迁移临时脚本均落 gitignored `.trae/tmp`、不入库。

## 3. 一句话结论

实现周/月运动目标（`sport_goal`）CRUD 与 pull-on-read 实时进度计算及前端统计面板目标卡片联动。在 `sql/02-record-db.sql` 尾部幂等追加 `sport_goal` 表结构（含 `UNIQUE KEY uk_user_period_metric`），在真实 MySQL 8.0 容器完成首跑建表、二跑跳过、唯一约束防重与 upsert 覆盖等完整幂等性留证；后端基于 MyBatis-Plus 实现 Goal 实体、Mapper、DTO、Service 与 Controller（`/api/records/my/goals`），支持周窗口（ISO 周一 00:00 起）与自然月窗口（1 日 00:00 起）对本人 **PASSED** 记录实时聚合 SUM(distance) 或 COUNT，百分比 0-100 整数截断与封顶，已删/非 PASSED 自动剔除；身份门与既有 `/my` 同构（缺头 1001，auth 关闭缺参 400，非 owner 403 1002，不存在 404 3001）；前端在统计面板新增目标卡片（`a-progress`、周期与达成标签、新建/编辑对话框、删除确认 Popconfirm），数据加载及记录删除后联动刷新；全程禁触 `api` 模块、零 MQ、零跨服务写、零缓存反范式、既有面源码零 diff；门禁按 sql 触碰执行 offline 全量逐位通过（`52/50/173/250/159/80/26`，record 净增 24 测，其余模块逐位不变）。

## 4. 判别式红绿（R1-R6 / G1-G3 逐条）

- **R1（绿）schema 与幂等**：`sql/02-record-db.sql` 尾部幂等追加 `sport_goal`（id PK AUTO_INCREMENT / user_id BIGINT / period_type TINYINT / metric TINYINT / target_value DECIMAL(10,2) / created_at DATETIME + UNIQUE KEY `uk_user_period_metric`）；真 MySQL 8.0 容器（`task131-scratch-mysql`）二跑留证完成（首跑成功建表，二跑幂等通过命中跳过分支，重复插入测试命中 1062 报错，upsert 覆盖更新成功）。
- **R2（绿）身份门与所有权校验**：
  - 身份门同构：`GoalControllerTest.getGoals_authEnabled_missingHeader_throwsUnauthorized`（缺头抛 1001 401）、`getGoals_authDisabled_missingUserId_badRequest`（auth 关缺参 400）、`getGoals_authEnabled_success`（正常放行 200）。
  - 删除越权与不存在：`deleteGoal_forbidden_throwsForbidden`（非 owner 抛 1002 HTTP 403）、`deleteGoal_notFound_throwsNotFound`（目标不存在抛 3001 HTTP 404）、`deleteGoal_success`（owner 成功删除 200）。
- **R3（绿）upsert 覆盖与输入校验**：
  - upsert 语义：`GoalServiceTest.upsertGoal_existingGoal_updatesTargetValue` 实测已存在同一 (user, period_type, metric) 目标时更新 `target_value` 不增行；`upsertGoal_concurrentInsert_retriesOnDuplicateKey` 验证并发 DuplicateKey 容错重试。
  - 参数校验：`upsertGoal_invalidPeriodType_badRequest`（periodType=99 抛 400）、`upsertGoal_invalidMetric_badRequest`（metric=99 抛 400）、`upsertGoal_nonPositiveTarget_badRequest`（targetValue<=0 抛 400）。
- **R4（绿）pull-on-read 进度与边界聚合**：
  - 窗口边界：`GoalServiceTest.listGoalsWithProgress_weeklyDistanceGoal_aggregatesOnlyCurrentWeek` 实测 ISO 周一至周日窗口计算，上周日记录不计入；`listGoalsWithProgress_monthlyCountGoal_aggregatesOnlyCurrentMonth` 实测自然月 1 日至末日窗口计算，上月末记录不计入。
  - 聚合与封顶：`listGoalsWithProgress_percentCappedAt100` 实测超额完成时 percent 严格封顶为 100，未达成时向下截断。
  - 状态与联动：仅本人 `PASSED` / `RE_PASSED` 记录计入，`PENDING` / `REJECTED` 记录与他人记录自动排除；删除记录后自然不再计入（与 TASK-223 级联删除联动语义留证）。
- **R5（绿）web 界面联动与卡片承载**：
  - `web/src/pages/index.page.vue` 个人统计面板集成目标卡片（`a-progress`、周/月周期标签、达成状态标签、新建/编辑对话框、删除确认 Popconfirm）；
  - 数据拉取（`loadData`）与删除记录后自动联动调用 `loadGoals()` 刷新目标进度；
  - `web/src/api/client.ts` 导出 `GoalDTO`、`GoalUpsertPayload` 与 `getMyGoals`、`upsertGoal`、`deleteGoal` 三方法；
  - `web/src/api/__tests__/client.test.ts` 增补 3 个单测全绿；web 四件套（vitest 31 测、type-check 0 错误、build 成功、typed-router diff 0）全绿。真实浏览器交互未跑，登记未覆盖。
- **R6（绿）既有面零改动**：`git diff 06d17f3` 严格证明既有面（`/my`、`/my/streak`、`feed`、删除、筛选、visibility 门）源码零 diff。
- **G1（绿）**：offline 全量逐位通过，组合读数 `52/50/173/250/159/80/26`（`record-service` 由基线 226 增至 **250**，净增 24 测，0 Failures/Errors/Skipped，rc=0；其余六模块逐位不变）。
- **G2（绿）**：C-01 后 `git diff --name-only 06d17f3` 恰白名单 14 文件；越界面（`api/`、六服务白名单外主码、pom、ci.yml、scripts、compose、sql 冻结区、web/package.json、web/vite.config.ts、typed-router.d.ts）diff 全空。
- **G3（绿）**：`--static=record-service` rc=0（0 Checkstyle violations / SpotBugs 0 错误 / PMD BUILD SUCCESS）；web 四件套全绿；契约门双态 rc=0（在途 `--open TASK-225 --baseline=06d17f3` + 收口无参）；token 29 项 2036 持平；词面门 CI-exact ZERO_HIT + 探针正控 HIT；`git diff --check` rc=0；改动文件保持基线 LF 行尾无 BOM。

## 5. §6 门禁留证

1. **offline 全量逐位门禁**（sql 触碰不收窄，按分级政策保守读法）：`scripts/verify/mvn-verify.sh --mode=offline test` 实测全 7 模块通过（rc=0，Total time: 08:32 min），用例数 **52/50/173/250/159/80/26**（`record-service` 基线 226→**250**，净增 24 测，其余 6 模块逐位不变，Failures: 0, Errors: 0, Skipped: 0）。
2. **scratch 迁移二跑证明**：真 MySQL 8.0 实例（`task131-scratch-mysql`）执行二跑验证：
   - Run 1（首跑）：执行创建 `sport_goal` 表成功；
   - Run 2（二跑）：再次执行迁移脚本，成功进入幂等检查跳过分支，无报错；
   - UK 约束证明：插入相同 (user_id=1, period_type=0, metric=0) 重复记录触发 `ERROR 1062 (23000): Duplicate entry '1-0-0' for key 'sport_goal.uk_user_period_metric'`；
   - Upsert 证明：执行 `ON DUPLICATE KEY UPDATE target_value` 成功更新目标值，行数不变。
3. **新增测试类隔离单跑**（D7 纪律，S0 强制）：每个承载新断言的测试类 `-Dtest=` 隔离单跑全绿——`GoalServiceTest` **10 测 rc=0**、`GoalControllerTest` **14 测 rc=0**。
4. **静态**：`scripts/verify/mvn-verify.sh --mode=offline --static=record-service` rc=0（`0 Checkstyle violations`，SpotBugs 0 错误，PMD BUILD SUCCESS）。
5. **web 本机四件套**：`pnpm test` rc=0（vitest，4 套 31 测：token 6 / gpx 5 / pace 6 / client 14，净增 3 测，TMP/TEMP 重定向 `.trae/tmp`）；`pnpm type-check` rc=0（vue-tsc --noEmit，0 错误）；`pnpm build` 成功（3283 modules）；`git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零新路由）。
6. **契约门双态**：在途 `--open TASK-225 --baseline=06d17f3` **实测 rc=0**（判据 A 两件套 + TASK-225 待办放行；判据 B 一致）；收口无参 **rc=0**（见 §9）。
7. **token 29 项**：开工基线 06d17f3 = **2036**、工作树 post-C01 = **2036**，持平且 ≥2034。改动集 14 文件逐项扫描 `ZERO_HIT`。
8. **词面门 CI-exact**：显式 ERE 口径（`grep -n -I -iE -f <模式文件>`）对 C-01 改动集 **ZERO_HIT rc=1**；合成探针同模式 **HIT rc=0**，无假阴性。模式字面量与探针均落 gitignored `.trae/tmp`、不入库。
9. **`git diff --check`**：rc=0（工作树与暂存区双查 clean）。
10. **行尾**：新文件与改动文件均保持基线纯 LF 行尾无 BOM，末尾换行齐全，无新增未跟踪文件。
11. **只改清单全等**：`git diff --name-only 06d17f3` 对照恰 §2 的 18 文件（C-01 14 + C-02 4）；越界面 diff 全空（G2）。
12. **凭证自检**：改动集为目标 CRUD、进度聚合、前端目标卡片与台账文案，零真实凭证字面量、零受保护 token 字面量、零新正则字面量。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和）：开工（06d17f3 基线）= **2036**、post-C01（C-01 提交后工作树）= **2036**（只增不减，且 ≥ 任务书 2034 基线）。本课题改动集（14 实施文件 + 4 台账文件）全部内容零真实凭证 / 零受保护 token 字面量。**口径说明**：沿用 `.trae/tmp/count_tokens.py`（29 项模式）复算，不枚举字面量以避免全仓计数自增失准（沿 TASK-188 N1 教训）。

## 7. 实现要点

- **Schema（02-record-db.sql）**：
  - 幂等追加 `sport_goal` 表（id PK AUTO_INCREMENT / user_id BIGINT / period_type TINYINT / metric TINYINT / target_value DECIMAL(10,2) / created_at DATETIME + UNIQUE KEY `uk_user_period_metric`）；
  - 遵循 02-record-db.sql 既有幂等模式，先从 `information_schema.tables` 检测表是否存在，不存在时执行建表。
- **record-service**：
  - `Goal`：MyBatis-Plus 实体，映射 `sport_goal` 表，主键使用 `IdType.AUTO`；
  - `GoalMapper`：继承 `BaseMapper<Goal>`；
  - `GoalDTO` / `GoalUpsertDTO`：支持周期类型（0=周 1=月）、指标类型（0=距离 1=次数）及正数 `target_value` 校验，提供 camelCase 与 snake_case 兼容 getter；新建 `dto/package-info.java` 满足 JavadocPackage 规约；
  - `GoalService`：
    - `listGoalsWithProgress(Long userId)`：查询本人所有目标；计算 ISO 周一 00:00:00 与自然月 1 日 00:00:00 起止窗口；拉取该窗口内本人 `status in (PASSED, RE_PASSED)` 的记录；按指标聚合已完成距离 `SUM(distance)` 或次数 `COUNT(*)`；计算进度百分比 `(achieved / target) * 100`（向零截断整数，封顶 100）；组装返回 `GoalDTO`；
    - `upsertGoal(Long userId, GoalUpsertDTO dto)`：校验参数合法性；查询是否存在同一 (user_id, period_type, metric) 目标，存在则更新 `target_value`，不存在则插入新行；捕获并发 `DuplicateKeyException` 自动容错重试更新；
    - `deleteGoal(Long userId, Long goalId)`：查询目标是否存在（不存在抛出 3001 记录不存在），校验所有权（非本人抛出 403 code 1002 越权）。
  - `GoalController`（`/api/records/my/goals`）：
    - `GET`：查询目标列表及实时进度；
    - `POST`：新建或更新目标；
    - `DELETE /{id}`：删除目标；
    - 身份门与既有 `/my` 同构（`resolveUserId`：鉴权开启从 `X-User-Id` 头解析，缺头抛 1001；鉴权关闭降级 `userId` 参数，缺失抛 400）。
- **web**：
  - `web/src/api/client.ts`：导出 `GoalDTO`、`GoalUpsertPayload`，新增 `getMyGoals()`、`upsertGoal()`、`deleteGoal()` 三方法；
  - `web/src/pages/index.page.vue`：在个人统计面板集成目标卡片，使用 `a-progress` 展示完成进度、状态标签（未达成/已达成）、周/月标签；提供「设置目标」与「编辑」弹窗以及删除确认 Popconfirm；与数据加载及记录删除事件联动刷新；
  - `web/README.md`：记录运动目标管理与进度展示说明。

## 8. 异常与偏差登记

- **D1（台账笔自指哈希沿先例）**：C-02 台账笔包含 handoff.md 自身，其 commit hash 按先例记载，以最终 `git log` 为准。
- **D2（临时脚本与迁移留证落 `.trae/tmp`）**：scratch 迁移验证脚本、token 计算、词面门扫描等临时件均存放在 gitignored `.trae/tmp` 目录，不入库，命令行参数保持零中文。
- **D3（Checkstyle JavadocPackage 规约适配）**：新建 `dto` 包需补充 `package-info.java`，满足项目全局 Checkstyle 规约。
- **D4（offline 日志噪音）**：offline 模式下 Nacos 连接未就绪告警为既有测试环境噪音，对 7 模块计数零影响。
- **D5（web build chunk 体积告警）**：`vite build` 提示 chunk > 500 kB 为既有非阻断 advisory，构建 rc=0。
- **停止条件（§3）核对**：无一按字面触发——
  - §3.1 02 幂等模式与 S1 记载相符，沿用 `information_schema.tables` 分支幂等追加，scratch 二跑验证成功；
  - §3.2 offline 7 模块全绿，`record-service` 净增 24 测（226→250），其余 6 模块逐位不变，scratch 二跑完全幂等；
  - §3.3 正确实现完全无需触碰 `api` 模块、零发 MQ、零跨服务写；
  - §3.4 进度窗口（ISO 周一与自然月）为标准日历周期，与既有 streak/统计周期语义完全兼容无冲突；
  - §3.5 进度聚合按本人周期窗口拉取记录（`user_id` 已有索引），单测执行迅速，无性能红旗。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2036**，≥2034 基线；改动集 18 文件 token 扫描 ZERO_HIT。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；工作树 `git status` 干净。
- `git diff --name-only 06d17f3` 对照恰 **18 文件**（C-01 14 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程未跑（未 push、未建 PR）；外部门槛待指导侧推动推送后补记。
- **真实浏览器「运动目标卡片与对话框交互/列表联动」未跑**（沿 TASK-213/220/221/222/223/224 口径登记未覆盖）：验证由 vitest + type-check + build + 代码审查承载；不得据此推断在真实浏览器端到端交互可用。
- **真实跨服务与真实 DB 聚合联调未跑**（未起六服务全栈）：真实 MySQL 大数据量目标与记录聚合性能未在全栈端到端环境执行；`--it` 未执行。目标 CRUD 与 pull-on-read 进度聚合逻辑由真 MySQL scratch 迁移验证及单测 Mockito 验证承载。
