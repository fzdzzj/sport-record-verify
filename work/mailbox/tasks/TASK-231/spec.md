# TASK-231: 个人统计服务端聚合端点与页面历史文案清理（MEDIUM 档）

> **开工基线**：9d1e79f（工作树干净）
> **任务级别**：MEDIUM

## 1. 目标与背景

- **背景**：
  1. TASK-211 引入的首页个人统计（总里程、总记录数、通过率、近 30 天每日里程条形图）在前端依赖单次 `size=500` 拉取并在浏览器端 JS 内存聚合，存在两个明显技术债：
     - 当用户记录数超过 500 条时发生截断，统计数据失真；
     - TASK-224 增加列表过滤与分页后，首页统计与当前翻页分页耦合（当翻到第 2 页时，统计数据变成了仅对第 2 页的 20 条做统计）。
  2. 首页与提交页中仍残留早期脚手架的陈旧文案（如「本会话 recordId 存 sessionStorage；刷新或换浏览器不会从服务端恢复（诚实无列表 API）」），与系统已具备持久化分页列表的实况冲突。
- **目标**：
  1. 服务端新增 `GET /record/api/records/my/stats` 端点，基于全量数据提供个人核心统计指标与近 30 天每日里程分布；
  2. 前端客户端与首页接入该接口，实现统计卡与翻页完全解耦；
  3. 清洗前端页面中陈旧的「无列表 API」历史旧文案。

## 2. 修改白名单

严格限定在以下实施文件与测试文件内：
- 后端：
  - `api/src/main/java/com/sportverify/api/record/dto/MyStatsDTO.java`（新增统计 DTO）
  - `record-service/src/main/java/com/sportverify/record/controller/RecordController.java`
  - `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`
  - `record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java`
  - `record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java`
- 前端：
  - `web/src/api/client.ts`
  - `web/src/api/__tests__/client.test.ts`
  - `web/src/pages/index.page.vue`
  - `web/src/pages/record-submit.page.vue`
- 台账文件：
  - `work/mailbox/tasks/TASK-231/spec.md`
  - `work/mailbox/tasks/TASK-231/handoff.md`

## 3. 核心设计与红线要求

- **接口定义与归属鉴权**：
  - 端点：`GET /record/api/records/my/stats`（与 `/my`、`/my/streak` 同构）；
  - 鉴权逻辑：`app.auth.enabled=true` 时从 `X-User-Id` 头取 `userId`，缺失报 1001；`false` 时从 `userId` 请求参数取，缺失报 400；
  - 返回对象 `MyStatsDTO` 包含：
    - `totalCount`: Long（总记录数）
    - `totalDistance`: BigDecimal（总里程，km，保留两位小数）
    - `passCount`: Long（状态为 2=PASSED 或 5=RE_PASSED 的通过记录数）
    - `passRate`: String（格式化百分比，如 `75.0%`，无记录时返回 `0%`）
    - `dailyDistances`: Map<String, BigDecimal>（近 30 天每日里程，key 为 `YYYY-MM-DD`，value 为里程数）
- **性能考量**：
  - 服务端聚合可按用户投影查询 `select(distance, status, createdAt).eq(userId)`，避免 select * 大字段，在内存中完成轻量汇总；
- **红线要求**：
  - 严禁触碰 `work/mailbox/PLAN.md` 与 `work/mailbox/HANDOVER.md`；
  - 严禁引入新的未声明依赖或外部 npm 包；
  - 保持现有所有单测不回归。

## 4. 验证命令

- 后端单测：`bash scripts/verify/mvn-verify.sh --mode=offline --pl record-service test`
- 前端验证：`pnpm --dir web type-check && pnpm --dir web test && pnpm --dir web build`
- 契约门禁：`bash scripts/verify/mailbox-contract.sh`
