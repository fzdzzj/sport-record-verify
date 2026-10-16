# TASK-254 台账（handoff）

> **TASK**：E 系批4a-1——网关与高危簇（E10+E11+E12+E16+两顺手项，FULL 档）
> **开工基线**：`9ce65b7`（main，立项笔后工作树干净；C-02 已回填实际哈希）
> **方案**：2026-10-16 E 系批4a-1 指导侧立项侦察，用户拍板「按建议先启动批 4a-1」立项
> **状态**：已终裁封盘（2026-10-16：指导亲核三块 PASS + 独立复核五块全 PASS 零阻断 N1-N3 非阻断登记，指导侧终裁 **ACCEPT**；CI 外部终验运行号与终裁依据由补记笔落库登记）

## 1. 实施摘要

六判别式逐项落地（与 spec §3 对应）：

- **R1（E10）网关 HTTP 客户端超时**（spec §3 R1）：gateway `application.yml` 的 `spring.cloud.gateway` 段内（与 routes/globalcors 同级）新增 `httpclient` 段 —— `connect-timeout: 3000`（毫秒）、`response-timeout: 10s`（SCG 默认 response-timeout 无截止，上游 hang 死连接无限占用——Sentinel 限流对已建立连接零作用；数值对照 verify-service Feign 默认 connect 1000 / read 3000，网关为最外层代理取余量）。纯配置零 Java 改动。`GatewayProbeTest` 追加 `httpclientTimeoutsConfigured` 资源断言用例（沿 `readinessGroupStructureMatchesRealDependencies` 资源读取范式，断言两键字面量存在）。
- **R2（E11 前半）规则快照程序化校验**（spec §3 R2）：`RuleVersionService.createVersion` 入口 `request.getRules() != null` 时先调 `validateSnapshotRules` 逐项校验，违规抛 `BizException(RULE_VERSION_CONFLICT, "规则参数非法：<字段路径>=<实际值>，须满足 <约束>")`——message 含字段路径与实际值；rules=null（快照当前基线）行为不变。校验范围：`rules.vDrift > 0`；legacy 扁平 r1-r4（`rules.r1/r2/r3/r4`）与 `bySportType` 每条目（`rules.bySportType.<TYPE>.r1..r4`）同一套 R1-R4 约束（r1.speed>0/windowPoints>=2/minWindows>=1、r2.accel>0/minCount>=1、r3.minMinutes>=1/maxMeters>0/segmentRatio∈(0,1]、r4.maxRatio>=1/minCumulativeMeters>=0）；通用 R5（offRoadRatio∈(0,1)、hardOffRoadRatio∈(0,1] 且 >= offRoadRatio、minPoints>=1）。`VerifyProperties` / `RuleVersionCreateRequest` / `RuleVersionController` **零改动**（校验程序化落 service，不引注解级联，避免 Nacos 配置绑定语义外溢）。
- **R3（E11 后半）VerifyEngine 单规则异常隔离**（spec §3 R3）：规则链循环体 per-rule try/catch——单规则 evaluate 抛任意 RuntimeException → `log.error`（含规则类名 `rule.getClass().getSimpleName()` 与异常摘要 `e.getMessage()`，附堆栈）+ 跳过该规则；其余规则照常评估、聚合评分不受影响、verify 正常返回。预处理段与聚合段不在隔离范围（保持既有语义）。`VerifyEngineTest` 追加 `ruleChain_singleRuleRuntimeException_isolatedAndSkipped`：stub 一条 evaluate 抛 RuntimeException 的规则 + R1SpeedRule 并行 → verify 正常返回 REJECTED、R1_SPEED 命中被收集、THROWING 规则命中零收集。
- **R4（E12）likes/batch 入参上限**（spec §3 R4）：`RecordLikeService.getLikesBatch` 入口（去重与过滤 null 之前）`recordIds.size() > LIKES_BATCH_MAX_SIZE(50)` → 拒绝式抛 `BizException`（非截断——防静默丢数据破坏「返回顺序与去重后可见记录顺序一致」契约）。50 为 Feed 单页记录数量级推定值，按任务书未改数值（常量 `LIKES_BATCH_MAX_SIZE = 50`，数值调整需指导侧授权）。合法 ≤50（含重复与 null）行为不变。**实现决策 D1（见 §4）**：record 域无通用参数错误 ResultCode（common 模块属零触碰面不可加码），采用 record 域既有「非法输入拒绝」承载码 `SPORT_TYPE_INVALID` + 描述性 message（含实际条数与上限）。`LikeController` **零改动**。`RecordLikeServiceTest` 追加两用例：51 条原始入参拒（断言 BizException 与 message 含 51/50、零 Pipeline 零 Mapper 触达）、恰 50 条含重复与 null 正常（去重后走既有逻辑）。
- **R5（E16）Sentinel 空集护栏**（spec §3 R5）：`SentinelGatewayRuleConfig.toGatewayFlowRules` 解析成功后 `list.isEmpty()` → `log.warn`（注明「空规则集视为无效更新，保持上一版」）+ return null——与解析失败同一「保持上一版」语义，合法空 JSON 数组不再覆盖 `register2Property` 属性（Nacos 缺省时落下的代码兜底 5000 QPS 不被静默清空）。**可见性形态（C-02 登记）**：private → **包私有**（spec 授权二择一之「包私有」，同包测试直调，Javadoc 注明；方法引用 `this::toGatewayFlowRules` 不受影响）。新建 `SentinelGatewayRuleConfigTest` 三用例：合法空数组 `[]` → null、非 JSON 垃圾 `{oops`/`not-json` → null（既有行为回归）、合法两条规则（resource/count/intervalSec/grade 四字段）→ size==2。
- **R6（顺手项）注释与脚本口径订正**（spec §3 R6）：
  - `RecordLikeMapper` `selectUserIdsByRecordId` / `selectDistinctRecordIds` 两处 Javadoc 由「对账已改走 selectRecordLikePairs() 单次批量扫描」订正为与类头 L18 现行口径统一：键集分页游标（`selectDistinctRecordIdsAfter` + `selectPairsByRecordIds`），本方法保留、对账主路径对其调用须保持 0 次（判别式测试约束）。
  - `scripts/perf/healthcheck.sh` L9-10 两行 internal 冒烟订正为现行可达口径：TASK-251 起网关对 internal 路径段一律 404，改为经网关公开只读路由（`/leaderboard/api/leaderboard?type=overall`），脚本自检用途不变。
  - `docs/项目速览手册.md` grep `internal/health` 定位 3 处旧口径行（L77 StripPrefix 示例、L197/L198 冒烟两行）同步订正：L77 改 `/leaderboard/api/leaderboard` → leaderboard-service `/api/leaderboard` 真实路由示例；L197/L198 改两个经网关公开只读榜单路由（总体榜 / 日期范围榜，响应均为 code:0，与「冒烟五条期望均为 200 + code:0」块注释一致）。L199 `internal/probe/record` 为另一旧口径行但不含本课题 grep 串（internal/health），不在本课题订正范围、零触碰。README 已订正、勿动。

## 2. 只改清单全等声明

`git diff --name-only 9ce65b7` + 未跟踪文件（无 .trae 残留，普通口径）恰 **15** 项，与 spec §2 白名单 15 项双向全等（业务/测试 13 项 + 台账 2 项）：

- gateway-service/src/main/resources/application.yml
- gateway-service/src/main/java/com/sportverify/gateway/config/SentinelGatewayRuleConfig.java
- gateway-service/src/test/java/com/sportverify/gateway/config/SentinelGatewayRuleConfigTest.java（新建）
- gateway-service/src/test/java/com/sportverify/gateway/GatewayProbeTest.java
- verify-service/src/main/java/com/sportverify/verify/service/RuleVersionService.java
- verify-service/src/main/java/com/sportverify/verify/algorithm/VerifyEngine.java
- verify-service/src/test/java/com/sportverify/verify/algorithm/VerifyEngineTest.java
- verify-service/src/test/java/com/sportverify/verify/service/RuleVersionServiceTest.java
- record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java
- record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java
- record-service/src/main/java/com/sportverify/record/mapper/RecordLikeMapper.java
- scripts/perf/healthcheck.sh
- docs/项目速览手册.md
- work/mailbox/tasks/TASK-254/spec.md
- work/mailbox/tasks/TASK-254/handoff.md

零触碰声明（白名单外全仓零 diff）：LikeController.java（E12 校验落 service 层）、VerifyProperties.java / RuleVersionCreateRequest.java / RuleVersionController.java（E11 校验程序化落 service）、AuthGlobalFilter 及网关鉴权面全部（AuthGlobalFilter.java / JwtTokenParser.java 等）、除 gateway 外各服务 application.yml/properties、common 模块（ResultCode/BizException/GlobalExceptionHandler）、api 模块、全部 pom.xml、web/（含 typed-router.d.ts，build 再生成后与基线零 diff）、scripts/verify/*、scripts/db/*、sql/*、.github/、docker-compose*、PLAN.md、HANDOVER.md、BACKLOG.md。`.trae/tmp/` 为被忽略临时件目录（门禁模式/读数/日志/探针），用后已删。

## 3. 门禁读数

七门禁逐项（后端等门禁为 C-01 前实施态读数；契约门双时点；token/词面门收口复测）：

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端全模块 | `& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test` | 0 | 全模块 surefire：common 52 / **gateway 75（+4）** / user 205 / **record 302（+2）** / **verify 195（+7）** / leaderboard 108 / mapmatch 29，全 0F/0E/0S，BUILD SUCCESS（11:10）；基线 9ce65b7 = 52/71/205/300/188/108/29 → gateway +4 = GatewayProbeTest 新增 httpclientTimeoutsConfigured 1 测 + SentinelGatewayRuleConfigTest 新建 3 测；record +2 = RecordLikeServiceTest 批量上限两用例；verify +7 = RuleVersionServiceTest 快照校验六用例 + VerifyEngineTest 隔离一用例；common/user/leaderboard/mapmatch 逐位持平（凭白名单外零 diff 论证不变），只增不减 |
| 2 | Compose | `docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` | 0 | 编排解析通过（本机 .env 在位） |
| 3 | 前端 | `cd web; pnpm run type-check; pnpm test` | 0 | type-check（vue-tsc --noEmit）0 错；vitest 4 文件 **61 测全绿**（与基线 4/61 持平，白名单无 web 文件；build 后 typed-router.d.ts 与基线零 diff） |
| 4 | 契约门 | `& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-254 --baseline=9ce65b7` | 见说明 | **C-01 窗口 rc=0**（handoff.md 相对基线无变动 → 判据 B「已收口，不重审」，判据 A 两件套齐）；**C-02 收口态 rc=1**（判据 A 通过；判据 B 因非 ASCII 路径 `docs/项目速览手册.md` 工具提取缺口失败，未声明项恰 1 条——工具侧表达缺口非改动集越界，见 §6 阻断说明；等价判据 `git -c core.quotePath=false diff --name-only 9ce65b7` 恰 15 条与白名单逐行全等） |
| 5 | token 门 | 29 项 `git grep -cF` tracked 全量逐项计数（普通口径，禁 --untracked；清单取自 PLAN-history 受保护 token 节，台账不枚举字面量） | 0 | 基线 9ce65b7 SUM=**1989** → C-02 收口态复测 SUM=**1989**，29 项**逐位持平、无一减少**（TOKEN_VIOLATIONS=0）；改动集对受保护 token 零命中扰动 |
| 6 | 词面门 | CI 权威口径 `git grep -n -I -i -E -f <模式文件>`（模式文件法绕行本机中文命令行 127；正则字面量现场从 ci.yml 提取经 .trae/tmp 模式文件承载、不入库，三排除 `:!spec/changes/archive/**` `:!docs/internal/**` `:!.github/workflows/ci.yml`） | 1（零命中） | 收口态（全 tracked 覆盖含新测试与两件套）实扫 **ZERO_HIT rc=1**；合成探针阳性对照：`.trae/tmp` 探针文件经 `git grep --no-index` 实扫命中 rc=0（证明模式文件有效）；探针与临时件已删 |
| 7 | diff 净 | `git diff --check` | 0 | 提交前净、两笔 `git show --check` 净；行尾：新文件 `SentinelGatewayRuleConfigTest.java` 纯 LF + 完整末尾换行（末字节 0x0A）+ 无 BOM；改文件保持基线各自工作树形态（application.yml / SentinelGatewayRuleConfig / GatewayProbeTest / RuleVersionService / VerifyEngine / VerifyEngineTest / RuleVersionServiceTest / RecordLikeService / RecordLikeServiceTest / RecordLikeMapper / healthcheck.sh / 速览手册 / spec / handoff 均原样保留既有行尾，无混合、无行尾 churn） |

**时点边界说明**：门禁 1/2/3/7 为 C-01 前实施态读数（工作树含全部 13 项改动）；门禁 4 契约门为双时点（C-01 窗口 rc=0 + C-02 收口 rc=1，工具缺口见 §6）；门禁 5 token 收口复测在 C-02 提交后执行（读数与实施态一致）；门禁 6 词面门在 C-02 收口态执行（新测试文件与两件套均已 tracked，全量覆盖）。

**静态门（停手条件核验，非七门禁）**：`mvn-verify.sh --mode=offline --static=record-service` rc=1，Checkstyle 恰 **4** 违规，全部 pre-existing——`SportRecordService.java:1050`（TASK-234 引入的 private record `DerivedTrackMetrics` 缺 @param，JavadocType 闸门）；该文件自基线**零 diff**（`git diff 9ce65b7 --quiet` rc=0），checkstyle 对内容确定，故基线同为 4 违规、**delta=0**；本课题 record 两个改动文件（RecordLikeService / RecordLikeMapper）**0 违规**。gateway/verify 模块未声明静态三件套插件（pom 零 diff），不涉。「checkstyle 违规增加」未触发（见 §4 D3）。

## 4. 受限与偏差声明

spec §5 预注册五项受限（全部按口径登记）：

1. **E10 超时数值（3000/10s）为静态推演**（内网 Feign 对照 + 余量），真实 hang 上游场景未实测（可选受限）——按口径登记，数值按任务书落地未调整。
2. **E16「null 保持上一版」沿既有解析失败分支同一框架语义**，Nacos 真实推送链路本机无 Nacos 未实测——converter 纯逻辑三用例单测覆盖（spec §5 预注册 2）。
3. **E11 R3 隔离为单规则 fail-open**（坏规则被跳过削弱该规则防线），由 R2 前置校验拦截坏快照 + log.error 告警补偿（spec §5 预注册 3 已知权衡）。
4. **E12 上限 50 为推定值**，实施侧未自行改数值（常量 LIKES_BATCH_MAX_SIZE=50，注释注明调整需指导侧授权）。
5. **速览手册订正行号以 grep 实际定位为准**：L77/L197/L198 三处（时点漂移已核实）；L199 `internal/probe/record` 不含 `internal/health` 串、不在本课题 grep 范围，零触碰。

实施偏差与决策授权链：

- **D1（R4 异常选用码）**：record 域 ResultCode 无通用「参数错误」码（common 模块属零触碰面，白名单外不可加枚举）；采用 record 域既有「非法输入拒绝」承载码 `SPORT_TYPE_INVALID`（先例：SportRecordService 未知运动类型拒绝）+ 描述性 message 承载语义（含实际条数与上限）。R2 同逻辑采用 verify 域既有参数错误承载码 `RULE_VERSION_CONFLICT`（先例：灰度比例越界）。任务书仅要求 BizException 与 message 含提示，未钉码值；测试断言异常类型与提示内容，不钉码值。
- **D2（E16 可见性形态）**：`toGatewayFlowRules` private → **包私有**（spec §3 R5 授权二择一实际形态，Javadoc 注明；测试同包直调）。
- **D3（静态门基线）**：record-service `--static` 基线（9ce65b7）已含 SportRecordService:1050 的 4 项 JavadocType 违规（TASK-234 遗留，白名单外文件）；本课题 delta=0、自身改动文件 0 违规，「checkstyle 违规增加」停手条件未触发。
- **D4（契约门收口 rc=1）**：工具侧对非 ASCII 路径 `docs/项目速览手册.md` 的表达能力缺口（TASK-251 N1 同型先例），**未采取越界操作**——未改 scripts/verify/*（零触碰）、未重命名/移动文件、未新增仓内文件绕过比对；等价人工判据 = 本台账 §2 清单 15 条与 `git -c core.quotePath=false diff --name-only 9ce65b7` 输出逐行全等，详见 §6。

## 5. 双提交记录

- **C-01 实施笔**：`342bd9a`（`342bd9af6aa485a46d8daa4c06e49c5c562a0964`）：
  `fix(gateway,verify,record): 网关HTTP客户端超时与Sentinel空集护栏及规则快照校验与引擎隔离和likes批量上限与顺手项订正（TASK-254）` —— 仅白名单 1-13（13 files changed, 334 insertions(+), 14 deletions(-)）；
- **C-02 台账笔**：`docs(mailbox): 登记TASK-254实施完成与门禁回填（TASK-254）` —— 仅 14、15（spec.md 回填开工基线占位符为 `9ce65b7` 并改状态、handoff.md 五节回填）。C-02 为收口最终 HEAD；本笔内容引用本笔哈希属自指，台账不落具体值（`git log --oneline -1` / `git rev-parse HEAD` 即为该笔）；
- **收口日净声明**：`git status --porcelain` 为空（C-02 后实测；`.trae/tmp` 临时件已删，零残留）；未推送（推送由指导侧终裁后统一执行）。

## 6. 收口态契约门阻断说明（C-02 后）

收口态实跑（两笔落库、工作树净）：`bash scripts/verify/mailbox-contract.sh --open=TASK-254 --baseline=9ce65b7` → 判据 A **通过**（两件套齐、0 待办）；判据 B **rc=1**，未声明项**恰 1 条**（`docs/项目速览手册.md`，非 ASCII 路径——契约工具 `extract_claims` 的正则字符类 `[A-Za-z0-9_./-]+` 无法提取含 CJK 的路径行，其余 14 条已与清单逐字全等；与 TASK-251 对 `docs/adr/0007-鉴权设计.md` 的已登记工具缺口同型）。等价人工判据：`git -c core.quotePath=false diff --name-only 9ce65b7` 输出恰 **15 条**，与本台账 §2 白名单清单**逐行全等**（复核命令即此，无需改仓内任何文件）。未采取越界操作：未改 `scripts/verify/mailbox-contract.sh`（零触碰面）、未重命名/移动/删除白名单文件、未新增仓内文件绕过比对。**结论：该门禁 rc=1 属工具侧对非 ASCII 路径的表达能力缺口，非改动集越界；改动集与白名单双向全等由等价判据成立。**

## 7. 纪律确认

- 双提交：C-01 `342bd9a`（13 业务/测试文件）+ C-02 台账笔（spec/handoff，收口 HEAD）；提交消息均 `type(scope): description（TASK-254）` 格式。
- 行尾：新文件纯 LF + 完整末尾换行（0x0A）无 BOM；改文件保持基线既有行尾（默认 autocrlf=true 下工作树 CRLF/LF 各自形态原样保留，blob 层 LF 不变，无混合行尾）。
- `git diff --check` 净（提交前工作树 + 两笔 `git show --check` 均 rc=0）。
- 停止条件未触发自证：五判别式按任务书落地（无「无法落地」项）；零触碰面全仓零 diff（白名单外）；offline 只增不减无回退（52/75/205/302/195/108/29）；checkstyle delta=0（D3）；无新依赖无新插件（pom 零 diff）；未 stash / 未 `add -A`（逐路径显式 add）/ 未改归档 / 未动 `.codex` 与 `.trae`（仅 `.trae/tmp/` 临时件用后删）；未推送（由指导侧终裁后统一执行）。
