# TASK-226 handoff：LIGHT 清理批（H3，LIGHT 档）

## 1. 编号、基线与外部状态

- 任务编号：`TASK-226`（提案 `spec/changes/light-cleanup-batch/proposal.md`）。
- 分级：**LIGHT**（零功能变更清理批：留痕清偿核验 / README 订正 / 静态偏差台账化 / BACKLOG 刷新）。
- 开工基线：`11364af`（TASK-226/227 派发笔；其下 bc84594 为 TASK-225 封盘笔，随批次推送勿动勿 amend；开工前工作树干净）。
- 离线基线（任务书 §6）：`52/50/173/250/159/80/26`；token 基线 SUM=2036。
- C-01 实施笔：`docs` 笔（README 订正 + BACKLOG 刷新，恰 3 文件）。
- C-02 台账笔：本 handoff 所在提交（`docs(mailbox)`，自指哈希沿 TASK-220/221/222/223/224/225 先例，实际以 `git log` 为准）。
- 两笔均**未 push、未建 PR**；外部门槛（CI）待指导侧推动推送后补记。LIGHT 档免独立复核（沿 TASK-212 先例），指导亲核后随 TASK-227 批次推送。

## 2. 只改清单（相对开工基线 11364af，恰 7 文件 = C-01 3 + C-02 4，逐字符声明）

C-01（docs 实施笔，恰 3 文件）：
- README.md
- web/README.md
- work/mailbox/BACKLOG.md

C-02（docs(mailbox) 台账笔，本 handoff 所在提交，恰 4 文件）：
- spec/changes/light-cleanup-batch/tasks.json
- work/mailbox/tasks/TASK-226/spec.md
- work/mailbox/tasks/TASK-226/handoff.md
- work/mailbox/PLAN.md

> 下列载体零触碰（零功能变更的直接证据）：record-service/src/test/java/.../RecordControllerTest.java（留痕-1 已清偿，本课题零 diff，见 D1）、一切 src/main、api 模块、pom、.github/workflows/ci.yml、scripts/、docker-compose*、sql/、web/package.json、web/vite.config.ts、web/src/**、web/src/typed-router.d.ts、其余台账载体。词面门/token/契约等临时脚本均落 gitignored `.trae/tmp`、不入库。

## 3. 一句话结论

零功能/测试逻辑变更的 LIGHT 清理批：核验并登记 TASK-221 留痕-1（RecordControllerTest.java 末尾换行）已由 TASK-224 编辑本文件时清偿（HEAD 实测末字节 0x0A 在位、行尾纯 LF 无 BOM，本课题零 diff）；订正根 README 与 web/README.md 共 7 处陈旧表述（模块职责行缺新功能、web「仅脚手架」与「无服务端我的记录列表」旧口径、目录约定措辞），逐处留对照；将 user-service（1023 项）与 api（487 项）通用 checkstyle sun_checks 静态基线违规台账化登记（不修码）；刷新 BACKLOG 推荐序行（F 系四方向均已封盘清空）。门禁按 LIGHT 档执行：offline `--pl record-service` 逐位 250 不变、diff --check rc=0、token 29 项 2036 持平、词面门 ZERO_HIT + 探针正控、契约门收口无参 rc=0、行尾核对通过。

## 4. 判别式红绿（R1-R4 / G1-G3 逐条）

- **R1（绿）留痕-1 清偿核验**：`git cat-file -s HEAD:record-service/src/test/java/com/sportverify/record/controller/RecordControllerTest.java`=20113 字节，字节级校验纯 LF（CR=0）+ 末字节 0x0A（末 4 字节 `7D 0A 7D 0A` = `}\n}\n`）+ 无 BOM；历史核验：TASK-221 C-01（ff80635）该文件末 3 字节为 `7D 0A 7D`（缺末尾换行，留痕-1 由此而生），TASK-222（9db644a）/TASK-223（40ce0d3）仍缺，TASK-224（fabeb60）编辑本文件时补入末尾 0x0A；此后 TASK-225 未触碰。即预注册 S2.1「补末尾换行」的实际变更已由中途提交清偿，本课题对 RecordControllerTest.java **零 diff**（严格满足「除末字节 0x0A 外零 diff」的超集），清偿事实登记入账（D1）。
- **R2（绿）README 陈旧表述订正（逐处留对照，共 7 处）**：
  - R2.1 根 README 模块表 user-service 行：原文「好友申请/同意/拒绝/列表 + 认证域（注册/登录/刷新）」→ 改文补「登出/自助改密、refresh 经 HttpOnly cookie 下发」+「通知中心（列表/未读/已读/偏好 + WebSocket 实时推送）」+「封禁（user.status=0 拒登录/续签）」——对应 TASK-205/208/210/213-218/202 已落地功能。
  - R2.2 根 README 模块表 record-service 行：原文「记录提交/查询/申诉 + 点赞/取消/计数」→ 改文补「删除」+「我的记录（分页/筛选/统计，TASK-211/224）」+「连续打卡（TASK-220）」+「好友动态 feed（TASK-222）」+「记录可见性三态（TASK-221）」+「周月运动目标（TASK-225）」。
  - R2.3 根 README 快速开始 §5：「前端控制台脚手架」→「前端控制台」。
  - R2.4 根 README 目录约定 web/ 行：「前端控制台脚手架（…）」→「前端控制台（…）」。
  - R2.5 web/README.md 注意节：「仅脚手架。业务功能…在后续独立变更中实现。」→「控制台已承载多业务功能（…见下方各变更小节）；认证会话与路由守卫为基础层」。
  - R2.6 web/README.md 业务演示节：「无服务端『我的记录』列表限制（诚实说明）」整节 → 改文「服务端『我的记录』列表（TASK-211 起已落地）」：说明 add-web-record-console 阶段 sessionStorage 限制已被 TASK-211 解除，首页仪表盘现调用 GET /record/api/records/my（过滤联动 TASK-224）；提交页「本会话记录」卡片仍保留 sessionStorage 快捷记法、与服务端列表无关。
  - R2.7 不改项声明：web/README.md「无 @vue/test-utils、无 Playwright」与「E2E … 设计性非目标」两句在本课题时点仍为事实（Playwright 由 TASK-227 重议引入，其 README 更新随 TASK-227 交付），故 TASK-226 不预动。
- **R3（绿）静态基线偏差台账化（登记不修码）**：`--static=user-service` 与 `--static=api` 实测为**非本仓声明插件模块**（静态三件套仅 record-service/leaderboard-service 声明并强制，见 mvn-verify.sh §static 注释），通用 checkstyle sun_checks 规则集下存在基线违规：user-service **1023 项**（javadoc 556 / sizes 216 / misc 199 / coding 28 / design 14 / whitespace 8 / imports 2），api **487 项**（javadoc 333 / misc 69 / sizes 56 / coding 21 / design 8）；两模块 checkstyle:check 先行失败（BUILD FAILURE rc=1），SpotBugs/PMD 未及执行。处置：**只登记不修码**（超出本课题白名单）；修复候选路径 = 逐模块声明自建 checkstyle 配置并逐步治理（沿 TASK-018 leaderboard-service 先例），记为后续独立变更方向。此违规不阻断 CI（静态门仅对声明模块生效）。
- **R4（绿）BACKLOG 推荐序行刷新（F 系已清空）**：F1→TASK-222、F2→TASK-223、F3→TASK-225、F4→TASK-224 均已封盘，推荐序由「F1 首选 → H3 随手批 → F2/F3/F4 按需」刷新为「F 系已清空；在途 H3（TASK-226）与 H1（TASK-227）完成后候选 H2（E5 生产部署姿态演练）；若近期上生产，H2 提前」。
- **G1（绿）**：offline `--pl record-service` 逐位 **250** 不变（0 Failures/Errors/Skipped，rc=0）——零功能变更的直接证据。
- **G2（绿）**：`git diff --name-only 11364af` 恰 §2 的 7 文件（C-01 3 + C-02 4）；越界面（java/api/pom/ci.yml/scripts/sql/web src/package.json/vite.config.ts/typed-router.d.ts）diff 全空。
- **G3（绿）**：token 29 项两点 2036/2036 持平；词面门改动集 CI-exact ZERO_HIT + 探针正控 HIT；`git diff --check` rc=0；改动文件保持基线行尾（README.md 工作树 CRLF 为 `core.autocrlf=true` checkout 形态、blob LF 与基线一致；web/README.md、BACKLOG.md 纯 LF，全部无 BOM、末尾换行齐全）。

## 5. §6 门禁留证（LIGHT）

1. **offline record-service 逐位门禁**：`scripts/verify/mvn-verify.sh --mode=offline --pl record-service test` 实测 **rc=0 / BUILD SUCCESS**，`record-service` 用例 **250/0/0/0** 与基线逐位一致（仅行尾+文档改动，零功能变更）。
2. **diff --check**：rc=0（工作树与暂存区双查；`LF will be replaced by CRLF` 为 `core.autocrlf=true` 信息性告警，非空白错误）。
3. **token 29 项**：开工基线 11364af = **2036**、工作树 = **2036**，持平且 ≥2034；改动集 7 文件逐项扫描 ZERO_HIT。
4. **词面门 CI-exact**：显式 ERE 口径（`git grep -n -I -iE -f <模式文件>`）对 C-01+C-02 改动集 **ZERO_HIT rc=1**；合成探针同模式 **HIT rc=0**，无假阴性。模式字面量与探针均落 gitignored `.trae/tmp`、不入库。
5. **契约门双态**：在途 `--open TASK-226 --baseline=11364af` **实测 rc=0**（判据 A 两件套齐全；判据 B 只改清单与实际改动集一致）；收口无参 **rc=0**（见 §9）。
6. **行尾核对**：RecordControllerTest.java 保持纯 LF + 末尾 0x0A + 无 BOM（本课题零 diff，字节级复核）；改动 3 文件保持基线行尾（README.md 工作树 CRLF 为 autocrlf checkout 形态、blob LF；web/README.md、BACKLOG.md 纯 LF）、全部末尾换行齐全、无 BOM；无新增未跟踪文件。
7. **只改清单全等**：`git diff --name-only 11364af` 对照恰 §2 的 7 文件；越界面 diff 全空（G2）。
8. **凭证自检**：改动集为文档订正与台账文案，零真实凭证字面量、零受保护 token 字面量、零新正则字面量。

## 6. token 时点读数

29 项受保护 token 以 tracked 全量计（`git grep -cF`，SUM = 各 token 行命中计数求和）：开工（11364af 基线）= **2036**、工作树 = **2036**（只增不减，且 ≥ 任务书 2034 基线）。本课题改动集（3 实施文件 + 4 台账文件）全部内容零真实凭证 / 零受保护 token 字面量。**口径说明**：沿用 `.trae/tmp/count_tokens.py`（29 项模式）复算，不枚举字面量以避免全仓计数自增失准（沿 TASK-188 教训）。

## 7. 实现要点

- **留痕-1 清偿核验**（R1）：历史链 ff80635→9db644a→40ce0d3 均缺末尾换行（末字节 `7D`），fabeb60（TASK-224）编辑时补入 `0A`；HEAD 与工作树字节级一致（20113 字节、纯 LF、末 4 字节 `7D 0A 7D 0A`）。本课题零 diff，清偿事实登记。
- **README 订正 7 处**（R2）：见 §4 R2.1-R2.7 对照；仅文档订正，未涉及任何行为承诺变更（S3.2 未触发）。
- **静态基线偏差台账**（R3）：user-service 1023 / api 487 项通用 checkstyle 违规清单化登记（类别分布见 §4 R3），处置=登记不修码；候选修复沿 TASK-018 先例声明模块化 checkstyle 配置，属后续变更。
- **BACKLOG 推荐序刷新**（R4）：F 系四方向封盘清空后的推荐序行替换，UTF-8 保持原编码与原行尾。

## 8. 异常与偏差登记

- **D1（留痕-1 预注册项实况偏差）**：S2.1 预注册「RecordControllerTest.java 补末尾换行」经 recon 核验已于 TASK-224（fabeb60）编辑时清偿——HEAD 末字节 0x0A 在位、行尾纯 LF；本课题对该文件 **零 diff**（满足「除末字节 0x0A 外零 diff」超集），清偿事实登记入账，不虚构「已补」动作。
- **D2（静态台账口径）**：`--static=user-service`/`--static=api` 非本仓声明插件模块，通用 sun_checks 规则集下的 1023/487 项违规为基线偏差，登记不修码；静态门仅对声明并强制的 record-service/leaderboard-service 生效且 rc=0，本课题不触碰两模块静态面。
- **D3（页面源码旧口径未改）**：record-submit 页「本会话记录」卡片标题仍含「无服务端『我的记录』列表」旧口径文本（TASK-211 前遗留），属 web src 页面源码——TASK-226 白名单不含 web src，未改；web/README.md 对应 README 段落已订正（R2.6），页面文本订正登记为后续文档化变更候选。
- **D4（临时件落 `.trae/tmp`）**：token 计算、词面门扫描、契约门日志、offline 日志等临时件均落 gitignored `.trae/tmp`，不入库，命令行参数零中文。
- **D5（C-02 自指哈希）**：C-02 台账笔包含 handoff.md 自身，其 commit hash 按先例记载，以最终 `git log` 为准。
- **停止条件（§3）核对**：无一按字面触发——
  - §3.1 静态违规修复超范围：未修码，仅台账化登记（R3）；
  - §3.2 README 订正涉及行为承诺变更：7 处订正均为功能清单/描述口径更新，不构成行为承诺变更；
  - §3.3 offline record-service 任何用例变化：实测 250 逐位不变（G1）。

## 9. 收口门禁（C-02 后）

- 契约门**无参 rc=0**（baseline 默认 HEAD=C-02，本 handoff 相对基线无变动 → 已收口不重审）。
- token 收口态 SUM=**2036**，≥2034 基线；改动集 7 文件 token 扫描 ZERO_HIT。
- 词面门改动集（含 C-02 四文件）CI-exact ZERO_HIT + 探针阳性对照 HIT；`git diff --check` rc=0；工作树 `git status` 干净。
- `git diff --name-only 11364af` 对照恰 **7 文件**（C-01 3 + C-02 4）：越界面 diff 全空。

## 10. 未覆盖与不得推出的结论

- `--mode=online`、CI 全流程未跑（未 push、未建 PR）；外部门槛待指导侧推动推送后补记。
- **user-service/api 静态违规未修复**（本课题红线：只登记不修码）；修复候选（模块化 checkstyle 配置 + 逐类治理）登记为后续变更方向，不得据此推断静态面已合规。
- **record-submit 页面「无服务端我的记录列表」旧口径文本未订正**（web src 白名单外），登记为后续文档化变更候选。
- 本课题为纯文档/台账批次，无新功能、无测试逻辑变更；留痕-1 清偿为中途提交既有事实的核验登记，非本课题新动作。
