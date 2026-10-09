# TASK-189 add-feign-connection-pool 提案：Feign 传输层引入 Apache HttpClient5 连接池

## Why（现状与痛点）

findings F07（P1 性能）：全仓 6 个服务间 Feign 契约（api 模块 VerifyApi / UserApi / AuthApi / LeaderboardApi / MapMatchApi / RecordApi），实际消费方 record-service、verify-service、leaderboard-service 三家（@EnableFeignClients 实证），传输层走 Feign 默认 `Client.Default`（HttpURLConnection）——**每请求新建 TCP 连接**，5k QPS 目标下握手开销与端口耗尽风险。

现状实证（2026-10-09，指导侧亲核）：

- `spring-cloud-openfeign-core 4.1.1` 的 pom 中 `io.github.openfeign:feign-hc5` 为 `optional=true`——**不随 starter 传递**，三服务类路径无 HC5 客户端（F07 判定成立，且传递依赖层面亦无池化）
- 三消费服务已有超时配置（`client.config.default` connect 1000ms / read 3000ms）与 Resilience4j 熔断，但零 httpclient 池配置
- findings 原文建议的 `feign-httpclient5` 坐标经实证修正为 `feign-hc5`：Spring Cloud OpenFeign 4.1.1 `FeignAutoConfiguration$HttpClient5FeignConfiguration` 的 `@ConditionalOnClass` 探测类是 `feign.hc5.ApacheHttp5Client`（javap 常量池实证），该类在 feign-hc5 模块——引错坐标则装配条件不触发、池静默失效

## What（方案）

**依赖引入（单点：api 模块）+ 池参数显式化（三消费服务）+ 装配判别式单测（三服务）**，零 Java main 代码改动（装配由 Spring Cloud 自动配置完成）。

| 决策点 | 裁决 | 理由 |
| --- | --- | --- |
| 客户端选型 | Apache HttpClient5（feign-hc5），否决 okhttp | Spring Cloud OpenFeign 4.1.x 官方支持路径（`spring.cloud.openfeign.httpclient.hc5.enabled` 默认 true）；okhttp 需另配 `okhttp.enabled` 且非官方推荐路径 |
| 依赖位置 | api 模块 pom 单点声明 | 沿「api 模块传递 openfeign starter」既有架构先例（leaderboard pom L52-53 注释明确）；未来新服务启用 Feign 自动获得池化；gateway 不引 api 零影响 |
| 版本管理 | 无版本号引入 | 版本由 `spring-cloud-dependencies 2023.0.1 → spring-cloud-openfeign-dependencies 4.1.1 → feign-bom 13.2.1` import 链管理，与既有 feign-core 13.2.1 同源零漂移；httpclient5 5.2.3 / httpcore5 5.2.4 由 Boot 3.2.4 BOM 仲裁（均已在本地 .m2-repo 实证）；沿 loadbalancer / circuitbreaker starter 无版本先例，**不进父 pom dependencyManagement**（BOM 已管理，双声明反而制造两处版本源） |
| 开关 | `spring.cloud.openfeign.httpclient.hc5.enabled=true` 显式化 | 默认即 true（matchIfMissing），显式写一行让「池已启用」在配置文件可检索，沿「显式覆盖同值便于检索」既有注释文化 |
| 池容量 | `max-connections=200` / `max-connections-per-route=50`（官方默认值显式化） | 机制性目标是「消除每请求握手」；容量数值无压测依据不拍脑袋，与实际并发需求的匹配度登记为后续压测课题（见风险） |
| 连接寿命 | `time-to-live=300` + `time-to-live-unit=seconds`（唯一主动偏离默认 -1） | 默认无限寿命的池化连接在 Nacos 服务发现 + LB 场景下会粘住已下线实例（复用旧连接打到缩容目标）；300s 让连接定期重建，平衡复用率与拓扑感知 |
| 不配的键 | 池策略（`hc5.pool-concurrency-policy` / `pool-reuse-policy`）与 `hc5.socket-timeout` / `connection-request-timeout` | 保持 Spring Cloud 默认（STRICT / LIFO / 默认超时）；超时已有 `client.config.default` 1s/3s 治理，不双轨 |

**改动面（7 文件，全零 main 代码）**：

| 层 | 改动 |
| --- | --- |
| api | pom.xml +1 依赖（feign-hc5，注释说明坐标修正与仲裁链） |
| record-service | application.properties Feign 段 +5 行（properties 键风格，沿既有段）；config 包 +1 测试类 |
| verify-service | application.yml openfeign 段 + httpclient 子段（5 键）；config 包 +1 测试类 |
| leaderboard-service | application.yml openfeign 段 + httpclient 子段（5 键）；config 包 +1 测试类 |

**单测（三服务同构，预计 +9，offline 确定性、纯 JVM 不启上下文）**：

1. **类路径判别式**：`Class.forName("feign.hc5.ApacheHttp5Client")` 成功——守护「依赖误删 / 误换 feign-httpclient5 坐标导致装配条件静默失效」（正是 findings 坐标错误会被此断言抓住）
2. **真实配置绑定**：读各服务真实配置文件（yml 经 YamlPropertySourceLoader、properties 经 PropertiesLoaderUtils）→ Spring Binder 绑定 `FeignHttpClientProperties` → 断言 maxConnections=200 / maxConnectionsPerRoute=50 / timeToLive=300 / timeToLiveUnit=SECONDS——守护「键拼写错误静默失效」（mock 属性源测不了真实文件，故绑真实文件）
3. **开关读数**：断言 `spring.cloud.openfeign.httpclient.hc5.enabled` 键值为 true

## 边界（明确不做）

- 零 Java main 源码改动：装配全由自动配置完成（`@ConditionalOnClass(feign.hc5.ApacheHttp5Client)` + `@ConditionalOnMissingBean(Client.class)`）
- 超时 / 熔断既有配置（client.config.default / circuitbreaker / resilience4j 段）零改动
- user-service / mapmatch-service 引 api 传递获得 feign-hc5：运行时各多一个未被使用的 ApacheHttp5Client bean（无 @EnableFeignClients，无 Feign 客户端调用它），零行为影响——登记说明项，不为其加配置
- gateway-service 不引 api 模块，零影响
- 不做池容量压测定参（登记后续课题）；**不 claim 任何延迟 / QPS / 性能数字**（无压测读数，沿既有纪律）
- 零 SQL DDL、零前端改动、零 ci.yml / compose / mvn-verify.sh / mailbox-contract.sh / 父 pom 触碰

## 风险

| 风险 | 缓解 |
| --- | --- |
| 引错坐标（feign-httpclient5 / feign-httpclient / okhttp）导致装配条件不触发、池静默失效 | 依赖坐标以 javap 常量池实证锚定 feign-hc5；单测类路径判别式直接守护（引错即红） |
| 配置键拼错静默失效 | 单测绑定真实配置文件断言字段值（非 mock 属性源） |
| 离线门禁 .m2-repo 缺构件（exit 3 / offline 红） | 实证 feign-hc5 13.2.1 / httpclient5 5.2.3 / httpcore5 5.2.4 / httpcore5-h2 5.2.4 均已在 D:/code/sports/.m2-repo，首跑 offline 预计绿；任务书预置在线补料预案（`mvn -s .mvn-settings.xml -pl api -am clean install -DskipTests`，环境准备非验收读数，登记合规说明） |
| per-route 50 在单实例下游高并发时成为 lease 排队瓶颈（5k QPS × 200ms 级延迟的单 route 并发需求理论上界超 50） | 本课题定位「池化机制落地」，容量数值无实测依据不拍脑袋；提案登记后续压测课题定参，届时改配置即生效（零代码） |
| Boot BOM 与 feign-hc5 对 httpclient5 版本诉求不一致 | Boot 3.2.4 BOM 仲裁 httpclient5 5.2.3（dependencyManagement 优先于传递版本），feign-hc5 13.2.1 兼容 5.2.x；.m2-repo 已有该版本，offline 复跑即实证 |

## 验收（摘要）

三服务装配判别式单测全绿（类路径 / 真实配置绑定 / 开关读数，预计 +9 上下，落 record-service / verify-service / leaderboard-service 三模块，以实测逐位登记）；Java offline 全量新基线只增不减（当前 541 = 36/41/117/134/144/59/10）；`--static=record-service` 811 不增（main 零改动，checkstyle 不扫 test 源码，TASK-188 实证）；零 main 代码改动核验（git diff 确认仅 pom + 配置 + 测试）；契约门在途 rc=0；词面门 ZERO_HIT；token 29 项只增不减（新文档不枚举 token 字面量，沿 TASK-188 N1 教训）；CI online verify 正常拉取新依赖（第 31 次外部门槛）；连接复用的真实链路观察登记 UNDETERMINED（沿 182/185/186/187/188 口径不判失败）。
