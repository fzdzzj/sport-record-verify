# TASK-258 立项任务书（方案 B：E13 PostGIS 真实空间库复验）

> 状态：**已立项待派发**（2026-10-17 指导侧立项；开工基线 main @ 96caca4；FULL 档，双提交 + 全量七门禁 + 独立复核子 agent + 指导亲核）

## 1. 证据链（指导侧亲验坐实，2026-10-17）

1. **E13 核心关切与全仓现状坐实**：
   - `mapmatch-service` 的道路拓扑匹配是反作弊校验链路中 R5（`R5OffRoadRule`）离路判定的核心物理引擎；
   - 其底层候选边预筛依靠内联 SQL：
     `SELECT ST_AsText(geom) FROM road_edge WHERE ST_DWithin(geom, ST_MakeEnvelope(?, ?, ?, ?, 4326), ?)`
     与 Java 空间距离计算（`distanceToNearestRoad`、`nearestEdgeIndex`、`pointToCoordsMeters`）协同工作；
   - 既有单元测试 `MapMatchServiceTest` 全部采用 Mockito 打桩 `jdbcTemplate.queryForList(...)`，从未使用真实 PostGIS 数据库执行过该 SQL；全仓目前针对 `mapmatch-service` 没有任何 Integration Test（IT）；
   - 空间数据库交互存在潜在盲区：PostGIS `ST_MakeEnvelope` 的参数顺序（xmin, ymin, xmax, ymax 即 minLng, minLat, maxLng, maxLat）是否与实参对齐、SRID 4326 是否有效指定、GIST 空间索引 `idx_road_edge_geom` 是否真实生效而非 Seq Scan、WKT 结果返回串格式与经纬度解析是否精确无偏差、真实 OSM 7780 条路网边下的匹配准确率与极端离路伪造轨迹判定是否正确。
2. **指导侧现场真机亲验实测（2026-10-17 晚）**：
   - ① 容器与路网数据核验：`sport-verify-postgis`（PostgreSQL 16.4 + PostGIS 3.4）在端口 5433 正常运行，`road_db` 库中 `road_edge` 表结构完整，含有 `geom geometry(LineString,4326)` 与 `idx_road_edge_geom gist (geom)`，现有真实 OSM 导入路网数据恰 **7780** 条；
   - ② 真实 SQL 执行与 EXPLAIN 验证：
     执行 `EXPLAIN ANALYZE SELECT ST_AsText(geom) FROM road_edge WHERE ST_DWithin(geom, ST_MakeEnvelope(121.4700, 31.2300, 121.4750, 31.2350, 4326), 300.0 / 85000.0);`
     实测读数：
     - 执行计划：`Bitmap Heap Scan on road_edge` -> `Bitmap Index Scan on idx_road_edge_geom`；
     - `Index Cond: (geom && st_expand(...))` 证实精确命中 GIST 空间索引；
     - 执行耗时：实际执行时间仅 **7.915 ms**，无 Seq Scan，过滤 530 行候选边；
   - ③ WKT 输出解析核验：
     查询输出 `LINESTRING(121.4740169 31.2280424,...)`，与 `MapMatchService.parseWkt` 经纬度文本解析契约 100% 吻合，坐标系无翻转。

## 2. 改动白名单（仅限下列文件，双向全等）

- **C-01 业务/配置/测试/脚本（3 文件）**：
  1. `mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchSqlContractTest.java`（R2 纯 JVM 契约测试）
  2. `mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchPostgisIT.java`（R1 真实 PostGIS 集成测试）
  3. `scripts/mapmatch/verify-postgis.sh`（R3 自动化复验脚本）
- **C-02 台账（1 文件）**：
  4. `work/mailbox/tasks/TASK-258/handoff.md`（实施回填）

零触碰面声明：除上述 3+1 文件外，全仓业务代码、pom.xml、package.json、web 前端、docker-compose 等 100% 零触碰；生产 Java 业务代码（`MapMatchService`）零触碰。

## 3. 修法要求

- **R1（真实 PostGIS 端到端集成测试与环境感知门控）**：
  - 新增 `MapMatchPostgisIT.java`（IT 后缀，surefire 默认不隐式运行；内置环境变量与连接可用性探测门控：支持 `POSTGIS_HOST`、`POSTGIS_PORT`、`POSTGIS_USER`、`POSTGIS_PASSWORD`、`POSTGIS_DB`，默认自适应连接 `127.0.0.1:5433` 或 `5432`，若服务不可达则 `Assumptions.assumeTrue` 优雅跳过）；
  - 包含测试用例：
    1. `candidateSqlExecutesSuccessfullyOnRealPostgis`：验证 `CANDIDATE_SQL` 对 7780 条真实路网执行无报错，返回正确 WKT 列表；
    2. `gistSpatialIndexIsUsed`：执行 EXPLAIN 断言包含 `idx_road_edge_geom` 或 `Bitmap Index Scan`，绝非全表 Seq Scan；
    3. `matchRealisticOnRoadTrajectory_highMatchedRatio`：真实上海人民广场沿路轨迹，验证 `matchedRatio > 0.8`、`offRoadRatio < 0.2`、平均偏离米数 < 25m；
    4. `matchOffRoadFloatingTrajectory_cappedAtSearchRadius`：悬浮/大洋远距离伪造轨迹，验证 `offRoadRatio == 1.0` 且触发 300m 查询半径截断封顶；
    5. `adaptiveSplitBboxBehaviorOnRealDatabase`：超长跨度轨迹触发 D6 自适应递归中点拆块，验证真实候选集拉取与去重计算正常执行。
- **R2（纯 JVM 空间 SQL 与几何契约测试）**：
  - 新增 `MapMatchSqlContractTest.java`（Test 结尾，由常规 surefire 执行）；
  - 读取 `MapMatchService` 与 `scripts/mapmatch/road_schema.sql`，断言核心 SQL 语法、表名 `road_edge`、空间函数 `ST_DWithin`、`ST_MakeEnvelope`、SRID `4326`、列序 `(minLng, minLat, maxLng, maxLat, radiusDeg)` 与 GIST 索引定义；
  - 使 `mapmatch-service` surefire 测试计数由 35 稳定提升为 **36**（只增不减）。
- **R3（自动化复验脚本与运维工具）**：
  - 新增 `scripts/mapmatch/verify-postgis.sh`（带执行权限，LF 行尾）；
  - 支持一键执行 PostGIS 容器状态核验、7780 条路网边计数核对、EXPLAIN 验证、并可通过指定参数触发 `MapMatchPostgisIT` 实跑。

## 4. 门禁与验证要求（FULL 档）

- 唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（经 `D:\git\Git\usr\bin\bash.exe --login`），全量七模块，surefire 基线 **63/78/221/321/203/110/35** 只增不减（mapmatch 35 -> 36，净增 1 测）；
- 契约门：`scripts/verify/mailbox-contract.sh --open=TASK-258 --baseline=96caca4`；
- token 门 29 项逐位持平零减少（期望值口径：**C-02 树 = 2133 + 29 = 2162**）；
- 词面门模式文件法 + 探针阳性对照（四 locale 全 ZERO_HIT）；
- checkstyle delta=0；
- `git diff --check` 净；
- 修改文件保持既有行尾（LF/CRLF），新测试文件纯 LF 结尾；
- 双提交 C-01（业务/配置/测试/脚本）/ C-02（台账）；`git status --porcelain` 收口为空；
- 独立复核子 agent 审查把关。

## 5. 受限声明（预登记）

1. 集成测试 `MapMatchPostgisIT` 依赖外部 Docker PostGIS 容器（`sport-verify-postgis`），在 CI 或缺少容器环境时通过 `assumeTrue` 优雅跳过，离线构建与 CI 权威保障由 `MapMatchSqlContractTest` 承载；
2. 真实路网边数据 7780 条仅覆盖上海核心切片区域，测试样例轨迹选用该切片范围内的坐标，不外推至全量全球路网；
3. 生产代码 `MapMatchService` 保持零改动，本课题为端到端复验与测试基础设施补全。

## 6. 停止条件与升级路径

- 若在真实 PostGIS 上执行 `CANDIDATE_SQL` 出现语法报错或参数类型不匹配，立即停手；
- 若 EXPLAIN 证实未走 GIST 空间索引而走 Seq Scan，立即停手；
- 若 surefire 任何模块测试失败或低于基线立即停手；
- 严禁触碰白名单外任何文件。

## 7. 验收判据

- C-01+C-02 双提交落库、白名单双向全等；
- 七门禁全绿、surefire 净增 +1（mapmatch 35→36）；
- 真实 PostGIS IT 实测通过，EXPLAIN 证实命中 GIST 空间索引；
- 独立复核五块全 PASS 零阻断；
- 指导亲核三块全 PASS。
