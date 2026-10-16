# TASK-259 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-17 执行子agent 实施批 G-1 全流程；门禁/证据/偏差回填如下，独立复核五块与指导侧终裁待行）

## 1. 开工基线

- 开工基线实测：`git rev-parse HEAD` = `b18c9fefe8c2c4111de204c9efd3b685542a795c`（= 派发口径 main @ b18c9fe 立项笔，短哈希 b18c9fe，下同）、`git status --porcelain` 为空。本档骨架预登记的 `50d1877` 为立项笔落库前的旧值，已按派发包口径以实测值回填订正。
- 任务书：`work/mailbox/tasks/TASK-259/spec.md`（白名单/修法 R1-R4/门禁/受限声明以 spec 为准）。

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only b18c9fe` 双向全等）

- 改动清单（5 项 = C-01 业务/compose/测试 3 + C-02 台账 2，逐行路径 token 供判据 B 机器比对）：
  - docker-compose.services.yml
  - record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
  - record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
  - work/mailbox/tasks/TASK-259/spec.md
  - work/mailbox/tasks/TASK-259/handoff.md
- 未跟踪文件核验（C-01 前 `git ls-files -o --exclude-standard`）：恰零项（.trae/tmp 临时件属 gitignore，不纳入比对）；白名单第 2 项（台账四件）中 PLAN/BACKLOG/HANDOVER 本笔零动，沿 TASK-257 先例由指导侧封盘笔滚动。
- spec §2 白名单双向全等自证：业务/测试/compose 3 文件逐项落改（R1-R4 全落点在其内），白名单外全仓零 diff（api 模块、五服务 src/test、pom.xml、package.json、sql/、scripts/、web/、docker-compose.yml、.github/、三看板其余内容均零触碰）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（经 D:\git\Git\usr\bin\bash.exe --login） | 0 | BUILD SUCCESS（08:43 min）；surefire **63/78/215/308/200/108/35**——基线 63/78/215/302/200/108/35 逐位持平，record 302→308 恰 +6（R4 五法六测，零删减零跳过） |
| 2 | compose config | `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q`（CI 权威口径，本地 .env 实值） | 0 | 编排解析通过；渲染面 `TZ: Asia/Shanghai` 恰 **8** 处 = 中间件 mysql/postgis 既有 2 + 本笔六服务新增 6（awk 按服务块归属实测：gateway/leaderboard/mapmatch/record/user/verify 六服务各恰 1）。spec §4 书写的单文件形态 `docker compose -f docker-compose.services.yml config` 在 overlay 设计下本就不可单文件解析（depends_on 中间件定义在基础文件；基线 b18c9fe 纯净 worktree 实测同样报 invalid compose project，非本笔引入）——沿 CI 步骤口径执行，偏差登记 §4 |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-259 --baseline=b18c9fe` | 0 | C-01 前工作树态（3 业务 + 2 台账已编辑）：判据 A 通过 + 判据 B 恰 5 路径 token 与实际改动集双向全等 rc=0；C-01 后态 rc=0（§6）；C-02 后终态复跑 rc=0（收官汇报） |
| 4 | token 门 29 项 | `git grep -cF` 逐项（清单沿 TASK-181 handoff §6 全 29 token，行命中累加口径沿 TASK-257） | 0 | SUM 基线 b18c9fe = **2017** vs 工作树 = **2017**，29 项逐位持平零减少；改动集 3 文件联合查询零命中（rc=1 通过态） |
| 5 | 词面门（模式文件法 + 探针阳性对照） | `git grep -n -I -iE -f <模式文件>`（CI 权威口径：8 禁词 + 三排除路径） | 1 | 权威口径 ZERO_HIT（rc=1 通过态；模式文件 .trae/tmp 经 tr -d '\r' + od 验 LF/UTF-8，防 CRLF 假阴性先例）；阳性探针（仓根 .txt 两禁词）`--untracked` rc=0 恰 2 行命中后即删——词面机器活性坐实 |
| 6 | checkstyle delta | `mvn-verify.sh --mode=offline --static=record-service`（权威形态）+ 纯净 worktree 基线对照 | 1（先例预期形态） | 改后 rc=1 恰 **4** 违规：`SportRecordService.java:[1059,5]` JavadocType 4 组件缺 @param（DerivedTrackMetrics，TASK-234 遗留）；基线 b18c9fe 纯净 worktree（.trae/tmp 独立 worktree，用后即清）同法实测恰 **4** 违规同集合于 `[1050,5]`——本笔文件有 diff 不能沿 TASK-254「零 diff⇒基线同值」论证，改以双向实测坐实：违规集合同构、行位移 +9 恰为 import/常量块插入量、本笔改动行 0 违规 ⇒ **delta=0**；checkstyle failOnViolation 于第 1 goal 中止故 spotbugs/pmd 未及（沿 TASK-254/255 record 静态门同形态，停手条件核验非七门禁） |
| 7 | diff 净与行尾 | `git diff --check` / `git ls-files --eol` | 0 | diff --check 净（C-01 前后均 rc=0）；C-01 无新文件；3 业务文件保持 w/crlf（blobs i/lf），台账 2 文件保持 w/lf；`git show --check` 见 §6 |

## 4. 判别式逐项实施登记与条件项（实施侧回填）

- **R1**（容器时区统一）：docker-compose.services.yml 六服务 environment 各注入注释 1 行 + `TZ: Asia/Shanghai` 1 行（共 +12 行），注释标注 TASK-259 口径：与 mysql/postgis 中间件 TZ 及 JDBC serverTimezone=Asia/Shanghai 对齐（东八墙钟；仅容器环境变量，本地/CI 零影响）。
- **R2**（推导显式改区）：`SportRecordService.java` 新增类内常量 `private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai")`（Javadoc 声明全链东八墙钟口径：容器 TZ/JDBC serverTimezone/前端目标用户），deriveTrackMetrics 两行 `ZoneOffset.UTC` → `BUSINESS_ZONE`（现 L1079-1080）；同步四处陈旧帧描述（DerivedTrackMetrics 记录 Javadoc「UTC 墙钟」→「东八墙钟」、deriveTrackMetrics Javadoc、submit 内联注释、validateTimeRationality Javadoc）。`ZoneOffset` import 随最后使用点消失移除。
- **R2 牵连带（本笔偏差 ①，红证明先行）**：spec R2「仅此一处生产代码改动」之外，`validateTimeRationality` 推导分支未来时间比对帧由 `LocalDateTime.now(ZoneOffset.UTC)` 同帧改为 `LocalDateTime.now(BUSINESS_ZONE)`——推导帧自洽是该 Javadoc 明文的配对语义（「轨迹推导值按 UTC 墙钟比对（推导帧自洽）」），R2 改推导落库帧后比对帧必须同帧，否则东八推导墙钟恒超前 UTC 比对墙钟 8h、**8 小时内结束的真实提交全部被误判「开始时间不能为未来时间」拒绝**（compose R1 注入 TZ 后的常态路径即触发）。红证明：R2 主体先行、L1107 未同步状态下跑 `--pl record-service test` → 恰 1 ERROR = `submit_justFinishedTrack_derivedStartNotRejectedAsFuture`（IllegalArgumentException「开始时间不能为未来时间」，栈落 validateTimeRationality，全量 110 测中其余 109 绿）→ 应用同步后同法复跑 rc=0 全绿（308 测）。红/绿双日志留证于 .trae/tmp（红 red-proof-r2only.log / 绿 green-r2full.log），收官清理。spec「其余 LocalDateTime.now() 落点经容器 TZ 统一后自动正确」的枚举未覆盖该显式 UTC now 点（基线 L1107，非裸 now()），登记为 spec 证据链遗漏的必要最小牵连带，非扩面。
- **R3**（既有断言适配）：SportRecordServiceTest 两断言期望值 `ZoneOffset.UTC` → `ZoneId.of("Asia/Shanghai")`（首末点 1_700_000_000_000L/1_700_000_001_000L 期望墙钟 2023-11-15T06:13:20/06:13:21，较修前恰 +8h 定量对齐），断言消息「UTC 墙钟」→「东八墙钟，TASK-259 口径」同步；同 instant 不同墙钟帧的期望演进，断言强度不减（非删弱）。
- **R4**（边界用例 5 法 6 测，全部固定 Instant + 显式 ZoneId 构造，与 runner 时区无关，落点 SportRecordServiceTest.java 白名单内「时区语义边界（TASK-259）」小节）：
  1. `submit_tzCrossDayBoundary_utcPrevDay1600DerivesEastEightMidnight`——跨日提交：2026-03-14T16:00:00Z（= 东八 03-15 00:00）→ start_time 归东八当日 00:00（修前 UTC 墙钟归 03-14 16:00）；
  2. `submit_tzBoundary2359SameDay_plus2minNextDay`——东八 23:59 边界：2026-03-15T15:59Z 归当天 03-15 23:59、+2 分钟（16:01Z）归次日 03-16 00:01（双提交 × times(2) captor）；
  3. `submit_tzMorningRun0700_derivesEastEightSameDay`——晨跑归日：2026-03-15T23:00Z（= 东八 03-16 07:00）→ 归东八当日 07:00（修前形态 03-15 23:00 会致 myStreak 误断签）；
  4. `myStreak_eastEightMorningAnchors_attributesSameDay`——R4-3 的 myStreak 消费链伴言：东八晨跑锚与同日下午场同日去重 longest=1（修前 UTC 墙钟锚会把晨跑归前一日构成 2 天假连击）；固定日期构造与 runner 无关（current 随 runner today 变化故不断言）；
  5. `submit_tzWeekAndMonthBoundary_derivesNewWindowDay`——周/月窗口对齐（记录侧供数）：2026-03-15T16:00Z（= 东八周一 00:00）归新周首日（DayOfWeek.MONDAY）、2026-03-31T16:00Z（= 东八月初 00:00）归新月首日（dayOfMonth=1）；GoalService 周期窗口/周期榜按 COALESCE(start_time, created_at) 于东八日界过滤，记录侧供数在周界/月界瞬间的归属正确是窗口对齐前提（窗口界 LocalDate.now() 经 R1 容器 TZ 统一为东八墙钟，其消费侧不在本笔白名单，测试断言钉住供数侧）；
  6. `submit_justFinishedTrack_derivedStartNotRejectedAsFuture`——推导帧自洽回归守卫（偏差 ① 的常驻判别式）：约 30 分钟前结束的轨迹推导提交不被误判未来时间（R2-only 形态下恒红，见上）。
- **条件项**：compose config 以 CI 两文件口径执行（§3 门禁 2 行偏差说明）；词面门模式文件/探针/token 清单等 scratch 均落 .trae/tmp 且用后清理；checkstyle 基线对照以 .trae/tmp 独立 worktree 承载（b18c9fe 纯净树），用后即删不残留。

## 5. 受限与偏差声明（对照 spec §5 四项预登记，如实回报）

1. （spec §5.1）真实容器 JVM `user.timezone` 运行态读数未实测——TZ 环境变量→JVM 默认时区为 JDK 标准行为 + temurin ubuntu 基础镜像含 tzdata，以 compose config 配置层断言承载（渲染面六服务 TZ 落位为配置层证据）。
2. （spec §5.2）存量数据不迁移——compose 形态存量 UTC 墙钟与新东八墙钟混存（演示库可接受）；本笔 `--mode=offline` 不涉运行态数据库。
3. （spec §5.3）非东八浏览器用户显示错位——前端不在本批白名单，目标用户东八，登记已知边界。
4. （spec §5.4）Nacos 运行时配置未核（沿 G 系侦察口径）。
5. （本笔偏差 ①）R2 牵连带 validateTimeRationality 比对帧同步——详见 §4，红→绿证据链完整，登记为必要最小牵连带待指导侧追认。
6. （本笔偏差 ②）compose config 执行口径：spec §4 书写单文件形态 → 实际按 CI 权威两文件口径执行并附基线纯净树同败证据（overlay 设计既有行为，非本笔引入）。
7. （本笔受限）record 静态门 spotbugs/pmd 未及执行（checkstyle failOnViolation 于第 1 goal 中止，沿 TASK-254/255 同形态先例）；gateway/verify 等模块未声明静态三件套插件（pom 零 diff）不涉。

## 6. 提交与哈希（实施侧回填）

- C-01（实施）：`48a0a45c5bd01663842b3fac3a530f3ab6b74bc4`（短哈希 48a0a45）— `fix(record): G1时区语义全链统一——容器TZ注入+推导改区东八墙钟（TASK-259）`，恰 3 文件 +243/−14（compose 六服务 TZ / 主类 R2+牵连带 / 测试 R3+R4 五法六测），消息经 .trae/tmp/msg-c01.txt -F 落库（中文命令行 127 先例），`git show --check` rc=0，偏差 ①② 全文随提交体登记。
- C-02（台账）：提交时自指哈希不可知，沿 git log 后补先例由封盘笔/终验笔落库（本笔仅 spec.md 状态行 + handoff.md 回填）。

## 7. 收口自证（实施侧回填）

- C-01 后态：契约门复跑 rc=0（判据 B 恰 5 token 全等）；`git show --check C-01` 净。
- 完成 C-02 后 `git status --porcelain` 为空（原文见收官汇报）；.trae/tmp 临时件（tokens259.txt / lex-pattern259*.txt / 各日志 / compose-config-rendered.yml / msg.txt / 纯净 worktree）用后清理。
