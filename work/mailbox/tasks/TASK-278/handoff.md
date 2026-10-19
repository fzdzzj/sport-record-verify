# TASK-278 实施交接台账（handoff）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-19 指导侧终裁；开工基线 main @ `1e4462b`；C-01 `a28d18d` / C-02 `c33150c`；FULL 档独立复核五块全 PASS 零阻断、总结论 ACCEPT；非阻断 9 项 N1–N9 登记见 §5 末节，其中 N1/N2/N3/N9 读数订正已随封盘笔回填；封盘批次统一推送 origin/main，CI 外部终验随后登记）

## 0. 阅读顺序与申报义务

- 先读 `work/mailbox/tasks/TASK-278/spec.md` 全文（含 §五预注册裁决与停止条件——裁决已定案无需申报，冲突即停）；
- 实施中任何与 spec 预期不符的实测（行号漂移、范式冲突、白名单需增删），先登记后处置，禁止静默适配；
- 关键决策需在 §5 逐项留痕（背景 / 选项 / 采纳理由 / 证据）。

## 1. 基线与提交

- 开工基线：main @ `1e4462b`（TASK-278 立项笔）；开工实测 `git status --porcelain` 为空（R0 成立，无跨任务窗口效应）；
- 任务书：`work/mailbox/tasks/TASK-278/spec.md`（开工态行号级证据 §一 五处病灶逐条核对一致，无漂移需登记）；
- C-01：`a28d18d` `feat(record): 跑团里程锚点持久化与对账任务（TASK-278）`——恰 **16 文件，+1049/−159**（复核 N9 订正：初记 −158 系合计笔误，逐文件 numstat 无误；`git show --numstat` 实测逐项见 §2）；落库后工作树随即纯净；
- C-02：`c33150c` `docs(mailbox): TASK-278 实施 handoff 回填（TASK-278）`——本笔仅本台账 1 文件（+95/−22）；曾为 `4f377db`，因提交链读数笔误（0 2→实测 0 3）以 amend 订正为纯台账笔、零代码改动（复核 R1 已核实订正范围 = §1/§7 两读数行 +2/−2）；哈希由指导侧封盘时回填（2026-10-19 已回填）；
- 提交链核验：`git log --oneline 1e4462b..HEAD` 恰 2 笔线性；`git rev-list --left-right --count origin/main...HEAD` = `0 3`（`origin/main` = `c9fac33`，其中 1 笔为基线立项笔 `1e4462b` 本身亦未推送，**本课题自身恰 2 笔**），严格零推送（推送由指导侧统一执行）；
- 白名单核对：改动集 17 路径 ⊆ spec §三 13 项上限（第 11 项＝新增独立对账测试类，第 12 项＝既有测试适配，覆盖 5 个 leaderboard 测试文件；第 12 项的 record 部分未触发——record 侧既有测试适配全部落在白名单第 6 项的重写范围内），双向全等见 §3 门禁 4。

## 2. 改动清单

- 改动清单（逐行路径 token 供判据 B 机器比对；行数为 `git show --numstat a28d18d` 实测，台账行数为 C-02 本笔实测）：
  - sql/02-record-db.sql（+17/−0，末尾追加锚点表 DDL，既有段落零改动）
  - sql/migrations/add-club-distance-contribution.sql（+32/−0，新增，首行 `USE record_db;`）
  - record-service/src/main/java/com/sportverify/record/entity/ClubDistanceContribution.java（+61/−0，新增）
  - record-service/src/main/java/com/sportverify/record/mapper/ClubDistanceContributionMapper.java（+70/−0，新增）
  - record-service/src/main/java/com/sportverify/record/service/ClubDistanceSettleService.java（+129/−89，锚点化重构）
  - record-service/src/test/java/com/sportverify/record/service/ClubDistanceSettleServiceTest.java（+171/−59，重写为锚点口径，14 例→19 例；复核 N3 订正：初记「15 例」系笔误）
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/entity/ClubDistanceContribution.java（+63/−0，新增，只读映射）
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/ClubDistanceContributionMapper.java（+67/−0，新增，只读零写方法）
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java（+164/−5，跑团三组键对账 + R4 措辞精确化）
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardClubReconcileTest.java（+257/−0，新增，6 例）
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java（+4/−1，构造器增参 + 新 Mapper 打桩，断言零改动）
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardClubTest.java（+3/−1，构造器增参，断言零改动）
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardSegmentTest.java（+3/−1，构造器增参，断言零改动）
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTraceIdTest.java（+3/−1，构造器增参，断言零改动）
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardCacheInvocationTest.java（+3/−1，构造器增参，断言零改动）
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardWritePathStaysUnproxiedTest.java（+2/−1，构造器增参，断言零改动）
  - work/mailbox/tasks/TASK-278/handoff.md（本台账，C-02 本笔）
- 未进入改动集的声明：白名单 13 项全部被使用或由对应项覆盖，无「已授权未落地」项。record 侧既有测试仅 `ClubDistanceSettleServiceTest` 一族受锚点化直接波及（白名单第 6 项内重写）；`SportRecordServiceTest` 等对入账服务只做 Mockito 打桩，零适配需求（实测未改动、单测全绿）。
- 零触碰自证（实测 `git diff --name-only 1e4462b HEAD` 全量 17 路径可证，禁触面零命中）：api 模块、`SportRecordService.statusCallback` 挂点与状态机参数、MQ/批量/索引参数、gateway/user/verify/mapmatch 的 src 与 test、根 pom 与各模块 pom、package.json、web、docker-compose、.github、既有 V 序列迁移脚本（只新增不修改）均不在改动集。

## 3. 七门禁实测读数

| # | 门禁 | 命令 | rc | 关键读数（实测时点） |
| :- | :--- | :--- | :-: | :--- |
| 1 | offline 全量单测 | `scripts/verify/mvn-verify.sh --mode=offline test` | 0 | BUILD SUCCESS；全仓 surefire **1208** = 63/79/250/**411**/203/**145**/57，0 败 0 错 0 跳（基线 1197 = 63/79/250/406/203/139/57 → record +5、leaderboard +6，其余五模块逐位不变） |
| 2 | 静态门 record | `scripts/verify/mvn-verify.sh --static=record-service` | 0 | Checkstyle **0 违规**；SpotBugs 实测 26 bugs 全 Medium（High 级 0 条，failThreshold=High 通过）；PMD BUILD SUCCESS（15 条 warning 均 priority>3，`target/pmd.xml` 内 priority≤3 为 0） |
| 2b | 静态门 leaderboard | `scripts/verify/mvn-verify.sh --static=leaderboard-service` | 0 | Checkstyle **0 违规**；SpotBugs 实测 15 bugs 全 Medium（High 0，与 TASK-277 同期口径一致）；PMD BUILD SUCCESS（1 条 UnnecessaryFullyQualifiedName priority 4，位于 `resolveNicknames` 既有行；复核 N2 归因订正：C-01 新增 `import java.util.HashMap` 才使该既有行变为「多余」，属本任务触发、priority 4 不收门禁） |
| 3 | 前端三件套（零前端改动） | `pnpm run type-check` / `pnpm test` / `pnpm run e2e` | 0/0/0 | vue-tsc 0 错；vitest 6 文件 **90** 测全绿；E2E **14/14** 全绿（route-mock 模式，webServer=vite preview，零全栈依赖）；TMP/TEMP 重定向至 `.trae/tmp/build-tmp`，脚本与配置零改动 |
| 4 | 契约门 | `scripts/verify/mailbox-contract.sh --open=TASK-278 --baseline=1e4462b` | 0 | 判据 A：TASK-278 两件套齐；判据 B：在途态 **17 路径双向全等**（清单 17 = 实际改动 17，无清单多报、无未声明改动）；C-02 落库后终态复跑同判据 0 待办 + 清单一致 |
| 5 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树全仓 tracked 累加 | 0 | 基线 `1e4462b` SUM = **2568**；C-01 树 `a28d18d` SUM = **2568**（29/29 逐项零增量）；C-02 树 SUM = **2597** = 2568 + 29（§6 逐项行自枚举恰各 +1） |
| 6 | 词面门 | 正则自 `.github/workflows/ci.yml` L115 现场提取（文件 59 字节含换行，字面量不入任何入库文件），四 locale `git grep -n -I -iE -f` + 三排除 | 1 | default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8` 在 C-01 树与 C-02 树**全 ZERO_HIT**（rc=1，无 TOOL_ERROR）；阳性对照 rc=0（模式自匹配探针命中即删，工作树复核干净） |
| 7 | diff 净与行尾 | `git diff 1e4462b HEAD --check` / 入库 blob CR 与 BOM 扫描 | 0 | `--check` 输出空；17 个改动文件 blob CR 合计 **0**（纯 LF）、首字节均非 BOM；新文件纯 LF + 末尾换行 |
| 8 | DDL 幂等与迁移契约 | scratch 库（复用本机 `task131-scratch-mysql`）双跑 + `scripts/db/migrate.sh` 双跑 + `docker compose config -q` | 0 | 迁移脚本单独双跑 rc=0/0（二跑 `CREATE TABLE IF NOT EXISTS` 全跳过）；新装路径 `sql/02-record-db.sql` + 迁移双跑 rc=0/0/0，`SHOW CREATE TABLE` 为 **8 列 + 2 二级索引**（复核 N1 确证订正：初记「11 列」系「列定义 8 行 + 键 3 行」的输出行口径，`information_schema.columns` = 8）、`information_schema` 计数 1；`migrate.sh` 两轮均 **16/16 成功** rc=0；`docker compose config -q` rc=0 |

## 4. R0–R5 实施要点

- **R0（基线确认）**：`git status --porcelain` 空；spec §一 五处病灶行号逐条比对开工树一致（`claimTransition` 占位先于 Feign、`SETTLE_TTL_HOURS` 24h、回退 `resolveClub` 取当前团、`weekStart` 恒 null、`settleAndReconcile` 零跑团对账），无阻断性漂移。
- **R1（锚点表 DDL）**：`club_distance_contribution` 8 列（record_id PK / club_id / user_id / distance_meters DECIMAL(10,2) / week_start DATE / status TINYINT / created_at / reversed_at）+ `idx_club_status`、`idx_week` 两索引；双写 `sql/02-record-db.sql` 末尾（只追加，既有段落零改动）与 `sql/migrations/add-club-distance-contribution.sql`（首行 `USE record_db;`，`CREATE TABLE IF NOT EXISTS`，建表 DDL 两处逐字一致）。
- **R2（record 侧锚点化）**：
  - 正向：`INSERT IGNORE` 命中 1 → 上报 `+distance`；命中 0 且 `reactivate(REVERSED→CREDITED)` 影响 1 → 上报；仍 CREDITED → 幂等跳过；
  - 回退：`selectById` 读锚点 → 乐观 `updateStatus(CREDITED→REVERSED)` 影响 1 → 按锚点快照（clubId / week_start / distance_meters）上报 `−distance`；影响 0 / 无锚点 → 跳过；
  - 幂等键退役：`SETTLE_KEY_PREFIX`、`SETTLE_TTL_HOURS`、`STATE_CREDITED/REVERSED` 常量与 `RBucket`/`RedissonClient` 依赖整体删除（构造器 5 参→4 参），DB 锚点为唯一幂等源；
  - 回退不再 `resolveClub`、不再重查 `sport_record.distance`；`week_start` 入账时按东八（`Asia/Shanghai`）归一为周一写入锚点并随 payload 上报；best-effort 契约保持（全 catch 零抛出），Feign 失败不回滚锚点占位（裁决④）。
- **R3（leaderboard 对账扩展）**：`settleAndReconcile` 同锁同周期新增 `reconcileClubDistance()`（第 3.2 步，位于总榜纠偏与 `markSettled` 之间）：三组汇总 SQL（`INNER JOIN sport_record` 存活过滤）→ `club:overall`（按 club_id）、`club:weekly:{weekStart}`（按 (club_id, week_start)）、`club:member:{clubId}`（按 (club_id, user_id)）覆盖式 ZADD + 残留清理；整轮 best-effort 包裹（异常仅 WARN，不拖垮个人榜与报表快照）；leaderboard 侧锚点 Mapper 为只读接口（无 `BaseMapper`、无写注解），锚点行零翻转。
- **R4（顺手项）**：`getClubContributions` Javadoc 由「正常口径下成员分值为非负」精确化为「成对落库时非负、不承诺恒非负，异常窗口由对账收敛」；club 端点 size 语义核对：`getClubLeaderboard`（默认 20/上限 100）与 `getClubContributions`（默认 20/上限 100）同款算式、控制器均直传 `Integer size`，实测**零行为改动**，故未改代码，仅登记。
- **R5（收口）**：七门禁 + DDL 门全量逐位见 §3；测试基线只增不减（record 406→411、leaderboard 139→145）；handoff 改动清单与 `git diff --name-only` 双向全等。

## 5. 决策记录与偏差说明

1. **锚点表落 record_db，leaderboard 只读（裁决①）**：实现取「只读 Mapper 不继承 `BaseMapper` + 三组汇总 `INNER JOIN sport_record` 存活过滤」；孤儿锚点落出汇总、锚点行状态零翻转（「曾入账」是历史事实）。证据：新测试 `mapperQueries_filterOrphansByJoin_andExposeNoWriteMethods` 断言 3 个 `@Select` 均含存活连接且 Mapper 零写注解。
2. **Redis 幂等键整体退役（裁决②）**：删除键前缀/TTL/状态字符串常量与 Redisson 注入；`record-service` 侧构造器收敛（`sportRecordMapper` / `clubDistanceContributionMapper` / `userApi` / `leaderboardApi`）。证据：本服务（ClubDistanceSettleService）源码零 Redisson 引用 + 单测 19 例零 Redis 打桩（复核 N4 措辞收敛：record-service 其他服务如 RecordLikeService 的既有 Redisson 使用不受影响）。
3. **回退以锚点行快照为准（裁决③）**：clubId / week_start / distance_meters 全部取入账时点值，不再 `resolveClub`、不再重查记录里程。证据：`reConfirmed_whenCredited_reversesByAnchorSnapshot`（断言扣回旧团 42、旧周、快照 3400m，且 `userApi` / `sportRecordMapper` 零交互）。
4. **Feign 失败不回滚锚点占位（裁决④）**：上报异常只 WARN；锚点保持 CREDITED，漂移由 R3 对账收敛。证据：`leaderboardFailure_isSwallowed_andAnchorNotRolledBack`（Feign 抛 4008 后无任何状态回滚调用）。
5. **历史周键残留接受（裁决⑤）+ 落地边界**：仅「锚点 (club_id, week_start) 分组可达的周键」与「当周键」参与覆盖纠偏与键内残留清理；不可达历史周键零读写（不遍历、不 DEL）。证据：`settle_oldWeekAnchor_onlyTouchesReachableWeekKey`（仅旧周键被 ZADD、当周键不下发写）；`settle_noCreditedAnchors_clearsCurrentWeekResidualOnly`（无锚点时仅当周键清残留）。
6. **解散团清零（裁决⑥）**：`club:overall` 中无 CREDITED 汇总之团 ZREM，其 `club:member:{clubId}` 整键 DEL。证据：`settle_prunesStaleClubAndDeletesItsMemberKey`。
7. **re-credit 同时刷新锚点快照（实施决策，在裁决③语义内强化）**：背景——`INSERT IGNORE` 命中 0 时若只把状态从 REVERSED 翻回 CREDITED 而不刷新快照，则「通过→回退→再通过」跨团/跨周时会出现「加在新团/新周、下次回退仍扣旧团/旧周」的二次漂移（把 N5/跨周错扣推后一次）。选项——A：严格按 spec 字面只翻状态；B：翻状态并刷新 club_id/user_id/distance_meters/week_start/created_at、清 reversed_at。**采纳 B**：影响行数语义（乐观 UPDATE）不变，仅让「锚点行 = 当前 CREDITED 时点快照」成为不变式；证据 `rePassed_afterReversal_reCreditsWithRefreshedSnapshot`。与裁决③（回退取快照）同向，无冲突。指导侧终裁：采纳。
8. **成员键残留逐成员 ZREM（实施决策，裁决⑤边界内）**：spec 字面只要求「整键无汇总时 DEL」；实测该口径无法收敛「团仍在榜、个别成员回退后残留正分」的漂移。选项——A：仅整键 DEL；B：整键 ZADD 覆盖 + 键内残留成员 ZREM（+ 无汇总团整键 DEL）。**采纳 B**：等价于把「覆盖纠偏」贯彻到成员维度，跑团键成员规模以跑团成员数为上界；证据 `settle_prunesStaleClubAndDeletesItsMemberKey`。指导侧终裁：采纳。
9. **只读成员枚举用 `ZREVRANGE 0 -1` 而非 `SCAN`**：跑团三组键的成员上界是「跑团数 / 单团成员数」，整键读成本可控；个人总榜仍保持 `SCAN` 分批口径（B18/D3 哨兵 `settleAndReconcile_usesScanNotFullDumpRange` 与 `scanInvokedExactlyOnce` 全绿未适配）。理由：个人总榜哨兵明确禁止对总榜用 `range` 全量倾倒，跑团键无此规模风险且需「成员集合 - 权威集合」差集。指导侧终裁：采纳。
10. **首轮部署口径登记（需指导侧知悉，非阻断）**：锚点为唯一权威源、本课题白名单不含历史回填迁移，故部署后首轮对账会把「TASK-275/276 期已入 ZSet 但无锚点行」的跑团里程按「无 CREDITED 汇总之团」清零（含当周键）。这与裁决⑥「无锚点即清榜」同向、与「锚点行为权威源」一致；若需保留历史榜单，建议后续独立课题以一次性回填迁移收敛（不在本课题 13 项白名单内，未实施）。指导侧终裁：知悉接受。
11. **对账整轮 best-effort**：`reconcileClubDistance` 以 try/catch 包裹，任一汇总查询异常仅 WARN 并返回 0，不影响个人总榜纠偏、`settled_at` 盖戳与日报快照；证据 `settle_clubReconcileFailure_doesNotBreakPersonalSettle`。
12. **偏差登记**：无「与 spec 冲突」项；上述 7–11 均为 spec 未明确处的实施取值，已按「先登记后处置」逐条留痕。spec §一 行号证据与开工树一致，无漂移。

### 5.1 非阻断项登记（独立复核 N1–N9，指导侧终裁追认）

| # | 项 | 复核结论 | 处置 |
| :- | :--- | :--- | :--- |
| N1 | 8 列 vs 11 列口径确证 | `information_schema.columns` = 8；初记「11 列」系「列定义 8 行 + 键 3 行」的输出行口径歧义；实际 8 列与 spec §R1 一致，索引 = PRIMARY + 2 二级索引 | §3 门禁 8 已随封盘笔订正为「8 列 + 2 二级索引」 |
| N2 | leaderboard PMD p4 归因 | `UnnecessaryFullyQualifiedName`（`LeaderboardService` `resolveNicknames` 内）为既有行，但基线无 `import java.util.HashMap`，C-01 新增该 import 才使其「多余」 | §3 门禁 2b 已随封盘笔改记「本任务触发、priority 4 不收门禁」 |
| N3 | §2 例数笔误 | 「15 例→19 例」应为「14 例→19 例」（与 record 406→411 自洽；派发书「14→19」正确） | §2 已随封盘笔订正 |
| N4 | §5 决策②表述过宽 | 「record-service 全模块零 Redisson」过宽：本服务已零引用，全模块仍有 RecordLikeService/TrackPointArchiveService 等既有 Redisson（非本任务引入） | §5 决策②已随封盘笔收敛为「本服务零引用」 |
| N5 | 逐成员 ZREM 无专属断言 | 成员键内「团仍在榜、个别成员已回退」的逐成员 ZREM 无专属断言（逻辑与周键残留清理同构、已被覆盖） | 挂账：后续独立小课题可补 1 例「团仍存续 + 个别成员全回退」断言 |
| N6 | credit() 幂等判定前先 resolveClub | 重复投递多一次 Feign（无正确性影响） | 不改，登记备查 |
| N7 | 回退上报 clubName=null | leaderboard 侧已守卫；名称缓存仅入账路径维护，无行为回退 | 不改，登记备查 |
| N8 | E2E 环境前置 | 默认 `%TEMP%` 下 esbuild 报 Access denied；复用旧 build-tmp 会 trace zip 截断造成 6 例假失败，换新建干净目录后 14/14 | 登记备查：后续 E2E 复跑须将 TMP/TEMP 指向新建干净目录 |
| N9 | C-01 合计读数笔误 | §1 初记「+1049/−158」，实测 **+1049/−159**（`--shortstat` 同；逐文件 16 行无误） | §1 已随封盘笔订正 |

## 6. token 门 29 项逐项读数（基线 `1e4462b` / C-01 `a28d18d` / C-02 本笔）

- 13.4 → 173 / 173 / 174
- 18.0 → 189 / 189 / 190
- 73.93 → 130 / 130 / 131
- 68.8 → 125 / 125 / 126
- 6315 → 105 / 105 / 106
- 1.8612 → 104 / 104 / 105
- 3.3066 → 104 / 104 / 105
- 5.7056 → 110 / 110 / 111
- 9.408 → 95 / 95 / 96
- 36525962432 → 88 / 88 / 89
- 36586847965 → 85 / 85 / 86
- 36438897772 → 87 / 87 / 88
- 36399582548 → 85 / 85 / 86
- 36098038547 → 83 / 83 / 84
- 2806 → 125 / 125 / 126
- 598 → 122 / 122 / 123
- 36736221648 → 82 / 82 / 83
- 36808102571 → 70 / 70 / 71
- 36821040708 → 64 / 64 / 65
- 36845152965 → 61 / 61 / 62
- 36871294588 → 59 / 59 / 60
- 36880083885 → 61 / 61 / 62
- 36958994260 → 60 / 60 / 61
- 36976873215 → 55 / 55 / 56
- 36992632143 → 52 / 52 / 53
- 36995450125 → 50 / 50 / 51
- 37008317295 → 49 / 49 / 50
- 37021305016 → 48 / 48 / 49
- 37591580687 → 47 / 47 / 48
- 合计：2568 / 2568 / 2597（逐项零增量、末树恰 +29）

## 7. 收口自证与回滚

- `git status --porcelain` 收口后为空；`git log --oneline 1e4462b..HEAD` 恰 2 笔线性（C-01 `a28d18d` + C-02），`origin/main...HEAD` = `0 3`（含基线立项笔 `1e4462b`，本课题自身 2 笔），严格零推送；
- 回滚：`git revert` C-02 + C-01 即回到挂账态；数据面残留只需 `DROP TABLE IF EXISTS club_distance_contribution`（表为新增、无既有表改动），跑团三组 ZSet 由 TASK-276 期事件增量继续维护，无外部残留；
- 证据文件（本机 `.trae/tmp/`，gitignored 不入库）：`t278-offline.log`（1208 逐模块）、`t278-static-record.log` / `t278-static-lb.log`（静态门两模块）、`t278-ddl.sh` + `t278-ddl-run.log`（DDL 双跑与两路径建表）、`t278-migrate.sh` + `t278-migrate-run.log`（migrate.sh 双跑 16/16）、`t278-tok-base.txt` / `t278-tok-c01.txt`（token 三树逐项）、`lex278.txt` + `t278-lex-gate.sh`（词面门模式与四 locale）、`t278-lb-test.log`（leaderboard 145 例）、`t278-token-precheck.sh`（C-01 文件集 29-token 预扫描）。
- 复核侧证据（本机 `.trae/tmp/t278-rv-*`，gitignored 不入库，共 29 件）：`t278-rv-report.md`（五块复核报告总台账）与 `t278-rv-offline.log` / `t278-rv-static.log` / `t278-rv-gates.log` / `t278-rv-ddl.log` / `t278-rv-e2e-retry2.log` 等（八门禁独立复跑留证；复核收口自证 `git status --porcelain` 为空）。
