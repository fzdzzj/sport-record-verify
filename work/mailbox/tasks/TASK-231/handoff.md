# TASK-231 handoff：个人统计服务端聚合端点与页面历史文案清理（MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-231`（契约：`work/mailbox/tasks/TASK-231/spec.md`；本文件为回传）。
- 分级：**MEDIUM**（新增聚合端点 + 契约 DTO + 前端接入解耦 + 页面旧文案清洗）。
- 开工基线：`9d1e79f`（工作树干净；开工前仅本任务 `spec.md` 未跟踪）。
- C-01 实施笔：`ee95c493d826dc7f6a2489c1c3f5dab5231c0f7c`（`feat(record): 新增个人统计聚合端点 /my/stats 并接入首页统计卡（TASK-231）`，恰 9 文件 = 8 改 + 1 新增，`+353/−40` 加上新 DTO 全文）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/224 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI）待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 9d1e79f，恰 11 文件）

C-01（`feat(record)` 实施笔，恰 9 文件 = 白名单实施面全等）：
- api/src/main/java/com/sportverify/api/record/dto/MyStatsDTO.java
- record-service/src/main/java/com/sportverify/record/controller/RecordController.java
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
- record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
- web/src/api/client.ts
- web/src/api/__tests__/client.test.ts
- web/src/pages/index.page.vue
- web/src/pages/record-submit.page.vue

C-02（`docs(mailbox)` 台账笔，恰 2 文件）：
- work/mailbox/tasks/TASK-231/spec.md
- work/mailbox/tasks/TASK-231/handoff.md

> 白名单外零触碰：`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 未碰、零改动；六服务其余源码、父/子 pom、`.github/workflows/ci.yml`、`scripts/`、`sql/`、`docker-compose*`、`web/package.json`、`web/vite.config.ts`、`web/src/typed-router.d.ts`（零新路由）均零 diff。临时验证日志落 gitignored `.trae/tmp/`，不入库。

## 3. 一句话结论

新增 `GET /record/api/records/my/stats`（身份语义与 `/my`、`/my/streak` 同构：auth on 取网关注入 `X-User-Id`，缺失 1001；auth off 取显式 `userId`，缺失 400），服务层以 `select(distance, status, createdAt).eq(userId)` 单查投影 + 内存轻量汇总给出 `MyStatsDTO`（totalCount/totalDistance/passCount/passRate/dailyDistances），**全量口径、不接分页与过滤**，彻底解耦首页统计卡与表格翻页；前端 `client.getMyStats` 接入后，`index.page.vue` 统计卡三项与近 30 天里程条只读聚合结果，删除了原先对当前页 `records` 的浏览器端聚合（其两处技术债：>500 截断、翻页后统计只剩当页 20 条）；同时清洗 `index.page.vue` 与 `record-submit.page.vue` 中「无列表 API / 无服务端列表」历史旧文案。禁触文件零触碰、零新依赖、构造参数未新增（最大仍 7 参）。

## 4. 判别式与验收要点

- **R1 端点与鉴权（绿）**：`RecordController.myStats(userId, request)` 与 `/my/streak` 同构；`RecordControllerTest` 新增 4 例——auth on 取头 200 透传、auth on 缺头/空白头抛 1001、auth off 缺 userId 抛 400（消息与 `/my` 一致）、auth off 显式 userId 透传。
- **R2 全量聚合口径（绿）**：`SportRecordServiceTest.myStats_aggregatesAllRecordsAndDailyWindow` 以 captor 断言 wrapper 仅含 `user_id =`（参数集恰 `{200L}`，**无 status/分页条件**）且投影含 `distance/status/createdAt`（非 `select *`）；7 条样本（含同日多条、窗口边界第 29 天、窗口外第 30 天、`distance=null`、REJECTED）实测 totalCount=7、totalDistance=22.00、passCount=5（PASSED×4 + RE_PASSED×1）、passRate=`71.4%`；dailyDistances 恒 30 键、升序、首键 = today−29、末键 = today、today 同日累加 7.00（3.5+1.5+2.0，日粒度覆盖全部状态）、无记录日 `0.00`、窗口外日期不入日粒度但仍计入总量。
- **R3 空态与格式化（绿）**：`myStats_emptyReturnsZeroStatsWithFullDailyWindow` 实测无记录 → totalCount=0、totalDistance=0.00、passCount=0、passRate=`0%`（非 `0.0%`，与 spec §3 逐字一致）、dailyDistances 仍 30 键且全为 0.00。
- **R4 前端解耦与文案（绿）**：`client.test.ts` 新增 1 例断言 `getMyStats()` 打 `GET /record/api/records/my/stats`、method=get、**无 query 参数**（归属由网关注入）；`index.page.vue` 的 `totalCount/totalDistance/passRate/dailyBars` 全部改为读 `stats`（`/my/stats`），`dailyBars` 只做「日期窗口对齐 + 最大值为 100% 的高度归一化」，不再遍历 `records` 聚合；`isPassed` 前端本地判定与 `passCount` 局部计算已删除；旧文案「诚实无列表 API」「无后端列表 API」「统计自单次 size=500 拉取」已替换为指向 `/my`（过滤+分页）与 `/my/stats`（全量聚合）的实况描述。
- **R5 红线（绿）**：零新依赖（`package.json`、pom 零 diff）；未触碰 `PLAN.md`/`HANDOVER.md`；`MyStatsDTO` 无构造器、`SportRecordService` 构造器仍 7 参、控制器 `myRecords` 仍 8 参（Checkstyle `ParameterNumber` max=8 上限未逼近新阈值）。

## 5. 验证命令与输出片段

1. **后端单测**：`bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` → **rc=0**（输出落 `.trae/tmp/mvn-verify-task231.log`）：
   - `[INFO] Tests run: 79, Failures: 0, Errors: 0, Skipped: 0 -- in com.sportverify.record.service.SportRecordServiceTest`
   - `[INFO] Tests run: 37, Failures: 0, Errors: 0, Skipped: 0 -- in com.sportverify.record.controller.RecordControllerTest`
   - `[INFO] Tests run: 261, Failures: 0, Errors: 0, Skipped: 0` / `[INFO] BUILD SUCCESS` / `[INFO] Total time: 03:04 min`
   - 净增 6 测（`RecordControllerTest` +4、`SportRecordServiceTest` +2），既有用例零改动全绿。
2. **前端 type-check**：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit`，0 错误）。
3. **前端单测**：`pnpm --dir web test` → rc=0（`Test Files 4 passed (4)` / `Tests 32 passed (32)`，其中 `src/api/__tests__/client.test.ts (15 tests)`）。
4. **前端构建**：`pnpm --dir web build` → 成功（`✓ 3317 modules transformed` / `✓ built in 27.00s`；chunk >500 kB 为既有非阻断 advisory）。**首次构建失败非代码问题**：`vite:esbuild-transpile` 删除 `%LOCALAPPDATA%\Temp\esbuild-*` 被拒（`Access is denied`），把 `TMP/TEMP` 重定向到仓内 gitignored `.trae/tmp` 后复跑即成功（沿 TASK-224 同款处置）。
5. **首轮后端红→绿留证**：首跑仅 1 例红（新增用例自身期望值写错：today 日里程漏算同日 REJECTED 的 2.0 → 期望 5.00 实得 7.00），修正期望后复跑全绿；服务端实现未改动。
6. **契约门**：`bash scripts/verify/mailbox-contract.sh --open TASK-231 --baseline=9d1e79f` → **rc=0**（`[contract] TASK-231：判据 B 通过（只改清单与实际改动集一致）` + `契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`）。C-02 后无参复跑（baseline=HEAD=C-02）预期走「已收口不重审」分支。

## 6. 未覆盖、遗留风险与不得推出的结论

- `--mode=online`、`--static`（Checkstyle/SpotBugs/PMD）、CI 全流程均未跑（未 push、未建 PR），外部门槛待指导侧推动后补记。
- **真实浏览器端到端未跑**（沿 TASK-213/220/221/222/223/224 口径登记未覆盖）：「翻到第 2 页时统计卡与近 30 天里程保持不变」这一核心验收行为由代码审查 + 单测（client 层 URL 契约、service 层全量口径）承载，不得据此推断真实浏览器交互已可用。
- **真实 MySQL 未联调**：`myStats` 为单查全量拉取（个人量级 + 投影三列，无 `LIMIT`/无下推聚合），单用户记录数极大（10 万级）时的响应时间与内存占用未压测；如需进一步优化应下推 SQL 聚合或加时间窗条件，本任务按 spec §3「内存轻量汇总」实现。
- **时区口径**：`dailyDistances` 窗口右端取服务器时钟 `LocalDate.now()`（沿 `myStreak` 既有口径），与浏览器本地日期可能跨时区错位；前端按本地日期生成窗口并对 key 取数，跨时区场景未验证。
- **前端零值兜底未自动化覆盖**：`/my/stats` 失败时统计卡回退 `0 / 0.00 / 0%` 的分支无 vitest 用例（仓内既有 .vue 页面均无组件级测试）。
- **未覆盖的具体断言**：`dailyDistances` 的 `BigDecimal` 序列化形态（JSON 数字 vs 字符串）未在真实 HTTP 链路上验证，前端以 `Number(...)` 兼容两态。
- **本机 Playwright e2e 路由 mock 未同步（预期红，未实跑）**：`web/e2e/helpers.ts` 的 route-mock 未映射新增的 `GET /record/api/records/my/stats`，未命中分支落到 404 兜底（`code=9999`），前端按设计回退 `0 / 0.00 / 0%`；而 `web/e2e/record-list.e2e.ts` 仍按「统计卡 = 列表第 1 页聚合」断言 `记录数=25`、`总里程=pageOneDistanceSum()`、`通过率=25.0%`，据代码推断该 spec 的这三条断言不再成立（`login-redirect.e2e.ts` 只断言卡片标题，不受影响）。`web/e2e/` 不在本任务修改白名单内，故未改动、也未实跑（`pnpm --dir web e2e` 未执行）；CI 的 web 段（`.github/workflows/ci.yml`）只跑 `type-check/test/build`，不含 e2e，因此当前门禁不被阻断——但**后续任务须同步 e2e mock 与统计卡断言**，本任务不得据「门禁全绿」推断 e2e 面完好。
