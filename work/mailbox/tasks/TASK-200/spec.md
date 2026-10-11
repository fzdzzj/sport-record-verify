# 任务书：四服务生产启动缺陷修复 v2（TASK-200 / fix-d3-prod-boot-defects 改派）

本版为 round-2 改派修订版。round-1 执行侧完成 D3 缺陷A/缺陷B 全部修复与门禁验证（record 36.3s / verify 18.5s / leaderboard 16.0s 生产实起 liveness UP，user 越过 WS 装配期；offline 609 逐位、六探针 CI 等效、静态双模块、补仓全绿），user 冒烟暴露被缺陷A 掩蔽的第三个既有隐藏 bean 缺陷（缺陷C：NotificationService 双构造器无构造器级注入标注），触发停止条件 §3.1/§3.5 零提交停工（工作区恰 9 文件未提交自洽）。指导侧裁决改派 v2 扩权：不回退、不另立课题，白名单 9→10 文件（+NotificationService.java），round-1 改动与读数全部沿用（§1/§8），冲突以本版为准。唯一指令源：本任务书 + spec/changes/fix-d3-prod-boot-defects/。全程 bash 经 `D:\git\Git\bin\bash.exe`；命令行参数禁携中文；唯一验证入口 scripts/verify/mvn-verify.sh。

## §0 红线（违反即 FAILED）

1. **零触碰面**：ci.yml、mvn-verify.sh、mailbox-contract.sh、docker-compose 全部文件、gateway-service、mapmatch-service、web、sql、ADR、api、common、.trivyignore、仓库根 .env、record 测试夹具 sharding.yaml——需触碰即停止申报，不得擅自扩权。
2. **Maven 唯一入口**：一切构建/验证经 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh`；手工 mvn 禁止（dependency:get 补仓除外；round-1 补仓已就位，2.0.65 jar+pom 与传递 fastjson2 core 双落仓，无需重跑）。
3. **round-1 改动不可回退**：工作区既有 9 文件未提交改动原样沿用为 C-01 改动面（round-1 全门禁绿基于该状态）；禁 stash、禁 checkout 回摆、禁重做。探针断言语义零改动口径延续（round-1 已完成，round-2 不再触碰探针文件）。
4. **缺陷C 修复边界**：NotificationService.java 仅允许构造器级 @Autowired 标注一处（+1 行，import 既有）；@RequiredArgsConstructor、字段级注解、方法体、javadoc 业务语义零改动；需超出即停止申报。
5. **不推送不建 PR**；CI 红禁重试刷绿，按签名归因。
6. **token 29 项只增不减**（round-1 开工实测基线 2030）；新文档禁枚举受保护字面量、禁出现词面门正则形态。
7. **台账纯追加**（tasks.json 勾选翻转除外）；行尾规范：新文件 blob 级纯 LF + 末尾换行，修改文件保持既有行尾；`git diff --check` 干净。
8. **命令行禁携中文**：提交信息经 .trae/tmp msg 文件 + git commit -F；MSYS sed -i 禁用于 tracked 文件。

## §1 侦察实证（研究阶段 + round-1 已完成，round-2 不重跑，异常才复验）

### §1.1 缺陷A（user 拦截器）——round-1 已修复反转实证

- `StompConnectAuthInterceptor`（user-service/config）实现 ChannelInterceptor，全仓 grep 无 @Component/@Bean 注册；`WebSocketNotificationConfig` 构造注入该类型——上下文启动即 NoSuchBeanDefinition（TASK-185 引入遗留）。
- `JwtUtil`（user-service/auth/util）已 @Component，依赖链自洽；config 包在 UserApplication 组件扫描面内。
- UserProbeTest 现以 `@Import` 临时供 bean（TASK-198 批3 accommodation）。修复后必须移除：@Component 扫描注册 + @Import 注册 = 同类型双 bean 定义，WebSocketNotificationConfig 按类型注入即歧义失败。
- **round-1 反转实证**：@Component 已加、@Import 已移除；user 生产 boot 越过 WS 装配期（旧 NoSuchBeanDefinition 拦截器签名消失）。

### §1.2 缺陷B（四服务 fastjson 类缺失）——round-1 已修复反转实证

- **完整失败栈（leaderboard 生产 jar 实起实证，2026-10-11）**：`NoClassDefFoundError: com/alibaba/fastjson/TypeReference` ← `MQClientAPIImpl.<init>(MQClientAPIImpl.java:253)` ← `MQClientInstance.<init>` ← `DefaultMQPushConsumer.start` ← `LeaderboardEventConsumer.init`（@PostConstruct，catch Exception 不捕获 Error）。
- **fastjson 引用面（jar 解包实证）**：rocketmq-remoting 5.1.4 `NettyRemotingClient$1`（匿名 TypeReference 子类，NettyRemotingClient 构造期硬解析——失败必炸点）；rocketmq-client 5.1.4 `MQClientAPIImpl` / `MQClientInstance` / `SendResult`（JSON 静态调用）；rocketmq-common 5.1.4 `TopicConfig` / `TopicConfig$1` / `FastJsonSerializer` / `GenericMapSuperclassDeserializer`；rocketmq-spring-boot 2.3.1 `RocketMQMessageConverter`（FASTJSON_PRESENT 守卫，isPresent 捕获 CNFE，不致错）。
- **四服务触达链**：user（编程式 NotificationEventConsumer/LikeEventConsumer @PostConstruct；无 producer.group，自动配置模板无 producer 不炸）；record（@Scheduled flush 链 → LikeEventProducer → 模板 producer.start）；verify（@Scheduled relay 链 → VerifyEventProducer → 模板）；leaderboard（LeaderboardEventConsumer.init 编程式 consumer）。gateway/mapmatch 无 rocketmq 依赖（TASK-196 后镜像无 fastjson 字节），不在缺陷面。
- **版本研究**：rocketmq-client 5.4.0（2025-12-24）才以 fastjson2 全量替换 fastjson（Apache 发行注记 PR 9397/9927）；5.5.1（2026-08-20）注记 fastjson2 2.0.63 含安全修复。rocketmq-spring 2.3.1 parent 自管理 client 5.3.0，被 SCA BOM 2023.0.1.0 管理值 5.1.4 覆盖（消费方 dependencyManagement 优先）——升 client 路线需父 pom 覆盖 SCA 管理值且跨三大版本，爆炸半径大，列备选（§2.6）。
- **Trivy 历史口径**：TASK-196 前的 .trivyignore 存量登记含 fastjson 1.2.83（CVE-2026-16723，影响 user/record/verify/leaderboard 四镜像）；TASK-196 收缩态后零豁免。回补 2.0.65 不得再触发 CRITICAL（CI 实测为唯一权威口径，预判依据：高于 2.0.63 安全修复线、fastjson2 官方当前 stable）。
- **round-1 反转实证**：record/verify/leaderboard 三服务同轮生产实起 liveness 全 UP（leaderboard 正是完整 D3-B 失败栈实证档，其 Started 成功即反转）；user 崩溃点在 bean 创建序上先于其 MQ 消费者，fastjson 链未及触达（pom 修复已就位，缺陷C 修复后自然生效）。

### §1.3 缺陷C（user NotificationService 双构造器，round-1 冒烟新暴露）

- **失败签名链**：friendController → friendService → notificationService → `Failed to instantiate [NotificationService]: No default constructor found`（`NoSuchMethodException <init>()`）。
- **根因（字节码 javap 双证）**：`NotificationService`（user-service/service，TASK-182/187 引入）有且仅有两个构造器——@RequiredArgsConstructor 生成的 1 参构造（final 字段 notificationMapper）+ TASK-187 兼容期显式 2 参构造（mapper + preferenceService）；@Autowired 仅标注在两个字段上（均 required=false），无任何构造器级标注。Spring 多构造器无标注时回退无参构造，该类无无参构造 → 启动即炸。
- **潜伏机理**：单测手工 new 构造不经过 Spring；探针测试 lazy-initialization=true 使业务 bean 全懒加载永不创建；生产 boot 此前死在更早的 WS 装配期（被缺陷A 掩蔽）——此类缺陷只有生产实起能暴露（v1 §3.1 预判场景命中）。
- **隔离证据**：三服务同轮实起全 UP——缺陷C 仅 user 独有。
- **修复参数可解析性（指导侧亲核）**：NotificationMapper（MyBatis mapper bean）与 NotificationPreferenceService（@Service）均在上下文；`NotificationServiceTest` / `NotificationServicePushTest` / `NotificationConsumerRoundTripIT` 三处手工 `new NotificationService(mapper)` 单参调用走 Lombok 1 参构造，构造器加标注零破坏。

## §2 预注册设计（v2）

### §2.1 缺陷A：拦截器注册（round-1 已完成）

`StompConnectAuthInterceptor` 加 `@Component` + 对应 import（最小 diff，不新建工厂方法）；javadoc 补一行注册说明。验收口径：user 生产 jar 实起成功（WS 装配链在 web 基础设施急切装配期完成，启动即证明）。

### §2.2 缺陷B：fastjson2 v1 兼容包受控回补（round-1 已完成）

- 父 pom：properties 加 `fastjson2-compat.version=2.0.65`（注释：D3 缺陷B / TASK-196 exclusions 并存关系 / 2.0.63 安全修复线依据）；dependencyManagement 在 rocketmq-spring-boot-starter 条目邻位加 `com.alibaba:fastjson` 条目。
- 四模块 pom（user/record/verify/leaderboard）：rocketmq 依赖邻位加 `com.alibaba:fastjson`，scope=runtime（业务代码 0 引用维持，编译期不可见）。
- 版本偏差登记：TASK-196 spec §2.3 预注册兜底为 2.0.53，本课题升格 2.0.65（高于 2.0.63 安全修复线、当前 stable；2.0.53 有被判红返工风险），handoff §偏差 明示。
- 已知无害效应登记：RocketMQMessageConverter 的 FASTJSON_PRESENT 守卫转真，复合转换器列表尾部追加 MappingFastJsonMessageConverter——Jackson 在列表先位且首匹配命中，业务 JSON（统一 Jackson）行为零漂移。

### §2.3 缺陷C：NotificationService 构造器注入修复（round-2 唯一新增改动）

显式 2 参构造加构造器级 `@Autowired`（+1 行；`org.springframework.beans.factory.annotation.Autowired` import 已在文件头，零新增 import）。机制：Spring 构造器解析规则——唯一 @Autowired 标注构造器被择定，2 参（NotificationMapper + NotificationPreferenceService）均可解析（§1.3 亲核）；字段级 `@Autowired(required=false)`（notificationPushRelay 等）在构造完成后注入，语义不变。允许项：2 参构造既有 javadoc 行内补一句注入说明。验收口径：user 生产 jar 实起越过 notificationService 创建点。

### §2.4 探针掩蔽清理（round-1 已定案）

- UserProbeTest：@Import 已移除、javadoc 偏差段已修订（Redisson 段与断言口径段不动）。
- VerifyProbeTest / RecordProbeTest：模板 mock 已删除（六探针 CI 等效全绿 → 保持删除，恢复探针对模板 bean 创建期的真实覆盖即 D3 漏网防线）；Redisson mock 保留；断言零改动。round-2 不再触碰任何探针文件。

### §2.5 冒烟验收（D3 复现路径反转，round-2 基于最终 10 文件态全量重跑）

`mvn-verify.sh --mode=online package` 构建 → `D:\develop1\jdk21\bin\java.exe -jar` 逐服务实起（本机中间件在线实况：MySQL 3307 / Nacos 8848 / namesrv 9876 + broker healthy；Redis 6379 本机原生）→ `/actuator/health/liveness` 必须 200 + UP。四服务逐一留证（server.port 错峰避免占用冲突）；round-1 三服务读数为过程证据，收口以最终态重跑读数为准；临时日志存 .trae/tmp/ 用后即删。user 实起须越过 notificationService 创建点（缺陷C 验收）。

### §2.6 备选决策树（仅停止条件触发时回报，不自行改道）

- Trivy 判 2.0.65 CRITICAL：a) 升 2.0.66+（若 Central 已发布）；b) .trivyignore 豁免登记（治理回退，需用户裁决）；c) 改走 rocketmq-client ≥5.4.0 升级路线（需覆盖 SCA 管理值 + 兼容性验证课题化）。
- 恢复 fastjson 1.2.83：否决项（违反 TASK-196 终态治理）。

## §3 停止条件（v2，触发即停手回报）

1. 缺陷C 修复后 user 生产实起暴露**第四个**新失败签名（非 NotificationService、非 D3-A/B 已修签名）→ 停手归因（探针懒加载探不到业务 bean，此类缺陷只有生产实起能暴露，不可预判）。
2. offline 609 基线回退 / 静态检查违规净增 → 停手。
3. 需触碰 §0.1 零触碰面或 §4 白名单外文件（含自行登记 .trivyignore）→ 停手。
4. NotificationService 构造器改动致 user 单测回归（NotificationServiceTest / NotificationServicePushTest / NotificationConsumerRoundTripIT）且无法以 §2.3 边界内改动修复 → 停手。
5. CI（第 41 次）Trivy 对 com.alibaba:fastjson:2.0.65 报 CRITICAL → 停手走 §2.6 决策树。
6. round-2 任何环节需回滚 round-1 九文件改动 → 停手（裁决前提失效，重新裁决）。

## §4 白名单（只改清单 v2）

C-01 实施笔恰 10 文件（round-1 九文件沿用 + 第 10 号新增）：

1. `user-service/src/main/java/com/sportverify/user/config/StompConnectAuthInterceptor.java`
2. `user-service/src/main/java/com/sportverify/user/service/NotificationService.java`（仅 §2.3 构造器标注一处）
3. `pom.xml`（父）
4. `user-service/pom.xml`
5. `record-service/pom.xml`
6. `verify-service/pom.xml`
7. `leaderboard-service/pom.xml`
8. `user-service/src/test/java/com/sportverify/user/health/UserProbeTest.java`
9. `verify-service/src/test/java/com/sportverify/verify/health/VerifyProbeTest.java`
10. `record-service/src/test/java/com/sportverify/record/health/RecordProbeTest.java`

C-02 台账笔恰 4 文件：`spec/changes/fix-d3-prod-boot-defects/tasks.json`（勾选翻转）、本任务书 §7 回填、`work/mailbox/tasks/TASK-200/handoff.md`（新建，含只改清单小节，须含 round-1 停止留证与 round-2 修复全留证）、`work/mailbox/PLAN.md`（纯追加）。

提交主题建议（执行侧可按实际微调 scope，格式 `type(scope): description (TASK-200)`）：C-01 `fix(boot): 修复D3三缺陷恢复四服务生产实起（TASK-200）`；C-02 `docs(mailbox): 登记TASK-200生产启动缺陷修复验收与台账闭环（TASK-200）`。

## §5 受保护 token

29 项 tracked `git grep -cF` 权威口径（禁 --untracked 口径），round-1 开工实测基线 SUM=2030（v1 任务书 §5 与任务书正文枚举均不涉字面量，沿用）；round-2 收口复测只增不减；新文档禁枚举字面量。

## §6 门禁结构（收口全量留证，基于最终 10 文件态）

| 门禁 | 口径 |
| --- | --- |
| offline | `--mode=offline test` rc=0，609 逐位（36/48/130/151/159/70/15） |
| static | `--static=record-service` 与 `--static=leaderboard-service` 双模块 rc=0 |
| 契约门 | `mailbox-contract.sh` 双态（--open TASK-200 --baseline=<改派笔哈希> / 收口态）rc=0 |
| token | 29 项 SUM ≥ 2030 |
| 词面门 | CI word-check 全绿（新文档不引入触发形态） |
| 六探针 | CI 等效无中间件属性覆盖全绿（round-1 配方逐档复跑；禁停共享中间件容器） |
| 冒烟 | 四服务生产 jar 实起 liveness UP（§2.5，最终态全量重跑） |
| 外部 CI | 推送后第 41 次：6 镜像构建 + Trivy 收缩态 + 六探针实跑全绿 |

round-1 中间读数（补仓、offline 609、六探针、静态双模块）为过程证据，不替代收口读数；收口全量门禁在 C-01 提交前基于最终 10 文件工作区态实测。

## §7 收口记录（执行侧 C-02 纯追加回填）

（开工前为空；回填须含：round-1 停止留证沿用、round-2 缺陷C 修复与 user 实起反转、四服务最终态冒烟读数、全量门禁读数、提交哈希）

## §8 改派记录（round-1 停止归档）

- round-1 派发笔：`13e9ece`。round-1 执行侧于开工基线核验后完成缺陷A/缺陷B 全部修复与门禁验证：补仓（dependency:get 2.0.65 BUILD SUCCESS，jar+pom 与传递 fastjson2 core 双落仓）、offline 609 逐位全等、六探针 CI 等效六档全绿（verify/record 模板 mock 删除后仍绿，按 §2.4 决策规则保持删除）、静态双模块 rc=0、`--mode=online package` rc=0 四 jar 落位、record/verify/leaderboard 生产实起 liveness 200+UP、user 越过 WS 装配期（D3-A 反转实证）、token 开工 SUM=2030、改动面恰白名单 9 文件、diff --check 干净、中间件容器全程未停未触。
- user 冒烟失败签名链：friendController → friendService → notificationService → `Failed to instantiate [NotificationService]: No default constructor found`——v1 §3.1 预判场景命中（@Component 注册后生产实起暴露被掩蔽的其它隐藏 bean 缺陷），叠加 v1 §3.5（D3 之外新失败签名），零提交停工（tasks.json 未打勾、台账未动），工作区保持 9 文件未提交自洽态。
- 本 v2 由指导侧裁定改派（裁决 = round-1 报告选项 a 变体）：不回退、不另立课题，扩权白名单 9→10 文件。理由：D3 使命「四服务生产实起全绿」未竟，拆课题致两任务交叉依赖与提交序混乱；TASK-199 改派 v2 先例完备；round-1 九文件改动集自洽且全门禁绿，回退浪费已验工作。缺陷C 归因与修复参数可解析性经指导侧亲核（NotificationService.java 源码 + 三处单参调用方 grep + NotificationPreferenceService @Service 确认）。round-1 全部读数作为 v2 开工依据沿用；改派笔提交主题 `docs(spec): 改派TASK-200扩权NotificationService构造注入修复（TASK-200）`（哈希由 C-02 回填）。
