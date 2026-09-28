# TASK-159：把 `fix-verify-outbox-poison-head-of-line` 的纯 ADDED delta 并入主规格并归档（纯文档，零代码）

## 为何派执行 agent

本任务是**有界机械活儿**：逐字搬两个需求块、补一条清单条目、追加一条变更历史、把目录移进 archive、写台账。判断密集的部分（依赖顺序、插入锚点、行尾配方、门槛设计、陷阱预登记）指导侧已全部亲跑定死，写在下面。与 TASK-157（指导侧自执行）相反，与 TASK-158（同型任务，已成功收口）相同。

## 触发事实（指导侧 2026-09-28 亲跑一手；执行侧须自行复核后方可采信）

1. TASK-158 已把 outbox 能力**首次**并入主规格：`### Requirement: 判定事件可靠投递` 现位于主规格 **L946**（块 L946–L985，L986 空行，L987 为 `### Requirement: 规则阈值可配置`）；主规格现含 `relay` **12** 处、`verify_event_outbox` **4** 处、`retry_count` **3** 处。⇒ **本任务对 TASK-158 的强顺序依赖已解除**：TASK-158 之前主规格对 `outbox|relay|markSent` 是 ZERO-HIT，先并本变更会产出「有 relay 批次资格要求、却无 relay 定义」的孤儿需求。
2. `spec/changes/fix-verify-outbox-poison-head-of-line/` 是 **19 个未归档目录中冲突风险最低的一个**：`tasks.json` = **5 tasks / 10 steps 全 `completed: true`、`passes` 全 `true`**；`spec-delta.md` **纯 ADDED**（40 行、2 条需求、0 条 MODIFIED/REMOVED）；两条需求标题在主规格 **0 碰撞**（实测 `grep -c` 均为 0）。
3. **代码早已实现该变更**（Level A 亲验，delta ↔ 代码一致，故并入不会装进假需求）：
   - `verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java` **L23–L25**：`@Select("SELECT * FROM verify_event_outbox WHERE status = 'PENDING' AND retry_count < #{maxRetry} ORDER BY id LIMIT #{limit}")` + `selectPendingBatch(@Param("limit") int limit, @Param("maxRetry") int maxRetry)`；**L19–L21** javadoc 明写「耗尽行本身保留原状态与原数据供人工处理（不删除、不重置计数、不改 eventId、不重投）」。
   - `verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java` **L55** `private int maxRetry;`、**L118** `batch = outboxMapper.selectPendingBatch(batchSize, maxRetry);`（**relay 把自身上限传入 Mapper**，与 proposal 要求一致）、**L125** 兜底判断 `row.getRetryCount() >= maxRetry`、**L157** `outboxMapper.markSent(row.getId())`。
4. 主规格现为 **2765** 行、worktree **CRLF**（实测 CR=2765 / LF=2765 / bareLF=0）、**末字节 `0d 0a`**（即以换行符结尾）⇒ **本轮追加变更历史不会产生 TASK-158 那种末行伪影，G3 可要求「主规格零删除」**（比 TASK-158 更强的判据）。
5. 头部提案清单现为 **40** 条（L8–L47 连续 bullet）、L48 空行、**L49** 断言句「各提案的 spec-delta 中 ADDED 需求已全部合并进本规范…」、**L50** 例外说明行；`spec/changes/archive/` 现 **42** 目录，`archive − 清单` 恰为 2 项已登记合法例外（`add-microservice-skeleton`、`add-sharding-host-parameterization`）。并入后应为 **41 / 43**，差集**仍是同样那 2 项**（例外说明行因此**无需改动**）。
6. `work/mailbox/PLAN.md` **纯 LF**（CR=0 / LF=1083）。

## 起点与基线

- 起点 HEAD = `da6e3ee350f60c323561ed3fe1e9d98a6f2e3941`。开工先 `git rev-parse HEAD` 核对，不一致**即停手回报**，不得自行改基线。
- `git rev-list --left-right --count origin/main...main` 开工应为 `0	2`（`origin/main` = `83c98a4695c404c17433a89e1410cab41b2ffa42`，即 TASK-158 的 C4；未推送的两笔均为指导侧自执行的 PLAN L4 订正：`310b2f1`（外部门槛口径 + CI run 36438897772 证据）与 `da6e3ee`（push 口径改为时点读数））。**本任务不得 push**（授权按批次给，不延伸到新提交）。
- 外部参照：CI run `36438897772`（HEAD `83c98a4…`）conclusion=`success`，`web` ✓23s / `build` ✓2m41s，build 档 11 步全 success（第 10 步即词面门）。**本轮不 push ⇒ 本轮产物不经过该外部评判**。
- 开工脏项快照（**只允许**其中「3 个删除侧文件 + 1 个 archive 未跟踪目录」被本任务收编，其余原样不动）：`?? spec/changes/add-verify-degrade-status-index/`（唯一既有脏项，**不许碰**）。
- offline 基线（指导侧本会话**四连测**逐位一致）：`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS**，七模块 **36 / 41 / 33 / 103 / 110 / 59 / 10**、Skipped 全 0。

## 只改清单（严格 6 项，全为文档；零 `.java`/`.sql`/`.yml`/`.properties`/pom/`scripts/**` 改动）

1. `spec/specs/sport-record-verify/spec.md` —— **恰 3 个编辑点**（见「并入细则」）。
2. `spec/changes/archive/fix-verify-outbox-poison-head-of-line/tasks.json` —— 追加 1 个「归档阶段」task（2 steps，全 `completed: true`、`passes: true`），照 TASK-127 / TASK-158 先例。
3. git 索引：把 3 个删除侧路径与 archive 侧 3 个文件**逐路径** `git add`（**禁 `-A`/`add .`**），使移名在提交中成形。两个 `.md` 应为 `R100`（逐字移名），`tasks.json` 因第 2 项而低于 100。
4. `work/mailbox/PLAN.md` —— **纯追加** 1 个「## 验收记录：TASK-159 …」节于文件末尾；**不得改动任何既有行**（含 L4）。
5. `work/mailbox/tasks/TASK-159/spec.md` —— 本文件，指导侧已写入，**执行侧不得改写一个字**（如发现事实错误，停手回报，不得自行修正）。
6. `work/mailbox/tasks/TASK-159/handoff.md` —— 执行侧新建（内容要求见「交回物」）。

## 并入细则（逐字判据；行号均为起点 HEAD 的实测值，仅作定位，**以结构规则为准**）

### 第 1 步：插入两条 ADDED 需求（→ 提交 C1）

- **插入位置**：主规格 `### Requirement: 判定事件可靠投递` 块之后、`### Requirement: 规则阈值可配置`（起点 L987）之前。结构规则＝「紧跟 relay 可靠投递需求，中间保留一个空行分隔」；起点行号上即**在 L986 空行之后插入**。
- **插入内容（顺序固定）**：Q1 块（22 行）+ 1 空行 + Q2 块（15 行）+ 1 空行 = **39 行**。
  - Q1 = `### Requirement: 重试耗尽事件不得阻塞后续可投递事件` = delta **L3–L24**（含 3 个 `#### Scenario:`，起点 delta L7 / L14 / L20）。
  - Q2 = `### Requirement: 饥饿修复须验证真实取批条件` = delta **L26–L40**（含 2 个 `#### Scenario:`，起点 delta L30 / L36）。
  - delta 的 **L1 `## ADDED Requirements` 与 L2/L25 空行不搬**（主规格不用 delta 的分组标题）。
- **行尾必须转 CRLF，且必须用幂等配方**（见「关键陷阱」第 1 条）。搬完主规格应 **2765 → 2765+39 = 2804** 行（清单与变更历史各 +1 后为 **2806**）。

### 第 2 步：头部提案清单 +1（随 C1 一并提交）

- 在起点 **L47**（清单最后一条 `- adopt-native-mq-retry（…）`）之后、L48 空行之前，插入一条：
  `- fix-verify-outbox-poison-head-of-line（<中文短说明>）`
- **必须用全角括号 `（）`**（G2 提取器靠 `^- ` 前缀 + 全角括号截断；半角括号会让 G2 计数错）。短说明自拟，须准确表达「重试耗尽行不得堵塞后续可投递事件」。
- 插入后清单 **41** 条，断言句由 L49 → **L50**、例外说明行由 L50 → **L51**。**这两行原文一字都不许改**（例外说明行仍为真：43 − 41 = 同样那 2 项）。

### 第 3 步：变更历史 +1 条（随 C1 一并提交）

在主规格**文件末尾**追加 1 条，格式照既有条目（`- **<目录名>**：<正文>引用变更 spec/changes/archive/<目录名>/。`）。正文**必须**覆盖以下要素，且**必须原样继承 proposal.md 自带的三条反过度声称口径**（去 `spec/changes/archive/fix-verify-outbox-poison-head-of-line/proposal.md` 取原文，不要凭记忆写）：

1. 缺陷：取批按 `status='PENDING' ORDER BY id LIMIT limit`，而 relay 对 `retry_count >= maxRetry` 的行只告警并 `continue` ⇒ 队首若被耗尽行占满，后续可投递行永久不可见。
2. 修复：取批资格条件增加 `retry_count < maxRetry`，由 relay 传入其当前上限；耗尽行保留原状态与原数据（**不删除、不重置计数、不改 eventId、不重投**）。
3. **反过度声称（缺一不可）**：① 尚无真实运行时饥饿事故证据，结论只到「代码路径与 SQL 可确认此条件推导」；② **不得**拿 TASK-138 的 outbox 计数冒充本缺陷的事故证据；③ **不得**声称这一步改善了查询耗时（既有 `(status,id)` 索引仍服务顺序扫描，但可能需扫过大量耗尽行）。

### 第 4 步：归档移名（随 C1 一并提交）

`spec/changes/fix-verify-outbox-poison-head-of-line/` → `spec/changes/archive/fix-verify-outbox-poison-head-of-line/`（3 个文件）。两个 `.md` **必须逐字移名**（`git status` 应显示 `R100`）；**不得顺手"修"那个游离 CR**——移名是字节保真操作，改字节会让 `R100` 掉下来并把无关改动混进 C1。

### 第 5 步：台账（→ 提交 C2）

`tasks.json` 追加归档阶段 task（第 2 项）+ `PLAN.md` 纯追加验收记录（第 4 项）+ 本文件与 `handoff.md` 入库（第 5、6 项）。**C2 只碰 `work/mailbox/**` 与 archive 侧 `tasks.json`**，不得再触主规格。

### 硬性顺序与冲突即停

C1（主规格 3 处 + 移名 3 文件 + archive `tasks.json`）→ C2（PLAN + 两件套）。**任一 G 门在中间态报红且原因不明即停手回报**，不得为了让门槛变绿而改写需求原文、不得删改既有断言、不得扩围去并其他 18 个目录。

## 欠账登记（必须原样进 PLAN 验收记录的「未覆盖/后续」栏；须自行复跑脚本核对，不得抄指导侧数字）

本变更归档后 `spec/changes/` 应剩 **18** 个未归档目录（19 − 1）。指导侧已知的后续难点，供你在 handoff 里**自行复核后**登记（**不得直接抄**）：

- **3 深 MODIFIED 链**：需求「relay 可选诊断不得改变可靠投递语义」由 `measure-verify-outbox-relay-cost` ADDED，再被 `measure-verify-outbox-mark-sent-cost`、`prove-verify-outbox-mark-sent-attribution`  successive MODIFIED ⇒ 必须按序并入、终态取最后一个。
- **对既有基线需求的 MODIFIED**：`shorten-submit-db-footprint` MODIFIED「轨迹提交幂等」（属提交路径而非 outbox），须单独一轮。
- **未全绿目录（指导侧实测读数，你须自行复核后登记）**：`allPass=False` 的有 `add-verify-degrade-status-index`（steps 0/6）、`measure-verify-outbox-mark-sent-server-event`（5/10）、`resume-verify-outbox-mark-sent-server-event`（4/9）；另 `update-verify-outbox-relay-delay` 为 7/8。**这些一律不得并入**。注意 `steps=N/M` 与 `allPass` 是**两个独立维度**：实测存在 `steps=14/15` 却 `allPass=True` 的目录（`measure-head-bottleneck-attribution`），故登记时**两者都要报**，不得只报其一、也不得把 `allPass=True` 读成「步骤全做完」。
- **TASK-158 已登记的 21 个游离 CR 文件**：本任务的 poison 三件套里 `spec-delta.md` 与 `proposal.md` 各含 1 个（见陷阱 1），其余分布在别的目录，将来每轮都要单独处理。

## 明确不做（红线）

- **不 push、不建 PR、不 `git stash`、不 `git add -A`/`add .`**（逐路径 add）。
- **不改任何代码/SQL/配置/pom/脚本**；本任务是纯文档。特别地：`VerifyEventOutboxMapper.java`、`VerifyOutboxRelay.java`、`verify-service/src/test/**` **一律只读**（第 3 条触发事实只是让你核对一致性，不是让你改）。
- **不碰** `spec/changes/` 下其余 **18** 个在途目录；**不碰** `?? spec/changes/add-verify-degrade-status-index/`。
- **不改** 主规格 L49 断言句与 L50 例外说明行的原文（它们只允许因上方插入而位移）。
- **不改** `work/mailbox/PLAN.md` 任何既有行（含 L4）；**不改** 本文件。
- **不碰** `work/mailbox/tasks/TASK-156/**`、`TASK-157/**`、`TASK-158/**`、`docs/perf/**`。
- **不裸用 `mvn`**、不用 `MAVEN_OPTS`、**不创建 `.mvn/maven.config`**（TASK-154/156 已两次踩坑）。
- **不 Flyway / 不 Testcontainers / 不加新插件 / 不连演示库**；本任务无需任何数据库。
- 未达到的环境（`--mode=online`、CI）**一律如实记「未覆盖」**，绝不造假绿。

## 关键陷阱（指导侧已逐个实测，照做即可，不要自行发明写法）

1. **游离 CR + 行尾转换（本轮最大陷阱）**：`spec-delta.md` 是 **40 行 / CR=1 / LF=40**，那 1 个 CR 在**字节偏移 2108**，即**文件最后两字节是 `0d 0a`**——末行 L40 以 CRLF 结尾，而 L1–L39 以**裸 LF** 结尾。`proposal.md` 同型（CR 在偏移 3966 = 末行 L25）。主规格是**全 CRLF**。
   - **必须用幂等配方**：`sed 's/\r*$/\r/'`（先剥掉行尾可能存在的 CR，再补恰好一个）。指导侧实测：Q1 → 22 行 / CR=22 / LF=22，Q2 → 15 行 / CR=15 / LF=15，**双 CR（`\r\r`）出现 0 次**，且 EOL 归一后与 delta 原文**逐字相同**（Python 比对 True / True）。
   - **不要用 `sed 's/$/\r/'`**：它在本机碰巧也给对的结果（MSYS sed 读入时会剥掉行尾 CR），但**依赖平台行为**，换工具就会在 L40 产出 `\r\r\n`。
   - **绝对不要用 `cat` / `head` / `tail` 直接拼接搬块**：那是字节保真的，会把 39 行里的 38 个裸 LF 原样带进 CRLF 主规格 ⇒ G4 的 `CR == LF` 立刻红。
2. **行尾检测工具全都不可信，只有字节扫描可信**（指导侧用合成对照文件 `line1\nline2\nline3\r\n`（真值：1 个 CR、在第 3 行）实测）：
   - `awk '{if ($0 ~ /\r$/) …}'` → **0 命中＝假阴性**（GNU Awk 4.2.1 会剥掉记录尾的 CR）。
   - `grep -c $'\r$'` → **3＝假阳性**（GNU grep 3.1 匹配了全部行）。
   - **只有** `od -An -tx1 -v` 字节偏移扫描 / Python 二进制读 / `tr -cd '\r' | wc -c` 计数给出真值。⇒ 计数用 `tr`，**定位**用字节扫描。
3. **词面门：本文件刻意不内嵌那条正则的字面量**（TASK-158 的 C4 就是因为任务书内嵌了它、入库后自命中，把 CI 门槛打破）。**必须现场从 `ci.yml` 提取**：
   `RE=$(sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml | head -1)`，并**断言非空**（空则 `exit 9`，绝不静默通过）。指导侧实测提取长度 **26**，且用 9 种被禁形态（含「附录」紧接 A 与中间夹空格两种）做**正向对照**，`grep -n -iE "$RE"` **9/9 全命中 rc=0** ⇒ 提取式非空跑。
   - **连"描述禁词"都不能用禁词字面量**：TASK-158 的 C4 首版就是因为在说明文字里写了那两个字的字面形态，自造 2 处新命中。你写 handoff / PLAN 时同样受此约束。
4. **词面门必须三态判定**：`rc=0` 有命中 / `rc=1` 无命中 / **其他 rc ＝ 工具错误，判为门槛失败而不是通过**。`if git grep …; then HITS else ZERO_HIT` 这种二分支写法会把 fatal 当成干净。
5. **`git grep --untracked` 必须放在 pattern 之前**：写成 `git grep -n -I -iE "$RE" --untracked -- <pathspec>` 时 git 2.20.1.windows.1 把 `--untracked` 当 revision，报 `fatal: unable to resolve revision: --untracked`、返回 **rc=128**（指导侧已两次实测复现）。
6. **`git grep` 默认不搜未跟踪文件**：正向对照若用未跟踪探针文件，必须加 `--untracked`（且位置正确）或改用 `grep` 直扫，否则会得到**假的 ZERO_HIT**（指导侧本轮亲自踩过）。
7. **PowerShell 命令行全程不得出现中文**（会 exit 127 或被静默剥离）。中文正则/提交信息写入 `.trae/tmp/` 下 UTF-8 无 BOM 文件再引用；提交信息一律 `git commit -F <文件>`。需要 bash 时**一律写成 `.sh` 脚本文件**再用 `& 'D:\git\Git\bin\bash.exe' <脚本路径>` 跑；**禁止** `bash -lc "..."` 内联（PowerShell 会破坏 `$?`、`<`、`$'...'`）。注意 `set -e` 与 `rc=$?` 冲突：需要抓非零 rc 的段落**不要**放在 `set -e` 下。
8. **Q1/Q2 标题在主规格必须各只出现 1 次**：起点实测均为 0；若你的编辑让任一条变成 2，说明你贴重复了（G1 的 `occurrences` 会抓到）。

## 收口门槛（每条都要回传实测退出码/数字，不接受散文）

- **G0 代码↔delta 一致性（只读，硬）**：复核触发事实第 3 条列出的 5 处（Mapper L19–L25、Relay L55/L118/L125/L157）在当前 HEAD 仍然成立。**若代码已漂移致 delta 描述失真，立即停手回报，不得并入**（并入一条与代码不符的需求比不并更糟）。
- **G1 逐字判据（硬）**：主规格中 Q1 整块与 delta **L3–L24** 逐字一致（EOL 归一后比对）、Q2 整块与 delta **L26–L40** 逐字一致；两条标题 `occurrences` 各 = **1**。回传 `cmp`/比对 rc 与四个行数（应为 **22 / 22 / 15 / 15**）。
- **G2 集合不变式（硬）**：头部清单 **40 → 41**；`spec/changes/archive` **42 → 43**；`comm -23 <(ls -1 archive|sort) <(清单条目|sort)` 恰为 **2 行** = `add-microservice-skeleton`、`add-sharding-host-parameterization`；`comm -13` 输出为**空**。
- **G3 主规格零删除（硬，本轮最强判据）**：`git diff -U0 <起点SHA> HEAD -- spec/specs/sport-record-verify/spec.md` 的**所有 `@@` 行都必须是纯插入形态**（旧区间长度为 0，形如 `@@ -N,0 +M,K @@`）；**出现任何旧区间长度 > 0 的 `@@` 即判红**。回传全部 `@@` 行原文。并回传主规格行数 **2765 → 2806**（+39 需求块 +1 清单 +1 变更历史）。
- **G4 行尾完整性（硬）**：`cr=$(tr -cd '\r' < f | wc -c); lf=$(tr -cd '\n' < f | wc -c)`。主规格须 **CR == LF == 2806**（bareLF=**0**）、末 2 字节 **`0d 0a`**；`PLAN.md` 须 **CR == 0**。两文件均**无 BOM**。另须用**字节扫描**（非 awk/grep）回传 delta 与 proposal 的 CR 偏移，确认你**没有**在移名中改动它们（仍各为 1，且仍在末行）。
- **G5 offline 双跑零扰动（硬）**：开工基线跑一次、收口跑一次 `bash scripts/verify/mvn-verify.sh --mode=offline test`，两次 rc=**0**、**逐模块用例数逐位一致**、Failures/Errors/Skipped 全 0；回传两次的模块数字串（指导侧基线为 **36 / 41 / 33 / 103 / 110 / 59 / 10**）。
- **G6 词面自检（三态 + 正向对照，硬）**：用**从 `ci.yml` 提取的**正则（不得内嵌字面量），跑 4 种形态：CI 原样（**无 `LC_ALL`**）、`LC_ALL=C`、`zh_CN.UTF-8`、`C.UTF-8`，**全部须 rc=1（ZERO_HIT）**；任一 rc≠0/1 判**门槛失败**。**并必须做正向对照**：临时造一个含被禁形态的探针文件，证明同一 harness 能抓到它（回传命中数），**然后删除探针并回传 `git status` 证明已清干净**。
- **G7 空白与提交**：`git diff --check` rc=**0**；两笔提交各自 `git show --check` 干净（回传两个 rc）。
- **G8 契约**：在途 `bash scripts/verify/mailbox-contract.sh --open TASK-159 --baseline=<起点SHA>` 记录 rc（若 rc=1，须逐条说明过冲**仅**来自 `?? spec/changes/add-verify-degrade-status-index/` 这类既有脏项）；**两笔提交后无参数复跑必须 rc=0**。
- **G9 外部门槛**：不 push ⇒ PLAN 验收记录「是否到达外部门槛」栏必须写「**未达外部门槛**（本次不 push，待下次授权由 CI 复验）」。
- **提交结构**：C1 = 并入 + 归档移名（主规格 3 处 + archive `tasks.json` + 移名 3 文件）；C2 = 台账（`PLAN.md` + `TASK-159/spec.md` + `TASK-159/handoff.md`）。每笔 `git diff --cached --name-only` 只含预期路径，回传两份清单。

## 可直接使用的自检脚本（写到 `.trae/tmp/task159-verify.sh`，UTF-8 无 BOM，用 bash 跑；不要贴到 PowerShell 命令行）

```bash
#!/usr/bin/env bash
# 用法： bash .trae/tmp/task159-verify.sh <起点SHA>
# 注意：本脚本刻意不在自身内嵌词面门正则的字面量，一律从 ci.yml 现场提取。
cd /d/code/sports || exit 9
BASE="${1:?need base sha}"
SPEC=spec/specs/sport-record-verify/spec.md
mkdir -p .trae/tmp
# 变更目录动态定位：收口态在 archive/ 下、开工态仍在 spec/changes/ 下——两态都要能跑
CHG=spec/changes/archive/fix-verify-outbox-poison-head-of-line
[ -d "$CHG" ] || CHG=spec/changes/fix-verify-outbox-poison-head-of-line
[ -d "$CHG" ] || { echo "FATAL: change dir not found in either location"; exit 9; }
D="$CHG/specs/sport-record-verify/spec-delta.md"
echo "resolved change dir = $CHG"

# 需求块提取器（指导侧已实测：Q1=22 行=delta L3-L24，Q2=15 行=delta L26-L40）
blk() { awk -v t="$2" '
  index($0,t)==1 { f=1; print; next }
  f && ($0 ~ /^## / || $0 ~ /^### / || $0 ~ /^---[ \t]*$/) { exit }
  f { print }
' "$1" | awk 'NF{n=NR} {l[NR]=$0} END{for(i=1;i<=n;i++) print l[i]}'; }
# EOL 归一后比对（主规格 CRLF、delta 混合，直接 cmp 必假红）
norm() { tr -d '\r' < "$1"; }

Q1='### Requirement: 重试耗尽事件不得阻塞后续可投递事件'
Q2='### Requirement: 饥饿修复须验证真实取批条件'

echo '== G0 代码 delta 一致性（只读）=='
grep -n 'retry_count < #{maxRetry}' verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java
grep -n 'selectPendingBatch(batchSize, maxRetry)' verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java
echo "G0_mapper_hits=$(grep -c 'retry_count < #{maxRetry}' verify-service/src/main/java/com/sportverify/verify/mapper/VerifyEventOutboxMapper.java) expect=1"
echo "G0_relay_hits=$(grep -c 'selectPendingBatch(batchSize, maxRetry)' verify-service/src/main/java/com/sportverify/verify/mq/VerifyOutboxRelay.java) expect=1"

echo '== G1 逐字判据 =='
blk "$D" "$Q1" > .trae/tmp/a1.txt; blk "$SPEC" "$Q1" > .trae/tmp/b1.txt
blk "$D" "$Q2" > .trae/tmp/a2.txt; blk "$SPEC" "$Q2" > .trae/tmp/b2.txt
echo "G1 Q1 delta=$(wc -l < .trae/tmp/a1.txt)/22 spec=$(wc -l < .trae/tmp/b1.txt)/22 occ=$(grep -c "$Q1" "$SPEC")/1"
echo "G1 Q2 delta=$(wc -l < .trae/tmp/a2.txt)/15 spec=$(wc -l < .trae/tmp/b2.txt)/15 occ=$(grep -c "$Q2" "$SPEC")/1"
norm .trae/tmp/a1.txt > .trae/tmp/a1n.txt; norm .trae/tmp/b1.txt > .trae/tmp/b1n.txt
norm .trae/tmp/a2.txt > .trae/tmp/a2n.txt; norm .trae/tmp/b2.txt > .trae/tmp/b2n.txt
cmp .trae/tmp/a1n.txt .trae/tmp/b1n.txt; echo "G1_Q1_cmp_rc=$? expect=0"
cmp .trae/tmp/a2n.txt .trae/tmp/b2n.txt; echo "G1_Q2_cmp_rc=$? expect=0"
# 与 sed 行号区间交叉核对（提取器自身可信度）
sed -n '3,24p'  "$D" | tr -d '\r' > .trae/tmp/s1.txt; cmp .trae/tmp/a1n.txt .trae/tmp/s1.txt; echo "G1_Q1_blk_vs_sed3_24_rc=$? expect=0"
sed -n '26,40p' "$D" | tr -d '\r' > .trae/tmp/s2.txt; cmp .trae/tmp/a2n.txt .trae/tmp/s2.txt; echo "G1_Q2_blk_vs_sed26_40_rc=$? expect=0"

echo '== G2 集合不变式 =='
awk 'NR<8{next} /^- /{print;next} {exit}' "$SPEC" | sed -e 's/^- //' -e 's/（.*//' | sort > .trae/tmp/l.txt
ls -1 spec/changes/archive | sort > .trae/tmp/ar.txt
echo "G2 list=$(wc -l < .trae/tmp/l.txt)/41 arch=$(wc -l < .trae/tmp/ar.txt)/43"
echo 'G2 arch-minus-list (expect exactly 2):'; comm -23 .trae/tmp/ar.txt .trae/tmp/l.txt
echo 'G2 list-minus-arch (expect empty):';   comm -13 .trae/tmp/ar.txt .trae/tmp/l.txt

echo '== G3 主规格零删除（所有 @@ 旧区间长度必须为 0）=='
git diff -U0 "$BASE" HEAD -- "$SPEC" | grep '^@@' | tee .trae/tmp/g3.txt
echo "G3_hunk_count=$(wc -l < .trae/tmp/g3.txt) expect=3"
echo "G3_hunks_with_deletion=$(sed -n 's/^@@ -[0-9]*,\?\([0-9]*\).*/\1/p' .trae/tmp/g3.txt | awk '$1+0>0' | wc -l) expect=0"
echo "G3_spec_lines=$(wc -l < "$SPEC") expect=2806"

echo '== G4 行尾（tr 计数，不用 grep/awk 的行尾断言）=='
for f in "$SPEC" work/mailbox/PLAN.md; do
  cr=$(tr -cd '\r' < "$f" | wc -c); lf=$(tr -cd '\n' < "$f" | wc -c)
  echo "G4 $f CR=$cr LF=$lf bareLF=$((lf-cr)) lastbytes=$(tail -c 2 "$f" | od -An -tx1 | tr -s ' ')"
done
echo 'G4 期望：spec CR==LF==2806 bareLF=0 lastbytes=0d 0a ；PLAN CR=0'
echo '== G4b 游离 CR 字节偏移（只用 Python 二进制读，awk/grep 都不可信）=='
python - "$D" "$CHG/proposal.md" <<'PY'
import sys
for p in sys.argv[1:]:
    b=open(p,'rb').read()
    offs=[i for i,c in enumerate(b) if c==13]
    print("  %s size=%d CR=%s LF=%d lastbyte=%02x" % (p,len(b),offs,b.count(b'\n'),b[-1]))
PY

echo '== G6 词面门：正则现场提取 + 三态判定 + 正向对照 =='
RE=$(sed -n 's/.*git grep -n -I -iE "\([^"]*\)" --.*/\1/p' .github/workflows/ci.yml | head -1)
if [ -z "$RE" ]; then echo "G6_FATAL: regex extraction from ci.yml returned empty"; exit 9; fi
echo "G6 extracted regex length=${#RE} (指导侧实测 26)"
report() { # $1=tag $2=rc $3=file
  case $2 in
    0) echo "  G6[$1] HITS rc=0 lines=$(wc -l < "$3")  <== 判红"; sed 's/^/      /' "$3" ;;
    1) echo "  G6[$1] ZERO_HIT rc=1  <== 期望" ;;
    *) echo "  G6[$1] TOOL_ERROR rc=$2  <== 判失败，不是通过"; head -2 "$3" | sed 's/^/      /' ;;
  esac
}
# CI 原样形态：LC_ALL 与 LANG 必须**真 unset**。注意 `env LC_ALL=` 只是把它置成空串，
# 与 unset 不等价（指导侧实测：前者 ${LC_ALL-UNSET} 打印空、后者打印 UNSET）。
env -u LC_ALL -u LANG git grep -n -I -iE "$RE" -- \
  ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml' > .trae/tmp/g6-ci-exact.txt 2>&1
report ci-exact $? .trae/tmp/g6-ci-exact.txt
for loc in C zh_CN.UTF-8 C.UTF-8; do
  LC_ALL=$loc git grep -n -I -iE "$RE" -- \
    ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml' > ".trae/tmp/g6-$loc.txt" 2>&1
  report "$loc" $? ".trae/tmp/g6-$loc.txt"
done
echo '  -- 正向对照（证明 harness 非空跑）--'
python - <<'PY'
ws=["\u9762\u8bd5","\u5f39\u836f","\u5927\u5382","\u516b\u80a1","\u7b80\u5386","\u6c42\u804c","\u7a81\u51fb","\u9644\u5f55A","\u9644\u5f55 A"]
open("g6-probe-DELETEME.txt","w",encoding="utf-8").write(
  "line1 clean\n"+"".join("line%d has %s\n"%(i+2,w) for i,w in enumerate(ws))+"line%d clean tail\n"%(len(ws)+2))
print("  probe written with %d banned forms" % len(ws))
PY
git grep --untracked -n -I -iE "$RE" -- g6-probe-DELETEME.txt > .trae/tmp/g6-probe.txt 2>&1; prc=$?
echo "  G6_positive_control rc=$prc hits=$(wc -l < .trae/tmp/g6-probe.txt) expect rc=0 hits=9"
rm -f g6-probe-DELETEME.txt
echo "  probe removed; git status --porcelain follows:"; git status --porcelain

echo '== G7 =='
git diff --check; echo "G7_diffcheck_rc=$? expect=0"
echo '== G8 =='
bash scripts/verify/mailbox-contract.sh > .trae/tmp/g8.log 2>&1; echo "G8_contract_noarg_rc=$? expect=0"; tail -2 .trae/tmp/g8.log
echo '== 欠账复核（剩余 18 个目录，自行核出，不得抄）=='
for d in spec/changes/*/; do n=$(basename "$d"); [ "$n" = archive ] && continue
  python - "$d/tasks.json" "$n" <<'PY'
import json,sys
j=json.load(open(sys.argv[1],encoding='utf-8'))
t=sum(len(x['steps']) for x in j); c=sum(1 for x in j for s in x['steps'] if s['completed'])
print("  %s steps=%d/%d allPass=%s" % (sys.argv[2],c,t,all(x.get('passes') is True for x in j)))
PY
done
echo "  pending_dirs=$(ls -1d spec/changes/*/ | grep -v '/archive/$' | wc -l) expect=18"
```

注 1：`blk()` 与四个行数（22 / 22 / 15 / 15）、`sed` 交叉核对、正则提取长度 26、正向对照 9/9 —— 指导侧已在起点 HEAD **全部亲跑通过**，你照跑即可。若 `cmp` 报红，先 `diff` 看差异再判定，**不得为了让 cmp 变绿而改写需求原文**；确属提取器缺陷时改用 `sed -n 'X,Yp'` 行号区间直接比对，并在 handoff 写明所用区间。

注 2：G1 用 **EOL 归一后**比对（`tr -d '\r'`），因为主规格是 CRLF 而 delta 是混合行尾；**G4 才是管行尾的那道门**，两者分工不要混。

注 3：末段「欠账复核」用 python（本机 Python 3.13.12 已确认可用；node v22.19.0 为后备）。**目的是让你自己核出那 18 个目录的状态，不得直接抄指导侧的表。**

## 交回物（`handoff.md` 必含，缺一项即视为未收口）

1. 结论一句话（不变式是否维持、清单/archive 计数、G3 是否零删除）。
2. 起点 SHA 与**两笔**提交 SHA；每笔的 `git diff --cached --name-only` 原文（含 `R100` 移名状态）。
3. 只改清单逐项对齐（6 项，含「未改动」的显式声明）。
4. **G0–G9 全部实测输出/退出码原文**（含 G3 全部 `@@` 行与 `G3_hunks_with_deletion`、G5 两次模块数字串、G2 的 `comm` 输出、G4b 的 CR 偏移、**G6 四种形态的 rc + 正向对照的 rc/命中数 + 探针已删的 `git status`**）。
5. 欠账登记表的**自行复核结果**（18 个目录的 steps/allPass 是否与指导侧数字一致；不一致须列出差异并说明）。
6. 未覆盖项与不得推出的结论（至少：未 push 未过 CI；未合并其余 18 个 delta；本轮**不构成**对饥饿缺陷的事故证据、**不构成**查询耗时改善的声称；不翻案 TASK-153/154、不改写 TASK-152/156 的任何数字）。

## 指导侧复验收方式

收口授权下放（指导侧不复跑全流程）。指导侧将以 **G3 的「零删除」判据**（本轮最强，任何旧区间长度 > 0 即判红）+ G1 的 EOL 归一 `cmp` + G2 的 `comm` + 两笔提交的逐字 diff 事后核验；G6 的**正向对照**是主要抓手（没有正向对照的 ZERO_HIT 一律不采信）。若发现主规格有任何删除、或清单/例外说明与 archive 目录集合不一致、或两个 `.md` 的移名不是 `R100`，判定为**契约红**并要求回滚重做。
