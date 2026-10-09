# TASK-191 harden-gateway-auth-posture 网关鉴权硬约束与 health 明细收口 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-09）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧不推送。

## 1. 开工规程核验

- **工作树基线全符**：开工基线 HEAD 为 `94ae3c1bb9458ce7a37f4f446803ee2351f0c515`（`94ae3c1`，派发笔），父为 `9bdd46a`（TASK-190 补记笔）；开工前 `git status --porcelain` 为空。派发笔另含 `spec/changes/harden-gateway-auth-posture/proposal.md` 与 `specs/sport-record-verify/spec-delta.md`，二者相对派发笔基线零改动，不属本任务 diff 集。
- **未推送口径**：`git rev-list --left-right --count origin/main...main` 开工 `0 2`（派发笔与父均未推送，沿先例禁止推送，不建 PR）。
- **受保护 token**：29 项开工实测 SUM=2030（repo 全量 `git grep -cF` 口径；TASK-190 收口真值 2030 为参照）。
- **红线逐条核验**（§0）：
  1. strict 联动语义逐字：init() 中 `strictMode && !authEnabled` → `IllegalStateException`（中文消息含 `app.security.strict` 与 `app.auth.enabled` 两键名及「生产姿态必须开启鉴权」要旨）；检查置于 governanceToken 校验之前；未改动 lax（strict=false）路径行为、未新增配置键/profile/依赖。
  2. yml 恰双处：show-details 一行 always→never（+行上注释）+ strict 注释段补联动说明；`app.auth.enabled: false` 默认值、whitelist、admin.paths、路由、CORS、sentinel、management 其余段零改动。
  3. 零触碰清单遵守：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose*（含 services/perf）/ web / sql/ / 父 pom / record-service / user-service / verify-service / leaderboard-service / mapmatch-service / 仓库根 .env / gateway 内其它类（JwtTokenParser / SentinelGatewayRuleConfig / RequestIdGlobalFilter / GatewayCorsConfig / GatewayApplication）与既有测试类全程零触碰。
  4. main Java 改动仅 `AuthGlobalFilter.java` 一个文件；既有 18 例单测零改动（仅追加 3 例）。
  5. 性能与安全收益数字零 claim；词面门正则字面量与受保护 token 字面量不入任何 tracked 新文档（沿 TASK-184 F1 / TASK-188 N1 教训）。
  6. findings-summary.md 更新仅纯追加核实段（F01/F08），既有文字零改动；行号表述带时点说明。
  7. 停止条件核验：未触发——offline 558 只增不减（实测 561）、811 持平未增、联动单测纯 JVM 确定性落地、未触碰任一零触碰面。

## 2. 一句话结论与三支裁决

**AuthGlobalFilter.init() 头部新增 strictMode && !authEnabled 硬联动校验（生产姿态强制开启鉴权，置于治理凭证检查前）+ gateway application.yml show-details: never 收口匿名组件明细泄漏（TASK-125 六服务齐）+ README 鉴权小节补充上线前加固清单 + 3 例纯 JVM 确定性单测全绿（gateway-service 41→44，全仓 558→561，+3），静态 811 持平，main Java 仅 1 文件，findings F01 与 F08 纯追加核实段（F01 关闭，F08 六服务齐），全门禁通过，判定 PASSED**；契约门在途/无参均 rc=0。外部终验待推送后下一次外部门槛（第 33 次）CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | strict 联动硬校验 + show-details never 收口 + README 上线加固清单 + 3 单测纯 JVM 全绿（gateway 44，全仓 561）+ 静态 811 持平 + main Java 仅 1 文件 + findings F01/F08 核实回填 + 契约门 rc=0 + 全门禁绿 |
| FAILED | ✗ | 未触发（无停止条件触发、无门禁回归） |
| 外部终验 | 待推送 | 推送后下一次外部门槛（第 33 次）CI 绿；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only 94ae3c1..HEAD` 逐条比对）

C-01 实施笔（恰 4 文件）：

- `gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java`
- `gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java`
- `gateway-service/src/main/resources/application.yml`
- `README.md`

C-02 台账笔（恰 5 文件）：

- `spec/changes/harden-gateway-auth-posture/tasks.json`
- `work/mailbox/tasks/TASK-191/spec.md`
- `work/mailbox/tasks/TASK-191/handoff.md`
- `work/mailbox/PLAN.md`
- `work/mailbox/findings-summary.md`

零触碰清单遵守：`ci.yml`、`mvn-verify.sh`、`mailbox-contract.sh`、`docker-compose*`、`web/`、`web/src/typed-router.d.ts`、`sql/`、父 pom、`record-service/`、`user-service/`、`verify-service/`、`leaderboard-service/`、`mapmatch-service/`、仓库根 `.env`、gateway 内其它类与既有测试类。`git status --porcelain` 收口后为空。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希。处置：§8 列出派发笔 `94ae3c1`、C-01 `bade91b` 显式哈希；C-02 自指为台账收口笔，其显式哈希以本回传报告给出（沿 TASK-182/185/186/187/188/189/190 台账终态化先例的固有自指）。 | 台账提交表终态化固有的单一自指；两笔主题与任务书 §4 逐字一致，无主题偏差。 |
| D2 | **报错顺序前置**：strict 联动检查置于 governanceToken 检查之前，若 strict=true 时既未开鉴权又缺治理凭证，启动首个异常从治理凭证缺失变为鉴权关闭。 | 预注册设计（任务书 §2.1 与 proposal §What）；符合「身份边界优先于密钥完整性」的报错层级，机制完全达预期。 |
| D3 | **词面门 repo 全量口径**：本仓历史存档 `.github/workflows/ci.yml`（正则本体）与 `spec/changes/archive/**`（历史措辞）两处命中，均为公开门禁 CI 显式排除路径（见 ci.yml `Public docs wording self-check` 步骤 exclude 集）。 | CI 权威口径处置；本任务改动文件集独立 ZERO_HIT 已实测。本行不落任何词面门正则字面量（沿 TASK-184 F1 教训）。 |

## 5. 实施证据（含验收要点判据）

### 5.1 AuthGlobalFilter.java（联动硬校验与 Javadoc 补全）

- `init()` 方法头部在白名单解析之后、governanceToken 检查之前插入联动硬校验：
  ```java
  if (strictMode && !authEnabled) {
      throw new IllegalStateException(
              "app.security.strict=true 但 app.auth.enabled=false：生产姿态必须开启鉴权，网关拒绝启动");
  }
  ```
- 类 Javadoc 安全边界「降级」条目补一句：`strict=true 时鉴权关闭即启动失败（身份边界与密钥齐同列生产硬约束）。`
- 其余逻辑与方法零改动。

### 5.2 AuthGlobalFilterTest.java（3 例纯 JVM 确定性单测）

- 追加 3 例单测（全类 Tests run 21，既有 18 + 新 3，零回归）：
  1. `strictModeWithAuthDisabledFailsStartup`：`strictMode=true` + `authEnabled=false` → `assertThrows(IllegalStateException.class, fresh::init)`，断言异常消息包含 `app.security.strict` 与 `app.auth.enabled`。
  2. `strictModeWithAuthEnabledInitializesCleanly`：`strictMode=true` + `authEnabled=true` + `governanceToken="test-governance-token"` → `assertDoesNotThrow(fresh::init)`，合法生产姿态干净启动。
  3. `laxModeKeepsLocalDemoUnaffected`：`strictMode=false` + `authEnabled=false` → `assertDoesNotThrow(fresh::init)`，守护 lax 默认姿态零扰动。

### 5.3 gateway application.yml（双处改动 diff 证据）

- L106-108：strict 开关注释段末尾追加：`；true 时强制 app.auth.enabled=true，鉴权关闭即启动失败（TASK-191 联动）`。
- L164：`show-details: always` → `never`，行上增加注释：`# 对齐其余五服务（TASK-125 口径收尾）；health 状态聚合仍可用，仅组件明细不再匿名暴露（health 在网关白名单内免 token）`。
- 其余配置项（`app.auth.enabled: false` 默认值、白名单、路由等）零改动。

### 5.4 README.md（上线前加固清单 diff 证据）

- 鉴权小节降级开关行之后插入「上线前加固清单（TASK-191 生产姿态硬约束）」小块，包含：
  - 开启鉴权：`APP_AUTH_ENABLED=true`（环境变量）或 yml 覆盖（网关唯一身份边界，关闭时业务面裸透传）
  - `JWT_SECRET` 强随机注入（≥32 字节，与 user-service 一致）
  - `GOVERNANCE_TOKEN` 注入（治理面专用凭证，无演示默认）
  - `APP_SECURITY_STRICT=true`：密钥缺失或鉴权关闭即拒启（本课题联动后为双重硬约束）
  - actuator 收敛：见既有「生产 profile 应收敛 actuator」说明（:216 附近）
- 其余段落零改动。

### 5.5 findings-summary.md（F01/F08 纯追加核实段全文）

- F01 追加核实段：
  ```markdown
  - 核实（TASK-191，2026-10-09）：**全部收口并关闭**。① 降级/白名单剥离已在 `add-auth-degrade-header-strip` 落地〔`stripIdentityHeaders()` + 3 单测〕，行号按 TASK-191 时点订正：:79 → :143-145/:195-204；② strict 联动硬校验与 ③ README 上线检查项由 TASK-191 收口，3 例纯 JVM 单测守护；F01 关闭。
  ```
- F08 追加核实段：
  ```markdown
  - 收口核实（TASK-191，2026-10-09）：网关自身残留一处已由 TASK-191 收口。gateway application.yml（行号按 TASK-191 时点为 :164）`show-details: always` → `never`，六服务齐；`/actuator/health` 端点状态聚合仍可用，组件明细不再对匿名暴露。
  ```
- 既有文字零改动，纯追加回填。

### 5.6 main Java 仅 1 文件核验

- `git diff --name-only 94ae3c1..bade91b`（C-01）中 `src/main/java` 仅 `gateway-service/.../AuthGlobalFilter.java` 一个文件。全仓其余业务与网关代码零改动。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `python -m json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| 词面门（C-01 改动文件集） | 四形态全 ZERO_HIT | 1（预期非零） |
| 词面门（repo 全量，CI 权威 exclude 口径） | 四形态全 ZERO_HIT（见 §4 D3） | 1（预期非零） |
| 词面门探针三态 | state1 ZERO_HIT rc=1 / state2 探针 HIT rc=0 / state3 移除后 rc=1，PROBE_GONE=yes | 三态符合 |
| `git diff --check`（C-01 / C-02 提交前） | 干净，无空白错误 | 0 |
| 契约门在途 `--open TASK-191 --baseline=94ae3c1` | 判据 A 两件套齐 + 1 待办放行 + 判据 B 清单一致 | 0 |
| 契约门无参（C-02 后复跑） | 判据 A 两件套齐 + 判据 B 清单一致 | 0 |
| offline 全量 `--mode=offline test` | `36/44/119/137/149/64/12` = **561**（基线 558→561，+3 落 gateway-service 41→44），Failures/Errors/Skipped 全 0，BUILD SUCCESS | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增） | 1（基线违规模块预期） |
| typed-router.d.ts 零漂移 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| token 29 项 | 开工 SUM=2030；C-01 后 SUM=2030；收口态 SUM=2030，只增不减 | 只增不减 |
| 只改清单全等 | 实际改动集恰 §3 清单（C-01 4 + C-02 5 = 9 文件）；`git status --porcelain` 收口后为空 | 全等 |
| 行尾核验 | README/gateway yml/gateway 测试/Java CRLF、findings LF，保持既有行尾 | 符合 |

## 7. token 前后读数（口径 repo 全量 `git grep -cF`）

- **开工实测（SUM=2030，TASK-190 收口真值 2030 为参照）**：全量实测 29 项和为 **2030**。沿 TASK-188 N1 教训，本报告不枚举 29 项字面量，避免全仓计数自增失准。
- **C-01 后实测（SUM=2030）**：C-01 网关过滤器代码、测试、配置与 README 均未引入受保护 token 字面量，29 项读数逐位与开工持平，SUM=2030。
- **C-02 收口复测（SUM=2030，只增不减）**：台账 / handoff / PLAN 纯追加不引入任何受保护 token 字面量，收口态实测仍为 **SUM=2030**，只增不减。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔 | `94ae3c1bb9458ce7a37f4f446803ee2351f0c515`（`94ae3c1`） | `docs(spec): 派发 TASK-191 网关生产姿态硬约束提案与任务书` |
| C-01 实施 | `bade91b044025c466997c2a338805eb50a1364ae`（`bade91b`） | `fix(gateway): strict 联动强制鉴权与 health 明细收口（TASK-191）` |
| C-02 台账收口 | 本笔（自指，见 §4 D1；显式哈希以本回传报告给出） | `docs(mailbox): 登记 TASK-191 网关鉴权硬约束验收与台账闭环（TASK-191）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧不推送。

## 9. 未覆盖项

1. **本课题全离线可验证，无 UNDETERMINED 项**：单测覆盖 strict+disabled、strict+enabled、lax 纯 JVM 确定性场景，不依赖外部容器或中间件运行。
2. **运行时真机启动验证沿口径可选登记**：本地开发环境 `app.security.strict` 默认 false、`app.auth.enabled` 默认 false，网关平滑启动，与单测 `laxModeKeepsLocalDemoUnaffected` 等价；容器或生产环境注入 `APP_SECURITY_STRICT=true` 时必须同时具备凭证且开启鉴权。
3. **性能与安全收益数字零 claim**（纪律遵守）：本变更只登记机制性事实（启动期硬联动校验、组件健康明细匿名屏蔽），不写任何量化收益数字。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions，第 33 次）CI 绿（web 档 + build 档 + 词面门）为外部终验；红则按签名归因（不带预设、禁重试刷绿）。新 run 号以文本登记，不扩受保护 token 集合。
