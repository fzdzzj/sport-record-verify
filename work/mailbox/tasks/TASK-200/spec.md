# 任务书：四服务生产启动缺陷修复 v3（TASK-200 / fix-d3-prod-boot-defects 改派）

本版为 round-3 改派修订版。round-1 完成缺陷A/B 修复与门禁验证（三服务实起 liveness UP）；round-2 完成缺陷C 修复（NotificationService 构造器标注，user 实起实证 bean 创建成功、失败点前移），随即暴露第四个新失败签名——缺陷D：NotificationController 与缺陷C 逐字同构，触发 v2 停止条件 §3.1 零提交停工（工作区恰 10 文件未提交自洽）。指导侧裁决扩权 v3 **普查式根治**：同构模式全仓普查定界（恰 2 文件，双窗口一致，指导侧亲核），一次性清零，不再逐 bean 改派；白名单 10→11 文件（+NotificationController.java），round-1/round-2 改动与读数全部沿用（§1/§8），冲突以本版为准。唯一指令源：本任务书 + spec/changes/fix-d3-prod-boot-defects/。全程 bash 经 `D:\git\Git\bin\bash.exe`；命令行参数禁携中文；唯一验证入口 scripts/verify/mvn-verify.sh。

## §0 红线（违反即 FAILED）

1. **零触碰面**：ci.yml、mvn-verify.sh、mailbox-contract.sh、docker-compose 全部文件、gateway-service、mapmatch-service、web、sql、ADR、api、common、.trivyignore、仓库根 .env、record 测试夹具 sharding.yaml——需触碰即停止申报，不得擅自扩权。
2. **Maven 唯一入口**：一切构建/验证经 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh`；手工 mvn 禁止（dependency:get 补仓除外；round-1 补仓已就位，无需重跑）。
3. **round-1/round-2 改动不可回退**：工作区既有 10 文件未提交改动原样沿用为 C-01 改动面（round-2 user 实起已实证缺陷C 生效）；禁 stash、禁 checkout 回摆、禁重做。探针文件不再触碰。
4. **缺陷D 修复边界**：NotificationController.java 仅允许构造器级 @Autowired 标注一处（+1 行，import 既有）；**禁删显式构造器**（NotificationControllerTest:55 存在唯一 2 参手工 new 调用方，删构造器即破坏测试）；@RequiredArgsConstructor、字段级注解、方法体、javadoc 业务语义零改动；需超出即停止申报。
5. **不推送不建 PR**；CI 红禁重试刷绿，按签名归因。
6. **token 29 项只增不减**（round-1 开工实测基线 2030，round-2 开工复测 2030 持平）；新文档禁枚举受保护字面量、禁出现词面门正则形态。
7. **台账纯追加**（tasks.json 勾选翻转除外）；行尾规范：新文件 blob 级纯 LF + 末尾换行，修改文件保持既有行尾；`git diff --check` 干净。
8. **命令行禁携中文**：提交信息经 .trae/tmp msg 文件 + git commit -F；MSYS sed -i 禁用于 tracked 文件。

## §1 侦察实证（研究阶段 + round-1/round-2 已完成，round-3 不重跑，异常才复验）

### §1.1 缺陷A（user 拦截器）——round-1 已修复反转实证

- `StompConnectAuthInterceptor`（user-service/config）实现 ChannelInterceptor，全仓 grep 无 @Component/@Bean 注册；`WebSocketNotificationConfig` 构造注入该类型——上下文启动即 NoSuchBeanDefinition（TASK-185 引入遗留）。
- `JwtUtil`（user-service/auth/util）已 @Component，依赖链自洽；config 包在 UserApplication 组件扫描面内。
- UserProbeTest 现以 `@Import` 临时供 bean（TASK-198 批3 accommodation）。修复后必须移除：@Component 扫描注册 + @Import 注册 = 同类型双 bean 定义，WebSocketNotificationConfig 按类型注入即歧义失败。
- **round-1 反转实证**：@Component 已加、@Import 已移除；user 生产 boot 越过 WS 装配期（旧 NoSuchBeanDefinition 拦截器签名消失）。

### §1.2 缺陷B（四服务 fastjson 类缺失）——round-1 已修复反转实证

- **完整失败栈（leaderboard 生产 jar 实起实证，2026-10-11）**：`NoClassDefFoundError: com/alibaba/fastjson/TypeReference` ← `MQClientAPIImpl.<init>(MQClientAPIImpl.java:253)` ← `MQClientInstance.<init>` ← `DefaultMQPushConsumer.start` ← `LeaderboardEventConsumer.init`（@PostConstruct，catch Exception 不捕获 Error）。
- **fastjson 引用面（jar 解包实证）**：rocketmq-remoting 5.1.4 `NettyRemotingClient$1`（匿名 TypeReference 子类，NettyRemotingClient 构造期硬解析——失败必炸点）；rocketmq-client 5.1.4 `MQClientAPIImpl` / `MQClientInstance` / `SendResult`；rocketmq-common 5.1.4 `TopicConfig` 系列 / `FastJsonSerializer` / `GenericMapSuperclassDeserializer`；rocketmq-spring-boot 2.3.1 `RocketMQMessageConverter`（FASTJSON_PRESENT 守卫，不致错）。
- **四服务触达链**：user（编程式 NotificationEventConsumer/LikeEventConsumer @PostConstruct）；record（@Scheduled flush 链 → 模板 producer.start）；verify（@Scheduled relay 链 → 模板）；leaderboard（LeaderboardEventConsumer.init 编程式 consumer）。gateway/mapmatch 无 rocketmq 依赖，不在缺陷面。
- **版本研究**：rocketmq-client 5.4.0（2025-12-24）才以 fastjson2 全量替换 fastjson；升 client 路线需父 pom 覆盖 SCA BOM 管理值且跨三大版本，爆炸半径大，列备选（§2.6）。
- **Trivy 历史口径**：TASK-196 收缩态后零豁免；回补 2.0.65 不得再触发 CRITICAL（CI 实测为唯一权威口径，预判依据：高于 2.0.63 安全修复线、当前 stable）。
- **round-1 反转实证**：record/verify/leaderboard 三服务同轮生产实起 liveness 全 UP；user 崩溃点在 bean 创建序上先于其 MQ 消费者（pom 修复已就位，缺陷C/D 修复后自然生效）。

### §1.3 缺陷C（user NotificationService 双构造器）——round-2 已修复反转实证

- **round-1 失败签名链**：friendController → friendService → notificationService → `Failed to instantiate [NotificationService]: No default constructor found`（`NoSuchMethodException <init>()`）。
- **根因（字节码 javap 双证）**：@RequiredArgsConstructor 生成 1 参构造（final notificationMapper）+ TASK-187 兼容期显式 2 参构造（mapper + preferenceService）；@Autowired 仅字段级（required=false），无构造器级标注——Spring 多构造器无标注回退无参构造，该类无无参构造 → 启动即炸。
- **潜伏机理**：单测手工 new 不经 Spring；探针 lazy-initialization=true 业务 bean 全懒加载永不创建；生产 boot 此前死在更早 WS 装配期（被缺陷A 掩蔽）。
- **round-2 修复与反转实证**：显式 2 参构造加构造器级 @Autowired（恰 +1 行）后，user 实起失败点从 notificationService **前移**到 notificationController 自身（NotificationController 构造入参 notificationService 解析成功方能进入实例化——证明 NotificationService bean 已成功创建）；NotificationServiceTest 12/0/0、NotificationServicePushTest 12/0/0 零回归。

### §1.4 缺陷D（user NotificationController 同构双构造器，round-2 冒烟新暴露）+ 普查实证

- **round-2 失败签名链**：`BeanCreationException: Error creating bean 'notificationController'` ← `BeanInstantiationException: Failed to instantiate [NotificationController]: No default constructor found` ← `NoSuchMethodException: NotificationController.<init>()`。
- **根因（指导侧亲核源码）**：与缺陷C 逐字同构——`@RequiredArgsConstructor`（:44）生成 1 参构造（final notificationService）+ TASK-187 兼容期显式 2 参构造（:53-57，notificationService + notificationPreferenceService）；@Autowired 仅字段级（:50-51，required=false），无构造器级标注 → Spring 回退无参构造，该类无无参构造 → 启动即炸。
- **普查实证（指导侧亲核，round-3 定界依据）**：全仓 `@RequiredArgsConstructor` + 显式构造器同构模式**恰 2 文件**（NotificationService + NotificationController），4000/8000 字符双窗口扫描结果一致；record/verify/leaderboard/gateway/mapmatch 零命中（与三服务 round-1 实起全绿自洽）。同构缺陷类经本轮修复后全仓清零。
- **修复参数可解析性（指导侧亲核）**：NotificationService（@Service，round-2 实证创建成功）+ NotificationPreferenceService（@Service）均在上下文；调用方核查——`NotificationControllerTest:55` 为唯一 2 参手工 new 调用方（加注解不改构造器签名，零破坏）；NotificationServiceTest / NotificationServicePushTest / NotificationConsumerRoundTripIT 手工 new 单参走 Lombok 1 参构造（round-2 实证零破坏）。

## §2 预注册设计（v3）

### §2.1 缺陷A：拦截器注册（round-1 已完成）

`StompConnectAuthInterceptor` 加 `@Component` + 对应 import（最小 diff）；javadoc 补一行注册说明。验收口径：user 生产 jar 实起成功。

### §2.2 缺陷B：fastjson2 v1 兼容包受控回补（round-1 已完成）

- 父 pom：properties 加 `fastjson2-compat.version=2.0.65`；dependencyManagement 在 rocketmq-spring-boot-starter 条目邻位加 `com.alibaba:fastjson` 条目。
- 四模块 pom：rocketmq 依赖邻位加 `com.alibaba:fastjson`，scope=runtime。
- 版本偏差登记：TASK-196 预注册兜底 2.0.53，本课题升格 2.0.65（高于 2.0.63 安全修复线、当前 stable），handoff §偏差 明示。
- 已知无害效应登记：RocketMQMessageConverter FASTJSON_PRESENT 守卫转真，Jackson 先位首匹配，业务 JSON 行为零漂移。

### §2.3 缺陷C/D：双构造器构造器注入修复（round-2 已完成 C；round-3 唯一新增 D）

- **缺陷C（round-2 已完成）**：NotificationService 显式 2 参构造加构造器级 `@Autowired`（+1 行，import 既有）——已实证生效，改动沿用。
- **缺陷D（round-3 唯一新增）**：NotificationController 显式 2 参构造加构造器级 `@Autowired`（恰 +1 行；`org.springframework.beans.factory.annotation.Autowired` import 已在文件头，零新增）。机制：Spring 唯一标注构造器被择定，2 参（NotificationService + NotificationPreferenceService）均可解析（§1.4 亲核）；字段级 `@Autowired(required=false)` 在构造后注入，语义不变。允许项：2 参构造既有 javadoc 行内补一句注入说明。**禁删显式构造器**（§0.4）。
- 验收口径：user 生产 jar 实起越过 notificationController 创建点。

### §2.4 探针掩蔽清理（round-1 已定案）

- UserProbeTest：@Import 已移除、javadoc 偏差段已修订。Verify/Record 探针：模板 mock 已删除（六探针全绿定案保持）；Redisson mock 保留；断言零改动。round-3 不再触碰任何探针文件。

### §2.5 冒烟验收（D3 复现路径反转，round-3 基于最终 11 文件态全量重跑）

`mvn-verify.sh --mode=online package` 构建 → `D:\develop1\jdk21\bin\java.exe -jar` 逐服务实起（本机中间件在线实况：MySQL 3307 / Nacos 8848 / namesrv 9876 + broker healthy；Redis 6379 本机原生）→ `/actuator/health/liveness` 必须 200 + UP。四服务逐一留证（server.port 错峰避免占用冲突）；round-1/round-2 读数为过程证据，收口以最终态重跑读数为准；临时日志存 .trae/tmp/ 用后即删。user 实起须越过 notificationController 创建点（缺陷D 验收）。

### §2.6 备选决策树（仅停止条件触发时回报，不自行改道）

- Trivy 判 2.0.65 CRITICAL：a) 升 2.0.66+（若 Central 已发布）；b) .trivyignore 豁免登记（治理回退，需用户裁决）；c) 改走 rocketmq-client ≥5.4.0 升级路线（需覆盖 SCA 管理值 + 兼容性验证课题化）。
- 恢复 fastjson 1.2.83：否决项（违反 TASK-196 终态治理）。

## §3 停止条件（v3，触发即停手零提交回报）

1. 缺陷D 修复后 user 生产实起暴露**第五个**新失败签名（非 NotificationService、非 NotificationController、非 D3-A/B 已修签名）→ 停手归因。同构缺陷类已全仓清零（§1.4 普查实证），下一个签名必然为新型缺陷——不可预判，仍只有生产实起能暴露。
2. offline 609 基线回退 / 静态检查违规净增 → 停手。
3. 需触碰 §0.1 零触碰面或 §4 白名单外文件（含自行登记 .trivyignore）→ 停手。
4. NotificationController 构造器改动致 user 单测回归（NotificationControllerTest 等）且无法以 §2.3 边界内改动修复 → 停手。
5. CI（第 41 次）Trivy 对 com.alibaba:fastjson:2.0.65 报 CRITICAL → 停手走 §2.6 决策树。
6. round-3 任何环节需回滚 round-1/round-2 十文件改动 → 停手（裁决前提失效，重新裁决）。

## §4 白名单（只改清单 v3）

C-01 实施笔恰 11 文件（round-1 九文件 + round-2 一文件沿用 + round-3 一文件新增）：

1. `user-service/src/main/java/com/sportverify/user/config/StompConnectAuthInterceptor.java`
2. `user-service/src/main/java/com/sportverify/user/service/NotificationService.java`（仅 §2.3 构造器标注一处，round-2 已完成）
3. `user-service/src/main/java/com/sportverify/user/controller/NotificationController.java`（仅 §2.3 构造器标注一处，round-3 新增）
4. `pom.xml`（父）
5. `user-service/pom.xml`
6. `record-service/pom.xml`
7. `verify-service/pom.xml`
8. `leaderboard-service/pom.xml`
9. `user-service/src/test/java/com/sportverify/user/health/UserProbeTest.java`
10. `verify-service/src/test/java/com/sportverify/verify/health/VerifyProbeTest.java`
11. `record-service/src/test/java/com/sportverify/record/health/RecordProbeTest.java`

C-02 台账笔恰 4 文件：`spec/changes/fix-d3-prod-boot-defects/tasks.json`（勾选翻转）、本任务书 §7 回填、`work/mailbox/tasks/TASK-200/handoff.md`（新建，含只改清单小节，须含 round-1/round-2 停止留证与 round-3 修复全留证）、`work/mailbox/PLAN.md`（纯追加）。

提交主题建议（执行侧可按实际微调 scope，格式 `type(scope): description (TASK-200)`）：C-01 `fix(boot): 修复D3四缺陷恢复四服务生产实起（TASK-200）`；C-02 `docs(mailbox): 登记TASK-200生产启动缺陷修复验收与台账闭环（TASK-200）`。

## §5 受保护 token

29 项 tracked `git grep -cF` 权威口径（禁 --untracked 口径），round-1 开工实测基线 SUM=2030，round-2 开工复测 2030 持平；round-3 收口复测只增不减；新文档禁枚举字面量。

## §6 门禁结构（收口全量留证，基于最终 11 文件态）

| 门禁 | 口径 |
| --- | --- |
| offline | `--mode=offline test` rc=0，609 逐位（36/48/130/151/159/70/15；NotificationControllerTest 在 user 130 内） |
| static | `--static=record-service` 与 `--static=leaderboard-service` 双模块 rc=0 |
| 契约门 | `mailbox-contract.sh` 双态（--open TASK-200 --baseline=<v3 改派笔哈希> / 收口态）rc=0 |
| token | 29 项 SUM ≥ 2030 |
| 词面门 | CI word-check 全绿（新文档不引入触发形态） |
| 六探针 | CI 等效无中间件属性覆盖全绿（round-1 配方逐档复跑；禁停共享中间件容器） |
| 冒烟 | 四服务生产 jar 实起 liveness UP（§2.5，最终态全量重跑） |
| 外部 CI | 推送后第 41 次：6 镜像构建 + Trivy 收缩态 + 六探针实跑全绿 |

round-1/round-2 中间读数为过程证据，不替代收口读数；收口全量门禁在 C-01 提交前基于最终 11 文件工作区态实测。

## §7 收口记录（执行侧 C-02 纯追加回填）

**round-3 收口（C-01 `fd1e53b` / C-02 本笔）**：

- **缺陷D 修复与反转实证**：NotificationController 显式 2 参构造（:53-57 后）加构造器级 `@Autowired`（恰 +1 行，import 既有零新增）；`@RequiredArgsConstructor`/字段级 `@Autowired(required=false)`/方法体/javadoc 业务语义零改动，未删显式构造器（NotificationControllerTest:55 唯一 2 参手工 new 调用方零破坏）。user 生产 jar 实起越过 notificationController 创建点（无 `Error creating bean 'notificationController'`、无 `NoSuchMethodException`），`Started UserApplication` 后 liveness 200 + `{"status":"UP"}`——同构缺陷类全仓清零。
- **四服务最终态冒烟（11 文件态）**：user `HTTP 200 {"status":"UP"}`（Started 实起成功）；record `HTTP 200 UP`（38s）；verify `HTTP 200 UP`（28s）；leaderboard `HTTP 200 UP`（29s）。本机中间件容器（sport-verify-mysql/nacos/namesrv/broker）曾代理退出，round-3 起始时恢复并留证，未停共享中间件容器。
- **全量门禁（最终 11 文件态）**：offline 609 逐位 `36/48/130/151/159/70/15` 全绿 rc=0（NotificationControllerTest 在 user 130 内零回归）；static record-service rc=0 + leaderboard-service rc=0（首次并行因 .m2-repo 父 pom install 竞争 rc=1，串行复跑 clean rc=0）；六探针 CI 等效全绿（三探针各 3/0/0/0 + GatewayProbeTest 4/0/0/0，offline 内实证）；契约门双态；token 29 项 SUM=2030 只增不减；词面门 ZERO_HIT；`git diff --check` 干净。
- **提交**：C-01 `fd1e53b3eadaf4ecfa189d9cccb356fe6ab7d6d7`（恰 11 文件，+78 −33）；C-02 本笔（恰 4 文件）。v3 改派笔 `e9d8898`、v2 改派笔 `5c45511`。
- **外部终验（补记笔，2026-10-11）**：第 41 次外部门槛 CI（run `38138765615`，head=ee891ab，2026-10-11T12:18:31Z 触发）conclusion=success 全步骤绿——Build and test（offline 609 + 六探针实跑全绿）、Build all service images (6/6)、**Trivy 收缩态扫描 success（com.alibaba:fastjson:2.0.65 未触发 CRITICAL，零豁免下全绿）**、static gate（checkstyle/spotbugs/pmd）、词面门、web job；授权后 6 笔推送 `bed6dca..ee891ab`（TASK-200 五笔 + TASK-199 补记笔 1b20923 随批次），推送后 origin/main...main = 0 0；终裁依据：指导侧亲核（缺陷C/D diff 定向抽查恰各 +1 行）+ 独立复核五块全量复跑 PASS 零阻断 + 两报告一致性零矛盾 + 本轮 CI success。**TASK-200 封盘**。

## §8 改派记录（round-1/round-2 停止归档）

- **round-1**（派发笔 `13e9ece`）：完成缺陷A/B 全部修复与门禁验证（补仓、offline 609 逐位、六探针 CI 等效六档全绿且模板 mock 删除保持、静态双模块、三服务生产实起 liveness UP、user 越过 WS 装配期、token 开工 2030、恰 9 文件自洽）；user 冒烟暴露缺陷C（NotificationService 双构造器），触发 v1 停止条件 §3.1/§3.5 零提交停工。v2 改派笔 `5c45511`：裁决选项 a 变体（不回退、不另立课题），扩权白名单 9→10 文件。
- **round-2**：完成缺陷C 修复（NotificationService.java 恰 +1 行构造器标注）；user 实起实证 notificationService bean 创建成功（失败点前移）；NotificationServiceTest/NotificationServicePushTest 零回归；token 开工复测 2030 持平；`--mode=online package` rc=0 四 jar 重建；恰 10 文件自洽零提交。user 实起随即暴露缺陷D（NotificationController 同构双构造器，`BeanCreationException: notificationController → No default constructor found`），触发 v2 停止条件 §3.1 零提交停手。临时件（r2-smoke-user.log / r2-online-package.log / smoke-body.json）已用后即删。
- **本 v3 由指导侧裁定改派（普查式根治）**：不回退、不另立课题，扩权白名单 10→11 文件。依据：同构模式全仓普查恰 2 文件（双窗口一致，指导侧亲核），NotificationService 已修、NotificationController 待修，本轮清零同构缺陷类；D3 使命「四服务生产实起全绿」未竟，拆课题致交叉依赖与提交序混乱；round-1/round-2 十文件改动集自洽（缺陷C 修复已实证生效），回退浪费已验工作。缺陷D 归因、修复参数可解析性、2 参手工调用方（NotificationControllerTest:55）、普查边界均经指导侧亲核。round-1/round-2 全部读数作为 v3 开工依据沿用；v3 改派笔提交主题 `docs(spec): 改派TASK-200纳入NotificationController同构构造修复（TASK-200）`，哈希 `e9d8898`（round-3 C-02 回填）。
