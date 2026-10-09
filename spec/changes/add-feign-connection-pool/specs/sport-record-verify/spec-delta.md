# spec-delta：Feign 传输层连接池化（TASK-189 add-feign-connection-pool）

## ADDED 需求：服务间 Feign HTTP 传输层引入 Apache HttpClient5 连接池

### 场景

1. api 模块引入 `io.github.openfeign:feign-hc5`（无版本号，版本由 spring-cloud-dependencies 2023.0.1 → spring-cloud-openfeign-dependencies 4.1.1 → feign-bom 13.2.1 import 链管理，与 feign-core 13.2.1 同源）：Spring Cloud OpenFeign 自动配置 `FeignAutoConfiguration$HttpClient5FeignConfiguration`（`@ConditionalOnClass(feign.hc5.ApacheHttp5Client)`）检测到类路径后，Feign `Client` 由每请求新建 TCP 的 `Client.Default` 切换为池化的 `ApacheHttp5Client`——全仓 Feign 消费方零代码改动自动获得连接复用。
2. 三个实际消费方（record-service / verify-service / leaderboard-service，@EnableFeignClients 实证）在各自配置文件显式声明池参数（record 为 properties 键风格，verify / leaderboard 为 yml）：`spring.cloud.openfeign.httpclient.hc5.enabled=true`（开关显式化）+ `spring.cloud.openfeign.httpclient.max-connections=200` + `spring.cloud.openfeign.httpclient.max-connections-per-route=50` + `spring.cloud.openfeign.httpclient.time-to-live=300` + `spring.cloud.openfeign.httpclient.time-to-live-unit=seconds`。
3. 连接寿命 300 秒是唯一偏离官方默认（-1 无限）的参数：防止池化长连接粘住已下线服务实例（Nacos 发现 + LB 拓扑变更感知）；池容量 200/50 为官方默认值显式化，容量与实际并发需求的匹配度由后续压测课题定参（改配置即生效，零代码）。
4. 既有 Feign 治理配置零改动：三消费服务的 `client.config.default` 超时（connect 1000ms / read 3000ms）、显式覆盖、circuitbreaker 开关、Resilience4j 参数全部保持原样；池策略（pool-concurrency-policy / pool-reuse-policy）与 hc5 子段超时保持 Spring Cloud 默认。
5. user-service / mapmatch-service 经 api 模块传递获得 feign-hc5：运行时多一个未被使用的 ApacheHttp5Client bean（两服务无 @EnableFeignClients、无 Feign 客户端调用），零行为影响；gateway-service 不引 api 模块，零影响（登记说明项，不为其加配置）。
6. 本变更不 claim 任何延迟 / QPS / 性能改善读数（无压测依据）；连接复用的真实链路观察按既有口径登记 UNDETERMINED。

### 验收断言

- 单测（offline 确定性、纯 JVM 不启上下文，三服务同构）：类路径判别式——`feign.hc5.ApacheHttp5Client` 可加载（依赖经 api 传递到位，误删或误换坐标即红）；真实配置绑定——读各服务真实配置文件（yml 经 YamlPropertySourceLoader / properties 经 PropertiesLoaderUtils），Spring Binder 绑定 `FeignHttpClientProperties` 断言 maxConnections=200 / maxConnectionsPerRoute=50 / timeToLive=300 / timeToLiveUnit=SECONDS（守护键拼写静默失效）；开关读数——`spring.cloud.openfeign.httpclient.hc5.enabled` 为 true。
- 基线：Java offline 541（36/41/117/134/144/59/10）只增不减（预计 +9 上下落 record-service / verify-service / leaderboard-service，以实测逐位登记）；`--static=record-service` 811 不增。
- 零 main 代码改动：git diff 核验仅 pom + 配置文件 + 测试文件；零 SQL DDL、零前端改动、零 ci.yml / compose / 校验脚本 / 父 pom 触碰。
- 离线门禁：feign-hc5 13.2.1 / httpclient5 5.2.3 / httpcore5 5.2.4 / httpcore5-h2 5.2.4 已实证在本地 .m2-repo，offline 门禁复跑通过；缺构件时按预置预案在线补料（环境准备，非验收读数）后复跑。
- CI：online verify 正常拉取新依赖（缓存 key 按 pom 哈希自动失效）；compose parse check 与词面门零触碰面全绿。
