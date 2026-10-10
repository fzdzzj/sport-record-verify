# TASK-194 align-record-static-gate 提案：record-service 静态门口径对齐与 Checkstyle 存量清零

## Why（现状与痛点）

record-service 的 `--static=record-service` 门长期 rc=1（Checkstyle **811 项存量违规**，26 文件），所有任务书只能登记「811 持平 rc=1 基线违规模块预期」。而 static 门缺省模块 leaderboard-service 早已在 TASK-018 治理为 rc=0 全绿（374 项 → sun_checks 派生规则集 + 透明豁免）。两模块静态门口径割裂：record 用 sun_checks 默认规则（无任何配置），leaderboard 用有据裁剪的 checkstyle.xml。

现状实证（指导侧亲核，2026-10-10，static 日志逐条统计）：

- **811 项 / 15 类规则 / 26 文件**构成：JavadocStyle 195、JavadocMethod 175、FinalParameters 156、LineLength 138、JavadocVariable 39、HiddenField 25、DesignForExtension 22、MissingJavadocMethod 18、MagicNumber 16、OperatorWrap 12、JavadocPackage 8、JavadocType 4、HideUtilityClassConstructor 1、ParameterNumber 1、InvalidJavadocPosition 1
- **LineLength 138 处全部 ≤121 字符，0 处超 140**——leaderboard 同款放宽（max=140）后该类全消，零源码改动
- 零星源码修复面仅 **13 处**（全部零语义）：JavadocPackage 8（record-service 有源码权限，真建 package-info.java 而非豁免——leaderboard 豁免理由「无改动权」在本课题不成立）、JavadocType 4（RecordLikeService:174 PendingOp record 补 @param×4）、InvalidJavadocPosition 1（RecordEventProducer:33 Javadoc 挪到声明正前方）
- **关键未知**：mvn-verify.sh static 第 2 段三件套顺序执行，checkstyle 失败即中断——**record 的 spotbugs/pmd 从未真正跑过**（record pom 无任何三件套插件声明，当前 811 由命令行直调的引擎实测）。清零 checkstyle 后 spotbugs/pmd 首跑，结果未知
- 811 中 798 项（811−13）与 leaderboard 当年 374 项构成同源：JavadocStyle/JavadocMethod/FinalParameters/LineLength 为前四大，豁免理由可直接迁移（本仓中文 Javadoc 风格、不写 final 参数约定、构造器 this.x=x 风格、MyBatis 载体类）

## What（方案，用户已裁决路线 B：沿 TASK-018 先例派生规则集 + 零星源码修复）

**sun_checks 派生 record checkstyle.xml（透明豁免 + 两处有据放宽）+ pom 锁 maven-checkstyle-plugin 3.6.0 + 13 处零语义源码修复 + spotbugs/pmd 首跑探测与处置**，目标终态：`--static=record-service` **rc=0 全绿新基线**，与 leaderboard 口径统一。

| 决策点 | 裁决 | 理由 |
| --- | --- | --- |
| 治理路线 | 派生规则集 + 零星源码修复（非源码全修 811） | 用户裁决沿 TASK-018 先例；798 项属本仓既有风格与 sun 英文约定的系统性冲突（leaderboard 豁免理由明载），源码硬修产出「为过门禁而写」的代码且 26 文件大 diff 回归负担重 |
| 豁免分类 | 四类框架迁移（①误报 ②框架生成 ③风格冲突 ④工具重叠），每条豁免在 checkstyle.xml 内就地注明理由 + record 实测计数 | 与 leaderboard checkstyle.xml 同源同格式，全仓静态门口径统一 |
| LineLength | 放宽 max=140（非豁免） | record 实测最长 121、0 处超 140——全消且仍拦真正失控超长行；与 leaderboard 完全一致 |
| ParameterNumber | 放宽 max=8（非豁免） | 唯一命中 RecordLikeService:84 构造器注入 8 依赖（Spring 惯用法）；放宽比豁免保守，第 9 参仍拦截 |
| JavadocPackage 8 | **真建 package-info.java**（源码修复） | 本课题有 Java 源码权限，leaderboard 豁免理由（白名单无源码权）不成立；8 个小文件产出真实包级文档 |
| HideUtilityClassConstructor 1 | 豁免①误报 | RecordApplication:19 Spring Boot 启动类，与 leaderboard RecordApplication 同款误报 |
| 其余 10 类豁免 | JavadocStyle 195 / JavadocMethod 175 / FinalParameters 156 / JavadocVariable 39 / HiddenField 25 / DesignForExtension 22 / MissingJavadocMethod 18 / MagicNumber 16 / OperatorWrap 12（豁免③，理由与 leaderboard 逐条同源） | 本仓既有约定（中文 Javadoc 首句不以句号收尾、public 方法不全覆盖 @param/@return、不写 final 参数、构造器 this.x=x、MyBatis 载体不因扩展加 Javadoc、业务常量字面量、Mapper SQL 拼接 + 收行尾） |
| 引擎版本 | record pom 显式锁 maven-checkstyle-plugin 3.6.0（内置 checkstyle 9.3） | 与 leaderboard 同版本同引擎（「基准计数同引擎」TASK-018 原则）；当前 811 为命令行直调引擎实测，锁版本后计数可能有微差，执行侧以锁定后重跑为准（差异大则停手汇报） |
| spotbugs/pmd 首跑 | 清零 checkstyle 后三件套完整首跑；若暴露违规：Medium 以下按 leaderboard pom 口径透明豁免（spotbugs failThreshold=High / pmd failurePriority=3，逐条记账）；**High/CORRECTNESS 类暴露则停手汇报**（新课题，不得静默豁免） | static 门安全价值主体在 spotbugs/pmd（TASK-018 红线：CORRECTNESS/MALICIOUS_CODE High 不得豁免）；首跑结果是本课题最大未知项 |

**改动面（C-01 约 12 文件 + C-02 4 文件）**：

| 层 | 改动 |
| --- | --- |
| 构建配置 | record-service/pom.xml：build/plugins 新增 maven-checkstyle-plugin 3.6.0（configLocation=src/main/resources/checkstyle.xml、inputEncoding UTF-8、includeTestSourceDirectory=false、console 三开）；若 spotbugs/pmd 首跑暴露违规则按 leaderboard 口径补两插件配置段 |
| 规则集（新） | record-service/src/main/resources/checkstyle.xml：从 leaderboard 版派生，头部注释含 record 811 基准构成与逐条豁免理由 |
| 源码（零语义） | 8 个 package-info.java 新建（com.sportverify.record 根 + config/config.shardingsphere/controller/entity/mapper/mq/service 子包，以实测 JavadocPackage 命中为准）+ RecordLikeService.java PendingOp 补 @param×4 + RecordEventProducer.java:33 Javadoc 挪位 |
| 台账（C-02） | tasks.json 全勾 + 任务书 §7 回填 + handoff.md 新建 + PLAN.md 纯追加 |

## 边界（明确不做）

- 零触碰：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose* / web / sql/ / 父 pom / gateway / user / verify / leaderboard（含其 checkstyle.xml 与 pom）/ mapmatch / common / api / 仓库根 .env / record-service 测试源码与 application 配置
- Java 源码改动**仅限三类零语义**：package-info 新文件、Javadoc 文本追加（@param 标签）、Javadoc 注释块位置挪移——**零逻辑、零签名、零行为变化**；FinalParameters/HiddenField 等豁免类**不做任何源码修复**（:88 已有的 final 修饰符不回退）
- 不改 sharding.yaml / 索引 / 状态机 / 批次 / 周期 / MQ 参数；不加新依赖（checkstyle 插件为 build 插件非运行时依赖）
- 不 claim 任何质量/维护性收益数字；spotbugs CORRECTNESS/MALICIOUS_CODE High 类问题一律不得豁免

## 风险

| 风险 | 缓解 |
| --- | --- |
| spotbugs/pmd 首跑暴露大量存量违规，rc=0 目标不可达 | 停止条件预注册：High/CORRECTNESS 类暴露 → 停手汇报定新课题；Medium 以下 → 按 leaderboard 口径透明豁免记账（leaderboard spotbugs 基准 10 条全 Medium 已有先例） |
| 锁 3.6.0 引擎后 811 计数漂移 | 执行侧以锁定后重跑基准为准登记（差异 >5% 停手汇报） |
| checkstyle.xml 豁免面被质疑「藏问题」 | 透明豁免纪律：每条就地注明分类+理由+实测计数；无 suppressions 文件；规则集随 jar 打包可对外审计（leaderboard 同款口径） |
| 8 个 package-info 影响编译/打包 | 纯包级 Javadoc 声明文件，无类定义无副作用；offline 569 逐位不变为硬门 |
| 两模块规则集未来漂移 | 本课题产出的 record checkstyle.xml 头部注明派生自 leaderboard TASK-018 版与差异点，后续变更走课题化 |

## 验收（摘要）

`--static=record-service` rc=0（三件套全绿新基线，含 spotbugs/pmd 首跑结果登记）；offline 569（36/44/127/137/149/64/12）逐位不变；改动面 C-01 约 12 文件（pom 1 + checkstyle.xml 1 + package-info 8 + Java 修复 2）+ C-02 恰 4 文件；token 29 项 2030 只增不减；词面门 ZERO_HIT；契约门 rc=0；CI 第 35 次外部门槛绿。
