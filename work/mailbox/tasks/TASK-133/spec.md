# TASK-133 mapmatch 逐点 SQL 改轨迹级批量预筛——R5 同步路径上的 DB 往返收敛

## 目标

findings F05（TASK-130 核实「仍在」）：`MapMatchService.java:110-126` 每个采样点一次
`ST_DWithin` 往返；往返次数 = 采样点数，上界由 `max-sampled-points=200`
（`MatchProperties.java:18` + `application.yml:47`）决定，**最坏 200 次 DB RTT/请求**，
且该调用在 R5 同步关键路径上（`R5OffRoadRule.java:64/68`）。

本任务把「逐点一次预筛」改为「按轨迹（分块）一次 bbox 预筛 + Java 内存逐点算垂距」：
候选边 SQL 的参照物从「单个采样点」放宽为「一块采样点的最小外接矩形」（`ST_MakeEnvelope`
+ `ST_DWithin`，仍走 `idx_road_edge_geom`），块内各点在 Java 侧复用同一候选集算垂距。
采样点数 > 分块阈值时按点序切块、每块一次预筛。**逐点 SQL 与逐点循环删除。**

## 口径裁定（本任务设计基线）

1. **语义等价（代码级证明，TASK-130 已给）**：旧实现 `:118` `best` 初值 = 查询半径 +
   `:121` 单调截断 `if (d < best)` ⇒ 垂距 ≥ 半径的候选边不改变结果。
   新候选集 = 「块外接矩形按 `radiusDeg` 外扩后 `ST_DWithin`」⊇ 「任一块内点的逐点候选集」
   （块外接矩形包含块内任一点 ⇒ 点到位矩形的平面距离 ≤ 点到任一点的平面距离）。
   超集多出的边若垂距 < 半径，则它本就落在该点度数半径内、必属逐点候选集——与假设矛盾；
   故多出的边垂距必 ≥ 半径，被 `best` 初值截断吞掉。**两实现输出逐位一致。**
2. **半径换算不变**：沿用 `DEG_FACTOR = 85_000.0`（< 实际每度米数，故度数是米半径的保守外扩，
   预筛有意过覆盖）。`pointToLineStringMeters` / `pointToSegmentMeters` / `thin` 一律不动。
3. **分块阈值**：新增 `mapmatch.match.prefilter-chunk-points`，默认 **50**
   = `max-sampled-points`(200) 的 1/4 —— 最坏 4 次预筛往返（相对旧 200 次降 50×），
   与 F05 原文「50 采样点」示意值同量级；单块更大则 bbox 过宽（跨城轨迹一块覆盖整城路网，
   预筛退化为全表），更小则往返数随点数回升。**不改** `max-sampled-points`（停止边界）。
4. **判定缝**：新增包内静态 `nearestEdgeIndex(lat, lng, wkts, cap)`——「初值 = cap + 单调截断」
   的唯一落点，既被 `distanceToNearestRoad` 复用，也是单测断言「最佳边 + 垂距」的观测面。
5. **边身份口径**：服务 SQL 只 `SELECT ST_AsText(geom)` 单列，故夹具里「best 边 id」以
   该边的 WKT 字面量承担（`E1-west-near` / `E2-east-far` / `E3-interfere` 三条夹具边各一 WKT）；
   给 SQL 加 `id` 列会改变行映射形态（`queryForList(String.class)` → RowMapper），不在本任务范围。

## 红绿取证

- **红①（往返数判别）**：`MapMatchServiceTest:36` 现成 mock `JdbcTemplate`，
  断言 `verify(jdbcTemplate, times(K)).queryForList(…5 参：envelope+半径…)` 且
  旧三参形态 `never()`；K = 分块数（13 点 → 1；200 点 → 4），与采样点数无关。
  基线实现在打桩面下 0 次命中 5 参形态 → 断言级红。
- **红②（语义等价判别）**：固定夹具（13 点 × 3 夹具边，含超半径干扰边、多点共享边、
  无候选封顶点），黄金值由**改前旧实现**在保真假 DB 下留档；新实现须逐位命中。
  改前新判别式（只认轨迹级形态）红，改后绿。
- **变异**：临时回退为逐点查询 → 复现红① → `cp` 还原 → `sha256sum -c` OK + `cmp` 零差异。
- **PostGIS 真库 IT**：Docker/scratch 可用则补 `ST_Intersects`/`ST_DWithin` 语义验证，
  不可用则登记未覆盖。

## 只改清单

- mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java
- mapmatch-service/src/main/java/com/sportverify/mapmatch/config/MatchProperties.java
- mapmatch-service/src/main/resources/application.yml
- mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchServiceTest.java
- work/mailbox/tasks/TASK-133/spec.md
- work/mailbox/tasks/TASK-133/handoff.md
- work/mailbox/PLAN.md

## 落盘说明

规范三件套的判定见 handoff「spec 三件套判定」一节：`spec/specs/` 与在途 `spec/changes/`
对「候选边预筛 / 采样点上限 / ST_DWithin」零命中（Grep 取证），本任务是**语义不变的实现级
性能优化**——对外契约（`MatchResultDTO` 字段、§空间匹配「基于空间索引返回候选道路」）
一行不改，故无规格对象可写，按台账两件套收口。

## 停止边界

- 不改 R5 规则语义与判定结果（`R5OffRoadRule.java` 一行不碰）
- 不改 `max-sampled-points`（采样点上限）与吸附阈值/查询半径的取值
- 不改 `thin` / `pointToLineStringMeters` / `pointToSegmentMeters` 的算法
- 不改表结构、不加依赖/插件/测试框架、不动网关与其他五个服务
- 不 push

## 复跑口径

- 定向：`bash scripts/verify/mvn-verify.sh --mode=offline --pl mapmatch-service test`
  （先红后绿 + 变异）
- offline 全量：`bash scripts/verify/mvn-verify.sh --mode=offline test`（逐模块用例数）
- 契约 rc=0（在途 `--baseline=<开工基线>` + 收口后无参数）
- 词面自检双 locale（`LC_ALL=C` 为准）

## 完成定义

- 单点 SQL 与逐点循环删除：`match` 内 DB 往返 = 分块数（编译面 + mock 计数即证明）
- 语义等价：固定夹具下新旧输出（聚合指标 + 逐点最佳边 + 垂距）逐位一致，黄金值留档
- 模块用例数不低于基线（6 → 9，净 +3）；既有 6 条用例零回退
- 红/绿/变异三段取证齐备（红取断言级原文与行号，不取编译红）
- 台账两件套 + PLAN 收口记录（绑定 commit 与门槛来源）
