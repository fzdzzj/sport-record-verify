# TASK-203 回传交接（用户封禁/解封端点补全 + N4 表述同步）

## 1. 编号、基线与外部状态

- 课题：add-user-ban-ops（超管功能面补全——用户封禁/解封端点，含 N4 顺带）。
- 开工基线：`764d9d0`（派发笔，含 proposal/spec-delta/tasks.json 与任务书，恰 4 新文件）；开工前 `git status --porcelain` 为空，基线自洽。
- C-01 实施笔：`452c2ed`（`feat(auth): 用户封禁解封管理端点补全（TASK-203）`，恰 4 文件 = §4 白名单全量）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox): 登记TASK-203超管功能面补全验收与台账闭环（TASK-203）`，恰 4 文件）。
- 两笔均**未 push、未建 PR**；外部 CI 终验待推送后由指导侧补记。

## 2. 只改清单（C-01 实施笔 4 文件 = §4 白名单全量；C-02 台账笔 4 文件）

- user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java
- user-service/src/main/java/com/sportverify/user/auth/controller/InternalAuthController.java
- user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java
- web/README.md
- spec/changes/add-user-ban-ops/tasks.json
- work/mailbox/tasks/TASK-203/spec.md
- work/mailbox/tasks/TASK-203/handoff.md
- work/mailbox/PLAN.md

## 3. 红绿判别式（AuthServiceTest 新增 5 例，纯 JVM）

- R1 红：`banUser` 目标用户不存在 → USER_NOT_FOUND（2002），不落库。
- R2 红：`unbanUser` 目标用户不存在 → USER_NOT_FOUND（2002），不落库。
- G1 绿：`banUser` status 1→0 落库（ArgumentCaptor 断言 status 与 id）；已是 0 幂等跳过（不再改库）。
- G2 绿：`unbanUser` status 0→1 落库；已是 1 幂等跳过（不再改库）。
- G3 绿：`banUser` 落库实参为封禁态 status==0（消费侧语义由 TASK-202 既有用例守护，不重复实现）。
- 新增 5 例全绿（user-service 136→141，+5）。

## 4. §6 门禁留证

1. **offline 全量**：`--mode=offline test` rc=**0**、BUILD SUCCESS；逐位 common **52** / gateway **50** / user **141** / record **151** / verify **159** / leaderboard **70** / mapmatch **15**（=**638**）。user 136→141 净增 **+5**；其余六模块 common/gateway/record/verify/leaderboard/mapmatch **逐位不变**。
2. **契约门双态**：在途 `--open TASK-203 --baseline=764d9d0` rc=**0**；handoff 建立后 `--baseline=764d9d0` 判据 B 比对 rc=**0**；收口无参 rc=**0**（回传文件无基线变动，不重审）。
3. **token**：29 项 tracked `git grep -cF` 全量 SUM 开工=**2030**、C-01 后=**2030**（持平，只增不减）。
4. **词面门**：CI 权威正则（自 ci.yml）以 CI-exact `-n -I -iE` 对改动 4 文件 ZERO_HIT（rc=1）；合成探针阳性对照 rc=0（`-E` 必要性对照：BRE 形态不命中，证明口径有效）。
5. **`git diff --check`** rc=**0**。
6. **行尾**：AuthService/InternalAuthController/AuthServiceTest 保持基线 CRLF（`w/crlf`）；web/README.md 保持基线 LF（`w/lf`）。
7. **§0 零触碰面**：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose* / sql / 父 pom / api 模块 / gateway-service / common / record·verify·leaderboard·mapmatch 源码 / docs/adr / .trivyignore / spec/specs / 归档 / .codex / .trae 均无改动（web/ 仅 README.md N4 一处）。

## 5. token 时点读数

29 项受保护 token 以 tracked `git grep -cF` 全量计（SUM = 各 token 命中计数求和）：开工 **2030**、C-01 后 **2030**（持平）。本课题新文档（tasks.json / spec §7 / 本 handoff / PLAN 追加）未引入受保护 token 字面量。

## 6. N4 修正确认

web/README.md:154 原「授予后，用户需重新登录以获取带 role 的 token」→「授予后，用户重新登录或 refresh 续签后生效（最长延迟=access 剩余时效），以获取带 role 的 token」，与根 README TASK-202 时效口径对齐；该行 1 处替换（+1/-1）。

## 7. 实现要点

- **A（AuthService）**：新增 `banUser(Long)` / `unbanUser(Long)`，同构 grantAdmin/revokeAdmin：null→IllegalArgumentException；`selectById` null→USER_NOT_FOUND(2002)；已是目标态（0/1）幂等跳过；否则 `updateById` 改库 status=0/1。javadoc 注明封禁时效语义（login/refresh 双消费侧即拒、存量 access 自然过期、不吊销 refresh 键）。
- **A（控制器）**：InternalAuthController 新增 POST `/internal/auth/ban`、`/internal/auth/unban`，复用 `AdminGrantRequestDTO`（`@NotNull(userId)` 与 ban 语义无冲突，停止条件 1 未触发），保护面 `X-Internal-Token` 既有零新增机制。
- **B（N4）**：web/README.md:154 时效表述订正。

## 8. 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 无法记录 C-02 自身哈希，同型先例，以本提交哈希为准。
- **D2（AuthServiceTest 行尾 mixed→CRLF 归一）**：首轮编辑后该文件工作树呈 mixed（既有 CRLF 与新行 LF 混合）；已按基线 `w/crlf` 做裸字节重建归一（lone LF 转 CRLF，无 BOM），复核 `git ls-files --eol` 为 `i/lf w/crlf`。
- 停止条件 1（DTO 校验冲突）/ 2（status 其它消费方反证）/ 3（硬门禁红）/ 4（N4 行号漂移）均未触发。

## 9. 未覆盖与不得推出的结论

- **真实跨进程联调**（内部 ban/unban 端点实调、封禁用户真实链路拒登录/拒续签）未跑；`--mode=online` 与 CI 未跑。
- **未达外部门槛**：未 push、未建 PR，无 CI run；CI 外部终验待推送后由指导侧补记。
- 不涉 compose / 静态双模块（user 不在静态门；本课题未改任何静态门涉及模块）。
