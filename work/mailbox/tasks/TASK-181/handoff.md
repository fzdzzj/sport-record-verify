# TASK-181 Handoff

## 0. 编号、基线与外部状态

- **任务编号**：`TASK-181`（harden-surefire-agent-attach，surefire agent attach 加固）。
- **派发基线**：`31733b636d80667101b35f854a2bc0fb7c8be046`（指导侧亲笔，零触碰）。
- **状态**：**已收口**。裁决＝**PASSED（含两处经授权的形态订正 + 一处根因订正）**：offline 全量 **450 恒等 rc=0**、`--static=record-service` **811 不增**、`-X` 取证测试 JVM 命令行双参数同现、词面门四形态 ZERO_HIT + 探针 9/9、token 29 项只增不减。
- **外部状态**：未 push、未建 PR、未达外部门槛；修复有效性以**第 23 次门槛绿**为外部终验。本机 `.m2-repo`（gitignored）经在线落料新增 surefire 相关 20 个 jar，属本机环境动作、不属交付面。

## 1. 开工规程核验与偏差登记

### 1.1 开工规程核验

- **HEAD 状态**：`31733b636d…`，`origin/main...main` = `0 1`，`git status --porcelain` 空。
- **基线读数**：offline 七模块 `36/41/33/127/144/59/10` = **450** rc=0；`--static=record-service` = **811** rc=1（既有基线态）；在途契约门 `--open TASK-181 --baseline=31733b6…` rc=0；`python -m json.tool` tasks.json rc=0；`git diff --check` 与 `--cached --check` rc=0。
- **词面门**：正则现场自 ci.yml（58 字节、8 分支，字面量不入任何入库文件），权威解释器 git 2.20.1，四形态全 ZERO_HIT rc=1（无 TOOL_ERROR）；撤除 ci.yml 排除的对照四形态 HIT rc=0；由正则自身拼装的 9 行探针四形态 HIT rc=0 各 9/9；探针 `*.tmp` 用毕删除，`git status --porcelain` 复核无残留。
- **Token 29 项**：开工 `grep -cF` 逐项登记（见 §6 左列），与 TASK-180 收口值逐位一致。

### 1.2 偏差登记

1. **预注册形态在 offline 口径不可实施（形态订正一）**：任务书 §2.1 的「`pluginManagement` 内不写 `<version>`」实测让 Maven 在计划演算阶段改走仓库 metadata 取版本（`LifecyclePluginResolver.resolveMissingPluginVersions`），而本机离线仓 `maven-surefire-plugin/` 目录下只有各版本 jar、没有插件 metadata ⇒ `--mode=offline test` 与 `--static` 第 1/2 段双双 rc=1、**零用例执行**，§6.2 要求的 offline 450 逐位取证拿不到（同形态在 online 口径 rc=0、450 恒等，读数见 evidence.md §5.1）。据此回报指导侧裁定。
2. **指导侧首次裁定的 3.6.0 经实测确定性致红（形态订正二）**：锁 `3.6.0`（CI 第 22 次门槛的实读版本）后本机 offline 全量在 leaderboard-service 出现 `Tests run: 59, Failures: 0, Errors: 59`，签名与第 22 次门槛同族；去掉 `allowAttachSelf` 只留 `@{argLine}` 的对照复跑同样 59 Errors（两次一致）⇒ **致红变量是 surefire 版本，不是新 flag**。改锁最近一次绿的 `3.5.4`（= run `37722755341` 的实读版本）后四条门径全绿，据此二次回报并获授权。
3. **根因订正（对派发笔 §1 归档口径）**：第 22 次门槛的「runner 机群条件漂移」有确切落点——**本仓从未声明测试插件版本，版本随 runner 镜像里 Maven 的默认绑定漂移**。外部读数：上次绿的 run `37722755341`（03:26Z）为 `surefire:3.5.4:test` ×14、全文零 attach 签名；红的 run `37736029636`（06:08Z）为 `surefire:3.6.0:test` ×3、gateway `Tests run: 41, Failures: 0, Errors: 14`。本机在 3.6.0 下的复现说明该失败**可确定性重现而非间歇**；「外部子进程 attach 兜底不稳定」是失败链的末端表现，不是漂移本身。修法相应落到「显式声明版本」，`allowAttachSelf` 保留（实测无害，且消除对外部子进程兜底的依赖）。派发笔 §9/proposal 的「沿现状继承 3.6.0」对 CI 成立、对本机 offline 不成立（本机 Maven 3.9.4 绑定 3.1.2），本笔不改动派发笔正文，差异登记在此与 evidence.md §5。
4. **在途契约门 rc=1（历史回传的"零触碰声明"被当声明提取，非本笔超范围改动）**：`--open TASK-181 --baseline=31733b6…` 开工态（工作树空）rc=0；本笔 6 条路径进入改动集后 rc=1。逐条核对到脚本层面：`mailbox-contract.sh` 的 `extract_claims`（L114–L126）只认标题含「只改 / 改动 / 文件清单」的小节，并把该小节**正文里所有文件名样 token** 提取为声明，缺该小节时回退为整档扫描；历史 handoff 普遍在同小节写「显式零触碰：… `pom.xml` …」（如 TASK-174 L43、TASK-005 L18–L19），于是 `pom.xml` 成了几十个历史任务的"声明"。本笔真实改动含 `pom.xml` ⇒ 与这些声明交叠 ⇒ 判据 B 对被交叠的每个历史任务都要求两集合完全一致，遂逐个判「清单多报」（多报清单里 `PLAN.md` 与 `pom.xml` 命中数为 0，正说明它们是被匹配上的交叠项）。**本笔自身判据 B 通过**——原文 `[contract] TASK-181：判据 B 通过（只改清单与实际改动集一致）`（`.trae/tmp/t181-23-contract-out.log:1206`），且实际改动集经 `git status --porcelain` 与 `git diff --name-only 31733b6…` 双读核对，严格等于 §3 的 6 条路径。同型过冲在 TASK-160 G8、TASK-174 §1.2.1 已登记在册；权威收口口径是无参复跑（见 §5 G13）。
5. **提交主题按实施形态订正**：任务书 §6.3 预注册主题为「surefire 测试 JVM 启用进程内 agent attach」，只覆盖 argLine 一半；交付形态含版本锁定（真实修法），故 C-01 主题改为 `build(pom): 锁定 surefire 3.5.4 并启用测试 JVM 进程内 agent attach（TASK-181）`，版本漂移与 flag 两件事都在主题里可见。
6. **取证拼写经授权手拼**：`-X` 取证跑（`mvn -B -ntp -o -s .mvn-settings.xml -pl record-service -am clean test -X`）按任务书 §2.2 授权手拼，带齐 `-o -s` 与 `-am`；四组门禁结论一律出自 `scripts/verify/mvn-verify.sh`，全程无并发 mvn。
7. **执行侧自纠一处实物缺陷（追加订正笔 C-03）**：C-01 入库后自查发现 `pom.xml` 里那段版本说明注释仍写「显式锁 3.6.0（第 22 次门槛日志 surefire:3.6.0:test）」，而实锁值已在授权后改为 `3.5.4`——改值时没同步注释，注释与实物不一致。C-03 只改该注释（把"3.6.0 是 CI 现读值""3.5.4 是上次绿的现读值""3.6.0 本机确定性致红"三件事写清），**构建语义零变更**（注释不入模型），并复跑官方 offline 门确认 450 恒等。纪律取舍：不 `git amend` 已入库的 C-01（本仓纪律是新提交优先），改走追加笔 + 台账登记；§6.3 的「两笔」结构因此变为三笔，本笔即 §8 的 C-03。

## 2. 一句话结论

在 root pom 的 `pluginManagement` 内显式声明 `maven-surefire-plugin` 并锁 `3.5.4`（= 上一次全绿外部门槛的实读版本），同时把测试 JVM 参数写成 `@{argLine} -Djdk.attach.allowAttachSelf=true`：前者消除了「测试插件版本随 runner 镜像默认绑定漂移」这一仓库侧潜伏机制（第 22 次门槛的红正是 3.5.4→3.6.0 跳档所致，本机可在 3.6.0 上确定性复现），后者让 Mockito inline mock maker 的 ByteBuddy agent 走进程内 self-attach、不再依赖 JDK 21 默认禁用的外部子进程兜底，且晚绑定保留使 JaCoCo agent 未被覆盖。三支判定 **PASSED**（450 恒等、静态 811 不增、双参数取证在场、门禁全绿），修复有效性留给第 23 次外部门槛终验。

## 3. 只改清单

- pom.xml
- work/mailbox/tasks/TASK-181/evidence.md
- spec/changes/harden-surefire-agent-attach/tasks.json
- work/mailbox/tasks/TASK-181/spec.md
- work/mailbox/tasks/TASK-181/handoff.md
- work/mailbox/PLAN.md

## 4. 实施证据

### 4.1 双参数生效取证（offline，`-X`，record-service）

```
[INFO] --- surefire:3.5.4:test (default-test) @ sport-verify-record-service ---
[DEBUG]   (s) argLine = @{argLine} -Djdk.attach.allowAttachSelf=true
[DEBUG] Forking command line: cmd.exe /X /C "D:\develop1\jdk21\bin\java
  -javaagent:D:\\code\\sports\\.m2-repo\\org\\jacoco\\org.jacoco.agent\\0.8.12\\org.jacoco.agent-0.8.12-runtime.jar=destfile=D:\\code\\sports\\record-service\\target\\jacoco.exec
  -Djdk.attach.allowAttachSelf=true
  -jar C:\Users\fzdzzj\AppData\Local\Temp\surefire18376402435519604076\surefirebooter-20261008174040971_26.jar …"
```

同轮 JaCoCo 侧 `argLine set to -javaagent:…` 在场 ⇒ spec-delta「二者 SHALL NOT 互相覆盖」成立。原文位置 `.trae/tmp/t181-20-candidate-gates.log:6319 / :6324 / :6413`，全文摘录同步在 evidence.md §3。

### 4.2 归因矩阵（同一台机、offline、leaderboard-service）

| surefire | allowAttachSelf | 读数 | rc |
| --- | --- | --- | --- |
| 3.1.2（无声明→本机 Maven 3.9.4 绑定） | 无（开工基线） | 全量 450 绿 | 0 |
| 3.1.2（显式锁） | 有 | 59 绿（fork 行含双参数） | 0 |
| **3.5.4（显式锁，交付形态）** | 有 | 全量 450 绿（含 59） | 0 |
| 3.6.0（显式锁） | 有 | `Tests run: 59, Failures: 0, Errors: 59`，其余模块 36/41/33/127/144/10 绿 | 1 |
| 3.6.0（显式锁） | 无（仅 `@{argLine}`） | 同样 `Tests run: 59, Errors: 59`（复跑两次一致） | 1 |

本机 3.6.0 失败签名：`Could not initialize inline Byte Buddy mock maker.` / `It appears as if your JDK does not supply a working agent attachment mechanism.` / 末端 `com.sun.tools.attach.AgentInitializationException: Agent JAR loaded but agent failed to initialize`。
CI 第 22 次同族签名：`Could not self-attach to current VM using external process`、`Exception java.lang.NullPointerException [in thread "Attach Listener"]`、`NoClassDefFoundError: Could not initialize class org.apache.maven.surefire.api.report.StackWalkerStrategy`。

### 4.3 基线恒等与静态读数（交付形态）

- offline 全量：`36/41/33/127/144/59/10` = **450**，Failures/Errors/Skipped 全 0，BUILD SUCCESS，rc=0（`.trae/tmp/t181-19-candidate-354.log` 第 2 段）。
- online 全量（同形态、同机）：逐位同为 `36/41/33/127/144/59/10` = 450，rc=0（同日志第 1 段，兼作 3.5.4 的离线仓落料跑）。
- `--static=record-service`：`You have 811 Checkstyle violations.`，rc=1 ⇒ 与开工基线 811 同值、未增。

## 5. 逐门实测退出码

| 门禁项 | 判据与命令 | 实测读数 | 结果 |
| --- | --- | --- | --- |
| G0 开工基线 | HEAD / 0 1 / 工作树 / 450 逐位 / 811 / tasks.json / diff-check | 逐项符合（§1.1） | 通过 |
| G1 词面门 | 四形态 + 撤排除对照 + 探针对照（三态判定，权威解释器 git 2.20.1） | 四形态 ZERO_HIT rc=1；对照 A HIT rc=0 ×4；探针 9/9 rc=0；`PROBE_GONE` | 通过 |
| G2 契约门（在途） | `--open TASK-181 --baseline=31733b6…` | rc=0（开工态，工作树空）→ rc=1（本笔 6 条路径入改动集后，判据 B 对 70+ 历史 handoff 的 `PLAN.md` / `pom.xml` 公共文件交叠过冲；**TASK-181 自身判据 B 通过**，原文 `t181-23-contract-out.log:1206`） | 过冲登记（§1.2.4） |
| G3 空白门 | `git diff --check` / `git diff --cached --check` | 无违规 | 0 / 0 |
| G4 token | PLAN.md `grep -cF` 29 项只增不减 | 开工 29 项实测；C-02 追加后逐项 +1（§6） | 通过 |
| G5 新增文件卫生 | evidence.md 纯 LF、末尾换行、无 BOM | CRLF 0 / LF 143 / BOM False；入库 blob CR=0 实测 | 通过 |
| G6 tasks.json | `python -m json.tool` + 结构核对 | 语法 rc=0；4 步 completed=true、2 分组 passes=true；仅翻标志位（6 行增删等量，正文零改写） | 通过 |
| G7 offline 全量 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | 450 恒等逐位，rc=0 | 通过 |
| G8 静态门 | `bash scripts/verify/mvn-verify.sh --static=record-service` | 811（= 基线，不增），rc=1 | 通过 |
| G9 生效取证 | `mvn -X`（§2.2 授权拼写） | §4.1 三行在场：版本 3.5.4 + `(s) argLine` + fork 双参数 | 通过 |
| G10 白名单足迹 | 实际改动集 vs §3 | `pom.xml` + `evidence.md`（C-01，2 files / +161 −0）；tasks.json + spec.md + handoff.md + PLAN.md（C-02）；其余路径零触碰 | 通过 |
| G11 红线 | 仅 root pom 一处、不引入新插件、未碰 jacoco/enforcer/模块 pom/`ci.yml`/`scripts`/`src` | 改动面＝root pom 的 properties 一行 + pluginManagement 一个块 | 通过 |
| G12 提交信息 | `git commit -F` 文件、无 BOM、UTF-8 | 消息文件无 BOM；提交 blob CR=0 | 通过 |
| G13 收口复跑 | 官方 `--mode=offline test` + `--static=record-service` + 无参契约门（C-02 与 C-03 落库后各一轮） | offline 逐位 `36/41/33/127/144/59/10` = 450 恒等 rc=0（两轮一致：C-02 后 `.trae/tmp/t181-26-closing-mvn.log:4255`，C-03 后 `t181-28-c03-offline.log:4254`）；静态 811 rc=1（不增）；**契约门无参 rc=0**（`判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`，`t181-27-closing-nonmvn.log:6`）；PLAN.md 与任务书 numstat 删除数为 0（纯追加）；派发笔三件套 `git diff` 零改动 | 通过 |

## 6. 受保护 token（PLAN.md 行命中数 `grep -cF`）

29 项：开工实测值 → 本笔追加后实测值。只增不减，`TOKEN_VIOLATIONS=0`。

| Token | 开工实测值 | 收口实测值 | 变动 |
| --- | --- | --- | --- |
| 13.4 | 21 | 22 | +1 |
| 18.0 | 22 | 23 | +1 |
| 73.93 | 21 | 22 | +1 |
| 68.8 | 21 | 22 | +1 |
| 6315 | 18 | 19 | +1 |
| 1.8612 | 17 | 18 | +1 |
| 3.3066 | 17 | 18 | +1 |
| 5.7056 | 17 | 18 | +1 |
| 9.408 | 17 | 18 | +1 |
| 36525962432 | 17 | 18 | +1 |
| 36586847965 | 16 | 17 | +1 |
| 36438897772 | 17 | 18 | +1 |
| 36399582548 | 16 | 17 | +1 |
| 36098038547 | 16 | 17 | +1 |
| 2806 | 23 | 24 | +1 |
| 598 | 16 | 17 | +1 |
| 36736221648 | 15 | 16 | +1 |
| 36808102571 | 10 | 11 | +1 |
| 36821040708 | 8 | 9 | +1 |
| 36845152965 | 7 | 8 | +1 |
| 36871294588 | 7 | 8 | +1 |
| 36880083885 | 8 | 9 | +1 |
| 36958994260 | 8 | 9 | +1 |
| 36976873215 | 8 | 9 | +1 |
| 36992632143 | 7 | 8 | +1 |
| 36995450125 | 5 | 6 | +1 |
| 37008317295 | 6 | 7 | +1 |
| 37021305016 | 7 | 8 | +1 |
| 37591580687 | 6 | 7 | +1 |

受保护集合规模仍为 **29 项**（本轮无新增：新出现的 run 号 `37722755341` 与 `37736029636` 以文本登记，不扩受保护集合）。

## 7. 未覆盖项与不得推出的结论

1. **未诊断 3.6.0 的内部机制**：本机只证明「3.6.0 下 attach 失败、与 flag 无关」，未拆解该版本改了 fork/agent 装载的哪一环；与 JaCoCo 晚绑定的耦合关系也未单独隔离（未跑 `-Djacoco.skip` 对照）。
2. **`allowAttachSelf` 对 CI 那条路径的有效性不由本机证明**：Windows 与 ubuntu runner 的末端 cause 不同（本机 `AgentInitializationException`，CI `Could not self-attach … using external process`）。
3. **不声称已修复 CI 基础设施**：本轮只登记仓库侧触发机制（未声明的测试插件版本 + 外部子进程 attach 兜底依赖）的消除；第 23 次门槛绿才是外部终验，同签名再现转深诊断、不再重试刷绿。
4. **未跑 `--it` 真中间件端到端**（本笔零生产代码、零测试语义改动）；Dockerfile 交付面仅静态阅读（六份镜像 build 阶段为在线 `mvn … package`，显式锁版本后不再依赖 metadata 解析），未在容器内实跑。
5. **契约门提取器的"正文即声明"盲区（建议另立变更）**：`extract_claims` 把「只改清单」小节正文里的所有文件名样 token 当作声明，含**零触碰声明**（「未碰…`pom.xml`」「显式零触碰：… `pom.xml` …」），且小节缺失时回退整档扫描。后果：任何后续再碰这些公共文件（本例 `pom.xml`）的任务，在途判据 B 必对大批历史任务判红——本笔即为此rc=1，但本笔自身判据 B 通过。与既记的扩展名白名单盲区（`env.example` / `.editorconfig`）同源，修法候选：只解析列表行（`^- path` 形态），或让"零触碰/未碰"句式不入声明集。
6. **不得据本机外推**：本机 Maven 3.9.4 / JDK 21.0.9 与 runner 的 Maven / Temurin 21.0.12+1 不同源，本机的 3.5.4 绿不替代 CI 的绿。

## 8. 提交明细表

| 批次 | 完整 SHA | 提交主题 | 涉及文件 |
| --- | --- | --- | --- |
| C-01 | `e560ddfd216dc9e11d1ba71ce9f5172e32b77416` | build(pom): 锁定 surefire 3.5.4 并启用测试 JVM 进程内 agent attach（TASK-181） | 2 文件（`pom.xml` +18；`work/mailbox/tasks/TASK-181/evidence.md` 新建 +143） |
| C-02 | `c1727366eff6b7204eb31da558fe9c3ffcf57d97` | docs(mailbox): 登记 TASK-181 attach 加固验收与台账闭环（TASK-181） | 4 文件（`spec/changes/harden-surefire-agent-attach/tasks.json`、`work/mailbox/tasks/TASK-181/spec.md`、`work/mailbox/tasks/TASK-181/handoff.md`、`work/mailbox/PLAN.md`） |
| C-03 | 父锚定：父 = `c1727366eff6b7204eb31da558fe9c3ffcf57d97`（落库后 `git rev-list --left-right --count origin/main...main` = `0 4`） | docs(pom): 订正 surefire 注释与实锁值一致（TASK-181） | 4 文件（`pom.xml` 注释订正、本 handoff、任务书收口追加一条、PLAN.md 订正记录纯追加），构建语义零变更 |
