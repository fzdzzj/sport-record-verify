# TASK-203 任务书：超管功能面补全——用户封禁/解封端点（add-user-ban-ops，含 N4）

> 开工基线：本地提交 `<派发笔哈希>`（proposal/spec-delta/tasks.json + 本任务书，恰 4 新文件）。
> 对应提案：spec/changes/add-user-ban-ops/。

## §0 红线与零触碰面

- 零触碰：ci.yml、mvn-verify.sh、mailbox-contract.sh、docker-compose*、sql/、父pom、api 模块、gateway-service、common、record/verify/leaderboard/mapmatch 源码、docs/adr/、.trivyignore、spec/specs、归档、.codex/.trae（web/ 例外：仅 README.md N4 一处）
- 不引入新依赖/插件；不改内部令牌机制；Maven 唯一入口 bash scripts/verify/mvn-verify.sh（经 D:\git\Git\bin\bash.exe）；命令行禁携中文；MSYS sed -i 禁对 tracked 文件；禁 token 字面量与字面正则入新文档

## §1 侦察实证（指导侧 2026-10-12 亲证）

1. 申诉处理端点已存在：VerifyController:33 POST /api/appeals/{id}/review（治理面 ADMIN + TASK-201 服务侧签名二道防线）——课题3 原疑项证伪，零改动
2. User.status 设置侧缺口：全仓无 ban/unban 端点，status 仅注册置 1（AuthService:118）；消费侧完整（login:157-158、refresh:269-275 TASK-202、README:202 口径）
3. N4：web/README.md:154「需重新登录」旧表述（TASK-202 复核登记非阻断项）
4. 同构范式锚点：grantAdmin:190-208 / revokeAdmin:219-231（AuthService）；InternalAuthController 端点范式（:57 grantAdmin）
5. offline 基线 633=52/50/136/151/159/70/15；token SUM=2030；user 不在静态门；web 不涉 CI web job 变化（README 文本无构建影响）

## §2 预注册设计

### 2.1 A：banUser/unbanUser

- `banUser(Long userId)`：null→IllegalArgumentException；selectById null→USER_NOT_FOUND(2002)；status==0→log 幂等跳过；否则 update status=0
- `unbanUser(Long userId)`：同构，目标态 status=1
- javadoc：封禁后 login/refresh 即拒（双消费侧既有），存量 access ≤15min 自然过期（F12 口径），不吊销 refresh 键（续签校验已拦截）
- InternalAuthController：POST `/internal/auth/ban`、`/internal/auth/unban`，@Valid @RequestBody AdminGrantRequestDTO（复用），同构 grantAdmin 端点

### 2.2 B：N4 表述

- web/README.md:154「需重新登录」→「重新登录或 refresh 续签后生效（最长延迟=access 剩余时效）」，与根 README TASK-202 口径逐字对齐

### 2.5 红绿判别式（AuthServiceTest，纯 JVM）

- R1 红：banUser 不存在→USER_NOT_FOUND；R2 红：unbanUser 不存在→USER_NOT_FOUND
- G1 绿：banUser status 1→0 落库 + 已是 0 幂等跳过
- G2 绿：unbanUser status 0→1 落库 + 已是 1 幂等跳过
- G3 绿：banUser 后 user.getStatus()==0（封禁态断言，消费侧语义由 TASK-202 既有用例守护不重复实现）

## §3 停止条件

1. AdminGrantRequestDTO 校验注解与 ban 语义冲突（需新建 DTO）
2. status 字段存在其它消费方依赖「无设置端点」的语义（反证）
3. 词面门/token/行尾硬门禁红且白名单内无法修复
4. web/README.md:154 行号漂移致 N4 修正点定位失败

## §4 白名单（C-01 恰 4 文件）

1. user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java
2. user-service/src/main/java/com/sportverify/user/auth/controller/InternalAuthController.java
3. user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java
4. web/README.md

C-02 台账笔恰 4 文件：spec/changes/add-user-ban-ops/tasks.json、work/mailbox/tasks/TASK-203/spec.md（§7 回填）、work/mailbox/tasks/TASK-203/handoff.md（新建）、work/mailbox/PLAN.md（纯追加）。

## §5 token 基线

29 项 tracked git grep -cF SUM=2030（只增不减，新文档禁 token 字面量）。

## §6 门禁结构

1. offline 全量 `--mode=offline test` rc=0：633 基线上 user 136 净增只增不减、其余六模块逐位不变
2. 契约门双态：在途 `--open TASK-203 --baseline=<派发笔>` + 收口无参
3. token / 词面门（CI-exact `-n -I -iE` + 合成探针阳性对照）/ git diff --check / 行尾（改文件保持基线，web/README.md 保持其基线行尾）
4. 只改清单全等（4+4 双向）；台账纯追加
5. CI 外部终验（推送后）：全 job 绿（web job 亦需绿）；CI 红禁重试刷绿

## §7 收口记录（执行侧回填，保持纯追加）

（空——完成后按 handoff 规范回填两笔提交哈希、门禁留证、token 时点、CI 运行号）
