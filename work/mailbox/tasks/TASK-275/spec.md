# TASK-275 立项任务书（跑团俱乐部与团队里程榜系统 Running Clubs & Team Leaderboard）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-19 指导侧终裁；开工基线 main @ `a642d5d`（立项笔）；C-01 `53732b0` / C-02 `ec7f517`；FULL 档：指导亲核三块 + 独立复核五块双 PASS 零阻断，七门禁实测全绿；封盘批次统一推送 origin/main，CI 外部终验随后登记）

## 1. 证据链与业务背景（2026-10-19 指导侧）

全仓 54 项技术债、压测基准套件、代码规范清零、徽章成就体系、路段刷榜系统、公里分段配速与海拔剖面图，以及轨迹智能纠偏对比与每周运动智能周报（TASK-269~274）已全量闭环，Surefire 测试数达 1135 测稳态。
用户已选定**方向三（跑团俱乐部与团队对抗 PK / 跑团俱乐部与团队里程榜系统，FULL 档）**：
1. **团队协作与群体归属感缺口**：平台当前具备个人运动记录、好友互动（申请/点赞/好友榜）及赛段 KOM 个人英雄主义榜单，但缺乏团队与群体层面的组织载体。跑者无法创建跑团、加入跑团，缺乏团队身份标识与集体荣誉感；
2. **跑团团队跑量榜单与集体对抗缺口**：单人刷榜容易产生挫败感，通过跑团累计里程排行榜（总榜与周榜），能够将个人运动里程沉淀为集体贡献，激励团员共同出勤拉升团队排名；
3. **单测隔离度历史通病加固（R0 项）**：远端 CI run `39147321152` 复核暴露 `WeeklyDigestServiceTest` 在单测隔离执行时偶发缺失 `SportRecord` 的 MyBatis-Plus LambdaCache，与 TASK-270（`BadgeServiceTest`）如出一辙，借本任务将其纳入白名单加固，确保全仓测试稳健。

---

## 2. 只改清单（白名单，恰 24 业务/测试/UI/SQL/route + 1 台账 = 25 文件）

严格限制在以下 25 个文件内，严禁触碰白名单外任何文件：

1. `record-service/src/test/java/com/sportverify/record/service/WeeklyDigestServiceTest.java`（R0: TableInfoHelper 单测隔离初始化加固）
2. `sql/migrations/add-running-club.sql`（R1: 增量 DDL 迁移脚本，显式声明 USE user_db;）
3. `sql/01-user-db.sql`（R1: user_db 主 DDL 基线双写同步）
4. `api/src/main/java/com/sportverify/api/user/dto/ClubDTO.java`（R2: 跑团核心信息 DTO）
5. `api/src/main/java/com/sportverify/api/user/dto/ClubMemberDTO.java`（R2: 跑团成员信息 DTO）
6. `api/src/main/java/com/sportverify/api/user/dto/ClubCreateDTO.java`（R2: 创建跑团请求 DTO）
7. `api/src/main/java/com/sportverify/api/user/dto/ClubLeaderboardDTO.java`（R2: 跑团里程榜 DTO）
8. `user-service/src/main/java/com/sportverify/user/entity/RunningClub.java`（R3: 实体 RunningClub）
9. `user-service/src/main/java/com/sportverify/user/entity/ClubMember.java`（R3: 实体 ClubMember）
10. `user-service/src/main/java/com/sportverify/user/mapper/RunningClubMapper.java`（R3: Mapper RunningClubMapper）
11. `user-service/src/main/java/com/sportverify/user/mapper/ClubMemberMapper.java`（R3: Mapper ClubMemberMapper）
12. `user-service/src/main/java/com/sportverify/user/service/ClubService.java`（R3: 服务 ClubService）
13. `user-service/src/main/java/com/sportverify/user/controller/ClubController.java`（R3: 控制器 ClubController）
14. `user-service/src/test/java/com/sportverify/user/service/ClubServiceTest.java`（R3: 单元测试 ClubServiceTest）
15. `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`（R4: 跑团里程榜 Redis ZSet 与服务方法）
16. `leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java`（R4: 跑团里程榜对外接口）
17. `leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardClubTest.java`（R4: 单元测试 LeaderboardClubTest）
18. `web/src/api/client.ts`（R5: 跑团 API 请求方法与 TypeScript 契约声明）
19. `web/src/pages/clubs.page.vue`（R5: 跑团广场、详情、成员与创建跑团页面）
20. `web/src/pages/leaderboard.page.vue`（R5: 榜单页面增设「🏃 跑团里程榜」Tab）
21. `web/src/pages/index.page.vue`（R5: 首页仪表盘新增「🏃 我的跑团」卡片与快捷入口）
22. `web/src/App.vue`（R5: 顶部导航栏增设跑团入口）
23. `web/src/typed-router.d.ts`（R5: unplugin-vue-router 路由生成文件）
24. `web/e2e/helpers.ts`（R6: 补齐跑团与团队榜相关 mock 路由分支）
25. `work/mailbox/tasks/TASK-275/handoff.md`（执行交接台账）

---

## 3. 核心条件项与设计规约

### R0（单测隔离加固与 CI 兜底）
- 在 `WeeklyDigestServiceTest.java` 中导入 `com.baomidou.mybatisplus.core.MybatisConfiguration`、`com.baomidou.mybatisplus.core.metadata.TableInfoHelper` 与 `org.apache.ibatis.builder.MapperBuilderAssistant`；
- 在 `@BeforeEach setUp()` 中执行 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SportRecord.class);`；
- 彻底根除 `mvn test -pl record-service -Dtest=WeeklyDigestServiceTest` 独立执行时因 LambdaCache 缺失引发的 MPE 异常。

### R1（数据库表与幂等迁移脚本）
- 新建 `sql/migrations/add-running-club.sql`：
  - 首行显式声明 `USE user_db;`（满足 `SqlMigrationContractTest` 契约校验）；
  - `running_club` 表：`id` (BIGINT AUTO_INCREMENT PK), `name` (VARCHAR(64) NOT NULL UNIQUE), `description` (VARCHAR(255) DEFAULT ''), `badge_url` (VARCHAR(255) DEFAULT ''), `creator_id` (BIGINT NOT NULL), `member_count` (INT NOT NULL DEFAULT 1), `status` (TINYINT NOT NULL DEFAULT 1), `created_at` (DATETIME NOT NULL), `updated_at` (DATETIME DEFAULT NULL)；
  - `club_member` 表：`id` (BIGINT AUTO_INCREMENT PK), `club_id` (BIGINT NOT NULL), `user_id` (BIGINT NOT NULL), `role` (VARCHAR(20) NOT NULL DEFAULT 'MEMBER'), `joined_at` (DATETIME NOT NULL)；
  - 核心约束：`club_member` 设立 `UNIQUE KEY uk_user_club (user_id)`，物理保障「一人一团」；设立 `KEY idx_club_role (club_id, role)`；
- 在 `sql/01-user-db.sql` 末尾追加相同的两张表建表 DDL，保持双写逐字一致。

### R2（api 模块 DTO 契约）
- `ClubDTO`：包含跑团 ID、名称、简介、徽章、团长 ID、团长昵称、成员人数、状态、创建时间；
- `ClubMemberDTO`：包含记录 ID、跑团 ID、用户 ID、用户昵称、角色（`CAPTAIN` / `MEMBER`）、加入时间；
- `ClubCreateDTO`：包含名称（`@NotBlank`）、简介、徽章 URL；
- `ClubLeaderboardDTO`：包含名次 `rank`、跑团 ID `clubId`、跑团名称 `clubName`、成员数 `memberCount`、总里程公里数 `totalDistanceKm`、格式化里程 `formattedDistance`。

### R3（user-service 跑团服务与控制器）
- 实体与 Mapper：基于 MyBatis-Plus 注解落地 `RunningClub` 与 `ClubMember`，以及 `RunningClubMapper` 与 `ClubMemberMapper`；
- `ClubService` 核心逻辑：
  - `createClub(Long caller, ClubCreateDTO dto)`：校验 caller 未加入任何跑团（`memberMapper.selectOne` 为空），跑团名称未被占用；插入 `running_club`（creator_id=caller, member_count=1）与 `club_member`（role=CAPTAIN）；
  - `listClubs(String keyword, Integer page, Integer size)`：跑团广场查询，支持 keyword 模糊搜索与分页/钳制（最大 100），返回列表；
  - `getClub(Long clubId)`：获取跑团详情，补齐团长昵称；
  - `listMembers(Long clubId)`：获取跑团成员列表，批量补齐用户昵称；
  - `joinClub(Long caller, Long clubId)`：校验 caller 当前未入任何跑团；跑团存在且 status=1；插入 `club_member`（role=MEMBER），原子累加 `running_club.member_count`；
  - `leaveClub(Long caller, Long clubId)`：校验 caller 为团员；若为团长且 member_count > 1，禁止直接退出（需移交）；若仅剩团长 1 人，则退出并将跑团 status=0（解散）；普通成员退出删除成员记录并原子递减 member_count；
  - `getMyClub(Long caller)`：查询 caller 当前加入的跑团信息（未入团返回 null）；
- `ClubController`：
  - 路由映射：`@RequestMapping("/api/clubs")`，由网关 `/user/api/clubs/**` 剥离前缀转发；
  - 遵循 `resolveUserId` 统一鉴权与身份判定范式；
- `ClubServiceTest`：覆盖创建跑团、重复加入拦截、重名拦截、退出跑团、团长解散、广场查询等场景。

### R4（leaderboard-service 团队里程榜）
- `LeaderboardService` 团队榜逻辑：
  - 键结构：总榜 `leaderboard:club:overall` 与周榜 `leaderboard:club:weekly:{weekStart}`（Redis ZSet，member=clubId，score=累计里程米）；
  - 跑团名称缓存：Redis Hash `leaderboard:club:names`（field=clubId, value=clubName）；
  - `recordClubDistance(Long clubId, String clubName, Double distanceMeters, LocalDate weekStart)`：原子 `ZINCRBY` 更新总榜与对应周榜，同步更新 Hash 名称缓存；
  - `getClubLeaderboard(String period, Integer size)`：以 `ZREVRANGE` 降序取出前 N 名（默认 20，上限 100），组装 `ClubLeaderboardDTO`（公里数保留 2 位小数）；
- `LeaderboardController`：
  - 暴露 `GET /api/leaderboard/clubs`（参数 `period`=overall|weekly, `size`）；
  - 暴露 `POST /internal/leaderboard/clubs/distance`；
- `LeaderboardClubTest`：覆盖跑团里程累计、位次降序排列、周榜总榜切换、空榜兜底等场景。

### R5（前端跑团广场、排行榜与个人仪表盘）
- `web/src/api/client.ts`：实现 `getClubs`, `getClubDetail`, `getClubMembers`, `createClub`, `joinClub`, `leaveClub`, `getMyClub`, `getClubLeaderboard` 等 API 请求方法及相关类型；
- `web/src/pages/clubs.page.vue`：
  - 跑团广场：展示跑团卡片网格/列表，支持搜索；已入团展示「已在团内」状态；未入团支持点击「加入」；
  - 创建跑团：未入团用户可点击「创建跑团」弹出 Modal 表单；
  - 跑团详情与成员抽屉/弹窗：查看成员名单与退出跑团操作；
- `web/src/pages/leaderboard.page.vue`：
  - 榜单单选按钮组新增 `<a-radio-button value="club">🏃 跑团里程榜</a-radio-button>`；
  - 支持切换「总榜」与「本周」，展示团队排位表格（排名、跑团名称、成员数、累计跑量）；
- `web/src/pages/index.page.vue`：
  - 首页登录态仪表盘新增「🏃 我的跑团」卡片：已入团展示跑团名称与团内角色，未入团引导前往跑团广场；
- `web/src/App.vue`：
  - 顶部导航栏新增「跑团」跳转链接；
- `web/src/typed-router.d.ts`：
  - unplugin-vue-router 自动生成包含 `/clubs` 的路由类型定义。

### R6（E2E mock 协同）
- `web/e2e/helpers.ts`：
  - 补齐 `/user/api/clubs/**` 与 `/leaderboard/api/leaderboard/clubs*` 的 route-mock 分支；
  - 在 `API_PREFIX` 白名单中放行 `/user/api/clubs` 与 `/leaderboard/api/leaderboard/clubs`；
  - 确保 14/14 E2E 用例全绿。

---

## 4. 门禁要求与执行命令（七门禁必须全绿）

| # | 门禁项 | 命令 | 预期 |
| :--- | :--- | :--- | :--- |
| 1 | offline 全量单测 | `scripts/verify/mvn-verify.sh --mode=offline test` | BUILD SUCCESS，1135 测只增不减（0 败 0 错 0 跳） |
| 2 | 静态代码门禁 | `scripts/verify/mvn-verify.sh --mode=offline --static=leaderboard-service` | BUILD SUCCESS，Checkstyle 0 违规，SpotBugs/PMD 通过 |
| 3 | 前端三件套 | `pnpm --dir web test`、`pnpm --dir web type-check`、`pnpm --dir web run e2e` | vitest 90 测只增不减全绿、vue-tsc 0 错、E2E 14/14 全绿 |
| 4 | 契约门 | `bash scripts/verify/mailbox-contract.sh --open=TASK-275 --baseline=e512add` | 退出码 0，25 路径双向全等 |
| 5 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验 | 基线 e512add 树 2452 / C-01 树 2452（逐位零命中）/ C-02 树 2481（自枚举各 +1） |
| 6 | 词面门 | `scripts/verify/check-public-wording.sh` 四 locale | 全 ZERO_HIT（退出码 1） |
| 7 | 行尾与 diff 净 | `git diff e512add HEAD --check` 与 `git status --porcelain` | 输出为空，CR=0 纯 LF，无 BOM |

---

## 5. 约束红线

1. **严格遵守 DDL 双写规范与 USE 声明**：`add-running-club.sql` 首行必须为 `USE user_db;`，且建表语句与 `01-user-db.sql` 追加内容逐字一致；
2. **零新增第三方外部依赖**：前端严禁引入新 npm 包，纯 Ant Design Vue + CSS 实现；后端严禁引入新 Maven 坐标；
3. **严格遵守双提交纪律**：
   - C-01：`feat(user,leaderboard,web): 落地跑团俱乐部与团队里程榜系统全链路（TASK-275）`（恰 24 业务/测试/UI/SQL/route 文件）；
   - C-02：`docs(mailbox): TASK-275 实施 handoff 回填（TASK-275）`（仅 `handoff.md` 1 文件）；
4. **严格零推送**：执行期间禁止向远端 `origin` 推送任何 commit，封盘前保持本地。

---

## 6. 停止与阻断条件

1. 若修改超出 25 项白名单，立即停止并上报；
2. 若 Checkstyle 产生新增违规或测试出现回归，必须在白名单内就地修复，严禁降级规则；
3. 若发现 token 门 29 项历史度量指标被触碰，立即撤回改动并换用安全变量名。

---

## 7. 验收留痕

待子 Agent 实施完成后，由指导侧执行亲核三块（提交链/高风险定向抽查/门禁对账）与独立复核五块，达标后封盘归档。
