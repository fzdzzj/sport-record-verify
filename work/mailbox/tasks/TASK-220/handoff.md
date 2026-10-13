# TASK-220 handoff：连续打卡聚合端点与个人仪表盘卡片（P1，MEDIUM 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-220`（提案 `spec/changes/add-streak/proposal.md`）。
- 分级：**MEDIUM**（标准流程：后端只读聚合端点 + 前端卡片，零安全语义变更——归属姿态与既有 GET /my 完全一致）。指导侧亲核 + CI 外部终验，免独立复核。
- 开工基线：`db1542d`（派发笔，含提案三件套与任务书）；开工前 `git status` 干净、本地领先 origin 2 笔、未推送。
- 离线基线（任务书 §6）：698 = 52/50/165/166/159/80/26。
- C-01 实施笔：`8853079`（`feat(record): 连续打卡聚合端点与个人仪表盘卡片（TASK-220）`，恰 10 文件，+357/−2）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-178/183/204/205/213/214/215/216/217/218/219 先例，实际哈希以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；CI 外部终验待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 db1542d，恰 14 文件 = C-01 10 + C-02 4）

C-01（feat(record) 实施笔，10 文件，白名单全等）：
- api/src/main/java/com/sportverify/api/record/dto/MyStreakDTO.java（新，current/longest，javadoc 载明 PASSED/RE_PASSED 终态语义）
- record-service/src/main/java/com/sportverify/record/service/StreakCalculator.java（新，纯静态计算器）
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java（myStreak 聚合方法）
- record-service/src/main/java/com/sportverify/record/controller/RecordController.java（GET /my/streak）
- record-service/src/test/java/com/sportverify/record/service/StreakCalculatorTest.java（新，7 场景）
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java（myStreak 增补）
- record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java（/my/streak 四测增补）
- web/src/api/client.ts（MyStreakDTO + getMyStreak）
- web/src/pages/index.page.vue（统计卡区「连续打卡」卡）
- web/README.md（一句登记）

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，4 文件）：
- spec/changes/add-streak/tasks.json（勾选）
- work/mailbox/tasks/TASK-220/spec.md（§7 回填）
- work/mailbox/tasks/TASK-220/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

> 下列载体零触碰（G2 证据见 §5）：sql 迁移/索引、pom/compose/ci.yml/mvn-verify.sh/mailbox-contract.sh/scripts/、web/package.json/web/vite.config.ts/web/src/typed-router.d.ts、web/src 其余文件、index/状态机/批大小/周期/MQ 参数。词面门/探针/token 模式文件均落 gitignored `.trae/tmp`、不入库。

## 3. 一句话结论

在既有 GET /api/records/my 之上新增只读聚合端点 GET /api/records/my/streak（归属/包装/错误语义与 /my 全同）：service 层以 MyBatis-Plus `selectList` 单列投影（仅取 createdAt）按归属人过滤、status 限定通过终态集 {PASSED, RE_PASSED}，映射自然日去重后交由新纯静态类 StreakCalculator 计算当前/最长连续天数；web 仪表盘统计卡区新增「连续打卡」卡（当前 N 天 · 最长 M 天）。零新表、零新 SQL 形态、零新依赖、零状态机/MQ/批参改动；同日多条只算一天，今日无记录从昨日续算（连击活到当日结束）。

## 4. 判别式红绿（R1a-b / R2 / R3 / R4 / R5 / G1-G3 逐条）

- **R1a（绿）MyStreakDTO 语义**：`@Data` 双字段 Integer current/longest，javadoc 载明有效打卡 = PASSED/RE_PASSED 终态、同一天多条算一天、current 今日无记录时从昨日续算（与任务书预注册一致）。
- **R1b（绿）StreakCalculator 7 场景**：final 类 + 私有构造 + `compute(List<LocalDate>, LocalDate)` 静态；空/null → {0,0}；单日 today → {1,1}；单日 yesterday → {1,1}；today 缺 + yesterday 在 → 从昨日续算（昨+前日 → {2,2}）；断档止步（{2,2} 不停跨空日）；乱序+重复容忍（内部 HashSet 归一 → {3,3}）；longest 跨断档取历史最大段（{1,4}）。7 测全绿（offline 实证 StreakCalculatorTest Tests run: 7, Failures: 0）。
- **R2（绿）service 层 myStreak**：captor 断言 status 过滤参数值集**恰** {200L, PASSED(2), RE_PASSED(5)}（精确集合断言，非通过终态值不得混入）+ SQL 段含 `user_id =` 与 `IN`；createdAt 映射 `toLocalDate()` 后 `distinct`（同日两条只算一天）；today = `LocalDate.now()`（服务器时钟）；输出由真实计算器对固定日期（相对 today）计算 current=3/longest=3。
- **R3（绿）controller /my/streak**：紧邻 /my 放置，归属取值（auth 开 X-User-Id / 关显式 userId）、Result.success 包装、缺头 1001 与缺参 400 错误语义与 /my 全同，纯委托；四测（auth 开头注入绿 / 缺头红 / 缺参红 / auth 关显式 userId 绿）全绿。
- **R4（绿）web 卡片**：统计卡区 grid 3→4 列新增「连续打卡」卡，渲染 `当前 N 天 · 最长 M 天`（未加载/失败显示 —）；独立拉取不拖垮统计卡区；验证由 type-check rc=0 + build 成功 + 代码审查承载；**真实浏览器交互不跑，登记未覆盖**（沿 TASK-213/218/219 口径）。
- **R5（绿）web 封装**：client.ts 新增 `MyStreakDTO` 接口与 `getMyStreak()`（GET /record/api/records/my/streak，沿 getMyRecords 范式含类型）；README 一句登记。
- **G1（绿）**：offline 全量 BUILD SUCCESS rc=0，七模块逐位 **52/50/165/178/159/80/26**——record 166→178 恰 +12（StreakCalculatorTest 7 + SportRecordServiceTest 1 + RecordControllerTest 4），其余六模块逐位不变。
- **G2（绿）**：C-01 后 `git diff --name-only db1542d` + untracked 恰白名单 10 文件；C-02 后恰 14 文件（= 白名单 10 + 台账 4）；越界面（sql/pom/compose/ci.yml/两脚本/scripts/web 其余）diff 全空。
- **G3（绿）**：web 门禁 pnpm test rc=0（4 套 24 测不变）、type-check rc=0、build 成功（3283 modules）、typed-router diff 干净；契约门双态 rc=0（在途 `--open TASK-220 --baseline=db1542d` + 收口无参）；token 29 项两点 2034/2034 只增不减；词面门改动集 CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0；`git diff --check` rc=0；新文件纯 LF + 末尾换行、无 BOM，改文件保持基线行尾。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS**（Total time 14:16 min，`OFFLINE_RC=0`），逐位 **52/50/165/178/159/80/26**（common 52 / gateway 50 / user 165 / **record 178（基线 166 +12）** / verify 159 / leaderboard 80 / mapmatch 26，每模块 Failures/Errors/Skipped 全 0）。log 无任何非零 Failures/Errors/Skipped 行（`Failures: [1-9]|Errors: [1-9]|Skipped: [1-9]` 零命中）；StreakCalculatorTest 7 测实证通过。rc=0，非 3。
2. **web 本机四件套**：`pnpm test` rc=0（vitest 3.2.7，**4 套 24 测不变** = token 6 + gpx 5 + client 7 + pace 6；本课题无新 web 纯函数，按任务书 web 测试可选）；TMP/TEMP/TMPDIR 重定向 `.trae/tmp` 防 esbuild access denied；`pnpm type-check` rc=0；`pnpm build` 成功（3283 modules，38.53s）；`git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零漂移）。
3. **契约门双态**：在途 `--open TASK-220 --baseline=db1542d` **rc=0**（判据 A：TASK-220 仅 spec 声明进行中放行，共 1 个待办；其余历史任务「已收口不重审」；判据 B 清单一致）；收口无参 **rc=0**（见 §9）。
4. **token 29 项**：两点时点读数见 §6，SUM=**2034**（只增不减）。
5. **词面门 CI-exact**：显式 ERE 口径（`git grep -n -I -iE -f <模式文件>`，`-E` 显式；模式经 UTF-8 无 BOM 文件注入 .trae/tmp 以规避命令行中文）对 C-01 改动文件集（10 文件）**ZERO_HIT rc=1**；合成探针（gitignored 探针文件含禁用措辞）`git grep --no-index -n -I -iE -f`（同模式文件）**HIT rc=0**（命中探针行），证明 `-i -E` 口径有效、无假阴性。C-02 后对含台账四文件的改动集复扫（见 §9）仍 ZERO_HIT。模式字面量与探针均落 gitignored `.trae/tmp`、不入库（沿 TASK-184 F1 教训，本报告不落正则字面量）。
6. **`git diff --check`** rc=0（工作树与暂存区双查；`LF will be replaced by CRLF` 为 `core.autocrlf=true` 信息性告警，非空白错误）。
7. **行尾**：三新文件（MyStreakDTO.java / StreakCalculator.java / StreakCalculatorTest.java / handoff.md）字节级校验纯 LF（CR=0）+ 末尾换行（末字节 0A）+ 无 BOM；改文件保持基线行尾（CRLF 组：SportRecordService.java 542 CRLF、RecordController.java 187 CRLF、SportRecordServiceTest.java 1013 CRLF；LF 组：RecordControllerTest.java 保持基线无末尾换行、client.ts/index.page.vue/README.md 纯 LF）。
8. **只改清单全等**：`git diff --name-only db1542d` + untracked 对照恰 §2 的 14 文件；越界面 diff 全空（G2）。
9. **凭证自检**：改动集为只读聚合端点/纯计算器/测试/前端卡与台账文案，零真实凭证字面量、零受保护 token 字面量（29 项逐项 `git grep -cF` 复测 SUM 持平）、零新正则字面量（沿 TASK-188 N1 教训，字面量不枚举入本报告与任何 tracked 新文档）。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和，rev-aware 基线以 `git grep -cF <token> db1542d` 对基线提交计）：开工（`db1542d` 基线）= **2034**、post-C01（C-01 提交后工作树）= **2034**、收口（C-02 后工作树）= **2034**（只增不减）。本课题改动集（10 实施文件 + 4 台账文件）全部内容零真实凭证 / 零受保护 token 字面量（§5 第 9 条自检佐证）。**口径说明**：29 项权威清单由指导侧持有复测（沿 TASK-213/215/217/218/219 同口径）；本报告不枚举字面量以避免全仓计数自增失准（沿 TASK-188 N1 教训）。

## 7. 实现要点

- **MyStreakDTO**：`com.sportverify.api.record.dto` 包，`@Data implements Serializable`，Integer current/longest；javadoc 明确「仅计入 PASSED/RE_PASSED 通过终态、同日多条算一天、今日无记录从昨日续算」语义（契约即文档）。
- **StreakCalculator**：final 类 + 私有构造 + `public static int[] compute(List<LocalDate>, LocalDate)`；null/空 → {0,0}；内部 `HashSet` 归一容忍乱序与重复；current 锚点 = 集合含 today 取 today、否则含 yesterday 取 yesterday（连击活到当日结束）、否则 0，锚点起往回连续计数；longest 对归一集合排序后扫最大连续段。
- **service myStreak**：`LambdaQueryWrapper.select(SportRecord::getCreatedAt)` 单列投影 + `eq(userId)` + `in(status, List.of(PASSED, RE_PASSED))`（in(Collection) 重载）→ selectList → `map(toLocalDate)` → `distinct` → `StreakCalculator.compute(dates, LocalDate.now())` → DTO。无分页（个人量级 + 单小列），归属过滤即身份（构造上无越权）。
- **controller /my/streak**：紧邻 /my 放置（字面段优先，无 `/{id}` 歧义）；`@GetMapping("/my/streak")`；auth 开 `resolveUserId(request, null)` / 关显式 `userId` 查询参数、缺头 1001 / 缺参 400——与 myRecords 逐字同构，纯委托。
- **web**：client.ts 新增 `MyStreakDTO { current, longest }` 与 `getMyStreak()`；index.page.vue 统计卡 grid 3→4 列加第四卡（`当前 N 天 · 最长 M 天`，text-lg leading-snug 防溢出），`streak` 独立 ref + computed，onMounted 中 records 加载后独立 try/catch 拉取（失败显示 —，不拖垮统计区）；README 追加「连续打卡（TASK-220）」一节一句。
- **零依赖确认**：api/record-service/web 均零新 Maven/npm 依赖；零新表零新 SQL 形态（仅 MP selectList 单列投影）；web/package.json、pnpm-lock、vite.config.ts 未动。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 与 spec §7 内以「本 handoff 所在提交」指称 C-02，实际哈希以 `git log --oneline` 为准（沿先例）。
- **D2（词面门/探针/token 模式文件）**：中文模式与 29 项字面量经 gitignored `.trae/tmp` 文件注入（`[IO.File]::WriteAllText` UTF-8 无 BOM），不入库、不落 tracked 文档；命令行参数零中文（exit 127 规避）。
- **D3（offline 日志噪音）**：offline 模式 Nacos 连接失败日志（Client not connected / HikariPool 超时 / mapmatch 注册失败）属既有测试环境噪音（服务未起），对七模块计数零影响，`Tests run` 汇总逐位如 §5.1。
- **D4（web build chunk 体积告警）**：`vite build` 输出「chunks larger than 500 kB」为既有非阻断 advisory（入口 JS 1.6MB 系基线量级，与本课题正交），build rc=0。
- **停止条件（§3）**：无一按字面触发——§3.1 GET /my 头基归属姿态与预注册一致（X-User-Id 经 ADR-0007，未静默适配）；§3.2 无任何改动溢出白名单（sql/pom/compose/ci.yml/两脚本/scripts/web 其余零触碰）；§3.3 首轮 offline 全绿（含 MP `in(Collection)` 参数值集精确断言），无计算器语义歧义需停止上报。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2034**（见 §6 口径）。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT rc=1 + 探针阳性对照 HIT rc=0（已留证）；`git diff --check` rc=0；工作树 `git status` 干净（`.trae/tmp` 临时件 gitignored，无 override）。
- `git diff --name-only db1542d` + untracked 对照恰 **14 文件**（C-01 10 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程未跑（未 push、未建 PR）。
- **真实浏览器「连续打卡」卡渲染与交互未跑**（沿 TASK-213/218/219 口径登记未覆盖）：R4 验证由 type-check + build + 代码审查承载；不得据此推断卡片在真实浏览器端到端可用。
- 未做连续打卡口径与第三方健身 App（Keep/Strava 等）数值对照验证（仅按任务书预注册口径实现）。
- 未做 >500 条记录的部分统计对 streak 的影响声明（streak 端点独立于 size=500 全量拉取，取全部通过记录聚合，无此限制——此处仅为说明，非声称）。
- 本机环境为本地处置（D2 临时件），未入库，不影响对外交付物。
