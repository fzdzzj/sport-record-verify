# TASK-255 任务书（spec）

> **TASK**：E 系批4a-2——配置性能余项（E17 mapmatch 数据库超时与重灌误拒窗口 + E21 全局异常处理器状态码契约 + E23 COALESCE 窗口查询函数索引 + E24 改判重激活结算周期归位 + E29 候选边收集去重与 WKT 预解析，FULL 档）
> **开工基线**：`f39e693`（main，立项笔后工作树干净）
> **方案**：2026-10-16 E 系批4a-2 指导侧立项侦察，用户拍板「批4a-2 配置性能余项（E17/E21/E23/E24/E29）」立项
> **档位**：FULL（E21 触碰 common 全局异常契约面——跨服务 HTTP 状态码语义、E24 触碰榜单结算归窗正确性、E17/E29 触碰 mapmatch 判定链路与数据源重灌路径、多模块 common/mapmatch/record/leaderboard + scripts/sql——禁降级；离线全量逐位门禁 + 独立复核五块）
> **状态**：已实施并门禁全绿（2026-10-16 实施子 agent 按 R1-R7 全部落地，七门禁全绿，C-01 实施 `ce4aa4a` + C-02 台账 `1ac8c60`；指导亲核三块 PASS + 独立复核五块全 PASS 零阻断 N1-N3 非阻断登记，指导侧终裁 **ACCEPT 予以封盘**；CI 外部终验读数由补记笔落库）

## 1. 背景与侦察结论（2026-10-16 指导侧亲验，五项坐实，含一项前提订正）

- **E17 坐实（三半）**：
  - ① mapmatch `application.yml` L34 JDBC URL 仅 `stringtype=unspecified`，无 connectTimeout/socketTimeout；hikari 段仅 `initialization-fail-timeout: -1`；`MapMatchService` L168 `jdbcTemplate.queryForList(CANDIDATE_SQL, ...)` 无查询超时——PostGIS 挂死/锁阻塞时候选边查询无限等待，R5 判定线程被拖死（对照：verify-service Feign 显式 connect 1000 / read 3000）。
  - ② `scripts/mapmatch/import-road-network.sh` L63 `TRUNCATE road_edge RESTART IDENTITY` 取 ACCESS EXCLUSIVE 锁后经 L65 OsmImporter 管道重灌（分钟级窗口）——期间读侧全部阻塞，mapmatch 存活但候选边恒空 → R5 全量「误拒」（非降级，是停摆窗口）。
  - ③ `MapMatchService.thin`（L120-129）对 `maxSampledPoints` 零下界防御：Nacos 配 0 → 循环不执行返回空列表 → L102 `n=0` → L104 `matched/n = NaN` → NaN 比较为 false → fail-open；配 1 → `(max-1)=0` 除零 ArithmeticException。同文件 L88 `chunkSize` 有 `Math.max(1, ...)` 保护——防御不对称。
- **E21 坐实（三半）**：
  - ① common `GlobalExceptionHandler.handleBizException`（L39-43）无 @ResponseStatus 也无 HttpServletResponse 写状态 → BizException 恒 HTTP 200。影响无局部 handler 的服务（verify/mapmatch）：越权 1002、未授权 1001 均以 200 承载。**现状补充**：record/user/leaderboard 八个 controller 各有局部 `@ExceptionHandler(BizException)` 已正确映射 1001→401/1002→403（LeaderboardController L124-133 等标准款；GoalController 另多 3001→404；AuthController/UserController 仅 1001）——局部先例在，公共层缺位。
  - ② 三类框架异常落 Exception 兜底 500：HttpMediaTypeNotSupportedException（应 415）、HttpMediaTypeNotAcceptableException（应 406）、MissingRequestHeaderException（应 400）——调用方问题被升格为服务端 500。
  - ③ 同码两态：网关 AuthGlobalFilter 1002→403 / 1001→401 vs 服务侧（无局部的）1002→200——前端 web/client.ts 响应拦截器按 body.code 判定（L102-113），仅 `status===401` 触发单次 refresh（L121-124）：1001→401 将激活期望的 refresh 流程，1002→403 前端兼容（静态核对成立）。
- **E23 坐实（含前提订正）**：`SportRecordService.myStats`（L932-938）与 `GoalService.queryPeriodRecords`（L198-199）窗口过滤均为 `.apply("COALESCE(start_time, created_at) >= {0}", ...)`——列被函数包裹不可 sargable，`idx_user_time(user_id, created_at)` 只用 user_id 前缀，按用户全量回表。**前提订正**：BACKLOG 原文「Mapper 已备 selectDailyDistanceSince 全仓无调用」不准确——该方法 TASK-232 引入并在用，TASK-235 时间口径改写 COALESCE 后失用；现况 = main 零调用（grep 实证仅 Mapper 定义 + RecordAggDTO Javadoc 引用）。修法采用**纯索引路径**：MySQL 函数索引 `(user_id, (COALESCE(start_time, created_at)))`（8.0.13+，compose `mysql:8.0` 实际版本满足）使既有 COALESCE 表达式直接 sargable——服务层与查询 SQL 零改动，时间口径语义零漂移；失用方法 selectDailyDistanceSince 删除（死代码清理）。
- **E24 坐实（缺陷链完整）**：`LeaderboardContributionMapper.updateStatus`（L37-41）只 `SET status` 不动 `settled_at`；`markSettled`（L52-55）仅盖 `settled_at IS NULL` 行。链路推演：PASSED 入榜 ACTIVE → markSettled 盖 T1 → 驳回 ROLLED_BACK（T1 保留）→ 申诉改判重激活 ACTIVE（updateStatus 不清 T1）→ markSettled 不再盖（非 NULL）→ **改判里程永久归 T1 旧周期，当周/当月榜漏计**。`selectRangeSummaries`（L95）按 settled_at 归窗，修复需重激活时清 settled_at=NULL 让结算重盖当期。
- **E29 坐实（三点）**：`MapMatchService` L176 `acc.addAll(candidates)` 分块递归收集无去重——子块空间重叠区同边重复入候选集；L170-174 超限告警判据只看单叶 `candidates.size()` 而非累计集；L208-218 `nearestEdgeIndex` 每点每边调 `pointToLineStringMeters`（L222-240 每次重复 `wkt.substring/split/parseDouble` 解析）——同一 WKT 被重复解析 O(点数×边数) 次。

## 2. 改动文件白名单（16 项，双向全等硬约束）

业务/测试/SQL 14 项：

1. common/src/main/java/com/sportverify/common/exception/GlobalExceptionHandler.java（E21 R4）
2. common/src/test/java/com/sportverify/common/exception/GlobalExceptionHandlerTest.java（E21 R4 扩展）
3. mapmatch-service/src/main/resources/application.yml（E17 R1）
4. scripts/mapmatch/import-road-network.sh（E17 R2）
5. scripts/mapmatch/OsmImporter.java（E17 R2 表名参数化）
6. mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java（E17 R3 + E29 R7）
7. mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchServiceTest.java（E17 R3 + E29 R7 扩展）
8. sql/02-record-db.sql（E23 R5 新装基线同步）
9. sql/migrations/add-idx-record-coalesce-time.sql（E23 R5 新建，存量升级）
10. record-service/src/main/java/com/sportverify/record/mapper/SportRecordMapper.java（E23 失用方法删除）
11. record-service/src/main/java/com/sportverify/record/dto/RecordAggDTO.java（E23 Javadoc 口径订正）
12. leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java（E24 R6）
13. leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java（E24 R6 调用替换）
14. leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java（E24 R6 扩展）

台账 2 项（C-02 阶段）：work/mailbox/tasks/TASK-255/spec.md、work/mailbox/tasks/TASK-255/handoff.md。

零触碰声明（白名单外全仓零 diff）：**SportRecordService.java 与 GoalService.java（E23 纯索引路径，服务层零改动）**、八个含局部 BizException handler 的 controller（LeaderboardController/RecordController/LikeController/GoalController/UserController/NotificationController/FriendController/AuthController——E21 修复仅 common 公共层，局部 handler 保留为既有先例，机制收拢另立候选不在本课题）、既有局部 handler 直调测试（RecordControllerTest/LikeControllerTest/GoalControllerTest/NotificationControllerTest 相关用例）、rollbackOnRejected 及 updateStatus 既有调用（E24 仅入榜重激活方向换 reactivate）、MatchProperties.java（R3 防御落 thin 内部，不引 @Validated 不动配置类）、verify/gateway/user 服务代码、api 模块、全部 pom.xml、web/、docker-compose*、scripts/verify/*、scripts/db/*（migrate.sh 不改，新脚本由其按文件名顺序自动拾取）、scripts/mapmatch/road_schema.sql、.github/、PLAN.md、HANDOVER.md、BACKLOG.md（子 agent 禁改三看板）。

## 3. 实现要求（判别式）

### R1（E17-①）mapmatch 数据库超时三件套

`application.yml`：

- JDBC URL 追加 `&connectTimeout=3&socketTimeout=5`（PostgreSQL JDBC 参数，**单位秒**——连接建立上限 3s；socket 读上限 5s，须大于 query-timeout 使服务端取消优先生效）；
- hikari 段补 `connection-timeout: 5000`（毫秒，池连接获取上限；既有 `initialization-fail-timeout: -1` 保留不动）；
- 新增 `spring.jdbc.template.query-timeout: 2`（秒，JdbcTemplate 声明式查询超时——L168 候选边查询服务端 2s 取消，锁等待场景 fail-fast）。

### R2（E17-②）影子表原子切换替代 TRUNCATE 重灌窗口

`import-road-network.sh` [3/4] 段改造为影子表模式：

1. road_schema.sql 幂等建表后，`CREATE TABLE IF NOT EXISTS road_edge_staging (LIKE road_edge INCLUDING ALL)`（结构含 GIST 全量克隆）；
2. `TRUNCATE road_edge_staging RESTART IDENTITY`（清**影子表**，主表 road_edge 零锁）；
3. OsmImporter 管道导入目标表参数化为 `road_edge_staging`（OsmImporter.java L121 表名硬编码改为命令行第二参数，缺省 road_edge 保持向后兼容；表名仅脚本内部常量传入，Javadoc 注明非用户输入无注入面）；
4. 单事务原子切换：`BEGIN; ALTER TABLE road_edge RENAME TO road_edge_old; ALTER TABLE road_edge_staging RENAME TO road_edge; ALTER TABLE road_edge_old RENAME TO road_edge_staging; COMMIT;`——RENAME 持字典锁毫秒级，替代原 TRUNCATE+重灌分钟级 ACCESS EXCLUSIVE 窗口；[4/4] 自验段查 road_edge（切换后新表）语义不变。

注意点（实施侧自验）：serial 序列随 RENAME 归属迁移（owned sequence 自动改名并更新默认值引用），自验 count/抽样通过即为归属正确的运行时实证。

### R3（E17-③）thin 下界防御

`MapMatchService.thin` 开头 `int effectiveMax = Math.max(2, max);`，后续判定与循环均用 effectiveMax：maxSampledPoints 配 0/1 时不再产生空列表（NaN fail-open）与除零（ArithmeticException），按至少首尾 2 点采样；正常 ≥2 配置行为逐位不变（既有采样序列不变）。

### R4（E21）全局异常处理器状态码契约

`GlobalExceptionHandler`：

- `handleBizException` 签名加 `HttpServletResponse response`，按 code 映射：1001→`response.setStatus(401)`、1002→403、3001（RECORD_NOT_FOUND）→404，**其余业务码不写状态（维持 200）**——与局部 handler 先例（LeaderboardController L124-133 注释口径）及网关 AuthGlobalFilter 口径对齐；
- 新增三个框架异常 Handler：`HttpMediaTypeNotSupportedException`→415（@ResponseStatus(UNSUPPORTED_MEDIA_TYPE)，body `Result.failure(415, "请求 Content-Type 不支持")`）、`HttpMediaTypeNotAcceptableException`→406（NOT_ACCEPTABLE，`Result.failure(406, "请求 Accept 头无法满足")`）、`MissingRequestHeaderException`→400（BAD_REQUEST，`Result.failure(400, "缺少必需请求头：" + e.getHeaderName())`）；
- 八个 controller 局部 handler **保留不动**（已正确映射，属既有先例；机制收拢为独立候选登记 BACKLOG，不并入本课题）。

### R5（E23）COALESCE 函数索引（纯索引路径，服务层零改动）

- `sql/migrations/add-idx-record-coalesce-time.sql` 新建（沿 add-idx-record-status.sql 幂等形态：information_schema.statistics 计数 + PREPARE/EXECUTE 动态 SQL）：`ALTER TABLE `sport_record` ADD INDEX `idx_user_coalesce_time` ((user_id), (COALESCE(start_time, created_at)))`——函数索引，MySQL 8.0.13+（版本依赖登记 §5）；
- `sql/02-record-db.sql` sport_record 建表段同步同名 KEY 行（新装库直含，沿 idx_status_created 双路径先例）；
- myStats 与 queryPeriodRecords 的 `.apply("COALESCE(start_time, created_at) >= {0}")` **零改动**——查询表达式与索引函数部分精确匹配即 sargable（EXPLAIN 验证列 §5 预注册）；
- `SportRecordMapper.selectDailyDistanceSince`（TASK-235 后失用死方法）删除；`RecordAggDTO` Javadoc 对应条目订正为仅服务 `selectAggByUserId` 聚合投影的现状口径。

### R6（E24）重激活清结算戳

`LeaderboardContributionMapper` 新增：

```java
@Update("UPDATE leaderboard_contribution SET status = #{toStatus}, settled_at = NULL " +
        "WHERE record_id = #{recordId} AND status = #{fromStatus}")
int reactivate(@Param("recordId") Long recordId,
               @Param("fromStatus") Integer fromStatus,
               @Param("toStatus") Integer toStatus);
```

`LeaderboardService.applyVerified` L179-181 重激活分支由 `updateStatus(recordId, ROLLED_BACK, ACTIVE)` 改调 `reactivate(recordId, ROLLED_BACK, ACTIVE)`——重激活同时清 settled_at，下轮 markSettled 重盖当期，改判里程归位当周/当月窗口。`rollbackOnRejected` 方向（ACTIVE→ROLLED_BACK）与 `updateStatus` 本体保留不动（回滚行不入窗，旧戳保留无害）。Javadoc 写清双向语义差异（updateStatus 保留戳 / reactivate 清戳）。

### R7（E29）候选边收集去重 + 累计判据 + WKT 预解析

`MapMatchService`：

- 分块递归收集容器由 List 改 `LinkedHashSet<String>`（子块重叠区同边 WKT 去重，保序），最终返回 `List.copyOf(acc)`；
- 超限告警判据由单叶 `candidates.size() > maxCandidateEdges` 改为累计去重后总量（告警不截断现状保留，不引入行为变更）；
- `nearestEdgeIndex` 匹配循环改用预解析几何：候选 WKT 在进入点匹配前一次性解析为坐标数组（每边一次），点-边距离计算复用解析结果，消除 O(点数×边数) 次重复 substring/split/parseDouble；
- **行为等价硬约束**：同一输入的最近边索引与距离判定与既有实现逐位一致（既有 mapmatch 29 测全绿不破坏；若既有直调私有方法的用例需适配，适配后断言语义不得弱化）。

## 4. 测试与门禁要求

新增/扩展单测判别式（全部纯 JVM，零 Testcontainers/零新依赖）：

- E21（GlobalExceptionHandlerTest 扩展，MockHttpServletResponse 直调形态沿既有 L131 先例适配签名）：1001→断言 status 401 + body code、1002→403、3001→404、其他业务码（SYSTEM_ERROR）→status 不动（200）+ body code、415/406/400 三类框架异常各一用例；
- E17-③（MapMatchServiceTest 扩展）：thin(≥3 点, 0) 返回首尾 2 点不抛、thin(≥3 点, 1) 同、thin(N, k≥2) 既有等比采样序列不变；
- E29（MapMatchServiceTest 扩展）：构造含重复 WKT 的分块结果断言去重后单次、累计判据告警触发路径、预解析路径最近边索引与既有逐位一致（同输入对照断言）；
- E24（LeaderboardServiceTest 扩展）：applyVerified 对 ROLLED_BACK 锚点（insertIgnore=0 mock）verify `reactivate(recordId, ROLLED_BACK, ACTIVE)` 被调且 `updateStatus` 零调用、credit=true 走 ZINCRBY 既有断言复用；
- E23：无新增单测（纯索引 + 死方法删除）——无引用由全模块编译自证，幂等形态由脚本与 add-idx-record-status.sql 同构静态审查承载。

七门禁全绿（唯一入口 scripts/verify/mvn-verify.sh；契约门 --open=TASK-255 --baseline=&lt;开工基线&gt;；token 门 29 项只增不减；词面门模式文件法 + 探针阳性对照）；surefire 只增不减（开工基线 52/75/205/302/195/108/29——本课题增量预期：common +5~8、mapmatch +3~5、leaderboard +1~2，record/verify/gateway/user 持平，白名单外零 diff 论证不变）。

## 5. 预注册受限与授权

1. E17-① 超时数值（connectTimeout=3s / socketTimeout=5s / query-timeout=2s / connection-timeout=5000ms）为静态推演（内网对照 + 服务端取消优先层级设计），真实挂死上游场景未实测。
2. R2 影子表切换读侧零感知为锁语义推演（RENAME 字典锁毫秒级），真实 PostGIS 重灌实测依赖本机容器运行态——未实测则如实登记（脚本自验段为切换正确性的运行时自证入口）。
3. R4 前端兼容性为 web/client.ts 静态核对（body.code 判定 + 401 单次 refresh），真实浏览器 401/403 流转未跑（TASK-227 E2E 无 401/403 断言用例，无既有覆盖可回归）。
4. R5 函数索引依赖 MySQL 8.0.13+（compose tag `mysql:8.0` 为滚动 tag，8.0 线早已越过 8.0.13）；EXPLAIN 前后对比（type/ref/rows 读数）未实测——可选 scratch 库验证，读数随 handoff 登记或如实登记未做。
5. E29 不做任何延迟/吞吐声明（去重与预解析为正确性与复杂度治理，性能收益不量化、不承诺）。

## 6. 纪律

- 双提交：C-01 实施（恰上述 14 项业务/测试/SQL 文件）+ C-02 台账（spec/handoff 纯追加回填）；提交消息 type(scope): description（TASK-255）。
- 新文件纯 LF + 完整末尾换行；改文件保持既有行尾；git diff --check 净。
- 禁 stash / add -A / 改归档 / 动 .codex 与 .trae；临时件放 .trae/tmp/ 用后删。
- Bash 脚本仅经 D:\git\Git\bin\bash.exe 执行；数据库验证（如做 EXPLAIN）仅用 scratch 库。
- 停止条件：任一判别式无法按本任务书落地、需触碰零触碰面（尤其 SportRecordService/GoalService/八个局部 handler）、offline 基线回退、checkstyle 违规增加——立即停止修改并汇报，不得自行变通。
