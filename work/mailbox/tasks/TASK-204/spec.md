# TASK-204 任务书：越权反例收口——id 类端点归属校验补全（harden-idor-ownership）

> 开工基线：本地提交 `<派发笔哈希>`（proposal/spec-delta/tasks.json + 本任务书，恰 4 新文件）。
> 对应提案：spec/changes/harden-idor-ownership/。

## §0 红线与零触碰面

- 零触碰：ci.yml、mvn-verify.sh、mailbox-contract.sh、docker-compose*、sql/、父pom、api 模块、gateway-service、common、verify/leaderboard/mapmatch 源码、docs/adr/、.trivyignore、spec/specs、归档、.codex/.trae、web/、**record-service 中除 §4 所列 4 文件外的一切**（尤其 InternalRecordController、SportRecordService.listPoints 内部读路径、RecordLikeService）、**user-service 中除 §4 所列 5 文件外的一切**（尤其 AuthController/AuthService/InternalAuthController/Notification*）
- 不引入新依赖/插件；不改内部令牌机制；不改 api 模块 Feign 契约；Maven 唯一入口 bash scripts/verify/mvn-verify.sh（经 D:\git\Git\bin\bash.exe）；命令行禁携中文；MSYS sed -i 禁对 tracked 文件；禁 token 字面量与字面正则入新文档

## §1 侦察实证（指导侧 2026-10-12 全仓普查亲证）

1. R1：RecordController:90-95 GET `/record/api/records/{id}/points` → SportRecordService.pagePoints:329-353，仅 `selectById` 存在性判定，无 `record.userId` 归属断言；listPoints:293-299 为内部 Feign 读路径（InternalRecordController:48 消费，无调用者上下文）——仅 pagePoints 需补
2. R4：RecordController:80-85 POST `/record/api/records/{id}/appeal` → SportRecordService.appeal:245-276，`userId` 入参透传但从未与 `record.userId` 比对（落库用 `record.getUserId()`）；既有 SportRecordServiceTest:459/474/498/519/540 以 100L≠200L 断言「忽略入参」为当前行为（需随修复更新）
3. R2/R3：FriendController:66-78 accept/reject → FriendService.accept:153-199 / reject:205-232，仅申请存在性 + PENDING 乐观流转，无 `to_user` 归属断言；InternalFriendController:40-47 为 /internal/** Feign 契约实现（网内信任，无调用者上下文）
4. 正面范式：五控制器 `resolveUserId(request, claimed)` 逐字同源（ADR-0007）；服务层 `(id,user_id)` 双条件隔离范式见 NotificationService.markRead:145-161
5. 生效开关：`@Value("${app.auth.enabled:false}")`，与既有降级范式一致；局部异常 1002→HTTP 403 已就绪（各控制器 @ExceptionHandler）
6. offline 基线 638=52/50/141/151/159/70/15；token 29 项 tracked `git grep -cF` SUM=2030；record/user 不在静态门

## §2 预注册设计

### 2.1 统一范式

归属校验仅在 `app.auth.enabled=true` 时生效：控制器以 `Long caller = authEnabled ? resolveUserId(request, <claimed>) : null` 计算调用者；服务层以 `if (caller != null && !caller.equals(<owner>)) throw new BizException(ResultCode.FORBIDDEN)` 断言。false 时 caller 恒为 null → 零改动旧行为。**不改** `resolveUserId` 本体与局部异常处理。

### 2.2 R1（record-service 轨迹分页）

- `RecordController.points(Long id, long page, long size, HttpServletRequest request)`：caller 计算后下传
- `SportRecordService.pagePoints(Long recordId, Long caller, long page, long size)`：`record` 空判后（`QueryMethods_recordNotFound` 语义保留）插入归属断言，再走既有 archived 冷热路由分支
- `listPoints` 与 InternalRecordController **零改动**

### 2.3 R4（record-service 申诉）

- `RecordController.appeal(Long id, AppealCreateDTO dto, HttpServletRequest request)`：`caller = authEnabled ? resolveUserId(request, dto.getUserId()) : null`，下传
- `SportRecordService.appeal(Long recordId, Long caller, String reason)`：`record` 空判后插入 `caller==record.userId` 断言；后续 REJECTED/archived 防线与 `createAppeal(new AppealCreateDTO(recordId, record.getUserId(), reason))` 权威口径**不变**

### 2.4 R2/R3（user-service 好友申请流转）

- `FriendController.accept/reject` 增 `HttpServletRequest`：`caller = authEnabled ? resolveUserId(request, null) : null`，下传
- `FriendService.accept(Long requestId, Long caller)` / `reject(Long requestId, Long caller)`：申请空判后、取 lock 前插入 `caller==request.getToUser()` 断言（不一致→403，天然阻断 from_user 自同意）
- `InternalFriendController.accept/reject` 显式传 `null`（javadoc 注明 /internal/** 网内信任无调用者上下文）

### 2.5 红绿判别式（纯 JVM 服务层单测）

record-service（SportRecordServiceTest）：
- R1a 红：pagePoints(1L, caller=999L, 1, 20) 且 record.userId=100L → FORBIDDEN(1002)
- R1b 绿：pagePoints(1L, caller=100L, 1, 20) → 正常分页；R1c 绿：pagePoints(1L, null, 1, 20) → 降级放行
- R4a 红：appeal(5L, caller=100L, "理由") 且 record.userId=200L → FORBIDDEN(1002)
- R4b 绿：appeal(5L, caller=200L, "理由") → 成功（createAppeal 以 200L 为权威）；R4c 绿：appeal(5L, null, "理由") → 降级放行
- 既有 5 处 appeal 用例与 3 处 pagePoints 用例：调用点补 caller 参数（降级 null 或对齐归属人），语义不变

user-service（FriendServiceTest）：
- R2a 红：accept(5L, caller=1003L) 且 to_user=1002L → FORBIDDEN(1002)，不写 friendship
- R2b 绿：accept(5L, caller=1002L) → ACCEPTED；R2c 绿：accept(5L, null) → 降级放行
- R3a 红：reject(5L, caller=1003L) → FORBIDDEN(1002)；R3b 绿：reject(5L, caller=1002L) → REJECTED；R3c 绿：reject(5L, null) → 降级放行

## §3 停止条件

1. 归属断言与既有降级语义冲突：需在 `app.auth.enabled=false` 下改行为才能通过既有用例（即无法做到零扰动降级）
2. `listPoints` / api 模块 Feign 契约被迫改动（证明内部路径与对外路径不可分离）
3. 词面门/token/行尾硬门禁红且 §4 白名单内无法修复
4. record/user 模块 offline 基线回退（净增不足或其余模块非逐位不变）
5. 需触碰 §0 零触碰面才能落地

## §4 白名单（C-01 恰 9 文件）

1. record-service/src/main/java/com/sportverify/record/controller/RecordController.java
2. record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
3. record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java
4. record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
5. user-service/src/main/java/com/sportverify/user/controller/FriendController.java
6. user-service/src/main/java/com/sportverify/user/controller/InternalFriendController.java
7. user-service/src/main/java/com/sportverify/user/service/FriendService.java
8. user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java
9. user-service/src/test/java/com/sportverify/user/service/FriendConcurrencyTest.java

C-02 台账笔恰 4 文件：spec/changes/harden-idor-ownership/tasks.json、work/mailbox/tasks/TASK-204/spec.md（§7 回填）、work/mailbox/tasks/TASK-204/handoff.md（新建）、work/mailbox/PLAN.md（纯追加）。

## §5 token 基线

29 项 tracked git grep -cF SUM=2030（只增不减，新文档禁 token 字面量）。

## §6 门禁结构

1. offline 全量 `--mode=offline test` rc=0：638 基线上 user(141)/record(151) 两模块净增只增不减、其余五模块 common/gateway/verify/leaderboard/mapmatch 逐位不变
2. 契约门双态：在途 `--open TASK-204 --baseline=<派发笔>` + 收口无参
3. token / 词面门（CI-exact `-n -I -iE` + 合成探针阳性对照，注意 -E 缺失会假阴性）/ git diff --check / 行尾（改文件保持基线 CRLF）
4. 只改清单全等（9+4 双向）；台账纯追加
5. CI 外部终验（推送后）：全 job 绿；CI 红禁重试刷绿，按签名归因

## §7 收口记录（执行侧回填，保持纯追加）

（空——完成后按 handoff 规范回填两笔提交哈希、门禁留证、token 时点、CI 运行号）

### 收口记录（2026-10-12，执行侧回填）

- **C-01 实施笔**：`0cca37a`（`fix(auth): id 类端点归属校验补全（TASK-204）`），恰 9 文件 = §4 白名单全量（+250 ／−44）。
- **C-02 台账笔**：本记录所示 commits（`docs(mailbox): 登记TASK-204越权反例收口验收与台账闭环（TASK-204）`），恰 4 文件：tasks.json 勾选 / 本 §7 回填 / handoff.md（新建）/ PLAN.md（纯追加）。
- 两笔均**未 push、未建 PR**。
- **offline 全量**：`--mode=offline test` rc=0，逐位 **52 / 50 / 147 / 157 / 159 / 70 / 15**（=650）。user 141→147（+6，FriendServiceTest 17→23）、record 151→157（+6，新增 R1/R4 判别式各 3 例）；其余五模块 common/gateway/verify/leaderboard/mapmatch **逐位不变**（52/50/159/70/15）。
- **契约门双态**：在途 `--open TASK-204 --baseline=91540a2` rc=0；handoff 建立后判据 B（`--baseline=91540a2`）rc=0；收口无参 rc=0。
- **token**：29 项 tracked `git grep -cF` 开工/收口 SUM=**2030** 只增不减（新文档不枚举 token 字面量）。
- **词面门**：CI-exact 正则对改动文件集 ZERO_HIT（rc=1）+ 合成探针阳性对照（`-E` 口径命中 rc=0、BRE 形态漏检 rc=1 证明 `-E` 必要性）。
- **`git diff --check`** rc=0；**行尾**：改 9 文件保持各自基线（RecordControllerTest/InternalFriendController 为 LF，其余 7 文件 CRLF），无 BOM、无 mixed。
- 红绿判别式全部通过（见 handoff §3）。
