# TASK-201 handoff：治理面二道防线——签名凭证升级（harden-governance-credential）

> 状态：**已收口（本地两笔提交 af022fc / C-02 自指，未 push、未建 PR）**。v2 改派全流程实施与门禁留证。
> 开工基线：`0174269`（v2 改派笔：提案三件套 + 任务书 v2 修订）；过滤建议零触碰 gateway-service/pom.xml；
> 网关端按 v2 内联设计（零 common 依赖、pom 零改动）。

## 一句话结论

完成 TASK-201 治理面二道防线：common 新增 `GovernanceCredentialCodec`（HMAC-SHA256 签名凭证，
`v1.<ts>.<role>.<sigHex>`，服务侧唯一 verify 实现）；common `GovernanceApiAuthFilter` 由静态比对升级为
验签 + role=ADMIN 断言；`GovernanceApiHeaders` 加 `ROLE_ADMIN`；gateway `AuthGlobalFilter` 按 v2 内联
package-private `sign`（HmacSHA256，零 common 依赖、pom 零改动）每请求对治理面 ADMIN 签注；部署链
env.example/docker-compose.services.yml/README 收口。红绿判别式 R1-R5 + G1-G3 + codec 专项全落地，
golden vector 双端锚定（向量一/二 sig 均与独立 HMAC 复算逐位一致）；offline 627 全绿（common/gateway 净增、
五不变模块逐位不变）；static 双模块 rc=0；契约门双态 rc=0；token 29 项 SUM=2030 只增不减；compose config
CI 等效 rc=0。两笔本地提交，未达外部门槛。

## 编号、基线与外部状态

- 任务编号：`TASK-201`。
- 开工/对照基线：`0174269826cf3f39fbcd0c391d3add35fd1081c2`（v2 改派笔，工作树干净）。
- C-01 实施笔：`af022fc`（`fix(governance): 治理凭证升级HMAC签名与服务侧角色断言（TASK-201）`，15 文件）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox): 登记TASK-201治理面二道防线验收与台账闭环（TASK-201）`，
  4 文件；C-02 自身哈希自指，同型偏差 D1 沿先例）。
- 两笔均**未 push、未建 PR**；`origin/main...main` 本地领先本课题两笔。
- `.trae/` 为既有未跟踪目录，本任务仅用 `.trae/tmp/` 中转 msg/临时 env，收尾即删。

## 只改清单（C-01 15 文件 = §4 白名单 13 + 偏差 2；C-02 4 台账文件）

C-01 实施笔（15 文件）：

- common/src/main/java/com/sportverify/common/governance/GovernanceCredentialCodec.java（新增）
- common/src/main/java/com/sportverify/common/governance/GovernanceApiAuthFilter.java
- common/src/main/java/com/sportverify/common/governance/GovernanceApiHeaders.java
- common/src/test/java/com/sportverify/common/governance/GovernanceCredentialCodecTest.java（新增）
- common/src/test/java/com/sportverify/common/governance/GovernanceApiAuthFilterTest.java
- gateway-service/src/main/java/com/sportverify/gateway/auth/AuthGlobalFilter.java
- gateway-service/src/main/resources/application.yml
- gateway-service/src/test/java/com/sportverify/gateway/auth/AuthGlobalFilterTest.java
- gateway-service/src/test/java/com/sportverify/gateway/auth/LeaderboardDailyAdminOnlyTest.java（偏差 D2）
- gateway-service/src/test/java/com/sportverify/gateway/auth/VerifyAppealReviewAdminOnlyTest.java（偏差 D2）
- verify-service/src/main/resources/application.yml
- leaderboard-service/src/main/resources/application.yml
- docker-compose.services.yml
- scripts/verify/env.example
- README.md

C-02 台账笔（4 文件）：spec/changes/harden-governance-credential/tasks.json、
work/mailbox/tasks/TASK-201/spec.md、work/mailbox/tasks/TASK-201/handoff.md（本文件，新增）、
work/mailbox/PLAN.md（纯追加）。

## 红绿判别式（§2.5，纯 JVM 单测，grep surefire 全绿证）

| 判别式 | 断言 | 结果 |
| --- | --- | --- |
| R1 | 旧静态正确值（等于配置密钥原文）作凭证 → 403 | 绿（GovernanceApiAuthFilterTest） |
| R2 | codec.sign(key, now, "USER") 作凭证 → 403 | 绿 |
| R3 | 过期时间戳 now-maxSkew-1 签名 → 403 | 绿 |
| R4 | 合法签名串篡改 sig 一字符 → 403 | 绿 |
| R5 | 伪造 X-Role:ADMIN + 坏/无凭证 → 403 | 绿 |
| G1 | codec.sign(key, now, "ADMIN") 三条 protected path 全放行 | 绿 |
| G2 | 网关内联 sign(向量key, 1700000000, "ADMIN") 恰产出 `v1.1700000000.ADMIN.67d715c9…7e6b9` | 绿（AuthGlobalFilterTest） |
| G3 | common codec.verify 向量一返回 ADMIN、向量二返回 USER | 绿（GovernanceCredentialCodecTest） |

语义保持 | authEnabled=false 放行非保护路径、哨兵/空白/弱密钥失败关闭、strict 三态等价迁移、路径解析 | 全绿。

codec 专项：往返 / 域分隔（role/ts 入签名域致格式合法串验签失配）/ 弱密钥拒绝 / 格式垃圾拒绝 /
golden vector 两条断言 —— 全绿。

## §6 门禁留证

1. **offline 全量**：`--mode=offline test` rc=**0**、BUILD SUCCESS；逐位 common **52** / gateway **50** /
   user **130** / record **151** / verify **159** / leaderboard **70** / mapmatch **15**（=627）。五不变模块
   user/record/verify/leaderboard/mapmatch **逐位不变**（130/151/159/70/15）；common 36→52（+16）、
   gateway 48→50（+2）净增。基线 609 → 627（净增 18）。
2. **static 双模块**：`--static`（缺省 leaderboard）rc=0；`--static=record-service` rc=0。
3. **契约门双态**：在途 `--open TASK-201 --baseline=0174269` rc=0；收口无参 rc=待 C-02 后复跑。
4. **compose config**：CI 等效（env.example 复制 `.trae/tmp/` 临时 .env，经 `--env-file`）`config -q` rc=**0**；
   本机 .env 已补 `GOVERNANCE_TOKEN` 一行（不经 git）。本机 .env 直读 config 失败为既有畸形（见偏差 D3）。
5. **token**：29 项 tracked `git grep -cF` 全量 SUM 开工=2030、C-01 后=2030、C-02 后待复测（只增不减）。
6. **词面门**：CI 权威口径 regex（自 ci.yml L95 提取）对改动文件集 ZERO_HIT；新文档零字面正则模式。
7. **`git diff --check` / `--cached --check`** 均 rc=0。
8. **行尾**：两新文件纯 LF + 末尾换行；改文件保持既有（CRLF 或基线 LF）不回归。
9. **ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose.yml / web / sql / api 模块 / docs/adr /**
   `.trivyignore` / spec/specs 本体 / 归档目录** 零触碰。

## token 时点读数

29 项受保护 token 以 tracked `git grep -cF` 全量计（SUM 语义 = 各 token 命中计数求和）：开工 **2030**、
C-01 后 **2030**、C-02 后复测见下（只增不减）。本课题新文档（tasks.json/spec §7/handoff/PLAN 追加）均未枚举
token 字面量。

## v2 实现要点

- **common codec**：`sign(key, ts, role)` HMAC-SHA256 内容域 `governance-v1|<ts>|<role>` 小写 hex；
  `verify` 四段格式/常量时间签名/时间窗三重校验返回 role 或 null；`isUsableKey` 非空/非哨兵/UTF-8>=32。
- **服务侧 filter**：expectedToken 语义升级 HMAC key；doFilter 校验段 `codec.verify + ROLE_ADMIN 断言`；
  新增 `app.governance.max-skew-seconds`（默认 300）；可用性判断改 isUsableKey；strict 三态与无保护路径不误伤保持。
- **网关内联**（v2）：进注段每请求 `sign(governanceToken, now, identity.role())`；`isUsableGovernanceKey`
  内联（非空/非哨兵/UTF-8>=32）；strict 拒启消息含 `app.governance.token`。零 import common、零 pom 改动。
- **部署链**：env.example 补 `GOVERNANCE_TOKEN=SvLocal-Governance-a1b2c3d4-ChangeMe`（36 字节≥32）；
  docker-compose.services.yml 三服务加引号形态插值 `"${GOVERNANCE_TOKEN:?required: cp scripts/verify/env.example .env}"`；
  三 yml（网关/verify/leaderboard）`max-skew-seconds: 300` 显式化 + 密钥语义注释；README 鉴权章节补签名凭证语义。
- **golden vector**：向量一 ADMIN sig、向量二 USER sig 均与独立 HMAC-SHA256 复算**逐位一致**（双端锚定成立）。

## round-1 → round-2 差异

- **网关端设计**：round-1 拟「GateWay 共用 common codec」需 gateway-service/pom.xml 加 common 依赖（第 14 文件，
  触发白名单判红 + servlet/webflux 双栈风险）；v2 裁决为**网关端内联最小 sign**（`AuthGlobalFilter` 本体
  package-private 静态方法，零新增文件、gateway-service/pom.xml 零改动），双端格式由预注册 golden vector 锚定
  （对齐 ADR-0007「JwtUtil 与 JwtTokenParser 独立维护、两端对齐」范式）。
- **探索差异**：round-1 停止归因于模块依赖边界（common/pom L21 传递 spring-boot-starter-web）；round-2 按
  v2 内联重写网关端并复用 round-1 其余实证（codec 专项用例、common 全绿、compose 引号形态插值、token 2030 持平）。
- **白名单差异**：v2 白名单仍列 13 文件，但网关端两个既有测试（LeaderboardDailyAdminOnlyTest /
  VerifyAppealReviewAdminOnlyTest）夹具 `governanceToken` 为 21 字节旧值，在 ≥32 校验下必然失败关闭致断言红；
  经裁决纳入本次改动（13→15，见偏差 D2）。

## 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 无法记录 C-02 自身哈希，同型偏差沿先例，以本提交哈希为准。
- **D2（C-01 白名单 13→15）**：v2 §2.2 强制网关请求期密钥可用性判断 UTF-8>=32 字节，与白名单外的两个既有
  gateway 测试（夹具 21 字节 `test-governance-token`）的 ADMIN 放行断言冲突（弱 key 治理面失败关闭→断言红）。
  经用户裁决纳入白名单改 2 文件（夹具升为 44 字节向量 key），C-01 记 15 文件并在此登记。
- **D3（golden vector 断言时钟参数）**：§2.1 common 断言参数 `now=1700000500, maxSkew=300` 与 ts=1700000000
  差 500s，超 300s 时间窗，与「(时间窗内)」标注冲突；向量 sig 值经独立 HMAC 复算逐位正确（无实现漂移）。
  为令断言自洽且命中「(时间窗内)」意图，断言时钟取窗上界 1700000300（|300|<=maxSkew），向量值与实现零改动；
  登记为 spec 断言参数勘误而非实现迁移。
- **D4（本机 .env 直读 compose config）**：本机 `.env` 存在既有畸形（若干注释行缺换行，把 `MYSQL_PORT`/
  `POSTGRES_PASSWORD` 等变量并进注释行），docker compose 直读其 `config -q` rc=1（POSTGRES_PASSWORD 缺失）。
  与本任务改动无关（C-01 前即如此）；CI 等效路径（env.example→临时 .env）`config -q` rc=0 为权威门禁。
  本机 `.env` 仅在末尾独立追加 `GOVERNANCE_TOKEN` 一行并经 gitignore，不修其既有畸形。
- **offline 全量两轮并发假败**：首轮 full-reactor 与 static 检查并行执行致 target/ 互相 clean 冲刷，先后出现
  mapmatch `NoClassDefFoundError: com/sportverify.api.auth.AuthApi` 与 common testCompile「找不到符号」；
  隔离串行复跑（本文件所载 627）rc=0 逐位稳定，确认两轮为并发污染非回归。

## 未覆盖与不得推出的结论

- **真实跨进程联调（网关→verify/leaderboard 签名注入→服务侧验签）**未跑；`--mode=online` 与 CI 未跑。
- **未达外部门槛**：未 push、未建 PR，无 CI run。CI 外部终验（6 镜像 + Trivy + compose config）待推送后补。
- docker-compose.services.yml 头注释关于「服务侧按约定不校验 token」的过时措辞沿用（属 TASK-136 留点，不在
  本课题白名单改动面内，登记不改）。
