# TASK-275 执行交接（handoff）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-19 指导侧终裁；C-01 `53732b0` / C-02 `ec7f517`；指导亲核三块 + 独立复核五块全 PASS 零阻断，复核实测与本台账读数逐位一致；封盘批次统一推送 origin/main，CI 外部终验随后登记）

## 1. 开工基线

- 开工基线：main @ `a642d5d`（TASK-275 立项笔；派发令同源口径，工作树纯净确认，无跨任务窗口效应）
- C-01 业务/测试/UI 提交：`53732b0` `feat(user,leaderboard,web): 落地跑团俱乐部与团队里程榜系统全链路（TASK-275）`（恰 24 文件，+2154/−11，工作树随即纯净）
- 任务书：`work/mailbox/tasks/TASK-275/spec.md`
- 双提交纪律执行：C-01 恰 24 业务/测试/UI/SQL/route 文件 + C-02 仅本 handoff 1 文件；严格零推送（`origin/main..HEAD` 收口后 3 笔，推送由指导侧统一执行）
- 基线口径备注：任务书 §4 门禁表内基线写作 `e512add`（立项笔父提交），派发令明确开工基线为立项笔 `a642d5d`——本 handoff 全部实测（契约门/token 门/行尾门）均按 `a642d5d` 口径执行。以立项笔为契约基线才能保证判据 B 把立项笔新建的 spec.md 纳入既有声明（否则 spec.md 会被判「改动集未声明」而 rc=1）。

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only a642d5d` 双向全等）

- 改动清单（恰 25 项 = C-01 业务/测试/UI/SQL/route 24 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - record-service/src/test/java/com/sportverify/record/service/WeeklyDigestServiceTest.java
  - sql/migrations/add-running-club.sql
  - sql/01-user-db.sql
  - api/src/main/java/com/sportverify/api/user/dto/ClubDTO.java
  - api/src/main/java/com/sportverify/api/user/dto/ClubMemberDTO.java
  - api/src/main/java/com/sportverify/api/user/dto/ClubCreateDTO.java
  - api/src/main/java/com/sportverify/api/user/dto/ClubLeaderboardDTO.java
  - user-service/src/main/java/com/sportverify/user/entity/RunningClub.java
  - user-service/src/main/java/com/sportverify/user/entity/ClubMember.java
  - user-service/src/main/java/com/sportverify/user/mapper/RunningClubMapper.java
  - user-service/src/main/java/com/sportverify/user/mapper/ClubMemberMapper.java
  - user-service/src/main/java/com/sportverify/user/service/ClubService.java
  - user-service/src/main/java/com/sportverify/user/controller/ClubController.java
  - user-service/src/test/java/com/sportverify/user/service/ClubServiceTest.java
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java
  - leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java
  - leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardClubTest.java
  - web/src/api/client.ts
  - web/src/pages/clubs.page.vue
  - web/src/pages/leaderboard.page.vue
  - web/src/pages/index.page.vue
  - web/src/App.vue
  - web/src/typed-router.d.ts
  - web/e2e/helpers.ts
  - work/mailbox/tasks/TASK-275/handoff.md
- 零触碰面声明：除上述 25 文件外，全仓其他微服务生产源码、业务逻辑、其他单测、构建脚本、编排与环境配置、其他数据库脚本等 100% 零触碰；零新增外部依赖。

## 3. 门禁读数（实测回填，读数时点：门禁 1/2/3 于 C-01 提交前工作树实测；门禁 4/5/6/7 于 C-02 落库前后按标注时点实测）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 全仓 offline 全量测试 | `mvn-verify.sh --mode=offline test` | 0 | BUILD SUCCESS；全仓 surefire 合计 **1164** 测全绿（0 败 0 错 0 跳）= 基线 1135 + 29（user-service +20 = ClubServiceTest 新增；leaderboard-service +9 = LeaderboardClubTest 新增；record-service 18 保持）。分模块：common 63 / gateway 79 / **user 241** / record 392 / verify 203 / **leaderboard 129** / mapmatch 57 |
| 2 | 静态代码门禁 | `mvn-verify.sh --mode=offline --static=leaderboard-service` | 0 | BUILD SUCCESS，Checkstyle **0 违规**，spotbugs/pmd 同轮通过（两段式 install+test-compile/checkstyle/spotbugs/pmd 全绿） |
| 3 | 前端三件套 | `pnpm --dir web test`、`pnpm --dir web type-check`、`pnpm --dir web run e2e` | 0/0/0 | vitest **90** 测全绿（6 文件，只增不减）；vue-tsc 类型检查退出码 0；E2E **14/14** 全绿（14 passed, 53.9s） |
| 4 | 契约门 | `mailbox-contract.sh --open=TASK-275 --baseline=a642d5d` | 0 | C-01 后预跑（handoff 工作树回填态、未提交）rc=0，判据 B 25 路径双向全等；C-02 后终态复跑 rc=0（25 路径双向全等） |
| 5 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | 0 | 基线 `a642d5d` 树实测 SUM = **2481**；C-01 树（`53732b0`）实测 SUM = **2481**（白名单 24 文件逐位零命中：提交前逐文件预扫描 29 项全 0 + 提交后树扫描复核）；C-02 树实测 SUM = **2510** = 2481 + 29（§6 自枚举逐项行恰各 +1）。口径换算说明：派发令表内 2452/2452/2481 系按 `e512add` 树口径的算术（2452 = 立项笔父树实测），立项笔 handoff §3 本行 token 枚举使 `a642d5d` 树天然各 +1（2452 + 29 = 2481）；三树 DELTA 判据不受影响（C-01 − 基线 = 0，C-02 − C-01 = +29），逐项换算见 §6。29 项逐位基线读数：13.4=169、18.0=185、73.93=126、68.8=121、6315=101、1.8612=100、3.3066=100、5.7056=106、9.408=91、36525962432=84、36586847965=81、36438897772=83、36399582548=81、36098038547=79、2806=121、598=118、36736221648=78、36808102571=66、36821040708=60、36845152965=57、36871294588=55、36880083885=57、36958994260=56、36976873215=51、36992632143=48、36995450125=46、37008317295=45、37021305016=44、37591580687=43 |
| 6 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex268.txt HEAD -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml'`（四 locale） | 1 | 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT（rc=1，退出码 1 = 零命中通过）；提交前 24 文件预扫描同 ZERO_HIT |
| 7 | diff 净与行尾 | `git diff a642d5d HEAD --check` / 入库 blob CR 计数 | 0 | diff --check 输出空 rc=0；入库 blob 逐字节扫描 CR=0 纯 LF 无 BOM（新增 14 文件首字节 od 校验无 BOM；改动 10 文件 blob 纯 LF；CRLF 警告仅 autocrlf 工作树回写提示，blob 口径不受影响） |

## 4. 条件项与实施要点（如实回填）

- **R0（单测隔离加固）**：`WeeklyDigestServiceTest` 新增 import（`MybatisConfiguration`/`TableInfoHelper`/`MapperBuilderAssistant`）并在 `@BeforeEach setUp()` 首行执行 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class)`（GoalServiceTest D7 家法同款），消除独立单跑对其他用例先行触发表信息加载的用例序依赖；`mvn test -pl record-service -Dtest=WeeklyDigestServiceTest` 单跑实测 18 测全绿。
- **R1（数据库表与幂等迁移脚本）**：新建 `sql/migrations/add-running-club.sql`，首行 `USE user_db;`（SqlMigrationContractTest 契约口径），`running_club`（id/name 唯一/description/badge_url/creator_id/member_count 默认 1/status 1 正常 0 解散/created_at/updated_at）与 `club_member`（id/club_id/user_id/role CAPTAIN|MEMBER 默认 MEMBER/joined_at）建表均 `CREATE TABLE IF NOT EXISTS` 幂等；`club_member` 设 `UNIQUE KEY uk_user_club (user_id)` 物理一人一团 + `KEY idx_club_role (club_id, role)`；`sql/01-user-db.sql` 末尾追加同名建表 DDL，逐字一致经双提取 diff 实测 IDENTICAL（两表各自 `CREATE TABLE ... ;` 全块比对）。
- **R2（api 模块 DTO 契约）**：`ClubDTO`（id/name/description/badgeUrl/creatorId/creatorNickname/memberCount/status/createdAt）、`ClubMemberDTO`（id/clubId/userId/nickname/role/joinedAt）、`ClubCreateDTO`（name @NotBlank/description/badgeUrl，api 模块既有 jakarta.validation 依赖）、`ClubLeaderboardDTO`（rank/clubId/clubName/memberCount/totalDistanceKm/formattedDistance）四 DTO，@Data + Serializable + serialVersionUID，对齐 UserDTO/LeaderboardDTO 家法。
- **R3（user-service 跑团服务与控制器）**：实体 `RunningClub`（STATUS_ACTIVE/STATUS_DISSOLVED 常量）与 `ClubMember`（ROLE_CAPTAIN/ROLE_MEMBER 常量）+ 两 BaseMapper；`ClubService` 七方法——`createClub`（成员存在性 selectOne 拦截一人一团、名称 selectCount 查重、插 club + CAPTAIN 成员）、`listClubs`（status=1 + keyword LIKE + Page 分页钳制 1..100 默认 20，member_count 降序 id 升序）、`getClub`（补团长昵称，缺失/解散 404）、`listMembers`（joined_at 升序 + selectBatchIds 批量昵称）、`joinClub`（未入团校验 + 正常跑团校验 + MEMBER 插入 + `setSql("member_count = member_count + 1")` 原子累加 + DuplicateKeyException 撞 uk_user_club 兜底）、`leaveClub`（普通成员删行 + `member_count = member_count - 1` 原子递减；团长且有他人 1002 禁退需移交；仅剩团长一人删行并置 status=0 解散、计数清零）、`getMyClub`（未入团返回 null）；`ClubController` 暴露 /api/clubs 七端点（GET 广场 / POST 创建 / GET my / GET {id} / GET {id}/members / POST {id}/join / POST {id}/leave），`resolveUserId` 与 UserController 同款（X-User-Id + authEnabled 降级拒绝）；错误码复用既有枚举（common 模块不在白名单，不新增 CLUB_* 段，语义由 message 承载）：已入团/团长禁退 → 1002 FORBIDDEN、重名 → 3004 IDEMPOTENT_CONFLICT、跑团缺失/解散/未入团退团 → 3001 RECORD_NOT_FOUND（404）。`ClubServiceTest` 20 用例覆盖创建/重名/重复加入/解散团加入/并发撞键/成员退团/团长禁退/仅剩团长解散/广场钳制与关键词/详情与成员昵称/我的跑团各分支，含 Wrapper SQL 段断言（LIKE、member_count DESC、setSql 原子累加片段）。
- **R4（leaderboard-service 团队里程榜）**：`LeaderboardService` 新增三键常量（总榜 `leaderboard:club:overall`、周榜 `leaderboard:club:weekly:{weekStart}` 周一 ISO 日期、名称缓存 Hash `leaderboard:club:names`）与两方法——`recordClubDistance(clubId, clubName, distanceMeters, weekStart)`：双 ZINCRBY 原子累加总榜+周榜、clubName 非空同步 Hash（weekStart 缺省归一化当周周一）、参数非法 400；`getClubLeaderboard(period, size)`：overall（缺省）/weekly 分键、weekly 读当周周一键、未知 period 400，ZREVRANGE 降序前 N（默认 20、钳制上限 100），Hash 补名称（缺失「跑团{id}」占位），公里保留 2 位小数（meters/10 整圆回缩百倍，规避浮点截断）+ `%.2f km` 格式化串。`LeaderboardController` 新增 `GET /api/leaderboard/clubs`；`POST /internal/leaderboard/clubs/distance` 以同文件包级私有顶级类 `ClubLeaderboardInternalController` 承载（/internal/** 无法挂类级 /api/leaderboard 前缀，独立新文件不在白名单；Spring 组件扫描按包装配，包级私有类同样生效）。`memberCount` 为预留字段：ZSet/Hash 不携带成员数，服务端组装恒 0（javadoc 注明待后续计数同步链路填充），E2E 夹具携带真实值供前端渲染。`LeaderboardClubTest` 9 用例覆盖双榜累加/名称同步/空名跳过/参数校验/降序组装/占位兜底/weekly 周一键/size 钳制/空榜与非法 period。
- **R5（前端跑团广场、排行榜与个人仪表盘）**：`client.ts` 新增 ClubDTO/ClubMemberDTO/ClubCreatePayload/ClubLeaderboardDTO 四类型与 getClubs/getClubDetail/getClubMembers/createClub/joinClub/leaveClub/getMyClub/getClubLeaderboard 八方法；新页 `clubs.page.vue`（我的跑团状态条 + 创建入口、名称搜索、分页表格、成员抽屉、加入/退出、创建 Modal，请求序号守卫对齐 E26 范式）；`leaderboard.page.vue` 增设 `<a-radio-button value="club">🏃 跑团里程榜</a-radio-button>`，**点击激活**才发请求（挂载行为零改动，notifications.e2e 对 /leaderboard 请求的精确计数断言不受扰），club 模式独立「总榜/本周」切换组（weekly 传 period=weekly）+ clubColumns（排名/跑团名称/成员数/累计跑量/格式化里程），与 loadLb 共享序号守卫；`index.page.vue` 登录态仪表盘新增「🏃 我的跑团」卡片（已入团展示名称/角色/成员数 + 进入广场与查看里程榜入口，未入团引导态），角色经 getUserProfile().id === creatorId 判定（getMyClub 返回 ClubDTO 不携带本人角色）；`App.vue` 顶部导航增「跑团」入口；`typed-router.d.ts` 按生成器字母序补 `/clubs`（RouteNamedMap 与 _RouteFileInfoMap 两段，与 unplugin-vue-generator 形态逐字对齐）。
- **R6（E2E mock 协同）**：`helpers.ts` 在 handleUser 补 /user/api/clubs 七分支（my 返回 null 引导态、list/create/join/leave/detail/members 状态化夹具）、新增 route `**/leaderboard/api/leaderboard/clubs*` → handleLeaderboardClubs（GET 返回两跑团降序夹具）、监听器白名单由 segmentMocked 扩为 prefixMocked 放行 `/leaderboard/api/leaderboard/clubs` 前缀（`/user/api/clubs` 本就在 /user/api/ 前缀内）；新增 4 组夹具（广场 2 团、成员 2 行、我的跑团 null、里程榜 2 条）；`pnpm --dir web run e2e` 复跑 14/14 全绿（assertNoUnhandled/assertNoUnmocked 全过，index 页新增挂载请求 GET /user/api/clubs/my 已被 mock 覆盖，无 unmocked 兜底 404）。

## 5. 实施期缺陷与决策记录

- **基线口径偏差（非缺陷，需复核知悉）**：任务书 §4 表内契约/token 基线写 `e512add`，派发令为 `a642d5d`（立项笔）。实测确认：以 `e512add` 为契约基线会把立项笔新建的 spec.md 判「改动集未声明」rc=1；token 基线 `e512add` 树 2452、`a642d5d` 树 2481（差 29 = 立项笔 handoff §3 枚举行自枚举）。全部按派发令 `a642d5d` 口径执行，DELTA 判据（C-01=基线、C-02=+29）逐位成立，换算详见 §3 门禁 5 与 §6。
- **错误码复用决策**：common 模块 ResultCode 不在白名单，跑团域不新增错误码段；复用 1002（状态禁止）/3004（重名冲突）/3001（资源缺失，404）+ message 承载语义，与 FriendService 复用 SYSTEM_ERROR+自定义文案的先例同思路但语义更贴。
- **内部端点同文件托管**：POST /internal/leaderboard/clubs/distance 无法挂在 LeaderboardController 类级 /api/leaderboard 前缀下，InternalLeaderboardController.java 不在白名单；采用同文件包级私有顶级类方案（一个 .java 文件两个顶级类，非 public 类经 Spring 组件扫描正常装配），ADR-0009 哨兵测试 LeaderboardWritePathStaysUnproxiedTest 显式注册 bean 面不受影响，静态门禁 0 违规实测通过。
- **memberCount 预留字段**：spec 未给成员数的 Redis 承载形态且 recordClubDistance 签名固定四参；服务端组装恒 0 并 javadoc 注明，E2E 夹具给真实值，避免私加规格外 Hash 键。
- **R0 实施无新增缺陷**：GoalServiceTest 家法直接复用一次通过；新增两测试类首跑全绿（20+9），全量回归无回归。

## 6. token 门 29 项三树逐项读数（a642d5d 口径；C-02 终态 = 本节逐项行自枚举，恰各 +1）

- 13.4 → 171
- 18.0 → 187
- 73.93 → 128
- 68.8 → 123
- 6315 → 103
- 1.8612 → 102
- 3.3066 → 102
- 5.7056 → 108
- 9.408 → 93
- 36525962432 → 86
- 36586847965 → 83
- 36438897772 → 85
- 36399582548 → 83
- 36098038547 → 81
- 2806 → 123
- 598 → 120
- 36736221648 → 80
- 36808102571 → 68
- 36821040708 → 62
- 36845152965 → 59
- 36871294588 → 57
- 36880083885 → 59
- 36958994260 → 58
- 36976873215 → 53
- 36992632143 → 50
- 36995450125 → 48
- 37008317295 → 47
- 37021305016 → 46
- 37591580687 → 45

## 7. 收口自证

- `git status --porcelain` 输出为空；
- 未推送：`origin/main..HEAD` 收口后 = 3 笔（立项笔 `a642d5d` + C-01 `53732b0` + C-02 本笔）——推送由指导侧统一执行，本任务严格零推送；
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 25 路径双向全等）；token 门 C-02 树终态实测 SUM = 2510 与期望一致（2481 + 29，§6 逐项对账）；词面门 C-02 后终态四 locale ZERO_HIT 复核；行尾门 `git diff a642d5d HEAD --check` 空 + blob CR=0 无 BOM 复核。
