# TASK-211 回传交接（我的记录端点与个人统计面板，FULL 档）

## 1. 编号、基线与外部状态

- 课题：add-personal-stats（我的记录端点与个人统计面板）。
- 分级：**FULL**（新增查询端点 + 前端仪表盘；四块口径独立复核）。
- 开工基线：`ac6e469`（派发笔，含提案三件套 proposal/spec-delta/tasks.json 与任务书）；开工前 `git status --porcelain` 为空，基线自洽。
- 离线基线（§6）：675 = 52/50/162/157/159/80/15（record 157 仅净增）。
- C-01 实施笔：`852e97b`（`feat(record): 我的记录端点与个人统计面板（TASK-211）`，恰 8 文件 = §4 白名单，+444 ／−3）；C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`）。
- 两笔均**未 push、未建 PR**；外部 CI 终验与独立复核待指导侧推动推送后补记。

## 2. 只改清单（C-01 = 8 文件；C-02 = 恰 4 台账文件；两笔合计 12 文件 = 从 `ac6e469` 的改动集）

C-01（feat(record) 实施笔）：
- api/src/main/java/com/sportverify/api/record/dto/RecordSummaryDTO.java（新文件）
- record-service/src/main/java/com/sportverify/record/controller/RecordController.java
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
- record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
- web/src/pages/index.page.vue
- web/src/api/client.ts
- README.md

C-02（docs(mailbox) 台账笔，本 handoff 所在提交）：
- spec/changes/add-personal-stats/tasks.json（勾选）
- work/mailbox/tasks/TASK-211/spec.md（§7 回填）
- work/mailbox/tasks/TASK-211/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

## 3. 一句话结论

后端新增只读端点 `GET /record/api/records/my`（分页返回本人 `RecordSummaryDTO` 概要），
归属即身份——auth.enabled=true 从网关 `X-User-Id` 解析（缺→1001/401），false 必填 `userId`
查询参数（缺→400 PARAM），过滤即身份构造上无越权；page 下限 1、size 默认 20 上限 500。
前端首页登录态个人仪表盘：总里程/记录数/通过率（PASSED+RE_PASSED 口径）统计卡 + 近 30 天
每日里程纯 CSS 条图 + 最近记录前 10 条，均来自单次 size=500 拉取（超 500 条为已知部分统计）；
零新前端依赖（条图为纯 CSS 高度比例）。全部 §2.5 判别式与 §6 门禁按 FULL 档留证。

## 4. 判别式红绿（R1 / R2 / G1 / G2）

record 侧（纯 JVM Mockito，交织进 RecordControllerTest / SportRecordServiceTest）：
- **R1a 绿**：`myRecords_authEnabled_resolvesOwnerFromHeader`（X-User-Id=200 → service.pageMyRecords(200)）+ ServiceTest `pageMyRecords_filtersByUserId`——selectPage 的 wrapper `getSqlSegment()` 含 `user_id =` 且参数值含 200（mapper 过滤 verify，只返本人行）。
- **R1b 绿**：ServiceTest `pageMyRecords_clampsPageAndSize`——page 下限 1（0/-5 夹到 1）、size cap 500（501→500）、size 下限 1；ControllerTest `myRecords_delegatesPageSize`（page/size 透传服务层；默认 20 由 `@RequestParam(defaultValue="20")` 承担）。
- **R1c 红**：ControllerTest `myRecords_authEnabled_missingHeader_throwsUnauthorized`（缺 X-User-Id → BizException UNAUTHORIZED/1001 → HTTP 401）；`myRecords_authDisabled_missingUserId_throwsIllegalArgument`（false 且缺 userId → IllegalArgumentException → HTTP 400 PARAM）。
- **R1c 绿**：`myRecords_authDisabled_explicitUserId_delegates`（false 显式 userId → 按其查）。
- **R1d 绿**：ServiceTest `pageMyRecords_emptyReturnsEmptyPage`（空结果 → total=0、records 空，非错误）；`pageMyRecords_mapsToSummaryDto`（id/sportType/distance/status/createdAt 五字段映射正确）。
- 实测：SportRecordServiceTest 39→**43**（+4）、RecordControllerTest 11→**16**（+5）；record-service 全量 **166** 全绿。

web 侧（vue-tsc 留证 + 代码审查）：
- **R2a（绿）**：index.page.vue `totalCount`/`totalDistance`(reduce sum)/`passRate`(passCount/n) 聚合正确，`isPassed = status===2||status===5`（PASSED+RE_PASSED 口径，同既有入榜/可赞语义）。
- **R2b（绿）**：近 30 天条图为纯 `div` 高度比例（`height: pct%`），零图表库；`web/package.json` 零 diff（R2b 证据，见 §5.8）。
- **R2c（绿）**：`recent = records.slice(0,10)`，records 按 `createdAt` 倒序（后端排序 + 前端兜底 sort），最近 10 条。
- **G1（绿）**：`pnpm run type-check`（vue-tsc --noEmit）rc=**0**；record-service 既有用例零回归（offline 166 全绿）。
- **G2（绿/核空）**：`git diff ac6e469..HEAD` 对 api 既有文件/user/gateway/verify/leaderboard/mapmatch/common/parent pom/sql/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose 全空；api 模块仅新增 RecordSummaryDTO 一个新文件，禁加 index（未加，已登记已知限制）。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS rc=0**，逐位 **684 = 52/50/162/166/159/80/15**（record 157→166 净增 **+9**，其余六模块 common/gateway/user/verify/leaderboard/mapmatch 逐位不变）。
2. **静态门**：`--static=record-service` rc=**0**（TASK-194 口径：Checkstyle 0 / SpotBugs 满足 failThreshold / PMD 0）。
3. **契约门双态**：在途 `--open TASK-211 --baseline=ac6e469` rc=**0**（判据 A 两件套齐含 1 个待办进行中 = TASK-211，判据 B 在途）；收口无参 rc=**0**（见 §8）。
4. **token 29 项 2030**：见 §6。
5. **词面门**：CI-exact（`-n -I -iE` 显式 -E）`git grep -n -I -iE …` 对改动 8 文件（C-01 集）ZERO_HIT rc=**1**；合成探针阳性对照（经 `git add -f` 后 grep 含词样文）HIT rc=**0**，证明 `-E/-i` 口径有效、无假阴性。
6. **`git diff --check`**（含 staged）rc=**0**；C-01/C-02 两笔 `git show --check` rc=**0**。
7. **行尾**：改文件保持基线各自身——RecordController.java、SportRecordService.java、SportRecordServiceTest.java、README.md 为 CRLF；SportRecordMapper 未改动；RecordControllerTest.java、client.ts、index.page.vue 为 LF；新文件 RecordSummaryDTO.java 与 handoff.md 纯 LF + 末尾换行、无 BOM、无 mixed。
8. **web / 零依赖**：`pnpm run type-check`（vue-tsc --noEmit）rc=**0**；`web/package.json` 零 diff 为 **R2b** 租证。本机未跑 `pnpm build`（S6 口径「vue-tsc rc=0 或 CI web job 权威」满足，无 esbuild 阻断记录）。

## 6. token 时点读数

29 项受保护 token 以 tracked `git grep -cF` 全量计（SUM = 各 token 命中计数求和）：开工（ac6e469 基线）账单参照 = **2030**、post-C01 = **2030**、close = **2030**（只增不减；本课题新文档未引入受保护 token 字面量，C-01 的 8 个实现/测试/前端/文档文件对 29 项 token 命中 **0**）。

## 7. 实现要点

- **R1 端点**：`RecordController.myRecords(@RequestParam userId(required=false) Long, page=1, size=20, request)`——`Long owner = authEnabled ? resolveUserId(request, null) : userId`；`!authEnabled && owner==null` → `IllegalArgumentException("缺少必须的 userId 查询参数")`（GlobalExceptionHandler 映射 HTTP 400）；authEnabled 时 resolveUserId 缺/空 X-User-Id → BizException(1001) → 局部处理器 HTTP 401。服务层 `pageMyRecords(userId,page,size)` 夹取边界后 `sportRecordMapper.selectPage(new Page<>(p,s), wrapper.eq(userId).orderByDesc(createdAt))` 并映射 `RecordSummaryDTO`。
- **归属即身份**：过滤条件即身份（`WHERE user_id=?`），构造上无越权，无需逐行归属断言。
- **R2 前端**：登录态判定 `hasAccessToken()`；`getMyRecords(1, 500)` 单次拉取，统计卡/条图/最近列表全部基于该批数据；条图 `dailyBars` 为最近 30 天按 `createdAt.slice(0,10)` 聚合的纯 CSS 高度比例；`package.json` 零改动。
- **已知限制登记**：>500 条个人记录为部分统计（size cap 500 即前端单次拉取上限）；`sport_record` 未加 index（禁加 index 红线，沿用既有索引）。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 无法记录 C-02 自身提交哈希，沿 TASK-178/183/204/205/206/207/208/209/210 同型先例，以 C-02 实际提交哈希为准（`git log --oneline`）。
- **D2（pwsh 退出码遮蔽）**：后台 Maven 经 PowerShell `2>&1` 管道时 stderr 的 `[verify-entry]` 提示会被 native 写捕获，使 pwsh job 外层记录 exit=1；但 Maven 侧明文 `BUILD SUCCESS` 与 Reactor 全 SUCCESS、`[verify-entry] Maven 以退出码 0 结束` 均直证通过，判据以 Maven 原样输出为准。
- **D3（无 esbuild 本机 build）**：未跑 `pnpm build`，仅 vue-tsc rc=0，S6 口径「vue-tsc rc=0 + CI web job 权威」满足；无 esbuild 受阻记录。
- **停止条件（§3）**：无一条触发——改动未出白名单、分页约定沿用既有 Page 无共享基建冲突、静态门 rc=0 无结构性重构、offline 基线 record 仅净增其余逐位不变。

## 9. 收口门禁（C-02 后亲跑）

- 契约门**无参 rc=0**（收口后回传文件已入库）。
- token 收口态 repo 全量 `git grep -cF` SUM=**2030**（只增不减）。
- `git diff --check` 与两笔 `git show --check` 均 rc=0。
- `git diff --name-only ac6e469` 对照恰 **12 文件**（C-01 8 + C-02 4）：对 api 既有文件/user/gateway/common/父 pom/sql/ci.yml/mvn-verify.sh/mailbox-contract.sh/compose **git diff 全空**。
- 工作树 `git status --porcelain` 干净（`.trae/` 排除、`.trae/tmp` 已清空）。
- C-01 hash：见第 1 节与 §10。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程、真实网关注入 X-User-Id 的跨进程 HTTP 401/400 链路、真实浏览器可视交互未跑；未 push、未建 PR，无 CI run；外部 CI 终验与独立复核待指导侧推动推送后补记（FULL 档：独立复核四块口径）。
- 无性能/吞吐断言（任务书 §0 禁性能声明）。
