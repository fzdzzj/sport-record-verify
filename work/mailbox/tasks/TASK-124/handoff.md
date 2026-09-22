# TASK-124 · handoff 回传

## 结论

`SentinelGatewayRuleConfig.ROUTE_IDS` 3 条 → 7 条补全（与 application.yml 路由表逐条一致）；
新增判别式测试 `SentinelRouteCoverageTest`（2 用例）从 classpath yml 提取真实路由 ID 与
`defaultRules()` 产物双向比对，今后 yml 加路由而兜底漏配（或反向多配）当场红。
Nacos 数据源注册、阈值/窗口默认值、fallback 配置零改动。

## 只改清单

- gateway-service/src/main/java/com/sportverify/gateway/config/SentinelGatewayRuleConfig.java
- gateway-service/src/test/java/com/sportverify/gateway/config/SentinelRouteCoverageTest.java
- work/mailbox/tasks/TASK-124/spec.md
- work/mailbox/tasks/TASK-124/handoff.md
- work/mailbox/PLAN.md

## 改动明细（清单节到此截止，路径比对以上节为准）

- `SentinelGatewayRuleConfig.java`：`ROUTE_IDS` 由 3 条扩为 7 条（route-auth/user/record/leaderboard/verify/admin/mapmatch-service，顺序对齐 yml 路由表），javadoc 注明一致性由测试机械校验。`defaultRules()`/`loadGatewayFlowRules()`/`toGatewayFlowRules()`/`@Value` 项零改动（git diff 仅 ROUTE_IDS 块 + 注释行）。
- `SentinelRouteCoverageTest.java`（新建，2 用例）：
  - `fallbackRulesCoverEveryRouteInYml`：yml 提取 `- id:` 路由 ID（自检全部 route- 前缀，防判别式锚点漂移）→ 断言 `defaultRules()` 覆盖全部，缺失清单打进断言消息；
  - `fallbackRulesContainNoRouteBeyondYml`：反向断言兜底不多配（守 javadoc「与路由表一致」的双向语义）。
  - 判别式数据不硬编码（承 `LeaderboardDailyAdminOnlyTest` 先例）；走 `defaultRules()` 本体而非 ROUTE_IDS 字段镜像，反射实例的 qps/interval 为 0 不影响路由覆盖判定。

## 红绿取证

- **红（改前 yml/代码，只加测试）**：`mvn -B -ntp -pl gateway-service -am test -Dtest=SentinelRouteCoverageTest -Dsurefire.failIfNoSpecifiedTests=false` → `Tests run: 2, Failures: 1`，**rc=1**，失败原文（SentinelRouteCoverageTest.java:66）：
  `Sentinel 兜底路由缺失（Nacos 无规则时这些路由无限流兜底），缺失=[route-admin-service, route-auth-service, route-leaderboard-service, route-mapmatch-service]，兜底实际覆盖=[route-record-service, route-user-service, route-verify-service]，yml 路由表=[route-auth-service, route-user-service, route-record-service, route-leaderboard-service, route-verify-service, route-admin-service, route-mapmatch-service] ==> expected: <true> but was: <false>`
  缺失清单与任务包「缺的 4 条」逐字一致；第二条（反向）用例在改前即绿（兜底 3 条 ⊆ yml 7 条），符合设计。
- **绿（补全 ROUTE_IDS 后同命令）**：`Tests run: 2, Failures: 0`，**rc=0**，BUILD SUCCESS。
- **变异验证**（TASK-106/125 手法）：修复态副本 `.trae/tmp/task124/SentinelGatewayRuleConfig.fixed.java` sha256 `617fb30809e1a5c325b0b5ca8b2c65107bae81e683de058681029e162af20aad` 留底 → 临时删除 `route-mapmatch-service` 一条 → 同命令复红 **rc=1**，缺失清单恰为 `缺失=[route-mapmatch-service]`（断言消息与行号同红）→ `cp` 还原 → 工作树 sha256 回到 `617fb308…` 逐位一致 + `cmp` IDENTICAL。全程未用 `git stash`。

## 复跑与门槛

- 全量（唯一入口，**取证快照 00:26**）：`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0** / BUILD SUCCESS / 模块合计 **17/27/31/80/81/50/6 = 292**（gateway 25→27，+2 即 SentinelRouteCoverageTest；其余 6 模块与基线 290 逐位一致零扰动；Failures 0 / Errors 0 / Skipped 0；生效模式 offline，依赖来源可判定未触发退出码 3）。
- 全量（**收口修订复跑**，docs commit 前）：rc=0 / BUILD SUCCESS / **20/29/33/80/81/50/6 = 299**——较快照 +7 全部为并行会话 TASK-126 在途新增用例（common +3、gateway +2 即 JwtTokenParserTest 3→5、user +2，均在 TASK-126 只改清单文件内）；本任务贡献恒为 gateway +2，SentinelRouteCoverageTest 2/2 绿。
- 定向留档：`bash scripts/verify/mvn-verify.sh --mode=offline --pl gateway-service test` → 收口修订上 rc=0 / BUILD SUCCESS / gateway 模块 `Tests run: 29`（含本任务 2 用例）。
- 任务包原文「gateway 22→23+，全仓 287→288+」为 TASK-125 增量前旧锚点；开工实况 HEAD `e6c2643` 全仓 290（gateway 25），以实跑为准（spec.md 已同步登记）。

## 并行在途（TASK-126，重要）

本任务执行期间，工作树存在**另一会话**的在途任务 TASK-126（`add-strict-secret-fail-fast`：JwtTokenParser / JwtTokenParserTest / JwtUtil / JwtUtilTest / InternalApiFeignInterceptor / InternalApiAuthFilter / gateway application.yml 的 app.security.strict 段 + `work/mailbox/tasks/TASK-126/` 台账 + `spec/changes/add-strict-secret-fail-fast/` 目录）。其编辑期间 JwtTokenParserTest 在 `getStartupError`（对 Boot 3.2.4 不可编译）与 `getFailure`（可编译）两形态间迭代：
- 曾致 2 次 `--pl gateway-service` 定向复跑以**既有测试**编译红 rc=1 收场（编译错误位于 TASK-126 正在编辑的 JwtTokenParserTest.java:94 `getStartupError()`，与本任务改动无因果）；
- 本任务红绿/变异判据全部只依赖本任务自身文件（SentinelRouteCoverageTest / SentinelGatewayRuleConfig / yml 路由表——TASK-126 未触碰路由表，判别式不受其影响），TASK-126 中间态不参与任何「通过」声称；
- gateway 用例总数随 TASK-126 推进而变化（27→29），其最终结论归属该任务。

## 收口留档回填

- 定向 `--pl gateway-service test`（收口修订）：rc=0 / BUILD SUCCESS / gateway `Tests run: 29, Failures: 0, Errors: 0, Skipped: 0`（含 SentinelRouteCoverageTest 2/2）。
- 收口修订最终全量 `--mode=offline test`：rc=0 / BUILD SUCCESS / 299（20/29/33/80/81/50/6，差值归属见上节）。
- 契约在途 `mailbox-contract.sh --baseline=e6c2643 --open=TASK-126`（PLAN 记录追加后）：TASK-124 判据 A 通过（两件套齐全）；判据 B **零多报**——5 项声明全部命中实际改动集，12 条「改动集未声明」逐项核对**全部为并行会话 TASK-126 的在途足迹**（JwtTokenParser/JwtTokenParserTest/JwtUtil/JwtUtilTest/InternalApiFeignInterceptor/InternalApiAuthFilter/gateway yml 的 app.security.strict 段/TASK-126 台账/add-strict-secret-fail-fast 三件套），不属本任务；整体 rc=1 的另一成因为 PLAN.md 公共文件过冲（历史 handoff 交叠，既往已登记）。
- 收口提交后无参数跑：**rc=1，成因不在 TASK-124**——TASK-124 足迹已全部落库（脚本判「足迹不在工作树，视为已收口，不重审」）；残留失败为 TASK-126 在途状态（仅 spec 未声明 → 压判据 A；其在途文件落在实际改动集 → 压历史 PLAN 声明任务），待该会话自行收口后自然消解。

## 词面自检

CI 同款正则（`git grep -n -I -iE "面试|弹药|大厂|八股|简历|求职|突击|附录 ?A"`，载体脚本 `.trae/tmp/wording-check-task124.sh` 不入库）：
- `LC_ALL=C`（CI 口径）：全仓 **ZERO-HIT**；本任务 5 个改动载体单独扫 **ZERO-HIT**。
- 默认 locale：仅余 TASK-118 起已登记的 2 条本机引擎伪影（`api/**/MapMatchResultDTO.java:17/36`，本任务未触碰、只改清单外），按口径登记为未覆盖，不记为用例红。

## 规格判定与归档

不建 `spec/changes/` 三件套：补全既有 §8.3 限流基线（`add-sentinel-dynamic-rules` 落地的兜底默认）的实现覆盖，无新需求、无 spec 文本变更；台账两件套即满足契约判据 A。无归档动作。

## 未决

① 本次改动待下次 push 由 CI 复验（本任务不 push）；② AuthGlobalFilter 的 `@Value` 默认值与 javadoc `/actuator/**` 漂移为 TASK-125 既有未决，本任务不触碰；③ 默认 locale 词面伪影 2 条（同上，清单外既有登记）。
