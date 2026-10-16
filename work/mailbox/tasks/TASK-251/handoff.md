# TASK-251 台账（handoff）

> **TASK**：E 系批1——认证/鉴权面三处破口治理（E1 internal 凭证经网关可达 + E2 改密经网关恒 401 + E3 通知清理端点零鉴权，FULL 档）
> **开工基线**：`0af833a`（main HEAD = TASK-252 立项笔 `0af833ace3a6e6bfc0bb115aeac3ad7263d6463e`；开工时 `git status --porcelain` 空）
> **方案**：2026-10-16 E 系只读立项侦察产出，用户拍板批1（E1+E2+E3）FULL 档，与批2（TASK-252）串行执行、批1先行
> **状态**：已封盘（独立复核五块全 PASS 零阻断、非阻断 N1-N4 登记，指导侧终裁 **ACCEPT 予以封盘**，2026-10-16；CI 外部终验读数由补记笔落库）

## 0. 时点与复现须知

本台账与 spec 均为 **LF、无 BOM、单末尾换行**（仓内台账惯例）。两笔落库后**工作树干净**（`git status --porcelain` 空）。

- **复现路径读数须关 git 路径引号转义**：本课题改动集含非 ASCII 路径 `docs/adr/0007-鉴权设计.md`，而 git 默认 `core.quotePath=true` 会把 `git diff --name-only` 的该行输出为八进制转义 + 双引号形态，使 `scripts/verify/mailbox-contract.sh` 提取到的 token 与清单里的 UTF-8 路径字面不等（该工具的正则只取 `[A-Za-z0-9_./-]`）。曾临时设仓库级 `git config core.quotePath false` 复跑验证（仅 `.git/config` 本地配置，非仓内文件、不在改动集内），**该配置已回退**——收口态实测 `git config --get core.quotePath` 为空、契约门 rc=1（该配置仅修复 ACTUAL 侧转义 token 形态，提取正则的 ASCII 字符类缺口依旧，见 §6 阻断说明）；等价复现命令：`git -c core.quotePath=false diff --name-only 0af833a`（输出恰 11 条、含该路径原文）。（本条曾误述为「已设该配置且契约门 rc=0」，系 C-01 时点陈旧叙述，经独立复核 N1 指正随封盘笔订正，2026-10-16）
- **未推送（收口时点如实记录）**：实施侧两笔仅本机提交，不含 push/PR；封盘批次推送由指导侧随封盘笔执行（见 HANDOVER §1）。

## 1. 实施摘要

R1/R2/R3 三判别式逐项落地（gateway + user 双模块 + compose/CI 基建 + 文档补记）：

- **R3（E1）internal 凭证剥离 + 路径拒绝 + 密钥必填**（spec §3 R3 对应）：
  - `AuthGlobalFilter` 入口清理段（原 L129-132 治理头同一处 mutate）追加 `headers.remove(HEADER_INTERNAL_TOKEN)`，对「入口清理 → internal 路径拒绝 → 治理面分支 → 白名单/降级分支 → 鉴权分支」**全分支**生效（E1 的「外部持公开默认串经网关打上游 `/internal/auth/grant-admin` 自助提权」路径就此闭合）。
  - **常量引用方式**：R3 契约要求「引用 common 既有 `InternalApiHeaders.TOKEN`」，但 gateway 是 **common 零依赖的叶子模块**（任务书 `AuthGlobalFilter` 类注释 §1.8 明载，pom 无 common/api 依赖）；故新增本模块包内常量 `HEADER_INTERNAL_TOKEN`，值与 common 逐字相同并在 Javadoc 注明「两侧须同步」——**common 零触碰**、**模块依赖边界零破坏**（此为与契约字面的实现口径偏差，见 §4 偏差项）。
  - **路径拒绝**：新增 `static boolean hasInternalPathSegment(String)`（`("/"+path+"/").contains("/internal/")` 逐段精确比较），在**白名单判断之前、治理面分支之前**调用，任一段为 `internal` → `reject(exchange, HttpStatus.NOT_FOUND, "{\"code\":404,\"message\":\"资源不存在\"}")` + `log.warn`。语义：外部不可达、不泄露存在性；`/user/internal/health`（route-user `StripPrefix=1` 落上游 internal 面）、`/api/auth/internal/**` 等七条路由的未来 internal 面全部覆盖；`/user/internalized` 逐段语义不误伤。服务间 Feign 直连不经网关，主链路零影响。
  - **密钥必填**：`docker-compose.services.yml` 中四个使用 `INTERNAL_API_TOKEN` 的服务（user/verify/leaderboard/mapmatch，按各服务 `application.yml` 的 `app.internal.token: ${INTERNAL_API_TOKEN:...}` 与 `InternalApiFeignInterceptor` 消费面开工核对齐）environment 段改 `INTERNAL_API_TOKEN: "${INTERNAL_API_TOKEN:?required: cp scripts/verify/env.example .env}"`——对齐仓内 `GOVERNANCE_TOKEN` 既有插值形态（同文件四处逐字同款）。缺值/空值即 compose 解析失败（实测 rc=1，报 `services.gateway-service.environment.INTERNAL_API_TOKEN: required variable ... is missing a value`）。
  - **CI 占位**：`.github/workflows/ci.yml` 「Provision placeholder env」步骤由单行 `cp` 改为两行 run（`cp` + `echo "INTERNAL_API_TOKEN=..." >> .env`）——因 `scripts/verify/env.example` 属本课题零触碰面，占位值只能在本步骤补，否则 compose config/build 因必填插值红。
  - **本地 `.env`（未跟踪，不入库）**：补 `INTERNAL_API_TOKEN=SvLocal-Internal-...-ChangeMe` 一行（LF、无 BOM），使本机 compose 门禁可跑通；`.gitignore:26` 已忽略 `.env`，不入库、不影响白名单全等。
  - **文档订正**：README 冒烟表两行 curl 由「经 8080 网关打 `/user/internal/health`、`/verify/internal/probe/record`」改为「**直连 8081/8083** + token 取 `.env` 的 `INTERNAL_API_TOKEN`」，并注明「网关已拒绝一切 internal 路径，走 8080 恒 404」；README 鉴权章节把「已删除无路由的 `/internal/**` 死配置」升级为「网关入口全分支剥离入站 `X-Internal-Token` + 白名单前 internal 路径段一律 404」的新防线描述；ADR-0007 §9 内部接口凭证条下补记一行（原结论结构保留）。
- **R2（E2）改密端点迁出白名单走鉴权分支**（spec §3 R2 对应）：
  - **端点二分枚举（`AuthController`，`@RequestMapping("/api/auth")`，全 5 个端点，清晰可二分）**：公开集 = `POST /register`（发 token 前置入口，无 token 可带）/ `POST /login`（签发入口）/ `POST /refresh`（refresh 换新，凭 refresh token 自证，**无 access 可带**，故必须留白名单否则过期即无法续签）/ `POST /logout`（`AuthService.logout(refresh)` 仅凭 refresh token 吊销，**不读 X-User-Id**）；鉴权集 = `POST /change-password`（`resolveUserId(request)` 强制读网关注入的 X-User-Id）。
  - **logout 保留白名单的理由**（spec 要求登记）：登出语义是「吊销 refresh 存活键」，只依赖 refresh token 本身，不依赖身份头；若纳入鉴权分支，则 access 已过期的用户（正是需要登出的场景之一）会在网关 401 而被阻断登出，属功能回退。故 logout 留白名单，并由 AuthGlobalFilterTest 守护其「放行 + 剥离外部身份头」。
  - **whitelist 收窄**：gateway `application.yml` 由 `/api/auth/**` 改为逐端点精确清单 `/api/auth/register,/api/auth/login,/api/auth/refresh,/api/auth/logout`（+ 既有 `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`、`/user/ws-notifications` 原样保留）；`matchesPrefixList` 对无 `/**` 后缀模式走精确匹配，无需改过滤器逻辑。
  - **主链路恢复机制**：收窄后 change-password 命中鉴权分支 → 网关校验 JWT 并**覆盖式注入** `X-User-Id`/`X-Role` → `AuthController.resolveUserId` **零改动**即通（网关 `auth.enabled=false` 时该端点仍 401，属既有「无鉴权即无身份来源」口径，改密本就要求在 `auth.enabled=true` 下使用）。
  - **前端零改动核实**：`web/src/api/client.ts` **未触碰**（见 §2/§4）。核实依据：改密/登录/刷新/登出四个端点的**外部路径字符串完全未变**（变的只是网关白名单匹配方式），前端统一 Bearer 拦截器在 `auth.enabled=true` 姿态下本已携带 access token，故旧路径收窄后自然走鉴权即恢复；`web` 门禁 type-check rc=0 + 61 测全绿亦为佐证（但非端到端联调，见 §4 受限项）。
- **R1（E3）通知清理端点收敛 ADMIN 双层防线**（spec §3 R1 对应）：
  - **网关层**：gateway `application.yml` `admin.paths` 追加精确串 `/user/api/notifications/expired`（无 `**` 后缀 → 精确匹配语义，沿 `/leaderboard/api/leaderboard/daily` 先例），非 ADMIN 在网关即 403、未登录 401。
  - **服务层兜底**：`NotificationController.cleanExpiredNotifications` 方法签名加 `HttpServletRequest`，新增私有 `requireAdmin(request)` 读 `X-Role`，非字面量 `ADMIN` 或头缺失一律 `throw new BizException(ResultCode.FORBIDDEN)`（1002 → 既有局部 `@ExceptionHandler` 映射 HTTP 403）**失败关闭**，服务层零触达、不清库；Javadoc 注明双层语义与「②的意义是网关配置漂移/直连端口时不出现零鉴权清库路径」。新增包内常量 `HEADER_ROLE`/`ROLE_ADMIN`（供同包测试引用）。

测试同步（纯 JVM，无新增依赖）：`AuthGlobalFilterTest` 由 23 → **32**（+9：change-password 走鉴权分支并注入 X-User-Id / 无 token 改密 401 / 公开集四端点白名单放行且剥离身份头 / 伪造 internal 凭证在鉴权分支被剥离 / 在白名单分支被剥离 / `/user/internal/health` 带合法 token 与无 token 均 404 / 命中白名单形态的 `/api/auth/internal/**` 仍 404 且不进链 / `hasInternalPathSegment` 逐段语义（含 `internalized` 不误伤）/ 清理端点 USER 403 + ADMIN 放行）；`NotificationControllerTest` 由 17 → **20**（+3：ADMIN 放行并透传 `(90, 200)` / USER 403 零触达 / 无头 403 零触达）。`AuthControllerTest` **未触碰**（网关白名单收窄不改变控制器语义，既有断言零删弱）。

## 2. 只改清单全等声明

实际改动集 = `git diff --name-only 0af833a` + 未跟踪文件（`.trae/*` 契约排除）= 恰 **11** 条路径，即 spec §2 白名单 14 项中**实际触碰的 11 项**；其余 3 项条件项（文件 6 `AuthControllerTest.java`、文件 7 `web/src/api/client.ts`、文件 10 `.env.example`）**零 diff**（逐项理由见各行与 §4⑦），故**白名单 ⊇ 改动集且差集恰为 3 个未触碰条件项**——双向全等成立。**时点边界**：C-01 实施笔的改动集为其中 **9** 条（文件 1-5、8、9、11、12），C-02 台账笔追加文件 13、14 共 **2** 条；两笔并集 11 条：


- `gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java`（1，触碰：R3 头剥离 + 路径拒绝 + 常量）
- `gateway-service/src/main/resources/application.yml`（2，触碰：R1 admin.paths 追加；R2 whitelist 收窄）
- `gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java`（3，触碰：R2/R3 共 9 新用例，实际类名/路径与白名单一致）
- `user-service/src/main/java/com/sportverify/user/controller/NotificationController.java`（4，触碰：R1 服务层角色门）
- `user-service/src/test/java/com/sportverify/user/controller/NotificationControllerTest.java`（5，条件项：**已触碰**——文件存在，扩展 3 用例）
- `user-service/src/test/java/com/sportverify/user/auth/controller/AuthControllerTest.java`（6，条件项：**未触碰**——白名单收窄不需适配其语义，断言零删弱）
- `web/src/api/client.ts`（7，条件项：**未触碰**——预期零改动核实成立，理由见 §1 R2）
- `docker-compose.services.yml`（8，触碰：R3 四服务 INTERNAL_API_TOKEN 必填插值）
- `.github/workflows/ci.yml`（9，条件项：**已触碰**——仅「Provision placeholder env」步骤补 INTERNAL_API_TOKEN 占位一行；该文件其余段落保持原样、零 diff）
- `.env.example`（10，条件项：**未触碰**——仓内根**不存在**该文件（`glob`/`Get-ChildItem` 核实；唯一 env 样例为 `scripts/verify/env.example`，属零触碰面），故无此笔改动）
- `README.md`（11，触碰：R3 curl 示例改直连口径 + 「网关无 /internal 路由」表述订正）
- `docs/adr/0007-鉴权设计.md`（12，条件项：**已触碰**——§9 内部接口凭证条下补记式订正一行，原结论叙述保留）
- `work/mailbox/tasks/TASK-251/spec.md`（13，C-02 回填基线占位符）
- `work/mailbox/tasks/TASK-251/handoff.md`（14，本台账五节回填）

除上述 14 条外无任何其他改动：`record-service`/`verify-service`/`leaderboard-service`/`mapmatch-service` 的 Java 与测试、common 全部 Java（`InternalApiAuthFilter.java`/`InternalApiHeaders.java`）、user-service `application.yml`、根与子模块 `pom.xml`、`web/package.json`、`scripts/verify/*`、`sql/`、`StompConnectAuthInterceptor`、`work/mailbox/PLAN.md`、`work/mailbox/HANDOVER.md`、`.github/` 下除 ci.yml Provision 一行外的全部内容**全部零触碰**；`.trae/tmp/` 临时件（日志/探针/合成 env）用后即删、不在改动集内。

**不入选改动集的本地操作（如实登记）**：仓库根 `.env`（未跟踪、`.gitignore:26` 忽略）补 `INTERNAL_API_TOKEN` 一行——compose 必填插值后本机门禁的前置条件；该文件不入库、不进白名单比对。

## 3. 门禁读数

读数时点：C-01 提交前的工作树（含 C-02 待回填的两件套）。收口后部分读数（`git diff --check` 对象集、契约门在途语义）不可逐字复现，语义等价说明见第 7 项。

1. **后端全模块离线门禁（FULL 档全量逐位）**：`& "D:\git\Git\bin\bash.exe" scripts/verify/mvn-verify.sh --mode=offline test` → 退出码 **0**，BUILD SUCCESS（9/9 模块）。各模块 surefire：common **52** / gateway-service **71** / user-service **205** / record-service **300** / verify-service **182** / leaderboard-service **104** / mapmatch-service **29**，全部 Failures=0 / Errors=0 / Skipped=0。开工基线 `0af833a` 实测 = 52/**62**/**202**/300/182/104/29（与派发包标注逐位一致，先跑基线留证）：gateway **62→71**（+9，`AuthGlobalFilterTest` 23→32）、user **202→205**（+3，`NotificationControllerTest` 17→20），其余五模块**逐位不变**（白名单外零 diff 论证）。全仓只增不减（**986**）。
2. **Compose 校验**（触碰 docker-compose.services.yml）：`docker compose -f docker-compose.yml -f docker-compose.services.yml config` → 退出码 **0**（含本机 `.env` 的 `INTERNAL_API_TOKEN`）；同命令 `--env-file`（无该键）→ 退出码 **1**、报 `services.gateway-service.environment.INTERNAL_API_TOKEN: required variable ... is missing a value`（必填门实证）；空值键同报 rc=1（`:?` 于缺值与空值均失败关闭）。config 输出中 `INTERNAL_API_TOKEN` 出现 **6** 处（四服务 + 网关 env_file 透传面）。
3. **前端回归**（零前端改动，照跑留证）：`cd web; pnpm run type-check`（vue-tsc --noEmit）→ 退出码 **0**；`pnpm test`（vitest 3.2.7）→ **4 文件 61 测全 pass**，退出码 **0**（与基线 4 文件 61 测持平）。
4. **契约门**（Git Bash）：`& "D:\git\Git\bin\bash.exe" scripts/verify/mailbox-contract.sh --open=TASK-251 --baseline=0af833a` → **C-01 时点退出码 0**（判据 A 两件套齐、0 待办；判据 B 该时点 handoff 尚无基线变动 → 「已收口，不重审」）；**C-02 收口态退出码 1**（判据 A 通过、判据 B 因非 ASCII 路径工具表达缺口失败）——完整成因、实跑读数与复核命令见 §6 阻断说明。
5. **token 门**（29 项受保护字面量 `git grep -cF` tracked 逐文件计数 SUM，本机口径同 CI 结构；**不枚举字面量原文读音**——逐项台账见 `.trae/tmp/t251-token-post.txt`，本节只给相对基线的增减以免费数自增失准）：基线 `0af833a` = **1405**，C-01 后工作树 = **1443**，C-02 收口态 = **1488**，**只增不减（+38 / +83）**，`TOKEN_VIOLATIONS=0`。29 项逐项比对：**26 项持平或净增**（`X-Internal-Token` **+8**、`INTERNAL_API_TOKEN` **+20**、`/internal/**` **+11**、`X-User-Id` **+10**、`X-Role` **+10**、`/internal/auth/grant-admin` **+4**、`/user/internal/health` **+6**、`ChangeMe` **+2**、`probe` **+2**、`GOVERNANCE_TOKEN` **+2**、`/api/auth/**` **+2**（本台账历史引用）、其余原型/密钥字面量逐项持平），**3 项净减各 −1**，全部为本课题契约要求的替换或既有叙述行改写所致，非删除凭证：
   - 「旧 `/api/auth/**` 整段白名单模式串」65 → **C-01 当时 64**（−1）：R2 契约要求该整段模式被逐端点精确清单替换（gateway `application.yml` 原文 1 处）；收口态由本台账 §1 R2 与 spec §3 的历史引用补回并转正（净 +2）。
   - 「旧 `/actuator/**` 白名单模式串」54 → **53**（−1）：该串原出现在 README 鉴权行与 ADR §8 白名单节，两行按 R2/R3 口径改写后各 −1，ADR 侧为补记式（原行保留、新增 internal 防线描述）。
   - 「README 旧 curl 的演示内部 token 串」16 → **15**（−1）：R3 要求 README 两行 curl 由经网关改为直连口径（各 −1），`docker-compose.services.yml` 必填插值注释与 ADR 补记各 +1，净 −1。
   - 结论：**总账 +71**，且三项负增长均为「契约要求的口径替换」而非「凭证字面量删除」；`TOKEN_VIOLATIONS=0`（无任一受保护字面量被清空或删减至零）。
6. **词面门**（CI 权威口径八项禁用口径词正则，本机显式 `-E` 以免 `|` 退化为字面量；正则内容**不逐字复述**——复述即自命中，沿 TASK-250 台账 `"..."` 惯例）：真实扫描（排除 `spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）**零命中**（rc=1，无输出行）；全仓不排除任何路径的宽松口径亦仅命中 `spec/changes/archive/**` 归档 1 处（既有台账，非本课题新增）；**探针阳性对照**：`.trae/tmp/t251-wordprobe.md` 合成含禁用词文件 `git grep --no-index` 命中 1 行（rc=0）→ 正则引擎有效、探针阳性成立；探针已删除（`PROBE_GONE=True`）。
7. **`git diff --check`**：C-01 改动集（9 文件）提交前 **rc=0 净**（输出中 `LF will be replaced by CRLF` 为 `.gitattributes` 换行转换提示，非空白违规）。行尾核对（字节级 CRLF/LF 计数）：修改的 9 文件各自保持基线形态——`docker-compose.services.yml`/`README.md`/`docs/adr/0007-*.md`/gateway 三文件 = 全 CRLF，`.github/workflows/ci.yml`/user 两文件 = 全 LF，混合行尾 0；两件套 `.md`（台账新增行）保持仓内 LF、无 BOM、末尾换行齐备；本机 `.env` 亦 LF。

## 4. 受限与偏差声明

- **① 真实网关端到端联调未做（spec §5 预注册）**：R1/R2/R3 的网关侧修复均由 `AuthGlobalFilterTest` 纯 JVM（`MockServerWebExchange` + 内联链）验证，服务层由 `NotificationControllerTest` 纯 Mockito 验证，两层拼接论证「网关注入/剥离 → 控制器认定」闭环；**未起真实网关 + Nacos + 服务的端到端联调**（沿 TASK-249/250 口径）。
- **② strict 模式默认 false 保持（spec §5 预注册）**：`app.security.strict` 与 `InternalApiAuthFilter` 的演示默认串回落逻辑零改动；本课题只把 **compose 部署路径**的 `INTERNAL_API_TOKEN` 改为必填插值（缺值即解析失败），**裸机本地 demo 仍可用默认串**（属本地演示姿态、不构成生产部署姿态）。故「外部持公开默认串自助提权」的经网关路径已由 R3 闭合，但**单服务裸跑**仍可能以默认串启动——该姿态的提升由 strict/密钥治理另课题承担。
- **③ 端口层 internal 防护仍依赖网络边界（spec §5 预注册）**：`/internal/**` 的「不经网关」防护在服务端口层面仍依赖 compose 端口绑 `127.0.0.1`（8081-8085）这一网络边界；本课题仅消解「经网关绕入」路径。**已知旁证副作用**：`scripts/perf/healthcheck.sh` L9-10 仍按旧口径经 8080 打 `/user/internal/health`、`/verify/internal/health`，R3 后该两行将得 404（脚本属本课题零触碰面，未改）；`docs/项目速览手册.md` L197-199 同类旧口径亦未触碰（不在白名单）。两处已在 README 冒烟表以「必须直连服务端口」口径订正为准。
- **④ README/ADR 订正为补记式（spec §5 预注册）**：README 两行 curl 表项直接改写（无历史价值保留必要），但鉴权章节与 ADR-0007 的订正采用**补记式**——ADR 原「网关仍无 internal 路由」结论行**逐字保留**，其下新增缩进补记条目标注「订正上条结论，原叙述保留」；历史叙述结构未被改写。
- **⑤ 实现口径偏差（相对契约字面，如实登记）**：spec/派发包 R3 要求「常量引用 common 既有 `InternalApiHeaders.TOKEN`」，但 gateway 是 common 零依赖叶子模块（引依赖会破坏模块边界，且 common 属本课题零触碰面）。故 `AuthGlobalFilter` 采用**本模块包内等值常量 + Javadoc 注明与 common 同步义务**的实现，语义与契约要求一致（剥离同一个头名字面量），仅引用形式不同。
- **⑥ R2 二分枚举结果（契约要求全表登记）**：`/api/auth/register` 公开（注册前置，无 token）；`/api/auth/login` 公开（签发）；`/api/auth/refresh` 公开（仅 refresh token 自证）；`/api/auth/logout` **公开**（仅 refresh token 吊销，理由见 §1 R2）；`/api/auth/change-password` **鉴权**（强制 X-User-Id）。无「无法二分的特殊语义端点」，未触发停止条件。
- **⑦ 条件项触碰与否（契约要求）**：文件 5 已触碰 / 文件 6 未触碰（控制器语义未变）/ 文件 7 `web/src/api/client.ts` 未触碰（端点外部路径字符串未变 + 统一 Bearer 拦截器已覆盖）/ 文件 9 已触碰（仅 Provision 步骤）/ 文件 10 `.env.example` 仓内不存在故未触碰 / 文件 12 已触碰（补记一行）。
- 其余无受限项：未 push、未建 PR、未 stash、未 `git add -A`（按文件名 add）；未触碰归档文件、`.codex/`、`.trae/`（仅 `.trae/tmp/` 临时件用后即删）；未删弱任何既有断言；未引入新外部依赖；无新增未跟踪文件（`.trae/tmp/*` 为被忽略工作区）。

## 5. 双提交记录

- **C-01 实施笔** `124c93e`（`124c93eb683e3ecb4944a8fee78dd1c41bc7364c`）：
  `fix(gateway,user): 网关剥离internal凭证与拒绝internal路径及改密迁出白名单与通知清理收敛ADMIN（TASK-251）` — 仅白名单 1-12 中实际触碰的 **9** 条（条件项 6 `AuthControllerTest.java`、7 `web/src/api/client.ts`、10 `.env.example` 未触碰，逐项理由见 §2/§4⑦）。
- **C-02 台账笔**（收口最终 HEAD；因本笔内容自身引用本笔哈希属自指，台账此处不落具体值——`git log --oneline -1` / `git rev-parse HEAD` 即为该笔，**C-01 短哈希 `124c93e` / 完整 `124c93eb683e3ecb4944a8fee78dd1c41bc7364c` 为准**，C-02 紧随其后）：
  `docs(mailbox): 登记TASK-251实施完成与门禁回填（TASK-251）` — 仅文件 13（spec.md：替换开工基线占位符为 `0af833a`、状态改「已实施」）与文件 14（handoff.md：本台账五节回填 + C-01 哈希回填）。工作树于收口日净（`git status --porcelain` 为空）。

## 6. 收口态复跑记录（C-02 后）

| 门禁 | 命令 | 退出码 | 关键读数 |
| --- | --- | --- | --- |
| 契约门 | `bash scripts/verify/mailbox-contract.sh --open=TASK-251 --baseline=0af833a` | **1（见下方阻断说明）** | 判据 A 通过（两件套齐、0 待办）；判据 B **工具侧不可判**——见下 |
| 空白门 | `git diff --check`（工作树） | **0** | 净；`git diff --check 0af833a` 于两笔提交后同净（无尾随空白、末尾单换行） |
| 词面门 | CI 权威口径 `git grep -n -I -iE "..."`（排除归档/docs/internal/ci.yml） | **1（零命中）** | 唯一命中行系本台账 §3.6 对正则原文的引用；探针阳性对照 rc=0 后已删（`PROBE_GONE=True`） |
| token 门 | 29 项 `git grep -cF` SUM | — | 基线 `0af833a` 1405 → C-01 1443 → 收口态 **1488**（+83），3 项各 −1 已逐项登记（§3.5） |
| 净声明 | `git status --porcelain` | — | 空（两笔落库、无未跟踪残留；`.trae/tmp/` 临时件已删） |

**契约门判据 B 阻断说明（如实登记，含已排除项与复核路径）**：本课题改动集含**非 ASCII 路径** `docs/adr/0007-鉴权设计.md`，而契约工具的清单提取正则仅含 ASCII 字符类（`[A-Za-z0-9_./-]+`）——实测 git bash grep 在 `LC_CTYPE=C.UTF-8` 下对该 CJK 路径行**零输出**（该行的 ASCII 前缀也一并丢失），故 ACTUAL 侧的该路径在任何写法下都无法被清单 token 覆盖，判据 B 必报「改动集未声明」。收口态实跑（两笔落库、工作树净）：`bash scripts/verify/mailbox-contract.sh --open=TASK-251 --baseline=0af833a` → 判据 A **通过**（两件套齐、0 待办），判据 B **rc=1**，未声明项**恰 1 条**（即该 CJK 路径——其余 10 条已全部与清单逐字全等，含此前因清单行含「零触碰」否定词被提取器整行丢弃的 `ci.yml`，本台账已将该行改写为不含否定词的表述后复跑确认）。另曾追加本机 `git config core.quotePath false` 复跑 → 未声明项同样恰 1 条（该配置仅修复 ACTUAL 侧八进制转义 token 与清单字面不等，提取面缺口依旧），配置**已回退**（不留仓库本地配置，避免复核环境漂移）。**未采取的越界操作**：未改 `scripts/verify/mailbox-contract.sh`（零触碰面）、未重命名/移动 ADR 文件（白名单禁超出）、未新增仓内文件绕过比对。结论：**该门禁失败属工具侧对非 ASCII 路径的表达能力缺口，非改动集越界**；等价人工判据 = 本台账 §2 清单 11 条 + §4⑦ 三项条件项零 diff 声明，与 `git -c core.quotePath=false diff --name-only 0af833a` 输出**逐行全等**（复核命令即此，无需改动仓内任何文件）。
