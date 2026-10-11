# spec-delta：四服务生产启动缺陷修复（TASK-200 fix-d3-prod-boot-defects）

## ADDED 需求：生产启动完备性（D3 缺陷A）

user-service 的 `StompConnectAuthInterceptor` SHALL 以 `@Component` 注册为 bean（`JwtUtil` 既有 @Component 构造注入链自洽），使 `WebSocketNotificationConfig` 装配可解析；`UserProbeTest` SHALL 移除 `@Import(StompConnectAuthInterceptor.class)` 临时供 bean（双注册致注入歧义）并同步修订偏差登记段。验收：user 生产 jar 实起成功且 `/actuator/health/liveness` UP。

## MODIFIED 需求：父 pom 依赖管理（fastjson2 v1 兼容包受控回补，D3 缺陷B）

父 pom SHALL 以 property + dependencyManagement 锁 `com.alibaba:fastjson:2.0.65`；user/record/verify/leaderboard 四模块 pom SHALL 各声明该构件 `runtime` 依赖（与 TASK-196 fastjson exclusions 并存：exclusions 断 1.2.83 传递边，显式声明提供受控 2.0.x 运行时；gateway/mapmatch 零改动）。版本依据：TASK-196 spec §2.3 预注册兜底 2.0.53，本课题按 fastjson2 2.0.63 安全修复线（RocketMQ 5.5.1 发行注记实证）升格至当前 stable 2.0.65，偏差登记 handoff。机制依据：rocketmq-remoting 5.1.4 `NettyRemotingClient$1` 匿名 `TypeReference` 子类在客户端构造期硬解析（生产 jar 实起栈实证），兼容包同 FQCN 直配；`RocketMQMessageConverter` 复合转换器列表 Jackson 先于 fastjson（首匹配命中），业务 JSON（统一 Jackson）行为零漂移。

## MODIFIED 需求：探针测试掩蔽清理

record/verify 探针的 `RocketMQTemplate` @Primary mock SHALL 按「断言零改动、accommodation 最小」评估移除：先移除跑六探针 CI 等效验证，绿则保持移除（恢复探针对模板 bean 创建期的真实覆盖）；红则恢复 mock 并登记偏差（不停工，生产修复不依赖此项）。Redisson mock（TASK-199 环境隔离用途）一律保留；LeaderboardProbeTest 零改动。

## 验收断言

- 四服务（user/record/verify/leaderboard）生产 jar 实起全绿（liveness UP），D3 失败签名归零；gateway/mapmatch 不在冒烟面（本无缺陷）
- 六探针 CI 等效无中间件验证全绿；offline 609（36/48/130/151/159/70/15）逐位不变；static record/leaderboard rc=0；契约门双态 rc=0；token 29 项只增不减
- CI：6 镜像构建 + Trivy 收缩态扫描全绿——`com.alibaba:fastjson:2.0.65` 触发 CRITICAL 即停止条件（决策树：升 2.0.66+ / 豁免登记裁决 / 改走 client ≥5.4.0 升级路线），不得自行登记 .trivyignore
- 只改清单：C-01 恰 9 文件（StompConnectAuthInterceptor + 父 pom + 四模块 pom + User/Verify/Record 三探针）；C-02 恰 4 台账文件
- 零触碰：ci.yml、mvn-verify.sh、mailbox-contract.sh、docker-compose*、gateway-service、web、sql、ADR、api、common、mapmatch-service、.trivyignore、record sharding.yaml 夹具
