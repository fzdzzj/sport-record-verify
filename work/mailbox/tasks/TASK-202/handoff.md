# TASK-202 handoff：角色变更时效一致性与封禁续签缺口修复（fix-role-freshness）

> 状态：**已收口（本地两笔提交 876d209 / C-02 自指，未 push、未建 PR）**。
> 开工基线：`4e63eee`（提案三件套 + 任务书派发笔，工作树干净）；对应提案 spec/changes/fix-role-freshness/。

## 一句话结论

完成 TASK-202 角色时效缺口修复：A1 `AuthService.refresh` 在 GETDEL 原子消费旧 jti 之后、签发新对之前
查库校验存续——用户不存在或 status=0 → 401「账号已被禁用」（旧 jti 已消费，封禁即强制下线、重放同
refresh 亦 401，与 login 统一 401 防探测口径）；A3 新增 `AuthService.revokeAdmin` 与
`InternalAuthController` POST `/internal/auth/revoke-admin`（复用 AdminGrantRequestDTO，同构 grantAdmin：
null→IllegalArgumentException、不存在→2002、已是 USER 幂等跳过、否则改库 role=USER）；A2 grantAdmin/
revokeAdmin javadoc 与 README 角色时效表述订正为「重新登录或 refresh 续签后生效，最长延迟=当前 access
剩余时效，主动续签即时」；N1 顺带恢复 TASK-201 遗留的 common `GovernanceApiAuthFilterTest` 末尾换行。
红绿判别式 R1/R2/R3/G1/G2/G3 全落地（AuthServiceTest 新增 6 例）全绿；offline 633 全绿（user 净增、其余
六模块逐位不变）；契约门双态 rc=0；token 29 项 SUM=2030 只增不减。两笔本地提交，未达外部门槛。

## 编号、基线与外部状态

- 任务编号：`TASK-202`。
- 开工/对照基线：`4e63eee`（派发笔，工作树干净）。
- C-01 实施笔：`876d209`（`fix(auth): refresh封禁校验与角色撤销端点补全（TASK-202）`，5 文件，+170 −4）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox): 登记TASK-202角色时效缺口修复验收与台账闭环（TASK-202）`，
  4 文件；C-02 自身哈希自指，同型偏差 D1 沿先例）。
- 两笔均**未 push、未建 PR**。
- `.trae/tmp/` 仅用于 msg/临时 token 脚本与词面门探针，收尾即删。

## 只改清单（C-01 实施笔 5 文件 = §4 白名单全量；C-02 台账笔 4 文件）

- README.md
- common/src/test/java/com/sportverify/common/governance/GovernanceApiAuthFilterTest.java
- user-service/src/main/java/com/sportverify/user/auth/controller/InternalAuthController.java
- user-service/src/main/java/com/sportverify/user/auth/service/AuthService.java
- user-service/src/test/java/com/sportverify/user/auth/service/AuthServiceTest.java
- spec/changes/fix-role-freshness/tasks.json
- work/mailbox/tasks/TASK-202/spec.md
- work/mailbox/tasks/TASK-202/handoff.md
- work/mailbox/PLAN.md

## 红绿判别式（§2.5，纯 JVM 单测，AuthServiceTest）

| 判别式 | 断言 | 结果 |
| --- | --- | --- |
| R1 | status=0 用户合法 refresh → 401；旧 jti 已消费，重放同 refresh → 401 | 绿 |
| R2 | 库中用户已删（selectById 返回 null）→ 401 | 绿 |
| R3 | revokeAdmin 不存在用户 → USER_NOT_FOUND（2002） | 绿 |
| G1 | status=1 用户正常轮换不回归（新对签发 + 旧 jti 作废 + 新存活键落库） | 绿 |
| G2 | revokeAdmin 已是 USER 幂等跳过；ADMIN→改库 USER；撤销后续签返回 USER（降权即时性） | 绿 |
| G3 | grantAdmin 后立即续签返回 ADMIN（提权即时性，实证 β 修正后的语义） | 绿 |

测试接线：以内存 Map 假存储接线 `redis.execute(GETDEL_LUA, ...)`，按 key「取走并删除」等效真实 GETDEL 语义，
使「旧 jti 已消费 → 重放 401」闭环可判。

## §6 门禁留证

1. **offline 全量**：`--mode=offline test` rc=**0**、BUILD SUCCESS；逐位 common **52** / gateway **50** /
   user **136** / record **151** / verify **159** / leaderboard **70** / mapmatch **15**（=633）。user 130→136
   净增 +6（R/G 六例）；其余六模块 common/gateway/record/verify/leaderboard/mapmatch **逐位不变**；
   common 52 不变（N1 零语义）。
2. **契约门双态**：在途 `--open TASK-202 --baseline=4e63eee` rc=**0**；收口无参 rc=**0**。
3. **token**：29 项 tracked `git grep -cF` 全量 SUM 开工=2030、C-01 后=2030（只增不减）。
4. **词面门**：CI 权威正则（自 ci.yml L95）对改动文件集 ZERO_HIT（rc=1）；探针合成文件 rc=0（三态判定齐）。
5. **`git diff --check`** rc=**0**。
6. **行尾**：README/AuthService/InternalAuthController/AuthServiceTest 保持基线 CRLF；N1 文件保持基线 LF；
   AuthServiceTest 首轮曾现 mixed，已归一为 CRLF 后复核 w/crlf。
7. **§0 零触碰面**：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose* / web / sql / 父 pom /
   api 模块 / gateway-service / common 主体 / record·verify·leaderboard·mapmatch 源码 / docs/adr /
   .trivyignore / spec/specs 本体 / 归档目录 / .codex / .trae 均零触碰。

## token 时点读数

29 项受保护 token 以 tracked `git grep -cF` 全量计（SUM = 各 token 命中计数求和）：开工 **2030**、
C-01 后 **2030**（只增不减）。本课题新文档（tasks.json/spec §7/handoff/PLAN 追加）均未枚举 token 字面量。

## 实现要点

- **A1**：refresh 在 `alive` 判空之后插入 `userMapper.selectById(parsed.userId())`，`user == null ||
  status == 0` → `BizException(UNAUTHORIZED, "账号已被禁用")`；旧 jti 已由 Lua 原子消费，失败不回滚 jti。
- **A3**：revokeAdmin 与 grantAdmin 同构（null 校验 / 存在性 / 幂等 / updateById role=USER）；端点
  POST `/internal/auth/revoke-admin` 复用 `AdminGrantRequestDTO`，保护面 `X-Internal-Token` 既有零新增机制。
- **A2**：AuthService.grantAdmin、新增 revokeAdmin javadoc、InternalAuthController grantAdmin javadoc 与
  README 登出/吊销段时效表述统一订正；15min 声明接受口径原文保持。
- **N1**：仅对 `GovernanceApiAuthFilterTest.java` 末尾补 1 个 LF（此前 committed blob 缺末尾换行），零语义。

## 异常与偏差登记

- **D1（C-02 自指哈希）**：本 handoff 无法记录 C-02 自身哈希，同型偏差沿先例，以本提交哈希为准。
- **D2（AuthServiceTest 行尾 mixed→CRLF 归一）**：首轮编辑后该文件工作树呈 mixed（既有 CRLF 与新行 LF 混合）；
  已按基线 `w/crlf` 归一（`-replace` 转 CRLF，无 BOM），复核 `git ls-files --eol` 为 `i/lf w/crlf`，
  diff 纯 +117。
- **D3（Mockito updateById 重载歧义）**：MyBatis-Plus `updateById(T)` 与 `updateById(Collection<T>)` 重载致
  `any()` 判模糊（编译错），改用 `any(User.class)` 显式定型。
- **N1 顺带**：即 §4 第 5 文件，common 用例数 52 不变，属 TASK-201 指导侧复核登记的非阻断项落账。

## 未覆盖与不得推出的结论

- **真实跨进程联调**（封禁用户 refresh 实拒、内部撤销端点实调）未跑；`--mode=online` 与 CI 未跑。
- **未达外部门槛**：未 push、未建 PR，无 CI run；CI 外部终验待推送后由指导侧补记。
- 不涉 compose/static 双模块（user 不在静态门；本课题未改任何静态门涉及模块）。
