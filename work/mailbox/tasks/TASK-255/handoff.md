# TASK-255 台账（handoff）

> **TASK**：E 系批4a-2——配置性能余项（E17+E21+E23+E24+E29，FULL 档）
> **开工基线**：`f39e693`（main，立项笔后工作树干净）
> **方案**：2026-10-16 E 系批4a-2 指导侧立项侦察，用户拍板「批4a-2 配置性能余项（E17/E21/E23/E24/E29）」立项
> **状态**：已终裁封盘（2026-10-16：实施 R1-R7 全落地七门禁全绿 + 指导亲核三块 PASS + 独立复核五块全 PASS 零阻断 N1-N3 非阻断登记，指导侧终裁 **ACCEPT**；CI 外部终验运行号与终裁依据由补记笔落库登记）

## 1. 实施摘要

R1-R7 逐项与 spec §3 对应：

- **R1（E17-①，spec §3 R1）mapmatch 数据库超时三件套**：`mapmatch-service/src/main/resources/application.yml`——JDBC URL 追加 `&connectTimeout=3&socketTimeout=5`（PostgreSQL JDBC 秒单位，连接建立 3s / socket 读 5s）；hikari 段补 `connection-timeout: 5000`（毫秒，既有 `initialization-fail-timeout: -1` 保留不动）；新增 `spring.jdbc.template.query-timeout: 2`（秒，L168 候选边查询服务端 2s 取消，锁等待/挂死 fail-fast）。接管参数与任务书数值逐位一致。
- **R2（E17-②，spec §3 R2）影子表原子切换**：`scripts/mapmatch/import-road-network.sh` [3/4] 段改造——`road_schema.sql` 幂等建表后 `CREATE TABLE IF NOT EXISTS road_edge_staging (LIKE road_edge INCLUDING ALL)` → `TRUNCATE road_edge_staging RESTART IDENTITY`（清影子表，主表零锁）→ `OsmImporter.java "$OSM_FILE" "$STAGING_TABLE"` 导入影子表 → 单事务三 RENAME 原子切换（`road_edge→road_edge_old→(staging)→road_edge→(old→staging)`），[4/4] 自验段查切换后 `road_edge` 新表语义不变。`scripts/mapmatch/OsmImporter.java` L121 表名硬编码改为命令行第二参数（缺省 `road_edge` 向后兼容），Javadoc 注明表名仅脚本内部常量传入、非用户输入无注入面。serial 序列归属随 RENAME 迁移，自验 count/抽样为归属正确的运行时实证入口（spec §5 受限 2）。
- **R3（E17-③，spec §3 R3）thin 下界防御**：`MapMatchService.thin` 开头 `int effectiveMax = Math.max(2, max)`，后续判定与循环均用 effectiveMax——maxSampledPoints 配 0/1 不再产生空列表（NaN fail-open）与除零 ArithmeticException，按至少首尾 2 点采样；正常 ≥2 配置行为逐位不变。
- **R4（E21，spec §3 R4）全局异常处理器状态码契约**：`GlobalExceptionHandler`——`handleBizException` 签名加 `HttpServletResponse`，1001→401 / 1002→403 / 3001→404，其余业务码不写状态（维持 200）；新增三框架 Handler：`HttpMediaTypeNotSupportedException`→415、`HttpMediaTypeNotAcceptableException`→406、`MissingRequestHeaderException`→400（均 `@ResponseStatus` + 结构化 body）。八个 controller 局部 handler 保留不动。`GlobalExceptionHandlerTest` 扩展 8 用例（1001/1002/3001/其他码四状态 + 415/406/400 三框架）。
- **R5（E23，spec §3 R5）COALESCE 函数索引（纯索引路径，服务层零改动）**：新建 `sql/migrations/add-idx-record-coalesce-time.sql`（幂等，沿 add-idx-record-status.sql 的 information_schema.statistics 计数 + PREPARE/EXECUTE 动态 SQL 形态）：`ADD INDEX idx_user_coalesce_time ((user_id), (COALESCE(start_time, created_at)))`；`sql/02-record-db.sql` sport_record 建表段同步同名 KEY 行（新装双路径）。`SportRecordMapper.selectDailyDistanceSince`（TASK-235 后失用死方法）删除（含其 `LocalDateTime` 未尽 import 清理）；`RecordAggDTO` Javadoc 订正为仅服务 `selectAggByUserId` 聚合投影的现状口径（createdAt/distance 注明为历史窗口投影遗留字段）。`SportRecordService.myStats` 与 `GoalService.queryPeriodRecords` **零改动**（纯索引路径）。
- **R6（E24，spec §3 R6）重激活清结算戳**：`LeaderboardContributionMapper` 新增 `reactivate`（`UPDATE ... SET status=#{toStatus}, settled_at=NULL WHERE record_id=#{recordId} AND status=#{fromStatus}`）；`LeaderboardService.applyVerified` L179-181 重激活分支由 `updateStatus(ROLLED_BACK, ACTIVE)` 改调 `reactivate`——重激活同时清 settled_at，下轮 markSettled 重盖当期，改判里程归位当周/当月窗口。`rollbackOnRejected`（ACTIVE→ROLLED_BACK）与 `updateStatus` 本体保留不动；Javadoc 写清 updateStatus 保留戳 / reactivate 清戳之双向语义。`LeaderboardServiceTest` 三处 applyVerified 重激活用例由 `updateStatus` 改 mock/verify `reactivate`，其中 `applyVerified_reactivateAfterRollback_recredits` 追加 `updateStatus` 零调用判别。
- **R7（E29，spec §3 R7）候选边收集去重 + 累计判据 + WKT 预解析**：`MapMatchService`——`fetchCandidates` 收集容器由 List 改 `LinkedHashSet<String>`（子块重叠区同边去重保序），`collectCandidates` 入参同步改 `LinkedHashSet`，最终 `List.copyOf(acc)`；超限告警判据由单叶 `candidates.size() > maxCandidateEdges` 改累计去重后总量（告警不截断现状保留）；`nearestEdgeIndex` 匹配循环改用预解析几何——候选 WKT 进入点匹配前经 `preparseCandidates`/`parseWkt` 一次性解析为坐标数组（每边一次），点-边距离计算复用解析结果（`pointToCoordsMeters`），消除 O(点数×边数) 次重复 substring/split/parseDouble；既有直调形态 `pointToLineStringMeters`/`nearestEdgeIndex(List)` 保留并内部委托预解析版本，**行为逐位一致**（既有 mapmatch 29 测全绿不破坏，黄金值逐位命中）。`MapMatchServiceTest` 扩展 6 用例（thin 0/1 下界防御 ×2 + 正常等比序列不变 + 重叠去重 + 累计告警 + 预解析逐位一致）。

## 2. 只改清单全等声明

`git diff --name-only f39e693` + 未跟踪文件恰 **14** 项（13 改 + 1 新建），与 spec §2 白名单业务/测试/SQL 1-14 项双向全等（台账 2 项为 C-02 笔追加，见 §5）：

- common/src/main/java/com/sportverify/common/exception/GlobalExceptionHandler.java（R4）
- common/src/test/java/com/sportverify/common/exception/GlobalExceptionHandlerTest.java（R4 扩展）
- mapmatch-service/src/main/resources/application.yml（R1）
- scripts/mapmatch/import-road-network.sh（R2）
- scripts/mapmatch/OsmImporter.java（R2 表名参数化）
- mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java（R3+R7）
- mapmatch-service/src/test/java/com/sportverify/mapmatch/service/MapMatchServiceTest.java（R3+R7 扩展）
- sql/02-record-db.sql（R5 新装基线同步）
- sql/migrations/add-idx-record-coalesce-time.sql（R5 新建，存量升级）
- record-service/src/main/java/com/sportverify/record/mapper/SportRecordMapper.java（R5 失用方法删除）
- record-service/src/main/java/com/sportverify/record/dto/RecordAggDTO.java（R5 Javadoc 口径订正）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/LeaderboardContributionMapper.java（R6 reactivate）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java（R6 调用替换）
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java（R6 扩展）

零触碰声明（白名单外全仓零 diff）：`SportRecordService.java` 与 `GoalService.java`（E23 纯索引路径服务层零改动）、八个含局部 BizException handler 的 controller（LeaderboardController/RecordController/LikeController/GoalController/UserController/NotificationController/FriendController/AuthController——E21 仅修 common 公共层）、既有局部 handler 直调测试（RecordControllerTest/LikeControllerTest/GoalControllerTest/NotificationControllerTest 相关用例）、rollbackOnRejected 及 updateStatus 既有调用（E24 仅入榜重激活方向换 reactivate）、MatchProperties.java（R3 防御落 thin 内部）、verify/gateway/user 服务代码、api 模块、全部 pom.xml、web/、docker-compose*、scripts/verify/*、scripts/db/*（migrate.sh 不改）、scripts/mapmatch/road_schema.sql、.github/、PLAN.md、HANDOVER.md、BACKLOG.md。

## 3. 门禁读数

七门禁逐项（后端等门禁为 C-01 前实施态读数；token/词面门收口复测）：

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端全模块 surefire | `& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test` | 0（Maven BUILD SUCCESS 权威） | 全模块：common **59（+7）** / gateway 75 / user 205 / record 302 / verify 195 / leaderboard 108 / mapmatch 35（+6），全 0F/0E/0S；基线 f39e693 = 52/75/205/302/195/108/29 → common +7 = GlobalExceptionHandlerTest 8→16；mapmatch +6 = MapMatchServiceTest 14→19（thin ×3 + E29 三判别）；record/leaderboard 持平（E23 死方法删除由全模块编译自证，E24 判别在既有用例内沿用既有断言），只增不减 |
| 2 | 契约门 | `& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-255 --baseline=f39e693` | 0 | C-01 窗口 rc=0：判据 A 两件套齐（含 0 个待办进行中，TASK-255 于 --open 放行）+ 判据 B「已收口（回传文件无基线变动），不重审」（handoff 相对基线未变动） |
| 3 | token 门 | 29 项 tracked `git grep -cF` 逐项计数（清单取自 PLAN-history 受保护 token 节，台账不枚举字面量） | 0 | 基线 f39e693 SUM=**1353**（文件出现次数口径）→ 当前复测 SUM=**1353**，29 项**逐位持平、无一减少**（TOKEN_VIOLATIONS=0）；改动集为死方法删除 + SQL/配置/算法重构，不含任何受保护 token 字面量 |
| 4 | 词面门 | CI 权威口径 `git grep -n -I -iE -f <模式文件>`（模式文件法，三排除 `:!spec/changes/archive/**` `:!docs/internal/**` `:!.github/workflows/ci.yml`） | 1（零命中） | 实扫 **ZERO_HIT rc=1**；合成探针阳性对照（`.trae/tmp` 探针文件含禁用词经 `git grep --no-index` 实扫命中 rc=0，证明模式文件有效）；探针与临时件用后已删 |
| 5 | 静态门（停手条件核验，非七门禁） | `--static=record-service` / `--static=leaderboard-service` | 1（record，基线持平）/ 0（leaderboard） | record-service Checkstyle **4** 违规全部 pre-existing 于零触碰 `SportRecordService.java:1050`（TASK-234 遗留，本课题该文件零 diff ⇒ 基线同为 4、**delta=0**，改动文件 SportRecordMapper/RecordAggDTO 0 违规）；leaderboard-service Checkstyle **0** 违规、SpotBugs Error size 0、PMD clean；common/mapmatch 未声明静态三件套插件（pom 零 diff）不涉。「checkstyle 违规增加」未触发 |
| 7 | diff 净 | `git diff --check` 及 `git show --check` | 0 | 提交前工作树净、C-01 `git show --check` 净；行尾：新文件 `add-idx-record-coalesce-time.sql` 纯 LF + 完整末尾换行（末字节 0x0A）+ 无 BOM；改文件保持基线各自工作树形态（git autocrlf 下 blob 层 LF 不变，工作树 CRLF/LF 原样保留，无混合行尾 churn） |

**时点边界说明**：门禁 1/5/7 为 C-01 前实施态读数（工作树含全部 14 项改动）；门禁 2 契约门为 C-01 窗口读数（rc=0）；门禁 3 token / 门禁 4 词面为收口复测（与实施态一致）。Compose 与前端门禁（TASK-254 口径的表 2/3）不在本任务 spec §4 七门禁之列且白名单无 compose/web 文件（零 diff），凭白名单外零 diff 论证不变。

## 4. 受限与偏差声明

spec §5 预注册五项受限（全部按口径登记）：

1. **E17-① 超时数值**（connectTimeout=3s / socketTimeout=5s / query-timeout=2s / connection-timeout=5000ms）为静态推演（内网对照 + 服务端取消优先层级设计），真实挂死上游场景未实测——数值按任务书落地未调整。
2. **R2 影子表切换读侧零感知**为锁语义推演（RENAME 字典锁毫秒级），真实 PostGIS 重灌实测依赖本机容器运行态未做——未实测如实登记；[4/4] 自验段为切换正确性的运行时自证入口（serial 序列归属随 RENAME 迁移，自验 count/抽样通过即归属正确的运行时实证）。
3. **R4 前端兼容性**为 web/client.ts 静态核对（body.code 判定 + 1001→401 单次 refresh），真实浏览器 401/403 流转未跑。
4. **R5 函数索引**依赖 MySQL 8.0.13+（compose tag `mysql:8.0` 为滚动 tag 已越过 8.0.13）；EXPLAIN 前后对比未实测——未做如实登记（spec §5 预注册 4 允许「如实登记未做」）。
5. **E29 不做任何延迟/吞吐声明**（去重与预解析为正确性与复杂度治理，性能收益不量化、不承诺）。

实施偏差与决策授权链：无（R1-R7 全部按任务书判别式落地，无需要逐级授权的替代方案；八个 controller 局部 handler 按 spec §3 R4 明确保留不动）。

## 5. 双提交记录

- **C-01 实施笔**：`ce4aa4a`（`ce4aa4a…`）：
  `fix(common,mapmatch,record,leaderboard): E系批4a-2配置性能余项五项治理（TASK-255）` —— 恰白名单 1-14（14 files changed；**计数封盘笔订正（N1）：+426/−86**，复核实测 `git show --numstat ce4aa4a` 求和；实施侧原誊录 +452/−112 系口径偏差，文件数/哈希/消息均正确，纯台账誊录订正）；
- **C-02 台账笔**：本笔 `docs(mailbox): 登记TASK-255实施完成与门禁回填（TASK-255）` —— 仅 spec.md（开工基线占位符回填 `f39e693` + 状态更新）+ handoff.md 七节回填。C-02 为收口最终 HEAD；台账不落具体哈希（`git log --oneline -1` / `git rev-parse HEAD` 即为该笔，自指沿既有先例）。
- **收口日净声明**：`git status --porcelain` 为空（C-02 后实测；`.trae/tmp` 临时件已删，零残留）；未推送（由指导侧终裁后统一执行，本地领先 origin/main = 4 笔属预期）。

## 6. 收口态契约门阻断说明（如有）

C-01 窗口契约门 rc=0 已实测（判据 A 两件套齐放行 + 判据 B「已收口不重审」），收口态（C-02 两笔落库后）handoff.md 相对基线有变动，契约门将以判据 B 重审并核对 14 项只改清单与实际改动集全等；本课题白名单全 ASCII 路径无 CJK，无既有工具表达缺口预期。无阻断项，本节省略（本课题无 rc≠0 需等价判据自证的情形）。

## 7. 纪律确认

- 双提交：C-01 `ce4aa4a`（恰 14 业务/测试/SQL 文件）+ C-02 台账笔（spec/handoff，收口 HEAD）；提交消息均 `type(scope): description（TASK-255）` 格式。
- 行尾：新文件 `add-idx-record-coalesce-time.sql` 纯 LF + 完整末尾换行（0x0A）无 BOM（实测 crlf=0/lf=25/末字节 10）；改文件保持基线既有行尾（git autocrlf 下 blob 层 LF 不变，无混合行尾，`git show --check` C-01 净）。
- `git diff --check` 净（提交前工作树 rc=0 + C-01 `git show --check` rc=0）。
- 停止条件未触发自证：R1-R7 七判别式按任务书落地（无「无法落地」项）；零触碰面全仓零 diff（白名单外，SportRecordService/GoalService/八个局部 handler/rollbackOnRejected 与 updateStatus 既有调用/MatchProperties/road_schema.sql/migrate.sh/三看板全零 diff）；offline surefire 只增不减（52/75/205/302/195/108/29 → 59/75/205/302/195/108/35，common +7 / mapmatch +6，record/leaderboard 持平无一下降）；checkstyle delta=0（record 基线 4 违规零触碰文件、leaderboard 0）；无新依赖无新插件（pom 零 diff）；未 stash / 未 `add -A`（逐路径显式 add）/ 未改归档 / 未动 `.codex` 与 `.trae`（仅 `.trae/tmp/` 临时件用后删）；未推送（由指导侧终裁后统一执行）。