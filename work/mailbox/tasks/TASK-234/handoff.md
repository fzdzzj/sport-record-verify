# TASK-234 handoff：原有功能健壮化——物理合理性推导、榜单周期联调与目标边界防御（MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-234`（契约：`work/mailbox/tasks/TASK-234/spec.md`；本文件为回传）。
- 分级：**MEDIUM**（原有功能健壮化：后端合理性校验/轨迹推导 + 目标边界收紧 + 前端死逻辑修复与展示补齐）。
- 开工基线：`0224ea4`（工作树干净；开工前仅本任务 `spec.md` 未跟踪）。
- C-01 实施笔：`feat/refactor 见 git log`（提交信息 `feat(record,web): TASK-234 ...`，恰 7 文件 = 白名单实施面全等）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/224/231/232/233 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI / 契约脚本 bash 执行）待指导侧推动后补记（见 §7 D1）。

## 2. 只改清单（相对开工基线 0224ea4，恰 9 文件）

C-01（实施笔，恰 7 文件 = 白名单实施面全等）：
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
- record-service/src/main/java/com/sportverify/record/service/GoalService.java
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
- record-service/src/test/java/com/sportverify/record/service/GoalServiceTest.java
- web/src/pages/record-submit.page.vue
- web/src/pages/leaderboard.page.vue
- web/src/pages/index.page.vue

C-02（`docs(mailbox)` 台账笔，恰 2 文件）：
- work/mailbox/tasks/TASK-234/spec.md
- work/mailbox/tasks/TASK-234/handoff.md

> 白名单外零触碰：`api/`、`verify-service`、`web/src/api/client.ts`、pom、`package.json`、`playwright.config.ts`、
> `work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 均零 diff；零新依赖。临时验证日志落 gitignored `.trae/tmp/`，不入库。

## 3. 一句话结论

六处「破窗/死逻辑/缺口」一次收口：① `SportRecordService.submit` 新增物理合理性校验（显式或推导后的 `startTime <= endTime`、`startTime` 不得为未来），并对未显式提供且 `points` 非空的 `startTime/endTime/duration/distance` 逐字段补全——时间/时长取轨迹首末点毫秒（UTC 墙钟，与 GenSamples/前端 toISOString 同口径）、距离按相邻点 Haversine 累加（R=6371000 m，与 verify GeoUtils 同公式）换算公里保留两位（对齐 `DECIMAL(8,2)`），根除库内关键字段 NULL 破窗；② `record-submit.page.vue` 加载样例轨迹时同步提取并展示推导字段（UTC `YYYY-MM-DDTHH:mm:ss`、时长、Haversine 距离两位小数），提交 DTO 经 `as RecordSubmitDTO` 断言带齐四字段（`client.ts` 契约类型不在白名单，不做类型面改动）；③ `leaderboard.page.vue` 查询分支由恒真的 `lbType` 改为「最后一次点选控制项获胜」的 `lbMode`——今日/本周/本月真实调起 `getLeaderboardRange`，总榜/好友榜仍走 `getLeaderboard`，两组按钮均生效；④ `GoalService.upsertGoal` 边界收紧——目标值 >10000 拒绝、次数目标（metric=1）强制正整数（`stripTrailingZeros().scale()<=0`，尾零小数如 8.00 放行）；⑤ `GoalService.queryPeriodRecords` 周期窗口过滤由 `created_at` 改为 `COALESCE(start_time, created_at)`，离线补传运动按运动时间归属周期、存量行回退创建时间；⑥ `index.page.vue` 补齐 `visibility===0` 的绿色「公开」标签。生产代码仅白名单两个服务类与三个页面，测试仅白名单两个测试类；后端 275 测全绿、前端 type-check/test/build/e2e(12 passed) 全绿。

## 4. 判别式与验收要点

- **R1 提交合理性校验（绿）**：显式 `startTime > endTime` → `IllegalArgumentException("开始时间不能晚于结束时间")`、显式/推导 `startTime` 为未来 → `IllegalArgumentException("开始时间不能为未来时间")`，均不落库（`never insert` 断言）；轨迹首末点 ts 倒流（首点晚于末点）经推导后命中倒流拒绝；需推导但首末点缺 `ts` → 明确拒绝（`"轨迹点缺少时间戳（ts），无法推导开始/结束时间与时长"`）。
- **R2 轨迹推导（绿）**：四字段全缺 + 轨迹非空 → 落库 `startTime/endTime = 首末点 ts`（UTC 墙钟，`Instant.ofEpochMilli` 断言）、`duration = 毫秒差/1000`、`distance = Haversine 累加`（赤道 0.001° 段 ≈ 111.195 m → 0.111195 km 保留两位 = 0.11，独立数值断言）；部分缺失只补缺失项、显式项原样落库（三态全测：全缺推导 / 部分缺失 / 全显式原样）。
- **R3 榜单周期分支修复（绿）**：`leaderboard.page.vue` 查询分支从 `lbType`（封闭联合恒真 → `getLeaderboardRange` 死代码）改为 `lbMode`（`range|zset`）：今日/本周/本月点选置 `range` → `getLeaderboardRange(rangeStart(), size)`；总榜/好友榜点选置 `zset` → `getLeaderboard(type, size)`；默认 `range`（今日）。type-check 通过（分支可达性由 `vue-tsc` 与人工核对保证）。
- **R4 目标边界收紧（绿）**：`metric=1` 且 `targetValue=4.50` → 拒绝（`"次数目标必须为正整数（无小数位）"`）；`targetValue=10000.01` → 拒绝（`"目标值不能超过10000"`）；`10000` 恰在上限内放行、`8.00`（尾零小数）归一后按整数放行（既有测试 8.00/4.00 兼容不破坏）。
- **R5 周期归属修正（绿）**：`queryPeriodRecords` 窗口条件经 captor 断言为 `COALESCE(start_time, created_at) >= ?` / `<= ?`，上下界参数值恰为 ISO 周窗口（2026-10-12 00:00 / 2026-10-18 23:59:59.999999999）；行为侧：离线补传记录（startTime 在周窗口内、createdAt 在窗口外）计入本周达成量，startTime 为空的存量行按 createdAt 归属（次数 +1）。
- **R6 公开标签与红线（绿）**：`index.page.vue` 可见性列补 `visibility===0` → 绿色「公开」标签（1 行插入）；白名单外零 diff、零新依赖（`git diff --name-only 0224ea4` 恰 9 文件）。

## 5. 验证命令与输出片段

1. **后端单测**：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service -am`（= `scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` 的等价 Maven 拼写，bash 不可用见 §7 D1）→ **BUILD SUCCESS**（reactor：parent/common/api/record-service 4 模块全 SUCCESS，record-service 04:38min；输出落 `.trae/tmp/mvn-verify-234b.log`）：
   - `[INFO] Tests run: 275, Failures: 0, Errors: 0, Skipped: 0`（基线 262 → +13：SportRecordServiceTest +8、GoalServiceTest +5）。
2. **前端 type-check**：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，0 错误）。
3. **前端单测**：`pnpm --dir web test` → rc=0：`Test Files 4 passed (4)` / `Tests 32 passed (32)`（vitest）。
4. **前端构建**：`pnpm --dir web build`（`TMP/TEMP` 重定向 `.trae/tmp`，沿 TASK-224/231/232/233 同款处置）→ rc=0：`✓ built in 55.05s`（chunk >500 kB 为既有非阻断 advisory）。
5. **前端 E2E 全量**：`pnpm --dir web e2e`（同 TMP/TEMP 重定向）→ **`12 passed (3.5m)`，rc=0**（chromium 单 worker，vite preview 生产产物 + route-mock）：12 条全绿，含 `record-submit.e2e.ts` 两条关键路径（样例加载/推导字段展示不破坏既有断言、带四字段的 POST 载荷被 mock 正常接收）与 `record-list.e2e.ts` 可见性列渲染。
6. **契约门禁**：`bash scripts/verify/mailbox-contract.sh --open=TASK-234 --baseline=0224ea4` 本机 bash（WSL vhdx 缺失、无 Git Bash）不可执行 → 按脚本口径手动核算（§7 D1）：判据 A 两件套齐全（spec.md + handoff.md 均在，C-02 后）；判据 B 以 `git diff --name-only 0224ea4` + untracked 核算实际改动集 = 本 handoff §2 只改清单 9 文件全等，无多报无漏报。

## 6. 未覆盖、遗留风险与不得推出的结论

- `--mode=online` / `--static`（Checkstyle/SpotBugs/PMD）/ CI 全流程未跑（未 push、未建 PR），外部门槛待指导侧推动后补记。
- **契约脚本以 bash 执行未实跑**：本机无可用 bash（WSL Ubuntu 发行版 vhdx 缺失、未装 Git Bash），`mailbox-contract.sh` 仅按脚本源码口径人工核算判据 A/B（见 §7 D1）；若指导侧环境可跑 bash，建议补跑一次留证。
- **真实 MySQL / ShardingSphere 代理链路未联调**：推导/校验为纯服务层单测（mock mapper）验证；`COALESCE(start_time, created_at)` 的真实 SQL 窗口过滤与 `DECIMAL(8,2)` 落库精度未在真实数据源验证。
- **未来时间校验的墙钟帧假设**：显式入参按服务器本地墙钟比对、推导值按 UTC 墙钟比对——显式入参依赖「客户端本地 ≈ 服务器本地」的同帧假设；跨时区客户端显式提交的极端场景下未来校验可能有 ±时区差松弛，属既有 LocalDateTime 无时区契约的固有边界，非本任务引入。
- **前端推导仅覆盖内置样例轨迹**：`record-submit.page.vue` 的推导函数（`isoFromTs`/`haversineKm`）只用于加载内置样例；任意自定义轨迹仍由服务端推导兜底（前端未提交字段 → 服务端补全），两端公式同源但未做逐点对拍（E2E 未断言推导数值）。
- **次数目标整数性校验在服务层**：`GoalUpsertDTO` 的 bean 校验注解未改（白名单外），服务层 `validateGoalParams` 为最终防线；前端弹窗仍允许输入小数（提交后由服务端 400 拒绝），未加前端输入限制。
- **榜单「最后一次点选获胜」交互**：总榜/好友榜与今日/本周/本月为两组并列按钮，点选任一即切换对应查询族；「总榜 × 周期组合」视图（range 端点不区分 overall/friend，属公共聚合面）不存在，属预期产品语义而非缺陷。

## 7. 偏差登记

- **D1（bash 不可用，验证命令等价直调 + 契约脚本人工核算）**：`C:\Windows\System32\bash.exe` 为 WSL 入口，其 Ubuntu 发行版 `ext4.vhdx` 缺失（`ERROR_FILE_NOT_FOUND`），且未装 Git Bash → `scripts/verify/mvn-verify.sh` 与 `mailbox-contract.sh` 无法以 bash 执行。处置：后端以脚本同参等价直调 `mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service -am`（`--mode=offline` 语义 = `-o -s`，`-pl ... -am` 同脚本拼写）；契约判据按脚本源码口径（`extract_claims`/判据 A/B 逻辑）以 git 核算。非代码缺陷。
- **D2（后端壳命令退出码假阴性）**：pwsh 管道（`Tee-Object | Select-Object`）包裹后任务退出码取到管道端，Maven 日志为 `BUILD SUCCESS`（reactor 4 模块全 SUCCESS）；判读以日志 `Tests run: 275, Failures: 0, Errors: 0` 为准（沿 TASK-233 D2 同款先例）。
- **D3（E2E 首轮 webServer 超时）**：首轮 `pnpm --dir web e2e` 与后端 Maven 全量并行，CPU 抢占致 `pnpm build && pnpm preview` 未在 180s webServer 窗口内起服（`Timed out waiting 180000ms from config.webServer`）；Maven 结束后单独重跑 → `12 passed (3.5m)` rc=0。非代码缺陷。
- **D4（行尾纪律）**：本机 `core.autocrlf=true`，写入编辑器产生 bare LF，git add 时归一 CRLF（warning 属预期）；`git diff --numstat 0224ea4` 复核全为行级小 diff（如 index.page.vue 1/0、leaderboard 23/5、record-submit 56/2），无整文件重写残留。

## 8. 停止条件

白名单内实施与测试改动完成，验证命令全绿（后端 275 / 前端 type-check+32+build / e2e 12 passed），台账两件套落盘；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需推进 CI push、契约脚本 bash 实跑留证、真实连库验证推导落库与 COALESCE 窗口、前端次数目标输入限制，须新开任务，不在本任务扩大范围。
