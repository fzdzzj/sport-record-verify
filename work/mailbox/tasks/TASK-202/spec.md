# TASK-202 任务书：角色变更时效一致性与封禁续签缺口修复（fix-role-freshness）

> 开工基线：本地提交 `<派发笔哈希>`（proposal/spec-delta/tasks.json + 本任务书，恰 4 新文件）。
> 角色：执行子agent全流程实施（含自跑门禁留证、两笔本地提交）；指导侧出提案派工；复核子agent由用户另行派发。
> 对应提案：spec/changes/fix-role-freshness/（proposal.md / specs/sport-record-verify/spec-delta.md / tasks.json）。

## §0 红线与零触碰面

- 零触碰：ci.yml、mvn-verify.sh、mailbox-contract.sh、docker-compose*、web/、sql/、父pom、api 模块、gateway-service、common 模块（N1 顺带件除外）、record/verify/leaderboard/mapmatch 源码、docs/adr/、.trivyignore、spec/specs 本体、归档目录、.codex/.trae、add-verify-degrade-status-index/
- 不引入新依赖/新 Maven 插件/Flyway/Testcontainers；不改 index/状态机/批量/cycle/MQ 参数；不碰内部令牌机制
- 禁止在文档写 token 字面量与字面正则模式；性能数字禁入台账
- Maven 唯一入口 bash scripts/verify/mvn-verify.sh（经 D:\git\Git\bin\bash.exe）；命令行参数禁携中文；MSYS sed -i 禁对 tracked 文件

## §1 侦察实证（指导侧 2026-10-11 亲证）

1. issueTokenPair（AuthService.java:279-296）续签实时读库取 role（:282-283）——降权/提权窗口=access TTL 15min，无无限持权
2. 缺口α：README.md:202 承诺「紧急封禁（user.status=0）拒新登录/刷新」，refresh 主流程（:223-243）无 status 校验（login :157-158 有）——封禁用户 7 天 refresh 存活期内可无限续签
3. 缺口β：grantAdmin javadoc（:188）「生效需重新登录」与实现不符（续签即生效）
4. 缺口γ：仅 InternalAuthController:57 grantAdmin 授予端点，无撤销端点
5. 测试面：AuthServiceTest.java 存在（服务层用例落点）；InternalAuthController 无既有测试类
6. F12/TASK-193 先例：access 15min 无状态窗口声明接受；access jti 黑名单已裁定不采用（不重开）；网关不引入 Redis 依赖
7. offline 基线 627=52/50/130/151/159/70/15；token 29 项 SUM=2030；static 双模块=leaderboard+record（user 不在静态门）

## §2 预注册设计

### 2.1 A1：refresh 封禁与存续校验（AuthService.refresh）

- 位置：GETDEL 原子消费旧 jti（:234-239）之后、issueTokenPair（:240）之前
- 查库：`userMapper.selectById(parsed.userId())`；user==null 或 status==0 → 抛 `BizException(UNAUTHORIZED, "账号已被禁用")`（统一 401 口径，不区分删号与封禁，对齐 login 防探测精神）
- 旧 jti 已被消费保持不变——封禁用户重放同 refresh 亦 401（强制下线闭环）
- status=1 轮换语义（原子消费/新对签发/存活键 TTL/日志）等价保持

### 2.2 A3：revokeAdmin 端点

- `AuthService.revokeAdmin(Long userId)`：同构 grantAdmin（:190-208）——null → IllegalArgumentException；不存在 → USER_NOT_FOUND；已是 USER → log 跳过幂等返回；否则 update role=USER
- `InternalAuthController` POST `/internal/auth/revoke-admin`：@Valid @RequestBody AdminGrantRequestDTO（复用），调 authService.revokeAdmin；javadoc 注明时效语义（旧 access ≤15min 自然过期、续签即时降权）
- 端点保护：/internal/** X-Internal-Token 面既有，零新增机制

### 2.3 A2：文档修正

- grantAdmin javadoc（:188 附近）+ README.md:202 段：表述改「角色变更在重新登录或 refresh 续签后生效，最长延迟=当前 access 剩余时效，主动续签即时」；15min 声明接受口径原文保持
- revokeAdmin javadoc 同口径

### 2.4 N1 顺带（TASK-201 遗留）

- common/.../governance/GovernanceApiAuthFilterTest.java：恢复基线丢失的文件末尾换行（仅末尾 1 字节，零语义变化，common 用例数 52 不变）

### 2.5 红绿判别式（AuthServiceTest，纯 JVM）

- R1 红：status=0 用户合法 refresh → 401；同 refresh 重放 → 401（jti 已消费）
- R2 红：库中用户已删（selectById 返回 null）→ 401
- R3 红：revokeAdmin 不存在用户 → USER_NOT_FOUND
- G1 绿：status=1 用户轮换语义不回归（新对签发+旧 jti 作废+存活键 TTL）
- G2 绿：revokeAdmin 已是 USER 幂等跳过；USER 化后 issueTokenPair 续签返回 role=USER（降权即时性）
- G3 绿：grantAdmin 后立即续签返回 role=ADMIN（提权即时性，实证β修正后的语义）

## §3 停止条件

1. refresh 校验插入点与 GETDEL 消费语义冲突（如校验失败需回滚 jti）
2. AdminGrantRequestDTO 复用存在校验注解冲突（如 userId 语义不符需新建 DTO）
3. 既有 AuthServiceTest 用例因 mock 结构无法承接 R/G 用例（需重构生产代码才可测）
4. 词面门/token/行尾任一硬门禁红且无法在白名单内修复
5. 发现 status 字段存在其它消费方依赖「封禁可续签」语义（反证本修复）

## §4 白名单（C-01 恰 5 文件）

1. user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java
2. user-service/src/main/java/com/sportverify/user/auth/controller/InternalAuthController.java
3. user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java
4. README.md
5. common/src/test/java/com/sportverify/common/governance/GovernanceApiAuthFilterTest.java（N1 顺带，仅末尾换行）

C-02 台账笔恰 4 文件：spec/changes/fix-role-freshness/tasks.json、work/mailbox/tasks/TASK-202/spec.md（§7 回填）、work/mailbox/tasks/TASK-202/handoff.md（新建）、work/mailbox/PLAN.md（纯追加）。

## §5 token 基线

29 项 tracked git grep -cF 全量 SUM=2030（只增不减，新文档禁写 token 字面量）。

## §6 门禁结构

1. offline 全量 `--mode=offline test` rc=0：627 基线上 user 130 净增只增不减、其余六模块逐位不变（common 52 不变——N1 零语义）
2. 契约门双态：在途 `--open TASK-202 --baseline=<派发笔>`（C-02 提交前）+ 收口无参
3. token / 词面门 / git diff --check / 行尾（改文件保持 CRLF，README 若基线 LF 保持 LF）
4. 只改清单全等（5+4 双向）；台账纯追加
5. CI 外部终验（推送后）：全 job 绿；CI 红禁重试刷绿

## §7 收口记录（执行侧回填，保持纯追加）

（空——完成后按 handoff 规范回填两笔提交哈希、门禁留证、token 时点、CI 运行号）

### 回填（执行侧，C-02 台账笔，纯追加）

- C-01 实施笔 `876d209`（`fix(auth): refresh封禁校验与角色撤销端点补全（TASK-202）`，恰 5 文件 =
  §4 白名单全量含 N1 顺带件）。
- C-02 台账笔本提交（`docs(mailbox): 登记TASK-202角色时效缺口修复验收与台账闭环（TASK-202）`，恰 4 文件）。
- 红绿判别式：R1 封禁 refresh 401 + 重放同 refresh 401 / R2 删号 401 / R3 revokeAdmin 不存在 2002 /
  G1 正常轮换不回归 / G2 revokeAdmin 幂等 + 撤销后续签 USER / G3 授予后续签 ADMIN —— 全绿（AuthServiceTest 新增 6 例）。
- 门禁：offline 全量 `--mode=offline test` rc=0 逐位 52/50/136/151/159/70/15 = 633（user 130→136 净增 +6，
  其余六模块逐位不变、common 52 不变）；契约门双态 rc=0（在途 `--open TASK-202 --baseline=4e63eee` + 收口无参）；
  token 29 项 tracked `git grep -cF` SUM 2030 只增不减；词面门对改动文件 ZERO_HIT（探针 rc=0）；`git diff --check` rc=0；
  行尾改文件保持基线（README/AuthService/InternalAuthController/AuthServiceTest 保持 CRLF、N1 文件保持 LF）。
- N1 顺带完成：common GovernanceApiAuthFilterTest 末尾换行恢复（仅末字节，common 52 用例数不变）。
- CI 运行号待推送后补记。
