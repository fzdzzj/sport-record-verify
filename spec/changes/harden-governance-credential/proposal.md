# 提案：治理面服务侧二道防线——签名凭证升级（harden-governance-credential / TASK-201）

课题1拍板立项（2026-10-11）：治理端点直连服务端口可绕过网关 ADMIN 角色校验，服务侧仅静态凭证过滤。本提案把治理凭证从「静态共享密码」升级为「HMAC 签名凭证（绑定时间戳与角色）」，服务侧获得「网关已认定 ADMIN」的密码学证明——二道防线成立。

## 背景与问题

- **直连面实证**：治理面 ADMIN 校验唯一落点在网关 `AuthGlobalFilter`（admin.paths 四路径）；容器网络内 `http://verify-service:8083/rules/**` 可直连绕开。TASK-136 第二阶段已为服务侧加 `GovernanceApiAuthFilter`（common，护 verify `/api/appeals/**,/rules/**` 与 leaderboard `/api/leaderboard/daily` 共 5 端点），但凭证形态为静态共享密钥（`X-Gateway-Governance-Token`，`MessageDigest.isEqual` 比对）。
- **静态凭证三重剩余风险**（`GovernanceApiHeaders` javadoc 自认）：非签名、无重放保护、无绑定 caller、持有者可复用——① 嗅探/泄漏一次即**永久**可直连治理端点；② 凭证无角色语义，服务侧只验「知道密钥」不验「网关认定 ADMIN」，`X-Role` 头可伪造不可信任（F23 实证全仓服务侧 X-Role 读取点 0 恰是现状，但防线本身不含角色语义）；③ 治理端点全为高危写操作（规则版本创建/灰度/全量发布、申诉终判）。
- **部署缺口**（顺带收口）：`docker-compose.services.yml` 未注入 `GOVERNANCE_TOKEN`（environment 段零命中）、`scripts/verify/env.example` 无任何应用级密钥占位——容器栈治理面当前必然失败关闭（403），凭证机制从未在容器路径可用。
- **登记溯源**：findings-summary.md F23（L123-139，TASK-130 发现）、PLAN.md L255/L272（「治理面只在网关判，直连 8084 可绕」「属另一次拍板」）。

## 方向拍板：A 签名凭证（主路径）vs B 服务侧验 JWT（备选，不推荐）

**方案A（本提案主路径）：网关 HMAC 注签、服务侧验签 + 角色断言。**
维持 ADR-0007「身份由网关认定」边界：服务侧不重复认定身份，只密码学验证「网关已认定该请求为 ADMIN」这一事实。服务侧零 JWT 密钥扩面（仍只有 user 签发 / gateway 验签）。

**方案B（备选，不选理由）：服务侧复验 access token role claim。**
- HS256 对称密钥必须扩面到 verify/leaderboard——任何服务泄漏密钥即可**伪造任意 ADMIN token**（当前仅 user 可签），安全倒退；
- 改 RS256 非对称则需动 user 签发端 + 网关验签 + 全量 token 语义与测试，爆炸半径扩大到全部业务面；
- 网关已验过一次 JWT，服务侧再验属重复认定，与 ADR-0007 单点认定边界冲突；
- 网关 `auth.enabled=false` 时治理面已失败关闭，服务侧二验无增量场景。

**方向③网络策略（不覆盖）**：compose 网络隔离只管容器面，宿主机 `java -jar` 直跑（F20 当前主路径，`server.address` 全仓 0 命中默认绑 0.0.0.0）不受网络策略约束；应用侧凭证是纵深防御必需层。

## 方案A设计（预注册要点，细目见任务书 §2）

- **凭证格式** `v1.<epochSeconds>.<role>.<sigHex>`，`sig = HMAC-SHA256(key, "governance-v1|<ts>|<role>")` 小写 hex；`v1` 前缀留演进空间（未来 v2 可加 nonce/路径绑定）。
- **新增 common 静态工具 `GovernanceCredentialCodec`**：`sign(key, epochSeconds, role)` / `verify(key, headerValue, maxSkewSeconds, nowEpochSeconds)` → role 或 null（格式/常量时间签名/时间窗三重校验）/ `isUsableKey(key)`（非空、非哨兵、UTF-8 ≥32 字节，对齐 `JwtTokenParser` 密钥强度先例）。网关与服务共用同一实现，防两端格式漂移。
- **网关 `AuthGlobalFilter`**：治理面注入段由静态值改为每请求 `codec.sign(governanceToken, now, identity.role())`（HMAC 微秒级，event loop 内 jjwt 验签已有阻塞先例）；`init()` strict 校验改用 `isUsableKey`。
- **服务侧 `GovernanceApiAuthFilter`**：校验段改为 `codec.verify` + 断言 `role == ADMIN`（常量入 `GovernanceApiHeaders.ROLE_ADMIN`）；时间窗 `app.governance.max-skew-seconds`（默认 300）；strict fail-fast / 失败关闭 / 哨兵拒绝语义全部等价保持。
- **配置键不变**：`app.governance.token` / `GOVERNANCE_TOKEN` 环境变量沿用，值语义升级为 HMAC 密钥（≥32 字节）；**无双轨期**，旧静态格式在新验签下天然 403，部署前提=网关与 verify/leaderboard 同批重启。
- **部署链收口**：env.example 补 `GOVERNANCE_TOKEN` 占位（SvLocal 模式，38 字节）；compose services 三服务（gateway/verify/leaderboard）environment 显式插值（TASK-190 `${VAR:?required}` 口径）——容器栈治理面从「必然 403」变为「可用且签名态」，CI compose config 门经 placeholder .env 自动覆盖。

**声明接受的权衡**：时间窗内（300s）同凭证可重放、不绑路径（网关外部路径与 StripPrefix 后服务路径不一致，绑定即失配）；相比现状「静态值永久重放」已是数量级收紧，治理面低频（规则变更/终判/日报），严格防重放（nonce 存储）引入 Redis 故障耦合，列为后续课题不做。

## 验收核心

红绿判别式：旧静态正确值 → 403；签名凭证 role=USER → 403；过期时间戳 → 403；篡改签名 → 403；伪造 X-Role + 坏凭证 → 403；合法 ADMIN 签名凭证三条 protected path 全放行；网关注入值经 codec 验签往返通过。offline 609 逐位基线上只增不减（common/gateway 净增、其余五模块逐位不变）；static 双模块 rc=0；契约门双态；token 2030 持平；compose config 双栈通过；CI 6 镜像构建 + Trivy 收缩态全绿。
