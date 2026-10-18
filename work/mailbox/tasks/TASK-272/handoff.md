# TASK-272 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-18 实施完毕，门禁全绿，待指导侧亲核与独立复核）

## 1. 开工基线

- 开工基线：main @ `896ecb9`（TASK-272 立项笔；开工基线与派发令口径一致，工作树纯净确认）
- 契约基线口径说明：派发令标注 f32551b（TASK-271 封盘笔）为「判据 B 比对基线」，但该笔早于本任务立项笔——以 f32551b 跑判据 B 会把立项笔自身的 4 个 mailbox 文件（BACKLOG/HANDOVER/PLAN/spec.md）卷入实际改动集、与 17 路径白名单错位（跨任务窗口效应，与 TASK-260 复核记录同现象）；按 TASK-265 以来「契约基线 = 本任务立项笔」口径以 `896ecb9` 为权威基线执行，两档基线实测读数均登记于门禁表 #5
- C-01 业务/配置/测试提交：`deda9a1`（`feat(mapmatch,leaderboard,api,web): 新增路段挑战与赛段刷榜系统Segments/KOM全链路（TASK-272）`；16 文件 +1695/−10）
- 任务书：`work/mailbox/tasks/TASK-272/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 896ecb9` 双向全等）

- 改动清单（恰 17 项 = C-01 业务/配置/测试 16 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - scripts/mapmatch/road_schema.sql
  - mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchSqlContractTest.java
  - api/src/main/java/com/sportverify/api/mapmatch/dto/SegmentDTO.java
  - api/src/main/java/com/sportverify/api/mapmatch/dto/SegmentMatchRequestDTO.java
  - api/src/main/java/com/sportverify/api/mapmatch/dto/SegmentEffortDTO.java
  - api/src/main/java/com/sportverify/api/mapmatch/dto/SegmentLeaderboardDTO.java
  - mapmatch-service/src/main/java/com/sportverify/mapmatch/service/SegmentService.java
  - mapmatch-service/src/main/java/com/sportverify/mapmatch/controller/SegmentController.java
  - mapmatch-service/src/test/java/com/sportverify/mapmatch/service/SegmentServiceTest.java
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardSegmentTest.java
  - web/src/api/client.ts
  - web/src/pages/record-detail.page.vue
  - web/src/pages/leaderboard.page.vue
  - web/e2e/helpers.ts
  - work/mailbox/tasks/TASK-272/handoff.md
- 零触碰面声明：除上述 17 文件外，全仓其他微服务生产 Java 源码、业务逻辑、其他单测、构建脚本、编排与环境配置、数据库脚本等 100% 零触碰；未引入任何新的外部依赖。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 全仓 offline 全量测试 | `mvn-verify.sh --mode=offline test` | 0 | BUILD SUCCESS；全仓七模块 surefire 共 **1087** 测全绿（0 失败、0 错误、0 跳过）：common 63 / gateway 79 / user 221 / record 351 / verify 203 / **leaderboard 120**（110→120 新基线）/ **mapmatch 50**（36→50 新基线）。读数时点：C-01（deda9a1）树实测 |
| 2 | leaderboard 静态三件套 | `mvn-verify.sh --mode=offline --static=leaderboard-service` | 0 | BUILD SUCCESS；**You have 0 Checkstyle violations**，spotbugs/pmd 同轮通过。读数时点：C-01 前实测 |
| 3 | 前端测试与类型检查 | `npm test && npm run type-check && npm run e2e` | 0 | vitest **63** 测全绿（4 test files 全过）；vue-tsc 类型检查退出码 0；E2E **14/14** 全绿（含 leaderboard 陈旧响应守卫用例，挂载期请求计数断言不受赛段 Tab 影响）。读数时点：C-01 前实测 |
| 4 | 契约门 | `mailbox-contract.sh --open=TASK-272 --baseline=896ecb9` | 0 | C-01 后预跑 rc=0（handoff 未跟踪窗口，17 路径双向全等）；C-02 后终态复跑 rc=0。字面基线 f32551b 实测 rc=1：仅立项笔 4 个 mailbox 文件（BACKLOG/PLAN/HANDOVER/spec.md）报「改动集未声明」——立项笔早于该基线所致跨任务窗口效应（TASK-260 复核记录同现象），业务 17 路径零错位，权威基线按「契约基线=立项笔」口径取 896ecb9（见 §1） |
| 5 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | 0 | 基线 896ecb9 树实测 SUM = **2365**；C-01 树实测 SUM = **2365**（16 业务文件 29-token 逐位零命中，逐位持平零减少）；C-02 树期望 SUM = **2394** = 2365 + 29（本 handoff 本行逐位表自枚举恰各 +1，C-02 后实测复核）。29 项逐位基线读数（896ecb9 树）：13.4=166、18.0=182、73.93=123、68.8=118、6315=98、1.8612=97、3.3066=97、5.7056=103、9.408=88、36525962432=81、36586847965=78、36438897772=80、36399582548=78、36098038547=76、2806=118、598=115、36736221648=75、36808102571=63、36821040708=57、36845152965=54、36871294588=52、36880083885=54、36958994260=53、36976873215=48、36992632143=45、36995450125=43、37008317295=42、37021305016=41、37591580687=40 |
| 6 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex268.txt HEAD -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml'` | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1。读数时点：C-02 后终态实测 |
| 7 | diff 净与行尾 | `git diff 896ecb9 HEAD --check` / 入库 blob CR 计数 | 0 | diff --check 输出空 rc=0；17 文件入库 blob 逐字节 CR=0 纯 LF（git cat-file 逐字节扫描） |

## 4. 条件项与实施要点（如实回填）

- **R1（PostGIS 空间表与契约锁定）**：
  - `scripts/mapmatch/road_schema.sql` 末尾追加 `segment` 表 DDL（id BIGSERIAL 主键 / name / sport_type SMALLINT 缺省 1 / distance_m DOUBLE PRECISION / geom GEOMETRY(LINESTRING, 4326) / created_at）与 `idx_segment_geom` GIST 空间索引及 4 条 COMMENT，幂等可重跑；
  - `MapMatchSqlContractTest` 新增独立 @Test 契约方法：逐字锁定 `CREATE TABLE IF NOT EXISTS segment`、sport_type/distance_m/GEOMETRY(LINESTRING, 4326) 片段与 `CREATE INDEX IF NOT EXISTS idx_segment_geom ON segment USING GIST (geom)`，并反向防御 BTREE 退化。
- **R2（api 领域 DTO）**：
  - `com.sportverify.api.mapmatch.dto` 新增 4 DTO：SegmentDTO(id/name/sportType/distanceMeters/createdAt)、SegmentMatchRequestDTO(recordId/userId/sportType/points，复用 MapMatchPointDTO 并沿用 @NotEmpty+@Size(20000) 校验)、SegmentEffortDTO（匹配产出与上报回执双角色复用，isPr/isKom 由 leaderboard 判定回填）、SegmentLeaderboardDTO(rank/userId/nickname/elapsedSeconds/recordId/paceFormatted)。
- **R3（mapmatch-service 赛段服务与两段式匹配）**：
  - `SegmentService`：预置 2 条上海经典赛段（世纪公园内圈 1.2km 冲刺段 RUNNING / 陆家嘴滨江 2.0km 挑战段 CYCLING，固定稳定 ID 1/2，标称长度由折线坐标 haversine 实时计得自洽无漂移）；segment 表为空或空间库失联（DataAccessException，离线单测/开发机常态）时回退预置兜底集合；
  - `matchSegments` 两段式：①粗筛 = 轨迹外接矩形 `ST_MakeEnvelope(?,?,?,?,4326)` + `ST_DWithin` 走 idx_segment_geom（列序契约与 MapMatchService.CANDIDATE_SQL 同形），兜底路径用纯 Java 包围盒相交；②精匹 = 时序寻找距起点 ≤35m 的 P_start 与其后首个距终点 ≤35m 的 P_end，累计距离相对误差 ≤25% 防跳点假完赛，elapsed = P_end.ts − P_start.ts、avgSpeed = 实际累计距离/净耗时；ts 缺失或非正耗时跳过；
  - `SegmentController` 暴露 GET /segments 与 POST /segments/match（网关 StripPrefix=1 后映射）；
  - `SegmentServiceTest` 13 测覆盖列表兜底/库行映射/整段命中/库失联兜底/SQL 候选命中/远离/仅起锚/距离偏差/零耗时/缺 ts/类型过滤/简并请求，100% 通过。
- **R4（leaderboard-service 赛段排行榜与 KOM 判定）**：
  - `LeaderboardService.recordSegmentEffort`：ZSet `leaderboard:segment:{segmentId}`（member=userId，score=elapsedSeconds 升序）；现成绩不存在或更优才 ZADD 并同步附属 Hash `{key}:record`（回指 recordId）；与自身最好成绩持平不重写但报 PR（重放/重访口径）；劣于自身恒非 PR 非 KOM；写后 ZRANK rank=0 即 KOM；无分布式锁（纯 Redis 单键原子写，并发瞬态由下一次上报收敛，ADR-0009 同哲学）；
  - `getSegmentLeaderboard`：rangeWithScores 升序前 N（size 归一：缺省 50、上限 1000），批量昵称（user-service 失联降级「用户{id}」占位，与总榜同路径），附属 Hash 回指 recordId，配速由快照表记录里程派生（分'秒"/km，里程缺失 "-"）；刻意不加 @Cacheable（CacheConfig 只注册 overall 缓存名 + 写路径零代理哨兵口径）；
  - `LeaderboardController` 暴露 GET /api/leaderboard/segments/{segmentId} 与 POST /api/leaderboard/segments/{segmentId}/effort（body 复用 SegmentEffortDTO，userId 走 resolveUserId 网关 X-User-Id 越权拦截）；
  - `LeaderboardSegmentTest` 10 测覆盖首报 KOM/更优刷新/劣于自身不写/持平不重写/更新非 KOM/非法参数零触碰/升序榜单组装/空榜/size 归一/缺档降级，100% 通过；LeaderboardService 构造器 7 参不变，LeaderboardWritePathStaysUnproxiedTest 哨兵保持绿。
- **R5（前端与 E2E mock 协同）**：
  - `client.ts` 补齐 getSegments/matchSegments/getSegmentLeaderboard/submitSegmentEffort 4 方法与 SegmentDTO/SegmentEffortDTO/SegmentMatchRequestDTO/SegmentLeaderboardDTO 4 接口类型；
  - `record-detail.page.vue`：轨迹全量加载后异步调 matchSegments，命中成果逐条幂等上报（服务端仅更优于 PR 时写榜）并以响应回填 isPr/isKom，配速图下方渲染「🏁 赛段冲刺成果 (Segment Efforts)」卡片（完赛耗时/均速/KOM👑/PR 标识），点击弹窗拉取该赛段 KOM 榜；任一环节失败静默降级不阻断轨迹/配速主渲染；
  - `leaderboard.page.vue`：lbType 单选组新增「🏁 赛段 KOM 榜」，懒加载 getSegments 填充赛段下拉，表格列按模式切换（耗时/配速），与既有 range/zset 两视图共享请求序号守卫（TASK-257 E26 口径）；挂载期仍走 range 视图不发赛段请求，notifications.e2e 的请求计数断言不受影响（E2E 14/14 实测保持）；
  - `helpers.ts`：installRouteMocks 新增 `**/mapmatch/segments*` 与 `**/leaderboard/api/leaderboard/segments*` 两 route-mock 分支（列表/匹配/榜单/成绩上报四路 + unmocked 兜底 404），赛段请求面移出 unhandled 真实网络风险面口径。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；
- 未推送：`origin/main..HEAD` 收口后 = **3 笔**（立项笔 `896ecb9` + C-01 `deda9a1` + C-02 本笔）——推送由指导侧批次统一执行，本任务严格零推送；
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 17 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望 2394 一致。
