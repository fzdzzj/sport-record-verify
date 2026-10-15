# TASK-234: 原有功能健壮化——物理合理性推导、榜单周期联调与目标边界防御（MEDIUM 小档）

> **开工基线**：0224ea4（工作树干净）
> **任务级别**：MEDIUM（原有功能健壮化：合理性、边界条件、数据完整性与前后端闭环）

## 1. 目标与背景

- **背景**：既有主链路的几处「破窗」与死逻辑：
  1. **库内关键字段 NULL 破窗**：`SportRecordService.submit` 对 `startTime / endTime / duration / distance` 直接透传入参，客户端缺省时落库 NULL——后续按 `start_time` 的周期归属（目标达成量、列表过滤）全部失真；
  2. **物理合理性缺失**：提交时间倒流（`startTime > endTime`）与未来时间未设防；
  3. **榜单周期查询死逻辑**：TASK-209 引入「今日/本周/本月」`lbRange` 时，查询分支误写在封闭联合 `lbType`（`'overall'|'friend'`）上——恒真，`getLeaderboardRange` 分支成为不可达死代码，周期选择完全不生效；
  4. **目标边界未收紧**：`GoalService.upsertGoal` 仅校验「目标值 > 0」，次数目标可填小数（4.5 次无物理意义）、目标值无上限；
  5. **目标周期归属错位**：`queryPeriodRecords` 按 `created_at` 过滤周期窗口，离线补传运动（补传当日已过周期窗口）被误归入错误周期；
  6. **前端展示缺口**：`index.page.vue` 只渲染「仅自己/好友可见」两种可见性标签，`visibility === 0`（公开，默认态）无标签。
- **目标**：
  1. 提交链路增加物理合理性校验（时间不得倒流、startTime 不得为未来），并对缺失关键字段做服务端轨迹推导（首末点毫秒 → 时间/时长，相邻点 Haversine → 距离），根除 NULL 破窗；提交页样例轨迹同步带齐推导字段；
  2. 修复榜单周期查询分支，使「今日/本周/本月」真实调起 `getLeaderboardRange` 并与总榜/好友榜协调；
  3. 目标 upsert 收紧：次数目标强制正整数（无小数位）、目标值 ≤ 10000 上限；周期达成量优先按 `start_time` 归属（空则回退 `created_at`）；
  4. 补齐 `visibility === 0` 的「公开」标签渲染。

## 2. 修改白名单

严格限定在以下 9 个文件内：

- 后端服务（`record-service` src/main）：
  - `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`
  - `record-service/src/main/java/com/sportverify/record/service/GoalService.java`
- 后端单测：
  - `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`
  - `record-service/src/test/java/com/sportverify/record/service/GoalServiceTest.java`
- 前端页面：
  - `web/src/pages/record-submit.page.vue`
  - `web/src/pages/leaderboard.page.vue`
  - `web/src/pages/index.page.vue`
- 台账两件套：
  - `work/mailbox/tasks/TASK-234/spec.md`
  - `work/mailbox/tasks/TASK-234/handoff.md`

> 红线：`api/`、`verify-service`、`web/src/api/client.ts`、pom、`package.json`、`playwright.config.ts`、
> `work/mailbox/PLAN.md` / `work/mailbox/HANDOVER.md` 及白名单外一切文件零触碰；零新依赖。

## 3. 核心设计与红线要求

### 3.1 提交合理性校验与轨迹推导（SportRecordService.submit）

- **推导（缺省补全）**：`startTime / endTime / duration / distance` 任一未显式提供且 `points` 非空时，服务端整体推导——
  - `startTime / endTime` = 首/末点 `ts`（毫秒）转 `LocalDateTime`，**UTC 墙钟**口径（与 `scripts/perf/GenSamples.java`、前端 `toISOString` 同口径，跨时区确定、测试可复现）；
  - `duration` = `(末点 ts - 首点 ts) / 1000`（秒，整数截断）；
  - `distance` = 相邻点 Haversine 逐段累加（地球半径 `6371000` m，与 verify-service `GeoUtils` 同公式同常数），换算公里后 `setScale(2, HALF_UP)`（对齐 `sport_record.distance DECIMAL(8,2)` 存储精度）。
  - 显式提供的字段原样保留，不被推导覆盖（逐字段补全）。
- **合理性校验**：推导/显式后的 `startTime` 与 `endTime` 均存在时不得倒流（`IllegalArgumentException "开始时间不能晚于结束时间"`）；`startTime` 不得为未来时间（`"开始时间不能为未来时间"`）——显式入参按服务器本地墙钟比对（客户端同帧假设），推导值按 UTC 墙钟比对（推导帧自洽）。
- **异常类型**：沿用 `GoalService`/`pageMyRecords` 先例抛 `IllegalArgumentException`（控制器全局映射 400 PARAM），不新增 ResultCode、不动 api 契约。

### 3.2 提交页样例轨迹带齐推导字段（record-submit.page.vue）

- `loadSample()` 同步计算并展示：`startTime/endTime`（`YYYY-MM-DDTHH:mm:ss`，UTC）、`duration`（首末 ts 差/1000）、`distance`（前端 Haversine 累加，两位小数）；提交 DTO 经 `as RecordSubmitDTO` 断言带齐四字段（`client.ts` 契约类型不在白名单，不做类型面改动）。

### 3.3 榜单周期查询死逻辑修复（leaderboard.page.vue）

- 根因：分支 `if (lbType === 'overall' || lbType === 'friend')` 对封闭联合恒真，`getLeaderboardRange` 不可达。
- 修复：新增 `lbMode`（`'range'|'zset'`），总榜/好友榜按钮点选置 `'zset'`（走 `getLeaderboard`），今日/本周/本月按钮点选置 `'range'`（走 `getLeaderboardRange(rangeStart(), size)`）——「最后一次点选的控制项获胜」，两组按钮均真实生效、无缝协调；默认 `'range'`（今日）。

### 3.4 目标边界与整数性收紧（GoalService）

- `validateGoalParams` 追加：目标值 `> 10000` 拒绝（`"目标值不能超过10000"`，距离/次数同口径）；`metric == 1`（次数）时 `targetValue.stripTrailingZeros().scale() > 0` 拒绝（`"次数目标必须为正整数（无小数位）"`）——尾零小数（如 `8.00`）归一后视作整数放行（与既有测试 `8.00`/`4.00` 兼容）。
- `queryPeriodRecords` 周期窗口过滤由 `created_at` 改为 `COALESCE(start_time, created_at)`（`apply` 原生 SQL 片段，上下界同口径），离线补传运动按运动时间归属周期，存量行 `start_time` 为空时回退 `created_at`。

### 3.5 公开标签渲染（index.page.vue）

- 可见性列补充 `record.visibility === 0` → `<a-tag color="green">公开</a-tag>`（置于「仅自己/好友可见」之前）。

### 3.6 红线要求

- 生产代码只动白名单两个服务类与三个页面；测试只动白名单两个测试类；台账只动两件套。
- 后端 Maven 单测 262+ 全绿；前端 `type-check` / `pnpm test` / `pnpm build` / `pnpm e2e` 12 passed 全绿。

## 4. 验证命令

- 后端单测：`bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test`
  （本机 bash 不可用时的等价直调：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service -am`，见 handoff D 偏差）
- 前端单测与构建：`pnpm --dir web type-check && pnpm --dir web test && pnpm --dir web build`（Windows 下 `TMP/TEMP` 重定向至仓内 `.trae/tmp`，沿 TASK-224/231/232/233 同款处置）
- 前端 E2E：`pnpm --dir web e2e`（同上 TMP/TEMP 重定向）
- 契约门禁：`bash scripts/verify/mailbox-contract.sh --open=TASK-234 --baseline=0224ea4`（判据 A/B 判读见 handoff §7）

---

### 回填（2026-10-15，C-02 台账笔）

**判别式红绿**

- R1 提交合理性校验：**绿**。显式 `startTime > endTime` → `IllegalArgumentException("开始时间不能晚于结束时间")`；显式/推导 `startTime` 为未来 → `IllegalArgumentException("开始时间不能为未来时间")`；轨迹首末点 ts 倒流经推导后命中倒流拒绝；需推导但首末点缺 ts → 明确拒绝。均 `never insert` 断言。
- R2 轨迹推导：**绿**。四字段全缺 + 轨迹非空 → 落库 `startTime/endTime`（首末点 ts，UTC 墙钟）、`duration`（毫秒差/1000）、`distance`（相邻点 Haversine 累加，R=6371000 m，公里保留两位：赤道 0.001° → 0.11 km）；部分缺失只补缺失项、全显式原样落库（三态全测）。
- R3 榜单周期分支修复：**绿**。`leaderboard.page.vue` 分支由恒真 `lbType` 改为 `lbMode`（最后一次点选获胜）：今日/本周/本月 → `getLeaderboardRange`，总榜/好友榜 → `getLeaderboard`；type-check 0 错误。
- R4 目标边界收紧：**绿**。`metric=1` + `4.50` 拒绝（次数须正整数）；`10000.01` 拒绝（≤10000 上限）；`10000` 恰可、`8.00` 尾零小数归一后放行（既有测试兼容）。
- R5 周期归属修正：**绿**。`queryPeriodRecords` 窗口条件 = `COALESCE(start_time, created_at)`（captor 断言 SQL 与上下界参数值恰为 ISO 周窗口）；离线补传记录按 startTime 计入本周、startTime 空按 createdAt 归属。
- R6 公开标签与红线：**绿**。`index.page.vue` 补 `visibility===0` 绿色「公开」标签；白名单外零 diff、零新依赖。

**门禁留证**

- 后端：`mvn -B -ntp -o -s .mvn-settings.xml clean test -pl record-service -am`（= mvn-verify.sh offline 等价拼写，bash 不可用见 handoff §7 D1）→ **BUILD SUCCESS**：`Tests run: 275, Failures: 0, Errors: 0, Skipped: 0`（基线 262 → +13）。
- 前端：`pnpm --dir web type-check` → rc=0；`pnpm --dir web test` → `Tests 32 passed`；`pnpm --dir web build` → rc=0（`✓ built in 55.05s`，TMP/TEMP 重定向 `.trae/tmp`）。
- 前端 E2E：`pnpm --dir web e2e`（TMP/TEMP 重定向）→ **`12 passed (3.5m)`，rc=0**。
- 契约门禁：`bash scripts/verify/mailbox-contract.sh --open=TASK-234 --baseline=0224ea4` 本机 bash 不可执行 → 按脚本口径人工核算：判据 A 两件套齐全；判据 B 实际改动集（`git diff --name-only 0224ea4` + untracked）恰与 handoff §2 只改清单 9 文件一致。

**偏差**

- D1：bash 不可用（WSL vhdx 缺失、无 Git Bash）→ Maven 等价直调、契约脚本人工核算；非代码缺陷。
- D2：pwsh 管道包裹后任务退出码假阴性，判读以 Maven 日志 `BUILD SUCCESS` / `Tests run: 275` 为准。
- D3：E2E 首轮与 Maven 并行致 webServer 180s 超时；单独重跑 12 passed。
- D4：行尾纪律按 `autocrlf` 归一，`git diff --numstat` 复核为行级小 diff，无整文件重写。

**未覆盖清单**

- `--mode=online` / `--static` / CI 未跑（未 push、未建 PR）；契约脚本 bash 实跑留证未做（本机无 bash）。
- 真实 MySQL / ShardingSphere 代理链路未联调：推导/COALESCE 窗口/DECIMAL(8,2) 落库精度未在真实数据源验证。
- 前端推导仅覆盖内置样例轨迹；任意自定义轨迹由服务端推导兜底，两端公式未逐点对拍。
- 次数目标整数性仅在服务层拦截（`GoalUpsertDTO` 注解白名单外未动），前端弹窗未加输入限制。
- 未来时间校验的显式入参依赖客户端/服务器同帧假设（LocalDateTime 无时区契约的固有边界）。

**停止条件**

- 白名单内实施与测试改动完成，验证命令全绿（后端 275 / 前端 type-check+32+build / e2e 12 passed），台账两件套落盘；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需 CI push、契约脚本 bash 实跑、真实连库验证、前端次数输入限制，须新开任务。

