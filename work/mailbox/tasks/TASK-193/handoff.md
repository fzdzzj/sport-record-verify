# TASK-193 harden-auth-tail 登出吊销、锁定 IP 维度与缓存多实例文档化 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-10）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧保持未推送状态。

## 1. 开工规程核验

- **工作树基线全符**：开工基线 HEAD 为 `4d939d0e7a659c9ed80458e4881b5a76bf5f2d73`（`4d939d0`，派发笔），父为 `3d8d636`（TASK-192 订正笔）；开工前 `git status --porcelain` 为空，工作区干净。
- **未推送口径**：`git rev-list --left-right --count origin/main...main` 开工实测 `0 4`（随批次待推送沿先例，执行侧不推送）。
- **受保护 token**：29 项开工实测 SUM=2030（repo 全量 `git grep -cF` 口径；TASK-191 收口真值 2030 为参照）。
- **offline 全量基线**：开工实测 `36/44/119/137/149/64/12` = 561 全绿通过。
- **静态检查门基线**：`--static=record-service` Checkstyle 811 违规持平。
- **红线逐条核验**（§0 十条）：
  1. 零触碰网关：gateway-service 全部文件零改动（C-01 与 C-02 均无 gateway 文件；未引入黑名单或网关 Redis 依赖）。
  2. logout 语义逐字：无效/已作废 refresh token 幂等返回成功（不泄有效性，delete 不被调）；有效 refresh 删存活键；Redis 异常抛 BizException(ResultCode.SYSTEM_ERROR) 500 口径不虚假成功。
  3. 阈值预注册：threshold 默认值 5→20 升级为跨 IP 累计兜底阈值；新增 `app.auth.lock.ip-threshold` 默认 5 为单 IP 组合锁阈值；window-minutes / lock-minutes / enabled 两维度共用零改动；既有键名保留。
  4. IP 提取：X-Forwarded-For 尾段 trim（网关 append 真实客户端出口 IP；代码与 README 声明信任边界）；无 XFF 取 remoteAddr；解析不出跳过 IP 维度仅走 phone 降级不阻断。
  5. 既有测试订正边界：仅「phone 维度 5 次锁全账号」相关断言用例按新语义订正，其余既有用例零改动；逐条记入本报告。
  6. F13 零逻辑：VerifyService.java 仅注释改动（常量注释 + 类 Javadoc 补充多实例语义），verify-service 测试数 149 不变。
  7. 零触碰清单遵守：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose* / web / sql/ / 父 pom / common / record-service / leaderboard-service / mapmatch-service / 仓库根 .env 全程零触碰。
  8. 性能与安全收益数字零 claim；词面门正则字面量与受保护 token 字面量不入任何 tracked 新文档。
  9. findings-summary.md 纯追加 F11/F12/F13 核实段，既有文字零改动，行号表述带 TASK-193 时点说明。
  10. 停止条件核验：未触发（offline 561 只增不减、811 持平未增、8 例单测纯 JVM 确定性落地、零触碰面严格遵守）。

## 2. 一句话结论与三支裁决

**api 模块新增 LogoutRequestDTO + user-service 新增 logout 端点（幂等主动作废 refresh 存活键、Redis 异常如实报错不虚假成功）+ login 提取 XFF 尾段 clientIp 支持单 (phone,ip) 组合锁（ip-threshold=5）与全账号兜底锁（threshold 5→20）双维度锁定 + VerifyService 补充 Caffeine 多实例本地缓存语义文档化注释（零逻辑零配置）+ README 补充登出吊销与双维度锁定说明 + 8 例纯 JVM 确定性单测全绿且既有用例对齐订正（user-service 119→127，全仓 561→569，+8）+ findings F11/F12/F13 纯追加核实段全部收口关闭，全门禁通过，判定 PASSED**；网关零改动。外部终验待推送后下一次外部门槛（第 34 次）CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | logout 吊销 refresh + 登录锁定双维度 + Caffeine 多实例语义注释 + 8 单测纯 JVM 全绿（user 127，全仓 569）+ 静态 811 持平 + findings F11/F12/F13 纯追加收口 + 网关零改动 + 全门禁绿 |
| FAILED | ✗ | 未触发（无停止条件触发、无门禁回归） |
| 外部终验 | 待推送 | 推送后下一次外部门槛（第 34 次）CI 绿；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only 4d939d0..HEAD` 逐条比对）

C-01 实施笔（恰 6 文件，网关零文件）：

- `api/src/main/java/com/sportverify/api/auth/dto/LogoutRequestDTO.java`
- `user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java`
- `user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java`
- `user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java`
- `verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java`
- `README.md`

C-02 台账笔（恰 5 文件）：

- `spec/changes/harden-auth-tail/tasks.json`
- `work/mailbox/tasks/TASK-193/spec.md`
- `work/mailbox/tasks/TASK-193/handoff.md`
- `work/mailbox/PLAN.md`
- `work/mailbox/findings-summary.md`

零触碰清单遵守：`gateway-service/` 全部文件、`ci.yml`、`mvn-verify.sh`、`mailbox-contract.sh`、`docker-compose*`、`web/`、`web/src/typed-router.d.ts`、`sql/`、父 pom、`common/`、`record-service/`、`leaderboard-service/`、`mapmatch-service/`、仓库根 `.env`、user-service 其余文件、verify-service 其余文件。`git status --porcelain` 收口后为空。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希。处置：§8 列出派发笔 `4d939d0`、C-01 `4dbf1f5` 显式哈希；C-02 自指为台账收口笔，其显式哈希以本回传报告给出（沿 TASK-182~191 先例）。 | 台账提交表终态化固有的单一自指；提交主题与任务书 §4 逐字一致。 |
| D2 | **threshold 默认值 5→20 行为变更**：既有 `app.auth.lock.threshold` 默认值从 5 改为 20，语义升级为全账号跨 IP 累计兜底阈值；新增 `app.auth.lock.ip-threshold` 默认 5 作为单 IP 组合锁阈值。处置：按 spec §0/§2 预注册设计实施；既有测试用例仅 threshold 语义相关断言订正（改传 IP 验证组合锁或累加至 20 验证全账号锁），其余用例零变动。 | 预注册设计，解除 F11 账号 DoS 痛点的必要提升，符合提案裁决。 |
| D3 | **ResultCode 500 语义码定稿**：logout 在 Redis 异常时抛出 `BizException`，核查 `ResultCode` 枚举无 `INTERNAL_ERROR`，定稿采用既有 `ResultCode.SYSTEM_ERROR`（code=9999，消息「系统繁忙，请稍后重试」）。 | 核查既有枚举定稿，符合通用异常约定与任务书要求。 |
| D4 | **词面门 repo 全量口径**：本仓历史存档 `spec/changes/archive/**` 与 `.github/workflows/ci.yml` 命中既有排除路径，按 CI 权威 exclude 口径复扫 repo 全量四形态 ZERO_HIT。本行不落任何正则字面量与受保护 token 字面量（沿 TASK-184 F1 / TASK-188 N1 教训）。 | CI 权威口径处置；改动文件集独立四形态 ZERO_HIT 已实测。 |

## 5. 实施证据（含验收要点判据）

### 5.1 实施要素摘要

- **LogoutRequestDTO.java**（新增）：`com.sportverify.api.auth.dto` 包，`refreshToken` 必填注解，Javadoc 声明登出幂等语义，纯 LF 行尾。
- **AuthController.java**：
  - 新增 `@PostMapping("/logout")`：白名单下免网关全局 token 校验，服务侧自行解析 refresh token 并在 Redis 吊销；access 15min 短窗口为声明接受权衡。
  - `login` 增加 `HttpServletRequest request` 参数，调用私有 static `clientIp(request)` 提取 IP：取 X-Forwarded-For 尾段 trim（网关 append 真实客户端出口 IP，前置段不可信，注释声明信任边界）；无 XFF 取 remoteAddr；空则返回 null（降级不阻断）。
- **AuthService.java**：
  - 新增 `logout(String refreshToken)`：JWT 解析失败/已过期 log.info 并返回（幂等，不泄漏有效性）；`stringRedisTemplate.delete` 删存活键；Redis 异常时抛 `BizException(ResultCode.SYSTEM_ERROR)`（500 语义码，不虚假成功，促客户端重试）。
  - 双维度锁定：`isLocked(phone, ip)` 检查全账号锁或单 IP 组合锁任一存在即拒；`countFailure(phone, ip)` 既有 `fail:{phone}` 达 threshold(20) 锁全账号，ip 非空时 `fail:{phone}:{ip}` 达 ip-threshold(5) 锁组合；成功清四键；`refreshPrefix` 赋默认值 `"auth:refresh:"` 保证纯 JVM 直造可用。

### 5.2 单测矩阵读数（+8 例纯 JVM 确定性单测 + 4 既有用例订正）

`AuthServiceTest`（全类 Tests run: 12, Failures: 0, Errors: 0, Skipped: 0）：

1. `logoutRevokesRefreshLiveKey`：有效 refresh 解析出 userId/jti 后，`stringRedisTemplate.delete("auth:refresh:123:jti-abc")` 被调用一次。
2. `logoutInvalidTokenStillSucceeds`：坏 token 抛 JwtException 时正常返回，delete 零调用，幂等且不泄漏有效性。
3. `logoutRedisFailureReportsError`：delete 抛异常时抛出 BizException SYSTEM_ERROR，不虚假成功。
4. `ipFailureLocksOnlyThatPhoneIpPair`：同 IP 失败 5 次触发组合锁 `auth:lock:{phone}:{ip}`，全账号锁 `auth:lock:{phone}` 未被写入（5 < 20）。
5. `phoneTotalThresholdStillLocksAccount`：换不同 IP 累计失败 20 次触发全账号兜底锁 `auth:lock:{phone}`。
6. `isLockedChecksBothDimensions`：组合锁存在拒、全账号锁存在拒、两者皆无放行。
7. `loginSuccessClearsAllFourKeys`：成功登录各调用一次 delete 清除 `fail:{phone}` / `lock:{phone}` / `fail:{phone}:{ip}` / `lock:{phone}:{ip}` 四键。
8. `nullIpSkipsIpDimension`：ip=null 仅 INCR phone 维度，IP 维度键零触碰。
9. 既有用例订正清单：
   - `setUp()`：显式对齐 `lockThreshold=20` 与 `ipLockThreshold=5`。
   - `consecutiveFailures_lockAfterThresholdAndRejectSixth`：改传 IP 验证单 IP 5 次失败写组合锁定键，第 6 次该 IP 登录被拒 403。
   - `lockExpires_loginRecoversToPasswordCheck`：改传 IP 验证组合锁定后模拟组合锁键 TTL 到期恢复登录，成功清除组合锁与计数。
   - `lockDisabled_noLockRejection`：改传 IP 验证 lockEnabled=false 时 5 次失败不写组合锁定键，第 6 次正常通过。

### 5.3 VerifyService.java（F13 多实例语义注释 diff 证据）

- 类 Javadoc「幂等」条目补充：`多实例部署各实例独立、窗口内他实例重判由消费端幂等兜住（要求严格单次判定时再立项失效广播或分布式锁）`。
- `RESULT_CACHE_PREFIX` 常量注释补充：`Caffeine 为单实例本地缓存，多实例部署各实例独立、无跨实例失效广播；窗口内他实例重判属声明接受行为，重复事件由消费端幂等（leaderboard 锚点行 + SETNX 去重）兜住。`
- 零逻辑、零配置改动；verify-service 测试数 149 不变。

### 5.4 README.md diff 证据

- 鉴权小节上线前加固清单之后插入：
  - **登出与吊销语义**：POST /api/auth/logout 携 refreshToken 即刻作废；access 15min 短窗口为声明接受权衡（RFC 6749 精神）；紧急封禁 status=0 拒新登录/刷新，已签发 access 需等自然过期——完全收敛的黑名单机制属后续可选课题。
  - **登录锁定双维度**：单 (phone,ip) 组合失败达阈值锁该组合（机主本人 IP 无感）；跨 IP 累计达兜底阈值锁全账号（防分布式爆破）；IP 取 X-Forwarded-For 尾段（网关注入，前置段不可信）。

### 5.5 findings-summary.md 核实段 diff 证据

- F11 追加核实段：引入 per-(phone,ip) 组合锁 + threshold 5→20 兜底 + XFF 尾段，行号带时点说明，F11 全部收口关闭。
- F12 追加核实段：logout 端点吊销 refresh + access 15min 短窗口文档声明，黑名单方向裁定不采用并记录理由，F12 全部收口关闭。
- F13 追加核实段：VerifyService Caffeine 多实例语义文档化完成，消费端幂等兜底重申，F13 全部收口关闭。
- `git diff --numstat work/mailbox/findings-summary.md` 实测 3 增 0 删，既有文字零改动。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `python -m json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| 词面门（改动文件集） | 四形态全 ZERO_HIT | 1（预期非零） |
| 词面门（repo 全量，CI 权威 exclude 口径） | 四形态（default / C / zh_CN.UTF-8 / C.UTF-8）全 ZERO_HIT | 1（预期非零） |
| 词面门探针三态 | state1 ZERO_HIT rc=1 / state2 探针 HIT rc=0 / state3 移除后 rc=1，PROBE_GONE=yes | 三态符合 |
| `git diff --check`（提交前） | 干净，无空白错误与 CRLF 警告 | 0 |
| 契约门在途 `--open TASK-193 --baseline=4d939d0` | 判据 A 两件套齐 + 1 待办放行 + 判据 B 清单一致 | 0 |
| 契约门无参（收口后） | 判据 A 两件套齐 + 判据 B 清单一致 | 0 |
| offline 全量 `--mode=offline test` | `36/44/127/137/149/64/12` = **569**（基线 561→569，+8 全落 user-service 119→127），Failures/Errors/Skipped 全 0，BUILD SUCCESS | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增） | 1（基线违规模块预期） |
| typed-router.d.ts 零漂移 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| token 29 项 | 开工 SUM=2030；C-01 后 SUM=2030；收口态 SUM=2030，只增不减 | 只增不减 |
| 只改清单全等 | 实际改动集恰清单（C-01 6 + C-02 5 = 11 文件，网关零文件）；`git status --porcelain` 收口后为空 | 全等 |
| 行尾核验 | 新文件（LogoutRequestDTO、handoff）pure LF；修改文件保持既有（README/user/verify CRLF，findings LF） | 符合 |

## 7. token 前后读数（口径 repo 全量 `git grep -cF`）

- **开工实测（SUM=2030，TASK-191 收口真值 2030 为参照）**：全量实测 29 项和为 **2030**。沿 TASK-188 N1 教训，本报告不枚举 29 项字面量，避免全仓计数自增失准。
- **C-01 后实测（SUM=2030）**：C-01 业务代码、单测、配置与 README 均未引入受保护 token 字面量，29 项读数逐位与开工持平，SUM=2030。
- **C-02 收口复测（SUM=2030，只增不减）**：台账 / handoff / PLAN 纯追加不引入任何受保护 token 字面量，收口态实测仍为 **SUM=2030**，只增不减。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-m` 提交无 BOM） |
| --- | --- | --- |
| 派发笔 | `4d939d0e7a659c9ed80458e4881b5a76bf5f2d73`（`4d939d0`） | `docs(spec): 派发 TASK-193 认证域 P2 尾巴打包提案与任务书` |
| C-01 实施 | `4dbf1f557dc694d255402dc080c26001bea90e90`（`4dbf1f5`） | `feat(auth): 登出吊销 refresh 与登录锁定双维度及缓存语义文档化（TASK-193）` |
| C-02 台账收口 | 本笔（自指，见 §4 D1；显式哈希以回传报告给出） | `docs(mailbox): 登记 TASK-193 认证域尾巴打包验收与台账闭环（TASK-193）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 4`、C-01 后 `0 5`、C-02 后 `0 6`（对外显式计数）。push 由指导侧另行授权执行，执行侧保持未推送状态。

## 9. 未覆盖项

1. **本课题全离线可验证，无 UNDETERMINED 项**：单测覆盖 logout 删存活键/幂等处理/Redis 异常 500、组合锁 5 次仅锁组合、总阈值 20 次锁全账号、查双维度锁、成功清四键、null ip 跳过 IP 维度等纯 JVM 确定性场景，不依赖外部容器或真实 Redis 运行。
2. **运行时真机验证沿口径可选登记**：网关白名单放行 `/api/auth/**` 下 logout 端点免全局 token 校验，由 user-service 解析 refresh token；单网关一跳拓扑下 XFF 尾段即真实客户端 IP。
3. **性能与安全收益数字零 claim**（纪律遵守）：本变更只登记机制性事实（refresh 主动吊销、单 IP 组合锁与跨 IP 兜底锁、Caffeine 多实例语义声明），不写任何量化收益数字。
