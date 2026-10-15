# TASK-232: 个人统计 SQL 聚合下推与页面加载态解耦（MEDIUM 档）

> **开工基线**：af97413（工作树干净）
> **任务级别**：MEDIUM

## 1. 目标与背景

- **背景**：
  TASK-231 落地了服务端全量个人统计聚合端点 `GET /record/api/records/my/stats`，并彻底解决了前端 500 条截断问题。但在独立四块复核中暴露了两个性能与体验边界（复核 N-4 与 N-6）：
  1. **N-4 容量开销**：`SportRecordService.myStats(userId)` 采用全表投影查询拉取用户全部记录到 JVM 内存做汇总；且 `sport_record` 虽有 `idx_user_time (user_id, created_at)` 索引，但非覆盖索引导致全行回表。单用户十万级记录时会有较大内存与网络开销；
  2. **N-6 加载态耦合与翻页重复触发**：前端 `index.page.vue` 的 `loadData()` 把分页列表查询与统计、连续打卡、目标混在一个函数内，并由 `loading` 闸门整体包裹；导致翻页或修改过滤条件时重复调用 `getMyStats()` 等接口，且表格与统计卡同时被清屏闪烁为「加载中…」。
- **目标**：
  1. 服务端将全量统计（总数、总里程、通过数）下推至 SQL 单行聚合，近 30 天每日里程增加 `created_at >= windowStart` 时间窗条件充分利用复合索引，杜绝全量历史记录加载；
  2. 前端拆分列表与统计的加载链路，翻页与筛选时仅拉取列表，消除重复聚合；将表格与统计卡的 loading 状态解耦，避免整页闪烁与统计延迟拖垮表格展示。

## 2. 修改白名单

严格限定在以下实施与测试文件内：
- 后端：
  - `record-service/src/main/java/com/sportverify/record/mapper/SportRecordMapper.java`（新增 SQL 聚合查询声明）
  - `record-service/src/main/java/com/sportverify/record/dto/RecordAggDTO.java`（可选，新增单行聚合结果 DTO，或使用内部类/Map）
  - `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（实现下推与窗口查询）
  - `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`（单测适配与补强）
- 前端：
  - `web/src/pages/index.page.vue`（解耦 loadRecords 与 loadStats，解耦 loading）
- 台账文件：
  - `work/mailbox/tasks/TASK-232/spec.md`
  - `work/mailbox/tasks/TASK-232/handoff.md`

## 3. 核心设计与红线要求

- **服务端下推与索引利用**：
  - 全量指标下推：
    - `totalCount = COUNT(*)`
    - `totalDistance = COALESCE(SUM(distance), 0)`
    - `passCount = COUNT(CASE WHEN status IN (2, 5) THEN 1 ELSE NULL END)`
    - 仅以 `WHERE user_id = #{userId}` 为条件，数据库端单行返回；
  - 30 天窗口收窄：
    - `dailyDistances` 仅查询 `user_id = #{userId} AND created_at >= #{windowStartDateTime}`（利用 `idx_user_time` 复合索引）；
    - 仅对近 30 天内的记录在内存中按日汇总并补齐 0.00；
  - 契约兼容：`MyStatsDTO` 结构、字段名、保留小数位（里程 2 位、通过率 1 位百分比、无记录 0%）与 30 键日期升序完全一致。
- **前端解耦体验**：
  - 拆分 `loadRecords()` 与 `loadStats()`：
    - 页面初次加载（`onMounted`）或删除记录（`handleDelete`）后：调用全量加载；
    - 翻页（`onPageChange`）或过滤变更（`onFilterChange`, `resetFilters`）：仅调用 `loadRecords()`；
  - 加载态解耦：表格使用独立 loading 状态（如 `tableLoading`），统计卡片与打卡不因翻页而闪烁「加载中…」。
- **红线要求**：
  - 严禁触碰 `work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md`；
  - 严禁修改其他服务的代码或配置；
  - 保持现有所有既有单测 100% 通过不回归。

## 4. 验证命令

- 后端单测：`bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test`
- 前端验证：`pnpm --dir web type-check && pnpm --dir web test && pnpm --dir web build`
- 契约门禁：`bash scripts/verify/mailbox-contract.sh --open=TASK-232 --baseline=af97413`

---

### 回填（2026-10-15，C-02 台账笔）

**判别式红绿**

- R1 全量指标 SQL 单行聚合：**绿**。`selectAggByUserId(userId, {PASSED(2), RE_PASSED(5)})` 单行返回 `COUNT(*)` / `COALESCE(SUM(distance),0)` / `COUNT(CASE WHEN status IN (…) THEN 1 END)`；`verify(never()).selectList()` 断言 myStats 路径已无全量行拉取。
- R2 30 天窗口收窄：**绿**。`selectDailyDistanceSince(userId, today−29 00:00)`，`WHERE user_id=? AND created_at>=?` 命中 `idx_user_time` 键序；日粒度恒 30 键升序、无记录补 0.00、窗口外不入日粒度（仍计入总量）、`createdAt=null` 行跳过。
- R3 向后兼容与空态：**绿**。`MyStatsDTO` 零 diff；无记录 → `0 / 0.00 / 0 / 0%`；聚合行缺失 → 零值兜底不抛 NPE（新增用例）。旧断言（键序/累加/补 0/一位小数百分比）原样保留通过。
- R4 前端列表/统计加载解耦：**绿**。翻页/筛选调用点收敛为 `loadRecords()`；`loadStats`/`loadStreak`/`loadGoals` 仅由首挂载与删除后的 `loadData()` 触发；表格独立 `tableLoading`，统计卡与打卡不因翻页闪「加载中…」；统计失败改为 `statsError` 软提示（保留上一份统计）。
- R5 红线：**绿**。`api/client.ts`（`getMyStats` 对外契约）、`MyStatsDTO`、pom、`package.json` 零 diff；`PLAN.md` / `HANDOVER.md` 零触碰；零新依赖、零新配置。

**门禁留证**

- `bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` → **rc=0**：`SportRecordServiceTest` 80 通过、record-service 合计 **262 通过 / 0 失败 / 0 错误**、`BUILD SUCCESS`。
- `pnpm --dir web type-check` → rc=0；`pnpm --dir web test` → `Test Files 4 passed / Tests 32 passed`；`pnpm --dir web build` → `✓ built in 37.22s`（temp 重定向后 exit=0）。
- `bash scripts/verify/mailbox-contract.sh --open=TASK-232 --baseline=af97413` → 判据 A（两件套齐）/ 判据 B（只改清单与实际改动集一致，恰 7 文件）通过，rc=0。

**偏差**

- D1：C-02 自指哈希不入本文件，以 `git log` 为准；本任务只记 C-01 = `ceec5c6`。
- D2：首轮后端全量跑 `TimingHikariDataSourceWiringTest` 1 例计时红（1075ms > 1000ms 判别线，用例本体的 `assertTrue(String,long)` 重载使判别语义失效），该用例只依赖本任务零 diff 的既有类；复跑 262 全绿，判为偶发，未改该文件。
- D3：`web build` 首次因 `%TEMP%` 下 esbuild 临时目录删除被拒失败，重定向 `TMP/TEMP` 至 `.trae/tmp` 后通过（沿 TASK-224/231 先例）。

**未覆盖清单**

- 真实 MySQL / ShardingSphere 代理链路未联调，未做 EXPLAIN；`total_count`/`total_distance`/`pass_count` 三列别名到 `RecordAggDTO` 的映射与 `COALESCE(SUM(distance),0)` 的 DECIMAL 精度未经真实驱动验证（单测为 Mockito）。
- 注解 SQL 的 `<script>` + `<foreach>` 未在 MyBatis `SqlSource` 构建期验证（仅编译期字符串）。
- 真实浏览器端到端与 e2e（`web/e2e/` 未 mock `/my/stats`、未实跑）未覆盖；`--mode=online` / `--static` / CI 未跑。
- 时区口径沿用服务器时钟，跨时区错位为既有问题，未处理。

**停止条件**

- 白名单内实施与测试改动完成，验证命令全绿，台账两件套落盘；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需真连库执行计划验证与大用户量压测，须新开任务，不在本任务扩大范围。
