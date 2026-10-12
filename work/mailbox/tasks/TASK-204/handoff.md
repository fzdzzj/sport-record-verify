# TASK-204 回传交接（越权反例收口——id 类端点归属校验补全）

## 1. 编号、基线与外部状态

- 课题：harden-idor-ownership（id 类端点归属校验补全）。
- 开工基线：`91540a2`（派发笔，含 proposal/spec-delta/tasks.json 与任务书，恰 4 新文件）；开工前 `git status --porcelain` 为空，基线自洽。
- C-01 实施笔：`0cca37a`（`fix(auth): id 类端点归属校验补全（TASK-204）`，恰 9 文件 = §4 白名单全量，+250 ／−44）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox): 登记TASK-204越权反例收口验收与台账闭环（TASK-204）`，恰 4 文件）。
- 两笔均**未 push、未建 PR**；外部 CI 终验待推送后由指导侧补记。

## 2. 只改清单（C-01 实施笔 9 文件 = §4 白名单全量；C-02 台账笔 4 文件）

- record-service/src/main/java/com/sportverify/record/controller/RecordController.java
- record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
- record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java
- record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
- user-service/src/main/java/com/sportverify/user/controller/FriendController.java
- user-service/src/main/java/com/sportverify/user/controller/InternalFriendController.java
- user-service/src/main/java/com/sportverify/user/service/FriendService.java
- user-service/src/test/java/com/sportverify/user/service/FriendConcurrencyTest.java
- user-service/src/test/java/com/sportverify/user/service/FriendServiceTest.java
- spec/changes/harden-idor-ownership/tasks.json
- work/mailbox/tasks/TASK-204/spec.md
- work/mailbox/tasks/TASK-204/handoff.md
- work/mailbox/PLAN.md

## 3. 红绿判别式（纯 JVM 服务层单测）

record-service（SportRecordServiceTest）：
- **R1a 红**：`pagePoints(1L, caller=999L, 1, 20)` 且 `record.userId=100L` → FORBIDDEN（1002），`selectPage` 零调用（不论热/归档表）。
- **R1b 绿**：`pagePoints(1L, caller=100L, 1, 20)` → 正常分页。
- **R1c 绿**：`pagePoints(1L, null, 1, 20)` → 降级放行。
- **R4a 红**：`appeal(5L, caller=100L, ...)` 且 `record.userId=200L` → FORBIDDEN（1002），不建单、不迁移。
- **R4b 绿**：`appeal(5L, caller=200L, ...)` → 成功（createAppeal 以 `record.getUserId()`=200L 为权威）。
- **R4c 绿**：`appeal(5L, null, ...)` → 降级放行。
- 既有 5 处 appeal 用例与 3 处 pagePoints 用例：调用点补 caller 参数（降级 null 或对齐归属人），断言语义不变，零改动既有断言目标。

user-service（FriendServiceTest）：
- **R2a 红**：`accept(5L, caller=1003L)` 且 `to_user=1002L` → FORBIDDEN（1002），不流转、不写 friendship。
- **R2b 绿**：`accept(5L, caller=1002L)` → ACCEPTED（落 friendship）。
- **R2c 绿**：`accept(5L, null)` → 降级放行。
- **R3a 红**：`reject(5L, caller=1003L)` → FORBIDDEN（1002），不流转。
- **R3b 绿**：`reject(5L, caller=1002L)` → REJECTED。
- **R3c 绿**：`reject(5L, null)` → 降级放行。
- 既有 7 处 accept/reject 用例与反射自省调用点（`getMethod("accept", Long.class, Long.class)` 更新）已同步；FriendConcurrencyTest 的 accept 调用点补 caller=null（降级语义，断言不变）。

实测：FriendServiceTest 17→**23**（+6）、SportRecordServiceTest 33+→**39**（+6）、RecordControllerTest 11 恒等；`--mode=offline test` 全绿。

## 4. §6 门禁留证

1. **offline 全量**：`--mode=offline test` rc=**0**、BUILD SUCCESS；逐位 common **52** / gateway **50** / user **147** / record **157** / verify **159** / leaderboard **70** / mapmatch **15**（=**650**）。user 141→147 净增 **+6**、record 151→157 净增 **+6**（只增不减）；其余五模块 common/gateway/verify/leaderboard/mapmatch **逐位不变**。
2. **契约门双态**：在途 `--open TASK-204 --baseline=91540a2` rc=**0**；handoff 建立后 `--baseline=91540a2` 判据 B rc=**0**；收口无参 rc=**0**。
3. **token**：29 项 tracked `git grep -cF` 全量 SUM 开工=**2030**、C-01 后=**2030**、C-02 后=**2030**（只增不减；本课题新文档不枚举保护 token 字面量）。
4. **词面门**：CI 权威正则（自 ci.yml 现场提取）以 CI-exact `-n -I -iE` 对改动 9 文件 ZERO_HIT（rc=1）；合成探针阳性对照 rc=0（CI-exact 形态命中探针文本）；BRE 形态（无 `-E`）对同一探针漏检 rc=1 → 证明 `-E` 缺失会假阴性、CI-exact 口径有效。
5. **`git diff --check`** rc=**0**（含 staged，C-01/C-02 两笔 `git show --check` 均 rc=0）。
6. **行尾**：改 9 文件保持各自基线——RecordControllerTest、InternalFriendController 为 LF（`w/lf`），其余 7 文件 CRLF（`w/crlf`），全部无 BOM、无 mixed；新 handoff.md 与既有台账同型为 LF。
7. **§0 零触碰面**：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose* / sql / 父 pom / api 模块 / gateway-service / verify·leaderboard·mapmatch 源码 / docs/adr / .trivyignore / spec/specs / 归档 / .codex / .trae 均无改动；record-service 中除 §4 所列 4 文件外的一切（尤其 InternalRecordController、listPoints 读路径、RecordLikeService）与 user-service 中除 §4 所列 5 文件外的一切均未触碰。

## 5. token 时点读数

29 项受保护 token 以 tracked `git grep -cF` 全量计（SUM = 各 token 命中计数求和)：开工 **2030**、C-01 后 **2030**、C-02 后 **2030**（只增不减）。本课题新文档（tasks.json / spec §7 / 本 handoff / PLAN 追加）未引入受保护 token 字面量（C-01 的 9 个实现/测试文件对 29 项 token 命中 **0**）。

## 6. 实现要点

- **R1**：`RecordController.points` 增 `HttpServletRequest request`，`Long caller = authEnabled ? resolveUserId(request, null) : null` 下传 `pagePoints(id, caller, page, size)`；`SportRecordService.pagePoints` 增 `caller` 参数，`record` 空判后插入 `if (caller != null && !caller.equals(record.getUserId())) throw new BizException(ResultCode.FORBIDDEN)`，再走既有 archived 冷热路由。`listPoints` 与 InternalRecordController **零改动**。
- **R4**：`RecordController.appeal` 改传 `Long caller = authEnabled ? resolveUserId(request, dto.getUserId()) : null`；`SportRecordService.appeal` 增 `caller` 参数，`record` 空判后插入归属断言；后续 REJECTED/archived 防线与 `createAppeal(new AppealCreateDTO(recordId, record.getUserId(), reason))` 权威口径**不变**。
- **R2/R3**：`FriendController.accept/reject` 增 `HttpServletRequest request`，`Long caller = authEnabled ? resolveUserId(request, null) : null` 下传；`FriendService.accept/reject` 增 `caller` 参数，申请空判后、取 lock 前插入 `if (caller != null && !caller.equals(request.getToUser())) throw new BizException(ResultCode.FORBIDDEN)`（天然阻断 from_user 自同意/自拒绝）。`InternalFriendController.accept/reject` 显式传 `null` 并 javadoc 注明 `/internal/**` 网内信任、无调用者上下文。
- **降级口径**：仅 `app.auth.enabled=true` 时 caller 非空归属断言才生效；false 时 caller 恒 null → 既有行为零扰动。未改 `resolveUserId` 本体与各控制器局部异常处理（1002→HTTP 403 已就绪）。

## 7. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 无法记录 C-02 自身提交哈希，沿 TASK-178/183 同型先例，以 C-02 实际提交哈希为准。
- **D2（早期单测一次失败已修复）**：新增 `appeal_callerNull_degradeAllowed` 初版以 `anyInt()` 匹配 `updateStatus` 第 4 参（版本号），但 `record.getVersion()` 为空值与 int 匹配器不命中导致误抛「并发冲突」；已改为显式 `record.setVersion(0)` + 精确 stub `(5L, REJECTED, APPEALING, 0)` 修复，复跑 record-service offline 全绿。
- 停止条件 1（降级语义冲突）/ 2（listPoints/契约被迫改动）/ 3（硬门禁红且白名单内不可修）/ 4（自模块基线回退）/ 5（需触零触碰面）**均未触发**。

## 8. 未覆盖与不得推出的结论

- **真实跨进程/跨服务联调**（网关注入 X-User-Id → 控制器 → 服务层真实 HTTP 403 链路）未跑；`--mode=online` 与 CI 未跑。
- **未达外部门槛**：未 push、未建 PR，无 CI run；CI 外部终验待推送后由指导侧补记。
- 本项目为纯 JVM 服务层单测判别，未验证生产鉴权开关在真容器下的装配行为；`app.auth.enabled=true` 的网关身份注入属 ADR-0007 既有口径，未在本任务复述其实现。

## 9. 收口门禁（C-02 后亲跑）

- 契约门**无参 rc=0**（收口后回传文件已入库，ACTUAL 空）。
- token 收口态 repo 全量 `git grep -cF` SUM=**2030**（只增不减）。
- `git diff --check` 与两笔 `git show --check` 均 rc=0。
- `git diff --name-only` 对照：C-01 恰 9 条（= §4 白名单）、C-02 恰 4 条（tasks.json / spec.md §7 / handoff.md / PLAN.md）。
- 两笔 commit hash：C-01 `0cca37a`；C-02 为本 handoff 所在提交。