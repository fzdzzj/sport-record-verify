# TASK-193 harden-auth-tail 任务书（登出吊销、锁定 IP 维度与缓存多实例文档化）

## 0. 红线（违任一条即 FAILED 停手回报）

1. **零触碰网关**：gateway-service 全部文件零改动（git diff --name-only 核验零 gateway 文件）；access jti 黑名单方向已裁定不采用，禁止以任何形式给网关引入 Redis/黑名单查询
2. **logout 语义逐字**：无效/已作废 refresh token 也返回成功（幂等，不泄 token 有效性，delete 不被调）；有效 refresh → 删 `auth:refresh:{userId}:{jti}`；Redis 删除异常 → 500 口径（ResultCode 既有内部错误语义码，执行侧核枚举定稿）**不虚假成功**
3. **阈值预注册**：threshold 默认值 5→**20**（语义=跨 IP 累计兜底阈值，达 → 锁 `auth:lock:{phone}` 全账号）；新键 `app.auth.lock.ip-threshold` 默认 **5**（单 (phone,ip) 组合阈值，达 → 锁 `auth:lock:{phone}:{ip}`）；window-minutes / lock-minutes / enabled 两维度共用零改动；既有键名 `auth:fail:{phone}` / `auth:lock:{phone}` 保留不改名
4. **IP 提取**：X-Forwarded-For **尾段**（trim，网关 append 的真实客户端出口 IP；前置段可伪造不可信——注释声明信任边界）；无 XFF 取 remoteAddr；null/blank → 跳过 IP 维度仅走 phone（降级不阻断登录）
5. **既有测试订正边界**：仅「phone 维度 5 次锁全账号」断言的既有用例按新语义订正（组合锁 5 次 / 全账号兜底 20 次口径），其余既有用例零改动；订正用例逐条列入 handoff
6. **F13 零逻辑**：VerifyService.java 仅注释改动（常量注释 + 类 Javadoc 补多实例语义），零代码逻辑/配置改动；verify-service 测试数 149 不变
7. 零触碰清单：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose*（含 services/perf）/ web / sql/ / 父 pom / common / record-service / leaderboard-service / mapmatch-service / 仓库根 .env / user-service 除 AuthController / AuthService / AuthServiceTest 外 / verify-service 除 VerifyService.java 外
8. 性能与安全收益数字零 claim；词面门正则字面量与受保护 token 字面量不入任何 tracked 新文档（TASK-184 F1 / TASK-188 N1 教训）
9. findings-summary.md 更新仅纯追加核实段（F11/F12/F13），既有文字零改动；行号表述带 TASK-193 时点说明
10. 停止条件：offline 基线 561（36/44/119/137/149/64/12）回退 / 811 增 / logout 或双维度单测无法纯 JVM 确定性落地 / 需触碰任一零触碰面（含网关任一文件）

## 1. 背景与侦察实证（指导侧已亲核，2026-10-10）

- **F12 现状**：refresh jti 轮换在位（AuthService.java:206-226 refresh + :218 GETDEL_LUA 原子消费 + :241-244 存活键写入）；无 logout 端点（AuthController.java:39-60 仅 register/login/refresh 三端点）；access 窗口权衡未文档化。网关白名单默认 `/api/auth/**`（AuthGlobalFilter.java:55）→ logout 端点免网关校验，user-service 自行 parseRefresh（JwtUtil.java:117-123）。**网关无 Redis 依赖**（gateway pom 无 spring-boot-starter-data-redis）——黑名单方案成本实证
- **F11 现状**：countFailure(phone)（AuthService.java:264-288）INCR `auth:fail:{phone}` + 首次设 TTL（:267-271）→ 达 threshold（:273）set `auth:lock:{phone}`（:275）；isLocked(phone)（:253-261）hasKey 判定；login 成功清两键（:153-159）；login 单参无 IP（:130，AuthController.java:49 无 HttpServletRequest）；配置 app.auth.lock.*（:66-77：enabled/threshold=5/window-minutes=15/lock-minutes=15）；Redis 异常降级不阻断登录（:256-260/:284-287 既有口径）
- **F13 现状**：VerifyService.java:60-61 RESULT_CACHE_PREFIX 常量（注释仅「压测 P95 达标关键」无多实例语义）+ :70 Cache 注入；消费端幂等兜底结论 findings 在案
- **模块计数归属**：offline 七数 36/44/119/137/149/64/12 → common=36、gateway=44、**user=119**、record=137、verify=149、leaderboard=64、mapmatch=12（api=0）；+8 预期 user 119→127、全量 569
- **行尾基线**（执行侧开工实测 `git ls-files --eol` 确认）：README.md 工作区 CRLF；findings-summary.md LF；新文件（LogoutRequestDTO / handoff.md / 四件套）LF + 末尾换行；AuthController / AuthService / AuthServiceTest / VerifyService 按仓库既有行尾
- **基线**：开工 HEAD=3d8d636（TASK-192 订正笔，随批次待推送沿先例），origin/main...main=0 3，工作区干净；offline 561（36/44/119/137/149/64/12）；静态 811；token 29 项收口真值 2030 参照

## 2. 预注册实施设计

### 2.1 api 模块 LogoutRequestDTO.java（新增）

com.sportverify.api.auth.dto 包，沿 RefreshRequestDTO 模式（@Data + 字段 + 校验注解）：`private String refreshToken;` 必填校验；Javadoc 说明登出幂等语义（无效 token 也成功）。LF 行尾。

### 2.2 AuthService.logout(String refreshToken)

```java
public void logout(String refreshToken) {
    JwtUtil.ParsedRefresh parsed;
    try {
        parsed = jwtUtil.parseRefresh(refreshToken);
    } catch (JwtException e) {
        // 幂等：无效/过期 token 同样返回成功——登出不泄 token 有效性
        log.info("登出幂等处理（token 无效或已过期）");
        return;
    }
    try {
        stringRedisTemplate.delete(refreshKey(parsed.userId(), parsed.jti()));
    } catch (Exception e) {
        // 不虚假成功：删键失败 refresh 仍可轮换，如实报错让客户端可重试
        log.error("登出吊销失败（Redis 异常）：userId={}", parsed.userId(), e);
        throw new BizException(<ResultCode 既有 500 语义码>);
    }
    log.info("登出成功：refresh 即刻作废 userId={}", parsed.userId());
}
```

（delete 返回 false = 键已不存在（已轮换/已吊销），仍正常返回——幂等。）ResultCode 500 语义码执行侧核枚举（如 INTERNAL_ERROR / SYSTEM_ERROR 实名）后定稿并在 handoff 登记。

### 2.3 AuthController：logout 端点 + login 传 IP

- `@PostMapping("/logout")` → `authService.logout(dto.getRefreshToken())` → `Result.success()`；Javadoc：白名单下服务侧自行解析 + access 15min 窗口声明引用 README
- login 签名加 `HttpServletRequest request`；私有 static clientIp(request)：X-Forwarded-For 存在且非空 → 取**最后一个逗号后段** trim（网关 append 的真实客户端出口 IP，前置段不可信）；否则 remoteAddr。注释声明信任边界（单网关一跳拓扑）

### 2.4 AuthService 登录锁定双维度

- `login(LoginRequestDTO dto, String ip)`（原单参签名保留委托或直接改签名——执行侧按测试面最小改动定，handoff 登记）
- isLocked(phone, ip)：`hasKey(lockKey(phone)) || hasKey(ipLockKey(phone, ip))` 任一即拒（异常降级不锁沿 :256-260）
- countFailure(phone, ip)：
  - 既有 `auth:fail:{phone}` INCR + 首次 TTL 保留；达 threshold(20) → set `auth:lock:{phone}`（log.warn 兜底口径）
  - ip 非空：INCR `auth:fail:{phone}:{ip}` + 首次 TTL（同窗口）；达 ip-threshold(5) → set `auth:lock:{phone}:{ip}`（log.warn 组合锁口径）
  - Redis 异常整体降级仅告警（沿 :284-287）
- 登录成功清 4 键：fail:{phone} / lock:{phone} / fail:{phone}:{ip} / lock:{phone}:{ip}（ip 为空跳过后两键）
- threshold @Value 默认值 5→20 + 注释语义升级；新增 `@Value("${app.auth.lock.ip-threshold:5}")`；类 Javadoc「登录」条目补双维度说明
- 键方法：`ipFailKey(phone, ip)` / `ipLockKey(phone, ip)` 沿既有命名族

### 2.5 AuthServiceTest（+8 例 + 既有订正）

mock StringRedisTemplate 纯 JVM（沿既有测试构造模式）：

| 用例 | 断言要点 |
| --- | --- |
| logoutRevokesRefreshLiveKey | 有效 refresh → delete(refreshKey) 被调一次 |
| logoutInvalidTokenStillSucceeds | 篡改 token（坏签名/非 refresh）→ 正常返回，delete 零调用 |
| logoutRedisFailureReportsError | delete 抛 RuntimeException → assertThrows BizException |
| ipFailureLocksOnlyThatPhoneIpPair | countFailure(phone, ipA) 调 5 次（ip 计数 INCR 返回 1..5）→ set(lock:{phone}:{ipA}) 被调；set(lock:{phone}) 未被调（phone 计数 5 < 20） |
| phoneTotalThresholdStillLocksAccount | 同 phone 换 ipA/ipB/ipC... 累计 20 次（phone 计数达 20）→ set(lock:{phone}) 兜底被调 |
| isLockedChecksBothDimensions | hasKey(组合锁)=true → 拒；组合 false + hasKey(全账号)=true → 拒；两者 false → 放行 |
| loginSuccessClearsAllFourKeys | 登录成功 → delete 四键各一次 |
| nullIpSkipsIpDimension | ip=null → 仅 phone 维度键被 INCR，ip 维度键零触碰 |

既有订正：断言「phone 5 次失败 → auth:lock:{phone} 被写」的用例改为新语义（5 次写组合锁 / 20 次写全账号锁）；逐条列 handoff。

### 2.6 VerifyService.java（F13 仅注释）

- :60-61 常量注释补：Caffeine 单实例本地缓存（1min TTL），多实例部署各实例独立、无跨实例失效广播；窗口内他实例重判属声明接受行为，重复事件由消费端幂等（leaderboard 锚点行 + SETNX 去重）兜住
- 类 Javadoc「幂等」条目（:46）补一句多实例语义 + 重开条件（要求严格单次判定时再立项失效广播或分布式锁）
- 零逻辑零配置

### 2.7 README.md

鉴权/token 小节（TASK-191 上线前加固清单之后）插入两小块：

- **登出与吊销语义**：POST /api/auth/logout 携 refreshToken 即刻作废（此后轮换 401；无效 token 幂等成功）；access 15min 无状态短窗口为声明接受的权衡（RFC 6749 精神：access 短命无状态、refresh 有状态可吊销）；紧急封禁（user.status=0）拒新登录/刷新，已签发 access 需等自然过期——完全收敛的黑名单机制属后续可选课题
- **登录锁定双维度**：单 (phone,ip) 组合失败达阈值锁该组合（机主本人 IP 无感）；跨 IP 累计达兜底阈值锁全账号（防分布式爆破）；IP 取 X-Forwarded-For 尾段（网关注入，前置段不可信）

其余段落零改动。

## 4. 白名单（只改清单与主题串）

- 派发笔：`spec/changes/harden-auth-tail/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + `work/mailbox/tasks/TASK-193/spec.md`（四件套零改动入库），主题：`docs(spec): 派发 TASK-193 认证域 P2 尾巴打包提案与任务书`
- C-01（恰 6 文件）：`api/src/main/java/com/sportverify/api/auth/dto/LogoutRequestDTO.java`（新增）、`user-service/src/main/java/com/sportverify/user/auth/controller/AuthController.java`、`user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java`、`user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java`（路径以仓库实际为准）、`verify-service/src/main/java/com/sportverify/verify/service/VerifyService.java`、`README.md`，主题：`feat(auth): 登出吊销 refresh 与登录锁定双维度及缓存语义文档化（TASK-193）`
- C-02（恰 5 文件）：`spec/changes/harden-auth-tail/tasks.json`、`work/mailbox/tasks/TASK-193/spec.md`（§7 纯追加）、`work/mailbox/tasks/TASK-193/handoff.md`（新建）、`work/mailbox/PLAN.md`（纯追加）、`work/mailbox/findings-summary.md`（F11/F12/F13 纯追加核实段），主题：`docs(mailbox): 登记 TASK-193 认证域尾巴打包验收与台账闭环（TASK-193）`

## 5. 受保护 token（29 项，口径 repo 全量 git grep -cF）

开工实测 SUM 为准（TASK-191 收口真值 2030 参照）；收口读数以收口态实测为准。只增不减；新文档不枚举 token 字面量。

## 6. 门禁结构

1. **预提交门禁（每笔前亲跑记 rc）**：tasks.json 语法（python -m json.tool）rc=0；词面门四形态 ZERO_HIT rc=1 + 探针三态；git diff --check rc=0；契约门在途 `--open TASK-193 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新文件 LF + 末尾换行、修改文件保持既有行尾
2. **收口门禁（C-02 后亲跑留证）**：`bash scripts/verify/mvn-verify.sh --mode=offline test` 全量新基线逐位登记（561 只增不减，+8 预计落 user-service 119→127，全量 569=36/44/127/137/149/64/12 以实测为准）；`bash scripts/verify/mvn-verify.sh --static=record-service` 811 不增；零越界核验（`git diff --name-only <派发笔>..HEAD` 仅白名单 11 文件，**gateway 零文件**）；typed-router 零漂移（git diff --exit-code rc=0）；契约门无参 rc=0；PLAN.md 自派发笔起纯追加；findings 核实段与代码实态逐字一致
3. **handoff.md**：开工规程核验 / 偏差登记（threshold 5→20 行为变更 + 既有用例订正清单 + ResultCode 500 语义码定稿值）/ 一句话结论 / 只改清单 / 实施证据（logout 与双维度 8 例读数、README/注释 diff 证据、findings 核实标注）/ 逐门实测表 / token 前后读数 / 未覆盖项（本课题全离线可验证，无 UNDETERMINED 项；运行时真机验证沿口径可选登记）/ 提交表（显式哈希，禁时效指针）
4. **推送后**：第 34 次外部门槛 CI 绿为外部终验；红则按签名归因，禁重试刷绿

## 7. 收口记录（执行侧 C-02 纯追加）

### 7.1 提交记录

- 派发笔：`（回填显式哈希）` `docs(spec): 派发 TASK-193 认证域 P2 尾巴打包提案与任务书`
- C-01 实施笔：`（回填显式哈希）` `feat(auth): 登出吊销 refresh 与登录锁定双维度及缓存语义文档化（TASK-193）`
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff §8 给出）` `docs(mailbox): 登记 TASK-193 认证域尾巴打包验收与台账闭环（TASK-193）`

### 7.2 单测矩阵读数（offline，C-01 实施态实测回填）

- user-service：`AuthServiceTest` Tests run: ?（+8 例与既有订正后全量数，Failures: 0, Errors: 0, Skipped: 0）
  - `logoutRevokesRefreshLiveKey`：pass（…）
  - `logoutInvalidTokenStillSucceeds`：pass（…）
  - `logoutRedisFailureReportsError`：pass（…）
  - `ipFailureLocksOnlyThatPhoneIpPair`：pass（…）
  - `phoneTotalThresholdStillLocksAccount`：pass（…）
  - `isLockedChecksBothDimensions`：pass（…）
  - `loginSuccessClearsAllFourKeys`：pass（…）
  - `nullIpSkipsIpDimension`：pass（…）
  - 既有用例订正清单：（逐条回填）
- verify-service：测试数不变（149，F13 纯注释零新测试）

### 7.3 门禁读数（收口态实测回填）

- offline 全量逐位：`36/44/12?/137/149/64/12` = **?**（基线 561 只增不减，+8 预计落 user-service，全量以实测为准），Failures/Errors/Skipped 全 0，BUILD SUCCESS
- 静态门：`--static=record-service` Checkstyle **811 持平**未增
- 契约门在途：`bash scripts/verify/mailbox-contract.sh --open TASK-193 --baseline=<派发笔哈希>` rc=0
- 词面门四形态：改动文件集与 repo 全量（CI 权威 exclude 口径）四形态全 ZERO_HIT rc=1，探针三态 HIT rc=0，PROBE_GONE=yes
- token 29 项：开工实测 SUM=?；C-01 后 SUM=?；收口态 SUM=? 只增不减
- 只改清单全等核验：C-01 恰 6 文件（网关零文件）；C-02 恰 5 文件；typed-router.d.ts 零漂移
- 行尾与末尾换行核验：新文件 LF、修改文件保持既有行尾
