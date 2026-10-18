# TASK-269 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-18 实施完成，七门禁全绿，待指导亲核三块把关）

## 1. 开工基线

- 开工基线：main @ `20b82dd`（TASK-269 立项笔；派发令口径，详见 §4 口径事实登记第 1 条）
- C-01 业务提交：`b9cbb6d`（`feat(record,web): 落地运动徽章与成就体系全链路闭环（TASK-269）`；15 文件 +1392/−11）
- 任务书：`work/mailbox/tasks/TASK-269/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 20b82dd` 双向全等）

- 改动清单（恰 16 项 = C-01 业务/SQL/测试 15 + C-02 台账 1，逐行路径 token 供判据 B 机器比对；恰 14 派发基数 +1 偏差文件见 §4 偏差登记第 1 条）：
  - sql/02-record-db.sql
  - sql/migrations/add-user-badge-table.sql
  - record-service/src/test/java/com/sportverify/record/db/SqlMigrationContractTest.java
  - record-service/src/main/java/com/sportverify/record/enums/package-info.java
  - record-service/src/main/java/com/sportverify/record/enums/BadgeType.java
  - record-service/src/main/java/com/sportverify/record/entity/UserBadge.java
  - record-service/src/main/java/com/sportverify/record/dto/BadgeDTO.java
  - record-service/src/main/java/com/sportverify/record/mapper/UserBadgeMapper.java
  - record-service/src/main/java/com/sportverify/record/service/BadgeService.java
  - record-service/src/main/java/com/sportverify/record/controller/BadgeController.java
  - record-service/src/test/java/com/sportverify/record/service/BadgeServiceTest.java
  - record-service/src/test/java/com/sportverify/record/controller/BadgeControllerTest.java
  - web/src/api/client.ts
  - web/src/pages/index.page.vue
  - web/src/pages/feed.page.vue
  - work/mailbox/tasks/TASK-269/handoff.md
- 零触碰面声明：除上述 16 文件外，全仓其他微服务生产源码、既有单测与 E2E 测试、构建脚本、编排与环境配置、规格与台账共享件（PLAN/HANDOVER/BACKLOG/HANDOVER 状态行）等 100% 零触碰；未引入任何新的外部依赖（新代码仅用既有 MyBatis-Plus / spring-tx / Mockito）。

## 3. 门禁读数（实测回填，每行标注读数时点）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test` | 0 | BUILD SUCCESS（Total time 10:30 min）；surefire **63/79/221/351/203/110/36**（common/gateway/user/record/verify/leaderboard/mapmatch），record 323→351 净增 +28（BadgeServiceTest 20 + BadgeControllerTest 7 + SqlMigrationContractTest 1→2），全仓 1035→**1063**，0 失败 0 错误 0 跳过。读数时点：C-01 前工作树态（源码与 C-01 blob 逐字一致） |
| 2 | record 静态三件套 | `mvn-verify.sh --mode=offline --static=record-service` | 0 | BUILD SUCCESS；**You have 0 Checkstyle violations**（0 新增 0 存量，新包 package-info 已建），spotbugs/pmd 同轮通过（pmd 13 warnings 属非违规提示，rc=0）。读数时点：C-01 前工作树态 |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-269 --baseline=20b82dd` | 0 | C-01 前预跑 rc=0，判据 B 通过（16 路径双向全等，清单多报 0、改动集未声明 0）；变体 `--baseline=3b34b81`（立项前稿口径）rc=1，未声明恰 1 项=spec.md（立项笔窗口内容，佐证 §4 口径事实登记第 1 条）；C-02 后终态复跑 rc=0（判据 B 16 路径双向全等，读数时点：C-02 后） |
| 4 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | 0 | 基线 20b82dd 树实测 SUM = **2278**（与 TASK-268 封盘 C-02 树终态位位相同，立项笔零 token 增量）；C-01 树（b9cbb6d）实测 SUM = **2278**（15 个改动文件 29-token 扫描零命中，逐位持平零减少，diff 全等）；C-02 树期望 SUM = **2307** = 2278 + 29（本行逐位表自枚举恰各 +1，C-02 后实测复核）。29 项逐位基线读数（20b82dd 树）：13.4=163、18.0=179、73.93=120、68.8=115、6315=95、1.8612=94、3.3066=94、5.7056=100、9.408=85、36525962432=78、36586847965=75、36438897772=77、36399582548=75、36098038547=73、2806=115、598=112、36736221648=72、36808102571=60、36821040708=54、36845152965=51、36871294588=49、36880083885=51、36958994260=50、36976873215=45、36992632143=42、36995450125=40、37008317295=39、37021305016=38、37591580687=37 |
| 5 | 词面门 | `git grep -n -I -iE -f .trae/tmp/task269/lex269.txt`（8 禁词模式文件法，三排除路径） | 1 | tracked 与 untracked 两形态 × 四 locale（default / C / zh_CN.UTF-8 / C.UTF-8）全 ZERO_HIT rc=1（读数时点：C-01 前工作树态，untracked 形态覆盖全部新增文件）；阳性探针 .probe269.txt（模式文件自拷贝自匹配）rc=0 恰命中后即删、复跑 rc=1 状态复原——词面机器活性坐实 |
| 6 | record 模块增量单测 | `mvn-verify.sh --mode=offline --pl record-service test` | 0 | BUILD SUCCESS，surefire **351** 测（基线 323 → 净增 +28：BadgeServiceTest 20 + BadgeControllerTest 7 + SqlMigrationContractTest 1→2），0 失败 0 错误 0 跳过（读数时点：C-01 前工作树态，首两轮因实施中自纠①②编译失败已披露，第三轮全绿） |
| 7 | diff 净与行尾 | `git diff 20b82dd --check` / 入库 blob CR 计数 | 0 | diff --check 输出空 rc=0（C-01 前工作树态 + C-01 提交区间 20b82dd..b9cbb6d 双实测）；C-01 十五文件入库 blob 逐字节 CR=0 纯 LF（tr 字节计数法；index.page.vue 工作树 CRLF 形态经 autocrlf 入库归一纯 LF，其余文件工作树即 LF）。读数时点：C-01 后 |

## 4. 条件项与实施要点（如实回填）

- **R1（数据库表与迁移脚本）**：`sql/02-record-db.sql` 末尾按任务书 DDL 逐字追加 user_badge 建表（+15 行）；新增 `sql/migrations/add-user-badge-table.sql`，首行即 `USE record_db;`（满足派发令「第一行」字面要求；既有 13 脚本为注释在前风格，此处为字面遵从而起的形态差异，幂等 DDL 与 migrate.sh 灌库语义不受影响），建表语句与主 DDL 逐字一致；`SqlMigrationContractTest` 迁移数断言 13→14，抽取器泛化表名参数（原 leaderboard 断言语义零变化），新增第 2 测断言 badge 迁移显式声明 USE record_db 且建表 DDL 与主 DDL 双写逐字一致。
- **R2（领域枚举与实体）**：`BadgeType` 声明 9 大成就（中文名/描述/分类/阶梯/图标/目标值六元组与任务书逐字一致），code 即枚举名与 badge_code 列值对齐，`targetValue` 以字符串构造 BigDecimal（规避 21.0975 类二进制浮点误差）；`UserBadge` 实体 @TableName("user_badge")（uk_user_badge 幂等锚点）；`BadgeDTO` 十二字段与派发令清单一致。
- **R3（服务层读时结算）**：`BadgeService.listMyBadges` 五查询读时结算——已解锁映射（user_badge 单查）→ 有效运动投影（status IN (PASSED, RE_PASSED) 单查，算单次跑步/骑行/夜间三维峰值）→ `SportRecordService.myStreak`（历史最长连续天数，成就语义追溯点亮）→ `SportRecordService.myStats`（passCount / totalDistance，两查询）——数据源与任务书 R3 指定逐字对应；满足即补插（unlocked_at=当前时间，is_equipped=0），`DuplicateKeyException` 捕获后重查库内行，幂等不外抛；达成判定统一 ≥ 目标值口径；夜间窗口按 anchor=startTime（null 回退 created_at，TASK-235 时间口径）小时数 ≥21 或 <5。`wearBadge` 单事务（@Transactional rollbackFor=Exception.class）两步 UPDATE「先清后置」保证同用户至多佩戴一枚；`unwearBadge` 幂等全量置 0；`getEquippedBadge` 纯只读（不触发结算写——动态流逐作者调用，写放大不可接受；进度字段按已解锁语义满格回填，实时进度以勋章墙为准）。
- **R4（控制器）**：任务书两组路由（/api/records/my/badges 三端点 + /api/records/users 外显端点）在单控制器内以类级前缀 /api/records + 方法级子路径统一承载（白名单仅一个控制器文件；四条 URL 与任务书逐字一致，与 RecordController 既有 /{id} 族段数互异零冲突）；身份头按派发令逐字 `@RequestHeader("X-User-Id")`（未复刻 RecordController 的 authEnabled 显式参数降级开关——徽章域无显式 userId 参数，缺头由公共层 MissingRequestHeaderException→400 承接）。
- **400 / BADGE_NOT_UNLOCKED 落地通道**：common 模块不在白名单、无法新增 ResultCode 枚举项；按公共层既有「业务侧主动校验失败 → 400」通道（GlobalExceptionHandler 对 IllegalArgumentException 的 400 映射）+ 控制器局部 handler（GoalController 同款先例）落地，异常消息携带 `BADGE_NOT_UNLOCKED` 标识串区分未解锁场景（控制器测试断言 400 + 标识串透传）。
- **R5（前端呈现）**：client.ts 增补 BadgeDTO 契约与四方法（getMyBadges / wearBadge / unwearBadge / getUserEquippedBadge）；index.page.vue 在个人统计卡下方新增「成就勋章墙」卡片（三列网格：已解锁琥珀高亮 + 佩戴中金标 + 佩戴/卸下交互，未解锁置灰 + 进度条 + 阶梯标签；loadBadges 并入 loadData 全量加载链，软错误告警口径与统计卡一致）；feed.page.vue 作者名后外显佩戴勋章标签（icon+名称，按当页 distinct 作者 Promise.allSettled 容错拉取，单作者失败仅该作者不外显）。
- **R6（测试守卫）**：BadgeServiceTest 20 测全量覆盖 9 徽章判定（FIRST_STEP/TEN_KM_RUN/HALF_MARATHON/FULL_MARATHON/STREAK_7_DAYS/STREAK_30_DAYS/HUNDRED_KM_CLUB/CYCLING_MASTER/NIGHT_RUNNER 含窗口含端点与 ≥ 边界）、追溯解锁、并发幂等（DuplicateKeyException 重查）、已解锁不重插、佩戴/卸下约束（未解锁拒、非法码拒、先清后置以 LambdaUpdateWrapper SQL 段断言）与外显只读（verifyNoInteractions 坐实零写）；BadgeControllerTest 7 测断言四端点委托、400 映射与外显空值语义；SqlMigrationContractTest 升 2 测。
- **偏差登记 1（白名单恰 14 +1 → 15 业务文件）**：新包 com.sportverify.record.enums 触发 record 静态门 JavadocPackage 活动闸门（TASK-194 曾以新建 8 个 package-info.java 源码修复清零、规则保留为闸门），白名单 14 项未含其 package-info.java —— 门禁优先于文件计数，新增第 16 文件 enums/package-info.java（4 行包文档），判据 B 以 16 路径双向全等闭环。
- **口径事实登记**：
  1. spec.md §4.3 行内记契约门基线 3b34b81 为立项前稿口径；立项笔 20b82dd 落库后派发令以 20b82dd 为开工基线（TASK-268 同款登记），契约门与 token 门三树均按 20b82dd 实测；
  2. token 门基线树（20b82dd）实测 SUM=2278：与 TASK-268 封盘 C-02 树终态 2278 位位相同（立项笔零 token 增量）；其 29 项逐位读数较 TASK-268 派发令基线表（0b928ab 树）恰各 +1，来源为其 handoff §3 逐位表自枚举（C-02 后已入库），非本任务改动所致；
  3. 词面门模式与 token 三树脚本沿 TASK-266/268 配方在 .trae/tmp/task269 重建（lex269.txt 与 lex268.txt 同 8 禁词模式，Write 产物 od 验 LF 尾行防末位 -E 备选静默失效），均不入库。
- **e2e 影响面登记（白名单外既有套件，如实披露）**：index 与 feed 两页新增挂载期请求（GET /record/api/records/my/badges、GET /record/api/records/users/{id}/equipped-badge），在 TASK-257 E14 的 e2e mock 体系（helpers.ts 两前缀兜底 404 前记入 ctx.unmocked，全部 e2e 断言 assertNoUnmocked）下将记为未映射请求——除 notifications.e2e（顶栏专属链路）外，feed.e2e.ts（访问 /feed）与访问首页的 delete-record / goal-card / filter-pagination / record-list / login-redirect（会话态用例） / record-submit / top-nav 各用例将各 +1 unmocked 转红。E2E 测试文件不在本任务白名单、本任务七门禁亦不含 E2E；修复只需 web 轮在 helpers.ts 的 handleRecord 增补两映射（按 stores 假数据返回），登记为后续任务条件项，本任务不越权改 e2e 件。vitest 63 测复测全绿不受影响（client.test.ts 40 测零改动通过）。
- **实施过程自纠披露（终态无遗留）**：① 首轮编译失败——漏建 UserBadgeMapper（白名单第 7 项），补建后编译绿；② BadgeControllerTest 首轮测试编译失败——对 void 方法误用 when().thenThrow()，改 doThrow().when() 惯用法后全绿；③ feed.page.vue 重写点赞批量函数时误将 item.recordId 笔误为 item.id（vue-tsc 捕获，对照 HEAD 恢复）——三处均为实施中自纠，最终门禁读数不含上述中间态。
- **部署注意**：live record_db 需先执行 sql/migrations/add-user-badge-table.sql（幂等可重复）方可上线本特性（TASK-260 live schema 滞后先例登记）；本任务未触碰本地/线上数据库。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；.trae/tmp/task269 临时件（词面门脚本与模式、token 三树脚本、commit msg 文件、门禁日志、探针残留）留于 gitignored 目录不入库。
- 未推送：`origin/main..HEAD` 收口后 = **3 笔**（开工前 1 笔未推送 + C-01 `b9cbb6d` + C-02 本笔）——推送由指导侧批次统一执行，本任务严格零推送。
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 16 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望 2307 一致。
