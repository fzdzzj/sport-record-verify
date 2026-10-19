# TASK-276 实施交接台账（handoff）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-19 指导侧终裁；开工基线 `9ab922d`；C-01 `fb6adea` / C-02 `fe2e4bd`；指导亲核三块 + 独立复核五块全 PASS 零阻断；非阻断 8 项登记 N1–N8，其中 N3/N4/N5 收敛为对账课题挂账；封盘批次统一推送 origin/main，CI 外部终验随后登记）

## 1. 基线与提交

- 开工基线：main @ `9ab922d`（立项笔；开工时 `origin/main..HEAD` 恰 1 笔、`git status --porcelain` 为空）；
- C-01 提交哈希：`fb6adea`（`feat(record,user,leaderboard,web)`，恰 20 文件 / 1437 插入 / 40 删除 / 4 新建）；
- C-02 提交哈希：本笔（`docs(mailbox)`，仅 handoff 1 文件）；
- 提交链核验：`origin/main..HEAD` 恰 3 笔线性（立项笔 `9ab922d` + C-01 `fb6adea` + C-02 本笔），**零推送**（推送由指导侧统一执行）。

## 2. 改动清单（恰 21 项 = C-01 20 项 + C-02 本文件）

- api/src/main/java/com/sportverify/api/user/dto/ClubMemberContributionDTO.java（新增 +34：成员贡献明细 DTO，rank/userId/nickname/distanceMeters）
- api/src/main/java/com/sportverify/api/user/dto/ClubLeaderboardDTO.java（+5/-2：memberCount javadoc 由「恒 0 预留」订正为「真实计数、查询失败降级 0」）
- api/src/main/java/com/sportverify/api/user/UserApi.java（+29：getClubByUserId 所在跑团、listClubMemberCounts 成员数批查）
- api/src/main/java/com/sportverify/api/user/UserApiFallback.java（+19：两读路径软降级——未入团返 null、批查返空表）
- api/src/main/java/com/sportverify/api/leaderboard/LeaderboardApi.java（+53：recordClubDistance 入账契约 + 内嵌 ClubDistancePayload）
- api/src/main/java/com/sportverify/api/leaderboard/LeaderboardApiFallback.java（+19/-2：入账写路径显式抛 4008，不静默成功）
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java（+28：statusCallback 终态迁移成功分支挂旁路，setter required=false）
- record-service/src/main/java/com/sportverify/record/service/ClubDistanceSettleService.java（新增 +174：净额对账 + RBucket 三态幂等 + best-effort）
- record-service/src/test/java/com/sportverify/record/service/ClubDistanceSettleServiceTest.java（新增 +284：14 用例）
- user-service/src/main/java/com/sportverify/user/service/ClubService.java（+91：transferCaptain 同事务双 role、listMemberCounts 批查、findMembershipInClub）
- user-service/src/main/java/com/sportverify/user/controller/ClubController.java（+17/-2：POST /api/clubs/{clubId}/transfer-captain）
- user-service/src/main/java/com/sportverify/user/controller/InternalClubController.java（新增 +59：/internal/clubs 两只读端点）
- user-service/src/test/java/com/sportverify/user/service/ClubServiceTest.java（+175：移交 7 用例 + 批查 2 用例，21→29）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java（+108/-20：成员贡献 ZSet 读写、真实 memberCount 组装、带符号净额）
- leaderboard-service/src/main/java/com/sportverify/leaderboard/controller/LeaderboardController.java（+21/-4：贡献明细端点 + 内网上报 payload 增 userId）
- leaderboard-service/src/test/java/com/sportverify/leaderboard/service/LeaderboardClubTest.java（+177/-9：9→19 用例）
- web/src/pages/clubs.page.vue（+63/-8：成员抽屉贡献标签、团长移交入口、贡献明细与名单并行拉取）
- web/src/pages/leaderboard.page.vue（+66/-4：跑团 Tab 成员数「人」单位、成员贡献抽屉、贡献列）
- web/src/api/client.ts（+30/-3：ClubMemberContributionDTO 类型、getClubContributions、transferClubCaptain）
- web/e2e/helpers.ts（+19/-3：贡献端点路由、handleLeaderboardClubs 分支、贡献榜夹具）
- work/mailbox/tasks/TASK-276/handoff.md（本文件：C-02 台账回填）

## 3. 七门禁实测读数

| # | 门禁 | 命令 / 口径 | 实测读数（2026-10-19） |
| :- | :--- | :--- | :--- |
| 1 | offline 全量单测 | `scripts/verify/mvn-verify.sh --mode=offline test` | **BUILD SUCCESS**；分模块 63 / 79 / 250 / 406 / 203 / 139 / 57 = **1197**（基线 1164 = 63/79/241/392/203/129/57，只增不减：user +9、record +14、leaderboard +10）；0 败 0 错 0 跳；10:03 完成 |
| 2 | 静态门禁 | `--mode=offline --static=leaderboard-service` | **BUILD SUCCESS**；Checkstyle `You have 0 Checkstyle violations.`；SpotBugs `Error size is 0`；PMD 7.17.0 check 通过；10:05 完成 |
| 3 | 前端三件套 | `pnpm --dir web type-check` / `test` / `run e2e` | vue-tsc **0 错**（09:39）；vitest **6 文件 / 90 用例全绿**（09:40）；playwright **14/14 全绿**（09:44）；e2e 需 TMP/TEMP 指向仓内目录（环境侧 esbuild 清理 %TEMP% 被拒，见 §5） |
| 4 | 契约门 | `scripts/verify/mailbox-contract.sh --open=TASK-276 --baseline=9ab922d` | **rc=0**；判据 A 两件套齐；判据 B 21 路径双向全等（清单与实际改动集零多报零漏报）；10:10 预提交实测 rc=0、10:11 终态复跑 rc=0 |
| 5 | token 门 29 项 | `git grep -cF` 三树比对（字面量见 §6） | 基线树 `9ab922d` **SUM = 2510（B）**；C-01 树 `fb6adea` SUM = **2510**（29/29 逐项零增量）；C-02 树 SUM = **2539**（= B + 29，§6 逐项各 +1） |
| 6 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex268.txt`（四 locale） | default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8` 在 C-01 暂存树与 `HEAD` 树**全 ZERO_HIT**（hits=0）；阳性对照（仓内控制文件含首个词面）`rc=0` 命中、`LC_ALL=C` 同样命中、控制文件已删 |
| 7 | diff 净与行尾 | `git diff 9ab922d HEAD --check` + blob 字节扫描 | 输出**空**；21 文件 blob **CR=0 纯 LF 无 BOM**（`git cat-file` 逐文件字节统计，见 §7） |

## 4. R0–R5 实施要点

- **R0（侦察确认基线）**：开工前 `git status --porcelain` 空、`origin/main..HEAD` 恰立项笔 1 笔；spec 引用行号以 `fba1cb3` 为准已逐点核对——`ClubService.leaveClub` 团长拦截（原 L235）、`LeaderboardService.recordClubDistance`（原 L801）、`LeaderboardController` 内网上报（原 L190）、`ClubLeaderboardDTO.memberCount` 占位、`SportRecordService.statusCallback`（原 L322）结构与 spec 描述一致，**无停止条件触发**。
- **R1（团长移交）**：`ClubService.transferCaptain(operatorId, clubId, targetUserId)`——校验链「跑团存在且未解散 → 操作者本团在册 → 操作者在任团长 → 目标非本人 → 目标本团在册」，失败零写；写操作两条 `LambdaUpdateWrapper`（操作者 CAPTAIN→MEMBER、目标 MEMBER→CAPTAIN）在同一 `@Transactional` 内；`running_club` 零写入（member_count 与 creator_id 均不变，单测 `verify(runningClubMapper, never()).update`）。端点 `POST /api/clubs/{clubId}/transfer-captain?targetUserId=`，身份仍走 `resolveUserId`（X-User-Id）。`ClubServiceTest` 21→29（移交 7：成功双 role 交换+成员数不变 / 非团长 1002 / 操作者未入团 3001 / 目标非本团成员 3001 / 目标是自己 3001 且短路不回查 / 目标未入团 3001 / 参数缺失 400）。
- **R2（里程入账，核心）**：新增 `ClubDistanceSettleService`（record-service），挂点 `SportRecordService.statusCallback` 乐观锁 UPDATE 影响 1 行之后；方向由目标终态决定——`PASSED(2)` / `RE_PASSED(5)` → +distance，`RE_CONFIRMED(6)` → −distance，其余终态（REJECTED/APPEALING/VERIFYING/MANUAL_REVIEW）不动作。里程以库内记录为权威（`sport_record.distance` 公里 × 1000 → 米），入账前经 `UserApi.getClubByUserId` 定位跑团（未入团跳过、user-service 降级跳过），再经 `LeaderboardApi.recordClubDistance` 调既有内网上报端点。幂等：Redisson `RBucket` 键 `club:distance:settle:{recordId}`（TTL 24h，沿 `NotificationEventConsumer` 的 eventId `trySet` 范式），三态 `CREDITED` / `REVERSED`——首入账 `trySet` 成功才生效；回退仅在现值 `CREDITED` 时 `compareAndSet(→REVERSED)` 成功才扣减（从未入账不产生负里程）；回退后再通过 `compareAndSet(REVERSED→CREDITED)` 补账并续期。**best-effort**：方法整体 try/catch，任何异常（记录缺失/无跑团/Feign 4008/Redis 抖动/DB 抖动）只 WARN 留证并返回，`statusCallback` 另加一层 try/catch 保险，**零抛出**（单测 `assertDoesNotThrow` 覆盖 leaderboard 降级与 DB 抖动两路）。`ClubDistanceSettleServiceTest` 14 用例覆盖方向、幂等四态、无团/降级、记录缺失/非正里程、null 入参与 best-effort 契约。
- **R3（成员贡献明细）**：`LeaderboardService.recordClubDistance(clubId, clubName, distanceMeters, weekStart, userId)` 扩展 userId 并改为**带符号净额**（正入账/负回退，0 拒绝），三处 ZINCRBY（总榜 / 周榜 / 新键 `leaderboard:club:member:{clubId}`，member=userId）同号累加保证团队榜与贡献榜恒等；既有键常量值一字未改，仅新增前缀常量。查询 `getClubContributions(clubId, size)`：`ZREVRANGE` 降序前 N（**默认 20、钳制上限 100、下限 1**），昵称 `UserApi.listUsersByIds` 一次批量补齐、缺失或空白回退「跑者{userId}」；端点 `GET /api/leaderboard/clubs/{clubId}/contributions?size=`。`LeaderboardClubTest` 9→19（贡献写入含负净额与 null userId、贡献榜降序/占位/钳制/空榜/null clubId）。
- **R4（memberCount 真实计数）**：新增 `InternalClubController`（`/internal/clubs`）——`GET /user/{userId}` 返所在跑团（无团 data=null）、`GET /member-counts?ids=` 返 clubId→member_count 批查；`ClubService.listMemberCounts` 单次 `selectBatchIds` 回表，缺项不出现。`LeaderboardService.getClubLeaderboard` 组装前对榜单 TOP N 一次批查真实成员数，**失败/空表/缺项降级 0**（`resolveClubMemberCounts` 自身 try/catch，绝不拖垮榜单主体）；`ClubLeaderboardDTO` 与 service javadoc 同步由「恒 0」订正为「降级 0」。
- **R5（前端与 E2E）**：跑团 Tab 成员数展示原本已在（TASK-275 的 clubColumns 已含 memberCount 列），按 spec「已有字位则校验既有渲染」改为显式 `{{ memberCount }} 人` 展示并追加「成员贡献」列与抽屉（挂载期零请求，点击才拉贡献端点）；`clubs.page.vue` 成员抽屉增成员贡献标签（贡献 km + 名次，与名单 `Promise.all` 并行拉取、贡献失败单独 catch 归零不阻断名单）与团长「移交团长」二次确认入口（仅团长可见）；`client.ts` 增 `ClubMemberContributionDTO` 类型与 `getClubContributions` / `transferClubCaptain` 类型化调用；`helpers.ts` 增 `clubs/*/contributions` 路由与 handler 分支、贡献榜夹具（并订正里程榜夹具注释为真实计数口径）。既有 14 E2E 用例零破坏（14/14 绿）。

## 5. 决策记录与偏差说明

- **错误码复用（无新增段）**：common 模块 `ResultCode` 不在白名单，移交全程只复用既有码——非团长/状态禁止 → 1002 `FORBIDDEN`；操作者未入团、目标非本团成员、目标为本人 → 3001 `RECORD_NOT_FOUND`（按 spec R1「目标侧统一 3001」的字面口径落地，语义由 message 承载，与 TASK-275 跑团域复用先例一致）。
- **移交端点请求形态**：`targetUserId` 走 query 参数而非请求体 DTO——新增一个 api 请求体文件会突破「恰 21 项」白名单；query 也让「目标用户」与「操作者身份」在语义上清晰分离（身份只来自网关 X-User-Id）。
- **入账契约请求体承载**：`LeaderboardApi` 以接口内嵌类型 `ClubDistancePayload` 承载请求体（与 leaderboard-service 侧内网 payload 按字段名同构），同样为避免新增 api 模块文件；两侧各自持有自己的类型符合本仓「接口与实现分离」既有形态。
- **recordClubDistance 语义变更（净额/带符号）**：既有实现只接受正数（`<= 0` 拒绝），净额回退要求负数能流经同一内网端点，故改为「非 0」校验并同步更新内网 payload（增 userId）与既有单测（`LeaderboardClubTest` 原 `-50.0` 断言改写为「0 拒绝 + 负数回退」两例）；这是 R2/R3 的必要面，未触碰任何既有 Redis 键常量值。
- **幂等三态而非单次 trySet**：spec 要求 recordId 级 `trySet` 幂等，但同一 recordId 在改判链路需两次相反方向生效（补账/回退）——单键单次 `trySet` 只能挡重复投递、无法表达方向。实现取「`trySet` 首置 + `compareAndSet` 方向迁移」组合：首入账仍用 `trySet`（重复事件天然跳过），回退/再入账用 CAS 保证同方向恒只生效一次，TTL 统一 24h。
- **入账占位与失败取舍**：幂等占位在调用 leaderboard 之前完成；调用失败**不释放占位**（避免「响应丢失但服务端已入账」时重放造成双记）。口径为「宁少记不双记」，与 spec §七「短时少记可接受、对账兜底为后续课题」一致。
- **`@Autowired(required = false)` 的必要性（实测发现）**：首轮全量 offline 在 record-service 单点红——`RecordServiceCircularDependencyTest` 以 `registerBean` 按需装配上下文，不注册本任务新增的旁路 bean，严格模式下 `setClubDistanceSettleService` 注入失败。该测试不在白名单（禁改），故在 `SportRecordService`（白名单内）把该 setter 声明为 `required=false`：裁剪上下文缺 bean 时按「无旁路」降级，生产上下文由组件扫描正常注入。修后 record-service 406/406 全绿。
- **`getClubLeaderboard` 成员数只批查不缓存**：刻意不为 memberCount 另建 Redis 计数键（避免规格外的第二个真相源与写入放大），每次组装一次 Feign 批查；失败降级 0 保持占位语义，符合「降级不阻断榜单」。
- **e2e 环境侧偏差（非代码缺陷）**：首跑 `pnpm --dir web run e2e` 在 webserver 构建阶段失败（esbuild 清理 `%TEMP%` 下临时文件 `Access is denied`，环境侧拒绝删除）；将 `TMP`/`TEMP` 指向仓内 `.trae/tmp/esb` 后 14/14 全绿。**未改任何脚本/pom/配置**，仅运行环境变量注入，脚本与 git 树零变更。
- **memberCount 前端字位**：spec R5 表述为「若前端未渲染则补渲染」，实测 TASK-275 已渲染该列，故不做重复补列（避免同一字位两处渲染），改为加载人单位与贡献入口；此为「校验既有渲染」分支的落地方式。

## 6. token 门 29 项逐项读数（C-02 终态；基线树 `9ab922d` = B 与 C-01 树逐位持平，本节逐项行自枚举恰各 +1）

- 13.4 → 172
- 18.0 → 188
- 73.93 → 129
- 68.8 → 124
- 6315 → 104
- 1.8612 → 103
- 3.3066 → 103
- 5.7056 → 109
- 9.408 → 94
- 36525962432 → 87
- 36586847965 → 84
- 36438897772 → 86
- 36399582548 → 84
- 36098038547 → 82
- 2806 → 124
- 598 → 121
- 36736221648 → 81
- 36808102571 → 69
- 36821040708 → 63
- 36845152965 → 60
- 36871294588 → 58
- 36880083885 → 60
- 36958994260 → 59
- 36976873215 → 54
- 36992632143 → 51
- 36995450125 → 49
- 37008317295 → 48
- 37021305016 → 47
- 37591580687 → 46

## 7. 收口自证

- `git status --porcelain` 收口后为空；`git diff 9ab922d HEAD --check` 输出为空；
- 21 文件 blob 行尾/编码：`git cat-file -p <rev>:<path>` 逐文件字节统计 **CR = 0、纯 LF、无 BOM**（autocrlf=true 下仓库存 LF，索引与提交对象均为 LF）；
- 未推送：`origin/main..HEAD` 收口后 = 3 笔（立项笔 `9ab922d` + C-01 `fb6adea` + C-02 本笔），推送由指导侧统一执行；
- 契约门 C-02 终态复跑 rc=0（判据 A 两件套齐 + 判据 B 21 路径双向全等）；
- token 门 C-02 树终态实测 SUM = 2539 = 2510 + 29（§6 逐项对账）；
- 词面门 C-02 终态四 locale ZERO_HIT 复核；阳性对照命中、控制文件已清理；
- 零触碰复核：未改 sql/、任何 pom、package.json、gateway/verify/mapmatch 的 src/test、scripts/、.github/、docker-compose、index.page.vue、typed-router.d.ts、既有 Redis 键常量值、状态机与 MQ 参数。
