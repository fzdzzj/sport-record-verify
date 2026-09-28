# TASK-159 回传（handoff）

## 结论

不变式维持：`fix-verify-outbox-poison-head-of-line` 的 2 条纯 ADDED 需求已逐字并入主规格（G1 EOL 归一 cmp rc=0 ×2、两标题 occurrences=1/1），头部提案清单 40 → **41**，`spec/changes/archive` 42 → **43**，`archive − 清单` 差集仍恰为 2 项合法例外（`add-microservice-skeleton`、`add-sharding-host-parameterization`，例外说明行原文未动）；主规格 2765 → **2806** 行**零删除**（G3 全部 3 个 @@ 均纯插入形态、`G3_hunks_with_deletion=0`，本轮最强判据成立）。纯文档零代码（零 .java/.sql/.yml/.properties/pom/scripts 改动）；offline 双跑逐模块数字逐位一致；未 push，**未达外部门槛**。

## 起点与两笔提交

- 起点 HEAD = `da6e3ee350f60c323561ed3fe1e9d98a6f2e3941`（开工 `git rev-parse` 逐位核对一致）；`origin/main` = `83c98a4695c404c17433a89e1410cab41b2ffa42`；`git rev-list --left-right --count origin/main...main` = `0	2`。
- C1 = `e0c3ae424bf9ce634d1598cc7f7011e1c1408e0d`（并入 + 归档移名）。暂存清单 `git diff --cached --name-status` 原文：
  ```
  R100	spec/changes/fix-verify-outbox-poison-head-of-line/proposal.md	spec/changes/archive/fix-verify-outbox-poison-head-of-line/proposal.md
  R100	spec/changes/fix-verify-outbox-poison-head-of-line/specs/sport-record-verify/spec-delta.md	spec/changes/archive/fix-verify-outbox-poison-head-of-line/specs/sport-record-verify/spec-delta.md
  R077	spec/changes/fix-verify-outbox-poison-head-of-line/tasks.json	spec/changes/archive/fix-verify-outbox-poison-head-of-line/tasks.json
  M	spec/specs/sport-record-verify/spec.md
  ```
  同一暂存态 `git diff --cached --name-only` 原文（4 行）：
  ```
  spec/changes/archive/fix-verify-outbox-poison-head-of-line/proposal.md
  spec/changes/archive/fix-verify-outbox-poison-head-of-line/specs/sport-record-verify/spec-delta.md
  spec/changes/archive/fix-verify-outbox-poison-head-of-line/tasks.json
  spec/specs/sport-record-verify/spec.md
  ```
  R100 双证：`git rev-parse` 双侧 blob id 相等（proposal `6c5b5728` = `6c5b5728`、spec-delta `131d384e` = `131d384e`）+ git rename 检测 100%；R077 为 tasks.json 追加归档 task 后相似度。提交统计 `4 files changed, 52 insertions(+)`，`git show --check` 干净。
- C2 = 本笔台账提交（收口实测以补记随本笔 `git commit --amend --no-edit` 落库，TASK-157/TASK-158 先例同构；amend 后最终 SHA 无法自引，以 `git log -1` 事后核对，见补记）。提交前 `git diff --cached --name-status` 原文见补记。

## 只改清单（6 项逐项对齐，含未改动声明）

1. spec/specs/sport-record-verify/spec.md —— 恰 3 编辑点：① 头部清单 L47 末条之后插入 1 条（全角括号，41 条，断言句/例外说明行仅位移至 L50/L51、原文一字未动）；② 「判定事件可靠投递」块之后、「规则阈值可配置」之前插入 Q1（22 行）+ 1 空行 + Q2（15 行）+ 1 空行 = 39 行（行尾幂等配方转 CRLF，与 delta L3-L24/L26-L40 EOL 归一后逐字一致）；③ 文件末尾追加变更历史 1 条（正文含缺陷、修复与 proposal 三条反过度声称口径原文继承）。2765 → 2806 行。
2. spec/changes/archive/fix-verify-outbox-poison-head-of-line/tasks.json —— 追加 1 个归档阶段 task（number 6，category「阶段 6：归档」，2 steps 全 `completed: true`、`passes: true`，照 TASK-127/TASK-158 先例；追加后 6 tasks / 12 steps 全绿，python 解析断言通过）。
3. git 索引 —— 删除侧 3 路径与 archive 侧 3 文件（spec/changes/archive/fix-verify-outbox-poison-head-of-line/proposal.md、spec/changes/archive/fix-verify-outbox-poison-head-of-line/specs/sport-record-verify/spec-delta.md、spec/changes/archive/fix-verify-outbox-poison-head-of-line/tasks.json）**逐路径** `git add`（git mv 成形移名 + 显式 add 台账文件与主规格，未用 `-A`/`add .`），R100 ×2、R077 ×1。
4. work/mailbox/PLAN.md —— 纯追加 1 个「## 验收记录：TASK-159 …」节于文件末尾（含欠账登记 18 目录自核表与下轮合并难点、G0-G9 实测读数、「未达外部门槛」声明）；既有行零改动（含 L4；`git diff --numstat` 仅 insertions / 0 deletions，见补记）。
5. work/mailbox/tasks/TASK-159/spec.md —— **未改动**（指导侧原样首次入库，执行侧一个字未改）。
6. work/mailbox/tasks/TASK-159/handoff.md —— 本文件，执行侧新建。
- **未改动声明**：`spec/changes/` 其余 18 个在途目录零触碰；`?? spec/changes/add-verify-degrade-status-index/` 原样未动（4 文件保持未跟踪）；TASK-156/157/158 信箱目录与 docs/perf 只读未动；两个 outbox 主代码文件与 verify-service 测试目录只读未动（G0 仅核对一致性）；CI 工作流定义与 scripts/verify 下脚本未动；未创建 `.mvn/maven.config`、未设 `MAVEN_OPTS`、未裸用 mvn。
- **口径说明（spec 第 5 步句与提交结构的关系）**：spec「第 5 步」句（意引）「C2 只碰 work/mailbox/** 与 archive 侧台账文件」与同文件「硬性顺序」（C1 = 主规格 3 处 + 移名 3 文件 + archive 侧台账文件 → C2 = PLAN + 两件套）、「收口门槛·提交结构」（C1 = 并入 + 归档移名，含 archive 侧台账文件；C2 = PLAN 验收记录 + TASK-159 两件套）及「只改清单第 3 项」（archive 侧台账文件因第 2 项而低于 100，即编辑与移名同笔成形）存在张力。本轮按后三者 + TASK-158 先例执行（其并入提交 d3c4284 的暂存清单即 R100×2 + R056 台账文件 + M 主规格，台账提交 aea2149 恰为 PLAN + 两件套）：archive 侧台账文件追加随 C1 入库，C2 恰为 3 个 work/mailbox 路径。TASK-159 任务书一字未动，特此披露供指导侧核验。

## 触发事实复核（执行侧亲跑后方可采信）

- 主规格结构（起点实读）：L8-L47 连续 40 条 bullet、L48 空行、L49 断言句、L50 例外说明行；L946 `### Requirement: 判定事件可靠投递` 块至 L985、L986 空行、L987 `### Requirement: 规则阈值可配置`；末两条变更历史 L2764/L2765 格式 `- **<目录名>**：…引用变更 spec/changes/archive/<目录名>/。`。全部成立。
- 主规格 2765 行、worktree 全 CRLF（CR=2765/LF=2765/bareLF=0）、末字节 `0d 0a`：成立。
- delta 40 行 CR=1@字节 2108（末行）、proposal 25 行 CR=1@字节 3966（末行）：成立（G4b python 二进制读）。
- poison tasks.json 起点态 5 tasks / 10 steps 全 `completed` / `passes`：成立（追加后 6/12）。
- 代码 5 锚点成立（见 G0）。`ci.yml:72` `Public docs wording self-check` 在（G6 用同款正则 + 三排除）。
- `core.autocrlf=true`、blob 以 i/lf 存储、worktree w/crlf（`git ls-files --eol` 实测）——G3 的零删除判据在 blob 级成立，G4 的行尾判据在工作树级成立，两者不矛盾。

## G0–G9 实测输出与退出码原文

（读数取自 C1 后工作树终态实跑——C2 只触 work/mailbox 三文件，对主规格/archive/代码零影响；开工态对照值一并给出，证明脚本非恒绿。收口态复跑随补记落库。）

### G0 代码↔delta 一致性（只读，硬）

```
23:    @Select("SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < #{maxRetry} " +
118:            batch = outboxMapper.selectPendingBatch(batchSize, maxRetry);
G0_mapper_hits=1 expect=1
G0_relay_hits=1 expect=1
```
另 3 处锚点执行侧补核（sed 实读）：Relay L55 `private int maxRetry;`（上文 `@Value("${verify.outbox.max-retry:16}")`）、L125 `if (row.getRetryCount() != null && row.getRetryCount() >= maxRetry) {`（兜底保留行）、L157 `outboxMapper.markSent(row.getId());`；Mapper L19-L21 javadoc「…耗尽行本身保留原状态与原数据供人工处理（不删除、不重置计数、不改 eventId、不重投）」。5 处全成立，代码未漂移。

### G1 逐字判据（硬）

```
G1 Q1 delta=22/22 spec=22/22 occ=1/1
G1 Q2 delta=15/15 spec=15/15 occ=1/1
G1_Q1_cmp_rc=0 expect=0
G1_Q2_cmp_rc=0 expect=0
G1_Q1_blk_vs_sed3_24_rc=0 expect=0
G1_Q2_blk_vs_sed26_40_rc=0 expect=0
```
开工态对照：`G1 Q1 delta=22/22 spec=0/22 occ=0/1`、`G1 Q2 delta=15/15 spec=0/15 occ=0/1`、`cmp: EOF on .trae/tmp/b1n.txt which is empty`、两 cmp rc=1（spec 侧空，预期报红）。

### G2 集合不变式（硬）

```
G2 list=41/41 arch=43/43
G2 arch-minus-list (expect exactly 2):
add-microservice-skeleton
add-sharding-host-parameterization
G2 list-minus-arch (expect empty):
（空输出）
```
开工态对照：`G2 list=40/41 arch=42/43`，comm -23 同为上述 2 行、comm -13 空。

### G3 主规格零删除（硬，本轮最强判据）

`git diff -U0 da6e3ee… HEAD -- spec/specs/sport-record-verify/spec.md` 全部 @@ 行原文（3 个，全为纯插入形态）：

```
@@ -47,0 +48 @@
@@ -986,0 +988,39 @@ AND 超过阈值（默认 16）仅记录告警并保留行供人工处理，SHAL
@@ -2765,0 +2806 @@ AND 下次读取回源到最新值
G3_hunk_count=3 expect=3
G3_hunks_with_deletion=0 expect=0
G3_spec_lines=2806 expect=2806
```
`G3_hunks_with_deletion` 的判据：所有 @@ 旧区间长度（`-N,0` 中的 0）经 sed 提取逐个核为 0 ⇒ 零删除。开工态对照：`G3_hunk_count=0`、`G3_spec_lines=2765`。另执行侧在组装时做字节级区域校验（python 二进制比对 HEAD blob 与新文件）：47+1+939+22+1+15+1+1779+1=2806，未动区域逐字一致、新文件 2806 CRLF / 0 裸 LF / 0 游离 CR。

### G4 行尾完整性（硬）

```
G4 spec/specs/sport-record-verify/spec.md CR=2806 LF=2806 bareLF=0 lastbytes= 0d 0a
G4 work/mailbox/PLAN.md CR=0 LF=1083 bareLF=1083 lastbytes= 82 0a
```
计数法 `tr -cd '\r' | wc -c` / `tr -cd '\n' | wc -c`（未用 `grep -c $'\r$'` 与 awk）；两文件均无 BOM（首 3 字节实测非 `ef bb bf`）。PLAN 追加后的行数与 numstat 见补记。开工态对照：spec CR=2765=LF=2765、末字节 `0d 0a`；PLAN CR=0。

### G4b 游离 CR 字节偏移（python 二进制读，awk/grep 均未用）

```
  spec/changes/archive/fix-verify-outbox-poison-head-of-line/specs/sport-record-verify/spec-delta.md size=2110 CR=[2108] LF=40 lastbyte=0a
  spec/changes/archive/fix-verify-outbox-poison-head-of-line/proposal.md size=3968 CR=[3966] LF=25 lastbyte=0a
```
与开工态（移名前，路径在 spec/changes/ 下）完全一致：CR 仍各恰 1 个、仍在末行 ⇒ 移名为字节保真，游离 CR 未被动过（R100 佐证）。

### G5 offline 双跑零扰动（硬）

- 第一次（开工基线，起点 HEAD）：`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS / Total 01:53**，逐模块汇总行原文 `Tests run: 36, Failures: 0, Errors: 0, Skipped: 0` × 7 档 = **36/41/33/103/110/59/10**（与指导侧参考值逐位一致），全程无 FAILURE。
- 第二次（C1 并入后）：同命令 → **rc=0 / BUILD SUCCESS / Total 01:52**，逐模块 **36/41/33/103/110/59/10**，与第一次汇总行 `diff` 实证逐位一致，Failures/Errors/Skipped 全 0 ⇒ 零扰动成立。
- 第三次（收口，C2 后）随补记落库。

### G6 词面自检（三态 + 正向对照，硬）

正则现场从 ci.yml 提取（`sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' … | head -1`，非空断言通过；**其字面量与探针词形态均不入本文件**，提取长度见下）。四形态实测（C1 后态）：

```
G6 extracted regex length=26 (指导侧实测 26)
  G6[ci-exact] ZERO_HIT rc=1  <== 期望
  G6[C] ZERO_HIT rc=1  <== 期望
  G6[zh_CN.UTF-8] ZERO_HIT rc=1  <== 期望
  G6[C.UTF-8] ZERO_HIT rc=1  <== 期望
```
正向对照（证明 harness 非空跑；探针由 python 以 unicode 转义写入 9 种被禁形态，字面量不落本文件）：

```
  probe written with 9 banned forms
  G6_positive_control rc=0 hits=9 expect rc=0 hits=9
  probe removed; git status --porcelain follows:
?? spec/changes/add-verify-degrade-status-index/
?? work/mailbox/tasks/TASK-159/
```
探针已删（`rm -f` 后 git status 无残留，仅剩 2 个既有未跟踪目录）；三态判定就位（rc=0 判红 / rc=1 无命中 / 其他 rc 判工具错误，本轮无 TOOL_ERROR）。开工态对照：四形态同 ZERO_HIT rc=1、正向对照同 9/9 rc=0。收口态（两件套入库后）随补记落库。

### G7 空白与提交

```
G7_diffcheck_rc=0 expect=0
```
C1 `git show --check` 干净（无输出、rc=0）；C2 见补记。开工态对照 rc=0。

### G8 契约

- 在途（开工，TASK-159 仅 spec）：`bash scripts/verify/mailbox-contract.sh --open TASK-159` → **rc=0**（`进行中（仅 spec）：TASK-159` → `已声明，列入待办放行`；判据 A 两件套齐含 1 个待办进行中 + 判据 B 清单一致）；`--baseline=da6e3ee…` 形态同 **rc=0**。
- 中途/收口（无参与 `--baseline` 复跑）随补记落库。

### G9 外部门槛

未 push ⇒ PLAN 验收记录「是否到达外部门槛」栏写「**未达外部门槛**（本次不 push，待下次授权由 CI 复验）」。未跑 `--mode=online`、无 CI run 证据，不把 offline 绿表述为外部门槛绿。

## 欠账登记自行复核（18 目录）

steps/allPass 用自检脚本末段 python 分支实跑（未降级 node/grep 粗核），tracked 用 `git ls-files` 实数。18 行全表见 PLAN 验收记录「欠账登记表」；与指导侧在 spec「欠账登记」给出的重点读数逐项对照：`add-verify-degrade-status-index` steps=0/6 allPass=False、`measure-verify-outbox-mark-sent-server-event` 5/10 False、`resume-verify-outbox-mark-sent-server-event` 4/9 False、`update-verify-outbox-relay-delay` 7/8 False、`measure-head-bottleneck-attribution` 14/15 **allPass=True**——**逐项一致，零差异**（执行侧自核读数以 PLAN 表为准）。两维度口径再次确认：steps=N/M 与 allPass 相互独立，allPass=True 不等于步骤全做完（14/15 即反例）。收口态 `pending_dirs=18` 实测成立（开工态 19）。

## 未覆盖项与不得推出的结论

1. **未 push 未过 CI**：本轮 2 笔提交仅在本地 main（收口时 `origin/main...main` = 0 behind / 4 ahead，见补记）；未跑 `--mode=online`、无 CI run ⇒ 不得把 offline 绿表述为外部门槛绿。
2. **未合并其余 18 个在途 delta**：主规格不含它们任何需求；3 深 MODIFIED 链（relay 可选诊断）、shorten-submit-db-footprint 的 MODIFIED「轨迹提交幂等」等实质变更仍未并回规范；未全绿/未启动的 4 目录一律未并入。
3. **本轮不构成对饥饿缺陷的事故证据**：尚无真实运行时饥饿事件或新增红测证据，结论只到「代码路径与 SQL 可确认此条件推导」；TASK-138 的 outbox PENDING 1012/SENT 998 系另一场景计数、未证明当时存在耗尽行，不得冒充本缺陷事故证据。
4. **本轮不构成查询耗时改善的声称**：既有 `(status,id)` 索引仍服务顺序扫描、可能需扫过大量耗尽行；本轮未跑任何 SQL/负载/EXPLAIN 实验。
5. **不翻案、不改写**：不翻案 TASK-153/154 的 NO-GO 裁决，不改写 TASK-152/156 的任何数字；本轮为纯文档任务，不构成任何性能结论。
6. **只读范围声明**：TASK-156/157/158 信箱、docs/perf、verify-service 测试、两 Java 主文件、ci.yml、scripts/verify/** 全程只读/未动；PLAN 既有行（含 L4）未动、未据其推出新结论；游离 CR（poison 侧 2 个）字节保真保留、未顺手「修」。

## 补记（收口实测，提交后补录）

- C2（初版）= `cc528af40f50ef341950b7e827bd460889267221`（3 files changed, 488 insertions(+)；提交前 `git diff --cached --name-status` 原文：`M	work/mailbox/PLAN.md` / `A	work/mailbox/tasks/TASK-159/handoff.md` / `A	work/mailbox/tasks/TASK-159/spec.md`；`git show --check` rc=0）。本补记连同「只改清单」节的全路径化订正随 C2 以 `git commit --amend --no-edit` 落库（TASK-157/TASK-158 先例同构）；amend 只增改 handoff/PLAN 台账内容与措辞，不触任何代码与规格文件；amend 后最终 SHA 无法自引，以 `git log -1` 事后核对（见执行方最终回传）。
- **G5 第三次（收口，C2 后）**：`bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0 / BUILD SUCCESS / Total 02:49，逐模块 **36/41/33/103/110/59/10**，与第一次（开工基线 01:53）、第二次（C1 后 01:52）逐位一致（`diff` 实证），Failures/Errors/Skipped 全 0 ⇒ 三跑零扰动成立。
- **收口全量自检**（`bash .trae/tmp/task159-verify.sh da6e3ee…`，script_rc=0，两件套已 tracked 态）：G1 cmp rc=0 ×2（22/22 与 15/15、occurrences 1/1）、G2 list=41/41 arch=43/43 且 comm -23 恰 2 行、G3 @@ 集合与本文 G3 节一致（G3_hunks_with_deletion=0）、G4 spec CR=2806=LF bareLF=0 末字节 `0d 0a` 且 PLAN CR=0/LF=1132、G4b CR 偏移 2108/3966 不变、G7 `git diff --check` rc=0。
- **G6 收口态**（两件套入库后，CI 同款扫描范围首次覆盖 TASK-159 两件套）：四形态（CI 原样 / `C` / `zh_CN.UTF-8` / `C.UTF-8`）全部 **ZERO_HIT rc=1**，正向对照 rc=0 命中 9/9、探针已删——无 TASK-158 型内生冲突。
- **G8 收口**：无参数 `bash scripts/verify/mailbox-contract.sh` → **rc=0**（`契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`；TASK-159 足迹不在工作树，视为已收口）。`--open TASK-159 --baseline=da6e3ee…` → **rc=1**（判据 A=0）：TASK-159 自身过冲恰 4 条 = `?? spec/changes/add-verify-degrade-status-index/` 的既有未跟踪 4 文件（清单多报 0 条，满足 spec G8「过冲仅来自既有脏项」的条件说明）；整体 rc=1 的其余来源为历史任务（TASK-001~158）回传对主规格/PLAN 的公共文件交叠——TASK-127 记载的同名既知模式，不属本任务（`.trae/tmp/g8-final-baseline.log`）。
- **收口态其他**：`git rev-list --left-right --count origin/main...main` = `0	4`（4 笔未推送：`310b2f1`、`da6e3ee`、C1、C2）；`git diff --numstat da6e3ee..HEAD -- work/mailbox/PLAN.md` = 56 insertions / 0 deletions（纯追加实证：验收记录节 49 行 + 补记 7 行含分隔空行）；`git show` 出库的 TASK-159 任务书与工作树文件 `cmp` **逐字节一致**（原样入库实证）；收口态 `git status --porcelain` 仅剩 `?? spec/changes/add-verify-degrade-status-index/`（既有脏项原样未动）；`.trae/tmp/` 下脚本与证据日志均被 `.gitignore` 忽略。
- **G9 维持**：未 push、未建 PR、未跑 `--mode=online`，**未达外部门槛**；PLAN 验收记录外部门槛栏已如实标注。
