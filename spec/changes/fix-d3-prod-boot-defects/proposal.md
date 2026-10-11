# 提案：四服务生产启动缺陷修复（fix-d3-prod-boot-defects / TASK-200）

## 背景与问题（TASK-198 D3 登记，本课题研究阶段已完整归因）

四个服务生产 jar 实起在 HEAD 即坏（CI 不拉上下文故历次外部 CI 不可见）：

- **缺陷A（user）**：`StompConnectAuthInterceptor` 全仓无任何 bean 注册（TASK-185 引入，缺 @Component/@Bean），`WebSocketNotificationConfig` 构造注入即 NoSuchBeanDefinition，WebSocket 急切装配使整上下文启动失败。
- **缺陷B（user/record/verify/leaderboard 四服务）**：TASK-196 排除 fastjson 后，rocketmq-remoting 5.1.4 `NettyRemotingClient$1`（匿名 `new TypeReference` 子类）在 `NettyRemotingClient` 构造期即触发超类解析失败——任何 MQ 客户端构造（模板 producer 或编程式 consumer）必抛 `NoClassDefFoundError: com/alibaba/fastjson/TypeReference`。已以 leaderboard 生产 jar 实起取得完整失败栈（`MQClientAPIImpl.<init>(253) ← MQClientInstance ← DefaultMQPushConsumer.start ← LeaderboardEventConsumer.init`）。rocketmq-spring 2.3.1 本体 `RocketMQMessageConverter` 有 FASTJSON_PRESENT 守卫，不致错；源头全在 rocketmq-client/remoting/common 5.1.4 核心。user 无 producer.group（模板无 producer），失败点为其编程式消费者 @PostConstruct。

## 修复方案（预注册）

- **缺陷A**：`StompConnectAuthInterceptor` 加 `@Component`（JwtUtil 已 @Component，构造注入链自洽）；`UserProbeTest` 移除 `@Import` 临时供 bean（@Component+@Import 双注册致注入歧义，必移除）并更新偏差登记段。
- **缺陷B（主路径：fastjson2 v1 兼容包受控回补）**：父 pom property + dependencyManagement 锁 `com.alibaba:fastjson:2.0.65`（runtime），user/record/verify/leaderboard 四模块 pom 各加 runtime 依赖（与 TASK-196 exclusions 并存：排除断 1.2.83 传递边、显式声明受控 2.0.x 运行时）；gateway/mapmatch 零改动（无 rocketmq，镜像保持无 fastjson 字节）。版本依据：TASK-196 预注册兜底 2.0.53 之上取安全修复线——RocketMQ 5.5.1 发行注记实证 fastjson2 2.0.63 含安全修复，2.0.65 为当前 stable release；偏差登记。兼容性：Jackson 转换器在模板复合转换器列表中先于 fastjson（首匹配命中），业务 JSON 行为零漂移；业务代码 0 处 fastjson 引用维持。
- **备选路线（决策树，非主路径）**：升级 rocketmq-client ≥5.4.0（fastjson→fastjson2 全量重写线）需覆盖 SCA BOM 管理的 5.1.4 且跨三大版本、rocketmq-spring 2.3.1 二进制兼容未验证，爆炸半径大，仅当主路径触发停止条件时交用户裁决；恢复 fastjson 1.2.83 违反 TASK-196 终态治理（Trivy 豁免回归），否决。
- **探针掩蔽清理**：record/verify 探针 `RocketMQTemplate` mock 按「断言零改动、accommodation 最小」评估移除（先移除跑 CI 等效验证，绿则保持——恢复探针对模板创建期的真实覆盖即 D3 漏网防线；红则恢复 mock 登记偏差，不停工）；Redisson mock（TASK-199 环境隔离）一律保留；LeaderboardProbeTest 零改动。

## 验收核心

四服务生产 jar 实起全绿（D3 复现路径反转，liveness UP）；六探针 CI 等效全绿；offline 609 逐位不变；静态双模块 rc=0；契约门双态；token 2030 只增不减；CI 6 镜像构建 + Trivy 收缩态扫描全绿（fastjson 2.0.65 不得触发 CRITICAL，否则停止条件）。
