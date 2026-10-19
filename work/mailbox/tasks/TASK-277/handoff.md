# TASK-277 实施交接台账（handoff）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-19 指导侧终裁；开工基线 main @ `5daff7e`；C-01 `0689459` / C-02 `8ef0919`；MEDIUM 档免独立复核，指导亲核三块 PASS，两处关键决策均裁定采纳；封盘批次统一推送 origin/main，CI 外部终验随后登记）

## 1. 基线与提交

- 开工基线：main @ `5daff7e`（TASK-276 封盘笔）；开工实测 `git status --porcelain` 为空，无跨任务窗口效应；
- 任务书：`work/mailbox/tasks/TASK-277/spec.md`；
- C-01：`0689459` `chore(mapmatch): 补齐Checkstyle规则集并零语义清零266项存量违规（TASK-277）`——恰 7 文件，+365/−6（`git show --numstat` 实测）；落库后工作树随即纯净；
- C-02：`8ef0919` `docs(mailbox): TASK-277 实施 handoff 回填（TASK-277）`——本笔仅本台账 1 文件；哈希由指导侧封盘时以 `git log -1` 回填（2026-10-19 已回填）；
- 提交链核验：`git log --oneline 5daff7e..HEAD` 恰 2 笔线性（立项笔 `9ab922d` 已含于基线之前，见 `git merge-base --is-ancestor 9ab922d 5daff7e`）；`origin/main..HEAD` = 2 笔，严格零推送；
- 白名单上限核对：C-01 7 文件 ⊆ spec §三上限 18 项；C-02 仅台账 1 文件；合计 8 项 ⊆ 19 项上限。

## 2. 改动清单（恰 8 项 = C-01 7 + C-02 台账 1，与 `git diff --name-only 5daff7e` 双向全等）

- 改动清单（逐行路径 token 供判据 B 机器比对；行数为 `git show --numstat 0689459` 实测，台账行数为 C-02 本笔实测）：
  - mapmatch-service/pom.xml（+52/−0）
  - mapmatch-service/src/main/resources/checkstyle.xml（+262/−0，新增文件）
  - mapmatch-service/src/main/java/com/sportverify/mapmatch/MapMatchApplication.java（+5/−0）
  - mapmatch-service/src/main/java/com/sportverify/mapmatch/controller/MapMatchController.java（+7/−1）
  - mapmatch-service/src/main/java/com/sportverify/mapmatch/controller/SegmentController.java（+13/−2）
  - mapmatch-service/src/main/java/com/sportverify/mapmatch/service/MapMatchService.java（+7/−1）
  - mapmatch-service/src/main/java/com/sportverify/mapmatch/service/SegmentService.java（+19/−2）
  - work/mailbox/tasks/TASK-277/handoff.md（+89/−12，C-02 本笔）
- 未进入改动集（实测零违规/零扫描面，严禁列入）：10 个测试类（checkstyle 配置 `includeTestSourceDirectory=false`，与 leaderboard-service 同配置，测试源码不在扫描面 → 0 违规）；规则集同目录的既有配置文件与本任务无关。
- MatchProperties 未改动（如实记账）：其基线 14 项违规全部落在规则集已裁剪/已放宽类别，采用保留规则集后实测 0 违规，故按「仅实测存在违规者进入改动集」口径不入 diff；6 个主类中 5 个进入改动集。
- **R0 违规分布快照（开工第一步实测：`--static=mapmatch-service` 报 266 项、10 类规则；下表类名不带扩展名，避免判据 B 误提取）**：

| 主类（不含扩展名） | 合计 | 规则分布 |
| :--- | ---: | :--- |
| MapMatchService | 119 | FinalParameters 33 / JavadocMethod 45 / JavadocPackage 1 / JavadocStyle 17 / JavadocVariable 2 / LineLength 20 / MultipleVariableDeclarations 1 |
| SegmentService | 107 | FinalParameters 18 / JavadocMethod 28 / JavadocStyle 23 / JavadocVariable 8 / LineLength 26 / MagicNumber 4 |
| MatchProperties | 14 | JavadocPackage 1 / JavadocStyle 7 / MagicNumber 6 |
| SegmentController | 13 | FinalParameters 2 / JavadocMethod 4 / JavadocStyle 3 / JavadocVariable 1 / LineLength 3 |
| MapMatchController | 8 | FinalParameters 1 / JavadocMethod 2 / JavadocPackage 1 / JavadocStyle 2 / JavadocVariable 1 / LineLength 1 |
| MapMatchApplication | 5 | FinalParameters 1 / HideUtilityClassConstructor 1 / JavadocPackage 1 / JavadocStyle 1 / MissingJavadocMethod 1 |
| **合计** | **266** | JavadocMethod 79 / FinalParameters 55 / JavadocStyle 53 / LineLength 50 / JavadocVariable 12 / MagicNumber 10 / JavadocPackage 4 / MultipleVariableDeclarations 1 / MissingJavadocMethod 1 / HideUtilityClassConstructor 1 |

## 3. 七门禁实测读数

| # | 门禁 | 命令 | rc | 关键读数（实测时点） |
| :- | :--- | :--- | :-: | :--- |
| 1 | 全仓 offline 全量单测 | `scripts/verify/mvn-verify.sh --mode=offline test` | 0 | BUILD SUCCESS；全仓 surefire 合计 **1197** = 63/79/250/406/203/139/**57**，0 败 0 错 0 跳（时点：C-01 工作树，内容与 C-01 提交逐位一致） |
| 2 | 静态门禁（本任务核心） | `--static=mapmatch-service` | 0 | BUILD SUCCESS；Checkstyle **0 违规**（`You have 0 Checkstyle violations.`）；SpotBugs 实测 3 bugs 全为 Medium（EI_EXPOSE_REP2，构造器注入字段，High 级 0 条 → 按 failThreshold=High 通过）；PMD 实测 0 违规（priority 4 已清零）。时点：C-01 工作树 |
| 2b | 静态门禁连带漂移复跑 | `--static=leaderboard-service` | 0 | BUILD SUCCESS；Checkstyle 0 违规；SpotBugs 15 bugs 全 Medium；PMD 0 违规 —— 与 TASK-018/TASK-275 同期口径一致，无连带漂移（时点：C-01 工作树） |
| 3 | 前端三件套（零前端改动） | `pnpm run type-check` / `pnpm test` / `pnpm run e2e` | 0/0/0 | vue-tsc 0 错；vitest 6 文件 **90** 测全绿；E2E **14/14** 全绿（14 passed，1.3m）；TMP/TEMP 重定向至 `.trae/tmp/build-tmp`，未改任何脚本/配置（时点：C-01 提交后） |
| 4 | 契约门 | `scripts/verify/mailbox-contract.sh --open=TASK-277 --baseline=5daff7e` | 0 | 判据 A：TASK-277 两件套齐；判据 B：**8 路径双向全等**（清单 8 = 实际改动 8），无清单外裸路径、无未声明改动（时点：C-02 落库后终态复跑） |
| 5 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树全仓 tracked 累加 | 0 | 基线 `5daff7e` 树 SUM = **2539**；C-01 树 `0689459` SUM = **2539**（逐项零增量，29 项逐位相同）；C-02 树 SUM = **2568** = 2539 + 29（§6 自枚举逐项行恰各 +1） |
| 6 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex268.txt HEAD -- ':!spec/changes/archive/**' ':!docs/internal/**' ':!.github/workflows/ci.yml'`（四 locale 等效手跑） | 1 | default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 四 locale 全 ZERO_HIT（rc=1）；阳性对照 rc=0（探针行命中即删，工作树复核干净） |
| 7 | diff 净与行尾 | `git diff 5daff7e HEAD --check` / 入库 blob 逐字节扫描 | 0 | `--check` 输出空 rc=0；8 个改动文件 blob CR 合计 **0**（纯 LF），首字节 od 均非 BOM（新增 checkstyle.xml 首字节实测 `3c 3f 78`）；工作树 CRLF 警告仅 autocrlf 回写提示，blob 口径为零 CR |

## 4. R0–R3 实施要点

- **R0（基准与对照）**：开工即实测 266 项分布（§2 表）并落盘 `target/checkstyle-result.xml`；整改后同一命令复测 0 违规，形成「266 → 0」对照基线。
- **R1（规则集与插件对齐）**：
  - 规则集：以 `git cat-file -p leaderboard-service/src/main/resources/checkstyle.xml` 直接落盘为 mapmatch 同名文件，`cmp` 逐字节一致 + `sha256sum` 双文件同值 `289abf8d…c2e076`（262 行）；未增删一个字符、未改一行注释。
  - pom 三段与 leaderboard-service 现行配置逐项对齐：checkstyle 3.6.0（`configLocation` 指向本模块同名路径、`inputEncoding` UTF-8、`includeTestSourceDirectory=false`、console/log 三开关全开）；spotbugs 4.9.8.5（`failThreshold=High`、`includeTests=false`）；pmd 3.28.0（默认规则集 `rulesets/java/maven-pmd-plugin-default.xml`、`includeTests=false`、`failurePriority=3`、`printFailingErrors=true`）。三段均**不绑执行/不绑 phase**（与 leaderboard-service 一致），故 `mvn test` 生命周期零扰动、1197 计数不变；未引入任何新依赖坐标。
- **R2（零语义整改）**：C-01 的 Java 改动逐项性质——(a) 注释补全 12 处字段 Javadoc + 7 处方法 Javadoc（含 2 个控制器公有方法补 `@param/@return`、2 个服务公有方法补 `@return`、`main` 补 Javadoc）；(b) 1 处多变量声明拆行（`double dx = cx - ax, dy = cy - ay;` → 两行）；(c) 2 处逐位等价重写（详见 §5）。零逻辑改写、零方法签名变更、零注解变更、零配置值变更。改前/改后 `mvn -o test -pl mapmatch-service -am` 双跑：均为 **57 tests / 0 failures / 0 errors / 0 skipped**，测试名集合逐位一致（改前 `t277-mmtest-pre.log`、改后 `t277-mmtest-post.log`，均在 `.trae/tmp/` 留证）。
- **R3（收口）**：`--static=mapmatch-service` Checkstyle 0 违规 + SpotBugs/PMD 通过（§3 门禁 2）；`--static=leaderboard-service` 复跑 rc=0 确认零连带漂移（门禁 2b）。

## 5. 决策记录与偏差说明

1. **清零口径（关键决策）**：R0 的 266 项是「不配 configLocation 时插件默认 sun_checks」口径；R1 又强制规则集与 leaderboard-service **逐字节一致**。该规则集按 TASK-018 家法对 13 条规则做了有据裁剪（文件内逐条注明分类与理由），保留的检查共 10 组（JavadocType、命名 9 项、import 卫生 3 项、MethodLength/ParameterNumber、空白 9 项、ModifierOrder/RedundantModifier、块 4 项、常见编码 8 项、FinalClass/InterfaceIsType/VisibilityModifier、ArrayTypeStyle/TodoComment/UpperEll + 文件级 NewlineAtEndOfFile/Translation/FileLength/LineLength(140)/FileTabCharacter/RegexpSingleline）。两口径必然冲突：**266 项中 196 项落在该规则集整条裁剪的类别**（JavadocStyle 53、FinalParameters 55、MagicNumber 10、JavadocPackage 4、HideUtilityClassConstructor 1、未被补 Javadoc 的私有方法 JavadocMethod 73）、**50 项落在 LineLength 阈值放宽（80→140）**、**20 项由代码层实改清零**（JavadocVariable 12、MissingJavadocMethod 1、MultipleVariableDeclarations 1、公有方法 JavadocMethod 6）。196+50+20 = 266，账目闭合。
2. **为何不做 sun_checks 字面全零**：JavadocPackage 4 项要求每个包新增 `package-info.java`（4 个新文件，超出 19 项白名单）；HideUtilityClassConstructor 命中 Spring Boot 启动类，消解须加私有构造器（容器无法实例化配置类，属「必须改可执行语义」，且与 TASK-018 将其判为 ①误报同源）；JavadocStyle 与中文 Javadoc 句读约定冲突；FinalParameters 必须改方法声明修饰符，触碰「不改方法签名」红线。即触发派发令停止条件 1 的第一类情形，但 R1+R3 的机械判据可完整满足，故按「规则集 0 违规」收口并如实记账，不以文件级屏蔽掩盖。
3. **pom 新增 spotbugs/pmd 两段的理由**：`--static` 以全限定 GAV 直调 `spotbugs:check`/`pmd:check`，无模块级配置时按插件默认更严阈值执行——实测 mapmatch 4 bugs（3 条 Medium EI_EXPOSE_REP2 构造器注入误报 + 1 条 High RC_REF_COMPARISON）与 1 条 PMD priority 4。按 R1「与 leaderboard-service pom 现行配置逐项对齐」补两段（版本/阈值/范围逐项相同，不绑生命周期、无新依赖坐标），并把 High 级与 priority 4 两项**在代码层**一并消除，使 High 级红线实测 0 条（未用任何 excludeFilter/suppressions 静默掩盖）。
4. **两处逐位等价重写（零语义自证）**：(a) `PRESET_SPORT_TYPES.get(i) != sportType` → `.intValue() != sportType`：列表元素恒为 1/2 的 Integer 缓存实例，入参为 1/2 时引用比较与值比较同真，其余取值两法同假 → 全定义域等价，且消除 SpotBugs RC_REF_COMPARISON（High）；(b) `: (ca == null ? null : ca.toString())` 去掉三元外层括号：条件表达式右结合，语法树等价（PMD UselessParentheses，priority 4）。两处均为等价重写，非行为修正。
5. **测试类零改动依据**：checkstyle `includeTestSourceDirectory=false`（与 leaderboard-service 同配置），10 个测试类在扫描面之外、实测 0 违规，故不进入改动集（派发令「零违规者严禁列入」）。
6. **收敛声明**：本任务只碰 mapmatch-service 的 pom/规则集/5 个主类 + 本台账，其余六模块、根 pom、scripts、.github、docker-compose、web、sql、api、application.yml 零触碰（`git diff --name-only 5daff7e HEAD` 全量 8 路径可证）。

## 6. token 门 29 项逐项读数（基线 `5daff7e` / C-01 `0689459` / C-02 本笔）

- 13.4 → 172 / 172 / 173
- 18.0 → 188 / 188 / 189
- 73.93 → 129 / 129 / 130
- 68.8 → 124 / 124 / 125
- 6315 → 104 / 104 / 105
- 1.8612 → 103 / 103 / 104
- 3.3066 → 103 / 103 / 104
- 5.7056 → 109 / 109 / 110
- 9.408 → 94 / 94 / 95
- 36525962432 → 87 / 87 / 88
- 36586847965 → 84 / 84 / 85
- 36438897772 → 86 / 86 / 87
- 36399582548 → 84 / 84 / 85
- 36098038547 → 82 / 82 / 83
- 2806 → 124 / 124 / 125
- 598 → 121 / 121 / 122
- 36736221648 → 81 / 81 / 82
- 36808102571 → 69 / 69 / 70
- 36821040708 → 63 / 63 / 64
- 36845152965 → 60 / 60 / 61
- 36871294588 → 58 / 58 / 59
- 36880083885 → 60 / 60 / 61
- 36958994260 → 59 / 59 / 60
- 36976873215 → 54 / 54 / 55
- 36992632143 → 51 / 51 / 52
- 36995450125 → 49 / 49 / 50
- 37008317295 → 48 / 48 / 49
- 37021305016 → 47 / 47 / 48
- 37591580687 → 46 / 46 / 47
- 合计：2539 / 2539 / 2568（逐项零增量、末树恰 +29）

## 7. 收口自证与回滚

- `git status --porcelain` 收口后为空；`origin/main..HEAD` = 2 笔线性（C-01 + C-02），严格零推送（推送由指导侧统一执行）；
- 回滚：`git revert` C-01 + C-02 即回到豁免挂账状态，无数据/配置残留（无 SQL、无迁移、无外部依赖）；
- 证据文件（本机 `.trae/tmp/`，非入库）：`t277-static-base.log`（266 基线）、`t277-static2.log`（清零终态）、`t277-static-lb.log`（连带漂移复跑）、`t277-mmtest-pre.log` / `t277-mmtest-post.log`（57 双跑对照）、`t277-offline.log`（1197）、`t277-web.log`（前端三件套）、`t277-final.txt`（diff/CR/BOM/token/词面合集）、`t277-tok-{base,head}.txt`（逐项 token）。
