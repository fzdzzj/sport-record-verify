# spec-delta：登出吊销、锁定 IP 维度与缓存多实例文档化（TASK-193 harden-auth-tail）

## ADDED 需求：登出端点（refresh 主动吊销）

POST /api/auth/logout（网关白名单 /api/auth/** 下免网关校验，user-service 自行解析）：请求体 {refreshToken 必填}；解析成功 → 删 `auth:refresh:{userId}:{jti}` 存活键，refresh 即刻作废（此后轮换 401）；无效/已作废 token 同样返回成功（幂等，不泄 token 有效性）；Redis 删除异常 → 500 口径如实反馈（不虚假成功，客户端可重试）。

### 变更前

无登出端点；refresh 存活键仅在轮换时被原子消费（AuthService.refresh），用户无主动吊销入口。

### 变更后

AuthController 新增 logout 端点 + AuthService 新增 logout 方法（沿 parseRefresh → 删存活键路径）；api 模块新增 LogoutRequestDTO（refreshToken 必填）。

## MODIFIED 需求：access token 吊销边界（文档声明）

### 变更前

access token 15min 时效内不可吊销，窗口权衡未在任何文档声明（findings F12「部分」的未决半边）。

### 变更后

README 声明：access 无状态短窗口（15min）为设计权衡（RFC 6749 精神：access 短命无状态、refresh 有状态可吊销）；登出后 access 残留窗口接受；紧急封禁（user.status=0）拒新登录/刷新，已签发 access 需等自然过期——完全收敛的黑名单机制属后续可选课题（网关引 Redis 被裁定不采用：新强依赖属新故障面，收益窗口仅 15min）。

## MODIFIED 需求：登录失败锁定双维度（F11 收口）

### 变更前

单维度：`auth:fail:{phone}` 窗口内计数达 threshold（默认 5）→ 锁 `auth:lock:{phone}` 全账号 15min——攻击者对任意手机号试错 5 次即锁机主（账号 DoS）。

### 变更后

双维度（window-minutes / lock-minutes / enabled 两维度共用）：
- **组合锁（防单 IP 骚扰）**：`auth:fail:{phone}:{ip}` 计数达 ip-threshold（新键 app.auth.lock.ip-threshold，默认 5）→ 锁 `auth:lock:{phone}:{ip}`——仅该 (phone,ip) 组合被拒 15min，机主本人 IP 无感；
- **全账号兜底锁（防分布式爆破）**：`auth:fail:{phone}` 跨 IP 累计达 threshold（既有键，默认 **5→20**，语义升级为兜底阈值）→ 锁 `auth:lock:{phone}`；
- IP 来源：login 入口取 X-Forwarded-For **尾段**（网关 append 的真实客户端出口 IP；前置段可伪造不可信），无 XFF 取 remoteAddr；解析不出跳过 IP 维度仅走 phone；
- 登录成功清 4 键（两 fail + 两 lock）。

## MODIFIED 需求：判定结果缓存多实例语义文档化（F13 收口）

### 变更前

VerifyService RESULT_CACHE_PREFIX Caffeine 1min 本地缓存，多实例独立、无跨实例失效广播——语义未在代码注释声明（消费端幂等兜底结论仅在 findings 在案）。

### 变更后

常量注释 + 类 Javadoc 声明：Caffeine 单实例本地缓存，多实例部署各实例独立；窗口内他实例重判属声明接受的行为，重复 VERIFIED/REJECTED 事件由消费端幂等（leaderboard 锚点行 + SETNX 去重）兜住；重开条件：要求严格单次判定时再立项失效广播或分布式锁。零逻辑零配置改动。

## 验收断言

- 单测（纯 JVM 确定性 mock StringRedisTemplate，user-service +8 例 119→127 预计）：logout 3 例（删存活键 / 无效 token 幂等成功且不删键 / Redis 异常 500 不虚假成功）+ 双维度 5 例（组合锁只锁该组合 / 总阈值兜底全账号 / isLocked 查双维度 / 成功清 4 键 / null ip 跳过 IP 维度）；既有用例仅 threshold 语义相关订正，其余零改动。
- offline 全量 561 只增不减（预计 569=36/44/127/137/149/64/12）；`--static=record-service` 811 不变（record 零触碰）。
- 只改清单：C-01 恰 6 文件（api LogoutRequestDTO 新增 / AuthController.java / AuthService.java / AuthServiceTest.java / VerifyService.java 仅注释 / README.md），**网关零文件**；C-02 恰 5 文件（tasks.json / 任务书 spec.md §7 / handoff.md / PLAN.md / findings-summary.md）。
- 零触碰：gateway-service 全部 / ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose* / web / sql/ / 父 pom / common / record-service / leaderboard-service / mapmatch-service / 仓库根 .env / verify-service 除 VerifyService.java 注释外 / user-service 除三文件外。
- findings-summary.md：F11/F12/F13 纯追加核实段（TASK-193 时点行号），F12 记录黑名单方向裁定不采用及理由。
- 台账与 handoff 不含安全收益数字、不枚举受保护 token 字面量；threshold 默认值变更（5→20）作为行为变更偏差显式登记。
