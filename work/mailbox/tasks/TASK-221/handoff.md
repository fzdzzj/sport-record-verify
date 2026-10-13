# TASK-221 handoff：记录可见性三态与读取面访问门（P3，FULL 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-221`（提案 `spec/changes/add-record-visibility/proposal.md`）。
- 分级：**FULL**（schema + 跨服务好友判断 + 读取面逐记录授权语义；独立 review 复核适用）。
- 开工基线：`1ee1cdc`（派发笔，含提案三件套与任务书）；开工前 `git status` 干净、本地领先 origin 2 笔、未推送。
- 离线基线（任务书 §6）：52/50/165/178/159/80/26（=710；record=178，user=165）。
- C-01 实施笔：`ff80635`（`feat(record): 记录可见性三态与读取面访问门（TASK-221）`，恰 21 文件，+666/−23）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI）待指导侧推动推送后补记。

## 2. 只改清单（相对开工基线 1ee1cdc，恰 25 文件 = C-01 21 + C-02 4）

C-01（feat(record) 实施笔，21 文件 = S4 白名单全等）：
- sql/migrations/add-record-visibility.sql（新，幂等迁移：information_schema COUNT + PREPARE 模式，沿用户角色迁移同款幂等先例）
- sql/02-record-db.sql（sport_record 建表仅加 `visibility` 列，位置在 status 之后、注释与迁移逐字一致——dual-write 新装路径）
- api/src/main/java/com/sportverify/api/record/RecordVisibility.java（新，PUBLIC(0)/FRIENDS(1)/PRIVATE(2)，javadoc 载门语义）
- api/src/main/java/com/sportverify/api/record/dto/RecordSubmitDTO.java（+ visibility，@Min(0)@Max(2) 可选）
- api/src/main/java/com/sportverify/api/record/dto/RecordSummaryDTO.java（+ visibility）
- api/src/main/java/com/sportverify/api/user/UserApi.java（+ areFriends）
- api/src/main/java/com/sportverify/api/user/UserApiFallback.java（+ areFriends → false，失败安全隐私）
- user-service/src/main/java/com/sportverify/user/service/FriendService.java（+ areFriends：null→false、相同→true、归一 selectCount>0）
- user-service/src/main/java/com/sportverify/user/controller/InternalFriendController.java（+ GET /internal/friends/exists，纯委托）
- user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java（+ 5 测）
- record-service/src/main/java/com/sportverify/record/entity/SportRecord.java（+ visibility 字段）
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java（submit resolveVisibility + assertCanView 门 + getVerifyResult(caller) + toSummary 带 visibility）
- record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java（like/unlike/getLike 复用 assertCanView，setter 注入协作器）
- record-service/src/main/java/com/sportverify/record/controller/RecordController.java（verifyResult 加 caller 解析）
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java（getVerifyResult 适配 + R2/R3/R4 门/提交流可见性测试增补）
- record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java（R5 门测试 + mock 协作器注入）
- record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java（verifyResult caller 适配 + 门测试增补）
- web/src/api/client.ts（RecordSubmitDTO/RecordSummaryDTO + visibility?: number）
- web/src/pages/record-submit.page.vue（可见性三选 radio，默认公开 0）
- web/src/pages/index.page.vue（我的记录列表可见性徽标：仅自己/好友可见 tag，公开显示「公开」）
- web/README.md（一句登记）

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，4 文件）：
- spec/changes/add-record-visibility/tasks.json（勾选）
- work/mailbox/tasks/TASK-221/spec.md（§7 回填）
- work/mailbox/tasks/TASK-221/handoff.md（本新文件）
- work/mailbox/PLAN.md（纯追加）

> 下列载体零触碰（G2 证据见 §5）：index/状态机/批大小/周期/MQ 参数、RecordEvent DTO、leaderboard-service、gateway-service、ci.yml、docker-compose*、scripts/、web/package.json、web/vite.config.ts、web/src/typed-router.d.ts（路由零新增）、既有 sql/migrations/*、sql/01,03,04,05。词面门/探针/token 模式/迁移验证脚本均落 gitignored `.trae/tmp`、不入库。

## 3. 一句话结论

给每条运动记录引入三态可见性（PUBLIC=0 公开/默认、FRIENDS=1 好友可见、PRIVATE=2 仅自己）：`sport_record` 加 `visibility` 列（幂等迁移 + 02 建表双写，存量行 DEFAULT 0 回填公开向后兼容），新增 `RecordVisibility` 枚举与 `UserApi.areFriends` + user-service `FriendService.areFriends` + `InternalFriendController /internal/friends/exists` 逐对好友判断；核心在读取面收敛 `SportRecordService.assertCanView(record, caller)` 门并应用到 pagePoints、getVerifyResult（新增 caller 参数，关闭「任何人读任意判定」开放暴露）与点赞三端点（like/unlike/getLike 同矩阵），提交路径 resolveVisibility（缺省 PUBLIC、枚举外拒绝）、RecordSummaryDTO 带可见性供前端徽标。**owner×三态均放行、stranger×PUBLIC 放行、friend×FRIENDS 放行、stranger×FRIENDS 403、非 owner×PRIVATE 403、caller=null 降级态放行、UserApi 软降级 false→403（失败安全隐私）** = 13 组合门矩阵；生产严格 auth.enabled=true（TASK-191 严格链接），降级态仅本地基准可达。

## 4. 判别式红绿（R1-R6 / G1-G3 逐条）

- **R1（绿）枚举+schema**：`RecordVisibility` codes 0/1/2 黄金值（`@Getter`，SportType/RecordStatus 先例形式，javadoc 载全门语义）；迁移在 scratch 库首跑 ALTER、**二跑命中跳过分支**（`sport_record.visibility 已存在，跳过`）证明幂等；`02-record-db.sql` diff 恰 = sport_record 建表内一行 `visibility` 列（其余 DDL 逐字节不变）。
- **R2（绿）门矩阵 13 组合**（SportRecordServiceTest + RecordLikeServiceTest 实证）：owner×{PUBLIC(0),FRIENDS(1),PRIVATE(2)} 放行；stranger(999)×PUBLIC 放行（含 null 存量行）；friend×FRIENDS 放行（userApi.areFriends(owner,caller)=true）；stranger×FRIENDS 403；非 owner×PRIVATE 403；caller=null 降级放行（含 PRIVATE）；userApi 抛异常/软降级 false → 403（fail-safe）。组内 `verifyNoInteractions(userApi)`/`verify(userApi).areFriends(owner,caller)` 判别式守卫短路与好友调用。
- **R3（绿）verify-result 越权收口**：非归属人读 PRIVATE 判定 → HTTP 403 body 1002（controller 设状态 403、service FORBIDDEN）；owner → 200 透传 verify-api 结果；caller=null（auth 关降级）→ 行为与今日逐字节一致（透传）。`RecordController.verifyResult` 在 auth 开时以 X-User-Id 解析 caller、缺头 1001。
- **R4（绿）提交路径**：null visibility → 落库 0（PUBLIC）；显式 1/2 → 原样落库；越界 99 → 服务层 3007 拒绝不落库（DTO @Min/@Max 拦控制器 400，双保险）。
- **R5（绿）点赞三端点同矩阵**：like/unlike/getLike 都在记录查找后过 `assertCanView(record, actingUser)`；PRIVATE 陌生人 → 403 且零 Redis 写/取消/计数；FRIENDS 好友 → 200 照常；门由注入的 SportRecordService mock 承载，与 pagePoints/getVerifyResult 测试等价。
- **R6（绿）web**：提交页三选 radio 默认 0（公开）并随 `visibility` 入提交载荷；我的记录列表新增可见性列——1/2 渲染彩色 tag、0 显示「公开」；`client.ts` 两 DTO 类型补 `visibility?: number`；验证由 type-check rc=0 + build 成功 + 代码审查承载；**真实浏览器交互不跑，登记未覆盖**（沿 TASK-213/220 口径）。
- **G1（绿）**：offline 全量 BUILD SUCCESS rc=0，七模块逐位 **52/50/170/196/159/80/26**——record 178→196 恰净增 18、user 165→170 恰净增 5，其余五模块逐位不变；全 log 零 非零 Failures/Errors/Skipped。
- **G2（绿）**：C-01 后 `git diff --name-only 1ee1cdc` + untracked 恰白名单 21 文件（19 M + 2 ??）；越界面（index/状态机/批参/MQ/RecordEvent DTO/leaderboard/gateway/ci.yml/compose/scripts/sql 既有）diff 全空。
- **G3（绿）**：record-service 静态 rc=0（0 Checkstyle violations / SpotBugs / PMD 通过——唯一声明并强制三件套的改动模块）；web pnpm test rc=0（4 套 24 测不变）、type-check rc=0、build 成功（3283 modules）、typed-router diff 干净；契约门双态 rc=0（在途 `--open TASK-221 --baseline=1ee1cdc` + 收口无参）；token 29 项两点 2036/2036 只增不减且 ≥2034；词面门改动集 CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；新文件纯 LF + 末尾换行、无 BOM，改文件保持基线行尾。

## 5. §6 门禁留证

1. **offline 全量**：唯一入口 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh --mode=offline test` → **BUILD SUCCESS**（Total time 13:16 min，`OFFLINE_RC=0`），逐位 **52/50/170/196/159/80/26**（common 52 / gateway 50 / user 170（基线 165 +5）/ record 196（基线 178 +18）/ verify 159 / leaderboard 80 / mapmatch 26，每模块 Failures/Errors/Skipped 全 0）。log 零 任何 `Failures: [1-9]|Errors: [1-9]|Skipped: [1-9]` 行。rc=0，非 3。
2. **静态**：record-service（唯一改动且声明 checkstyle/spotbugs/pmd 的模块）`--static=record-service` rc=0，`0 Checkstyle violations`，SpotBugs Analysis 完成、PMD BUILD SUCCESS。`--static=user-service`/`--static=api` 非本仓声明插件模块——用通用 checkstyle 配置实测存在**基线违规**（见 §8 D3，非本课题新增、不属收口门）。
3. **web 本机四件套**：`pnpm test` rc=0（vitest 3.2.7，4 套 24 测不变）；TMP/TEMP/TMPDIR 重定向 `.trae/tmp` 防 esbuild access denied；`pnpm type-check` rc=0（vue-tsc --noEmit）；`pnpm build` 成功（3283 modules，22.11s）；`git diff --exit-code -- web/src/typed-router.d.ts` rc=0（零漂移）。
4. **契约门双态**：在途 `--open TASK-221 --baseline=1ee1cdc` **实测 rc=0**（判据 A 两件套齐；判据 B 实质核：工作树 25 文件改动集 与 §2 只改清单逐项相等，零清单多报、零改动未声明，非待办放行/收口捷径——重建修复后复跑读数）；收口无参 **rc=0**（见 §9）。
5. **token 29 项**：两点时点 2036/2036（见 §6），SUM **只增不减且 ≥2034**。
6. **词面门 CI-exact**：显式 ERE 口径（`git grep -n -I -iE -f <模式文件>`，`-E` 显式；模式经 UTF-8 无 BOM 字节级拷贝注入 .trae/tmp 以规避命令行中文）对 C-01 改动文件集（21 文件）**ZERO_HIT rc=1**；合成探针（gitignored 探针文件含禁用措辞）同模式 **HIT rc=0**（命中探针行），证明 `-i -E` 口径有效、无假阴性。C-02 后对含台账四文件的改动集复扫（见 §9）仍 ZERO_HIT。模式字面量与探针均落 gitignored `.trae/tmp`、不入库（沿 TASK-184 F1 教训，本报告不落正则字面量）。
7. **`git diff --check`** rc=0（工作树与暂存区双查；`LF will be replaced by CRLF` 为 `core.autocrlf=true` 信息性告警，非空白错误）。
8. **行尾**：两新文件（RecordVisibility.java、add-record-visibility.sql）字节级校验纯 LF（CR=0）+ 末尾换行（末字节 0A）+ 无 BOM；改文件保持基线行尾。
9. **迁移幂等二跑证明**：scratch 实例 `task131-scratch-mysql`（mysql:8.0）内建独立 `record_db` 预迁移 schema（含 1 条遗留行 legacy-1）→ 首跑 `add-record-visibility.sql` ALTER 成功、`sport_record.visibility` COUNT=1、遗留行回填 `visibility=0`（PUBLIC）→ **二跑输出 `sport_record.visibility 已存在，跳过`**（命中跳过分支）、列仍 COUNT=1、行数稳定 1（幂等）。scratch 库验证后容器恢复退出态，未触碰生产/持久数据。
10. **只改清单全等**：`git diff --name-only 1ee1cdc` + untracked 对照恰 §2 的 25 文件（C-01 21 + C-02 4）；越界面 diff 全空（G2）。
11. **凭证自检**：改动集为授权门/枚举/迁移/测试/前端表单徽标/台账文案，零真实凭证字面量、零受保护 token 字面量（29 项逐项 `git grep -cF` 复测 SUM 持平 2036）、零新正则字面量（沿 TASK-188 N1 教训，字面量不枚举入本报告与任何 tracked 新文档）。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和，rev-aware 基线以 `git grep -cF <token> 1ee1cdc` 对基线提交计）：开工（1ee1cdc 基线）= **2036**、post-C01（C-01 提交后工作树）= **2036**、收口预检（C-02 前 TASK-219 同源 29 项清单）= **2036**（只增不减，且 ≥ 任务书 2034 基线）。本课题改动集（21 实施文件 + 4 台账文件）全部内容零真实凭证 / 零受保护 token 字面量。**口径说明**：29 项权威清单由指导侧持有复测（沿 TASK-213/215/217/218/219/220 同口径）；本报告沿用 .trae/tmp/tokenlist_T219.txt（29 行）复算，不枚举字面量以避免全仓计数自增失准（沿 TASK-188 N1 教训）。

## 7. 实现要点

- **api RecordVisibility**：枚举常量 {PUBLIC(0)/FRIENDS(1)/PRIVATE(2)} + `fromCode(Integer)`（null→null，未知→null），javadoc 载 13 组合门矩阵（契约即文档）。无新依赖。
- **RecordSubmitDTO**：+ `Integer visibility` 可选，`@Min(0)@Max(2)` 消息「可见性非法」（越界控制器 400）；缺省由服务层回退 PUBLIC。
- **UserApi.areFriends**：`@GetMapping("/friends/exists")` 两参 `@RequestParam`；`UserApiFallback` 软降级返回 `Result.success(false)`（失败安全隐私，注释载「非好友 = 403，绝不允许软成功 true 泄露」，与好友榜空榜同口径）。
- **user-service**：`FriendService.areFriends` — null→false、相同→true、归一 user_low<user_high `selectCount>0`（共享既有 normalized 查询）；`InternalFriendController GET /internal/friends/exists` 纯委托。
- **record-service**：`SportRecord.visibility` 字段；`submit` 前 `resolveVisibility(dto.getVisibility())`（null→PUBLIC，枚举外→3007，resolveSportType 先例）；`assertCanView(record, caller)` 门（见 §3 矩阵）public（供 RecordLikeService 复用）；`pagePoints` 以 assertCanView 取代 caller==owner 断言（PUBLIC/FRIENDS 外人可读为预期产品变更）；`getVerifyResult(recordId, caller)` 门内 assertCanView 后透传 verify-api — 关闭开放判定暴露；`toSummary` 带 visibility；RecordController.verifyResult 在 auth 开时 resolveUserId(request,null) 解析 caller、auth 关 null。
- **RecordLikeService**：setter 注入 `SportRecordService` 协作器，like/unlike/getLike 在记录查找后（like 在 isLikeable 后）调 `assertCanView(record, userId)`（userId = 网关解析的 acting user），三端点同矩阵（R5）；LikeController 零改动（已传 acting user）。
- **web**：client.ts 两 DTO 补 `visibility?: number`；record-submit 表单「可见性」三选 radio（默认 0）入提交载荷；index 我的记录新增可见性列（1/2 渲染 a-tag、0 显示「公开」，由 RecordSummaryDTO.visibility 驱动）；README 一句登记。零新路由、零新依赖。
- **零依赖确认**：api/record/user/web 全零新 Maven/npm 依赖；零新表（sport_record 加列）、零新索引（PK 查找 + 内存门）、无状态机/MQ/RecordEvent DTO/批参改动；`web/package.json`、`pnpm-lock`、`vite.config.ts`、`typed-router.d.ts` 未动。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 与 spec §7 内以「本 handoff 所在提交」指称 C-02，实际哈希以 `git log --oneline` 为准（沿先例）。
- **D2（词面门/探针/token/迁移验证脚本）**：中文模式、29 项字面量、迁移 idempotency shell 均经 gitignored `.trae/tmp` 注入（`Copy-Item` 字节级拷模式防编码二次转义；`[IO.File]::WriteAllText` UTF-8 无 BOM），不入库、不落 tracked 文档；命令行参数零中文（exit 127 / bash -c 引号边界规避，probe 内容经文件而非命令行传中文）。
- **D3（`--static=user-service`/`--static=api` 基线违规，非收口门）**：user-service 与 api 均**未声明** checkstyle/spotbugs/pmd 插件（仅 leaderboard-service、record-service 声明）。对未声明模块调用 `--static` 会取下通用 checkstyle 配置，实测 user-service **1015**、api 数百条违规——且违规集中在**未触碰的基线代码**（如 InternalFriendController 既有端点缺 @return/@param/final、package-info.java 缺失、`@Size(max=64)`/`@Max(3)` MagicNumber）。这些是不本课题引入的基线条件，修需越白名单大规模重构。**结论**：本课题静态门以唯一声明并强制的改动模块 **record-service** 为准且 rc=0 / 0 violations；user-service/api 未期满不记入本变更收口（如实登记，不假装绿）。
- **D4（offline 日志噪音）**：offline 模式 Nacos 连接失败/熔断 / grpc 探测等日志系既有测试环境噪音（服务未起），对七模块计数零影响，`Tests run` 汇总逐位如 §5.1。
- **D5（web build chunk 体积告警）**：`vite build`「chunks larger than 500 kB」为既有非阻断 advisory（入口 JS 1.6MB 系基线量级，与本课题正交），build rc=0。
- **D6（独立复核：缩写路径致契约门判据 B 失配 + 在途证据时点失实，已重建 C-02 修复）**：独立 review（四块口径：git 状态/提交链、红绿抽样、双态门复跑、改动集全等）复跑契约门在途 `--open TASK-221 --baseline=1ee1cdc` 实测 **rc=1**，根本原因二重：① §2 只改清单十处 `...` 缩写路径（enable tree 缩写不含实际目录段）致 `extract_claims` 提取的 claims 与实际改动集不相等——判据 B 失配；② §2 首行散文 token `add-user-role.sql`（非本改动集文件）被提取为 claim → 清单多报。此前 §5.4/§9 声称在途 rc=0 属时点失实（未至真正全等即记为通过）。**修复**：§2 十处缩写全部展开为 git 全路径、首行散文改不含可提取 token 表述，重建 C-02（amend），重新逐字符对 25 文件全等并实跑在途/收口双态取真实读数，如实登记不弱化。
- **D7（CI 双红根因：FriendServiceTest 缺 TableInfo 惯用法致测试序依赖，由 TASK-221 后置修复笔修复）**：C-01 的 `areFriends_friendshipExists_returnsTrue`（FriendServiceTest:398）在纯 Mockito 单测里调用 `wrapper.getValue().getSqlSegment()` 解析 Friendship lambda 列名以断言规范化存储 user_low/user_high——该断言是测试的价值本体，不得删弱。但 `getSqlSegment()` 走 MyBatis-Plus `TableInfo` lambda 缓存，未初始化即抛 `MybatisPlusException: can not find lambda cache for this entity [com.sportverify.user.entity.Friendship]`。CI 在线双红（run 38411110326 与 run 38412925568，均为 user-service `Tests run: 28, Failures: 0, Errors: 1` 且在**同一测试** line 398 报错），本地 offline 却绿——根因是**类序依赖**：本地 offline 类序偶然先跑了先初始化该 TableInfo 缓存的其他测试类，CI online 类序不同故未幸免。**修复**：按仓内惯用法（NotificationServiceTest:46 / SportRecordServiceTest:762 同款 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Friendship.class)`，@BeforeEach setUp 内注册 Friendship 列缓存），恰改 1 文件（+7 行，3 import + 4 初始化），**未删弱任何断言**。隔离单跑红→绿实证：修复前 `mvn -o -s .mvn-settings.xml -pl user-service -am clean test -Dtest=FriendServiceTest` → 398 抛 `can not find lambda cache for this entity [Friendship]`（Tests run: 28, Errors: 1）；修复后同命令 → **Tests run: 28, Errors: 0, BUILD SUCCESS**。offline user-service 全量 170 全绿（见本笔结果）。该惯用法同时消除了线上/离线类序差异，属确定性修复而非「让本地继续碰巧绿」。
- **停止条件（§3）核对**：无一按字面触发——
  - §3.1 既有 pagePoints/getVerifyResult/like 代码形状与 recon 一致（assertCanView 可插入注册点；getVerifyResult 仅加 caller 参数、controller 侧 resolveUserId 现成）；
  - §3.2 既有测试适配后 offline 任何模块未下降（record/user 均净增，五模块逐位不变）；
  - §3.3 UserApiFallback areFriends 软降级 false 与好友榜空榜失败安全口径一致，未冲突；
  - §3.4 S1 清单外未发现新的记录读取面暴露点（业务读取路径仅 pagePoints/getVerifyResult/点赞三端点 + 归属即身份的 /my、/my/streak，均已收敛；appeal 保持 owner-only 写路径不变；getRecordWithPoints/listPoints/getDto 属内部 Feign 契约实现面，网内信任边界，不入对外读取面）；
  - §3.5 visibility 列在 scratch 库预迁移（存量 legacy-1 行）成功 ALTER 且回填 PUBLIC，无数据碰撞。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2036**（见 §6 口径，≥2034）。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT + 探针阳性对照 HIT（已留证）；`git diff --check` rc=0；工作树 `git status` 干净（`.trae/tmp` 临时件 gitignored，无 override）。
- `git diff --name-only 1ee1cdc` + untracked 对照恰 **25 文件**（C-01 21 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程未跑（未 push、未建 PR）；外部门槛待指导侧推动推送后补记。
- **真实浏览器「可见性三选表单 + 我的记录徽标」渲染与交互未跑**（沿 TASK-213/220 口径登记未覆盖）：R6 验证由 type-check + build + 代码审查承载；不得据此推断表单/徽标在真实浏览器端到端可用。
- **真实 MySQL/Redis/RocketMQ 上的跨服务好友判断联调未跑**（未起六个服务 + auth.enabled=true 全栈）；`--it` 未执行。门逻辑以单测 mock 承载（SportRecordServiceTest 直接打桩 userApi.areFriends），真实 Feign 到 user-service 的端到端链路未覆盖。
- **`--static=user-service`/`--static=api`** 因模块未声明三件套插件而存在基线违规，未把它写成通过（见 §8 D3）；本变更只以 record-service 静态为收口门。
- 未做 PRIVATE/FRIENDS 对历史存量行展示的视觉回归（存量 DEFAULT 0 回填公开，前端默认「公开」无 tag，不额外断言）。
- 本机环境为本地处置（D2 临时件），未入库，不影响对外交付物。
