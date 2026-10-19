# TASK-278 跑团里程对账与回退补偿（锚点持久化 + 对账任务）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-19 指导侧终裁；开工基线 main @ `1e4462b`（立项笔）；C-01 `a28d18d` / C-02 `c33150c`；FULL 档指导亲核三块 + 独立复核五块双 PASS 零阻断；封盘批次统一推送 origin/main，CI 外部终验随后登记）

## 一、背景与动机

TASK-276 复核非阻断项 N3/N4/N5 收敛课题（BACKLOG 条目 22）。立项侦察（2026-10-19，行号级证据）坐实五处病灶，其中第 4 处为本轮新发现：

1. **N3 双记窗口（先占位后调用）**：`ClubDistanceSettleService.claimTransition` 回退分支 L168-171 先 `compareAndSet(CREDITED, REVERSED)` 占位、上层 L116 才调 `leaderboardApi.recordClubDistance(-meters)`；Feign 失败时 L119-123 仅 WARN——幂等键已翻转为 REVERSED 而负里程永不上报（重放同事件 CAS 恒失败）。**正向对称同病**：L157 `trySet(CREDITED)` 成功 + Feign 失败 = 占位未入账，且状态回调为一次性同步 Feign 无重放语义，漂移无人收敛；
2. **N4 TTL 过期静默跳过**：幂等键 `club:distance:settle:{recordId}` TTL 24h（`SETTLE_TTL_HOURS`），申诉改判链路（PASSED→RE_CONFIRMED）完全可能超过 24h，键过期后回退 CAS 对不存在键恒 false → L167-172 静默跳过，贡献 ZSet 残留正里程；
3. **N5 跨团漂移**：回退方向 `resolveClub(record.getUserId())` 取**当前**所在团，而 `ClubService.leaveClub` L240/L249 退团为物理 DELETE（`club_member` 无 status 列、`uk_user_club` 唯一），换团历史零记录——回退负里程错扣到新团，旧团残留正里程；
4. **新发现·跨周错扣**：`recordClubDistance` L820 周榜键由入参 `weekStart` 归一，而 record 侧 L114 恒传 null → leaderboard 归一**调用时**当周周一。跨周改判（本周回退上周入账的里程）时负里程扣到本周周榜，上周周榜残留——与 N5 同构的「时点漂移」缺陷；
5. **对账空缺**：`settleAndReconcile`（LeaderboardService L462）仅以 `leaderboard_contribution` ACTIVE 汇总纠偏个人总榜 `leaderboard:overall`；跑团三组 ZSet（`leaderboard:club:overall` / `leaderboard:club:weekly:{weekStart}` / `leaderboard:club:member:{clubId}`）零对账——Redis 键丢失/漂移/前述四类病灶均无自愈路径。

**根因归纳**：跑团里程的幂等态与入账快照（团、周、距离）只存放在 24h TTL 的 Redis 键中，无持久化锚点、无对账权威源——与个人总榜「锚点行为准 + ZSet 投影 + 结算纠偏」的成熟范式（`leaderboard_contribution` 表 + `settleAndReconcile`，ADR-0009 最终一致）结构性不对称。

## 二、目标与验收标准（R0–R5）

- **R0 侦察基线确认**：开工时 `git status --porcelain` 为空、TASK-277 已封盘推送；确认本 spec §一行号级证据与开工树一致（漂移仅登记不阻断）。
- **R1 锚点表 DDL（record_db）**：新增 `club_distance_contribution`（沿 `leaderboard_contribution` 范式，02-record-db.sql L55-65 先例）：
  - 列：`record_id BIGINT PK`（回退锚点）、`club_id BIGINT NOT NULL`、`user_id BIGINT NOT NULL`、`distance_meters DECIMAL(10,2) NOT NULL`（入账时点正数快照）、`week_start DATE NOT NULL`（入账时点归一周一锚点，东八口径沿 TASK-259 BUSINESS_ZONE）、`status TINYINT NOT NULL`（1 CREDITED / 0 REVERSED）、`created_at / reversed_at DATETIME`；
  - 索引：`KEY idx_club_status (club_id, status)`、`KEY idx_week (week_start, status)`；
  - 双写：`sql/02-record-db.sql` 末尾 + `sql/migrations/add-club-distance-contribution.sql`（幂等 `CREATE TABLE IF NOT EXISTS`，沿 `add-running-club.sql` 先例，首行 `USE record_db;`）。
- **R2 record 侧锚点化重构（`ClubDistanceSettleService`）**：
  - 正向（PASSED/RE_PASSED）：`INSERT IGNORE` 锚点 CREDITED 命中 1 行 → 上报 `+distance`；命中 0 行且现态 REVERSED（改判再次通过）→ 乐观 `UPDATE(REVERSED→CREDITED)` 影响 1 行 → 上报；现态 CREDITED → 幂等跳过；
  - 回退（RE_CONFIRMED）：乐观 `UPDATE(CREDITED→REVERSED)` 影响 1 行 → 按**锚点行快照**（clubId / week_start / distance_meters 均取入账时点值）上报 `-distance`；影响 0 行（已 REVERSED / 从未入账）→ 跳过；
  - **幂等键退役**：删除 `SETTLE_KEY_PREFIX` Redis 键路径（DB 锚点为唯一幂等源，治 N4 病根），Redisson 注入随之收敛；
  - **回退不再 `resolveClub`**（治 N5）与不再重查 `sport_record.distance`（快照语义）；正向仍需 `resolveClub` 定位当前团（入账时点团即当时所在团，语义正确）；
  - `week_start` 由 record 侧入账时归一写入并随 payload 上报（治跨周错扣，leaderboard 归一逻辑保留为兜底）；
  - best-effort 纪律保持：锚点写库或 Feign 失败仅 WARN 零抛出（ADR-0009）；**Feign 失败不回滚锚点占位**（状态回调无重放语义，回滚占位无意义；漂移由 R3 对账收敛——与个人榜「锚点行为准，ZSet 是投影」同构）。
- **R3 leaderboard 对账任务扩展**：在 `settleAndReconcile` 框架内（同锁同周期）新增跑团三组键纠偏：
  - 孤儿过滤（只读口径，见裁决①）：对账汇总 SQL 以「锚点 CREDITED 且对应 `sport_record` 行仍存在」为过滤口径（LEFT JOIN / 子查询），record 已物理删除的孤儿行自然落出汇总（榜上剔除）；**锚点行不翻转状态**（「曾入账」是历史事实，leaderboard 对锚点表维持零写入）；
  - 以 CREDITED 汇总为权威源：`(club_id) → SUM` 纠偏 `club:overall`、`(club_id, week_start) → SUM` 逐周键纠偏 `club:weekly:*`、`(club_id, user_id) → SUM` 逐团纠偏 `club:member:{clubId}`（ZADD 覆盖式，幂等纠偏）；
  - 残留清理：`club:overall` 中无 CREDITED 汇总之团 ZREM（含解散团自然清零）；`club:member:{clubId}` 整键无 CREDITED 时 DEL；历史周键不主动遍历（仅被 (club_id, week_start) 分组覆盖纠偏，漂移键超集接受——**预注册裁决⑤**）；
  - 新增只读 Mapper：leaderboard 侧 `ClubDistanceContributionMapper`（SELECT 汇总，跨服务同库只读沿 `SportRecordMapper` 读 `sport_record` 的 ADR-0005 先例）。
- **R4 顺手项（N6/N7）**：`getClubContributions` Javadoc L882-884「正常口径下成员分值为非负」表述精确化（异常窗口由 R3 对账收敛，非恒非负承诺）；club 端点 size 语义核对（L843/L890 同款写法，预计零行为改动，择实登记）。
- **R5 收口**：七门禁全量逐位 + 测试基线只增不减；handoff 双向全等；独立复跑五块。

## 三、白名单（上限 13 项，handoff 声明与 `git diff --name-only <baseline>` 双向全等）

1. `sql/02-record-db.sql`（DDL 双写）
2. `sql/migrations/add-club-distance-contribution.sql`（新增）
3. `record-service/src/main/java/com/sportverify/record/entity/ClubDistanceContribution.java`（新增）
4. `record-service/src/main/java/com/sportverify/record/mapper/ClubDistanceContributionMapper.java`（新增）
5. `record-service/src/main/java/com/sportverify/record/service/ClubDistanceSettleService.java`
6. `record-service/src/test/java/com/sportverify/record/service/ClubDistanceSettleServiceTest.java`
7. `leaderboard-service/src/main/java/com/sportverify/leaderboard/entity/ClubDistanceContribution.java`（新增，只读映射）
8. `leaderboard-service/src/main/java/com/sportverify/leaderboard/mapper/ClubDistanceContributionMapper.java`（新增，只读）
9. `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
10. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardServiceTest.java`
11. 择实：leaderboard 对账任务独立测试类（新增，若单测体量超 LeaderboardServiceTest 承载）
12. 择实：record/leaderboard 既有测试适配（仅限锚点化改造直接波及的断言，禁删弱）
13. `work/mailbox/tasks/TASK-278/handoff.md`

**零触碰红线**：api 模块（本课题零新契约——对账走同库只读，不经 Feign）、`SportRecordService.statusCallback` 挂点与状态机参数、MQ/批量/索引参数、gateway/user/verify/mapmatch 服务 src/test、根 pom 与各模块 pom、package.json、web、docker-compose、.github、既有迁移脚本（V 序列不可改，仅新增）。

## 四、门禁要求

七门禁全量逐位（FULL 档）：offline 全量单测（基线 1197 = 63/79/250/406/203/139/57，record/leaderboard 只增不减）、静态门两模块（record/leaderboard Checkstyle 0 / SpotBugs High=0 / PMD 通过，mapmatch 沿基线不回归）、前端三件套（零前端改动，全绿保持）、契约门（判据 A 两件套 + 判据 B 白名单双向全等）、token 门 29 项三树只增不减、词面门四 locale ZERO_HIT + 阳性对照、diff 净与行尾（--check 空、blob 纯 LF、无 BOM）。DDL 验证：migrations 幂等双跑（scratch 库，沿先例）。

## 五、预注册裁决与停止条件

**预注册裁决**（执行侧无需再申报，直接按此落地；与裁决冲突时停止并汇报）：

1. **锚点表落 record_db**：record-service 写、leaderboard-service 只读（跨服务同库只读沿 ADR-0005 `SportRecordMapper` 先例；不走 Feign 汇总——批量聚合拖垮调用方）；孤儿 record 行不走「翻转锚点状态」路径，对账以 JOIN 存活过滤、锚点行零翻动；
2. **Redis 幂等键退役**：`SETTLE_KEY_PREFIX` 路径整体删除，DB 锚点为唯一幂等源（Redisson 注入从本服务收敛移除；删除的常量/测试属本课题白名单内改动，非红线）；
3. **回退以锚点行快照为准**：clubId / week_start / distance_meters 均取入账时点值（时点漂移类缺陷 N5/跨周错扣的根治口径）；
4. **Feign 失败不回滚锚点占位**：状态回调无重放，最终一致由对账任务保证（与个人榜 applyVerified「先锚点后 ZINCRBY、结算纠偏」同构）;
5. **历史周键残留接受**：对账仅覆盖锚点 (club_id, week_start) 分组可达的周键与当周键；锚点不可达的历史周键漂移（如 Redis 手工误操作）不主动遍历清理；
6. **解散团清零**：无 CREDITED 锚点之团从 `club:overall` ZREM、`club:member:{clubId}` 整键 DEL（解散即清榜，与「仅剩团长退团解散」产品语义一致）。

**停止条件**（任一触发即停止修改并汇报，由指导侧裁决）：

1. 需修改 `statusCallback` 挂点位置、状态机参数或引入 MQ/本地事务（违反 ADR-0009 / TASK-276 best-effort 旁路设计）；
2. 需触碰 api 契约模块或新增跨服务 Feign 契约（本课题明确零新契约）；
3. 锚点表与 `leaderboard_contribution` 范式存在无法同构的冲突（如乐观 UPDATE 语义不可移植）；
4. 对账任务无法在纯 JVM 单测落地（沿 TableInfoHelper 注册实体范式；确需真库则停止申报）；
5. DDL 迁移与 migrate.sh 逐文件灌库契约不兼容（首行 USE 约定沿 TASK-267）；
6. 出现 offline 基线回退、Checkstyle 新增违规、需触碰零触碰红线。
