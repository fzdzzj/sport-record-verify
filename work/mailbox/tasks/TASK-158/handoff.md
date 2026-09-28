# TASK-158 回传（handoff）

## 结论

不变式已恢复：`wire-verify-outbox` 与 `adopt-native-mq-retry` 两个归档提案的 spec-delta 已按「先 wire 后 adopt」顺序（adopt delta L3-L7 合并口径）逐字并入主规格——ADDED「判定事件可靠投递」40 行块与主规格 cmp rc=0；MODIFIED「校验事件与幂等」5 场景终态 45 行块与主规格 cmp rc=0、标题 occurrences=1。头部提案清单 38 → **40**；`spec/changes/archive` 42 目录 − 清单 40 = 恰 2 行合法例外（说明行已并入主规格，集合不变式自解释）。主规格 L47 断言「各提案的 spec-delta 中 ADDED 需求已全部合并进本规范，MODIFIED 需求按规则处理」由假变**真**（原文一字未动）。纯文档零代码（零 .java/.sql/.yml/.properties/pom/scripts 改动）；offline 双跑逐位一致；**未 push，未达外部门槛**。另：收口态 G6 有 1 行命中（TASK-158/spec.md:179 自检脚本内嵌正则字面量，spec 内生冲突，需指导侧处置），详见补记——该发现不影响不变式恢复与 L47 判真。

## 起点与三笔提交

- 起点 HEAD = `779a293835ada6f20f0db24066d0d75a25f8f06f`（开工 `git rev-parse` 逐位核对一致）；`git rev-list --left-right --count origin/main...main` = `0	2`。
- C1 = `5fb302babb558fc58684a5497469058b4c236dc6`（并入 wire-verify-outbox）。暂存清单 `git diff --cached --name-status` 原文：
  ```
  R100	spec/changes/wire-verify-outbox/proposal.md	spec/changes/archive/wire-verify-outbox/proposal.md
  R100	spec/changes/wire-verify-outbox/specs/sport-record-verify/spec-delta.md	spec/changes/archive/wire-verify-outbox/specs/sport-record-verify/spec-delta.md
  R082	spec/changes/wire-verify-outbox/tasks.json	spec/changes/archive/wire-verify-outbox/tasks.json
  M	spec/specs/sport-record-verify/spec.md
  ```
- C2 = `d3c42849cf5d40d5d7980bd873b9f0742f13f107`（并入 adopt-native-mq-retry + 头部例外说明）。暂存清单 `git diff --cached --name-status` 原文：
  ```
  R100	spec/changes/adopt-native-mq-retry/proposal.md	spec/changes/archive/adopt-native-mq-retry/proposal.md
  R100	spec/changes/adopt-native-mq-retry/specs/sport-record-verify/spec-delta.md	spec/changes/archive/adopt-native-mq-retry/specs/sport-record-verify/spec-delta.md
  R056	spec/changes/adopt-native-mq-retry/tasks.json	spec/changes/archive/adopt-native-mq-retry/tasks.json
  M	spec/specs/sport-record-verify/spec.md
  ```
- C3 = 本笔台账提交（自身 SHA 无法自引，以 `git log -1` 事后核对，见补记）。提交前 `git diff --cached --name-status` 原文见补记。
- R100×4 证实 4 个移名文件 blob 与起点逐字一致；R082/R056 为两份台账文件追加归档 task 后的相似度（adopt 侧已补录的 HEAD=58cd104 收口复跑证据原样保留，脚本断言 `58cd104` 存在于解析后的任务数据）。

## 只改清单（7 项逐项对齐，含移名落点）

1. spec/specs/sport-record-verify/spec.md —— 恰 4 编辑点 + 1 纯追加行：ADDED「判定事件可靠投递」40 行块插入（「校验事件与幂等」块之后、「规则阈值可配置」之前，块间 1 空行）；「校验事件与幂等」先按 wire 4 场景版整段替换（C1）、再按 adopt 5 场景终态版整段替换（C2）；头部清单 +2 行；变更历史 +2 条；L47 断言句之后纯追加清单外合法例外说明 1 行（L47 原文一字未动）。搬块行尾 LF→CRLF，末行补换行符（G3 伪影口径）。
2. spec/changes/archive/wire-verify-outbox/tasks.json —— 追加归档阶段 task（number 5，2 steps 全 completed、passes true，TASK-127 先例格式）。
3. spec/changes/archive/adopt-native-mq-retry/tasks.json —— 同上追加；在 HEAD=58cd104 已补录收口复跑证据版本之上追加，该段证据未覆盖（解析等价 + 拼接后整体仍为 canonical json 格式，脚本断言通过）。
4. git 索引 —— 删除侧 6 路径与 archive 6 文件逐路径 add（禁 -A/add .），移名在 C1/C2 成形（R100×4、R082/R056×2）。
5. work/mailbox/PLAN.md —— 纯追加 1 个「## 验收记录：TASK-158 …」节（48 行，两列表头格式照既有验收记录），追加于文件末尾；既有行零改动（`git diff --numstat` = 48 insertions / 0 deletions，CR=0 全程 LF）。
6. work/mailbox/tasks/TASK-158/spec.md —— **未改动**（指导侧原样首次入库，一个字未改）。
7. work/mailbox/tasks/TASK-158/handoff.md —— 本文件，执行侧新建。
8. 移名落点 4 文件（随第 4 项索引操作成形，4 个 blob 与起点逐字一致、未改一字）：spec/changes/archive/wire-verify-outbox/proposal.md、spec/changes/archive/wire-verify-outbox/specs/sport-record-verify/spec-delta.md、spec/changes/archive/adopt-native-mq-retry/proposal.md、spec/changes/archive/adopt-native-mq-retry/specs/sport-record-verify/spec-delta.md

## 触发事实复核（执行侧亲跑后方可采信）

- 起点态主规格对 `outbox|relay|markSent`（-iE）0 命中：`git grep -c -iE 'outbox|relay|markSent' 779a293… -- spec/specs/sport-record-verify/spec.md` → **rc=1**（0 命中）。
- 代码锚点（HEAD 实测）：`VerifyOutboxRelay.java:83` `relay-interval-ms:5000`、`:54` `max-retry:16`；`LeaderboardEventConsumer.java:56/132` `MAX_RECONSUME_TIMES = 3` / `setMaxReconsumeTimes(MAX_RECONSUME_TIMES)`（VerifyEventConsumer 同构）；判别式 `LeaderboardEventConsumerTest.java:83` `buildConsumer_enablesNativeMaxReconsumeTimes3`。
- 反例核实：`VerifyEventOutboxMapper` 取批资格（`retry_count < maxRetry`）已在代码，其 delta 未并入——本轮按红线不并入，仅入欠账登记。
- `ci.yml:72` `Public docs wording self-check` 在（G6 用同款正则 + 三排除）。
- blob 逐字比对（`git hash-object` vs `git rev-parse HEAD:`）：archive 6 文件中 5 个与起点 blob 相同，唯一不同 = adopt 侧台账文件（已补录证据，必须保留）。

## G1–G9 实测输出与退出码原文

（G1–G4/G6/G7 读数取自 C2 后工作树终态实跑——C3 只触 work/mailbox 与 TASK-158 两文件，对主规格/archive 零影响；收口复跑全文见补记。开工态对照值一并给出，证明脚本非恒绿。）

### G1 逐字判据（硬）

```
G1a delta=40/40 spec=40/40
G1a_cmp_rc=0 expect=0
G1b delta=45/45 spec=45/45 occurrences=1/1
G1b_cmp_rc=0 expect=0
```
开工态对照：`G1a delta=40/40 spec=0/40`、`cmp: EOF on .trae/tmp/g1b.txt which is empty`、`G1a_cmp_rc=1`；`G1b delta=45/45 spec=25/45 occurrences=1/1`、`differ: byte 198, line 5`、`G1b_cmp_rc=1`。

### G2 集合不变式（硬）

```
G2 list=40/40 arch=42/42
G2 arch-minus-list (expect exactly 2: add-microservice-skeleton / add-sharding-host-parameterization):
add-microservice-skeleton
add-sharding-host-parameterization
G2 list-minus-arch (expect empty):
（空输出）
```
开工态对照：`G2 list=38/40 arch=42/42`，comm -23 为 4 行（上述 2 行 + `adopt-native-mq-retry`、`wire-verify-outbox`），comm -13 空。

### G3 无误删（硬，最强判据）

`git diff -U0 779a293… HEAD -- spec/specs/sport-record-verify/spec.md` 全部 @@ 行与删除行原文：

```
@@ -45,0 +46,2 @@
@@ -47,0 +50 @@
@@ -900,0 +904,5 @@ WHEN 校验流程产生状态变化,
@@ -908,0 +917,7 @@ AND verify 消费后拉轨迹执行判定并回调
@@ -915,0 +931,7 @@ AND 业务仅执行一次
@@ -918 +940 @@ AND 业务仅执行一次
-GIVEN 消费失败达到重试阈值
@@ -920 +942,2 @@ WHEN 消费者无法处理
-THEN 消息进入 record-verify-events-dlq
@@ -922,0 +946,41 @@ AND 可人工排查
@@ -2699 +2763,3 @@ AND 下次读取回源到最新值
\ No newline at end of file
```

含删除的旧行号区间 = `-918`、`-920`（被替换「校验事件与幂等」旧块 [897,921] 内）与 `-2699`（末行伪影：起点 L2699 无行尾换行符，本轮改为以换行符结尾，该行删除侧与重加侧逐字一致，同 hunk 重加侧另 2 行 = wire/adopt 两条新增变更历史）。除此之外零删除，其余 2674 行一字未动。C3 不触主规格，收口 HEAD 下该 @@ 集合不变（补记复跑确认）。

### G4 行尾完整性（硬）

```
G4 spec/specs/sport-record-verify/spec.md CR=2765 LF=2765 bareLF=0
G4 work/mailbox/PLAN.md CR=0 LF=1071 bareLF=1071
```
计数法 `tr -cd '\r' | wc -c` / `tr -cd '\n' | wc -c`（未用 `grep -c $'\r$'`）。两文件均无 BOM。开工态对照：spec CR=2698/LF=2698、PLAN CR=0/LF=1023。G4b 末字节：`tail -c 2 | od -An -tx1` → `0d 0a`（开工态为 `80 82`，即末行原无 0a，本轮有意补尾换行 = G3 伪影同源）。

### G5 offline 双跑零扰动（硬）

- 第一次（开工基线）：`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS / Total 04:01**，逐模块汇总行原文 `Tests run: 36, Failures: 0, Errors: 0, Skipped: 0` × 7 档 = **36/41/33/103/110/59/10**（与指导侧同日参考值逐位一致），全程无 FAILURE。
- 第二次（收口，C3 后）：`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS / Total 02:29**，逐模块汇总行与第一次**逐位一致** = **36/41/33/103/110/59/10**，Failures/Errors/Skipped 全 0 ⇒ 零扰动成立。

### G6 词面自检（双 locale，三态实测）

开工态与 C2 后态（TASK-158 两件套尚未 tracked，git grep 不可见）：

```
G6_C=ZERO_HIT
G6_zh_CN.UTF-8=ZERO_HIT
```

收口态（C3 已把 TASK-158/spec.md 入库后复跑）：

```
G6_C=HITS lines=1
G6_zh_CN.UTF-8=HITS lines=1
```

唯一命中行 = `work/mailbox/tasks/TASK-158/spec.md:179` —— 指导侧 spec.md「可直接使用的自检脚本」代码块内嵌的 G6 正则字面量自身（含全部禁词，此处不逐字转录以免制造新命中载体）。CI 的 Public docs wording self-check 扫全部 tracked 文本、仅三排除（archive/**、docs/internal/**、ci.yml），ci.yml 对自身排除的理由（本文件自身携带该正则则门槛永远红）同样适用于该文件 ⇒ **下次 push 会打破 CI 词面门槛**。属 **spec 内生冲突**（C3 入库 spec.md 与 G6 收口 0 命中两个要求不可同时成立），执行侧无权改写 TASK-158/spec.md（一个字）也不得改 ci.yml（不在只改清单），如实披露待指导侧处置；不影响 G1-G5/G7-G9 与不变式恢复本身。

### G7 空白与提交

- `git diff --check` → **rc=0**（开工/中途/收口各次实测均 0）。
- C1 `git show --check` 干净、C2 `git show --check` 干净（C3 见补记）。

### G8 契约

- 在途（开工，TASK-158 仅 spec）：`bash scripts/verify/mailbox-contract.sh --open TASK-158 --baseline=779a293…` → **rc=0**（`进行中（仅 spec）：TASK-158 已声明，列入待办放行`；判据 A 两件套齐含 1 个待办进行中 + 判据 B 清单一致）。
- 在途（handoff/PLAN 已写、C3 未提交）：`--open TASK-158 --baseline=779a293…` → **rc=1**，TASK-158 的判据 B 过冲恰 4 条 = add-verify-degrade-status-index 的既有未跟踪 4 文件（清单多报 0 条），其余为历史任务公共文件交叠（.trae/tmp/g8-inflight-handoff-written.log）。
- 收口（三笔提交后）：无参数复跑 **rc=0**（硬门槛达成）；`--baseline=779a293…` 复跑 rc=1（过冲构成与在途相同）——逐条见补记。

### G9 外部门槛

未 push ⇒ PLAN 验收记录「是否到达外部门槛」栏写「**未达外部门槛**（本次不 push，待下次授权由 CI 复验）」。未跑 `--mode=online`，无 CI run 证据，不把 offline 绿表述为外部门槛绿。

## 欠账登记自行复核（19 目录）

steps/passes 用自检脚本末段实跑（python 分支成功，未降级 node/grep 粗核），tracked 用 `git ls-files` 实数。19 行与指导侧表格**逐行一致，零差异**：

```
add-verify-degrade-status-index 0/6 allPass=False tracked=0（未跟踪，从未启动，共 4 文件未跟踪）
fix-verify-outbox-poison-head-of-line 10/10 allPass=True tracked=3
measure-head-bottleneck-attribution 14/15 allPass=True tracked=3
measure-submit-db-wait-evidence 13/13 allPass=True tracked=3
measure-submit-pool-capacity 13/13 allPass=True tracked=3
measure-verify-event-stage-lag 11/11 allPass=True tracked=3
measure-verify-mark-sent-admin-window 9/9 allPass=True tracked=3
measure-verify-mark-sent-spring-paired-cost 10/10 allPass=True tracked=3
measure-verify-outbox-mark-sent-cost 10/10 allPass=True tracked=3
measure-verify-outbox-mark-sent-server-event 5/10 allPass=False tracked=3
measure-verify-outbox-relay-cost 11/11 allPass=True tracked=3
prove-verify-mark-sent-wait-attribution 10/10 allPass=True tracked=3
prove-verify-outbox-batch-mark-safety 10/10 allPass=True tracked=3
prove-verify-outbox-mark-sent-attribution 10/10 allPass=True tracked=3
prove-verify-outbox-mark-sent-spring-wiring 10/10 allPass=True tracked=3
prove-verify-outbox-relay-concurrency-scaling 10/10 allPass=True tracked=3
resume-verify-outbox-mark-sent-server-event 4/9 allPass=False tracked=3
shorten-submit-db-footprint 17/17 allPass=True tracked=3
update-verify-outbox-relay-delay 7/8 allPass=False tracked=3
```

即 15 个已完成但从未并入、从未归档，3 个未全绿，1 个未启动；全表与下一轮合并难点（3 深 MODIFIED 链、对既有基线需求的 MODIFIED）及下一轮首候选（fix-verify-outbox-poison-head-of-line）已原样登记进 PLAN 验收记录「未覆盖/后续」栏。

## 未覆盖项与不得推出的结论

1. **未 push 未过 CI**：本轮 3 笔提交仅在本地 main（收口时 `origin/main...main` = 0 behind / 5 ahead）；未跑 `--mode=online`、无 CI run ⇒ 不得把 offline 绿表述为外部门槛绿。
2. **未合并其余 19 个在途 delta**：主规格不含它们任何需求；`fix-verify-outbox-poison-head-of-line`、`shorten-submit-db-footprint` 等已改代码的实质变更仍未并回规范。
3. **主规格仍缺**：TASK-142 取批资格（`VerifyEventOutboxMapper` 的 `retry_count < maxRetry`，代码已实现）与 relay 诊断（`VerifyOutboxRelay` 的 `relay-diagnostics-enabled`/`relay-diagnostics-window-ms`）相关需求——本轮按红线不并入。
4. **本轮不构成任何性能结论**：纯文档任务，未跑任何负载/基准实验。
5. **PLAN.md L4 为已知陈旧断言**（「已 push 至 origin/main（58cd104..de81b59）…= 0 0」在其后被 0f62dbf/779a293 及本轮 3 笔提交变假）：本轮纯追加未改 L4，未据其推出任何「已推送」结论；留待下一次推送批次连同新 CI run 证据一并订正。
6. **只读范围声明**：work/mailbox/tasks/TASK-156/**、docs/perf/**、spec/changes/prove-verify-outbox-relay-concurrency-scaling/**、verify-service/src/test/** 本轮只读未动；在台账交叉引用与触发事实代码锚点复核范围内未发现需订正的可疑之处（未做更深审计）。
7. **收口实测的落库方式**：G5 第二次与 G8 收口复跑在 C3 提交后实测，随下方补记以 amend 方式落库（与 TASK-157「补记（收口实测，提交后补录）」先例同构）；除补记外无任何未跑门禁被写成已跑。

## 补记（收口实测，提交后补录）

- C3（初版）= `633980ac7ab29e27a26db962a762c35167468122`（3 files changed, 428 insertions；提交前 `git diff --cached --name-status` 原文：`M work/mailbox/PLAN.md` / `A work/mailbox/tasks/TASK-158/handoff.md` / `A work/mailbox/tasks/TASK-158/spec.md`；`git show --check` 干净）。本补记连同 G5/G6/G8 收口读数随 C3 以 `git commit --amend --no-edit` 落库；amend 后最终 SHA 无法自引，以 `git log -1` 事后核对（见执行方最终回传）。amend 只增改 handoff/PLAN 台账内容，不触任何代码与规格文件。
- **G5 第二次（收口）**：`bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0 / BUILD SUCCESS / Total 02:29，逐模块 36/41/33/103/110/59/10，与开工基线（Total 04:01）**逐位一致**，Failures/Errors/Skipped 全 0 ⇒ 零扰动成立。
- **收口全量自检**（`bash .trae/tmp/task158-verify.sh 779a293…`，script_rc=0）：G1a cmp rc=0（40/40）、G1b cmp rc=0（45/45、occurrences=1/1）、G2 list=40/40 arch=42/42 且 comm -23 恰 2 行、G3 @@ 集合与本文 G3 节所录一致（删除仅 -918/-920/-2699）、G4 spec CR=2765=LF 且 PLAN CR=0/LF=1071、G4b 末字节 `0d 0a`、G7 `git diff --check` rc=0。
- **G8 收口读数**：无参数 `bash scripts/verify/mailbox-contract.sh` → **rc=0**（`契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`；TASK-158 足迹不在工作树，视为已收口）。`--open TASK-158 --baseline=779a293…` → **rc=1**：TASK-158 自身的判据 B 失败过冲**恰 4 条** = `?? spec/changes/add-verify-degrade-status-index/` 的 proposal、delta、台账 json、verification 4 个既有未跟踪文件（清单多报 0 条），满足 G8「过冲仅来自既有脏项」的条件说明；整体 rc=1 的其余来源为历史任务（TASK-106~157）回传对 spec.md/PLAN.md 的公共文件交叠——TASK-127 记载的「PLAN.md 公共文件过冲」同一既知模式，不属本任务（.trae/tmp/g8-final-baseline.log）。
- **G6 收口态披露（spec 内生冲突，需指导侧处置）**：三态实测见本文 G6 节。根因：指导侧 spec.md 把自检脚本（内嵌禁词正则字面量）写进自身正文，C3 按交回物要求把该文件原样入库，使 CI 同款命令在收口态必然命中 1 行（开工/中间态因未 tracked 而 0 命中，与 spec 撰写时的实测一致）。执行侧无权修（spec.md 一个字不能改；ci.yml 不在只改清单），未做任何规避动作；候选处置（指导侧定）：给 CI 词面自检增加对 work/mailbox/** 的排除（与 ci.yml 自身排除同理）、或订正 spec.md 内嵌正则的书写形态。本任务交付物（不变式恢复、L47 判真）不受影响。
- 收口态 `git status --porcelain` 仅剩 `?? spec/changes/add-verify-degrade-status-index/`（既有脏项，原样未动）；`.trae/tmp/` 下脚本与证据日志均被 .gitignore 忽略。
- G9 维持：未 push、未建 PR、未跑 --mode=online，**未达外部门槛**；PLAN 验收记录外部门槛栏已如实标注。

---

## 指导侧订正（C4，2026-09-28）：G6 内生冲突处置

**缺陷归属：指导侧任务书自身，不是执行侧问题。** `spec.md` L179 原样内嵌了 CI 词面门的正则字面量，而只改清单第 6 项又要求把 `spec.md` 入库 ⇒ 「C3 入库」与「G6 收口 ZERO_HIT」在数学上不可同时成立。执行侧开工/中间态实测 0 命中（当时两件套未 tracked，与撰写时实测一致）、收口态实测双 locale 各 1 行命中，**未做任何规避、如实披露并停手待处置**——判定正确、处置得当。

**处置**：把 L179 改写为**字符类拆开**的等价形式（`面[试]|弹[药]|大[厂]|八[股]|简[历]|求[职]|突[击]|附[录] ?A`）。脚本仍可运行、匹配集合不变，而该行自身不再含被禁字面量。除 L179 外，`spec.md` 其余 212 行**一字未动**。

**否决的替代方案**：给 `ci.yml` 增加 `:!work/mailbox/**` 排除。理由——`ci.yml` L65–L67 明写该门是**故意**从「扩展名白名单取样」扩为「全部 tracked 文本文件」，并注明白名单是「新增文件类型要记得同步扩 glob」的自设盲区、曾让 4 处命中长期躲在 java/yml 注释里不被发现；而 `work/mailbox/` 并未被 `.gitignore` 声明为不公开（与 `docs/internal/**` 的排除理由不同类）。加该排除等于把刚堵上的盲区重新打开，**用削弱门槛来掩盖任务书缺陷，方向错误**。

**等价性实测（Level A，指导侧亲跑，`.trae/tmp/eqtest.sh`）**：
- 7 行样本（6 行各含一个禁词、第 6 行同时覆盖「附录」紧接 A 与两者之间夹一个空格这两种形态，1 行无关文本）。
- 旧式命中 6 行；新式命中**同样 6 行**；两者命中行号 `diff` → **rc=0**（匹配集合完全等价）。
- 新式源码行 `grep -q -iE "$OLD"` → **SELF_MATCH=NO**（不再自命中）。
- 旧式源码行同测 → **OLD_SELF_MATCH=YES**（本缺陷根因，已隔离复现）。

**C4 后复验（指导侧亲跑，见 PLAN 订正记录）**：G6 双 locale **ZERO_HIT**；`git diff --check` **rc=0**；无参 `mailbox-contract.sh` **rc=0**；`mvn-verify.sh --mode=offline test` **rc=0 / BUILD SUCCESS**，模块数 **36/41/33/103/110/59/10** 与 C3 后复跑逐位一致（C4 零代码改动）。

**C4 未改变的结论**：不变式已恢复、主规格 L47 断言由假变真（原文一字未动，仅由 L47 位移至 L49）；仍**未 push、未达外部门槛**。

**顺带查出的第二个 harness 陷阱（指导侧本轮亲踩，已登记进 PLAN）**：`git grep` 的 `--untracked` **必须置于 pattern 之前**。指导侧写成 `git grep -n -I -iE "$RE" --untracked -- <pathspec>`，git 2.20.1.windows.1 把 `--untracked` 当成 revision，报 `fatal: unable to resolve revision: --untracked`、返回 **rc=128**；而 `if git grep ...; then HITS else ZERO_HIT` 把 **rc=128 与 rc=1 同等看待**，于是输出**假 ZERO_HIT**。指导侧是靠**回读输出文件内容**（而非只信 rc）才发现的。⇒ 后续词面门一律**三态判定**：`rc=0` 有命中 / `rc=1` 无命中 / **其他 rc ＝ 工具错误，判失败不判通过**。

**指导侧本轮自造又自纠的一处**：C4 首版把等价性样本描述成字面「附录」+A 两种形态，**自身触发了词面门 2 处新命中**（`PLAN.md:1078`、本文件 L198），已在提交前改为不含该字面量的等价表述。这印证了本次订正立的红线：**任何要入库的文档，连"描述禁词"都不能用禁词的字面量**。
