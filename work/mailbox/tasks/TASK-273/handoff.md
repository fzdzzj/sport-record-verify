# TASK-273 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-18 执行侧实施，七门禁全绿）

## 1. 开工基线

- 开工基线：main @ `c53e951`（TASK-273 立项笔；派发令与任务书同源口径，工作树纯净确认，无跨任务窗口效应）
- C-01 业务/测试/UI 提交：`bdd1dd7`（`feat(record,web): 落地公里分段配速与海拔剖面图全链路（TASK-273）`；13 文件 +1364/−6）
- 任务书：`work/mailbox/tasks/TASK-273/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only c53e951` 双向全等）

- 改动清单（恰 14 项 = C-01 业务/测试/UI 13 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - api/src/main/java/com/sportverify/api/record/dto/KilometerSplitDTO.java
  - api/src/main/java/com/sportverify/api/record/dto/RecordSplitsDTO.java
  - record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
  - record-service/src/main/java/com/sportverify/record/controller/RecordController.java
  - record-service/src/test/java/com/sportverify/record/service/RecordSplitsServiceTest.java
  - record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java
  - web/src/api/client.ts
  - web/src/utils/splits.ts
  - web/src/utils/__tests__/splits.test.ts
  - web/src/utils/gpx.ts
  - web/src/utils/__tests__/gpx.test.ts
  - web/src/pages/record-detail.page.vue
  - web/e2e/helpers.ts
  - work/mailbox/tasks/TASK-273/handoff.md
- 零触碰面声明：除上述 14 文件外，全仓其他微服务生产源码、业务逻辑、其他单测、构建脚本、编排与环境配置、数据库脚本等 100% 零触碰；零表结构变更、零新增外部依赖（柱状图纯 CSS 相对比例条、海拔折线复用既有 SVG 投影机制）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 全仓 offline 全量测试 | `mvn-verify.sh --mode=offline test` | 0 | BUILD SUCCESS；全仓七模块 surefire 共 **1104** 测全绿（0 败 0 错 0 跳，1087→1104 只增不减）：common 63 / gateway 79 / user 221 / **record 368**（351→368：RecordSplitsServiceTest 新 12 测 + RecordControllerTest 增 5 测）/ verify 203 / leaderboard 120 / mapmatch 50。读数时点：C-01 树内容实测（首轮实测 4 个控制器新测红：/splits 端点误以 (id, caller) 顺序透传服务层 getRecordSplits(caller, recordId)，修正参数序后全绿，详见 §4 R2；除参数序外零改动面变化） |
| 2 | record 静态三件套 | `mvn-verify.sh --mode=offline --static=record-service` | 0 | BUILD SUCCESS；**You have 0 Checkstyle violations**，spotbugs/pmd 同轮通过。读数时点：C-01 树内容实测 |
| 3 | 前端三件套 | `npm test && npm run type-check && npm run e2e` | 0 | vitest **80** 测全绿（63 基线 + splits 新 11 测 + gpx 增 6 测，5 test files）；vue-tsc 类型检查退出码 0；E2E **14/14** 全绿。读数时点：C-01 树内容实测 |
| 4 | 契约门 | `mailbox-contract.sh --open=TASK-273 --baseline=c53e951` | 0 | C-01 后预跑（handoff 未跟踪窗口）rc=0，14 路径双向全等；C-02 后终态复跑 rc=0（判据 B 14 路径双向全等）。基线 = 本任务立项笔 c53e951（派发令同源，立项笔不卷入改动集） |
| 5 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | 0 | 基线 c53e951 树实测 SUM = **2394**；C-01 树（bdd1dd7）实测 SUM = **2394**（13 业务文件 29-token 逐位零命中，逐位持平零减少）；C-02 树期望 SUM = **2423** = 2394 + 29（本 handoff 本行逐位表自枚举恰各 +1，C-02 后实测复核）。29 项逐位基线读数（c53e951 树）：13.4=167、18.0=183、73.93=124、68.8=119、6315=99、1.8612=98、3.3066=98、5.7056=104、9.408=89、36525962432=82、36586847965=79、36438897772=81、36399582548=79、36098038547=77、2806=119、598=116、36736221648=76、36808102571=64、36821040708=58、36845152965=55、36871294588=53、36880083885=55、36958994260=54、36976873215=49、36992632143=46、36995450125=44、37008317295=43、37021305016=42、37591580687=41 |
| 6 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex268.txt HEAD -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml'` | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1。读数时点：C-02 后终态实测 |
| 7 | diff 净与行尾 | `git diff c53e951 HEAD --check` / 入库 blob CR 计数 | 0 | diff --check 输出空 rc=0；14 文件入库 blob 逐字节 CR=0 纯 LF 无 BOM（git cat-file 逐字节扫描） |

## 4. 条件项与实施要点（如实回填）

- **R1（api 领域 DTO 契约）**：
  - `KilometerSplitDTO`（km/distanceMeters/elapsedSeconds/paceFormatted/avgSpeed/isFastest/isSlowest/elevationGain/elevationLoss）与 `RecordSplitsDTO`（recordId/totalDistanceMeters/totalElapsedSeconds/splits/fastestKm/slowestKm/totalElevationGain/totalElevationLoss/hasElevationData）两新增 DTO，@Data + Serializable 与既有 record-api 契约同风格；空轨迹语义（空 splits、0.0 总里程、null 最快最慢、false 海拔）写入类级 Javadoc。
- **R2（record-service 千米插值算法与端点）**：
  - `SportRecordService.getRecordSplits(caller, recordId)`：归属校验与 getRecordDetail/deleteRecord/appeal 同款（caller 非空且非归属人 → 1002，记录不存在 → 3001，caller=null 鉴权关闭降级放行）；轨迹点按 seq ASC 读取（WHERE 携带 user_id 分片键路由单分片），归档记录冷数据读空优雅返回空切片（与申诉拒绝归档记录口径一致）；
  - 切片算法 `buildRecordSplits`（包可见供单测直测）：Haversine（R=6371000m，复用既有 EARTH_RADIUS_M 与 haversineMeters）逐点累计路程 → 跨 1000m, 2000m... 阈值在跨线相邻两点间按距离比例线性插值越线时刻（ts_cross = ts_{i-1} + (ts_i − ts_{i-1}) × (k·1000 − dist_{i-1}) / (dist_i − dist_{i-1})，段长退化防除零回退右端点时刻）→ 逐公里耗时/配速（m'ss"，非正耗时回退 --）/均速（m/s 两位 HALF_UP）；末尾余程 ≥10m 独立成段（不足并入不列）；满 1000m 分段标记最快/最慢（并列取先出现者，余程段不参与）；
  - `RecordController` 暴露 GET /records/{id}/splits（caller 解析与 /{id} 详情同构）；
  - **实施期缺陷如实记录**：首轮 offline 实测 4 个控制器新测红——端点误以 `(id, caller)` 顺序调用服务层 `getRecordSplits(caller, recordId)`（mock 不匹配返回 null、403/404 全链路用例得 200）；修正为 `(caller, id)` 后全绿，纯参数序修正、零白名单外改动；
  - `RecordSplitsServiceTest` 12 测（鉴权 4 + 算法 8：空轨迹/单点/不足 1km 纯余程/跨 2km 线性插值 + 900m 余程/3.5km 整点越线 + 最快最慢/余程 <10m 并入不列/唯一满公里同领最快最慢/零耗时配速回退 --）；`RecordControllerTest` 增 5 测（auth 关透传/auth 开 X-User-Id/缺头 1001/3001→404 全链路/1002→403 全链路）；同经线布点保证 Haversine 断言值确定（asin∘sin 恒等误差 < 1e-9 米，round2/取整后逐位确定）。
- **R3（前端算法封装）**：
  - `web/src/utils/splits.ts` 纯前端 `computeKilometerSplits(points, recordId=0)`：与后端同算法口径（同 R 常数/同阈值插值/同 10m 余程门槛/同最快最慢判定），并做 GPX 本地预览增强——点位携带 ele 时计算分段与全程累计爬升/下降（段归属按段起点所在公里、末尾归余程段，相邻点缺 ele 的高差对跳过）；配速格式化复用 pace.ts formatPace（与后端 %02d 形态逐位一致）；`splits.test.ts` 11 测（空数组/单点/不足 1km/跨 2km 插值 + 余程/3.5km 多公里/余程 <10m/唯一满公里双标识/零耗时 --/含海拔高差/局部缺 ele 跳过/无海拔恒零）。
- **R4（GPX 导入导出海拔增强）**：
  - `client.ts` TrackPointDTO 增可选 `ele?: number`；
  - `gpx.ts` parseGpx 增 `<ele>\s*([-\d.]+)\s*<\/ele>` 提取（存在且有限数值才填入 point.ele，缺失/非法省略）；generateGpx 对 `p.ele != null` 点位在 trkpt 节点内输出 `<ele>${p.ele.toFixed(1)}</ele>`（先于 <time>，GPX schema 顺序），无 ele 点位省略零扰动；
  - `gpx.test.ts` 增 6 测（导出 <ele> 节点与 schema 序/无 ele 省略/混合逐点有无/解析提取含负值/无 <ele> 缺省/带海拔 round-trip 还原）。
- **R5（前端详情页卡片与 E2E mock 协同）**：
  - `client.ts` 新增 getRecordSplits 方法与 KilometerSplitDTO/RecordSplitsDTO 接口（fastestKm/slowestKm 容 null）；
  - `record-detail.page.vue`：配速趋势下方新增「📊 公里分段配速 (Kilometer Splits)」卡片——统计栏（总里程/⚡绿最快标签/🐢橙最慢标签）+ 逐公里行（公里号 1km/+0.35km 形态、纯 CSS 相对比例条：越快越长最快 100% 最慢 30% 钳制、⚡绿/🐢橙/余程灰/其余蓝、耗时/配速/均速）；数据含海拔时渲染「⛰️ 海拔剖面 (Elevation Profile)」卡片（累计爬升/下降、最高/最低海拔、SVG 高度折线 X=累计公里 Y=海拔归一化、全程同海拔退化中位横线），无海拔时温和引导文案（GPX 导入 <ele> 可查看）；
  - 异步链路：轨迹全量加载后 `void loadSplits()` 调 getRecordSplits，任一环节失败静默降级 `computeKilometerSplits` 本地切片（同算法口径无缝兜底），绝不阻塞轨迹/配速主渲染；海拔门控 hasElevationData 或点位带 ele 双口径；
  - `web/e2e/helpers.ts`：handleRecord 补 `**/record/api/records/*/splits` GET mock 分支（3.5km 四段夹具，最快第 1 公里/最慢第 2 公里），防御 E2E assertNoUnmocked 兜底告警（points mock 为空时页面不发起该请求，分支为防御性兜底）。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；
- 未推送：`origin/main..HEAD` 收口后 = **3 笔**（立项笔 `c53e951` + C-01 `bdd1dd7` + C-02 本笔）——推送由指导侧统一执行，本任务严格零推送；
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 14 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望 2423 一致；词面门 C-02 后终态四 locale ZERO_HIT 复核。
