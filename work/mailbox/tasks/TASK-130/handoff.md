# TASK-130 Handoff

**侦察类任务：零代码改动**。产出下一轮立项情报，未 push。

## 一句话结论

清单 4 项（F05 / F06 / F15~F17 / 治理面）**全部仍在、零改动**；其中 F05/F06/F15/F15~F17
五条曾被 **TASK-103 声称修复但代码零落地**（与 TASK-102 同类台账虚报，TASK-128 已核实后者）。
故旧台账不能作为方案输入，需按本报告重新立项。另发现 F08 残留（网关自身 health 详情仍对外）
与 F18（索引未加）两条同源未落地。

## 环境可行性（是否需 scratch 库 / 全栈）

| 项 | 离线判别式 | scratch 库 | 全栈 |
|---|---|---|---|
| F05 | 可（Mockito 往返计数） | 语义等价性**建议** PostGIS IT | 不需 |
| F06 | 可（Mockito ZSet 调用参数） | 不需 | 不需 |
| F15 | 可（Mockito mapper 调用计数） | GROUP BY 正确性**建议** MySQL mapper IT | 不需 |
| F16 / F17 | 可（纯离线） | 不需 | 不需 |
| 治理面 | 可（Servlet 过滤器单测 + 配置断言） | 不需 | 直连实测**建议**但本次不可行 |

**本次 DB 侧实测不可行（已如实登记，非「通过」）**：Docker daemon 未运行
（`docker ps` → `cannot find npipe dockerDesktopLinuxEngine`）；本机 `.env` 指向容器端口
`MYSQL_PORT=3307` / `POSTGRES_PORT=5433`，两端口均 CLOSED；本机 3306 / 5432 虽 OPEN 但属
**原生** MySQL 8.0.44 / PostgreSQL 16.14（非本项目实例），`root/root`、`postgres/postgres`
认证均失败 → 无任何可达的业务库或 scratch 库。报告中所有量化一律标注为「静态推演假设」。

## 侦察报告

### 1. F05 mapmatch 逐点一次 SQL

**现状证据**：`mapmatch-service/.../service/MapMatchService.java:68-75` 逐点循环调用
`distanceToNearestRoad`；`:110-126` 该方法内 `:112-117` 每点执行一次
`SELECT ST_AsText(geom) FROM road_edge WHERE ST_DWithin(geom, ST_SetSRID(ST_MakePoint(?,?),4326), ?)`
——findings 原文行号 `110-126` **逐字吻合**。`road_edge` 有 GIST 索引
（`scripts/mapmatch/road_schema.sql:23`）。

**影响面量化假设**：单次 `POST /match` 的 DB 往返 = 采样点数。上界由
`MatchProperties.java:18` + `mapmatch-service/src/main/resources/application.yml:47`
（`max-sampled-points: 200`）决定 → **最坏 200 次 RTT/请求**；findings 举例的「50 采样点」
是示意值而非配置上界，立项时按 **200** 计。该调用在 R5 规则的**同步**关键路径上
（`verify-service/.../algorithm/rule/R5OffRoadRule.java:64 → :68 mapMatchApi.match`，
catch 才降级），故 P95 直接吃这 N 次往返。

**修复方案草案与分级**：

- **S1（P1，推荐）**：按轨迹一次预筛候选边——`ST_DWithin(geom, <轨迹折线>, radiusDeg)`
  或 `ST_Intersects + ST_Expand(bbox)`，Java 侧仍逐点算垂距 → 往返 N→1。
  **语义等价性有代码级证明**：`best` 初值为 `searchRadiusMeters`（`:118`），
  循环只做 `if (d < best)`（`:121`），候选集扩大为超集后，超出半径的候选**无法**改变结果
  （第 118/121 行构成单调下界截断）→ 与「无候选即按半径封顶」语义一致。
- **S2（P1，折中）**：按 K 点分块（K≈50）→ 往返 N→⌈N/K⌉，规避密集路网单次候选爆炸。
- **S3（P2）**：临时表 / `VALUES ... LATERAL` 联表；PostGIS 语法重、需真库验证，不建议本期。
- 风险：S1 单次返回候选边数无界（长轨迹 × 密集城区），建议 S1 叠加 K 分块与候选计数指标。

**红绿判别式可行性：高**。`MapMatchServiceTest.java:36` 已 `mock(JdbcTemplate.class)`，
`:61/:78/:93` 以 5 参签名打桩 → 判别式 = 200 点轨迹上
`verify(jdbcTemplate, times(1)).queryForList(...)`（改前 200 次红 / 改后 1 次绿）。
注意：批量化会改 SQL 参数元数，同文件 3 处桩须同步改签名（该测试文件进未来任务只改清单）。

**是否需 scratch 库或全栈**：往返计数不需；**PostGIS 语义等价性建议 scratch PostGIS IT**
（本次不可用，记未覆盖）。

### 2. F06 好友榜全量拉取

**现状证据**：`leaderboard-service/.../service/LeaderboardService.java:246-271` `topFriends`；
**`:259-260`** `stringRedisTemplate.opsForZSet().reverseRangeWithScores(OVERALL_ZSET_KEY, 0, -1)`
= 全量拉全榜。findings 原文写的 `:250-251` **已漂移**（该处现为好友列表 Feign 调用续行），
应订正为 `:259-260`。好友数由 `:77 FRIEND_FETCH_SIZE=1000` 与 `:249 listFriends(...)` 截断；
`topFriends` **无 `@Cacheable`**（对比 `topOverall` `:227` 有 L1+L2）。

**影响面量化假设**：榜上 N 用户 → 每次好友榜查询传输并反序列化 N 个 (member, score) 元组；
N=10 万时单次约 10 万对象/请求。因无缓存，**每个好友榜请求都付全量成本**，无摊薄；
反序列化与分配是主要开销（Redis 侧 ZREVRANGE 本身 O(log N + M)）。单请求 `size` 上界 1000
（`:210 Math.min(size, 1000)`）。

**修复方案草案与分级**：

- **S1（P1，推荐）**：分批 `ZREVRANGE`（批 K=500）边拉边过滤，凑满 `size` 即停。
  **rank 语义等价**：`assemble` 的 rank = 过滤后序号 `i+1`（`:285`），分批保持分数降序
  → 取到同一序列即同一 rank。常态成本从「N 传输 + N 分配」降为「≤命中位置」；
  最坏（好友全在榜尾）仍 O(N) 但按 K 分片，不再单次巨量分配。
- **S2（P2）**：Redis 侧 `ZINTERSTORE` 临时键（好友集合 ∩ 总榜）→ 传输量降为 O(size)；
  代价是读路径写 Redis + 临时键 TTL 管理。
- **S3（P2）**：维护好友榜增量结构（写放大、复杂度高，不建议本期）。

**红绿判别式可行性：高**。`LeaderboardServiceTest.java:79` 已 `mock(ZSetOperations.class)`，
且 `:254` 已为好友榜打桩 `(KEY, 0, -1)` → 判别式 = 断言新调用参数（如 `(KEY, 0, 499L)`）
+ `verify(zSetOps, never()).reverseRangeWithScores(KEY, 0, -1)`；改前该断言必红。
同文件 `:474` 的总榜桩不受影响。

**是否需 scratch 库或全栈**：均不需（纯 Redis API 语义）；真 Redis 往返 IT 可选（容器未起，未覆盖）。

**附带发现（登记不立项）**：`listFriends(page=1, size=1000)` 只取第一页
（`LeaderboardService.java:249`）→ 好友数 >1000 的用户好友榜**静默截断**，无分页循环。
属独立小缺陷候选，可并入 F06 立项或单列。

### 3. F15~F17 点赞三条

**是否已被 TASK-108 系列顺带解决：否**。TASK-108（每日榜单快照，`190ab1f`）只动
`LeaderboardService` 与新增 DailySummary，未触碰 `RecordLikeService`；
`git log` 显示 `RecordLikeService.java` 全史仅 4 笔（`d9c5c15` 初版 / `e5e30f9` flush 事务 /
`f48c792` 注释 / `6daf863` 词面），无一条命中下列三处。**三条全部仍在、零改动。**

**F15 现状证据**：`record-service/.../service/RecordLikeService.java:264-291`
（`:263` 为 `@Scheduled`）`reconcileLikeCounts`：`:272 selectDistinctRecordIds()` 一次，
`:274` 循环内 `:275 selectUserIdsByRecordId(recordId)` **逐记录一次** → N+1 往返；
另有每记录 `DEL + SADD` 两次 Redis 往返（`:280-284`）。findings 行号 `263-291` 吻合。
**精度订正**：findings 标题「全表扫」不准确——`record_like` 主键为 `(record_id, user_id)`
（`sql/02-record-db.sql:64`），`SELECT DISTINCT record_id`（`RecordLikeMapper.java:68-69`）
走主键索引扫描，`COUNT(*)`/`user_id` 查询是主键前缀查找，**均非全表扫**；
真正的成本是**往返次数**：O(N) SQL RTT + O(2N) Redis RTT，周期固定 10min（`:86`）。

**F16 现状证据**：`RecordLikeService.java:80 FLUSH_BATCH = 200`（硬编码）、
`:82 FLUSH_FIXED_DELAY_MS = 5_000`、`:213 range(PENDING_QUEUE_KEY, 0, FLUSH_BATCH - 1)`
→ 吞吐天花板 **200/5s = 40 ops/s**。队列 `like:pending:ops` 无长度上限、无指标、无告警
（全类零 Micrometer 引用），flush 亦无背压 → 持续超 40 ops/s 则队列单调增长。
findings 行号 `80-82` 吻合。

**F17 现状证据**：`RecordLikeService.java:329-339 readCount`：`:330` GET → miss →
`:334 countByRecordId` + `:336` SET 回填；**无 SETNX / 无单飞 / 无短 TTL** →
并发 miss 时 N 个线程各回源一次 DB 并各写一次 SET（击穿）。
findings 行号 `329-339` **逐字吻合**。

**修复方案草案与分级**：

- **F15（P1）**：`RecordLikeMapper` 加一条 `GROUP BY record_id` 聚合（一次出全部
  `(record_id, COUNT(*))`）；成员集重建只对「Redis 计数 ≠ DB 计数」的记录执行，
  不再逐记录查 userIds。可选再加一条 `SELECT record_id, user_id` 一次性取成员。
- **F16（P1/P2，可并入 F15 同一变更「点赞规模化」）**：`FLUSH_BATCH` →
  `@Value("${record.like.flush-batch:200}")`；`LLEN` 暴露 Micrometer Gauge + 阈值告警。
- **F17（P1，二选一，**需用户拍板**）**：
  ① 进程内 Caffeine `get(key, loader)` 单飞（简单，但多实例各一份，
  与 F13「Caffeine 单层多实例不一致窗口」的既有结论口径需对齐）；
  ② SETNX 短锁 `lock:like:count-init:{recordId}`（跨实例一致，代价是未持锁线程自旋）。

**红绿判别式可行性：高**（三条皆 Mockito 可判，`RecordLikeServiceTest` 已 mock
`RecordLikeMapper` 与 `ValueOperations`）：

- F15：`verify(recordLikeMapper, times(1)).<新聚合方法>()` +
  `verify(recordLikeMapper, never()).selectUserIdsByRecordId(anyLong())`（无漂移场景）；改前必红。
- F16：`@Value` 字段单测注入需用反射（`ReflectionTestUtils`）→ 判别式可行性**中-高**，
  需先确认仓内既有 `@Value` 字段的单测注入先例，否则改走构造注入。
- F17：**建议用确定性判别式**（断言 `setIfAbsent` 被调用 / Caffeine loader 只执行一次），
  避免 N 线程 + latch 的 flaky 形态。

**是否需 scratch 库或全栈**：F15 的 GROUP BY 正确性**建议 scratch MySQL mapper IT**
（仓内已有 `LeaderboardDailySummaryMapperMysqlIT` + `TASK108_IT_*` 先例，
record-service 尚无 IT 先例）；本次不可用 → 未覆盖。F16/F17 纯离线。

### 4. 治理面：服务侧不校验角色

**InternalApiAuthFilter 覆盖面**：

- 只保护 `/internal/**`：`common/.../internal/InternalApiAuthFilter.java:68`
  `if (!authEnabled || !isInternalPath(request.getRequestURI()))` 直接放行；
  判据 `:89-95`（`/internal` 或 `/internal/` 前缀）。
- 注册方式 `@Component`（`:31`）；5 个后端服务启动类全部
  `scanBasePackages = "com.sportverify"`（`UserApplication.java:14` / `RecordApplication.java:19` /
  `VerifyApplication.java:19` / `LeaderboardApplication.java:21` / `MapMatchApplication.java:19`）
  → **5 个服务均加载**；网关是 WebFlux 且未扫该包 → 不加载（正确，网关无 `/internal` 路由）。
- **治理面端点不在 `/internal/**` 下 → 不在覆盖面内**：
  - `/admin/**` 经 `StripPrefix=1` → verify-service `/api/appeals/**`
    （`VerifyController.java:22`，终判翻案）
  - `/verify/rules/**` → `/rules/**`（`RuleVersionController.java:27`；写端点 `:34` POST
    `/versions`、`:40` PATCH `/versions/{id}/gray`、`:47` POST `/versions/{id}/activate`）
  - `/leaderboard/api/leaderboard/daily`（跨用户报表）

**角色校验唯一落点**：`gateway-service/.../auth/AuthGlobalFilter.java:102-105`
（`adminRoleCheckEnabled && isAdminPath(path) && !"ADMIN".equals(identity.role())` → 403/1002）；
角色来源为 JWT claim，`:111` 注入 `X-Role`；治理面路径与开关见
`gateway-service/src/main/resources/application.yml:120`（白名单）/`:121-129`（admin）。
**下游服务侧零角色概念**：全仓服务代码 `X-Role` 读取点 **0**
（grep 仅命中网关与文档），`@PreAuthorize/@Secured/@RolesAllowed` 全仓 **0 命中**；
`RuleVersionController.java:22` javadoc 明写「鉴权只依赖网关过滤（本服务不自行校验 token，
角色由网关注入 X-Role）」。

**直连面缓解现状（关键限定）**：compose 把 8081-8085 绑回环
（`docker-compose.services.yml:62/97/129/161/191`，仅 `:34 "8080:8080"` 对外）；
**但该缓解只在 compose 路径成立**——全仓 `server.address` **0 命中**，
宿主机 `java -jar` 直跑（F20 记录的当前主路径）默认绑 `0.0.0.0` → 同网段可直连；
容器网络内任何容器可 `http://verify-service:8083/rules/**` 直连。
`/internal/**` 有共享密钥保护（`InternalApiHeaders.java:12` `X-Internal-Token`），
**治理面路径没有**。compose 头部注释（`:14-16`）已自述此边界（「角色靠网关注入的 X-Role，
而 header 对能直连服务端口的人等于不存在」）。

**两个方向的论据**：

- **方向 A｜维持 ADR-0007 边界（网关唯一信任边界）**
  - 支持：ADR-0007 决策 1（唯一入口 = 唯一信任边界；越权防线收敛一处，避免密钥/策略 N 处漂移）、
    决策 9（`add-resilience-hardening` 已把直连面从「网内拓扑信任」升级为「`/internal/**` 共享密钥」）；
    服务侧复制角色判定会让角色语义两处维护、易于漂移。
  - **本次侦察新增的关键论据**：服务侧若靠**读 `X-Role` 头**判角色，安全增量**恒为零**——
    能直连 8083 的调用方同样能自由伪造 `X-Role: ADMIN`；不加签的 header 对能直连的人等于不存在。
    故方向 A 若要加强，正确做法是**把治理面请求也纳入凭证机制**（网关统一注签，服务侧校验），
    而非让服务侧看角色头。
  - 缺口：ADR-0007 §9 的凭证硬化只覆盖 `/internal/**`，治理面路径在直连面裸奔——
    这是 ADR 自身 hardening 意图的**未覆盖区**，登记为本轮治理面立项的实质缺口。
- **方向 B｜服务侧校验角色（二道防线）**
  - 支持：defense in depth；直连面客观存在（宿主机直跑 0.0.0.0 / 容器网络互访）；
    F20 若把服务容器化，网络内互访面只会更大；一次误配（未来误加 internal 路由、
    或误把 8083 映射出网卡）即无任何二道防线。
  - **前提（硬）**：必须绑定**凭证**而非角色头——最小实现 = 治理面本地路径
    （`/api/appeals/**`、`/rules/**`、`/api/leaderboard/daily`）复用共享密钥机制
    （服务侧要求网关签发的凭证）。**单纯加「读 X-Role 判 ADMIN」是假硬化，不建议**。

**红绿判别式可行性：高**（离线，形态参照 `common/src/test/.../InternalApiAuthFilterTest.java`
`MockHttpServletRequest` + 过滤器直调）：

- 走方向 B：断言「直连 `/rules/versions`（无凭证）→ 403」——改前必红、改后绿。
- 走方向 A（维持边界）：断言「`isInternalPath("/rules") == false`」（现状恒真）+ 网关
  `admin.paths` 覆盖三条治理面路径（配置断言，参照 TASK-125 从 classpath 读真实 yml
  灌过滤器的 `ActuatorWhitelistNarrowTest` 先例）→ 该判别式**只能防漂移，不能证明安全**，
  台账须如实标注，不得写成「安全已证明」。

**是否需 scratch 库或全栈**：判别式不需；**全栈直连实测（网关 + 直连 8083 治理面）
本次不可行**（服务未起、Docker 未运行）→ 未覆盖。

### 5. 跨条目发现：TASK-103 台账虚报（代码零落地）

`work/mailbox/tasks/TASK-103/handoff.md` 声称 F04/F05/F06/F15/F16/F17/F18 七条**全部落地**
并给出逐条文件:行号，与事实不符：

- `git log --all -S` 对 6 个声称标识（`fetchCandidateEdges` / `FRIEND_SCAN_BATCH` /
  `selectCountsByRecord` / `lock:like:count-init` / `idx_status_created` /
  `record.like.flush-batch`）**全部只命中 `6650ae3` 一笔 docs(mailbox) 提交**——
  即 handoff 文本自身，无任何代码提交（含所有远端/本地分支）。
- 当前代码逐条反证：`MapMatchService` 无 `fetchCandidateEdges`（仍逐点 SQL）；
  `LeaderboardService` 无 `FRIEND_SCAN_BATCH`（仍 `0,-1`）；`RecordLikeMapper` 无
  `selectCountsByRecord` 且 `selectDistinctRecordIds` **仍在**（`:68-69`，TASK-103 称已删）；
  `FLUSH_BATCH` 仍硬编码（无 `@Value`）；`readCount` 无 SETNX；`sql/` 无 `idx_status_created`；
  `FriendService.java:151` 仍 `@Transactional`（TASK-103 称改为编程式事务）。
- `PLAN.md` 无 TASK-103 验收记录（grep **0 命中**）→ 从未收口。
  TASK-103 目录仅 `spec.md`(16:05) + `handoff.md`(16:33) 两文件——28 分钟内声称完成
  4 个服务 + sql 的七项改造，且 `git status` 工作树无任何残留。

**结论**：TASK-103 与 TASK-102（TASK-128 已核实的同类）同属
「台账声称完成、代码零落地」。**本任务包的背景判断成立**：各热点方案不能沿用旧台账手腕，
必须重新立项。建议对 TASK-103 handoff 加订正批注（同 TASK-128 对 TASK-102 的处置），
属**需用户拍板**项。

## 附带发现（超范围，仅登记）

1. **F08 残留**：`gateway-service/src/main/resources/application.yml:158`
   `show-details: always` —— TASK-125 spec 目标写「六个服务 `show-details: always` → `never`」，
   但其「只改文件」只列了 5 个后端服务 + 网关的 whitelist 一行；落地提交 `d89fe15` 亦未碰网关
   该行。5 个后端服务现均为 `never`（user `:96` / leaderboard `:118` / mapmatch `:73` /
   verify `:175` / record `application.properties:89`），**仅网关仍为 `always`**；
   而 `/actuator/health` 恰在网关白名单内且免 token → 匿名可读网关 health 组件明细。
   一行可修（P2）。
2. **F18 仍在**：`sql/02-record-db.sql:13-30` `sport_record` 无 `idx_status_created`
   （仅有 `:27 idx_user_time`），`VerifyDegradeService` 的 `WHERE status=? AND created_at<?`
   无复合索引支撑。TASK-103 声称已加，实际未加（P2）。
3. **好友榜静默截断**：见上「2. F06」附带发现。

## 立项建议一览

| # | 条目 | 建议 | 分级 | 一句话理由 |
|---|---|---|---|---|
| 0 | TASK-103 台账虚报订正 + 此后立项不引旧台账 | **需用户拍板** | — | 七条声称改动全仓零落地，已核到 `6650ae3` 仅台账提交；处置口径（是否加订正批注）属流程决策 |
| 1 | F05 mapmatch 批量预筛（S1，可叠 S2 分块） | **建议立项** | P1 | 往返 200→1，语义等价有代码级证明（`:118/:121` 单调截断），离线判别式现成 |
| 2 | F06 好友榜分批取数（S1，含 rank 等价性说明） | **建议立项** | P1 | 无缓存的全量拉，10 万榜即 10 万元组/请求；`LeaderboardServiceTest:254` 桩已就位 |
| 3 | F15 点赞对账聚合（含 F16 批可配 + 队列指标） | **建议立项** | P1 | N+1 往返 + 2N Redis 往返；一条 GROUP BY + 仅重建漂移即解，可作一个变更交付 |
| 4 | F16 单独项 | **建议关闭（并入 #3）** | P2 | 与 F15 同文件同变更，「点赞规模化」一起走红绿更省一轮 |
| 5 | F17 回源防击穿（Caffeine 单飞 vs SETNX） | **需用户拍板** | P1 | 两方案跨实例语义不同，且与 F13 的 Caffeine 多实例既有结论口径需对齐 |
| 6 | 治理面：维持 ADR 边界（把凭证硬化扩到治理面路径）vs 服务侧二道防线 | **需用户拍板** | P1 | 两方向论据齐全；**「服务侧读 X-Role」为零增量假硬化**，无论选哪个方向都应排除该实现 |
| 7 | F08 残留（网关自身 `show-details: always`） | **建议立项** | P2 | 一行 + 补配置断言；TASK-125 目标与落地范围不一致的收尾 |
| 8 | F18 索引缺失（`idx_status_created`） | **建议立项** | P2 | 单条 DDL + 迁移脚本幂等段，随 #3 或单列 |
| 9 | 好友榜 >1000 好友静默截断 | **需用户拍板** | P2 | 是否按产品既定的「演示规模 1000 用户」口径接受（`LeaderboardService.java:76` 注释）需拍板 |

## 落盘说明

1. `findings-summary.md`：F05 / F06 / F15 / F16 / F17 五行加核实批注（含行号订正与精度订正）；
   新增 F23 承接治理面缺口（原清单无对应编号），注明来源 TASK-130。
2. 本 handoff（侦察报告 + 立项建议）+ `PLAN.md` 收口记录。
3. **未改任何代码/配置/sql/规格**；`*/src/**`、pom、yml、properties 全部零改动。

## 验证记录

侦察类任务，无先红/后绿/变异环节（无代码行为改动）。

- offline 全量：`bash scripts/verify/mvn-verify.sh --mode=offline test` → 见「复跑结果」
- 契约：在途 `--baseline=f2b58c3` + 收口后无参数，见「复跑结果」
- 词面自检：CI 同款正则双 locale，见「复跑结果」

## 复跑结果

- **offline 全量**：`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS**，
  模块合计 `20/30/33/80/81/50/6 = 300`，与 TASK-127 起收口锚点逐位一致**零扰动**
  （任务包所写 299 为过期锚点：TASK-127 已把基线抬到 300；本任务只改 `work/` 下 md，数字不动）。
  日志 `.trae/tmp/task130-offline.log`。
- **契约**：在途 `--baseline=f2b58c3` → TASK-130 段 `判据 B 通过`（整体 rc=1 为历史任务交叠噪声，
  详见 PLAN.md 收口记录）；收口提交后无参数 → **rc=0**。
- **词面自检**（CI 同款正则，双 locale）：`LC_ALL=C` **ZERO-HIT**；默认 locale 命中均为
  TASK-118 起登记的本机伪影（`api/.../MapMatchResultDTO.java`，本任务未触碰），按未覆盖计。

## 只改清单

- work/mailbox/findings-summary.md
- work/mailbox/tasks/TASK-130/spec.md
- work/mailbox/tasks/TASK-130/handoff.md
- work/mailbox/PLAN.md
