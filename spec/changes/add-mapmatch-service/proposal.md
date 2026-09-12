# 提案：新增道路拓扑匹配服务（mapmatch-service，服务数 5→6）

## Why

现有校验引擎 R1-R4 是「启发式规则链」（速度/加速度/停留/距离一致性），本质是**对轨迹自身反常的检测**——它识别「匀速刷里程」「飞点」「原地抖动」，但**识别不了「轨迹不在任何真实道路上」**。这是作弊者最隐蔽、也最难用简单规则击穿的一类伪造：伪造一段速度、加速度、距离都「正常」的轨迹，但整条轨迹悬浮在没有任何道路的地方（如海面、楼顶、田野直线穿越）。

引入 OSM 道路拓扑匹配后，校验引擎从「规则链」升级为「规则链 + 空间真实性双保险」：R5 离路规则把轨迹点吸附到真实路网，计算「偏离路网的距离/占比」，偏离过大即判为可疑。这是技术天花板最高的一项（执行计划 P2 重度项），也是面试「为什么校验引擎不只有 if-else，还要空间计算」的最强谈资。

**背景**：
- 审批版 §2.1 的 OSM 拓扑匹配原为「讲设计」未落地；执行计划 P2「道路拓扑匹配（OSM）」列为进度超前才做。
- 已确认决策：独立 mapmatch-service（重）+ 真实 OSM 路网数据（非手工假路网）。
- 校验引擎现有 R1-R4 均为 `Rule` 接口的本地实现（`evaluate(List<Point>, Rules)` 纯计算）；R5 是首个**依赖外部空间服务**的规则，架构上需要独立路网服务承载空间数据与匹配算法。

**当前状态**：verify-service 有规则链 R1-R4 + `Rule` 接口 + `VerifyEngine` 流水线；无任何空间数据、无 PostGIS、无路网匹配能力；服务数 5（gateway/user/record/verify/leaderboard）。

**期望状态**：独立 mapmatch-service（第 6 服务）预载真实 OSM 路网，暴露匹配接口；verify-service 新增 R5 离路规则，校验时调 mapmatch 得到「偏离路网比例」，作为 SOFT/HARD 证据参与判定聚合；服务数 5→6。

## What Changes

- **新增 mapmatch-service 模块**：父 pom modules 加第 8 个子模块；独立端口（8085）、Nacos 注册、配置中心；包名 `com.sportverify.mapmatch`。
- **路网数据与存储**：引入 PostGIS（或内存图）承载 OSM 路网；docker-compose 追加 postgis service；提供路网导入脚本（下载真实 OSM PBF → osm2pgsql/自定义导入 → 路网表）。
- **匹配算法**：实现路网匹配（隐藏马尔可夫 HMM 或最近边投影，先落最近边投影 + 偏离度计算，HMM 作为进阶）；暴露 `POST /mapmatch/match` 输入轨迹点 → 输出 `{matchedRatio, avgOffRoadDistance, maxOffRoadDistance, offRoadRatio}`。
- **R5 离路规则**：verify-service 新增 `R5OffRoadRule`（实现现有 `Rule` 接口），`evaluate()` 内调 mapmatch Feign 接口，将 `offRoadRatio` 与阈值比较，产出 SOFT/HARD 证据（`R5_OFFROAD`）。
- **判定聚合扩展**：R5 证据并入现有 `hits` 列表，走既有「HARD 即拒 / SOFT 计分」逻辑，无需改 VerifyEngine 聚合框架（天然兼容）。
- **降级与熔断**：mapmatch 不可用时 R5 降级为「不命中」（不阻断判定，记 warn），沿用 record-service 已验证的 Resilience4j 熔断模式。
- **阈值可配**：`verify.rules.r5.*`（离路比例阈值、最小匹配点数）纳入 Nacos 配置，与 R1-R4 同源。
- **api 契约**：`MapMatchApi`（Feign 接口）+ `MapMatchResultDTO`（匹配结果）供 verify-service 调用。

## Impact

### 受影响的规范
- `spec/specs/sport-record-verify/spec.md` - 追加道路拓扑匹配能力域需求（ADDED）：路网数据、匹配服务、R5 离路规则、降级；服务划分 MODIFIED（5→6）。

### 受影响的代码
- 父 `pom.xml`（+mapmatch-service 模块）
- 新增 `mapmatch-service/`（全量路网匹配服务 + 导入脚本）
- `verify-service`：+`R5OffRoadRule`、+MapMatchApi Feign 客户端、+阈值配置
- `api`：+`MapMatchApi`、+`MapMatchResultDTO`
- `docker-compose.yml`（+postgis）
- 网关路由（+`/mapmatch/**` 或内部 Feign 不通网关）

### 用户影响
- 校验能力增强：伪造「无道路轨迹」将被 R5 识别并拦截；真实轨迹通过率不受影响（真实轨迹必在道路上）。

### API 变更
- 新增 `POST /mapmatch/match`（内部服务调用 + 管理端查询）；R5 判定并入现有校验结果。
- 无破坏性变更（R1-R4 行为不变，R5 为新增软/硬证据）。

### 需要迁移
- [x] 数据库迁移（PostGIS 路网表首次建）
- [ ] API 版本提升
- [ ] 用户沟通
- [x] 文档更新（README 架构图 5→6、速览手册、ADR 空间匹配选型）

## 时间线评估

大：约 2-3 周（P2 重度项，技术天花板项）。

## 风险

- **OSM 路网数据获取与导入成本**（下载 PBF、空间库建表、坐标系）→ 缓解：选单个城市切片（如上海）降低体积；导入脚本幂等可重跑；面试讲「全国路网分片/瓦片」设计。
- **匹配算法正确性与性能**（HMM 复杂度、大数据量轨迹）→ 缓解：先落「最近边投影 + 偏离度」跑通闭环，HMM 作为进阶优化（弹药：离线预计算 + 空间索引）；匹配限制在固定采样点数防超时。
- **R5 误判（如骑行沿河边/公园无路网区）** → 缓解：离路比例阈值可配 + 灰度（复用 rule_version 灰度机制，R5 可先灰度观察再全量）；R5 默认 SOFT，仅极端偏离记 HARD。
- **mapmatch 不可用拖垮校验** → 缓解：Feign 熔断 + 降级为「不命中」，校验主链路不挂（沿用 T11 熔断降级模式）。
- **技术卡壳超时**（既定决策）→ 缓解：本项是 P2 天花板项，若卡壳，降级为「最近边投影 + 离线预处理」最小可用版交付，HMM 与全国路网讲设计。

## 备注

- 这是「规则链 → 规则链 + 空间真实性」的质变，不是简单加一条 if；面试弹药：为什么离线匹配、为什么 HMM、为什么 PostGIS、空间索引原理、离路阈值的工程化（per-road-type 差异）。