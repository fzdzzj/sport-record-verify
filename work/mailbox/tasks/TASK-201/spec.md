# TASK-201 任务书 v2：治理面服务侧二道防线——签名凭证升级（harden-governance-credential，round-1 改派）

> 开工基线：本地提交 `<v2 改派笔哈希>`（proposal/spec-delta/tasks.json/本任务书 v2 修订）。
> **v2 改派（round-1 停止归因裁决）**：round-1 执行发现 spec 内部冲突——「网关共用 common codec」要求 gateway-service/pom.xml 增加 sport-verify-common 依赖（第 14 文件，触发白名单判红）。指导侧亲核补证：common/pom.xml L21 传递 `spring-boot-starter-web`，gateway 引 common 致 servlet/webflux 双栈并存、Spring Boot 3 默认起 SERVLET 类型（需 yml 补 reactive 声明 + 镜像实起验证，风险与验证面双扩）。**裁决：网关端内联最小 sign 实现（零新增文件、pom 零改动），双端格式由预注册 golden vector 锚定**——对齐 ADR-0007 L88「JwtUtil 与 JwtTokenParser 独立维护、两端对齐」既有范式。round-1 已实证结论保留沿用：codec 专项 9 用例全绿、common 51 用例全绿、compose config 双栈 RC=0（插值须加引号）、token 2030 持平、词面门 ZERO_HIT。
> 角色：执行子agent全流程实施（含自跑门禁留证、两笔本地提交）；指导侧出提案派工；复核子agent由用户另行派发。
> 对应提案：spec/changes/harden-governance-credential/（proposal.md / specs/sport-record-verify/spec-delta.md / tasks.json，均已 v2 修订）。

## §0 红线（违者立即停手回报）

恒久红线（全课题通用）：
- Maven 唯一入口 `bash scripts/verify/mvn-verify.sh`（bash 经 `D:\git\Git\bin\bash.exe`），禁手工 mvn
- 退出码 0/1/3/2；3=依赖源未定不得记 PASS
- 不推送、不建 PR、不 stash、不 `git add -A`
- 禁引入 Flyway / Testcontainers / 新 Maven 插件
- 不以 Mockito 预制过滤清单做 SQL 红测
- 不改 index、状态机、批大小、cycle、MQ 参数
- 命令行参数禁携中文（本机 exit 127），中文检索用 Grep/Read 工具
- MSYS sed -i 禁对 tracked 文件使用（CRLF 全文重写事故先例）
- 新文件 LF + 完整末尾换行；改文件保持既有 CRLF；`git diff --check` 干净
- 提交格式 `type(scope): description (TASK-201)`，msg 经 `.trae/tmp/` msg 文件 + `git commit -F`；重命名文件禁 `git commit -- <pathspec>`
- CI 红禁重试刷绿，按签名归因

本课题零触碰面（预注册，越界即停）：
`ci.yml`、`scripts/verify/mvn-verify.sh`、`scripts/verify/mailbox-contract.sh`、`docker-compose.yml`（中间件栈）、`web/`、`sql/`、父 `pom.xml`、`api/` 模块、`user-service`、`record-service`、`mapmatch-service` 全部文件、`docs/adr/**`、`.trivyignore`、`spec/specs/**` 本体、`work/mailbox/archive/**`、`.codex/`、`.trae/`、归档 spec 目录、仓库根 `.env`。

## §1 侦察实证（指导侧已完成，行号以派发笔时点为准）

1. **治理端点全集（5 端点 / 2 服务，全高危）**：
   - verify-service：`POST /rules/versions`（创建规则版本）、`PATCH /rules/versions/{id}/gray`（调灰度）、`POST /rules/versions/{id}/activate`（全量发布）——RuleVersionController.java:27-50；`POST /api/appeals/{id}/review`（申诉终判，外部路径 `/admin/api/appeals/{id}/review`）——VerifyController.java:22-35
   - leaderboard-service：`GET /api/leaderboard/daily`（每日报表）——LeaderboardController.java:68
   - 服务侧保护：common `GovernanceApiAuthFilter`（@Component，@Order(HIGHEST_PRECEDENCE+25)，OncePerRequestFilter）；protected-paths 配置落点 verify application.yml:214、leaderboard application.yml:87
   - 网关 ADMIN 校验：`AuthGlobalFilter` admin.paths 默认 `/admin/**,/verify/rules/**,/verify/api/appeals/**,/leaderboard/api/leaderboard/daily`（application.yml:138）
2. **凭证现状（静态共享密钥）**：网关 AuthGlobalFilter L176-184 仅治理面 ADMIN 注入 `X-Gateway-Governance-Token: <静态值>`（L77-78 `app.governance.token`/`GOVERNANCE_TOKEN`）；入口 L128-131 统一清除客户端自带头。服务侧 GovernanceApiAuthFilter L121-130 `MessageDigest.isEqual` 静态比对。剩余风险自认于 GovernanceApiHeaders.java:8（非签名、无重放保护、无绑定 caller、持有者可复用）。
3. **直连面**：容器网络内服务名互达（compose 单网络 sport-verify-net）；宿主机 java -jar 直跑 `server.address` 全仓 0 命中默认 0.0.0.0。F23 登记（findings-summary.md L123-139）、PLAN.md L255/L272（属另一次拍板，本课题即该拍板的落地）。
4. **JWT 基础设施（方案B不选依据）**：HS256 对称密钥 `app.auth.jwt.secret` 仅 user（签发 JwtUtil）与 gateway（验签 JwtTokenParser）持有；扩面到 verify/leaderboard 即任何服务泄漏可伪造任意 ADMIN token。JwtTokenParser.java:49-50 有密钥 >=32 字节无条件校验先例。
5. **部署缺口**：docker-compose.services.yml environment 段零 GOVERNANCE_TOKEN 命中（容器栈治理面必然 403）；scripts/verify/env.example 无应用级密钥占位（L12-23 仅中间件口令）。CI compose config 门用 `cp scripts/verify/env.example .env` placeholder（ci.yml L42-48）——env.example 补占位后 CI 自动覆盖。
6. **测试面基线**：common GovernanceApiAuthFilterTest 16 用例（静态 token 语义，含 strict 三态）；gateway AuthGlobalFilterTest 21 用例（含治理凭证注入/剥离/失败关闭约 8 例）。offline 逐位 609 = 36(common)/48(gateway)/130(user)/151(record)/159(verify)/70(leaderboard)/15(mapmatch)。
7. **static 门口径**：`--static` 缺省 leaderboard-service，双模块=leaderboard+record；common/gateway 不在静态门内（登记不改）。
8. **模块依赖边界（round-1 停止归因，v2 裁决依据）**：gateway-service/pom.xml 依赖清单零命中 sport-verify-common（纯 WebFlux 栈：spring-cloud-starter-gateway + nacos-discovery + sentinel + jjwt，无任何 servlet 依赖）；common/pom.xml L21 依赖 `spring-boot-starter-web`——gateway 若加 common 依赖将传递引入 servlet/Tomcat，Spring Boot 3 双 web 栈并存时默认起 SERVLET 类型致 WebFlux 路由失效。**故 v2 起网关端禁引 common、禁改 gateway-service/pom.xml，sign 以内联实现落地。** round-1 执行侧已实证：codec 专项 9 用例全绿（往返/时间窗/过期/篡改/垃圾/旧静态明文/域分隔/弱密钥）、common 51 用例全绿、compose config 双栈 RC=0（本机 .env 与 env.example 占位两态；插值不加引号因含空格/冒号致 go-yaml 解析 RC=1，须加引号）、token SUM 2030 持平、词面门 ZERO_HIT、git diff --check 干净。

## §2 预注册设计

### 2.1 凭证格式、codec 与预注册 golden vector（common 新增 GovernanceCredentialCodec，v2）

- 格式：`v1.<epochSeconds>.<role>.<sigHex>`，`sig = HMAC-SHA256(keyUTF8Bytes, "governance-v1|" + ts + "|" + role)` 小写 hex
- API（纯静态，JDK Mac/MessageDigest，零新依赖）：
  - `static String sign(String key, long epochSeconds, String role)` → 凭证串
  - `static String verify(String key, String headerValue, long maxSkewSeconds, long nowEpochSeconds)` → 合法返回 role，任一不符返回 null（格式四段 / ts 数字 / 常量时间签名比对 / `|now-ts|<=maxSkew`）
  - `static boolean isUsableKey(String key)` → 非空、非哨兵 `__GOVERNANCE_TOKEN_UNSET__`、UTF-8 字节数 >= 32
- now 参数化注入（可测性）；filter 内取 `System.currentTimeMillis()/1000`
- **定位（v2）**：服务侧（verify/leaderboard 经 common 加载）唯一 verify 实现；网关端不 import 本类（模块依赖边界见 §1.8），双端格式兼容由下述预注册向量锚定
- `GovernanceApiHeaders`：加 `public static final String ROLE_ADMIN = "ADMIN"`；javadoc 剩余风险改记「时间窗内可重放、不绑路径（网关外部路径与 StripPrefix 后服务路径不一致），v1 声明接受」
- **预注册 golden vector**（指导侧 HMAC-SHA256 离线算定，确定性四元组；common 与 gateway 两端测试各自硬编码断言，任一端格式/内容域/编码漂移即红）：
  - 向量 key：`task201-governance-hmac-key-0123456789abcdef`（44 字节 ASCII）
  - 向量一（正向）：ts=1700000000、role=ADMIN → sig=`67d715c97093426d344f34f8621b31849cae5d76ef9ef94cbe04f3600cd7e6b9`；网关断言内联 sign(向量key, 1700000000, ADMIN) 产出该 sig；common 断言 codec.verify(向量key, `v1.1700000000.ADMIN.<该sig>`, 300, 1700000500) 返回 ADMIN（时间窗内）
  - 向量二（role 参与签名域对照）：同 key 同 ts、role=USER → sig=`012ada0fd8c2cb393bcff183a4c1192cc16894628d0595edfbff2fd14260a035`——与向量一 sig 不同即实证 role 入签名域；common 断言 codec.verify 返回 USER（服务侧 filter 层按 ADMIN 断言拒 403 属 R2）

### 2.2 网关改造（AuthGlobalFilter，v2 内联实现，pom 零改动）

- L176-184 注入段：`headers.set(HEADER_GOVERNANCE_TOKEN, sign(governanceToken, System.currentTimeMillis()/1000, identity.role()))`——注入前提四条件（auth.enabled/JWT 有效+ADMIN/admin.enabled/凭证可用）与「仅治理面 ADMIN 注入」不变
- **内联 sign**：本类 package-private 静态方法 `static String sign(String key, long epochSeconds, String role)`——JDK Mac HmacSHA256、内容域 `"governance-v1|" + ts + "|" + role`、小写 hex、输出格式 `v1.<ts>.<role>.<sig>`，与 common codec 字面语义一致；**输出经 §2.1 向量一锚定**（网关测试硬编码断言）
- `isValidGovernanceToken` 改内联密钥可用性判断（非空、非哨兵 `__GOVERNANCE_TOKEN_UNSET__`、UTF-8 >= 32 字节；弱/哨兵/空值 → 治理面失败关闭 403，非 strict 不炸启动）
- `init()` strict 分支改用同判断（strict + 密钥不可用拒启，消息含 `app.governance.token`）
- HMAC 计算微秒级，event loop 内阻塞可接受（jjwt 验签先例同在请求路径）
- **禁引 com.sportverify.common、禁改 gateway-service/pom.xml**（§1.8 归因）；application.yml L103 段注释更新（值语义 = HMAC 密钥，>=32 字节）

### 2.3 服务侧改造（GovernanceApiAuthFilter）

- `expectedToken` 字段语义升级为 HMAC key（配置键 `app.governance.token` 不变）；新增 `@Value("${app.governance.max-skew-seconds:300}") long maxSkewSeconds`
- doFilterInternal 校验段：`codec.verify(hmacKey, provided, maxSkewSeconds, now) != null && GovernanceApiHeaders.ROLE_ADMIN.equals(role)` → 放行；否则 403（writeForbidden 不变）
- `isConfiguredToken` 改用 `isUsableKey`；init() strict fail-fast（strict + protected-paths + 密钥不可用拒启）与「无 protected-paths 服务不误伤」语义等价保持
- verify/leaderboard application.yml：注释更新 + `max-skew-seconds: 300` 显式化
- 非 strict 弱密钥：不炸启动、治理请求失败关闭（与哨兵现状对齐）

### 2.4 部署链收口

- `scripts/verify/env.example` 补：`GOVERNANCE_TOKEN=SvLocal-Governance-<8位hex>-ChangeMe`（占位 38 字节 >=32；禁复用本机 .env 实值、禁明文口令进跟踪文件）
- `docker-compose.services.yml`：gateway-service / verify-service / leaderboard-service 三服务 environment 段各加**引号形态插值** `GOVERNANCE_TOKEN: "${GOVERNANCE_TOKEN:?required: cp scripts/verify/env.example .env}"`（round-1 实测：不加引号因值含空格/冒号致 go-yaml 解析 RC=1；user/record/mapmatch 不消费零改动；env_file: [.env] 既有机制不变）
- README 鉴权/密钥章节：签名凭证语义一小段（同批重启前提：网关与 verify/leaderboard；跨机部署需 NTP；时间窗 300s 声明接受的权衡）

### 2.5 红绿判别式（预注册，全部纯 JVM 单测落地）

- R1 红：旧静态正确值（等于配置密钥原文）→ 403
- R2 红：`codec.sign(key, now, "USER")` → 403
- R3 红：过期时间戳 `now - maxSkew - 1` 签名 → 403
- R4 红：合法签名串篡改 sig 一字符 → 403
- R5 红：伪造 `X-Role: ADMIN` + 坏/无凭证 → 403（服务侧不信任 X-Role 语义保持）
- G1 绿：`codec.sign(key, now, "ADMIN")` 三条 protected path（/api/appeals/1/review、/rules/versions、/api/leaderboard/daily）全放行
- G2 绿（v2 向量断言）：网关内联 `sign(向量key, 1700000000, "ADMIN")` 输出恰为 `v1.1700000000.ADMIN.67d715c9...7e6b9`（§2.1 向量一 sig）；网关测试另硬编码断言注入 header 值经内联 sign 往返、role=ADMIN、格式四段
- G3 绿（双端锚定）：common codec 测试断言向量一 verify 返回 ADMIN、向量二 verify 返回 USER——与网关 G2 同源向量，任一端漂移即红
- 语义保持：authEnabled=false 放行非保护路径、哨兵/空白密钥失败关闭、strict 三态（fail-fast/无路径不误伤/显式密钥启动）等价迁移
- codec 专项：往返/域分隔（同 key 其它内容域签名不得通过）/弱密钥（<32 字节）拒绝/格式垃圾拒绝/golden vector 两条断言

### 2.6 交付笔结构

- C-01 实施笔：`fix(governance): 治理凭证升级HMAC签名与服务侧角色断言（TASK-201）`，恰 13 文件
- C-02 台账笔：`docs(mailbox): 登记TASK-201治理面二道防线验收与台账闭环（TASK-201）`，恰 4 文件（spec/changes/harden-governance-credential/tasks.json 勾选翻转 / 本任务书 §7 回填 / handoff.md 新建含只改清单与显式哈希 / work/mailbox/PLAN.md 纯追加登记）
- 两笔均不推送不建 PR；本机 `.env` 补 `GOVERNANCE_TOKEN` 一行属本机操作不进 git（compose config 验证前置）

## §3 停止条件（触发即零提交或保持自洽态停手，回报归因）

1. 改造导致无 protected-paths 服务（user/record/mapmatch）上下文装配失败或既有探针红
2. strict fail-fast / 失败关闭 / 哨兵拒绝 / 伪造头剥离任一既有语义无法纯 JVM 等价迁移
3. offline 609 出现回退（净增约束破坏；五不变模块任一位变动）
4. 完成凭证升级必须触碰业务 Controller / 服务业务逻辑 / 网关路由定义 / §0 零触碰面
5. HMAC 实现需要新依赖（JDK Mac 不足）或 compose config 在 env.example 补占位后仍解析失败（CI 门炸）
6. 网关测试迁移中发现注入前提四条件与签名计算存在语义冲突（如 ADMIN 判定与 role 签名值不一致路径）
7. golden vector 断言失败：网关内联 sign 或 common codec 任一端与 §2.1 预注册 sig 不符——即双端实现与预注册设计漂移，禁自行改向量值迁就实现，停手回报

## §4 白名单（恰 13 文件，C-01 只改清单全等判据；v2 维持不变——网关内联 sign 落在 AuthGlobalFilter.java 本体内，零新增文件、gateway-service/pom.xml 零改动）

| # | 文件 | 动作 |
| --- | --- | --- |
| 1 | common/src/main/java/com/sportverify/common/governance/GovernanceCredentialCodec.java | 新增 |
| 2 | common/src/main/java/com/sportverify/common/governance/GovernanceApiAuthFilter.java | 改造 |
| 3 | common/src/main/java/com/sportverify/common/governance/GovernanceApiHeaders.java | 常量+javadoc |
| 4 | common/src/test/java/com/sportverify/common/governance/GovernanceCredentialCodecTest.java | 新增 |
| 5 | common/src/test/java/com/sportverify/common/governance/GovernanceApiAuthFilterTest.java | 改造 |
| 6 | gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java | 改造 |
| 7 | gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java | 改造 |
| 8 | gateway-service/src/main/resources/application.yml | 注释 |
| 9 | verify-service/src/main/resources/application.yml | 注释+max-skew |
| 10 | leaderboard-service/src/main/resources/application.yml | 注释+max-skew |
| 11 | docker-compose.services.yml | 三服务密钥插值 |
| 12 | scripts/verify/env.example | 补占位 |
| 13 | README.md | 鉴权章节小段 |

台账笔 4 文件：spec/changes/harden-governance-credential/tasks.json、work/mailbox/tasks/TASK-201/spec.md、work/mailbox/tasks/TASK-201/handoff.md（新建）、work/mailbox/PLAN.md。

## §5 token 基线

- 开工口径：29 项 tracked `git grep -cF` 全量 SUM=2030，只增不减；本课题新文档（proposal/spec-delta/tasks.json/任务书/handoff）禁含字面正则模式（词面门）
- 测量时点：C-01 前与 C-02 后各一次，登记 handoff

## §6 门禁结构（收口全量留证，缺一不可）

1. offline 全量：`bash scripts/verify/mvn-verify.sh --mode=offline test` rc=0，逐位对照 609 基线（common/gateway 净增、user/record/verify/leaderboard/mapmatch 五模块逐位不变）
2. static 双模块：`--static`（leaderboard 缺省）与 `--static=record-service` 均 rc=0
3. 契约门双态：`bash scripts/verify/mailbox-contract.sh --open TASK-201 --baseline=<派发笔哈希>` 在途态 rc=0；收口后终态 rc=0（只改清单声明用 `./` 或 `<路径>/` 前缀形态）
4. compose config：`docker compose -f docker-compose.yml -f docker-compose.services.yml config -q`（本机 .env 补 GOVERNANCE_TOKEN 后）+ env.example 复制为临时 .env 的 CI 等效验证（临时件放 `.trae/tmp/` 用后即删）
5. token 2030 只增不减；词面门；`git diff --check`；行尾核验
6. 只改清单全等（git diff --name-only 与 §4 双清单双向核对）；纯追加核验（台账文件）
7. CI 外部终验（推送后）：6 镜像构建 + Trivy 收缩态 + compose config（placeholder .env 含新变量）全绿；CI 红禁重试刷绿

## §7 收口记录（执行侧回填，保持纯追加）

（空——执行子agent完成后按 handoff 规范回填：两笔提交哈希、门禁逐项留证、token 时点读数、CI 运行号待推送后补记）
