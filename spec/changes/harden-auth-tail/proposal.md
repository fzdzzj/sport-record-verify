# TASK-193 harden-auth-tail 提案：登出吊销、锁定 IP 维度与缓存多实例文档化

## Why（现状与痛点）

findings P2 尾巴三项打包（用户裁决「P2 尾巴打包」；F23 经 TASK-192 订正笔确认已收口不并入，F21 属 CI 工程面另立）：

- **F12 无登出/吊销（部分）**：refresh token 已 jti 轮换（AuthService.java:206-226，GETDEL_LUA 原子消费），但**无主动吊销入口**（logout 端点不存在，AuthController 仅 register/login/refresh 三端点）；access token 15min 时效内不可吊销且窗口权衡未文档声明。用户裁决「按真实开发质量」→ 采用 RFC 6749 精神标准解：access 无状态短命（窗口声明接受）+ refresh 有状态可吊销（补 logout 端点即刻作废存活键）；网关引 Redis 黑名单被裁掉（网关新增强依赖属新故障面，非真实生产优选）
- **F11 登录锁定可被滥用做账号 DoS（仍在）**：countFailure 按 phone 计数（阈值 5/窗口 15min，AuthService.java:264-288）→ 达阈值锁 `auth:lock:{phone}` **全账号**（:275）——攻击者对任意手机号试错 5 次即锁机主 15min；login 链路无 IP 概念（AuthController.java:49 无 HttpServletRequest、AuthService.java:130 单参）。用户裁决 **per-(phone,ip) 锁 + phone 总阈值兜底**
- **F13 判定结果缓存多实例不一致未文档化（部分）**：Caffeine 1min 本地缓存（VerifyService.java:60-61 常量 + :70 注入），消费端幂等兜底结论在 findings 在案，但代码注释未声明多实例语义（findings 原文建议「文档化」，不接失效广播）

## What（方案）

**logout 端点吊销 refresh + access 窗口文档声明 + 登录锁定双维度（per-IP 锁 + 总阈值兜底）+ Caffeine 多实例语义注释**，改动面 6+5 文件，零网关改动。

| 决策点 | 裁决 | 理由 |
| --- | --- | --- |
| F12 吊销形态 | **logout 吊销 refresh + access 15min 窗口文档声明**（不引 access jti 黑名单） | RFC 6749 精神：access 无状态短命是声明接受的权衡，refresh 有状态可吊销；网关查黑名单需引 Redis（响应式改造 + 新强依赖 + 碰 TASK-191 刚收口的敏感面），收益窗口仅 15min，非真实生产优选 |
| logout 语义 | POST /api/auth/logout {refreshToken}：解析成功 → 删 `auth:refresh:{userId}:{jti}` 存活键即刻作废；**无效/已作废 token 也返回成功**（幂等，不泄 token 有效性）；Redis 删除异常 → 500 口径如实反馈（不虚假成功，客户端可重试） | 登出幂等是真实安全实践（不泄有效性）；删键失败假装成功属「虚假登出」，如实报错让客户端重试才诚实 |
| 端点鉴权 | logout 在网关白名单 `/api/auth/**` 下免网关校验，user-service 自行 parseRefresh（JwtUtil 在本服务） | 发 token 域沿既有三端点同构口径，零网关改动 |
| F11 锁形态 | **双维度**：`auth:fail:{phone}:{ip}` 达 ip-threshold（新键，默认 5）→ 锁 `auth:lock:{phone}:{ip}`（仅该组合 15min，机主本人 IP 无感）；既有 `auth:fail:{phone}` 跨 IP 累计达 threshold（默认 **5→20**，语义升级为兜底阈值）→ 锁 `auth:lock:{phone}`（全账号兜底，防分布式换 IP 爆破单账号） | per-(phone,ip) 精准锁解除 DoS（陌生 IP 试错不影响机主）；总阈值兜底防「每 IP 5 次」的分布式绕过；NAT 出口互不误伤（组合键含 phone）；threshold 默认值 5→20 是防 DoS 的必要提升（5 次即全锁正是 F11 痛点） |
| IP 来源 | AuthController.login 加 HttpServletRequest：`X-Forwarded-For` **尾段**（网关 SCG append 真实客户端出口 IP 到尾部；前置段可伪造不可信）→ 无 XFF 取 remoteAddr；解析不出 → 跳过 IP 维度仅走 phone（降级不阻断） | 尾段=最近一跳（网关注入），单网关一跳拓扑下即真实客户端地址；直连场景（无网关）remoteAddr 兜底 |
| F13 形态 | VerifyService 常量注释 + 类 Javadoc 补多实例语义（窗口内他实例重判由消费端幂等兜住 + 重开条件），**零逻辑改动** | findings 原文建议文档化；失效广播/分布式锁属过度设计（消费端幂等已在位） |

**改动面（C-01 6 文件 + C-02 5 文件）**：

| 层 | 改动 |
| --- | --- |
| api 契约 | LogoutRequestDTO.java 新增（沿 RefreshRequestDTO 模式：refreshToken 必填） |
| user 认证 | AuthController.java：+logout 端点；login 加 HttpServletRequest 提取 clientIp（XFF 尾段） |
| user 认证 | AuthService.java：+logout（parseRefresh → 删存活键，幂等 + Redis 异常 500）；login(dto, ip) 双参；countFailure/isLocked 双维度；成功清 4 键；threshold 默认 5→20 + 新键 ip-threshold=5 |
| user 测试 | AuthServiceTest.java：+logout 3 例 + 双维度 5 例；既有 threshold 相关用例按新语义订正（预注册，见任务书） |
| verify 注释 | VerifyService.java：RESULT_CACHE_PREFIX 常量注释 + 类 Javadoc 补多实例语义（F13，零逻辑） |
| 文档 | README.md：鉴权/token 小节补「登出与吊销语义」（logout 吊销 refresh、access 15min 窗口声明、紧急封禁边界：status=0 拒新登录但已签发 access 需等自然过期）+「登录锁定双维度」说明 |
| 台账（C-02） | tasks.json 全勾 + 任务书 §7 回填 + handoff.md 新建 + PLAN.md 纯追加 + findings-summary.md F11/F12/F13 核实段 |

**单测（+8 预计，纯 JVM 确定性 mock StringRedisTemplate，落 user-service 119→127 以实测为准）**：

| 用例 | 断言 |
| --- | --- |
| logoutRevokesRefreshLiveKey | 有效 refresh → delete(auth:refresh:{userId}:{jti}) 被调，正常返回 |
| logoutInvalidTokenStillSucceeds | 解析失败 → 正常返回（幂等），delete 未被调，不泄有效性 |
| logoutRedisFailureReportsError | delete 抛异常 → BizException（500 口径），不虚假成功 |
| ipFailureLocksOnlyThatPhoneIpPair | 同 (phone,ip) 失败达 ip-threshold → set(auth:lock:{phone}:{ip}) 被调，set(auth:lock:{phone}) 未被调（未达总阈值） |
| phoneTotalThresholdStillLocksAccount | 跨 IP 累计达 threshold(20) → set(auth:lock:{phone}) 兜底全账号锁 |
| isLockedChecksBothDimensions | 组合锁存在 → 拒；全账号锁存在 → 拒；两者皆无 → 放行 |
| loginSuccessClearsAllFourKeys | 成功登录 → fail:{phone} / lock:{phone} / fail:{phone}:{ip} / lock:{phone}:{ip} 四键全删 |
| nullIpSkipsIpDimension | ip=null → 仅 phone 维度计数，IP 维度键零触碰（降级不阻断） |

## 边界（明确不做）

- **零触碰网关**（gateway-service 全部文件——本课题零网关改动；access 黑名单被裁掉）
- 零触碰：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose*（含 services/perf）/ web / sql/ / 父 pom / common / record-service / leaderboard-service / mapmatch-service / 仓库根 .env
- verify-service **仅 VerifyService.java 注释**（F13 零逻辑）；user-service 仅 AuthController / AuthService / AuthServiceTest 三文件
- 不引入 access jti 黑名单 / 网关 Redis 依赖 / Spring profile / 新 Maven 依赖
- 既有 `auth:fail:{phone}` / `auth:lock:{phone}` 键名保留（语义升级不改键）；窗口/锁定时长配置（window-minutes / lock-minutes / enabled）两维度共用零改动
- F21（CI 增补）/ F04/F14/F22（语义维持）不并入；不 claim 任何安全收益数字（无量化评估依据，只登记机制性事实）

## 风险

| 风险 | 缓解 |
| --- | --- |
| threshold 默认 5→20 属行为变更，既有测试可能红 | 预注册订正范围：仅「phone 维度 5 次锁全账号」断言的既有用例按新语义调整（组合锁 5 次/全账号锁 20 次），其余用例零改动；任务书列明订正边界，防扩大化 |
| X-Forwarded-For 可被客户端伪造前置段 | 只取尾段（网关 append 的真实客户端出口 IP）；信任边界在代码注释与 README 声明；phone 总阈值兜底伪造 XFF 的分布式绕过 |
| logout 在白名单下免网关校验，被滥用于探测 token 有效性？ | 幂等设计恰好反探测：无效/有效 token 响应一致（都 200 + success），不泄有效性 |
| logout 删键失败后 refresh 残留可轮换 | Redis 异常时 refresh 轮换本身也依赖 Redis（GETDEL 同库）——故障窗口内轮换同样不可用；恢复后残留键可被后续 logout 重试删除；500 反馈促客户端重试 |
| 双维度计数增加登录失败路径 Redis 操作（2 INCR + TTL） | 失败路径才计数（成功路径只清键）；Redis 异常整体降级不阻断登录（沿既有口径） |

## 验收（摘要）

logout + 双维度共 8 例新单测全绿（user-service 119→127 预计）；Java offline 全量 561 只增不减（预计 569=36/44/127/137/149/64/12 以实测为准）；`--static=record-service` 811 不变（record 零触碰天然满足）；verify-service 149 不变（F13 纯注释零新测试）；零网关改动经 `git diff --name-only` 核验；词面门 ZERO_HIT；token 29 项只增不减；契约门 rc=0；findings F11/F12/F13 核实标注与代码实态逐字一致；README 登出/锁定语义与实现一致；CI 下一次外部门槛绿（第 34 次）。
