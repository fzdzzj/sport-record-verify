# TASK-133 回传：mapmatch 逐点 SQL 改轨迹级批量预筛

【回传】TASK-133 已收口：候选边预筛由「每采样点一次 `ST_DWithin`」改为「按轨迹分块一次
`ST_MakeEnvelope` + `ST_DWithin`，块内各点复用候选集在 Java 侧算垂距」；逐点 SQL 与逐点循环删除。
模块用例 6 → 10，offline 全量 307 → 311 全绿；红/绿/变异三段取证 + 真实 PostGIS 复核齐备。

## 结论

| 项 | 结果 |
| --- | --- |
| DB 往返数（采样上限整值 200 点） | 200 → **4**（= 分块数 `ceil(200/50)`） |
| DB 往返数（13 点夹具 / 20 点小轨迹） | 13 → 1 / 20 → 1 |
| 旧三参逐点形态调用次数 | 每点 1 次 → **0**（`verify(never())` 判据） |
| 语义等价（聚合指标） | 固定夹具 6 个 DTO 字段逐位命中黄金值 |
| 语义等价（逐点 best 边 + 垂距） | 13/13 点逐位命中黄金值；真库独立复核 13/13 相等 |
| 范围 | 只动 mapmatch-service 4 个文件 + 台账；**无规格对象变更**（判定见「规格判定」节） |

## 只改清单

- mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java
- mapmatch-service/src/main/java/com/sportverify/mapmatch/config/MatchProperties.java
- mapmatch-service/src/main/resources/application.yml
- mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchServiceTest.java
- work/mailbox/tasks/TASK-133/spec.md
- work/mailbox/tasks/TASK-133/handoff.md
- work/mailbox/PLAN.md

## 落盘说明

### 规格判定（spec 三件套为何未建）

`LC_ALL=C git grep -n "候选边\|预筛\|ST_DWithin\|采样点上限\|max-sampled" -- spec/specs/`
→ **0 命中**；`spec/changes/` 在途 2 个变更（`wire-verify-outbox`、`adopt-native-mq-retry`）
grep 同样 0 命中。主 spec 与「空间匹配」相关的两条需求
（`独立路网匹配服务 → 匹配接口返回`、`真实路网数据 → 数据可查询`）只约束**对外行为**
（返回 `matchedRatio`/`avgOffRoadDistance`/`offRoadRatio`；「基于空间索引返回候选道路」），
本任务**一行不改对外契约**、也**仍然基于空间索引**（真库 EXPLAIN 实证 `Index Scan using
idx_road_edge_geom`），属语义不变的实现级性能优化 → 无 delta 可写，按台账两件套收口。

### 设计口径

1. **候选边 SQL**：`SELECT ST_AsText(geom) FROM road_edge WHERE ST_DWithin(geom,
   ST_MakeEnvelope(?, ?, ?, ?, 4326), ?)`，实参 = 块外接矩形四界 + 半径（度）。
   仍走 GIST（`geom && ST_Expand(envelope, r)`，真库 EXPLAIN 已证）。
2. **分块阈值**：新增 `mapmatch.match.prefilter-chunk-points`，默认 **50** = 采样上限 200 的 1/4
   → 最坏 4 次预筛；`max-sampled-points` 保持 200 不动（停止边界）。
3. **语义不变的唯一落点**：包内静态 `nearestEdgeIndex(lat, lng, wkts, cap)`——`best` 初值 = cap +
   单调截断 `d < best`。块级候选集 ⊇ 逐点候选集；超集多出的边若垂距 < cap，则其度数距离
   `d/85,000 < 半径(度)`，说明它本就在该点的度数半径内、必属逐点结果，与「多出」矛盾
   → 多出的边垂距必 ≥ cap，被截断吞掉。
4. **`ST_Intersects` 未采用**：任务提到的另一种形态需先 `ST_Buffer` 出多边形，包围盒更大、
   选择性与索引效率更差；`ST_DWithin(geom, envelope, r)` 语义等价且直接落在
   `&&  ST_Expand` 上，故取后者（显式取舍，记于「未决」节第 6 条）。
5. **边身份口径**：SQL 单列 `ST_AsText(geom)`，故「best 边 id」在取证中以边 WKT 字面量承担
   （给 SQL 加 `id` 列会改行映射形态为 RowMapper，不在本任务范围）。

## 红绿取证

### 红相（改前，`bash scripts/verify/mvn-verify.sh --mode=offline --pl mapmatch-service test`，`.trae/tmp/t133-red2.log`，rc=1）

`Tests run: 9, Failures: 3, Errors: 0, Skipped: 0`——3 条新判别式全红，既有 6 条全绿
（先红只加判别式：既有用例打桩仍按基线 3 参形态）。

| 判别式 | 失败类型 | 关键原文 | 行号 |
| --- | --- | --- | --- |
| 红①-a 单块往返数 | Mockito 实参不符 | `Argument(s) are different! Wanted: queryForList(<any String>, class String, <any double>×5)`，`Actual invocations` 指名 `-> at com.sportverify.mapmatch.service.MapMatchService.distanceToNearestRoad(MapMatchService.java:112)`（3 参、每点一条 SQL） | `MapMatchServiceTest.java:280 → verifyTrajectoryPrefilter:164` |
| 红①-b 跨块往返数 | 同上 | 同上（actual 为 200 次逐点调用） | `MapMatchServiceTest.java:307 → :164` |
| 红② 聚合黄金值 | 断言级 | `expected: 0.38461538461538464` / `but was: 0.0`（3 参形态一律空候选 → 全部退化为 300m 封顶） | `MapMatchServiceTest.java:327` |

### 绿相（改后，`.trae/tmp/t133-green1.log`，rc=0）

`MapMatchServiceTest: Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`；
既有 6 条零回退，新增 4 条（单块往返数 / 跨块往返数 / 聚合黄金值 / 逐点黄金值）。

### 变异（`.trae/tmp/t133-mutation.log`）

临时把 `match` 的块循环回退为逐点查询（并恢复 3 参逐点 SQL）→ `Tests run: 10, Failures: 4`
（两条往返数判别式 + 聚合黄金值 + 既有 `沿路轨迹` 全红，红① 复现）→ `cp` 还原 →
`sha256sum -c` 两行 `OK` + `cmp` **零差异**（冻结哈希 `89cf59eec0b3aad63276171050feba48d6a3fb9e9b5255ca1fa6262a20554528`）。
（留底/还原顺序：冻结修订 → 留底 → 变异 → 还原 → 校验。）

### 黄金值对比表（夹具 13 点，垂距单位米；旧 = 改前逐点实现留档，新 = 改后块级预筛）

夹具：E1 = `LINESTRING(121.4700000 31.2302000,121.4736000 31.2302000)`（近多点共享边，
P0-P4 吸附）；E2 = `LINESTRING(121.4764000 31.2303000,121.4805000 31.2303000)`
（另一条共享边，P7-P11 离路）；E3 = `LINESTRING(121.4700000 31.2332000,121.4800000 31.2332000)`
（**超半径干扰边**：距轨迹线 356.22m > 半径 300m，在块级候选集内但永不取胜）。

| # | 输入 (lat, lng) | 旧 best 边 / 垂距 | 新 best 边 / 垂距 | 一致 | PostGIS 大地线复核 |
| --- | --- | --- | --- | --- | --- |
| 0 | 31.2300, 121.4700 | E1 / 22.2640 | E1 / 22.2640 | ✅ | 22.1747 |
| 1 | 31.2300, 121.4709 | E1 / 22.2640 | E1 / 22.2640 | ✅ | 22.1757 |
| 2 | 31.2300, 121.4718 | E1 / 22.2640 | E1 / 22.2640 | ✅ | 22.1761 |
| 3 | 31.2300, 121.4727 | E1 / 22.2640 | E1 / 22.2640 | ✅ | 22.1757 |
| 4 | 31.2300, 121.4736 | E1 / 22.2640 | E1 / 22.2640 | ✅ | 22.1747 |
| 5 | 31.2300, 121.4745 | E1(端点) / 88.5158 | E1(端点) / 88.5158 | ✅ | 88.5676 |
| 6 | 31.2300, 121.4754 | E2(端点) / 100.8773 | E2(端点) / 100.8773 | ✅ | 100.9134 |
| 7 | 31.2300, 121.4763 | E2 / 34.7261 | E2 / 34.7261 | ✅ | 34.5996 |
| 8 | 31.2300, 121.4772 | E2 / 33.3960 | E2 / 33.3960 | ✅ | 33.2632 |
| 9 | 31.2300, 121.4781 | E2 / 33.3960 | E2 / 33.3960 | ✅ | 33.2638 |
| 10 | 31.2300, 121.4790 | E2 / 33.3960 | E2 / 33.3960 | ✅ | 33.2637 |
| 11 | 31.2300, 121.4799 | E2 / 33.3960 | E2 / 33.3960 | ✅ | 33.2629 |
| 12 | 31.2400, 121.4750 | 无候选 / **300.0（封顶）** | 无候选 / **300.0（封顶）** | ✅ | 逐点候选空；块级候选最小 753.9293 → 仍封顶 |

- 逐点断言口径：`Math.abs(新 − 黄金值) < 1e-9`（1e-9 m = 1 纳米，远小于 double 传播量级）；
  聚合指标 `matchedRatio` / `offRoadRatio` / `maxOffRoadDistance` 用 `isEqualTo`（位级相等），
  `avgOffRoadDistance` 用 `within(1e-9)`。
- 夹具覆盖三类形态，均已由真库 EXPLAIN/SQL 反证：**超半径干扰边**（E3 对 P0-P11）、
  **多点共享边**（E1 对 P0-P4、E2 对 P7-P11）、**无候选封顶点**（P12）。
- 逐点用例同时断言「块级候选集」与「逐点候选集」在同一输入上给出**同一条最佳边**
  （`轨迹级预筛_固定夹具_逐点最佳边与垂距逐位命中黄金值`）。

## DB 往返数前后对比

| 场景 | 采样点 | 旧往返 | 新往返 | 判据（`MapMatchServiceTest`） |
| --- | --- | --- | --- | --- |
| 小轨迹（单块） | 20 | 20 | **1** | `verify(times(1))` + 外接矩形实参逐项断言 |
| 采样上限整值 | 200 | 200 | **4** | `verify(times(4))` + 首块 minLat / 末块 maxLat 夹住轨迹 |
| 夹具轨迹 | 13 | 13 | **1** | 聚合黄金值用例（同一 mock 计数路径） |
| 旧三参形态 | 任意 | 每点 1 次 | **0** | `verify(never())` |

- 往返数上界：`ceil(max-sampled-points / prefilter-chunk-points) = ceil(200/50) = 4`，
  **与采样点数无关**（单块内恒 1 次）。
- 单次预筛的实参断言：`minLng / minLat / maxLng / maxLat / 半径(度)`，半径 = `300 / 85_000`
  （沿用 `DEG_FACTOR`，`within(1e-12)`）。

## 真库验证（PostGIS scratch，非仓内 IT 类）

本机 5432 被原生 PostgreSQL 占用（TCP 可连但 `postgres` 口令认证失败 = 非 compose 实例），
compose 栈未起；`postgis/postgis:16-3.4` 镜像本地在位 → 起**临时容器**（`--rm -p 5433:5432`，
验证后已 stop + 自动移除，未留卷/容器），用 psql 直测（`.trae/tmp/t133-postgis-check.sql`）：

1. **候选集包含关系**：逐点（点 + 度数半径）候选 id vs 整轨迹 envelope 候选 id 逐点打印
   （P0-P3 = `1,3`；P4-P7 = `1,2,3`；P8-P11 = `2,3`；P12 = 空；envelope 恒 `1,2,3`）→
   `new_superset_of_old = t`（**超集关系在真库成立**）。
2. **距离独立复核**：`ST_Distance(…::geography, geom::geography)` 逐点取最小，
   旧形态 min == 新形态 min **13/13 相等**；P12 旧形态无候选、新形态最小 753.9293m 仍被封顶。
3. **退化 envelope**：`ST_MakeEnvelope(x, y, x, y, 4326)`（单点块）语法可用，
   `ST_DWithin` 照常按包围盒处理（`degenerate_env_hits = 2`）。
4. **索引命中**：`EXPLAIN (COSTS OFF)` → `Index Scan using idx_t133_geom`，
   `Index Cond: (geom && st_expand(<envelope>, 0.0035294…))`（新 SQL 仍走 GIST）。
5. **顺带口径发现**：Java 侧局部等距圆柱投影（常量 111320 米/度）与真库大地线相比，
   在 31.23°N 系统性偏高约 **0.38%**（22.264 vs 22.175 / 33.396 vs 33.263），
   与 `MapMatchService` 类注释「全球变化 <0.5%」一致——属**既有实现口径**，本任务未改。
6. **未补仓内 IT 类**：`--it` 入口只定向 `LeaderboardDailySummaryMapperMysqlIT`（`mvn-verify.sh`），
   新增 mapmatch IT 无法经唯一验收入口执行，且 `scripts/verify/**` 不在本任务只改清单
   → 按「真库 IT 类未覆盖」登记（真库语义本身已按上述 1-4 条直测）。

## 逐模块用例数

| 模块 | 基线 | 本次 | 变化 |
| --- | --- | --- | --- |
| common | 20 | 20 | — |
| gateway | 30 | 30 | — |
| user | 33 | 33 | — |
| record | 80 | 80 | — |
| verify | 88 | 88 | — |
| leaderboard | 50 | 50 | — |
| **mapmatch** | **6** | **10** | **+4** |
| 合计 | **307** | **311** | **+4** |

## offline 汇总与契约、词面

| 项 | 结果 | 证据 |
| --- | --- | --- |
| offline 全量 | `bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0**，`BUILD SUCCESS`，311（20/30/33/80/88/50/10）全绿零跳过 | `.trae/tmp/t133-offline.log` |
| 定向（红/绿/变异） | 三次 `--pl mapmatch-service test`：rc=1（9/3 红）→ rc=0（10/0）→ rc=1（10/4 变异） | `t133-red2.log` / `t133-green1.log` / `t133-mutation.log` |
| 词面自检 | `LC_ALL=C`（CI 语义）**ZERO-HIT** rc=0；默认 locale 出现 `api/.../MapMatchResultDTO.java` 两行命中 = **本机 locale 伪影**（该文件本次未改，属既有已登记现象，判据以 `LC_ALL=C` 为准） | `.trae/tmp/wording-check-133.sh` 双 locale 各一次 |
| 契约（在途） | `bash scripts/verify/mailbox-contract.sh --baseline=2b4cb8a` → 判据 A `两件套齐全：TASK-133`；**`TASK-133：判据 B 通过（只改清单与实际改动集一致）`**（7 项声明零多报、零未声明）。整体 rc=1 为公共文件 `PLAN.md` 过冲（历史段逐一报过冲项），非本任务清单不一致 | `.trae/tmp/t133-contract-inflight.log` |
| 契约（收口后无参数） | 见 PLAN 验收记录 `<C>` 回填提交 | `.trae/tmp/t133-contract-final.log` |

## 未决与后续

1. **`findings-summary.md` 的 F05 标注未改**（该文件不在只改清单）：F05 结论「仍在」现已由本任务收口，
   需另立微变更把该行状态改为「已收口（TASK-133）」（改动集一旦越界即触发契约判据 B「改动集未声明」）。
2. **真库 IT 类未补**（理由见「真库验证」第 6 条）：真库语义已直测，但**没有常驻判据**——
   `ST_MakeEnvelope` + `ST_DWithin` 的语义回归只能靠离线 mock（夹具桩 + 本机 psql 记录），
   CI 不覆盖。属显式未覆盖，不得写成通过。
3. **单块 bbox 由块内点确定**：轨迹出现跳点/瞬移时，该块外接矩形被拉大（本任务夹具 P12 即此形态），
   预筛选择性下降——**正确性无损**（超集只多不少），但极端形态下相邻块的预筛代价可能超过逐点。
   后续若要压这条边界，可按「点间距/航向」切块而非纯按点数切块（本次未做）。
4. **距离口径 ±0.38%**：局部等距圆柱投影用常量 111320 米/度，在 31.23°N 偏高 0.38%；
   吸附阈值 25m 下最大绝对偏差 ~0.1m，对「在路网上」判定无实质影响。若要更准可换成
   按纬度查表/大地线——**不在本任务范围**（属既有实现口径）。
5. **ADR-0006 措辞未点明预筛粒度**：`docs/adr/0006-空间匹配.md:27` 写「GIST 索引预筛候选边
   （`ST_DWithin` 半径按 85km/度保守过覆盖）」——本任务后仍然成立（决策未变），只是未写明
   「参照物 = 块外接矩形」；`docs/**` 不在只改清单，未改。
6. **`ST_Intersects` 形态未采用**（显式取舍，见「设计口径」第 4 条）：若后续要求与
   「缓冲多边形相交」语义严格对齐，可另立变更改成 `ST_Intersects(geom, ST_Buffer(envelope, r))`，
   代价是包围盒扩张更大、索引选择性更差。
7. **`PLAN.md` 公共文件过冲**：本任务改动集含 `PLAN.md`，历史 handoff 正文提到该文件名即触发
   在途契约的强校验交叠（既往多次登记），非本任务清单不一致。
