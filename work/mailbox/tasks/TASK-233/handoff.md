# TASK-233 handoff：测试稳定性强化与 Web E2E 路由契约对齐（MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-233`（契约：`work/mailbox/tasks/TASK-233/spec.md`；本文件为回传）。
- 分级：**MEDIUM**（纯测试/台账面：后端计时断言容差 + 前端 E2E 路由 mock 补齐 + strict mode 定位器修正，零生产代码）。
- 开工基线：`60aee22`（工作树干净；开工前仅本任务 `spec.md` 未跟踪）。
- C-01 实施笔：`5e675e1`（`test: 测试稳定性强化与 Web E2E 路由契约对齐（TASK-233）`，恰 5 文件全为白名单测试文件，`5 files changed, 75 insertions(+), 8 deletions(-)`）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/224/231/232 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI）待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 60aee22，恰 7 文件）

C-01（`test` 实施笔，恰 5 文件 = 白名单实施面全等）：
- record-service/src/test/java/com/sportverify/record/config/TimingHikariDataSourceWiringTest.java
- web/e2e/helpers.ts
- web/e2e/fixtures/records.ts
- web/e2e/record-list.e2e.ts
- web/e2e/record-submit.e2e.ts

C-02（`docs(mailbox)` 台账笔，恰 2 文件）：
- work/mailbox/tasks/TASK-233/spec.md
- work/mailbox/tasks/TASK-233/handoff.md

> 白名单外零触碰：`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 未触碰、零改动；全部生产代码（record-service `src/main` 及各服务 src/main 与 `web/src`）零 diff；`scripts/`、`sql/`、pom、`package.json`、`web/e2e/playwright.config.ts` 均零 diff。临时验证日志落 gitignored `.trae/tmp/`，不入库。

## 3. 一句话结论

三处测试/基础设施层的既有测试债被一次性清除：① 后端 `TimingHikariDataSourceWiringTest.repeatedClose_withPendingConnectionWait_...` 的 `firstCloseMs`/`doubleCloseMs` 硬编码墙钟断言由 `<1000ms` 放宽至 `<3000ms`——原阻塞缺陷（close 被 30s 池内连接等待拖住）远超 3000ms 判别线，容差完全保留反阻塞缺陷的识别力，仅消除宿主/CI 高负载调度偶发越线（此前曾出现 1012ms / 1075ms 仅越线约 1~7.5% 的红）；② `web/e2e/helpers.ts` 在 `handleRecord` 中补齐 `GET /record/api/records/my/stats` 的 route-mock（此前落入 404 兜底，前端回退 0 值致统计卡断言失败），数据与 25 条夹具全量对齐：`totalCount=25`、`totalDistance=25 条累加 194.00`、`passCount=6`（status∈{2,5}，i%8 命中 {2,10,18}∨{5,13,21}）、`passRate='24.0%'`（6/25）、`dailyDistances` 为以 2026-10-10 为尾的恒 30 键 YYYY-MM-DD 升序 Map；③ `record-list.e2e.ts` 统计卡断言改与 `/my/stats` 全量聚合对齐：总里程用 `totalDistanceSum()`（25 条 194.00）、通过率 `24.0%（非第 1 页 20 条的 25.0%）`；`record-submit.e2e.ts` 把 `getByText('recordId: 9001')` 限定在 `.ant-alert` 容器内（`page.locator('.ant-alert').getByText(...)`），消除 Alert 详情插槽与历史会话列表同文案导致的 strict mode violation。生产代码零触碰、零新依赖、无对外契约变更。

## 4. 判别式与验收要点

- **R1 后端计时容差（绿）**：`TimingHikariDataSourceWiringTest` 中 `assertTrue(firstCloseMs < 3000, ...)` 与 `assertTrue(doubleCloseMs < 3000, ...)`；判别语义不变（close 不得持包装类生命周期锁等待数据库连接），仅完成时限上抬到 3000ms。全量 262 测通过、`BUILD SUCCESS`。
- **R2 `/my/stats` mock 契约对齐（绿）**：`handleRecord` 新增分支命中 `p === '/record/api/records/my/stats' && method === 'GET'`，`route.fulfill(ok(myStats()))`；不再落入 404 兜底。`myStats()` 由夹具推导：25 条中 status=2|5 恰 6 条 → 通过率 `24.0%`；`dailyDistances` 恒 30 键（2026-10-10 前溯 30 个自然日，无记录日补 0），与后端 30 天窗口契约同构。
- **R3 统计卡断言对齐（绿）**：`record-list.e2e.ts` 记录数 `25`、总里程 `totalDistanceSum().toFixed(2)`=`194.00`、通过率 `24.0%`、连续打卡 `当前 5 天 · 最长 12 天` 全部可见；表格 20 行、分页器可见、`assertNoUnhandled(ctx)` 零两前缀外请求。
- **R4 strict-mode 修正（绿）**：`record-submit.e2e.ts` 的 `recordId: 9001` 断言改为 `page.locator('.ant-alert').getByText('recordId: 9001')`，限定在 Alert 容器内；提交结果 message 槽断言保留。E2E 12 tests 全绿无 strict mode violation。
- **R5 红线（绿）**：生产代码（全部 src/main 与 `web/src`）零 diff；`api/client.ts`、`MyStatsDTO`、pom、`package.json`、`playwright.config.ts` 零 diff；`PLAN.md` / `HANDOVER.md` 零触碰；零新依赖。

## 5. 验证命令与输出片段

1. **后端单测**：`bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` → **BUILD SUCCESS**（输出落 `.trae/tmp/mvn-verify-233.log`）：
   - `[INFO] Tests run: 262, Failures: 0, Errors: 0, Skipped: 0` / `[INFO] Results:` / `[INFO] BUILD SUCCESS`（reactor：parent/common/api/record-service 4 模块全 SUCCESS；record-service 2:52min）。
   - TASK-232 首轮偶发红的 `TimingHikariDataSourceWiringTest` 本任务已按契约放宽容差，本轮全绿、无计时抖动命中。
2. **前端 type-check**：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，0 错误）。
3. **前端单测**：`pnpm --dir web test` → rc=0：`Test Files 4 passed (4)` / `Tests 32 passed (32)`（vitest，17.25s）。
4. **前端构建**：`pnpm --dir web build` → rc=0（`✓ 3317 modules transformed` / `✓ built in 18.29s`；chunk >500 kB 为既有非阻断 advisory）。`TMP/TEMP` 已重定向到仓内 `.trae/tmp`（沿 TASK-224/231/232 同款处置），未再遇 esbuild 临时目录删除被拒。
5. **前端 E2E 全量**：`$env:TMP/TEMP=...\.trae\tmp; pnpm --dir web e2e` → **`12 passed (47.2s)`，rc=0**（chromium 单 worker，vite preview 生产产物 + route-mock）：12 条全绿，含 `record-list.e2e.ts` 统计卡与 `record-submit.e2e.ts` 提交结果两条关键路径；无 strict mode violation。
6. **契约门**：`bash scripts/verify/mailbox-contract.sh --open=TASK-233 --baseline=60aee22` → 见 §4/§7 与 spec.md 回填的实时判读记录。

## 6. 未覆盖、遗留风险与不得推出的结论

- `--mode=online`、`--static`（Checkstyle/SpotBugs/PMD）、CI 全流程均未跑（未 push、未建 PR），外部门槛待指导侧推动后补记。
- **真实 MySQL / ShardingSphere 代理链路未联调**：`/my/stats` 的真实 SQL 聚合行为由 TASK-231/232 的服务端实现承载，本任务只对齐 E2E 侧 mock 契约（`MyStatsDTO` 字段口径），未在真实数据源上验证 `passRate` 格式化与 30 键窗口；本任务也不涉及生产代码，不得据此推断服务端性能/正确性。
- **风险残留：计时断言终归是墙钟敏感**：放宽到 `<3000ms` 消除的是「1s 级调度抖动」这一实测风险类别，但极端宿主暂停（GC 长停顿、整机冻结 >3s）仍可能越线。缺陷阻塞上限=池内等待窗口（C-03 订正后 `connectionTimeout=10000`，见 §9），3s 判别线仍有 ≥3× 余量；若未来 CI 稳定性要求更高，可改断言为「close 耗时 ≪ 内层空池等待超时（connectionTimeout=10000ms）的比例」或直接覆盖「内层池已关闭 + 等待线程以异常收场」这两个确定性断言（本用例 `assertTrue(inner.isClosed())` / `waiter.join(5s)` / `caught.get()!=null` 已承载），把墙钟只留给判别线。
- **`dailyDistances` mock 为静态归集**：夹具 25 条 `createdAt` 收缩在单日 2026-10-10 秒级递减，`myStats()` 的 `dailyDistances` 把这 25 条全部归到基准日键、其余 29 键补 0——满足「恒 30 键、升序窗口」契约，但与真实运动跨多日的分布不同；当前 E2E 不断言里程条数值，故不影响全绿。若后续要断言条形比例，须扩展夹具跨多日分布。
- **`pageOneDistanceSum()` 保留未删**：TASK-231 引入的「第 1 页 20 条总里程」辅助函数已不再被 `record-list.e2e.ts` 引用（改用全量 `totalDistanceSum()`），为保持夹具 API 稳定仍导出，属死代码但零风险；如需清理可新开小任务。
- **时区口径**：`dailyBars` 前端按浏览器本地日期取 key，主服务按服务器时钟聚合，跨时区错位为既有问题（TASK-231 已登记），本任务未改动。

## 7. 偏差登记

- **D1（自指哈希）**：C-02 台账笔哈希无法在本文件内自洽记录，本文件只记 C-01 = `5e675e1`；C-02 实际哈希以 `git log` 为准。
- **D2（后端壳命令退出码假阴性）**：`mvn-verify.sh` 经 PowerShell `Tee-Object | Select-Object` 管道包裹后 pwsh 侧 `$LASTEXITCODE` 因管道语义取到 `Select-Object` 的 0/非 Maven 码；判读以 Maven 日志 `BUILD SUCCESS` / `Tests run: 262`（reactor 4 模块全 SUCCESS）为准，非代码缺陷。后端实测全绿。
- **D3（e2e 内嵌二次 build）**：`pnpm --dir web e2e` 的 webServer 命令含 `pnpm build`，与本任务单独跑的 `pnpm --dir web build` 重复但无冲突（各自独立产物/临时目录），非问题。
- **D4（行尾纪律）**：本机 `core.autocrlf=true` 且仓内 `.gitattributes` 未对 `*.java`/`*.vue`/`*.ts` 做 eol 归一，写入编辑器曾产生 bare LF；实施笔前已将本次触碰文件经 git 归一 CRLF，`git show --numstat` 确认全为行级小 diff（5/2、57/0、6/1、4/3、3/2），无整文件重写残留。

## 8. 停止条件

白名单内实施与测试改动完成，验证命令全绿（后端 262 / 前端 type-check+32+build / e2e 12 passed），台账两件套落盘；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需推进 CI push、真实连库与更大日程分布的 dailyDistances 断言，须新开任务，不在本任务扩大范围。

## 9. 复核 BLOCKER-1 订正留痕（C-03 订正笔）

- **触发（独立复核）**：TASK-233 封盘后复核抓出 BLOCKER-1——`repeatedClose_withPendingConnectionWait_...` 用例第 129 行显式 `wrapper.setConnectionTimeout(2000)`，池内等待窗口 2s **小于** 3s 判别线：缺陷实现（取连接挪进包装类锁）的 close 阻塞上限恰为该窗口（等待线程 ≈2s 超时后释放锁），可被 `<3000ms` 断言放行，反死锁守卫判别力失效。
- **订正（C-03 笔，开工基线 d375b8e，任务级别 LIGHT）**：`TimingHikariDataSourceWiringTest.java:129` 由 `setConnectionTimeout(2000)` 调整为 `setConnectionTimeout(10000)`：
  - 正常健康 close 仅 <2ms，断言 `<3000ms` 零影响；
  - 缺陷发生时 close 被池内等待窗口拖住 ≈10s，被 3s 判别线精准拦截；
  - 判别余量 10s/3s ≈ 3.3×（恢复 ≥3 倍安全判别余量）。
  - 同步订正测试内两处注释（原「拖过 1s 判别线」「拖过 30s」表述与 2s 窗口事实不符，统一为「被 10s 窗口拖住、远超 3s 线」）。
  - **一致性联动（262 测实跑暴露）**：窗口上调后等待线程以池内等待窗口（10s）超时收场（close 不主动唤醒 borrower），原 `waiter.join(5s)`（2s 窗口时代口径）红；同步上调为 `waiter.join(15s)`（= 10s 窗口 + 5s 余量），断言消息同步改为「随池内等待窗口超时结束」。判别语义不变：反阻塞守卫仍由 `firstCloseMs/doubleCloseMs < 3000` 承载；用例时长由 ≈2s 升至 ≈11s。
- **台账同步**：本文件 §6 风险残留中陈旧事实（`connectionTimeout=2000ms` / 阻塞 30s / 10× 余量）已同步为「阻塞上限=池内等待窗口 10s / ≥3× 余量」；`spec.md` 回填段 R1 说明同步同口径。
- **改动集**：仅 3 个既有白名单文件（后端测试件 + 台账两件套），相对 C-03 基线 d375b8e 恰 3 文件；相对任务基线 60aee22 仍为白名单 7 文件全集，零新文件、零生产代码。
- **验证**：后端 Maven 262 测全绿（`BUILD SUCCESS`）；契约门禁 `bash scripts/verify/mailbox-contract.sh --open=TASK-233 --baseline=60aee22` → rc=0。
- **C-03 提交**：自指哈希沿 D1 先例不入本文件，以 `git log` 为准（提交信息 `test: 订正复核BLOCKER-1——连接等待窗口2000→10000恢复反死锁判别力（TASK-233）`），未 push、未建 PR。