# TASK-232 handoff：个人统计 SQL 聚合下推与页面加载态解耦（MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-232`（契约：`work/mailbox/tasks/TASK-232/spec.md`；本文件为回传）。
- 分级：**MEDIUM**（Mapper 聚合下推 + Service 窗口收窄 + 前端加载链路解耦 + 单测适配）。
- 开工基线：`af97413`（工作树干净；开工前仅本任务 `spec.md` 未跟踪）。
- C-01 实施笔：`ceec5c6`（`feat(record): 个人统计 SQL 聚合下推与首页加载态解耦（TASK-232）`，恰 5 文件 = 4 改 + 1 新增，`5 files changed, 306 insertions(+), 114 deletions(-)`）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/224/231 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI）待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 af97413，恰 7 文件）

C-01（`feat(record)` 实施笔，恰 5 文件 = 白名单实施面全等）：
- record-service/src/main/java/com/sportverify/record/mapper/SportRecordMapper.java
- record-service/src/main/java/com/sportverify/record/dto/RecordAggDTO.java
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
- web/src/pages/index.page.vue

C-02（`docs(mailbox)` 台账笔，恰 2 文件）：
- work/mailbox/tasks/TASK-232/spec.md
- work/mailbox/tasks/TASK-232/handoff.md

> 白名单外零触碰：`work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md` 未触碰、零改动；其余服务源码、父/子 pom、`scripts/`、`sql/`、`docker-compose*`、`web/package.json`、`web/vite.config.ts`、`web/src/api/client.ts`（`getMyStats` 契约不变故零改）、`web/e2e/` 均零 diff。临时验证日志落 gitignored `.trae/tmp/`，不入库。

## 3. 一句话结论

`SportRecordService.myStats` 的两段查询全部下推：全量指标（总数 / 总里程 / 通过数）由新增的 `SportRecordMapper.selectAggByUserId` **数据库单行聚合**（`COUNT(*)`、`COALESCE(SUM(distance),0)`、`COUNT(CASE WHEN status IN (2,5) THEN 1 END)`，状态码集由 `RecordStatus` 枚举传入而非 SQL 硬编码）算出，不再把该用户全部记录投影拉回 JVM（原实现为 `select(distance,status,createdAt)` 全量 `selectList`）；近 30 天日粒度改用新增的 `selectDailyDistanceSince(userId, windowStart)`，`WHERE user_id=? AND created_at>=?` 恰为 `idx_user_time (user_id, created_at)` 的完整键序、走范围扫描，只对窗口内两列投影做内存按日累加。`MyStatsDTO` 结构、字段名、保留位数（里程 2 位 / 通过率 1 位 / 无记录 `0%`）与 30 键升序窗口**逐字向后兼容**（旧口径断言全部保留并通过）。前端 `index.page.vue` 把原 `loadData()` 一体函数拆为 `loadRecords` / `loadStats` / `loadStreak` / `loadGoals`：翻页（`onPageChange`）与过滤（`onFilterChange` / `resetFilters`）只调 `loadRecords()`，不再重复打 `/my/stats`、`/my/streak`、`/my/goals`；表格改用独立 `tableLoading`，首屏 `loading` 闸门只由首挂载与删除后的全量加载持有，统计卡与打卡不再因翻页/筛选闪成「加载中…」。禁触文件零触碰、零新依赖、无对外契约变更。

## 4. 判别式与验收要点

- **R1 全量指标 SQL 单行聚合（绿）**：`SportRecordServiceTest.myStats_aggregatesAllRecordsAndDailyWindow` 以 `ArgumentCaptor<List<Integer>>` 断言 `selectAggByUserId(200L, {PASSED(2), RE_PASSED(5)})` 的入参**恰为归属人 + 通过终态码集**（枚举同源，防 SQL 硬编码漂移），并以 `verify(sportRecordMapper, never()).selectList(any())` 断言 myStats 路径**不再有全量行拉取**；样本总量 8 条（含窗口外 2 条、`distance=null`、REJECTED），实测 totalCount=8、totalDistance=22.00、passCount=5、passRate=`62.5%`（一位小数）。
- **R2 30 天窗口收窄（绿）**：同例以 `ArgumentCaptor<LocalDateTime>` 断言 `selectDailyDistanceSince(200L, today−29 00:00)`——窗口下界与 30 键窗口同源，`created_at >=` 由 SQL 承担；日粒度实测恒 30 键、升序、首键 today−29、末键 today、today 同日累加 7.00（3.5+1.5+2.0，含 REJECTED）、无记录日 `0.00`、窗口外日期不入日粒度；另补一条 `createdAt=null` 的投影行验证服务层跳过（不抛 NPE）。
- **R3 向后兼容与空态（绿）**：`myStats_emptyReturnsZeroStatsWithFullDailyWindow` 实测无记录 → 0 / 0.00 / 0 / `0%`（非 `0.0%`）且日粒度仍 30 键全 0.00；新增 `myStats_nullAggregateRowFallsBackToZero` 覆盖聚合行缺失（驱动返回 null）时零值兜底不抛 NPE。`MyStatsDTO` 未改动（零 diff），旧断言（键序、累加、补 0、格式化）全部原样保留并通过。
- **R4 前端列表/统计解耦（绿）**：`index.page.vue` 中 `onFilterChange` / `resetFilters` / `onPageChange` 三处调用点由 `loadData()` 改为 `loadRecords()`；`Promise.all([loadRecords, loadStats, loadStreak, loadGoals])` 仅由 `loadData()`（`onMounted` 与 `handleDelete` 调用）聚合；统计失败改为保留上一份统计 + `statsError` 软提示（不再整页 `loading` 覆盖）；表格区独立 `tableLoading`（无数据时占位「加载中…」，翻页时仅表格下方「列表刷新中…」）；`fetchRecordsPage` / `applyRecordsPage` 抽公共分页取数与排序，并对「过滤后当前页越界」做一次回退重查（顺带修掉既有边界）。
- **R5 红线（绿）**：`web/src/api/client.ts`、`MyStatsDTO`、父/子 pom、`package.json` 零 diff（零新依赖、零契约变更）；未触碰 `PLAN.md` / `HANDOVER.md`；新增 Mapper 方法为 `@Select` 注解式，无 XML、无新配置项。

## 5. 验证命令与输出片段

1. **后端单测**：`bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` → **rc=0**（输出落 `.trae/tmp/mvn-verify-232.log`）：
   - `[INFO] Tests run: 80, Failures: 0, Errors: 0, Skipped: 0 -- in com.sportverify.record.service.SportRecordServiceTest`（其中 myStats 三例 0.003s / 0.006s / 0.002s，无 failure 节点）
   - `[INFO] Tests run: 262, Failures: 0, Errors: 0, Skipped: 0` / `[INFO] BUILD SUCCESS`
   - 净增 1 测（`myStats_nullAggregateRowFallsBackToZero`），既有用例零改动全绿。
2. **首轮红与判读（留证，非本任务代码缺陷）**：首次全量跑出现 1 例红 `TimingHikariDataSourceWiringTest.repeatedClose_withPendingConnectionWait_isIdempotent_andNotBlockedByPoolWait:152`（`close 被等待线程的池内等待拖住 1075ms`，判别线 1000ms；该用例的 `firstCloseMs`/`doubleCloseMs` 用 long 字面量 1000 传入 JUnit `assertTrue`，实际解析为 `assertTrue(String, long)` 重载 → `equals` 比较，判别语义本身不成立）。该用例只依赖 `TimingHikariDataSource.close()`（本任务零 diff 的既有类），与本任务改动的 Mapper/Service/myStats 路径无交集；**同命令复跑即 rc=0、262 全绿**，判为既有计时敏感用例在负载下的偶发红，未改动该文件。
3. **前端 type-check**：`pnpm --dir web type-check` → rc=0（`vue-tsc --noEmit -p tsconfig.app.json`，0 错误）。
4. **前端单测**：`pnpm --dir web test` → rc=0（`Test Files 4 passed (4)` / `Tests 32 passed (32)`）。
5. **前端构建**：`pnpm --dir web build` → 成功（`✓ 3317 modules transformed` / `✓ built in 37.22s`；chunk >500 kB 为既有非阻断 advisory）。**首次构建失败非代码问题**：`vite:esbuild-transpile` 删除 `%LOCALAPPDATA%\Temp\esbuild-*` 被拒（`Access is denied`），把 `TMP/TEMP` 重定向到仓内 gitignored `.trae/tmp` 后复跑即成功并 `exit=0`（沿 TASK-224/231 同款处置）。
6. **契约门**：`bash scripts/verify/mailbox-contract.sh --open=TASK-232 --baseline=af97413` → 见 §4/§7 与 spec.md 回填的实时判读记录。

## 6. 未覆盖、遗留风险与不得推出的结论

- `--mode=online`、`--static`（Checkstyle/SpotBugs/PMD）、CI 全流程均未跑（未 push、未建 PR），外部门槛待指导侧推动后补记。
- **真实 MySQL 未联调、未做 EXPLAIN 实测**：`idx_user_time (user_id, created_at)` 的命中和「单行聚合不回表」由 SQL 形态（WHERE 仅 `user_id`、窗口查询 `user_id + created_at` 完整键序）与代码审查承载，**不得据此推断执行计划已实测优化**；`COUNT(CASE WHEN ...)` 在 MySQL 8 为全索引/全表扫描聚合，单用户十万级时的实际耗时未压测。
- **`COUNT(*)`/`SUM` 返回类型依赖驱动映射**：`RecordAggDTO.totalCount`/`passCount` 声明为 `Long`、`totalDistance` 为 `BigDecimal`，MyBatis 依 `map-underscore-to-camel-case=true`（既有配置）映射 `total_count`/`pass_count`/`total_distance`；未在真实 MySQL/ShardingSphere 代理链路上验证列别名映射与 `COALESCE(SUM(distance),0)` 的 DECIMAL 精度（单测为 mock，不经过 MyBatis 语句解析）。**首次真连库时须重点复核这三列映射**。
- **XML `<script>` + `<foreach>` 注解语句未在真实 MyBatis 上解析过**：单测为纯 Mockito，注解 SQL 的语法正确性只在编译期（字符串层面）成立，未经 MyBatis `SqlSource` 构建验证；`passStatus` 集合恒非空（`PASS_STATUS_CODES` 常量），空集合分支不会出现但未加防御断言。
- **真实浏览器端到端未跑**：核心验收行为「翻到第 2 页 / 改筛选时统计卡与 30 天里程不变且不闪『加载中…』」由代码审查（调用点收敛到 `loadRecords`）承载，无组件级（vitest）或 e2e 断言，**不得推断真实浏览器交互已验收**。
- **e2e 面未同步（本任务白名单外，未跑）**：`web/e2e/helpers.ts` 未 mock `GET /record/api/records/my/stats`（落到 404 兜底 → 统计软提示 + 卡面 0 值），`web/e2e/record-list.e2e.ts` 只断言「记录数」标签可见（不比数值），故按代码推断不会红；但 `web/e2e/` 未执行、CI 的 web 段只跑 `type-check/test/build` 不含 e2e，**不得据门禁全绿推断 e2e 面完好**。
- **时区口径不变**：`dailyDistances` 窗口右端仍取服务器时钟 `LocalDate.now()`，前端按浏览器本地日期取 key，跨时区错位为既有问题（TASK-231 已登记），本任务未改动。
- **行尾纪律**：本机 `core.autocrlf=true` 且仓内 `.gitattributes` 未对 `*.java`/`*.vue` 做 eol 归一，写入工具曾产生 bare LF；实施笔前已把本次触碰的 5 个文件逐一归一为 CRLF（与基线工作树一致），`git diff --numstat` 为行级小 diff（无整文件重写）。

## 7. 偏差登记

- **D1（自指哈希）**：C-02 台账笔哈希无法在本文件内自洽记录，本文件只记 C-01 = `ceec5c6`；C-02 实际哈希以 `git log` 为准。
- **D2（首轮后端偶发红）**：见 §5.2，`TimingHikariDataSourceWiringTest` 首跑 1 例红、复跑全绿，判为既有计时敏感用例偶发；未修改该测试文件（不在白名单），如实登记不掩盖。
- **D3（构建环境绕行）**：`pnpm --dir web build` 首次因 `%TEMP%` 下 esbuild 临时目录删除被拒而失败，重定向 `TMP/TEMP` 后通过；非代码缺陷，沿 TASK-224/231 先例登记。
- **D4（工具自伤与复原）**：实施过程中一次基于行号的文件拼接脚本误伤 `SportRecordServiceTest.java` 与 `SportRecordService.java`（编码/拼接错位），已 `git checkout` 复原后改用唯一文本锚点重做；最终改动经 `git diff --numstat` 与花括号计数复核为行级小 diff（`+72/−28` 与 `+22/−22`），无残留损坏。
