# TASK-201 任务书：治理面服务侧二道防线——签名凭证升级（harden-governance-credential）

> 开工基线：本地提交 `<派发笔哈希>`（proposal/spec-delta/tasks.json + 本任务书，恰 4 新文件）。
> 角色：执行子agent全流程实施（含自跑门禁留证、两笔本地提交）；指导侧出提案派工；复核子agent由用户另行派发。
> 对应提案：spec/changes/harden-governance-credential/（proposal.md / specs/sport-record-verify/spec-delta.md / tasks.json）。

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

## §2 预注册设计

### 2.1 凭证格式与 codec（common 新增 GovernanceCredentialCodec）

- 格式：`v1.<epochSeconds>.<role>.<sigHex>`，`sig = HMAC-SHA256(keyUTF8Bytes, "governance-v1|" + ts + "|" + role)` 小写 hex
- API（纯静态，JDK Mac/MessageDigest，零新依赖）：
  - `static String sign(String key, long epochSeconds, String role)` → 凭证串
  - `static String verify(String key, String headerValue, long maxSkewSeconds, long nowEpochSeconds)` → 合法返回 role，任一不符返回 null（格式四段 / ts 数字 / 常量时间签名比对 / `|now-ts|<=maxSkew`）
  - `static boolean isUsableKey(String key)` → 非空、非哨兵 `__GOVERNANCE_TOKEN_UNSET__`、UTF-8 字节数 >= 32
- now 参数化注入（可测性）；filter/网关内取 `System.currentTimeMillis()/1000`
- `GovernanceApiHeaders`：加 `public static final String ROLE_ADMIN = "ADMIN"`；javadoc 剩余风险改记「时间窗内可重放、不绑路径（网关外部路径与 StripPrefix 后服务路径不一致），v1 声明接受」

### 2.2 网关改造（AuthGlobalFilter）

- L176-184 注入段：`headers.set(HEADER_GOVERNANCE_TOKEN, GovernanceCredentialCodec.sign(governanceToken, System.currentTimeMillis()/1000, identity.role()))`——注入前提四条件（auth.enabled/JWT 有效+ADMIN/admin.enabled/凭证可用）与「仅治理面 ADMIN 注入」不变
- `isValidGovernanceToken` 改用 `isUsableKey`（每请求 sign 前校验，弱/哨兵/空值 → 治理面失败关闭 403，非 strict 不炸启动）
- `init()` strict 分支改用 `isUsableKey`（strict + 密钥不可用拒启，消息含 `app.governance.token`）
- HMAC 计算微秒级，event loop 内阻塞可接受（jjwt 验签先例同在请求路径）
- application.yml L103 段注释更新（值语义 = HMAC 密钥，>=32 字节）

### 2.3 服务侧改造（GovernanceApiAuthFilter）

- `expectedToken` 字段语义升级为 HMAC key（配置键 `app.governance.token` 不变）；新增 `@Value("${app.governance.max-skew-seconds:300}") long maxSkewSeconds`
- doFilterInternal 校验段：`codec.verify(hmacKey, provided, maxSkewSeconds, now) != null && GovernanceApiHeaders.ROLE_ADMIN.equals(role)` → 放行；否则 403（writeForbidden 不变）
- `isConfiguredToken` 改用 `isUsableKey`；init() strict fail-fast（strict + protected-paths + 密钥不可用拒启）与「无 protected-paths 服务不误伤」语义等价保持
- verify/leaderboard application.yml：注释更新 + `max-skew-seconds: 300` 显式化
- 非 strict 弱密钥：不炸启动、治理请求失败关闭（与哨兵现状对齐）

### 2.4 部署链收口

- `scripts/verify/env.example` 补：`GOVERNANCE_TOKEN=SvLocal-Governance-<8位hex>-ChangeMe`（占位 38 字节 >=32；禁复用本机 .env 实值、禁明文口令进跟踪文件）
- `docker-compose.services.yml`：gateway-service / verify-service / leaderboard-service 三服务 environment 段各加 `GOVERNANCE_TOKEN: ${GOVERNANCE_TOKEN:?required: cp scripts/verify/env.example .env}`（user/record/mapmatch 不消费零改动；env_file: [.env] 既有机制不变）
- README 鉴权/密钥章节：签名凭证语义一小段（同批重启前提：网关与 verify/leaderboard；跨机部署需 NTP；时间窗 300s 声明接受的权衡）

### 2.5 红绿判别式（预注册，全部纯 JVM 单测落地）

- R1 红：旧静态正确值（等于配置密钥原文）→ 403
- R2 红：`codec.sign(key, now, "USER")` → 403
- R3 红：过期时间戳 `now - maxSkew - 1` 签名 → 403
- R4 红：合法签名串篡改 sig 一字符 → 403
- R5 红：伪造 `X-Role: ADMIN` + 坏/无凭证 → 403（服务侧不信任 X-Role 语义保持）
- G1 绿：`codec.sign(key, now, "ADMIN")` 三条 protected path（/api/appeals/1/review、/rules/versions、/api/leaderboard/daily）全放行
- G2 绿：网关 AuthGlobalFilterTest 断言注入 header 值经 `codec.verify(同 key, value, 300, now)` 通过且 role=ADMIN
- 语义保持：authEnabled=false 放行非保护路径、哨兵/空白密钥失败关闭、strict 三态（fail-fast/无路径不误伤/显式密钥启动）等价迁移
- codec 专项：往返/域分隔（同 key 其它内容域签名不得通过）/弱密钥（<32 字节）拒绝/格式垃圾拒绝

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

## §4 白名单（恰 13 文件，C-01 只改清单全等判据）

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
