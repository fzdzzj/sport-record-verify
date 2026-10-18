# TASK-272 立项任务书（路段挑战与赛段刷榜系统 Segments / KOM）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-18 指导侧终裁；开工基线 main @ `f32551b`；FULL 档，指导亲核三块 + 独立复核五块把关）

## 1. 证据链与业务背景（2026-10-18 指导侧）

全仓 54 项技术债、压测基准套件、代码规范清零、徽章成就体系（TASK-269~271）已全量闭环（CI run `39116762606` 全绿达成）。系统稳定性与微服务韧性已达生产级，正式进入高感知、高粘性空间竞技玩法拓展阶段。
用户已选定**选项 C：路段挑战与赛段刷榜系统（Segments / KOM，FULL 档）**：
1. **空间竞技玩法缺失**：当前平台已具备完整的单次运动记录、全平台总里程榜与好友里程榜，但缺少像 Strava 一样基于真实地理路网的赛段（Segment）竞速刷榜机制，无法激发跑者在经典赛段上的拼搏与社交竞技欲；
2. **PostGIS 空间基础设施就绪**：`mapmatch-service` 独占的 PostGIS 空间库 `road_db` 具备成熟的 GIST 空间索引与 `ST_DWithin` / `ST_MakeEnvelope` 空间预筛能力；通过两段式算法（空间矩形粗筛 + 点列时序与垂距精匹）可快速判定用户轨迹是否完赛某赛段，并计算出精确完赛耗时；
3. **Redis ZSet 赛段耗时榜**：`leaderboard-service` 扩展赛段排行榜，采用耗时升序排序（Score = `elapsed_seconds`），耗时最短者荣膺赛段擂主（King of the Mountain / KOM 👑），并自动保留每位用户的个人最好成绩（PR）。

---

## 2. 只改清单（白名单，恰 16 业务/配置/测试/UI + 1 台账 = 17 文件）

严格限制在以下 17 个文件内，严禁触碰白名单外任何文件：

1. `scripts/mapmatch/road_schema.sql`（追加 segment 表 DDL 定义与 GIST 空间索引）
2. `mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchSqlContractTest.java`（断言 segment 表结构与 GIST 索引契约）
3. `api/src/main/java/com/sportverify/api/mapmatch/dto/SegmentDTO.java`（新增赛段信息 DTO）
4. `api/src/main/java/com/sportverify/api/mapmatch/dto/SegmentMatchRequestDTO.java`（新增赛段匹配请求 DTO）
5. `api/src/main/java/com/sportverify/api/mapmatch/dto/SegmentEffortDTO.java`（新增赛段冲刺记录 DTO）
6. `api/src/main/java/com/sportverify/api/mapmatch/dto/SegmentLeaderboardDTO.java`（新增赛段排行榜 DTO）
7. `mapmatch-service/src/main/java/com/sportverify/mapmatch/service/SegmentService.java`（新增赛段服务：赛段列表、预置赛段与两段式匹配计算）
8. `mapmatch-service/src/main/java/com/sportverify/mapmatch/controller/SegmentController.java`（新增 REST 控制器：暴露 `/segments` 与 `/segments/match`）
9. `mapmatch-service/src/test/java/com/sportverify/mapmatch/service/SegmentServiceTest.java`（新增赛段匹配算法与服务单测）
10. `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`（扩展赛段榜单 ZSet 写入与按耗时升序查询）
11. `leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java`（暴露赛段榜单查询与成绩上报 REST 端点）
12. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardSegmentTest.java`（新增赛段榜单单测）
13. `web/src/api/client.ts`（增补赛段列表、赛段匹配与赛段榜单 API 客户端方法）
14. `web/src/pages/record-detail.page.vue`（详情页挂载「赛段冲刺成果」展示与榜单弹窗）
15. `web/src/pages/leaderboard.page.vue`（排行榜页增加「赛段 KOM 榜」Tab 与赛段切换）
16. `web/e2e/helpers.ts`（补齐赛段相关 mock 路由，防止 E2E 缺口）
17. `work/mailbox/tasks/TASK-272/handoff.md`（执行交接台账）

---

## 3. 核心设计与实施要求

### R1 数据库表结构与契约锁定
- 在 `scripts/mapmatch/road_schema.sql` 追加 `segment` 表：
  ```sql
  -- 赛段表（TASK-272）：一行 = 一个挑战赛段（LINESTRING 空间折线，供 KOM 赛段刷榜）
  CREATE TABLE IF NOT EXISTS segment (
      id           BIGSERIAL PRIMARY KEY,
      name         VARCHAR(128) NOT NULL,
      sport_type   SMALLINT     NOT NULL DEFAULT 1,          -- 1 RUNNING, 2 CYCLING
      distance_m   DOUBLE PRECISION NOT NULL,                 -- 标称长度（米）
      geom         GEOMETRY(LINESTRING, 4326) NOT NULL,      -- 赛段折线（WGS84 经纬度）
      created_at   TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
  );
  CREATE INDEX IF NOT EXISTS idx_segment_geom ON segment USING GIST (geom);
  ```
- 在 `MapMatchSqlContractTest.java` 补齐对 `CREATE TABLE IF NOT EXISTS segment` 与 `idx_segment_geom` GIST 索引的纯 JVM 契约断言。

### R2 领域 DTO 与 API 契约（`api` 模块）
- 在 `com.sportverify.api.mapmatch.dto` 新增：
  - `SegmentDTO`：`(id, name, sportType, distanceMeters, createdAt)`；
  - `SegmentMatchRequestDTO`：`(recordId, userId, sportType, points)`，支持传入轨迹点列；
  - `SegmentEffortDTO`：`(segmentId, segmentName, recordId, userId, elapsedSeconds, startTime, endTime, avgSpeed, isPr, isKom)`；
  - `SegmentLeaderboardDTO`：`(rank, userId, nickname, elapsedSeconds, recordId, paceFormatted)`。

### R3 赛段服务与两段式匹配算法（`mapmatch-service`）
- `SegmentService`：
  - 初始化预置 2 条经典赛段（如「世纪公园内圈 1.2km 冲刺段」与「陆家嘴滨江 2.0km 挑战段」），若表为空或离线单测 mock 时提供稳定赛段集合；
  - 提供 `listSegments(sportType)` 查询所有激活赛段；
  - 提供 `matchSegments(request)`：
    - ① **粗筛**：基于轨迹经纬度包围盒通过 `ST_DWithin` / `ST_MakeEnvelope` 检索潜在相交赛段；
    - ② **精匹**：对候选赛段，在点列中按时间先后顺序寻找距离起点 $\le 35\text{m}$ 的点 $P_{start}$ 与距离终点 $\le 35\text{m}$ 的点 $P_{end}$；
    - ③ 校验累计距离与标称距离相对误差在合理阈值（$\pm 25\%$）内，计算净耗时 $elapsed\_seconds = P_{end}.ts - P_{start}.ts$。

### R4 赛段排行榜与 KOM 机制（`leaderboard-service`）
- 在 `LeaderboardService` 增加：
  - `recordSegmentEffort(segmentId, userId, recordId, elapsedSeconds)`：
    - Redis ZSet Key：`leaderboard:segment:{segmentId}`；
    - 若用户尚无成绩或当前耗时更短，则更新 ZSet，维护个人最好成绩 PR；
    - 判断当前用时是否成为该赛段全场第一名（KOM）；
  - `getSegmentLeaderboard(segmentId, size)`：
    - 调用 `opsForZSet().rangeWithScores`（耗时升序），解析用户 ID、查询批量昵称并格式化配速，第 1 名赋予 KOM 标识。
- 在 `LeaderboardController` 暴露 REST 端点：
  - `GET /api/leaderboard/segments/{segmentId}`
  - `POST /api/leaderboard/segments/{segmentId}/effort`

### R5 前端呈现与无缝协同（`web`）
- `client.ts` 补齐 4 个 API 方法：`getSegments`, `matchSegments`, `getSegmentLeaderboard`, `submitSegmentEffort`；
- `record-detail.page.vue` 挂载期若有轨迹点，调用 `matchSegments` 并在配速图表下方展示「🏁 赛段冲刺成果 (Segment Efforts)」卡片，支持点击查看该赛段 KOM 榜单；
- `leaderboard.page.vue` 新增单选或标签「赛段 KOM 榜」，支持下拉选择赛段并展示完整排行榜；
- `helpers.ts` 补齐 mock 路由分支，保持 `assertNoUnmocked` E2E 零缺口。

---

## 4. 门禁与纪律要求

1. **测试全量保持**：后端七模块 Surefire 测试保持 1063 测只增不减（`mapmatch-service` 36+，`leaderboard-service` 110+，0 失败 0 错误 0 跳过）；
2. **静态检查**：`leaderboard-service` 静态检查保持 0 Checkstyle violations；
3. **前端门禁**：vitest 63 测保持全绿，`vue-tsc` 类型检查退出码 0；
4. **契约门与 Token 门**：`mailbox-contract.sh` 退出码 0（判据 B 17 路径双向全等）；29 项 token 逐位只增不减；
5. **词面门与代码整洁**：四 locale（default/C/zh_CN.UTF-8/C.UTF-8）全数 ZERO_HIT；`git diff --check` 输出为空，纯 LF 无 BOM。
