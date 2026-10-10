# TASK-194 align-record-static-gate 任务书（record-service 静态门口径对齐与 Checkstyle 存量清零）

## 0. 红线（违任一条即 FAILED 停手回报）

1. **零语义源码改动**：Java 源码改动仅限三类——package-info.java 新文件（包级 Javadoc，零类定义）、Javadoc 文本追加（@param 标签）、Javadoc 注释块位置挪移（文本零改动）。**零逻辑、零签名、零行为变化**；FinalParameters/HiddenField 等豁免类不做任何源码修复；既有 final 修饰符（如 RecordLikeService:88）不回退
2. **透明豁免纪律**：checkstyle.xml 每条豁免/放宽就地注明分类（①误报 ②框架生成 ③风格冲突 ④工具重叠）+ 理由 + record 实测计数；**禁用 suppressions 文件级静默屏蔽**；spotbugs CORRECTNESS/MALICIOUS_CODE High priority 问题**一律不得豁免**——首跑暴露即停手汇报
3. **豁免面预注册**：豁免与放宽以本任务书 §2.2 清单为准（JavadocStyle 195/JavadocMethod 175/FinalParameters 156/JavadocVariable 39/HiddenField 25/DesignForExtension 22/MissingJavadocMethod 18/MagicNumber 16/OperatorWrap 12 豁免③；HideUtilityClassConstructor 1 豁免①；LineLength max=140 与 ParameterNumber max=8 放宽；JavadocPackage 8 源码修复）。**不得新增清单外豁免**；如需新增（引擎锁定后计数漂移或首跑暴露未知类）先停手汇报
4. 零触碰清单：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose*（含 services/perf）/ web / sql/ / 父 pom / gateway-service / user-service / verify-service / leaderboard-service（含其 checkstyle.xml 与 pom）/ mapmatch-service / common / api / 仓库根 .env / record-service 测试源码（src/test 零改动）/ record-service application 配置（application.properties / sharding.yaml 零改动）
5. record pom 改动**仅限 build/plugins 新增静态检查插件段**（maven-checkstyle-plugin 3.6.0 必加；spotbugs/pmd 配置段仅在首跑暴露违规时按 leaderboard 口径补）：dependencies 零改动、不引入新运行时依赖、不碰 spring-boot-maven-plugin 既有段
6. offline 基线 **569（36/44/127/137/149/64/12）逐位不变**（零语义改动的硬门，任何一位漂移即停手）；静态门终态 **rc=0**
7. 性能/质量/维护性收益数字零 claim；词面门正则字面量与受保护 token 字面量不入任何 tracked 新文档（checkstyle.xml 中文注释与 package-info Javadoc 均需过词面门）
8. 新文件（checkstyle.xml / package-info ×8 / handoff.md）纯 LF + 末尾换行；修改文件（pom / Java）保持既有行尾；`git diff --check` 干净
9. 停止条件：锁 3.6.0 引擎后基准计数与 811 差异 >5% / spotbugs/pmd 首跑暴露 High 或 CORRECTNESS/MALICIOUS_CODE 类 / 任一违规需改逻辑或签名才能消除 / 需触碰任一零触碰面
10. 沿用两级门禁纪律：唯一验证入口 scripts/verify/mvn-verify.sh；不推送不建 PR（push 由指导侧授权）

## 1. 背景与侦察实证（指导侧亲核，2026-10-10）

- **811 构成**（static 日志逐条统计）：JavadocStyle 195 / JavadocMethod 175 / FinalParameters 156 / LineLength 138 / JavadocVariable 39 / HiddenField 25 / DesignForExtension 22 / MissingJavadocMethod 18 / MagicNumber 16 / OperatorWrap 12 / JavadocPackage 8 / JavadocType 4 / HideUtilityClassConstructor 1 / ParameterNumber 1 / InvalidJavadocPosition 1 = 811，分布于 26 文件（top：RecordLikeService 162 / SportRecordService 115 / TimingHikariDataSource 97 / SubmitTxTiming 66）
- **LineLength 全消条件**：138 处 >80 中最长 121、**0 处 >140**——放宽 max=140（leaderboard 同款）即全消
- **零星修复点定位**：JavadocType 4 = RecordLikeService.java:174 PendingOp record（private record PendingOp(Long recordId, Long userId, Action action, long enqueuedAt)）缺 @param×4；InvalidJavadocPosition 1 = RecordEventProducer.java:33；ParameterNumber 1 = RecordLikeService.java:84 构造器 8 依赖注入（SportRecordMapper/RecordLikeMapper/StringRedisTemplate/RedissonClient/ObjectMapper/PlatformTransactionManager/MeterRegistry/LikeEventProducer）
- **HideUtilityClassConstructor 1** = RecordApplication.java:19（Spring Boot 启动类，leaderboard 同款①类误报）
- **record pom 现状**：无 checkstyle/spotbugs/pmd 任何声明（811 为 mvn-verify.sh 命令行直调引擎实测）；mvn-verify.sh 第 2 段三件套顺序执行，checkstyle 失败即中断——**spotbugs/pmd 从未首跑**
- **迁移模板**：leaderboard-service/pom.xml:157-172（checkstyle 插件配置全形态）与 leaderboard-service/src/main/resources/checkstyle.xml（260 行派生范本，头部含 TASK-018 豁免框架）
- **行尾基线**：record pom 与 Java 源码以 git ls-files --eol 实测为准（修改文件保持既有行尾）
- **基线**：开工 HEAD=2d6d29e 之后最新（TASK-193 补记笔 524b418 为本地未推送 0 1，沿先例随本课题批次推送）；offline 569；static 811 rc=1；token 29 项 TASK-193 收口真值 2030 参照

## 2. 预注册实施设计

### 2.1 record-service/pom.xml（插件段）

build/plugins 内（spring-boot-maven-plugin 之后）新增，配置形态逐项对标 leaderboard pom:157-172：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-checkstyle-plugin</artifactId>
    <version>3.6.0</version>
    <configuration>
        <configLocation>src/main/resources/checkstyle.xml</configLocation>
        <inputEncoding>UTF-8</inputEncoding>
        <includeTestSourceDirectory>false</includeTestSourceDirectory>
        <consoleOutput>true</consoleOutput>
        <logViolationsToConsole>true</logViolationsToConsole>
        <logViolationCountToConsole>true</logViolationCountToConsole>
    </configuration>
</plugin>
```

注释说明与 leaderboard 同版本同引擎口径（3.6.0 内置 checkstyle 9.3）。spotbugs/pmd 配置段不预置——仅首跑暴露 Medium 以下违规时按 leaderboard pom 同口径补（spotbugs failThreshold=High + pmd failurePriority=3 + printFailingErrors + includeTests false，豁免逐条记账）；首跑干净则不补。

### 2.2 checkstyle.xml（新建，从 leaderboard 版派生）

结构 = leaderboard checkstyle.xml 骨架（Checker / BeforeExecutionExclusionFileFilter / SuppressionFilter 空操作入口 / NewlineAtEndOfFile / Translation / FileLength / LineLength / FileTabCharacter / RegexpSingleline 尾空白 / TreeWalker 全保留闸门），差异仅：

- 头部注释块改写为 record 口径：811 基准构成（15 类计数表）+ 四类豁免定义 + 逐条豁免理由 + 两处放宽理由 + 硬边界（CORRECTNESS/MALICIOUS_CODE High 不得豁免）+ 派生来源声明（leaderboard TASK-018 版 + 差异点：JavadocPackage 源码修复不豁免、ParameterNumber 放宽 max=8 为 record 特有）
- LineLength max=140（理由：record 实测最长 121 零超标全消，同 leaderboard 取值）
- TreeWalker 内注释掉豁免规则：JavadocMethod / JavadocVariable / JavadocStyle / MissingJavadocMethod / OperatorWrap / HiddenField / MagicNumber / DesignForExtension / FinalParameters / HideUtilityClassConstructor（豁免理由逐条改写为 record 实测计数，措辞沿 leaderboard 同源）
- ParameterNumber 保留并配置 `<property name="max" value="8"/>`（leaderboard 无此 property，record 特有：构造器注入 8 依赖 Spring 惯用法，第 9 参仍拦截）
- **保留闸门（不豁免）**：JavadocType / InvalidJavadocPosition / 全部命名约定 / import 卫生 / MethodLength / ParameterNumber / 空白六件 / ModifierOrder / RedundantModifier / 块五件 / EmptyStatement / EqualsHashCode / IllegalInstantiation / InnerAssignment / MissingSwitchDefault / MultipleVariableDeclarations / SimplifyBoolean×2 / FinalClass / InterfaceIsType / VisibilityModifier / ArrayTypeStyle / TodoComment / UpperEll / UnusedImports（record 实测 0 违规，保留为闸门——与 leaderboard 豁免不同，因 record 无存量命中）

### 2.3 package-info.java（新建 8 个，以锁定引擎后实测 JavadocPackage 命中清单为准）

预期命中包：com.sportverify.record（根）/ record.config / record.config.shardingsphere / record.controller / record.entity / record.mapper / record.mq / record.service。每个文件形态：

```java
/**
 * 一句话包职责中文说明（如实描述，2-4 行以内）。
 */
package com.sportverify.record.xxx;
```

零类定义、零 import、零副作用。

### 2.4 零星源码修复（2 文件）

- RecordLikeService.java:174：PendingOp Javadoc 补四行 `@param recordId 记录 id`、`@param userId 用户 id`、`@param action 点赞动作（LIKE/UNLIKE）`、`@param enqueuedAt 入队时间戳（供队头年龄度量）`——措辞可微调但语义如实；其余行零改动
- RecordEventProducer.java:33：Javadoc 注释块整体挪到所属声明正前方（注释文本零改动）；其余行零改动

### 2.5 spotbugs/pmd 首跑探测与处置

C-01 全部落地后跑 `--static=record-service`（此时 checkstyle 已清零，三件套完整首跑）：

- 首跑干净（spotbugs/pmd 0 违规）→ static rc=0 直接达成，登记读数
- 暴露 Medium 以下 → 按 §2.1 补 record pom 两插件配置段（leaderboard 同口径），豁免逐条记入 handoff（位置+类型+分类理由）
- 暴露 High / CORRECTNESS / MALICIOUS_CODE 类 → **停手回报**（红线 2），不豁免不修复（新课题定夺）

## 4. 白名单（只改清单与主题串）

- 派发笔：`spec/changes/align-record-static-gate/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + `work/mailbox/tasks/TASK-194/spec.md`（四件套零改动入库），主题：`docs(spec): 派发 TASK-194 record 静态门口径对齐提案与任务书`
- C-01（约 12 文件，以实测 JavadocPackage 命中数微调）：`record-service/pom.xml`、`record-service/src/main/resources/checkstyle.xml`（新）、`record-service/src/main/java/com/sportverify/record/**/package-info.java`（8 个新）、`record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java`、`record-service/src/main/java/com/sportverify/record/mq/RecordEventProducer.java`（+ 若 §2.5 处置需补 pom 两段则 pom 一笔内完成），主题：`build(record): 派生静态检查规则集与 Checkstyle 存量清零（TASK-194）`
- C-02（恰 4 文件）：`spec/changes/align-record-static-gate/tasks.json`、`work/mailbox/tasks/TASK-194/spec.md`（§7 纯追加）、`work/mailbox/tasks/TASK-194/handoff.md`（新建）、`work/mailbox/PLAN.md`（纯追加），主题：`docs(mailbox): 登记 TASK-194 静态门口径对齐验收与台账闭环（TASK-194）`

## 5. 受保护 token（29 项，口径 repo 全量 git grep -cF）

开工实测 SUM 为准（TASK-193 收口真值 2030 参照）；收口读数以收口态实测为准。只增不减；新文档不枚举 token 字面量。

## 6. 门禁结构

1. **预提交门禁（每笔前亲跑记 rc）**：tasks.json 语法（python -m json.tool）rc=0；词面门四形态 ZERO_HIT rc=1 + 探针三态；git diff --check rc=0；契约门在途 `--open TASK-194 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新文件 LF + 末尾换行、修改文件保持既有行尾
2. **收口门禁（C-02 后亲跑留证）**：`--static=record-service` **rc=0**（从 811 rc=1 升级为全绿新基线，三件套读数逐项登记）；`--mode=offline test` 569（36/44/127/137/149/64/12）逐位不变；零越界核验（`git diff --name-only <派发笔>..HEAD` 仅白名单文件、src/test 零改动、sharding.yaml 与 application 零改动）；typed-router 零漂移；契约门无参 rc=0；PLAN.md 自派发笔起纯追加
3. **handoff.md**：开工规程核验 / 811 基准构成与引擎锁定前后计数对比 / 豁免清单逐条理由 / 13 处源码修复 diff 证据 / spotbugs/pmd 首跑读数与处置 / 逐门实测表 / token 前后读数 / 偏差登记 / 提交表（显式哈希，禁时效指针）
4. **推送后**：第 35 次外部门槛 CI 绿为外部终验；红则按签名归因，禁重试刷绿

## 7. 收口记录（执行侧 C-02 纯追加）

### 7.1 提交记录

- 派发笔：`f141d22768df57504f1b65a3fe0c94e807b66b09`（`f141d22`） `docs(spec): 派发 TASK-194 record 静态门口径对齐提案与任务书`
- C-01 实施笔：`41bf76d64a516c11c63540399ee7e5e75b1f9fe6`（`41bf76d`） `build(record): 派生静态检查规则集与 Checkstyle 存量清零（TASK-194）`
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff §8 给出）` `docs(mailbox): 登记 TASK-194 静态门口径对齐验收与台账闭环（TASK-194）`

### 7.2 实施读数（C-01 实施态实测回填）

- checkstyle 清零读数：锁定 3.6.0 后基准 811 项；豁免面构成 10 类共 659 项（JavadocStyle 195 / JavadocMethod 175 / FinalParameters 156 / JavadocVariable 39 / HiddenField 25 / DesignForExtension 22 / MissingJavadocMethod 18 / MagicNumber 16 / OperatorWrap 12 豁免③；HideUtilityClassConstructor 1 豁免①）；放宽 2 类共 139 项（LineLength max=140 全消 138 项、ParameterNumber max=8 覆盖 1 项）；源码修复 3 类共 13 项（JavadocPackage 8 项真建 package-info、RecordLikeService PendingOp @param×4、RecordEventProducer Javadoc 挪位）；规则集接入后实测 Checkstyle 0 违规，存量彻底清零
- spotbugs 首跑：首跑共 18 项违规，全部为 Medium priority（17 项 MALICIOUS_CODE + 1 项 STYLE），0 High priority，0 CORRECTNESS；未触发红线 2。按 §2.1/§2.5 及 leaderboard pom 同口径在 record pom 配置 spotbugs-maven-plugin（failThreshold=High、includeTests=false），全量 Medium 原样保留并逐条透明记账
- pmd 首跑：按 §2.1/§2.5 及 leaderboard pom 同口径在 record pom 配置 maven-pmd-plugin 3.28.0（rulesets 默认规则集、includeTests=false、failurePriority=3、printFailingErrors=true），实测 0 违规，target/pmd.xml 为空
- package-info 清单：实测命中并新建 8 个包（com.sportverify.record 根包、record.config、record.config.shardingsphere、record.controller、record.entity、record.mapper、record.mq、record.service），各 3-5 行包级中文 Javadoc，零类定义零副作用

### 7.3 门禁读数（收口态实测回填）

- `--static=record-service`：rc=0；三件套读数：Checkstyle 0 违规、SpotBugs 18 Medium（failThreshold=High 门禁通过）、PMD 0 违规；静态门由长期 rc=1 升级为 rc=0 全绿新基线
- offline 全量逐位：`36/44/127/137/149/64/12` = **569**（各模块逐位不变），Failures/Errors/Skipped 全 0，BUILD SUCCESS
- 契约门在途/无参、词面门四形态、token 29 项、只改清单全等、行尾核验：契约门在途 `--open TASK-194 --baseline=f141d22` rc=0、无参 rc=0；词面门改动集与全量四形态（default / C / zh_CN.UTF-8 / C.UTF-8）全 ZERO_HIT rc=1 + 探针三态 HIT rc=0（PROBE_GONE=yes）；token 29 项开工 2030 / C-01 2030 / C-02 2030 只增不减；只改清单 C-01 恰 12 文件、C-02 恰 4 文件，白名单全等；新文件 pure LF + 末尾换行，修改文件保持既有行尾
