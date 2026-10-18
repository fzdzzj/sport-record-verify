# TASK-273 立项任务书（公里分段配速与海拔剖面图 Splits & Elevation Profile）

> 状态：**已立项待派发**（2026-10-18 指导侧立项；开工基线 main @ `cddd0ac`；MEDIUM 档，指导亲核三块把关，免独立复核子 agent）

## 1. 证据链与业务背景（2026-10-18 指导侧）

全仓 54 项技术债、压测基准套件、代码规范清零、徽章成就体系与路段刷榜系统（TASK-269~272，CI run `39116762606` / `39129038776`）已全量闭环，Surefire 测试数达 1087 测稳态。
用户已选定**方向一：公里分段配速与海拔剖面图（Splits & Elevation Profile，MEDIUM 档）**：
1. **专业运动分析体验缺口**：当前运动记录详情页已有局部等距等比投影轨迹（TASK-213）和配速折线图（TASK-219），但缺少专业跑步与骑行中最核心的**每公里分段配速切片（Kilometer Splits）**，用户无法清晰复盘第 1 公里、第 2 公里乃至末尾余程的节奏变化，亦缺少最快/最慢公里的对比；
2. **海拔与爬升维度缺失**：目前系统轨迹点未透出海拔高度（Elevation），缺少高度剖面图与累计爬升/下降指标，无法衡量运动强度（例如爬坡越野跑）；
3. **高性价比与零架构侵入**：公里切片与海拔分析采用纯内存与数学插值计算，**零数据库 DDL 变更、零新外部依赖、零阻塞写链路**，收效立竿见影。

---

## 2. 只改清单（白名单，恰 13 业务/配置/测试/UI + 1 台账 = 14 文件）

严格限制在以下 14 个文件内，严禁触碰白名单外任何文件：

1. `api/src/main/java/com/sportverify/api/record/dto/KilometerSplitDTO.java`（新增单公里切片 DTO）
2. `api/src/main/java/com/sportverify/api/record/dto/RecordSplitsDTO.java`（新增运动记录分段全景 DTO）
3. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（新增 getRecordSplits 线性插值切片计算与归属防护）
4. `record-service/src/main/java/com/sportverify/record/controller/RecordController.java`（暴露 GET /records/{id}/splits 端点）
5. `record-service/src/test/java/com/sportverify/record/service/RecordSplitsServiceTest.java`（新增服务层公里切片算法与边界单测）
6. `record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java`（增补 /splits 端点路由与鉴权单测）
7. `web/src/api/client.ts`（TrackPointDTO 扩展 ele 属性，新增 getRecordSplits API 方法与接口定义）
8. `web/src/utils/splits.ts`（新增纯前端公里切片、配速格式化与海拔统计工具函数）
9. `web/src/utils/__tests__/splits.test.ts`（新增前端 splits 工具函数的完整单元测试）
10. `web/src/utils/gpx.ts`（GPX 导入解析支持提取 <ele>，GPX 导出支持回显 <ele>）
11. `web/src/utils/__tests__/gpx.test.ts`（增补 GPX 海拔导入导出单测）
12. `web/src/pages/record-detail.page.vue`（新增「📊 公里分段配速」卡片与「⛰️ 海拔剖面」SVG 可视化）
13. `web/e2e/helpers.ts`（补齐 /record/api/records/*/splits 的 mock 路由分支，防御 E2E unmocked 告警）
14. `work/mailbox/tasks/TASK-273/handoff.md`（执行交接台账）

---

## 3. 核心条件项与设计规约

### R1（api 领域 DTO 契约）
- `KilometerSplitDTO`：
  - `km: Integer`（分段序号：1, 2, 3...，末尾余程可标识为相应序号）；
  - `distanceMeters: Double`（分段标称距离，整公里为 1000.0，末尾不足 1km 为实际剩余米数）；
  - `elapsedSeconds: Integer`（该分段净耗时秒数）；
  - `paceFormatted: String`（如 `5'24"`；若非正耗时或异常回退 `--`）；
  - `avgSpeed: Double`（分段平均速度 m/s，保留 2 位小数）；
  - `isFastest: Boolean`（整公里中最快公里，⚡）；
  - `isSlowest: Boolean`（整公里中最慢公里，🐢）；
  - `elevationGain: Double`（分段累计爬升米，无海拔时可为 0.0）；
  - `elevationLoss: Double`（分段累计下降米，无海拔时可为 0.0）；
- `RecordSplitsDTO`：
  - `recordId: Long`、`totalDistanceMeters: Double`、`totalElapsedSeconds: Integer`；
  - `splits: List<KilometerSplitDTO>`；
  - `fastestKm: Integer`（最快满公里序号，若无满 1km 则为 null）；
  - `slowestKm: Integer`（最慢满公里序号，若无满 1km 则为 null）；
  - `totalElevationGain: Double`、`totalElevationLoss: Double`、`hasElevationData: Boolean`。

### R2（record-service 服务层千米插值算法与端点）
- `SportRecordService.getRecordSplits(Long caller, Long recordId)`：
  - **权限与归属**：与 `pagePoints` / `getRecord` 一致，`app.auth.enabled=true` 时断言 `caller == record.userId`，非本人抛 1002 (FORBIDDEN)；记录不存在抛 3001 (RECORD_NOT_FOUND)；
  - **点位获取**：通过 `trackPointMapper` 查询指定记录全量轨迹点（按 `seq ASC` 排序）；若轨迹点为空，优雅返回空 `splits` 列表；
  - **千米插值切片算法**：
    - 采用 Haversine 球面距离公式（$R=6371000\text{m}$，与系统既有推导一致）逐点累计实际路程；
    - 当累计路程跨过 $1000\text{m}, 2000\text{m}, 3000\text{m}\dots$ 时，在跨线相邻两点 $P_{i-1}$ 与 $P_i$ 之间按距离比例线性插值求出穿越时刻时间戳：
      $$ts_{cross} = ts_{i-1} + (ts_i - ts_{i-1}) \times \frac{k \cdot 1000 - dist_{i-1}}{dist_i - dist_{i-1}}$$
    - 第 $k$ 公里耗时 $\Delta t_k = ts_{cross}^{(k)} - ts_{cross}^{(k-1)}$；
    - 计算分段配速秒 $paceSec = \frac{\Delta t_k}{dist_k / 1000.0}$ 并格式化为 $m'ss''$；
    - 轨迹末尾剩余距离若 $\ge 10\text{m}$，作为独立末尾余程段记录；
    - 在所有满 1000m 的分段中标记 `fastestKm`（用时最短）与 `slowestKm`（用时最长）；
- `RecordController`：暴露 `GET /records/{id}/splits`，网关转发路径 `/record/api/records/{id}/splits`；
- 编写 `RecordSplitsServiceTest` 与更新 `RecordControllerTest`，覆盖插值正确性、末尾余程、空点退化、越权拦截 403、不存在 404 等场景。

### R3（前端算法封装 web/src/utils/splits.ts）
- 新建 `web/src/utils/splits.ts`，实现纯前端 `computeKilometerSplits(points: TrackPointDTO[]): RecordSplitsDTO`；
- 支持本地拖拽 GPX 预览切片计算，并在后端接口降级时作为无缝兜底；
- 新建 `web/src/utils/__tests__/splits.test.ts`，覆盖 100% 单元测试（空数组、单点、不足 1km、跨 3.5km 插值、最快最慢判定）。

### R4（GPX 导入导出海拔增强）
- `web/src/api/client.ts`：`TrackPointDTO` 增加可选属性 `ele?: number`；
- `web/src/utils/gpx.ts`：
  - `parseGpx`：在正则提取 `<trkpt>` 内容时增加对 `<ele>([-\d.]+)</ele>` 的提取，存在时填入 `point.ele`；
  - `generateGpx`：若点位包含 `p.ele != null`，在 `<trkpt>` 节点内输出 `<ele>${p.ele.toFixed(1)}</ele>`；
- `web/src/utils/__tests__/gpx.test.ts`：增补带海拔 GPX 解析与导出测试，确保 vitest 全绿。

### R5（前端页面可视化与 E2E 协同）
- `web/src/api/client.ts`：新增 `getRecordSplits` API 方法及相关类型定义；
- `web/src/pages/record-detail.page.vue`：
  - 在「配速趋势」下方增设「📊 公里分段配速 (Kilometer Splits)」卡片：
    - 统计栏：总里程、满公里最快配速（⚡ 绿色标签）、满公里最慢配速（🐢 橙色标签）；
    - 表格/条形对比图：列出公里号（如 1km、2km、+0.35km）、耗时、配速柱状图（以纯 CSS 相对长度条展示各公里配速快慢）、均速；
  - 若数据包含海拔（`hasElevationData` 为 true 或点位存在 `ele`）：渲染「⛰️ 海拔剖面 (Elevation Profile)」卡片，展示累计爬升、累计下降、最高/最低海拔与高度折线图；若无海拔，提供温和引导文案；
  - 轨迹加载后异步调用 `getRecordSplits`，任一环节失败静默降级为前端本地切片，绝不阻塞主轨迹与配速渲染；
- `web/e2e/helpers.ts`：
  - 在 `handleRecord` 补齐 `**/record/api/records/*/splits` mock 路由分支，返回预置分段数据，防御 E2E `assertNoUnmocked` 违背。

---

## 4. 门禁要求与执行命令（七门禁必须全绿）

| # | 门禁项 | 命令 | 预期 |
| :--- | :--- | :--- | :--- |
| 1 | offline 全量单测 | `scripts/verify/mvn-verify.sh --mode=offline test` | BUILD SUCCESS，1087 测只增不减（0 败 0 错 0 跳） |
| 2 | record 静态三件套 | `scripts/verify/mvn-verify.sh --mode=offline --static=record-service` | BUILD SUCCESS，Checkstyle 0 violations，spotbugs/pmd 通过 |
| 3 | 前端三件套 | `npm test && npm run type-check && npm run e2e` | vitest 全绿，vue-tsc 0 错误，E2E 14/14 全绿 |
| 4 | 契约门 | `scripts/verify/mailbox-contract.sh --open=TASK-273 --baseline=cddd0ac` | 判据 B 14 路径双向全等，退出码 0 |
| 5 | token 门 | `git grep -cF -e <token> HEAD` | 29 项历史指标基线 2394 逐位持平（只增不减） |
| 6 | 词面门 | 扫描 4 locale 形态 | ZERO_HIT（退出码 1） |
| 7 | diff 净与行尾 | `git diff --check` / CR 扫描 | 退出码 0，无 BOM，纯 LF |

---

## 5. 提交纪律（严格双提交与零推送）

- **C-01（业务/测试/UI 提交）**：仅允许修改 13 个业务/测试/UI 文件，提交消息：
  `feat(record,web): 落地公里分段配速与海拔剖面图全链路（TASK-273）`
- **C-02（台账提交）**：仅允许新增 1 个文件 `work/mailbox/tasks/TASK-273/handoff.md`，提交消息：
  `docs(mailbox): TASK-273 实施 handoff 回填（TASK-273）`
- **严禁提前 push**：整个实施周期严格零推送，由指导侧主 Agent 统一封盘推送。

---

## 6. 停止条件与红线声明

1. **零表结构变更红线**：严禁创建或修改任何 SQL 迁移文件，严禁触碰 `scripts/mapmatch/road_schema.sql` 或 `sql/` 下任何文件；
2. **零外部依赖红线**：严禁修改 `pom.xml` 或 `package.json` 引入新的第三方图表库或依赖（柱状与折线均基于纯 CSS 或既有 SVG 投影机制）；
3. **token 保护红线**：严禁在代码、测试、注释或常量中引入 29 项敏感指标字面量；
4. **遇阻停止**：遇到任何架构违背或无法收敛的门禁报错，立即停止并回传指导侧，严禁盲目静默修改。
