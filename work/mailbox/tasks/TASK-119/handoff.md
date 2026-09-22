# TASK-119 handoff：鉴权降级与白名单路径剥离身份头

结论：**降级分支与白名单分支已在透传前剥离外部携带的 `X-User-Id`/`X-Role`**，鉴权开启分支仍为
覆盖式注入（未受影响）。判别式先取到红对（`expected: <null> but was: <999>` ×2），实现后转绿；
定向复跑 gateway-service **22 条全绿**，全量 `--mode=offline test` **287 全绿**（基线 284 + 新增 3）。
未 push、未建 PR。

## 只改清单（contract 判据 B，与实际受版本控制改动集一致）

- gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java
- gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java
- spec/changes/add-auth-degrade-header-strip/proposal.md
- spec/changes/add-auth-degrade-header-strip/tasks.json
- spec/changes/add-auth-degrade-header-strip/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-119/spec.md
- work/mailbox/tasks/TASK-119/handoff.md
- work/mailbox/PLAN.md

### 开工基线与本任务边界

开工基线 `1c1b5c2`（`git status` 事前仅 `?? .trae/`，领先 `origin/main`（`726cf63`）8 个提交）。
本任务**未 push、未建 PR**；未改 CI 工作流、未改契约脚本与统一验收入口、未改 `spec/specs/**`（不自行归档）、
未动其它模块与 `application.yml`；全程未用 `git stash`（临时态用 `cp` 副本 + 还原后 `sha256sum -c`）。
实际改动集（`git diff --name-only 1c1b5c2` + untracked 排除 `.trae/`）与上方 8 项**逐字一致**，无多报无漏报。

分批提交：`afb69e2` 规范三件套 · `fff52ee` 台账两件套 · `447a40e` 先落红判别式（该提交树上为红，
可独立编译） · `9d574c7` 剥离实现 · 本条记录所在的收口提交（均**未 push**）。

## 改动内容

`AuthGlobalFilter.filter` 的透传分支由 `chain.filter(exchange)` 改为
`chain.filter(stripIdentityHeaders(exchange))`；新增私有方法 `stripIdentityHeaders`：
对请求做一次 mutate，`headers.remove(HEADER_USER_ID)` + `headers.remove(HEADER_ROLE)` 后透传。
两条透传路径（降级开关、白名单）共用同一段清洗逻辑；鉴权分支一行未动（仍是 `headers.set` 覆盖式注入）。
类注释补一段「透传路径的身份头边界」，说明「不校验 ≠ 放开身份」。

不改 `app.auth.enabled` 默认值（仍为 `false`）：关闭态是本地演示与压测的既有口径，
profile 强制开启属另立变更。

## 红绿取证（两对，命令 + 关键输出 + 退出码）

命令均为唯一入口 `bash scripts/verify/mvn-verify.sh --pl gateway-service test`（本机须先
`unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL` 且 `export JAVA_HOME=/d/develop1/jdk21`，见末节环境坑）。

**红（改前，测试先写、实现未动）** —— 退出码 **1**：

```
[ERROR] Failures:
[ERROR]   AuthGlobalFilterTest.degradeDisabledStripsForeignIdentityHeaders:144 expected: <null> but was: <999>
[ERROR]   AuthGlobalFilterTest.whitelistedPathStripsForeignIdentityHeaders:154 expected: <null> but was: <999>
[ERROR] Tests run: 22, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[verify-entry] Maven 以退出码 1 结束（模式 offline）
```

两条失败断言正落在判别式上（下游请求仍带着外部伪造的 `999`），第三条（鉴权分支覆盖式注入）
改前即为绿 —— 符合预期：它是防「剥离误伤注入」的护栏，不是本次修复对象。

**绿（改后）** —— 退出码 **0**：

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in com.sportverify.gateway.auth.AuthGlobalFilterTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  32.225 s
```

`AuthGlobalFilterTest` **8 → 11**（既有 8 条断言语义未改），gateway 模块 **19 → 22**，既有用例不回归。

### 变异验证（TASK-106 手法，先证判别式能红）

把已绿的实现临时改回 `chain.filter(exchange)`（仅此一处），定向复跑 → 退出码 **1**，
失败行与红证**逐字相同**（`degradeDisabledStripsForeignIdentityHeaders:144` /
`whitelistedPathStripsForeignIdentityHeaders:154`，`Tests run: 22, Failures: 2`，`BUILD FAILURE`）。
还原（`cp .trae/tmp/AuthGlobalFilter.java.fixed` 回原位）后：
`sha256sum -c` 报 `OK`，`cmp` 退出码 0、打印 `CMP_RC=0 ZERO-DIFF`，修复态哈希
`f70b81daef323372fe12434a4eb6219b871ce4290caaeca96db153877b654e3a` 与变异前逐位一致。

| 判别式 | 实现前 | 实现后 | 变异体（删剥离逻辑） |
| --- | --- | --- | --- |
| 降级分支伪造头清洗 | 红（`:144`） | 绿 | 红（`:144`） |
| 白名单分支伪造头清洗 | 红（`:154`） | 绿 | 红（`:154`） |
| 鉴权分支覆盖式注入 | 绿（预期） | 绿 | 绿（预期） |

## 全量验收（唯一入口）

```
[verify-entry] 生效模式：offline
[verify-entry] settings 路径：.mvn-settings.xml
[verify-entry] localRepository：D:/code/sports/.m2-repo
[verify-entry] 命令全文：mvn -B -ntp -o -s .mvn-settings.xml clean test
[INFO] BUILD SUCCESS
[INFO] Total time:  05:06 min
MVN_RC=0
```

逐模块 `Tests run`（Failures/Errors/Skipped 全 0）：**17 / 22 / 31 / 80 / 81 / 50 / 6 = 287**。
相对基线 284 的**唯一差异**是 gateway **19 → 22**（+3 本次新增用例），其余六模块逐位不变；
生效模式 offline、localRepository 存在 ⇒ 依赖来源可判定（未触发退出码 3）。

**环境坑（如实登记，非本任务引入）**：本机 shell 继承 `MSYS_NO_PATHCONV=1` / `MSYS2_ARG_CONV_EXCL=*`，
`mvn` 会把 unix 路径喂给 Windows java，报 `找不到或无法加载主类 …plexus.classworlds.launcher.Launcher`。
处置：跑验收前 `unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL` + `export JAVA_HOME=/d/develop1/jdk21`。
本次四次 Maven 调用（红、绿、变异、全量）全部在该前置下执行，无一次因该坑中断。

## 词面自检（CI 同款，两 locale 各一次）

脚本 `.trae/tmp/wording-check-119.sh`（UTF-8 承载禁用词正则，命令行保持纯 ASCII，该目录不入库），
与 `ci.yml` 的 `Public docs wording self-check` 同构：`git grep -n -I -iE <禁用词表> -- 三排除`。

```
===== locale=C rc=1 =====
(no match)
VERDICT[C]=ZERO-HIT
===== locale=default rc=0 =====
api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17  …〔含「垂距」字样的注释行〕…
api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:36  …〔同上〕…
VERDICT[default]=HIT-FOUND hits=2
```

- `LC_ALL=C`（CI 语义）**ZERO-HIT** —— 本任务全部 8 个改动文件零命中。
- 默认 locale 余下 2 条，落点与 TASK-118 已登记的完全是同一处（既有 `MapMatchResultDTO.java`，
  本任务未触碰），属本机 MSYS 引擎伪影、非真命中；详见「未覆盖」。

## 契约自证

- **在途跑法①**（工作树 7 项、`PLAN.md` 待写）：`bash scripts/verify/mailbox-contract.sh --baseline=1c1b5c2`
  → **判据 A 通过**；TASK-119 判据 B **失败**，唯一差异是
  `清单多报（实际未改动）：work/mailbox/PLAN.md` —— 即本记录自身，符合在途预期；
  其余历史任务全部「足迹不在工作树，视为已收口，不重审」（它们与本改动集无交集）。
- **在途跑法②**（工作树 8 项、含本记录）：同命令 → **`TASK-119：判据 B 通过（只改清单与实际改动集一致）`**。
  该次整体退出码仍为 1，成因**不在 TASK-119**：`PLAN.md` 进入改动集后，**12 个历史任务**
  （TASK-018 / 106 / 109~118）的回传正文含 `PLAN.md` 等公共文件 token 与本改动集交叠，
  触发既往台账已记录的「公共文件过冲」强校验，与本任务改动无关。
- 收口提交后：`bash scripts/verify/mailbox-contract.sh`（**无参数**）→ 退出 **0**
  （工作树无迹 ⇒ 「足迹不在工作树，视为已收口」不重审）。

## 未覆盖（不得写成通过）

1. **端到端面未覆盖**：本次只做单元级判别式（`MockServerWebExchange` + 内联链）。
   「真实起网关 → 打 8080 → 观察下游 `.getHeader("X-User-Id")`」的全栈验证本机未做
   （需六服务 + 中间件齐备，任务包未要求、也不具备），按未覆盖记账，**不得读作端到端已验**。
2. **默认 locale 下仍有 2 条词面命中**，落点 `MapMatchResultDTO.java:17/36`（本任务只改清单外，
   TASK-118 已登记同一处），未修、按未覆盖记账。
3. **CI（外部门槛）未到达**：本任务不 push，无新 run；CI 侧结论须待下次 push 由
   `Public docs wording self-check` 与 `Build and test` 复验。

## 待主 agent 决定

1. **`application.yml` 的注释已与实现漂移**：`gateway-service/src/main/resources/application.yml:104`
   仍写「false=旧行为（透传不校验，显式携带 userId）」，未提剥离身份头。该文件不在本任务只改清单内，
   故未动 —— 建议与「默认值是否按 profile 强制开启」一并处理，避免为一句注释扩改动集。
2. **是否把默认值改为按 profile 强制开启**：本任务按任务包包边界只做剥离，未碰默认值。
   若后续要让 `enabled=true` 成为生产口径，需同步更新压测脚本的鉴权头口径（本次已核对：现有脚本
   无一经网关发这两个头，前端 `client.ts` 也明确不发，故切换成本主要在 token 获取）。
3. **TASK-118 遗留的 2 条默认 locale 伪命中**是否借本类变更顺带清掉：处置口径未变，仍需最小改动授权。
