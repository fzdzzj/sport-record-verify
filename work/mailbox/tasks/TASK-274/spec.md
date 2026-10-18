# TASK-274 立项任务书（轨迹智能纠偏对比回放与每周运动智能周报 Map-matching Playback & Weekly Intelligent Digest）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-19 指导侧终裁；开工基线 main @ `11e40fb`；FULL 档，C-01 `efe8e5f` + C-02 `520762a`，指导亲核三块 PASS + 独立复核五块全 PASS 零阻断，全部门禁实测达标，封盘批次统一推送 origin/main，待 CI 外部终验）

## 1. 证据链与业务背景（2026-10-18 指导侧）

全仓 54 项技术债、压测基准套件、代码规范清零、徽章成就体系、路段刷榜系统以及公里分段配速与海拔剖面图（TASK-269~273，CI run `39116762606` / `39129038776` / `39136624184` 全绿）已全量闭环，Surefire 测试数达 1104 测稳态。
用户已选定**方向一加方向四（双功能合璧，FULL 档）**：
1. **微观轨迹专业呈现缺口（方向一）**：用户在城市峡谷或高架下跑步/骑行时容易产生 GPS 漂移点，系统在 `mapmatch-service` 中已具备 PostGIS 道路中线拓扑预筛能力（ADR-0006），但目前前端详情页仅展示原始采样轨迹，未将纠偏吸附后的路网中线同屏对比呈现，缺乏直观的算法价值外显；同时缺少专业运动 App 标志性的**轨迹动态回放控制器（Track Player）**，无法动态循迹重温运动节奏；
2. **宏观周期健康总结缺口（方向四）**：系统已具备单次运动记录详情、每日里程折线与连续打卡天数，但缺乏自然周维度的**每周运动智能周报（Weekly Intelligent Digest）**，用户无法直观了解上周总跑量、环比增减幅度、同城超越比例与个性化体能恢复建议；
3. **极高协同度与零数据库风险**：两项功能分别从「单次微观运动质感」与「周期宏观总结激励」切入，**零数据库 DDL 变更、零新外部依赖、零阻塞记录写链路**，在极低架构风险下显著提升平台产品力。

---

## 2. 只改清单（白名单，恰 15 业务/配置/测试/UI + 1 台账 = 16 文件）

严格限制在以下 16 个文件内，严禁触碰白名单外任何文件：

1. `api/src/main/java/com/sportverify/api/mapmatch/dto/TrackComparisonDTO.java`（新增轨迹纠偏对比与吸附点 DTO）
2. `api/src/main/java/com/sportverify/api/record/dto/WeeklyDigestDTO.java`（新增每周运动智能周报 DTO）
3. `mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java`（新增 compareTrack 道路投影纠偏计算）
4. `mapmatch-service/src/main/java/com/sportverify/mapmatch/controller/MapMatchController.java`（暴露 POST /match/compare 端点）
5. `mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchComparisonTest.java`（新增纠偏对比算法单测）
6. `record-service/src/main/java/com/sportverify/record/service/SportRecordService.java`（新增 getWeeklyDigest 自然周聚合与智能恢复建议）
7. `record-service/src/main/java/com/sportverify/record/controller/RecordController.java`（暴露 GET /records/weekly-digest 端点）
8. `record-service/src/test/java/com/sportverify/record/service/WeeklyDigestServiceTest.java`（新增周报聚合与环比算法单测）
9. `record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java`（增补周报端点路由与鉴权单测）
10. `web/src/api/client.ts`（新增 getTrackComparison 与 getWeeklyDigest API 方法及 DTO 类型定义）
11. `web/src/utils/track-playback.ts`（新增纯前端轨迹动态回放插值计算工具）
12. `web/src/utils/__tests__/track-playback.test.ts`（新增轨迹回放工具完整单测）
13. `web/src/pages/record-detail.page.vue`（新增纠偏对比双图层切换与动态回放器 Track Player）
14. `web/src/pages/index.page.vue`（新增首页「📅 每周运动小结」周报卡片与海报弹窗）
15. `web/e2e/helpers.ts`（补齐 /mapmatch/match/compare 与 /record/api/records/weekly-digest mock 分支）
16. `work/mailbox/tasks/TASK-274/handoff.md`（执行交接台账）

---

## 3. 核心条件项与设计规约

### R1（领域 DTO 契约）
- `TrackComparisonDTO`：
  - `snappedPoints: List<MapMatchPointDTO>`（吸附到道路中线后的点位坐标序列，保持原 seq/speed/ts/ele，lat/lng 吸附至最近道路投影）；
  - `correctedCount: Integer`（实际发生坐标纠偏的点数，即垂距 $\le \text{snapThresholdMeters}$ 且位移 $> 1.0\text{m}$ 的点数）；
  - `matchedRatio: Double`（路网吸附率，0.0~1.0）；
  - `offRoadRatio: Double`（离路比例，0.0~1.0）；
  - `avgOffRoadDistance: Double`（平均偏离距离，米，保留 1 位小数）；
  - `maxOffRoadDistance: Double`（最大偏离距离，米，保留 1 位小数）；
  - `totalPoints: Integer`（采样计算总点数）。
- `WeeklyDigestDTO`：
  - `userId: Long`、`nickname: String`；
  - `weekStartDate: String`（周一 ISO 日期如 `2026-10-12`）、`weekEndDate: String`（周日 ISO 日期如 `2026-10-18`）；
  - `totalDistanceMeters: Double`（自然周总有效运动里程）；
  - `totalElapsedSeconds: Integer`（自然周总运动耗时秒）；
  - `totalCalories: Double`（自然周估算消耗总热量）；
  - `workoutCount: Integer`（自然周运动完成次数）；
  - `activeDays: Integer`（自然周出勤活跃天数 0~7）；
  - `distanceGrowthRate: Double`（周里程环比前一周增减率，如 0.152 表示 +15.2%，前一周无数据设为 1.0）；
  - `beatRatio: Double`（击败跑者比例，基于周跑量分位数推算 0.0~0.99）；
  - `primarySportType: String`（当周主修运动类型：RUNNING / CYCLING / HIKING）；
  - `fastestPaceFormatted: String`（当周最佳满公里配速，如 `4'35"`，无则 `--`）；
  - `recoveryAdvice: String`（基于周跑量与频次的个性化体能恢复与训练建议）。

### R2（mapmatch-service 智能纠偏吸附算法与端点）
- `MapMatchService.compareTrack(MapMatchRequestDTO request)`：
  - 复用既有 `thin` 抽稀与 `fetchCandidates` 候选集预筛机制；
  - 精确计算点到线段的垂足投影坐标：
    - 在 `pointToSegmentMeters` 平面投影中，利用参数 $t \in [0, 1]$ 反算垂足在 WGS84 经纬度空间的插值坐标：
      $$lat_{snap} = lat_1 + t \cdot (lat_2 - lat_1), \quad lng_{snap} = lng_1 + t \cdot (lng_2 - lng_1)$$
    - 若最近垂距 $\le \text{snapThresholdMeters}$，该点采纳投影坐标 $(lat_{snap}, lng_{snap})$；若垂距 $> 1.0\text{m}$，记为 1 次纠偏点（`correctedCount++`）；
    - 若无候选边或最近垂距超限，保持原经纬度不变；
  - 返回完整 `TrackComparisonDTO`；
  - 保护性降级：请求为空或无点时返回全零指标与空点集，不报错。
- `MapMatchController`：暴露 `POST /match/compare`，网关转发路径 `/mapmatch/match/compare`；
- 编写 `MapMatchComparisonTest` 覆盖直道吸附、大漂移点保留、空输入防御。

### R3（纯前端轨迹回放算法封装 web/src/utils/track-playback.ts）
- 新建 `web/src/utils/track-playback.ts`：
  - 实现 `getPointAtProgress(points: TrackPointDTO[], progress: number)`：给定归一化进度 $progress \in [0, 1]$，在相邻两点间做线性插值计算当前经纬度、瞬时速度与当前时间戳；
  - 速度格式化与时间换算辅助；
- 新建 `web/src/utils/__tests__/track-playback.test.ts`，覆盖 100% 单元测试（进度 0/1 边界、跨点插值、单点与空数组安全返回）。

### R4（前端详情页 record-detail.page.vue 纠偏图层与动态回放器）
- **纠偏对比双图层**：
  - SVG 画布支持图层切换：原始 GPS 轨迹（灰色虚线 `stroke: #8c8c8c; stroke-dasharray: 4 4`）与纠偏吸附中线（青色实线 `stroke: #13c2c2; stroke-width: 3`）叠加同屏；
  - 顶部显式徽标标签：「🤖 智能纠偏：已校正 X 处漂移点，路网吸附率 Y%」；
  - 提供开关「显示原始对比」，支持用户自由切换；
- **动态回放控制器（Track Player）**：
  - 轨迹画布底部提供播放控制条：【▶ 播放 / ⏸ 暂停】按钮、倍速切换（`1x` / `2x` / `4x`）、时间进度条 Slider；
  - 跑步/骑行小人图标随着播放平滑沿纠偏中线移动，小人上方浮动气泡实时显示当前时刻与瞬时配速。

### R5（record-service 周报服务与端点）
- `SportRecordService.getWeeklyDigest(Long caller, Long userId, LocalDate weekStart)`：
  - **权限防护**：`app.auth.enabled=true` 时校验 `caller == userId`，非本人抛 1002 (FORBIDDEN)；
  - **周期计算**：默认取上一完整自然周（若未指定 `weekStart`，取当前时间的上周一 `previousOrSame(DayOfWeek.MONDAY)`，时间窗口为周一 00:00:00 至周日 23:59:59）；
  - **聚合指标**：查询该时间窗口内已审核通过记录（`status IN (PASSED, RE_PASSED)`），聚合计算总里程、总运动时长、总热量、出勤天数、主修运动类型与最快配速；
  - **环比分析**：同口径查询前一周同期里程，计算 `distanceGrowthRate`；
  - **个性化建议**：根据出勤天数与跑量等级，匹配个性化智能体能恢复与拉伸建议；
- `RecordController`：暴露 `GET /records/weekly-digest`（网关转发路径 `/record/api/records/weekly-digest`）；
- 编写 `WeeklyDigestServiceTest` 与增补 `RecordControllerTest`，覆盖周报正常生成、环比变化、0 跑量兜底、越权 403 拦截等场景。

### R6（前端首页 index.page.vue 周报卡片与海报弹窗）
- 首页仪表盘新增「📅 每周运动小结 (Weekly Digest)」卡片：
  - 展示当周总跑量、环比增减标签（如 `+15.2% ↗` 绿色 / `-3.5% ↘` 灰色）、出勤天数进度条、击败全站跑者百分比；
  - 展示智能健康恢复小贴士；
  - 提供「生成分享长图」按钮，点击弹出优雅的周报长图海报 Modal 弹窗。

### R7（client.ts 与 E2E mock 协同）
- `web/src/api/client.ts`：新增 `getTrackComparison` 与 `getWeeklyDigest` API 方法及类型；
- `web/e2e/helpers.ts`：补齐 `**/mapmatch/match/compare` 与 `**/record/api/records/weekly-digest*` 的 mock 路由分支，防御 E2E `assertNoUnmocked` 违背。

---

## 4. 门禁要求与执行命令（七门禁必须全绿）

| # | 门禁项 | 命令 | 预期 |
| :--- | :--- | :--- | :--- |
| 1 | offline 全量单测 | `scripts/verify/mvn-verify.sh --mode=offline test` | BUILD SUCCESS，1104 测只增不减（0 败 0 错 0 跳） |
| 2 | 静态代码门禁 | `scripts/verify/mvn-verify.sh --mode=offline --static=mapmatch-service` 与 `--static=record-service` | BUILD SUCCESS，Checkstyle 0 违规，SpotBugs/PMD 通过 |
| 3 | 前端三件套 | `pnpm --dir web test:run`、`pnpm --dir web type-check`、`pnpm --dir web test:e2e` | vitest 80 测只增不减全绿、vue-tsc 0 错、E2E 14/14 全绿 |
| 4 | 契约门 | `bash scripts/verify/mailbox-contract.sh --open=TASK-274 --baseline=b39d672` | 退出码 0，16 路径双向全等 |
| 5 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验 | 基线 b39d672 树 2423 / C-01 树 2423（逐位零命中）/ C-02 树 2452（自枚举各 +1） |
| 6 | 词面门 | `scripts/verify/check-public-wording.sh` 四 locale | 全 ZERO_HIT（退出码 1） |
| 7 | 行尾与 diff 净 | `git diff b39d672 HEAD --check` 与 `git status --porcelain` | 输出为空，CR=0 纯 LF，无 BOM |

---

## 5. 约束红线

1. **零新增表与 DDL 迁移**：所有计算均为点位流式几何投影与已归档记录在内存中的聚合，严禁新增数据库表或修改已有 schema；
2. **零新增依赖**：前端严禁引入新 npm 包，纯 SVG 与 CSS 实现回放与图层；后端严禁引入新 Maven 依赖；
3. **严格遵守双提交纪律**：
   - C-01：`feat(mapmatch,record,web): 落地轨迹智能纠偏对比回放与每周运动智能周报全链路（TASK-274）`（恰 15 业务/测试/UI 文件）；
   - C-02：`docs(mailbox): TASK-274 实施 handoff 回填（TASK-274）`（仅 `handoff.md` 1 文件）；
4. **严格零推送**：执行期间禁止向远端 `origin` 推送任何 commit，封盘前保持本地。

---

## 6. 停止与阻断条件

1. 若修改超出 16 项白名单，立即停止并上报；
2. 若 Checkstyle 产生新增违规或测试出现回归，必须在白名单内就地修复，严禁降级规则；
3. 若发现 token 门 29 项历史度量指标被触碰，立即撤回改动并换用安全变量名。

---

## 7. 验收留痕

待子 Agent 实施完成后，由指导侧执行亲核三块（提交链/高风险定向抽查/门禁对账）与独立复核五块，达标后封盘归档。
