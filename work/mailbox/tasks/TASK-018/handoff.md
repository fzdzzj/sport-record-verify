# TASK-018 handoff：leaderboard-service 静态检查三件套接入（checkstyle / spotbugs / pmd）

## 只改清单（contract 判据 B，与实际受版本控制改动集一致）

- leaderboard-service/pom.xml
- leaderboard-service/src/main/resources/checkstyle.xml
- leaderboard-service/.editorconfig
- work/mailbox/tasks/TASK-018/handoff.md
- work/mailbox/PLAN.md
- scripts/verify/README.md

开工基线 `dfa747a`（`git status` 事前仅 `?? .trae/`），领先 `origin/main` 4 个提交，全程未 push、未建 PR。
未动统一验收入口脚本本体、契约脚本本体、CI 工作流、服务生产代码、迁移脚本 V21-V23 与其他模块；全程未用 `git stash`。
仓库外兜底：`../sports-bundle/pre-task018-dfa747a.bundle`（`git bundle --all`，2009754 字节）。

> 判据 B 备注：`.editorconfig` 不在契约脚本路径提取的扩展名白名单内，因此**在本仓任何在途窗口
> 都会被记为「改动集未声明」**（详见文末「待主 agent 决定」第 3 条）。收口提交后工作树无迹，
> 该条不影响收口后契约退出 0。规划清单已如实列出该文件，未刻意回避。

## 装料清单（前置停止边界：网络可达，未停手）

`.m2-repo` 内 checkstyle / pmd / spotbugs 插件与引擎原为**全空**（实测 `com/puppycrawl`、
`net/sourceforge/pmd`、`com/github/spotbugs` 三个目录均不存在）。不带 `-o` 首跑三 goal，
从 `https://repo.maven.apache.org/maven2` 落料，**新增 122 个 jar**，全部来自 central。

直接声明的三插件与引擎（GAV）：

| 角色 | GAV |
| --- | --- |
| checkstyle 插件 | `org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0` |
| checkstyle 引擎 | `com.puppycrawl.tools:checkstyle:9.3` |
| PMD 插件 | `org.apache.maven.plugins:maven-pmd-plugin:3.28.0` |
| PMD 引擎 | `net.sourceforge.pmd:pmd-core:7.17.0`、`pmd-java:7.17.0`、`pmd-javascript:7.17.0`、`pmd-jsp:7.17.0` |
| SpotBugs 插件 | `com.github.spotbugs:spotbugs-maven-plugin:4.9.8.5` |
| SpotBugs 引擎 | `com.github.spotbugs:spotbugs:4.9.8`、`spotbugs-annotations:4.9.8` |

传递依赖 122 件按顶层 groupId 概览：`org/apache` 47、`org/codehaus` 17、`com/github` 8、
`org/ow2` 6、`org/eclipse` 6、`com/google` 6、`org/xmlresolver` 4、`net/sourceforge` 4、
`org/slf4j` 3、`net/sf` 3、`org/checkerframework` 2、其余单件（`commons-io`、`org/antlr`、
`org/dom4j`、`com/thoughtworks/qdox`、`info/picocli`、`jaxen`、`javax/xml`、`org/mozill*` 等）。
`.m2-repo/` 与 `.mvn-settings.xml` 本就在 `.gitignore` 内，装料产物不入改动集。

版本选择：三插件均取 2024 年之后发布的稳定线（3.6.0 / 3.28.0 / 4.9.8.5），与 Maven 3.9.4 +
JDK 21 实测兼容；SpotBugs 取 4.9 成熟线最新补丁（4.10.x 为较新线，本次不引入，降升级风险）。

**装料位置的一处附带修复（如实声明，非本任务白名单内文件）**：`.m2-repo` 中的内部构件
`sport-verify-common` 是 **2026-09-12 的旧包**，而 `com.sportverify.common.trace.TraceIds`
是 **2026-09-16**（`5846548`）才落库的 —— 即本地仓的 common 包**早于其源码 4 天**。
`cd leaderboard-service && mvn test-compile` 这类**独立模块构建**会从 `.m2-repo`（而非 reactor）
解析 `sport-verify-common`，此前一直没暴露是因为增量编译跳过了编译；一旦任何源文件触发全量重编，
就报 `程序包 com.sportverify.common.trace 不存在`（本任务在红绿还原后 `touch` 复跑时**实际撞上**，
见「修复记录」第 2 条）。已用 `mvn -o -pl common,api -am install -DskipTests` 刷新为当日包，
此后离线全量重编通过。该修复只动 `.m2-repo`（gitignored），不进改动集。

## N_default 基准计数（各工具默认规则集首跑，2026-09-22）

命令：`cd leaderboard-service && mvn -B -ntp -s ../.mvn-settings.xml test-compile <goal>`
（注：`spotbugs` goal 前缀在插件未声明于 pom 时不可解析——插件组只有
`org.apache.maven.plugins` 与 `org.codehaus.mojo`——故 spotbugs 首跑用全限定 GAV
`com.github.spotbugs:spotbugs-maven-plugin:4.9.8.5:check`。）

| 工具 | 规则集 | N_default | rc |
| --- | --- | --- | --- |
| checkstyle 9.3 | sun_checks.xml（插件默认） | **374** | 1 |
| PMD 7.17.0 | `rulesets/java/maven-pmd-plugin-default.xml`（插件默认，42 条规则） | **2** | 1 |
| SpotBugs 4.9.8 | 引擎默认 effort/threshold | **10** | 1 |

checkstyle 374 条的构成（13 种规则）：JavadocStyle 90、JavadocMethod 82、LineLength 79、
FinalParameters 65、JavadocVariable 22、JavadocPackage 8、HiddenField 7、MissingJavadocMethod 5、
OperatorWrap 4、MagicNumber 4、DesignForExtension 4、UnusedImports 3、HideUtilityClassConstructor 1。
证据存档：`N_default-checkstyle-result.xml`（65 KB 结构化报告）、`N_default-effective-config.xml`
（插件解析出的生效配置）、`N_default-pmd.xml`。

## 透明豁免面构成（N_default → 0）

分类口径：①误报 ②Lombok/框架生成代码 ③风格项与既有代码库冲突 ④工具间重复规则。

| 工具 | N_default | 处理后 | 处置 | 规则数 | 分类分布 |
| --- | --- | --- | --- | --- | --- |
| checkstyle | 374 | **0 违规** | 关闭 12 条规则（295 条）＋ 放宽 1 条（79 条） | 13 | ①1 ②4 ③369 ④0 |
| PMD | 2 | **0 条进失败判据** | 降级 1 条规则（`failurePriority=3`） | 1 | ③2 ④0 |
| SpotBugs | 10 | **0 条进失败判据** | 抬高失败阈值（`failThreshold=High`） | 2 型 | ①1 ②9 ④0 |

### checkstyle（关闭 12 条 + 放宽 1 条，逐条理由写在 `src/main/resources/checkstyle.xml` 内）

| 规则 | N | 分类 | 理由摘要 |
| --- | --- | --- | --- |
| JavadocStyle | 90 | ③ | sun 要求首句以英文句号结尾；本仓为中文多段落 Javadoc |
| JavadocMethod | 82 | ③ | 要求 @param/@return 全覆盖；补齐须改 Java 源（白名单外） |
| LineLength | 79 | ③（放宽，非关闭） | sun 默认 80；既有最长行 136（CacheConfig:89），取 120 会引入 4 条存量违规，故取 **140** |
| FinalParameters | 65 | ③ | 本仓约定不写 `final` 参数，65 处遍及全模块 |
| JavadocVariable | 22 | ③ | 字段级 Javadoc；命中集中在注入字段与内部类私有字段 |
| JavadocPackage | 8 | ③ | 要求每包 `package-info.java`；新增 8 个文件超出白名单 |
| HiddenField | 7 | ③ | `this.x = x` 构造器赋值与 enum/setter 参数同名 |
| MissingJavadocMethod | 5 | ③ | 与 JavadocMethod 同源（`main` 与 4 个 getter/setter） |
| OperatorWrap | 4 | ③ | Mapper 注解内 SQL 字符串拼接的 `+` 收在行尾 |
| MagicNumber | 4 | ③ | 4 处业务常量字面量（容量 1000、重连 30s、查询上限 1000） |
| DesignForExtension | 4 | ② | 全部落在 `UserMileage`（MyBatis 列别名映射载体）的 getter/setter |
| UnusedImports | 3 | ③ | 3 条真实无用导入，消除须改 Java 源 |
| HideUtilityClassConstructor | 1 | ① | **误报**：命中 `LeaderboardApplication`（Spring Boot 启动类，含 `main`） |

LineLength 的 140 是唯一的「阈值放宽」而非关闭：红绿取证即用它触发（见下）。
`SuppressionFilter` / `SuppressionXpathFilter` 两个文件级屏蔽入口被有意**保留但为空操作**
（`optional=true` 且对应文件不存在），口径写在规则集注释里：文件级豁免须走评审，不得静默掩盖。

### PMD（降级 1 条）

`UnnecessaryImport`，N_default = 2，priority 4 / Code Style：

- `LeaderboardService.java:3` — `com.sportverify.api.event.RecordVerifyEvents`
- `LeaderboardService.java:21` — `org.springframework.cache.annotation.EnableCaching`

为何是「降级」而不是「关闭」：摘掉这一条需要**新增一个 PMD 规则集文件**（超出白名单），
而 PMD 的 `rulesets` 参数**不支持「某规则集减去一条规则」的单规则引用写法**（实测踩坑见
「修复记录」第 1 条）。故设 `failurePriority=3`：只收窄失败判据，不收窄分析 —— 这 2 条仍原样
写入 `target/pmd.xml`、并以 `[WARNING] PMD 7.17.0 has issued 2 warnings` 出现在构建日志里。

### SpotBugs（抬高失败阈值）

10 条**全部为 Medium（priority=2）**，逐条：

- **9 条 `EI_EXPOSE_REP2`（MALICIOUS_CODE）** — 构造器注入字段（`@RequiredArgsConstructor` /
  显式构造器）的经典误报：`LeaderboardController:38`、`InternalLeaderboardController:23`、
  `LeaderboardEventConsumer:51`（4 处字段）、`LeaderboardService:62`（3 处字段）。分类 ②。
- **1 条 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`** — `LeaderboardService.java:286`，
  同行 `t.getScore() == null ? BigDecimal.ZERO : BigDecimal.valueOf(t.getScore())` 已带 null
  三元判断，SpotBugs 对「同一方法调用两次」的保守数据流判定；该类型在 4.9.8 归 **STYLE** 类。
  分类 ①。

口径：`failThreshold=High` 只收窄失败判据。10 条仍原样写入 `target/spotbugsXml.xml`
（实测 `<BugInstance>` 计数 = 10，`priority="2"` ×10）且 `Total bugs: 10` 照常打印在日志中。

## 红绿取证（哨兵验证，实跑原文）

**红**：向既有文件 `leaderboard-service/src/main/java/com/sportverify/leaderboard/service/LeaderboardService.java`
尾部注入一行 165 字符的注释哨兵（`LineLength max=140` 必然违规），复跑 `checkstyle:check`：

```
[red] checkstyle:check rc=1
[ERROR] .../LeaderboardService.java:442: 本行字符数 165个，最多：140个。 [LineLength]
[ERROR] src\main\java\...\LeaderboardService.java:[442] (sizes) LineLength: 本行字符数 165个，最多：140个。
```

违规规则名与位置：**`LineLength` @ `LeaderboardService.java:442`**（原文件 441 行，哨兵作为第 442 行追加在末尾）。

**还原**（禁用 `git stash`）：`git show HEAD:<path>` 取原始字节 + **非 `-p`** 的 `cp` + `touch`：

```
[restore] cmp orig vs restored: IDENTICAL (cmp exit 0)
[restore] sha256=3d1b4e189c670ed8 lines=441        # 与 git 对象 sha256 逐位一致
[restore] git diff --quiet for path: EMPTY (clean)
[restore] sentinel occurrences left: 0
```

**一处行尾细节（如实呈报，不影响结论）**：本仓 `core.autocrlf=true`，而 `git show HEAD:<path>`
输出的是**库里那份 LF 内容**，故 `cp` 之后工作树是 LF，而开工前工作树是 CRLF 形态。此时 git 层面
已**零差异**（`git diff` 与 `git diff HEAD` 均空、blob 与 HEAD 同为 `cd1a3b18`），但 `git status`
会报一个 **stat 幻影 ` M`**（stat 与 index 不符而内容一致，`git update-index --refresh` 亦消解不掉）。
已用 **`git checkout-index -f -- <path>`**（内容与 HEAD 同 blob，非破坏性）把文件归位到 autocrlf 下的
规范检出形态：归位后 **worktree sha256 = `944574080d62db5f...`，与「开工前基线快照」逐位一致**
（该值取自首次取证脚本对未改动文件打印的 `[orig]`），`git status` 随之恢复干净。
取证结论不受影响 —— 哨兵期间与还原之后，git 级差异始终为空。

**绿**：三 goal 复跑：

```
[green] three goals rc=0
[INFO] You have 0 Checkstyle violations.
[INFO] Total bugs: 10
[WARNING] PMD 7.17.0 has issued 2 warnings.
[INFO] BUILD SUCCESS
```

还原后 `touch` 是必须的（`cp -p` 会让 mtime 回退、Maven 增量误判），本次即因此暴露了
`.m2-repo` 旧包问题（见「修复记录」第 2 条）—— 属于还原流程的正常副产品，已修复。

## 三段式验收实跑结论

| 段 | 命令 | rc | 关键输出 |
| --- | --- | --- | --- |
| 1 装料（在线，一次） | `mvn -B -ntp -s ../.mvn-settings.xml test-compile checkstyle:check spotbugs:check pmd:check` | **0** | 15.8 s；0 violations / Total bugs 10 / PMD 2 warnings / BUILD SUCCESS |
| 2 离线复现 | 同上加 `-o` | **0** | 19.7 s；**下载数 0**（`grep -c Downloading` = 0）⇒ 依赖来源可判定 |
| 2b 离线 + clean（附加） | 同上再加 `clean` | **0** | 41.8 s；**从零全量编译**亦绿，证明第 2 段不是靠增量编译蹭过的 |
| 3 全仓回归（唯一入口） | `bash scripts/verify/mvn-verify.sh --mode=offline test` | **0** | BUILD SUCCESS；模块合计 `17 19 31 80 81 50 6` = **284**，Failures 0 / Errors 0 / Skipped 0；生效模式 offline、localRepository `D:/code/sports/.m2-repo`（未触发退出码 3） |

**284 零扰动结论成立**：三插件在 pom 内**无任何 `<executions>`**，不绑 lifecycle phase，
故 `clean test` 既不执行也不解析它们；本轮 284 与基线 `dfa747a` 逐位一致。

关于 spec 原命令（无 `test-compile` 前置）：未单独复跑 —— `spotbugs` / `pmd` 分析的是
`target/classes` 编译产物，冷 `target/` 下无类可析；任务包已注明「goal 前置 `test-compile`」，
故以带前置的命令为准。

## 修复记录（两次实跑失败与本任务内的处置）

1. **PMD 单规则引用被展开成整个 category**：首版 pom 按「默认规则集减去 UnnecessaryImport」
   的思路，把 42 条规则逐条写进 `<rulesets>`（形如 `category/java/codestyle.xml/UnnecessaryImport`），
   实测 **PMD 不认这种「规则集/规则名」单规则引用**，会退化成整个 category：违规数由 2 涨到
   **2056**（`MethodArgumentCouldBeFinal` 640、`LocalVariableCouldBeFinal` 640、`OnlyOneReturn` 200 …）。
   处置：改为显式引用插件内置默认规则集 `rulesets/java/maven-pmd-plugin-default.xml`（与基准同源，
   42 条规则），改用 `failurePriority=3` 做降级。属「最多 1 次修复重试」范围内的必要纠偏。
2. **`.m2-repo` 的 `sport-verify-common` 早于其源码**（旧包 2026-09-12 vs `TraceIds` 2026-09-16）：
   还原后 `touch` 触发全量重编 → `程序包 com.sportverify.common.trace 不存在` → 绿跑一度 rc=1。
   处置：`mvn -o -pl common,api -am install -DskipTests` 刷新本地仓（gitignored），复跑全绿。
   该缺陷**与本次改动无关**，是本仓独立模块构建路径上的既有隐患，如实登记。

## 契约自证

- 收口前脏树：`bash scripts/verify/mailbox-contract.sh --open=TASK-018` → **rc=1**（末行
  `契约校验失败（退出码 1）：判据 A=0 判据 B=1`）。**判据 A 通过** —— TASK-018 两件套已齐全
  （`spec.md` + 本 `handoff.md`），输出里已是「两件套齐全：TASK-018」，`--open` 不再需要声明。
  判据 B 共 **12 个任务**报「在途回传」不一致：`TASK-006 018 106 109 110 111 112 113 114 115 116 117`。
  两类成因，如实拆开（不把自家那一份藏进「历史遗留」里）：
  1. **历史任务共占公共文件**（TASK-006/106/109~117 共 11 个）：这些历史 handoff 的正文含
     `leaderboard-service/pom.xml`、`PLAN.md`、`README.md`、`spec.md` 等路径 token，与本次改动集
     交叠 ⇒ 契约按「回传在途」强校验 ⇒ 报「清单多报」。这与 TASK-106/116/117 台账里已记录的
     「历史任务共占 `pom.xml`/`PLAN.md`/`README.md` 公共文件过冲」是同一现象，与本次改动无关
     （本次未改这些历史文件的既有内容，TASK-018 的记账本身是自洽的）。
  2. **TASK-018 自身，且唯一缺口是 `.editorconfig`**：其余 5 个声明项与实际改动集逐项一致，
     唯一的 `only_actual` 项就是 `leaderboard-service/.editorconfig` —— 因为它不在契约脚本
     路径提取的扩展名白名单内（详见「待主 agent 决定」第 3 条），**写了也提取不出来**。
     这不是清单漏报，是**工具侧的提取缺口**；本任务白名单不含该脚本，无法在此修掉。
     换言之：在没有历史冲突的理想情况下，本任务的在途判据 B 仍会因为这一个文件而红。
- 收口提交后：`bash scripts/verify/mailbox-contract.sh`（**不再带 `--open`**）→ 期望 **rc=0**
  （工作树无迹 ⇒ 所有任务「足迹不在工作树，视为已收口」，不重审）。实测结果见 PLAN.md 台账
  「契约自证」行。
- README 同步：`scripts/verify/README.md` 的「本仓当前进行中任务」一行已改为「无进行中任务」并
  说明收口命令不再需要 `--open`；另补 1 段如实说明 `.editorconfig` 的提取局限（见下）。

## 待主 agent 决定

1. **SpotBugs 高危项：无。** 任务包红线为「CORRECTNESS / MALICIOUS_CODE 类 **High priority**」，
   实测该集合为**空** —— MALICIOUS_CODE 命中 9 条但全为 Medium（`priority=2`），CORRECTNESS 类 0 条。
   故无「不准靠关规则掩盖」的待决高危项。
2. **3 处真实无用 import 仍存在**（技术债，非豁免所能消除）：`InternalLeaderboardController.java:3`
   （`LeaderboardApi`）、`LeaderboardService.java:3`（`RecordVerifyEvents`）、
   `LeaderboardService.java:21`（`EnableCaching`）。本任务白名单无 Java 源改动权，故 checkstyle 的
   `UnusedImports` 与 PMD 的 `UnnecessaryImport` 只能按同一理由豁免/降级。**建议单开一个最小变更**
   （删 3 行 import + 重跑 checkstyle/pmd 观察违规数由 3/2 归零），这是本任务唯一想「修而未能修」的点。
3. **`mailbox-contract.sh` 的路径提取白名单不含 `.editorconfig`**：导致该文件在任何在途窗口都被
   记为「改动集未声明」，与 README 里既有的 `.example` 处置**同源**。本任务白名单不含该脚本，
   故只在 README 内如实登记、未改脚本；建议后续把 `.editorconfig` 加入白名单（1 行改动）。
4. **`.m2-repo` 内部构件陈旧无自愈**：见「修复记录」第 2 条。独立模块构建依赖本地仓的
   `sport-verify-common` 快照，刷新时机无约束；建议后续约定「独立模块构建前先
   `mvn -pl common,api -am install`」或把该风险写进 README 的快速开始。
5. **未 push、未建 PR**（任务硬边界）：本任务改动**待下次 push 由 CI 复验**，此处不作已到达外部
   门槛的声称。CI 当前也**不会**跑这三个 goal（未加入 workflow，属任务包硬边界），
   即静态检查的「外部门槛」目前为空——是否要进 CI 请指导侧定。
6. **注意（一颗「下次 push 必炸」的地雷，非本任务引入）**：本任务跑 CI 同款口径自检时发现，
   本任务 6 个改动文件 **0 命中**，但**全仓扫描非零命中**，落点在
   `work/mailbox/PLAN.md` 与 `work/mailbox/tasks/TASK-106/handoff.md` 各 1 行 ——
   TASK-106 为描述「本机 MSYS 把某汉字的第二字节按 cp1252 判成大小写等价、致既有未触碰文件
   伪命中」这一 locale 伪影，把**禁用词原文引进了台账**，于是这两处自身成了命中项。
   二者均属**已入库但未 push** 的内容（本地领先 `origin/main` 4 提交），因此此前 CI 从未见过它们；
   一旦 push，`.github/workflows/ci.yml` 的公开文档口径自检会直接失败。
   两处都在本任务白名单外（`PLAN.md` 本任务只追加、未动既有行；TASK-106 的 handoff 完全不在白名单），
   故**未修，留指导侧处置**：改写这两处表述、不再原文引用禁用词即可，无需改 CI。
   处置建议：与本次收口分开处理（本任务不做），但**请在下次 push 前先修掉**。
