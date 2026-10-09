# TASK-191 harden-gateway-auth-posture 任务书（strict 联动强制鉴权与网关 health 明细收口）

## 0. 红线（违任一条即 FAILED 停手回报）

1. **strict 联动语义逐字**：init() 中 `strictMode && !authEnabled` → `IllegalStateException`（中文消息含 `app.security.strict` 与 `app.auth.enabled` 两键名及生产姿态必须开启鉴权要旨）；检查置于 governanceToken 校验**之前**；**禁止**改动 lax（strict=false）路径行为、禁止新增配置键/profile/依赖
2. **yml 双处且仅双处**：show-details 一行 always→never（+行上注释）+ strict 注释段补联动说明；`app.auth.enabled: false` 默认值、whitelist、admin.paths、路由、CORS、sentinel、management 其余段**零改动**
3. 零触碰清单：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose*（含 services/perf）/ web / sql/ / 父 pom / record-service / user-service / verify-service / leaderboard-service / mapmatch-service / 仓库根 .env / gateway 内其它类（JwtTokenParser / SentinelGatewayRuleConfig / RequestIdGlobalFilter / GatewayCorsConfig / GatewayApplication）与既有测试类（ActuatorWhitelistNarrowTest 等零改动）
4. main Java 改动**仅 AuthGlobalFilter.java 一个文件**；既有 18 例单测零改动（只追加 3 例）
5. 性能与安全收益数字零 claim；词面门正则字面量与受保护 token 字面量不入任何 tracked 新文档（TASK-184 F1 / TASK-188 N1 教训）
6. findings-summary.md 更新仅纯追加核实段（F01/F08），既有文字零改动；行号表述带时点说明
7. 停止条件：offline 基线 558（36/41/119/137/149/64/12）回退 / 811 增 / 联动单测无法纯 JVM 确定性落地 / 需触碰任一零触碰面

## 1. 背景与侦察实证（指导侧已亲核，2026-10-09）

- **F01 ①已落地**（findings 档案行号漂移未复核实致误判）：AuthGlobalFilter :143-145 降级/白名单分支调 stripIdentityHeaders()（:195-204）剥离 X-User-Id/X-Role；归档变更 add-auth-degrade-header-strip；3 个既有单测覆盖（degradeDisabledStripsForeignIdentityHeaders / whitelistedPathStripsForeignIdentityHeaders / forgedIdentityOnAuthedPathIsOverridden）——伪造路径已阻断
- **F01 ②未落地**：app.auth.enabled 默认 false（:114），无生产姿态硬约束（忘开鉴权无启动期拦截）；既有锚点：strict 开关（TASK-126）已实现 strict=true 时治理凭证缺失启动失败（init :102-105）——本课题将鉴权开关纳入同一联动
- **F01 ③未落地**：README :190 仅降级开关语义，无上线检查清单
- **F08 网关残留**：gateway application.yml :164 show-details: always；其余 5 服务均 never（user:109 / leaderboard:133 / mapmatch:78 / verify:199 / record properties:89）；网关 health 在白名单（:126 精确匹配）免 token → 匿名可读组件明细
- **模块计数归属**（亲测清点）：offline 七数 36/41/119/137/149/64/12 → common=36、**gateway=41**、user=119、record=137、verify=149、leaderboard=64、mapmatch=12（api=0 不出现）；+3 后预期 gateway=44、全量 561
- **行尾基线**（git ls-files --eol 实测）：README.md / gateway application.yml / AuthGlobalFilterTest.java 工作区 CRLF；findings-summary.md LF——修改文件保持各自既有行尾（仅动目标行）
- **基线**：开工 HEAD=9bdd46a（TASK-190 补记笔待批推送，沿先例），origin/main...main=0 1，工作区干净；offline 558（36/41/119/137/149/64/12）；静态 811；token 29 项 TASK-190 收口真值 2030 参照

## 2. 预注册实施设计

### 2.1 AuthGlobalFilter.java（联动硬校验）

init() 头部（白名单解析后、governanceToken 检查前）插入：

```java
if (strictMode && !authEnabled) {
    throw new IllegalStateException(
            "app.security.strict=true 但 app.auth.enabled=false：生产姿态必须开启鉴权，网关拒绝启动");
}
```

类 Javadoc 安全边界列表「降级」条目补一句：strict=true 时鉴权关闭即启动失败（身份边界与密钥齐同列生产硬约束）。其余方法零改动。

### 2.2 AuthGlobalFilterTest.java（+3 例，纯 JVM 确定性）

构造模式：`new AuthGlobalFilter(parser)` + ReflectionTestUtils 设 `strictMode` / `authEnabled`（/ `governanceToken`）→ 直调 `init()` 断言（init 的白名单解析对 null 配置安全——splitPatterns(null) 返回空表）：

| 用例 | 设置 | 断言 |
| --- | --- | --- |
| strictModeWithAuthDisabledFailsStartup | strictMode=true, authEnabled=false | assertThrows(IllegalStateException, fresh::init)，消息含 app.security.strict 与 app.auth.enabled |
| strictModeWithAuthEnabledInitializesCleanly | strictMode=true, authEnabled=true, governanceToken="test-governance-token" | init() 不抛（联动不误伤合法生产姿态） |
| laxModeKeepsLocalDemoUnaffected | strictMode=false, authEnabled=false | init() 不抛（lax 零扰动守护，防联动误扩） |

中文 Javadoc 说明断言目的，风格沿既有测试。

### 2.3 gateway application.yml（双处）

- :164 `show-details: always` → `show-details: never`，行上注释：对齐其余五服务（TASK-125 口径收尾）；health 状态聚合仍可用，仅组件明细不再匿名暴露（health 在网关白名单内免 token）
- :106-108 strict 注释段末补一句：true 时强制 app.auth.enabled=true，鉴权关闭即启动失败（TASK-191 联动）

### 2.4 README.md（上线前加固清单）

鉴权小节降级开关说明行（:190）之后插入小块（标题 + 5 行清单 + 一句引用）：

- 开启鉴权：`APP_AUTH_ENABLED=true`（环境变量）或 yml 覆盖（网关唯一身份边界，关闭时业务面裸透传）
- `JWT_SECRET` 强随机注入（≥32 字节，与 user-service 一致）
- `GOVERNANCE_TOKEN` 注入（治理面专用凭证，无演示默认）
- `APP_SECURITY_STRICT=true`：密钥缺失或鉴权关闭即拒启（本课题联动后为双重硬约束）
- actuator 收敛：见既有「生产 profile 应收敛 actuator」说明（:216 附近）

其余段落零改动。

## 4. 白名单（只改清单与主题串）

- 派发笔：`spec/changes/harden-gateway-auth-posture/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + `work/mailbox/tasks/TASK-191/spec.md`（四件套零改动入库），主题：`docs(spec): 派发 TASK-191 网关生产姿态硬约束提案与任务书`
- C-01（恰 4 文件）：`gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java`、`gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java`、`gateway-service/src/main/resources/application.yml`、`README.md`，主题：`fix(gateway): strict 联动强制鉴权与 health 明细收口（TASK-191）`
- C-02（恰 5 文件）：`spec/changes/harden-gateway-auth-posture/tasks.json`、`work/mailbox/tasks/TASK-191/spec.md`（§7 纯追加）、`work/mailbox/tasks/TASK-191/handoff.md`（新建）、`work/mailbox/PLAN.md`（纯追加）、`work/mailbox/findings-summary.md`（F01/F08 纯追加核实段），主题：`docs(mailbox): 登记 TASK-191 网关鉴权硬约束验收与台账闭环（TASK-191）`

## 5. 受保护 token（29 项，口径 repo 全量 git grep -cF）

开工实测 SUM 为准（TASK-190 收口真值 2030 参照）；收口读数以收口态实测为准。只增不减；新文档不枚举 token 字面量。

## 6. 门禁结构

1. **预提交门禁（每笔前亲跑记 rc）**：tasks.json 语法（python -m json.tool）rc=0；词面门四形态 ZERO_HIT rc=1 + 探针三态；git diff --check rc=0；契约门在途 `--open TASK-191 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；修改文件保持既有行尾（README/gateway yml/gateway 测试 CRLF、findings LF）
2. **收口门禁（C-02 后亲跑留证）**：`bash scripts/verify/mvn-verify.sh --mode=offline test` 全量新基线逐位登记（558 只增不减，+3 预计落 gateway-service 41→44，全量 561=36/44/119/137/149/64/12 以实测为准）；`bash scripts/verify/mvn-verify.sh --static=record-service` 811 不增；零越界核验（`git diff --name-only <派发笔>..HEAD` 仅白名单 9 文件，main Java 仅 AuthGlobalFilter.java）；typed-router 零漂移（git diff --exit-code rc=0）；契约门无参 rc=0；PLAN.md 自派发笔起纯追加；findings 核实段与代码实态逐字一致
3. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（联动三例读数、yml 双处与 README diff 证据、findings 核实标注）/ 逐门实测表 / token 前后读数 / 未覆盖项（本课题全离线可验证，无 UNDETERMINED 项；运行时真机启动验证沿口径可选登记）/ 提交表（显式哈希，禁时效指针）
4. **推送后**：第 33 次外部门槛 CI 绿为外部终验；红则按签名归因，禁重试刷绿

## 7. 收口记录（执行侧 C-02 纯追加）

### 7.1 提交记录

- 派发笔：`94ae3c1bb9458ce7a37f4f446803ee2351f0c515`（`94ae3c1`） `docs(spec): 派发 TASK-191 网关生产姿态硬约束提案与任务书`
- C-01 实施笔：`bade91b044025c466997c2a338805eb50a1364ae`（`bade91b`） `fix(gateway): strict 联动强制鉴权与 health 明细收口（TASK-191）`
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff §8 给出）` `docs(mailbox): 登记 TASK-191 网关鉴权硬约束验收与台账闭环（TASK-191）`

### 7.2 单测矩阵读数（offline，C-01 实施态实测回填）

- gateway-service：`AuthGlobalFilterTest` Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
  - `strictModeWithAuthDisabledFailsStartup`：pass（strictMode=true 且 authEnabled=false 抛 IllegalStateException，异常消息含 app.security.strict 与 app.auth.enabled 两个开关名）
  - `strictModeWithAuthEnabledInitializesCleanly`：pass（strictMode=true、authEnabled=true 且 governanceToken="test-governance-token" 时 init() 正常完成不抛异常，联动不误伤合法生产姿态）
  - `laxModeKeepsLocalDemoUnaffected`：pass（strictMode=false 且 authEnabled=false 时 init() 正常完成不抛异常，lax 零扰动守护，本地演示/压测口径不受影响）
  - 既有 18 例零回归（全类 21 例全绿）

### 7.3 门禁读数（收口态实测回填）

- offline 全量逐位：`36/44/119/137/149/64/12` = **561**（基线 558 只增不减，+3 全落 gateway-service 41→44），Failures/Errors/Skipped 全 0，BUILD SUCCESS
- 静态门：`--static=record-service` Checkstyle **811 持平**未增，rc=1 为基线违规模块预期
- 契约门在途：`bash scripts/verify/mailbox-contract.sh --open TASK-191 --baseline=94ae3c1` rc=0（判据 A 两件套齐 + 判据 B 清单一致）
- 词面门四形态：改动文件集与 repo 全量（CI 权威 exclude 口径）四形态（default / C / zh_CN.UTF-8 / C.UTF-8）全 ZERO_HIT rc=1，探针三态 HIT rc=0，PROBE_GONE=yes
- token 29 项：开工实测 SUM=2030；C-01 后实测 SUM=2030；收口态实测 SUM=2030 只增不减（新文档不枚举 token 字面量，沿 TASK-188 N1 教训）
- 只改清单全等核验：C-01 恰白名单 4 文件，main Java 仅 AuthGlobalFilter.java；C-02 恰白名单 5 文件；合共 9 文件；typed-router.d.ts 零漂移（`git diff --exit-code -- web/src/typed-router.d.ts` rc=0）
- 行尾与末尾换行核验：修改文件保持既有行尾（README.md / gateway application.yml / AuthGlobalFilterTest.java / AuthGlobalFilter.java 工作区 CRLF，findings-summary.md LF，仅动目标行）
