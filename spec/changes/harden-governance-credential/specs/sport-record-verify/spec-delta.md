# spec-delta：治理面签名凭证升级（TASK-201 harden-governance-credential）

## ADDED 需求：治理面签名凭证编解码（common GovernanceCredentialCodec）

common 模块 SHALL 新增静态工具 `GovernanceCredentialCodec`：`sign(key, epochSeconds, role)` 产出 `v1.<epochSeconds>.<role>.<sigHex>`（HMAC-SHA256，内容域 `governance-v1|<ts>|<role>`，sig 小写 hex）；`verify(key, headerValue, maxSkewSeconds, nowEpochSeconds)` 依次校验四段格式 / 常量时间签名比对（MessageDigest.isEqual）/ `|now-ts| <= maxSkewSeconds` 时间窗，全过返回 role，任一不符返回 null（拒绝原因不外泄）；`isUsableKey(key)` 判非空、非哨兵 `__GOVERNANCE_TOKEN_UNSET__`、UTF-8 字节数 >= 32。网关与服务侧 SHALL 共用本实现（防两端格式漂移）。验收：纯 JVM 单测覆盖签名往返、篡改拒绝、过期/超窗拒绝、角色不符拒绝、弱密钥拒绝、格式垃圾拒绝、域分隔（同密钥下其它内容域签名不得通过）。

## MODIFIED 需求：网关治理凭证注入（gateway AuthGlobalFilter）

网关 `AuthGlobalFilter` 治理面注入段（原 `headers.set(HEADER_GOVERNANCE_TOKEN, governanceToken)` 静态值）SHALL 改为每请求 `GovernanceCredentialCodec.sign(governanceToken, System.currentTimeMillis()/1000, identity.role())`；注入前提不变（auth.enabled + JWT 有效 + role=ADMIN + admin.enabled + 凭证有效，任一不成立失败关闭）。`init()` strict 校验与请求期 `isValidGovernanceToken` SHALL 改用 `GovernanceCredentialCodec.isUsableKey`；strict fail-fast（strict + 密钥不可用拒启）语义等价保持。配置键 `app.governance.token`（环境变量 `GOVERNANCE_TOKEN`）沿用，值语义升级为 HMAC 密钥。验收：网关测试断言注入的 header 值经 `codec.verify` 通过且 role=ADMIN；既有 strict/失败关闭/伪造剥离用例等价迁移全绿。

## MODIFIED 需求：服务侧治理面角色断言（common GovernanceApiAuthFilter）

服务侧 `GovernanceApiAuthFilter` 校验段 SHALL 由静态值比对改为：`codec.verify(hmacKey, headerValue, maxSkewSeconds, nowEpochSeconds)` 返回非 null 且 `GovernanceApiHeaders.ROLE_ADMIN.equals(role)` 方放行，否则 403（失败关闭、哨兵/未配置拒绝、`app.governance.auth-enabled=false` 时仅非保护路径放行等既有语义等价保持）。`GovernanceApiHeaders` SHALL 新增 `ROLE_ADMIN = "ADMIN"` 常量并更新 javadoc（剩余风险描述由「非签名、无重放保护、无绑定 caller」改记为「时间窗内可重放、不绑路径，v1 声明接受」）。时间窗配置 `app.governance.max-skew-seconds` 默认 300（verify/leaderboard yml 显式化）。服务侧不以 `X-Role` 头作授权依据的现状保持（该头仍由网关注入业务面透传，治理面不消费）。验收：红绿判别式——旧静态正确值 403、role=USER 签名 403、过期时间戳 403、篡改签名 403、伪造 X-Role + 坏凭证 403、合法 ADMIN 签名三条 protected path 全放行；无 protected-paths 服务（user/record/mapmatch）上下文装配与 strict 启动语义零回归。

## MODIFIED 需求：治理凭证部署链（env.example / docker-compose.services.yml / README）

`scripts/verify/env.example` SHALL 补 `GOVERNANCE_TOKEN` 占位值（SvLocal-<组件>-<8位hex>-ChangeMe 模式，>=32 字节，禁复用本机 .env 实值）；`docker-compose.services.yml` 的 gateway/verify/leaderboard 三服务 environment 段 SHALL 各加 `GOVERNANCE_TOKEN: ${GOVERNANCE_TOKEN:?required: cp scripts/verify/env.example .env}` 显式插值（对齐 TASK-190 口径；user/record/mapmatch 不消费该变量零改动）。README 鉴权/密钥章节 SHALL 补签名凭证语义（部署前提：网关与 verify/leaderboard 同批重启；跨机部署需 NTP 对时）。验收：`docker compose -f docker-compose.yml -f docker-compose.services.yml config -q` 在 placeholder .env（env.example 复制）下通过；CI compose config 门自动覆盖。

## 验收断言

- offline 基线 609（36/48/130/151/159/70/15）只增不减：common/gateway 允许净增，user/record/verify/leaderboard/mapmatch 五模块逐位不变（verify/leaderboard 仅 yml 注释与配置行，零测试面变化）
- static 双模块（leaderboard 缺省 + record）rc=0；common/gateway 不在静态门覆盖内（既有口径，登记不改）
- 契约门双态 rc=0；token 29 项 SUM=2030 只增不减；词面门全过（新文档禁字面正则模式）
- 只改清单：C-01 恰 13 文件（codec 新增 + headers + filter + 网关 filter + 两测试类新增/改造 + 三 yml + compose services + env.example + README）；C-02 恰 4 台账文件
- 零触碰：ci.yml、mvn-verify.sh、mailbox-contract.sh、docker-compose.yml、web、sql、父 pom、api 模块、user/record/mapmatch 全部文件、docs/adr/**、.trivyignore、spec/specs 本体、归档目录
- CI 外部终验：6 镜像构建（common 改动进全镜像）+ Trivy 收缩态 + compose config（placeholder .env 含新变量）全绿
