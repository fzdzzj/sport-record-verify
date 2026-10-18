# TASK-274 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-18 执行侧子 Agent 实施；开门基线 main @ `11e40fb`，C-01 落库 `efe8e5f`；七门禁五绿一结构性红灯如实记账待裁决、一待 C-02 终态复核，详见 §3 门 2 / 门 4~7）

## 1. 开工基线

- 开工基线：main @ `11e40fb`（TASK-274 立项笔；派发令同源口径，工作树纯净确认，无跨任务窗口效应）
- C-01 业务/测试/UI 提交：`efe8e5f`（`feat(mapmatch,record,web): 落地轨迹智能纠偏对比回放与每周运动智能周报全链路（TASK-274）`；恰 15 文件 +1941/−5）
- 任务书：`work/mailbox/tasks/TASK-274/spec.md`
- 双提交纪律执行：C-01 恰 15 业务/测试/UI 文件 + C-02 仅本 handoff 1 文件；严格零推送（`origin/main..HEAD` 收口后 3 笔，推送由指导侧统一执行）

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 11e40fb` 双向全等）

- 改动清单（恰 16 项 = C-01 业务/测试/UI 15 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - api/src/main/java/com/sportverify/api/mapmatch/dto/TrackComparisonDTO.java
  - api/src/main/java/com/sportverify/api/record/dto/WeeklyDigestDTO.java
  - mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java
  - mapmatch-service/src/main/java/com/sportverify/mapmatch/controller/MapMatchController.java
  - mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchComparisonTest.java
  - record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
  - record-service/src/main/java/com/sportverify/record/controller/RecordController.java
  - record-service/src/test/java/com/sportverify/record/service/WeeklyDigestServiceTest.java
  - record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java
  - web/src/api/client.ts
  - web/src/utils/track-playback.ts
  - web/src/utils/__tests__/track-playback.test.ts
  - web/src/pages/record-detail.page.vue
  - web/src/pages/index.page.vue
  - web/e2e/helpers.ts
  - work/mailbox/tasks/TASK-274/handoff.md
- 零触碰面声明：除上述 16 文件外，全仓其他微服务生产源码、业务逻辑、其他单测、构建脚本、编排与环境配置、数据库脚本等 100% 零触碰；零表结构变更、零新增外部依赖（纠偏对比与回放为纯几何投影/插值 + SVG/CSS，周报为已归档记录内存聚合）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 全仓 offline 全量测试 | `mvn-verify.sh --mode=offline test` | 0 | BUILD SUCCESS；全仓七模块 surefire 共 **1135** 测全绿（0 败 0 错 0 跳，1104→1135 只增不减）：common 63 / gateway 79 / user 221 / **record 392**（368→392：WeeklyDigestServiceTest 新 18 测 + RecordControllerTest 增 6 测）/ verify 203 / leaderboard 120 / **mapmatch 57**（50→57：MapMatchComparisonTest 新 7 测）。读数时点：C-01 树内容实测 |
| 2 | 静态代码门禁 | `mvn-verify.sh --mode=offline --static=record-service` 与 `--static=mapmatch-service` | 0 / 1 | **record-service rc=0**：BUILD SUCCESS，Checkstyle 0 违规，spotbugs/pmd 同轮通过。**mapmatch-service rc=1 = 结构性红灯（开工前即红，非本任务回归）**，如实记账待指导侧裁决：mapmatch pom 未声明 checkstyle/spotbugs/pmd 插件，`checkstyle:check` 落默认 sun_checks 口径（LineLength 80 / JavadocStyle / JavadocMethod / FinalParameters / JavadocVariable / MagicNumber 全开），**开工前基线探针（改动零触碰时点）即 266 项存量违规**——MapMatchService 119 / SegmentService 107 / MatchProperties 14 / SegmentController 13 / MapMatchController 8 / MapMatchApplication 5；其中 **139 项位于本任务白名单之外**（SegmentService 等 4 文件），§6「就地修复」在白名单约束下不可达（整改白名单内 2 文件至零违规也无法使该门变绿），如需绿色须为 mapmatch 植入 record-service 式派生规则集（pom/configLocation 改动，超出本任务 16 文件白名单）。**本任务新增违规 = 0 已证明**：C-01 后复测白名单两文件违规数逐位回到基线（MapMatchService 119、MapMatchController 8），新增代码按 sun_checks 全闸门写就（final 形参、@param/@return 全覆盖、行宽 ≤80、换行运算符置行首、ASCII 句号收尾——实测 checkstyle 9.3 JavadocStyle 不认「。」、MagicNumber 命中 10.0 提常量 ROUND1_FACTOR）。探针留痕：`.trae/tmp/probe-static-mapmatch.log`（基线 266）与 `.trae/tmp/static-mapmatch.log`（C-01 后 266 逐文件对齐）。读数时点：C-01 树内容实测 |
| 3 | 前端三件套 | `pnpm --dir web test`、`pnpm --dir web type-check`、`pnpm --dir web run e2e` | 0 | vitest **90** 测全绿（80 基线 + track-playback 新 10 测，6 test files）；vue-tsc 类型检查退出码 0；E2E **14/14** 全绿。读数时点：C-01 树内容实测。注：任务书 §4 表内 `test:run` 拼写在 web package.json 不存在，实际脚本名为 `test`（= vitest run），已按仓库真实拼写执行 |
| 4 | 契约门 | `mailbox-contract.sh --open=TASK-274 --baseline=11e40fb` | 0 | C-01 后预跑（handoff 未跟踪窗口）rc=0，判据 B 清单一致；C-02 后终态复跑 rc=0（16 路径双向全等）。基线 = 本任务立项笔 11e40fb（派发令同源，立项笔不卷入改动集）。读数时点：C-02 后终态实测 |
| 5 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | 0 | 基线 11e40fb 树实测 SUM = **2423**；C-01 树（efe8e5f）实测 SUM = **2423**（15 业务文件 29-token 逐位零命中——plain grep 逐文件扫描 + git grep 树扫描双口径，逐位持平零减少）；C-02 树期望 SUM = **2452** = 2423 + 29（本 handoff 本行逐位表自枚举恰各 +1，C-02 后实测复核）。29 项逐位基线读数（11e40fb 树）：13.4=168、18.0=184、73.93=125、68.8=120、6315=100、1.8612=99、3.3066=99、5.7056=105、9.408=90、36525962432=83、36586847965=80、36438897772=82、36399582548=80、36098038547=78、2806=120、598=117、36736221648=77、36808102571=65、36821040708=59、36845152965=56、36871294588=54、36880083885=56、36958994260=55、36976873215=50、36992632143=47、36995450125=45、37008317295=44、37021305016=43、37591580687=42 |
| 6 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex268.txt HEAD -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml'` | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1。读数时点：C-02 后终态实测 |
| 7 | diff 净与行尾 | `git diff 11e40fb HEAD --check` / 入库 blob CR 计数 | 0 | diff --check 输出空 rc=0；15 文件入库 blob 逐字节 CR=0 纯 LF 无 BOM（git cat-file 逐字节扫描；扫描管道 head 截断产生的 SIGPIPE fatal 为已知读数噪音，CR 读数取自全量消费管道，值有效）。读数时点：C-01 后实测，C-02 后复核 |

## 4. 条件项与实施要点（如实回填）

- **R1（api 领域 DTO 契约）**：
  - `TrackComparisonDTO`（snappedPoints: List\<MapMatchPointDTO\> / correctedCount / matchedRatio / offRoadRatio / avgOffRoadDistance / maxOffRoadDistance / totalPoints），@Data + @NoArgsConstructor + @AllArgsConstructor 与既有 mapmatch-api 契约（MapMatchResultDTO）同风格；吸附语义与全零兜底写入类级 Javadoc。注：spec 提及「保持原 seq/speed/ts/ele」，MapMatchPointDTO 契约实际仅含 seq/lat/lng/ts（无 speed/ele 字段），按既有契约类型承载（snappedPoints 保 seq/ts 原样回带）。
  - `WeeklyDigestDTO`（userId/nickname/weekStartDate/weekEndDate/totalDistanceMeters/totalElapsedSeconds/totalCalories/workoutCount/activeDays/distanceGrowthRate/beatRatio/primarySportType/fastestPaceFormatted/recoveryAdvice），与 record-api 契约同风格。
- **R2（mapmatch 智能纠偏吸附算法与端点）**：
  - `MapMatchService.compareTrack(request)`：复用既有 `thin` 抽稀与 `fetchCandidates` 候选边预筛（含 D6 宽块二分与 E29 预解析）两段式，为每采样点新增 `nearestProjection` 求最近垂足投影——`pointToSegmentFoot` 与既有 `pointToSegmentMeters` 同公式逐位一致（垂距分量），参数 t∈[0,1] 钳制端点，垂足局部平面坐标按每度米数反算回 WGS84 插值坐标（与垂距精算同帧同系数）；
  - 采纳口径（双文本对齐）：垂距 ≤ snapThresholdMeters(25m) 采纳投影坐标（lat/lng 吸附至路网中线）；垂距 ≤ 阈值且位移 > 1.0m 计 1 次纠偏（CORRECTION_MIN_METERS，亚米级吸附视为 GPS 噪声不计纠偏但坐标照常采纳）；无候选边或垂距超阈值保持原经纬度；avg/max 偏离距离保留 1 位小数（HALF_UP）；
  - 保护性降级：请求为空或无点返回空点集与全零指标不报错（空轨迹/null 双形态单测覆盖）；
  - `MapMatchController` 暴露 `POST /match/compare`（网关转发 `/mapmatch/match/compare`，StripPrefix=1 后 /match/compare，请求契约与 /match 一致）；
  - `MapMatchComparisonTest` 7 测：直道旁点吸附（垂足经度=道路经度/垂足纬度=原纬度解析解逐位断言）、点列两侧交错全部吸附纠偏计数累加、大漂移点保持原坐标计离路（封顶 300m）、超阈值未封顶 max 取真实垂距、亚米吸附不计纠偏、空输入防御、超长轨迹复用抽稀封顶采样上限；
  - **实施期缺陷如实记录**：首轮 2 测红——夹具 near 点经度偏移笔误 0.0011°（≈105m > 25m 阈值落离路侧）误标 9.5m 吸附用例，修正为 0.0001° 后全绿；纯夹具坐标修正，零实现改动。
- **R3（纯前端轨迹回放算法封装）**：
  - `web/src/utils/track-playback.ts`：`getPointAtProgress(points, progress)` 按**累计 haversine 里程**归一（非点序均分，循迹节奏与真实距离一致），段内线性插值 lat/lng/ts（速度取右端点、恰在左端点取左端点、逐级回退 null），进度越界钳制 [0,1]，空数组 null、单点原样、全程同坐标退化按点序取整点；`formatInstantPace`（m/s → 配速 m'ss"，非正/缺失 --，与 pace.ts 停顿口径一致）、`formatPlaybackClock`（基准 ts + 已播毫秒 → 本地墙钟 HH:mm:ss，无效 --）；
  - `track-playback.test.ts` 10 测：空数组/单点/进度 0 与 1 边界/越界钳制/两点中点与四分位插值/不等长三段跨点插值（累计里程归一 vs 点序均分差异锁定）/全程退化/速度回退链/配速格式化/墙钟格式化（期望值由 Date 同帧推导防 CI 时区漂移）；
  - **实施期缺陷如实记录**：首轮 2 测红——①进度 0 速度断言与「右端点」实现冲突（边界语义修正为恰在左端点取左端点速度）；②formatPlaybackClock 期望值硬编码分秒为零而 BASE_TS 分秒非零（改 Date 同帧推导）；均为测试侧修正，实现侧仅同步边界语义注释。
- **R4（详情页纠偏对比双图层与动态回放器）**：
  - 双图层：详情页新增「🤖 智能纠偏 (Track Correction)」区块——徽标卡片（「🤖 智能纠偏：已校正 X 处漂移点，路网吸附率 Y%」+ 平均/最大偏离 + 采样点数）、SVG 同屏双图层（灰 `#8c8c8c` 虚线 dasharray `4 4` 原始轨迹 vs 青 `#13c2c2` 实线宽 3 吸附中线 + 绿起点红终点），`a-switch`「显示原始对比」自由切换；吸附点列投影复用主轨迹 SVG 同一投影框（bbox/cos 纬度修正/等比缩放/居中留边逐字同算法，snapProjector 计算属性），两图层逐像素对齐；
  - Track Player：轨迹画布下方控制条（▶ 播放/⏸ 暂停按钮、1x/2x/4x 倍速 `a-radio-group`、0..1000 归一 `a-slider` 进度条）+ SVG 回放画布（灰底全径 + 青色已播高亮段 + 🏃 小人沿吸附中线平滑循迹 + 上方白底青边浮动气泡实时显示当前时刻与瞬时配速）；requestAnimationFrame 虚拟时间轴驱动（dt×倍速），播完自动停在终点，再点播放从头开始，滑块回跳保持播放态连续，`onBeforeUnmount` 收敛 raf 防泄漏；气泡配速取原始轨迹 speed（纠偏契约不含速度，缺失 --）；
  - 异步链路：轨迹全量加载后 `void loadComparison()` 调 getTrackComparison（POST /mapmatch/match/compare），任一环节失败降级「纠偏对比服务暂不可用」警示并保持纯原始轨迹渲染，绝不阻断轨迹/配速主链路（沿 loadSplits/loadSegmentEfforts 先例）。
- **R5（record 周报服务与端点）**：
  - `SportRecordService.getWeeklyDigest(caller, userId, weekStart)`：权限防护 caller 非空且非本人 → 1002 (FORBIDDEN)，caller=null 鉴权关闭降级放行；周期缺省取东八墙钟上一完整自然周（`LocalDate.now(BUSINESS_ZONE).minusWeeks(1).with(previousOrSame(MONDAY))`），显式 weekStart 归一化到所在周周一，窗口 = 周一 00:00:00 至周日 23:59:59；查询口径 = 归属人 + 通过终态（PASSED/RE_PASSED 与 myStreak/myStats 同源）+ 活动时间 COALESCE(start_time, created_at) 归窗（G11/TASK-260 R4 统一），单列投影个人量级无分页；
  - 聚合算法 `buildWeeklyDigest`（包可见供单测直测）：总里程/总耗时/出勤天数（锚点时间归日去重 0~7）/主修类型（完成次数最多、并列取先出现、无记录 null）/最佳满公里配速（km ≥ 1 且耗时为正的记录按 duration/km 取最小，m'ss" 格式化，无则 --）/环比增减率（(本周−前周)/前周 HALF_UP 保留 3 位，前周无里程且本周有数据 = 1.0、双周皆无 = 0.0——spec 仅定义前周无数据 1.0，双零形态取 0.0 为实施口径，避免空空周误报 +100%）；周报装配另测双周窗口两次查询的装配协同（Mockito 调用序 thenReturn 两行集）；
  - 估算口径（零 DDL 红线，库内无热量列）：热量 = Σ 里程公里 × 运动类型系数（跑步 65 / 骑行 25 / 步行 50 kcal/km，未知类型回退跑步）；击败比例 = 指数饱和曲线 1 − e^(−km/20) 封顶 0.99 的周跑量分位数推算（零跨服务全站查询，5km≈0.22 / 20km≈0.63 / 50km≈0.92）；恢复建议 = 完成次数 × 出勤 × 跑量四档确定性文案（零记录引导开跑 / 高出勤或 ≥40km 休息恢复 / 低出勤低跑量循序渐进 / 中段保持节奏），单测逐字锁定；
  - 主修类型口径偏差如实记录：spec 写「RUNNING / CYCLING / HIKING」，SportType 枚举实为 RUNNING/CYCLING/WALKING（无 HIKING），回传枚举 name()；
  - 昵称经 userApi.listUsersByIds 批量接口补齐，user-service 不可用/缺数软降级 null（feed 先例 fail-safe，前端回退 userId 渲染）；
  - `RecordController` 暴露 `GET /records/weekly-digest`（网关转发 `/record/api/records/weekly-digest`；身份语义与 /my 完全同构：auth on 读 X-User-Id 缺失 1001、auth off 显式 userId 缺失 400；服务层再断言 caller==userId 双层防护；可选 weekStart ISO 日期参数）；
  - `WeeklyDigestServiceTest` 18 测（权限门 3 + 服务入口 4 + 聚合环比 10 + 昵称软降级 1）；`RecordControllerTest` 增 6 测（auth 开 X-User-Id 透传 / weekStart 透传 / 缺头 1001 / 缺 userId 400 / auth 关透传 / 1002→403 全链路）；
  - **实施期缺陷如实记录**：首轮 2 测红——①周报配速夹具将 20km 骑行记录误置「不足 1km 不参评」用例（该记录本就产出 3'20" 配速，重设计为三条不足 1km 夹具）；②403 全链路用例缺鉴权态（MockMvc standalone 不消费 mock request，需显式 authEnabled=true + 请求头 X-User-Id）；均为测试侧修正，零白名单外改动。
- **R6（首页周报卡片与海报弹窗）**：
  - 「📅 每周运动小结 (Weekly Digest)」卡片（登录态区，勋章墙之后）：本周跑量大数（km，两位小数）+ 周窗口日期、环比标签（>0 绿 `+x.x% ↗` / <0 灰 `x.x% ↘` / =0 持平 –）、出勤进度条（activeDays/7，青色 #13c2c2，条上击败全站跑者百分比）、运动次数/热量/最佳配速/主修类型行、青底恢复贴士、徽标行；
  - 「查看分享长图」按钮 → 海报 Modal（纯 CSS 渐变卡面 #0e7a7a→#13c2c2→#5cdbd3：周报标题/周窗口/大字跑量/环比胶囊/出勤与击败双格/恢复贴士/落款），零新依赖可截屏分享；软错误口径与统计卡一致（失败只提示卡区不拖垮仪表盘），`loadDigest` 并入 `loadData` 首屏 Promise.all（删除记录后全量刷新同链路）。
- **R7（client.ts 与 E2E mock 协同）**：
  - `client.ts` 新增 `getTrackComparison`（POST /mapmatch/match/compare）与 `getWeeklyDigest`（GET /record/api/records/weekly-digest，可选 weekStart 参数）方法及 MapMatchPointDTO/TrackComparisonDTO/WeeklyDigestDTO 类型定义；
  - `e2e/helpers.ts`：新增 `**/mapmatch/match/compare*` route-mock 挂载与 handleMapmatch 的 POST compare 分支（10 点夹具：4 处纠偏/吸附率 0.85/avg 12.5m/max 45.0m），API_PREFIX 白名单计数同步放行 `/mapmatch/match/compare` 前缀；handleRecord 补 `/record/api/records/weekly-digest` GET 分支（周一至周日夹具：18.45km/环比 +15.2%/出勤 3/7/击败 62%/恢复贴士），双分支防御 assertNoUnmocked 兜底告警（points 夹具为空时详情页不发起 compare，分支为防御性兜底；首页挂载期即拉周报，分支为实链路承接）。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；
- 未推送：`origin/main..HEAD` 收口后 = **3 笔**（立项笔 `11e40fb` + C-01 `efe8e5f` + C-02 本笔）——推送由指导侧统一执行，本任务严格零推送；
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 16 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望 2452 一致；词面门 C-02 后终态四 locale ZERO_HIT 复核；
- **待指导侧裁决项（唯一）**：门 2 mapmatch-service 静态门结构性红灯（开工前基线即 266 项存量违规、其中 139 项白名单外，就地修复不可达；本任务新增违规 0 已逐位证明），处置建议：后续单独立项为 mapmatch 植入 record-service 式派生规则集（pom checkstyle configLocation + 豁免清单就地注记），或指导侧豁免该门（基线口径）。
