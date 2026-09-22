# TASK-118 handoff：台账禁用词原文改写（解锁下次 push）

结论：**两处原文引用已改写为指代表述，CI 同款词面判据在 `LC_ALL=C` 下全量 ZERO-HIT**
（改前 2 条 → 改后 0 条）；默认 locale 下台账两行同样零命中，余下 2 条落在本任务只改清单外的
既有 Java 文件上，属同源 locale 伪影、**按未覆盖记账**（见末节）。全量 `--mode=offline test`
**284 全绿零扰动**。未 push。

## 只改清单（contract 判据 B，与实际受版本控制改动集一致）

- work/mailbox/PLAN.md
- work/mailbox/tasks/TASK-106/handoff.md
- work/mailbox/tasks/TASK-118/spec.md
- work/mailbox/tasks/TASK-118/handoff.md

### 开工基线与本任务边界

开工基线 `0f31c18`（`git status` 事前仅 `?? .trae/`，领先 `origin/main`（`726cf63`）5 个提交）。
本任务**未 push、未建 PR**；未改 CI 工作流、未动归档变更目录（`spec/changes/archive`）与内部文档目录、
未改契约脚本与统一验收入口本体、未动任何生产代码；全程未用 `git stash`。
实际改动集（`git diff --name-only 0f31c18` + untracked 排除 `.trae/`）与上方 4 项**逐字一致**，无多报无漏报。

分批提交：`a424b67` 建档两件套 · `2f8c090` 两处改写 · 本条记录所在的收口提交（均未 push）。

## 改写对照（语义保留，禁用词原文改为指代表述）

两处改写的都是「为描述 locale 伪影而引用词面自检禁用词原文」这一句。「被误判的词」改为**按字节书写**
（`0xE5 0x9E 0x82`），不再出现该词的汉字形态，因而不可能再与该表内的词形成字面或分支等价关系。

| 落点 | 改写后 |
| --- | --- |
| `work/mailbox/PLAN.md` 第 353 行 | 「…使既有文件 `…MapMatchResultDTO.java` 中「垂距」的「垂」（`0xE5 0x9E 0x82`）被误判为**词面自检的禁用词**命中——二者仅差第二字节、被判成大小写等价（TASK-118 实测：该误判只在模式含多分支时复现）…」并在句末补「该处原文引用已由 TASK-118 改写为指代表述」 |
| `work/mailbox/tasks/TASK-106/handoff.md` 第 97–99 行 | 同一表述的同源改写（附带发现一节），并注明「该处原文引用已由 TASK-118 改写为指代表述」 |

语义三点逐条保留：① 本机 MSYS `git grep -i` 在默认 `C.UTF-8` 下把该词的字节判成与表内某词等价；
② 该文件本任务/本任务前一次改动未触碰，且属上次 CI 绿（run `35671465068`）已含内容；
③ 判为 locale 伪影、非真命中，`LC_ALL=C` 下全量 ZERO-HIT。
改写**未删减**任何其它字句；`PLAN.md` 只动第 353 行（`git diff -U0` 实测 1 行替换），
TASK-106 handoff 只动 97–98 两行（同一句换行重排，净 +2 行）。

## 红绿取证（CI 同款判据，两 locale 各一次）

脚本 `.trae/tmp/wording-check-118.sh`（UTF-8 承载禁用词正则，命令行保持纯 ASCII），
与 `ci.yml` 的 `Public docs wording self-check` 同构：`git grep -n -I -iE <禁用词表> -- 三排除`。

**红（改前，`0f31c18`+在建档）** —— 退出码 **1**：

```
===== locale=C rc=0 =====
work/mailbox/PLAN.md:353:| 词面自检 | …〔此处引用禁用词原文，回传内省略〕…
work/mailbox/tasks/TASK-106/handoff.md:97:  …〔此处引用禁用词原文，回传内省略〕…
VERDICT[C]=HIT-FOUND hits=2
===== locale=default rc=0 =====
api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17: …〔含「垂距」字样的注释行〕…
api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:36: …〔同上〕…
work/mailbox/PLAN.md:353: …
work/mailbox/tasks/TASK-106/handoff.md:97: …
VERDICT[default]=HIT-FOUND hits=4
OVERALL_RC=1
```

**绿（改后，`2f8c090`）** —— 命令同红：

```
===== locale=C rc=1 =====
(no match)
VERDICT[C]=ZERO-HIT
===== locale=default rc=0 =====
api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17: …〔含「垂距」字样的注释行〕…
api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:36: …〔同上〕…
VERDICT[default]=HIT-FOUND hits=2
OVERALL_RC=1  (1=有命中)
```

| 口径 | 改前命中 | 改后命中 | 落点是否在只改清单内 |
| --- | --- | --- | --- |
| `LC_ALL=C`（CI 语义，见下节） | 2 | **0** | — |
| 默认 `C.UTF-8`（本机） | 4 | **2** | 改后 2 条**全部**在白名单外的既有 Java 文件上；台账两行已零命中 |

## 伪影最小复现与 CI 侧判定（必读，因原台账的机制描述不完整）

原台账把伪影写成「把字节 `0x8E`/`0x9E` 当大小写等价」，**该描述不足以复现**。本任务实测最小形态
（`.trae/tmp/wording-probe2-118.sh`、`wording-probe3-118.sh`，同一文件、同一 `-i`）：

| 模式 | 默认 locale 结果 |
| --- | --- |
| `<禁用词>`（单分支，字面） | rc=1，**0 命中** |
| `<禁用词>｜<其它词>`（加一个分支） | rc=0，命中 `MapMatchResultDTO.java:17/36` |
| 表内某词的**第二字**（单独一个字） | rc=1，**0 命中** |

⇒ 触发条件是**模式含多分支（`|`）**，且命中的是「大 + 表内某词第二字节的等价字节」组合；
单分支字面量与单字均不复现。故「字节等价」是被观测到的**现象**，而「引擎在哪一层按 cp1252 折叠」
属**本机推断、未证实到引擎层**，回传与台账均已按此口径降级书写，不冒充结论。

**CI 不受该伪影影响（两点实测 + 一点台账来源）**：

1. 该自检步骤与本任务同款正则、同款三排除，在 `620240b`（上次绿 run `35671465068` 的 head）**已存在**
   （`git show 620240b:.github/workflows/ci.yml` 第 64–67 行实测），且 `620240b` 是 `HEAD` 的祖先。
2. 同一 head 上 `MapMatchResultDTO.java` **已含**「垂距」6 处（`git cat-file -p 620240b:<path>` 实测）
   ⇒ 若 CI 侧（Linux）会折叠，那次 run 早就该红；该 run 两 job 全绿（台账既有记录）。
3. 本机 `gh` 未登录（`gh auth login` 提示），**无法独立拉取该 run 的 step 级结论**，故第 1、2 点为
   本任务新取的实测，第 3 点沿用台账既有记录、未作外部复核 —— 不写成「已复核 CI」。

## 全量验收（唯一入口）

```
[verify-entry] 生效模式：offline
[verify-entry] localRepository：D:/code/sports/.m2-repo
[verify-entry] 命令全文：mvn -B -ntp -o -s .mvn-settings.xml clean test
[INFO] BUILD SUCCESS
[INFO] Total time:  05:29 min
MVN_RC=0
```

逐模块 `Tests run`（Failures/Errors/Skipped 全 0）：**17 / 19 / 31 / 80 / 81 / 50 / 6 = 284**，
与基线 `0f31c18` 逐位一致 ⇒ 纯台账文本改动**零扰动**；生效模式 offline、依赖来源可判定（未触发退出码 3）。

**环境坑（如实登记，非本任务引入）**：首次直接 `bash scripts/verify/mvn-verify.sh --mode=offline test`
返回 **rc=1**，日志为 `错误: 找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`。
根因是本机继承了 `MSYS_NO_PATHCONV=1` / `MSYS2_ARG_CONV_EXCL=*`，`mvn` 脚本把 unix 路径喂给 Windows
java（既有备忘已记载该坑）。处置：同一命令前置 `unset MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL`
+ `export JAVA_HOME=/d/develop1/jdk21` → rc=0（上方结果即该次）。**这不是一次用例红**，
也不得记成依赖来源不可判定（未触发退出码 3）。

## 契约自证

- 在途（`2f8c090`，工作树仅本条待写）：`bash scripts/verify/mailbox-contract.sh --baseline=0f31c18`
  → 退出 **1**；其中 **TASK-118 段亦报不一致**，原因已定位并修掉：初版 handoff 的「只改清单」小节
  夹带了「未改 CI 工作流的路径」这类路径 token，被判据 B 当成「清单多报」——
  该节现已只保留 4 个路径，其后另起子标题隔断（本次修订）。
  其余 `TASK-018/106/109~117` 共 **11 个历史任务**报在途不一致，成因同一：这些历史 handoff 正文含
  `PLAN.md`/`README.md`/`pom.xml` 等公共文件路径，与本任务改动集（含 `PLAN.md`）交叠触发在途强校验，
  与台账（TASK-106/116/117/018）已记录的「公共文件过冲」同源，与本任务改动无关。
- 收口提交后：`bash scripts/verify/mailbox-contract.sh`（**无参数**）→ 退出 **0**
  （工作树无迹 ⇒ 「足迹不在工作树，视为已收口」不重审）。实测见 `PLAN.md` 台账「契约自证」行。

## 未覆盖（不得写成通过）

1. **默认 locale 下仍有 2 条命中**，落点 `api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17/36`。
   该文件**不在本任务只改清单内** ⇒ 未修、按未覆盖记账（要件齐备时的处置建议见「待主 agent 决定」第 1 条）。
2. **CI 侧结论未独立复核**：`gh` 未登录，只能以「上次绿 run 的 head 上该步骤已存在 + 该文件已含触发内容
   + 该 run 全绿」三点间接判定，未拉取 step 级日志。
3. 本次只在**本机**取证；CI 该步骤的真实执行结果须待下次 push（属外部写操作，本任务不动）。

## 待主 agent 决定

1. **默认 locale 那 2 条伪命中是否要顺带清掉**：可行的最小改法是给 `MapMatchResultDTO.java` 里
   「垂距」处的措辞加一个分隔（如「垂向距离」或两字之间插入空格），使「大 + 该字节」组合消失；
   但该文件是**只改清单外**的生产代码（且改动会牵动 `verify-service` 的字段注释一致性），
   故本任务未越界，留指导侧裁定是否另开最小变更。
2. **CI 侧的真伪需要一次 push 才能结清**：本任务只能给出本机 + 上次绿 run 的间接证据；
   若要实证，请在下次 push 后核对 `Public docs wording self-check` 步骤结论。
3. **是否把这处鉴伪方式沉淀为脚本**：本次的 `LC_ALL=C` + 默认 locale 双跑（含「改前未提交也能复用」）
   已在 `.trae/tmp/` 留下可复用脚本，但该目录不入库；若要长期保留，需决定落地位置。
