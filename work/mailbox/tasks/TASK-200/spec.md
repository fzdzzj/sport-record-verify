# 任务书：四服务生产启动缺陷修复（TASK-200 / fix-d3-prod-boot-defects）

D3 = TASK-198 §9-D3 登记的四服务生产启动缺陷（user 拦截器无 bean 注册；user/record/verify/leaderboard fastjson 类缺失）。本课题研究阶段已完成全部归因实证（§1），执行侧照预注册设计（§2）实施，异常才复验。

## §0 红线（违反即 FAILED）

1. **零触碰面**：ci.yml、mvn-verify.sh、mailbox-contract.sh、docker-compose 全部文件、gateway-service、mapmatch-service、web、sql、ADR、api、common、.trivyignore、仓库根 .env、record 测试夹具 sharding.yaml——需触碰即停止申报，不得擅自扩权。
2. **Maven 唯一入口**：一切构建/验证经 `D:\git\Git\bin\bash.exe scripts/verify/mvn-verify.sh`；手工 mvn 禁止（dependency:get 补仓除外，照 TASK-198 批2 先例带 -s .mvn-settings.xml）。
3. **探针断言语义零改动**：liveness UP / readiness 端点与组结构断言、禁断言 db-redis UP 口径逐字保持；只动 accommodation（@Import / 模板 mock）与 javadoc。
4. **mock 清算边界**：Redisson mock（TASK-199）一律保留；RocketMQTemplate mock 按 §2.4 评估移除，回退合法；不新增任何 mock。
5. **不推送不建 PR**；CI 红禁重试刷绿，按签名归因。
6. **token 29 项只增不减**（开工实测基线 2030）；新文档禁枚举受保护字面量、禁出现词面门正则形态。
7. **台账纯追加**（tasks.json 勾选翻转除外）；行尾规范：新文件 blob 级纯 LF + 末尾换行，修改文件保持既有行尾；`git diff --check` 干净。
8. **命令行禁携中文**：提交信息经 .trae/tmp msg 文件 + git commit -F；MSYS sed -i 禁用于 tracked 文件。

## §1 侦察实证（研究阶段已完成，开工不必重跑，异常才复验）

### §1.1 缺陷A（user 拦截器）

- `StompConnectAuthInterceptor`（user-service/config）实现 ChannelInterceptor，全仓 grep 无 @Component/@Bean 注册；`WebSocketNotificationConfig` 构造注入该类型——上下文启动即 NoSuchBeanDefinition（TASK-185 引入遗留）。
- `JwtUtil`（user-service/auth/util）已 @Component，依赖链自洽；config 包在 UserApplication 组件扫描面内。
- UserProbeTest 现以 `@Import` 临时供 bean（TASK-198 批3 accommodation）。修复后必须移除：@Component 扫描注册 + @Import 注册 = 同类型双 bean 定义，WebSocketNotificationConfig 按类型注入即歧义失败。

### §1.2 缺陷B（四服务 fastjson 类缺失）

- **完整失败栈（leaderboard 生产 jar 实起实证，2026-10-11）**：`NoClassDefFoundError: com/alibaba/fastjson/TypeReference` ← `MQClientAPIImpl.<init>(MQClientAPIImpl.java:253)` ← `MQClientInstance.<init>` ← `DefaultMQPushConsumer.start` ← `LeaderboardEventConsumer.init`（@PostConstruct，catch Exception 不捕获 Error）。
- **fastjson 引用面（jar 解包实证）**：rocketmq-remoting 5.1.4 `NettyRemotingClient$1`（匿名 TypeReference 子类，NettyRemotingClient 构造期硬解析——失败必炸点）；rocketmq-client 5.1.4 `MQClientAPIImpl` / `MQClientInstance` / `SendResult`（JSON 静态调用）；rocketmq-common 5.1.4 `TopicConfig` / `TopicConfig$1` / `FastJsonSerializer` / `GenericMapSuperclassDeserializer`；rocketmq-spring-boot 2.3.1 `RocketMQMessageConverter`（FASTJSON_PRESENT 守卫，isPresent 捕获 CNFE，不致错）。
- **四服务触达链**：user（编程式 NotificationEventConsumer/LikeEventConsumer @PostConstruct；无 producer.group，自动配置模板无 producer 不炸）；record（@Scheduled flush 链 → LikeEventProducer → 模板 producer.start）；verify（@Scheduled relay 链 → VerifyEventProducer → 模板）；leaderboard（LeaderboardEventConsumer.init 编程式 consumer）。gateway/mapmatch 无 rocketmq 依赖（TASK-196 后镜像无 fastjson 字节），不在缺陷面。
- **版本研究**：rocketmq-client 5.4.0（2025-12-24）才以 fastjson2 全量替换 fastjson（Apache 发行注记 PR 9397/9927）；5.5.1（2026-08-20）注记 fastjson2 2.0.63 含安全修复。rocketmq-spring 2.3.1 parent 自管理 client 5.3.0，被 SCA BOM 2023.0.1.0 管理值 5.1.4 覆盖（消费方 dependencyManagement 优先）——升 client 路线需父 pom 覆盖 SCA 管理值且跨三大版本，爆炸半径大，列备选（§2.6）。
- **Trivy 历史口径**：TASK-196 前的 .trivyignore 存量登记含 fastjson 1.2.83（CVE-2026-16723，影响 user/record/verify/leaderboard 四镜像）；TASK-196 收缩态后零豁免。回补 2.0.65 不得再触发 CRITICAL（CI 实测为唯一权威口径，预判依据：高于 2.0.63 安全修复线、fastjson2 官方当前 stable）。

## §2 预注册设计

### §2.1 缺陷A：拦截器注册

`StompConnectAuthInterceptor` 加 `@Component` + 对应 import（最小 diff，不新建工厂方法）；javadoc 补一行注册说明。验收口径：user 生产 jar 实起成功（WS 装配链在 web 基础设施急切装配期完成，启动即证明）。

### §2.2 缺陷B：fastjson2 v1 兼容包受控回补（主路径）

- 补仓：`mvn dependency:get -Dartifact=com.alibaba:fastjson:2.0.65 -s .mvn-settings.xml`（在线一次，传递 fastjson2 core 2.0.65 随取；失败即停止条件 §3.7）。
- 父 pom：properties 加 `fastjson2-compat.version=2.0.65`（注释：D3 缺陷B / TASK-196 exclusions 并存关系 / 2.0.63 安全修复线依据）；dependencyManagement 在 rocketmq-spring-boot-starter 条目邻位加 `com.alibaba:fastjson` 条目。
- 四模块 pom（user/record/verify/leaderboard）：rocketmq 依赖邻位加 `com.alibaba:fastjson`，scope=runtime（业务代码 0 引用维持，编译期不可见）。
- 版本偏差登记：TASK-196 spec §2.3 预注册兜底为 2.0.53，本课题升格 2.0.65（高于 2.0.63 安全修复线、当前 stable；2.0.53 有被判红返工风险），handoff §偏差 明示。
- 已知无害效应登记：RocketMQMessageConverter 的 FASTJSON_PRESENT 守卫转真，复合转换器列表尾部追加 MappingFastJsonMessageConverter——Jackson 在列表先位且首匹配命中，业务 JSON（统一 Jackson）行为零漂移。

### §2.3 探针掩蔽清理（UserProbeTest）

移除 `@Import(com.sportverify.user.config.StompConnectAuthInterceptor.class)` 行与 import；javadoc 偏差登记段改为缺陷A 已修复叙事（保留 TASK-199 Redisson 段与断言口径段不动）。

### §2.4 探针掩蔽清理（Verify/Record 模板 mock 评估）

- VerifyProbeTest：ProbeTemplateConfig 仅删 RocketMQTemplate mock bean 与 import（Redisson mock bean 保留，类保留）；javadoc 偏差段修订。
- RecordProbeTest：ProbeStubConfig 仅删模板 mock bean 与相关 javadoc 行（DataSource/SqlSessionFactory/事务/Redisson stub 全保留）；javadoc 偏差段修订。
- 决策规则：删后六探针 CI 等效全绿 → 保持删除（恢复探针对模板 bean 创建期的真实覆盖，即 D3 漏网防线）；红 → 恢复 mock + 偏差登记（不停工）。断言零改动。

### §2.5 冒烟验收（D3 复现路径反转）

`mvn-verify.sh --mode=online package` 构建 → `D:\develop1\jdk21\bin\java.exe -jar` 逐服务实起（本机中间件在线实况：MySQL 3307 / Nacos 8848 / namesrv 9876 + broker healthy；Redis 6379 本机原生）→ `/actuator/health/liveness` 必须 200 + UP。四服务逐一留证（server.port 错峰避免占用冲突）；临时日志存 .trae/tmp/ 用后即删。

### §2.6 备选决策树（仅停止条件触发时回报，不自行改道）

- Trivy 判 2.0.65 CRITICAL：a) 升 2.0.66+（若 Central 已发布）；b) .trivyignore 豁免登记（治理回退，需用户裁决）；c) 改走 rocketmq-client ≥5.4.0 升级路线（需覆盖 SCA 管理值 + 兼容性验证课题化）。
- 恢复 fastjson 1.2.83：否决项（违反 TASK-196 终态治理）。

## §3 停止条件（触发即停手回报）

1. @Component 注册后 user 探针或生产实起暴露**其它**隐藏 bean 缺失（非拦截器）→ 停手归因。
2. 模板 mock 恢复后六探针 CI 等效仍红 → 停手（说明 bridge 修复不完整或另有根因）。
3. offline 609 基线回退 / 静态检查违规净增 → 停手。
4. 需触碰 §0.1 零触碰面（含自行登记 .trivyignore）→ 停手。
5. 冒烟出现 D3 之外的新失败签名 → 停手归因，不扩大修改面。
6. CI（第 41 次）Trivy 对 com.alibaba:fastjson:2.0.65 报 CRITICAL → 停手走 §2.6 决策树。
7. dependency:get 补仓失败（Central 无 2.0.65 或解析异常）→ 停手（禁降级取 2.0.53 以下）。

## §4 白名单（只改清单）

C-01 实施笔恰 9 文件（模板 mock 回退时 7-9 号内容回摆、文件数不变）：

1. `user-service/src/main/java/com/sportverify/user/config/StompConnectAuthInterceptor.java`
2. `pom.xml`（父）
3. `user-service/pom.xml`
4. `record-service/pom.xml`
5. `verify-service/pom.xml`
6. `leaderboard-service/pom.xml`
7. `user-service/src/test/java/com/sportverify/user/health/UserProbeTest.java`
8. `verify-service/src/test/java/com/sportverify/verify/health/VerifyProbeTest.java`
9. `record-service/src/test/java/com/sportverify/record/health/RecordProbeTest.java`

C-02 台账笔恰 4 文件：`spec/changes/fix-d3-prod-boot-defects/tasks.json`（勾选翻转）、本任务书 §7 回填、`work/mailbox/tasks/TASK-200/handoff.md`（新建，含只改清单小节）、`work/mailbox/PLAN.md`（纯追加）。

## §5 受保护 token

29 项 tracked `git grep -cF` 权威口径（禁 --untracked 口径），开工实测基线 SUM=2030；收口复测只增不减；新文档禁枚举字面量。

## §6 门禁结构（收口全量留证）

| 门禁 | 口径 |
| --- | --- |
| offline | `--mode=offline test` rc=0，609 逐位（36/48/130/151/159/70/15） |
| static | `--static=record-service` 与 `--static=leaderboard-service` 双模块 rc=0 |
| 契约门 | `mailbox-contract.sh` 双态（--open / 收口态）rc=0 |
| token | 29 项 SUM ≥ 2030 |
| 词面门 | CI word-check 全绿（新文档不引入触发形态） |
| 六探针 | CI 等效无中间件属性覆盖全绿（禁停共享中间件容器） |
| 冒烟 | 四服务生产 jar 实起 liveness UP（§2.5） |
| 外部 CI | 推送后第 41 次：6 镜像构建 + Trivy 收缩态 + 六探针实跑全绿 |

## §7 收口记录（执行侧 C-02 纯追加回填）

（开工前为空）
