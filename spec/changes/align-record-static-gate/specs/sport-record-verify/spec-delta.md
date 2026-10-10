# spec-delta：record-service 静态门口径对齐与 Checkstyle 存量清零（TASK-194 align-record-static-gate）

## ADDED 需求：record-service 静态检查规则集（sun_checks 派生 + 透明豁免）

record-service 此前无任何静态检查配置（sun_checks 默认规则，811 项存量违规，static 门长期 rc=1）。本变更引入 src/main/resources/checkstyle.xml：从 leaderboard-service TASK-018 版派生，保留闸门规则逐条同源；豁免 11 类（JavadocStyle/JavadocMethod/FinalParameters/JavadocVariable/HiddenField/DesignForExtension/MissingJavadocMethod/MagicNumber/OperatorWrap 豁免③本仓风格冲突；HideUtilityClassConstructor 豁免①启动类误报；JavadocPackage 由源码新建 package-info 消除），放宽 2 处（LineLength max=140、ParameterNumber max=8）。每条豁免/放宽在配置内就地注明分类、理由与实测计数；禁用 suppressions 文件级静默屏蔽。

## ADDED 需求：静态门插件版本与口径锁定

record-service pom 显式声明 maven-checkstyle-plugin 3.6.0（checkstyle 9.3 引擎，与 leaderboard 同版本同引擎同配置形态：configLocation 指向 src/main/resources/checkstyle.xml、UTF-8、只查主源码、console 输出三开）。spotbugs/pmd 首跑后若暴露 Medium 以下违规，按 leaderboard pom 同口径补透明豁免配置（spotbugs failThreshold=High / pmd failurePriority=3），逐条记账。

## MODIFIED 需求：静态门终态基线

`--static=record-service` 从「rc=1 基线违规模块预期（811 持平）」升级为 **rc=0 三件套全绿**；后续所有任务书的 record 静态门基线口径按此更新。硬边界不变：spotbugs CORRECTNESS/MALICIOUS_CODE High priority 问题不得豁免。

## MODIFIED 需求：record 主源码包级文档

record-service 各包新增 package-info.java（8 个，包职责一句话中文 Javadoc）——JavadocPackage 违规以源码修复消除（leaderboard 因无源码权豁免，本课题有源码权限，选真修复）。

## 验收断言

- `--static=record-service` rc=0（checkstyle 0 违规 + spotbugs/pmd 首跑结果登记，Medium 以下透明豁免或 0）
- offline 569（36/44/127/137/149/64/12）逐位不变（源码改动限零语义三类：package-info 新文件 / Javadoc @param 追加 / Javadoc 位置挪移）
- 只改清单：C-01 约 12 文件（pom 1 + checkstyle.xml 1 新 + package-info 8 新 + RecordLikeService/RecordEventProducer 修复 2）；C-02 恰 4 文件（tasks.json / 任务书 spec.md §7 / handoff.md / PLAN.md）
- 零触碰：ci.yml / mvn-verify.sh / mailbox-contract.sh / docker-compose* / web / sql/ / 父 pom / 五个其它服务模块 / common / api / record 测试源码与 application 配置
- 台账不含质量/维护性收益数字 claim；豁免面全部可审计（配置内就地注明）
