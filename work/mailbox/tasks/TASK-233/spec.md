# TASK-233: 测试稳定性强化与 Web E2E 路由契约对齐（MEDIUM 小档）

> **开工基线**：60aee22（工作树干净）
> **任务级别**：MEDIUM（纯测试面，消除测试债与对齐 E2E 契约）

## 1. 目标与背景

- **背景**：
  近期任务实施与复核中揭示了三处纯测试/基础设施层面的契约失配与稳定性隐患：
  1. **后端墙钟断言敏感（TASK-232 复核 D1）**：`TimingHikariDataSourceWiringTest` 中的 `firstCloseMs < 1000` 与 `doubleCloseMs < 1000` 为硬编码墙钟断言。该测试旨在验证 close 不会持锁被等待 30 秒的物理连接拖住；但在宿主或 CI 高负载全量跑时，线程调度可能偶发超过 1000ms（如 1012ms，仅越线 1.2%）导致构建偶发红；
  2. **E2E 路由 mock 缺失（TASK-231 复核 N-3）**：`web/e2e/helpers.ts` 未映射新增端点 `GET /record/api/records/my/stats`，落入 404 兜底，导致 `record-list.e2e.ts` 的统计卡断言因前端回退 0 值而失败；
  3. **E2E 严格模式元素撞车（TASK-229 插槽修复附带影响）**：TASK-229 修复了提交页 `a-alert` 详情插槽后，页面上 Alert 详情与历史会话列表中同时出现 `recordId: 9001`，触发 Playwright strict mode violation。
- **目标**：
  1. 后端：微调 `TimingHikariDataSourceWiringTest` 计时断言容差（放宽至合理非阻塞阈值，如 3000ms / 5000ms），彻底消除宿主与 CI 偶发抖动红风险，保留反阻塞语义；
  2. 前端 E2E：在 `helpers.ts` 中补齐 `GET /record/api/records/my/stats` 路由 mock（与 25 条夹具对齐），同步更新 `record-list.e2e.ts` 的全量统计断言；
  3. 前端 E2E：修正 `record-submit.e2e.ts` 的 Alert 定位器（限定在 `.ant-alert` 范围内），消除 strict mode violation，使整个 Playwright E2E 套件在本地实跑达到 100% 全绿。

## 2. 修改白名单

严格限定在以下 5 个测试文件内：
- 后端测试：
  - `record-service/src/test/java/com/sportverify/record/config/TimingHikariDataSourceWiringTest.java`
- 前端 E2E 测试：
  - `web/e2e/helpers.ts`
  - `web/e2e/fixtures/records.ts`
  - `web/e2e/record-list.e2e.ts`
  - `web/e2e/record-submit.e2e.ts`
- 台账文件：
  - `work/mailbox/tasks/TASK-233/spec.md`
  - `work/mailbox/tasks/TASK-233/handoff.md`

## 3. 核心设计与红线要求

- **后端计时测试调整**：
  - 将 `TimingHikariDataSourceWiringTest` 中 `firstCloseMs < 1000` 与 `doubleCloseMs < 1000` 的断言放宽至 `3000` 或 `5000`（原阻塞缺陷为阻塞 30 秒，3000ms 完全保留对缺陷的识别力，同时消除调度抖动）；
- **前端 E2E mock 与断言**：
  - 在 `web/e2e/helpers.ts` 中针对 `GET /record/api/records/my/stats` 返回对齐数据：
    - `totalCount`: 25
    - `totalDistance`: 25 条记录的总里程（`records.reduce((acc, r) => acc + r.distance, 0)`）
    - `passCount`: 状态为 2 或 5 的记录数（25 条中恰 6 条）
    - `passRate`: `24.0%`（`6 / 25 = 24.0%`）
    - `dailyDistances`: 符合规范的 30 键 Map
  - 在 `record-list.e2e.ts` 中断言与全量统计指标对齐；
  - 在 `record-submit.e2e.ts` 中将 `page.getByText('recordId: 9001')` 约束在 `.ant-alert` 容器内（如 `page.locator('.ant-alert').getByText('recordId: 9001')` 或加 `{ exact: true }`）；
- **红线要求**：
  - 生产代码（六服务 src/main 与 web/src）零触碰！
  - 严禁触碰 `work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md`；
  - 保证后端 Maven 单测与前端所有测试 100% 全绿。

## 4. 验证命令

- 后端单测：`bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test`
- 前端单测与构建：`pnpm --dir web type-check && pnpm --dir web test && pnpm --dir web build`
- 前端 E2E 测试：`pnpm --dir web e2e`（若 Windows 遇 esbuild temp 锁，重定向 TMP/TEMP 环境变量至仓内 `.trae/tmp`）
- 契约门禁：`bash scripts/verify/mailbox-contract.sh --open=TASK-233 --baseline=60aee22`

---

### 回填（2026-10-15，C-02 台账笔）

**判别式红绿**

- R1 后端计时容差：**绿**。`firstCloseMs<3000` / `doubleCloseMs<3000`；原阻塞缺陷（30s）远超 3s 线，识别力保留，仅消 1s 级调度抖动。
- R2 前端 E2E `/my/stats` mock 契约：**绿**。`helpers.ts` 新增 `GET /record/api/records/my/stats` 分支返回 `myStats()`，与 25 条夹具全量对齐（totalCount=25 / 总里程 194.00 / passCount=6 / passRate=`24.0%` / 30 键 dailyDistances）；不再落入 404 兜底。
- R3 统计卡断言对齐：**绿**。`record-list.e2e.ts` 同用 `totalDistanceSum().toFixed(2)`=`194.00` 与 `24.0%` 全量口径。
- R4 strict-mode 修正：**绿**。`record-submit.e2e.ts` 的 `recordId: 9001` 限定在 `.ant-alert` 容器内。
- R5 红线：**绿**。生产代码（全部 src/main 与 `web/src`）、pom、`package.json`、`playwright.config.ts`、`PLAN.md`/`HANDOVER.md` 零触碰；零新依赖。

**门禁留证**

- `bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` → `BUILD SUCCESS`：record-service 合计 **262 通过 / 0 失败 / 0 错误**（reactor 4 模块全 SUCCESS）。
- `pnpm --dir web type-check` → rc=0；`pnpm --dir web test` → `Tests 32 passed`；`pnpm --dir web build` → `✓ built in 18.29s`（temp 重定向后 exit=0）。
- `pnpm --dir web e2e`（temp 重定向）→ **`12 passed (47.2s)`**，rc=0，无 strict mode violation。
- `bash scripts/verify/mailbox-contract.sh --open=TASK-233 --baseline=60aee22` → 判据 A（两件套齐 / 待办放行）/ 判据 B（只改清单与实际改动集一致，恰 7 文件）通过，rc=0。

**偏差**

- D1：C-02 自指哈希不入本文件，以 `git log` 为准；本任务只记 C-01 = `5e675e1`。
- D2：后端壳命令经 pwsh 管道包裹后 `$LASTEXITCODE` 取到管道端（Select-Object），判读以 Maven 日志 `BUILD SUCCESS` 为准，非缺陷。
- D3：`pnpm e2e` 内嵌二次 `build`，与单独构建无冲突，非问题。
- D4：行尾纪律按 `autocrlf` 归一，`git show --numstat` 复核为行级小 diff，无整文件重写。

**未覆盖清单**

- `--mode=online` / `--static` / CI 未跑（未 push、未建 PR）。
- 真实 MySQL / ShardingSphere 代理链路未联调；`/my/stats` mock 仅为 E2E 侧契约，不替代服务端实现验证。
- 计时断言仍为墙钟敏感：极端暂停 >3s 仍可能越线；确定性断言（内层池关闭/等待线程异常收场）已承载主判别。
- `dailyDistances` mock 把 25 条归集到单日（2026-10-10），跨多日分布未覆盖，E2E 不断言里程条数值。
- `pageOneDistanceSum()` 已不再被引用但保留导出（夹具 API 兼容，零风险）。
- 时区口径沿用服务器时钟，跨时区错位为既有问题，未处理。

**停止条件**

- 白名单内实施与测试改动完成，验证命令全绿（后端 262 / 前端 type-check+32+build / e2e 12 passed），台账两件套落盘；`PLAN.md` / `HANDOVER.md` 与白名单外文件零触碰。后续如需 CI push、真实连库与跨多日 dailyDistances 断言，须新开任务。
