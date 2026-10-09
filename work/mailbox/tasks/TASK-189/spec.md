# TASK-189 add-feign-connection-pool 任务书（Feign 传输层引入 Apache HttpClient5 连接池）

## 0. 红线（违任一条即 FAILED 停手回报）

1. 依赖坐标已裁决：`io.github.openfeign:feign-hc5`（**不是** findings 原文的 feign-httpclient5），无版本号，**仅加 api 模块 pom 一处**；父 pom dependencyManagement 零触碰（BOM 仲裁链不得双源）；禁止引入 feign-httpclient5 / feign-okhttp / feign-httpclient(HC4) / okhttp
2. 零 Java main 源码改动：装配全由 Spring Cloud 自动配置完成（`FeignAutoConfiguration$HttpClient5FeignConfiguration` @ConditionalOnClass(feign.hc5.ApacheHttp5Client) + @ConditionalOnProperty(spring.cloud.openfeign.httpclient.hc5.enabled) 默认 true）；只改 pom + 配置文件 + 新增测试文件
3. 池参数已裁决（5 键，逐字）：`hc5.enabled=true`（显式化）/ `max-connections=200` / `max-connections-per-route=50` / `time-to-live=300` / `time-to-live-unit=seconds`；池策略（pool-concurrency-policy / pool-reuse-policy）与 hc5 子段超时（socket-timeout / connection-request-timeout）**不配**（保持默认）
4. 既有 Feign 治理配置零改动：三服务 client.config.default 超时（1s/3s）、显式覆盖、circuitbreaker 开关、Resilience4j 参数段全部原样
5. 零触碰清单：ci.yml / docker-compose* / mvn-verify.sh / mailbox-contract.sh / gateway-service / web / typed-router.d.ts / sql/ / 父 pom / user-service 与 mapmatch-service 的任何文件（两服务传递获得依赖但无需配置，登记说明项即可）
6. 性能数字零 claim：handoff 与台账**禁写**任何延迟 / QPS / 性能改善读数（无压测依据）；连接复用真实链路观察登记 UNDETERMINED（沿 182/185/186/187/188 口径不判失败）
7. 词面门正则字面量不入任何 tracked 文档与输出（TASK-184 F1 教训）；受保护 token 字面量不枚举进新文档（TASK-188 N1 教训，避免全仓计数自增失准）
8. 离线门禁缺构件处置：先确认 .m2-repo 构件，缺则在线补料 `mvn -s .mvn-settings.xml -pl api -am clean install -DskipTests`（PowerShell 直跑，登记为环境准备、非验收读数），复跑 offline 门禁；**禁把 exit 3（依赖来源不可判定）记为通过**；验收读数一律走 scripts/verify/mvn-verify.sh
9. 停止条件：offline 基线 541（36/41/117/134/144/59/10）回退或 811 增 / checkstyle 因新测试文件产生违规 / 需触碰任一零触碰面 / 装配判别式单测无法以纯 JVM 确定性形态落地

## 1. 背景与侦察实证（指导侧已亲核，2026-10-09）

- **坐标实证修正**：spring-cloud-openfeign-core 4.1.1 的 pom 中 feign-hc5 为 `optional=true`（不随 starter 传递，F07 成立）；`FeignAutoConfiguration$HttpClient5FeignConfiguration` 的 @ConditionalOnClass 探测类为 `feign.hc5.ApacheHttp5Client`（javap 常量池 #7/#35 实证）、@ConditionalOnProperty 键为 `spring.cloud.openfeign.httpclient.hc5.enabled`（常量池 #37）、装配方法 `feignClient(CloseableHttpClient)` → `new ApacheHttp5Client(...)` @ConditionalOnMissingBean(Client.class)
- **配置类实证**：`org.springframework.cloud.openfeign.support.FeignHttpClientProperties`，前缀 `spring.cloud.openfeign.httpclient`（@ConfigurationProperties 注解实证）；顶层字段 maxConnections（默认 200）/ maxConnectionsPerRoute（默认 50）/ timeToLive（long，默认 -1）/ timeToLiveUnit（默认 SECONDS）；嵌套 Hc5Properties 仅含池策略与子段超时（**无 enabled 字段**——开关只在 @ConditionalOnProperty，测试断言走环境键值而非 properties 类）
- **版本仲裁链**：spring-cloud-openfeign-dependencies 4.1.1 pom 实证 feign.version=13.2.1（feign-bom 管理）；httpclient5 由 Boot 3.2.4 BOM 仲裁 5.2.3；.m2-repo（D:/code/sports/.m2-repo，.mvn-settings.xml 声明）已实证有 feign-hc5 13.2.1 / httpclient5 5.2.3 / httpcore5 5.2.4 / httpcore5-h2 5.2.4——离线门禁首跑预计绿
- **消费方实证**：@EnableFeignClients 仅 record-service（RecordApplication.java L20）/ verify-service（VerifyApplication.java L20）/ leaderboard-service（LeaderboardApplication.java L22）三家；api 模块 6 个 @FeignClient 契约（VerifyApi/UserApi/AuthApi/LeaderboardApi/MapMatchApi/RecordApi）；user-service 与 mapmatch-service 引 api 仅为 DTO（无 Feign 调用）；gateway-service 不引 api
- **配置先例**：record-service application.properties L44-53（Feign 熔断+超时段，properties 键风格——该服务因 snakeyaml 冲突禁用 yml，L93 注释明示）；verify-service application.yml L19-30（openfeign 段：circuitbreaker + client.config default/mapmatch-service）；leaderboard-service application.yml L27-35（openfeign 段：circuitbreaker + client.config default）
- **checkstyle 口径**：--static=record-service 的 checkstyle:check 不扫 test 源码——TASK-188 在 record-service 新增 2 个测试文件后 811 持平（实证）；本课题三服务 main 零改动，811 持平预期成立
- **CI 口径**：build 档 `mvn-verify.sh --mode=online verify`（在线拉取，actions/cache key 按 **/pom.xml 哈希自动失效）；静态门跑 leaderboard-service（main 零改动持平）；compose parse check 与词面门零触碰面
- **依赖先例**：api 模块传递 openfeign starter（api/pom.xml L26-29；leaderboard pom L52-53 注释「openfeign starter 由 api 模块传递引入」）；无版本号引入沿 loadbalancer / circuitbreaker-resilience4j starter 先例
- **基线**：开工 HEAD=015517b（TASK-188 收口态），origin/main...main=0 1（TASK-188 补记笔待批推送，沿先例）；offline 541（36/41/117/134/144/59/10）；静态 811；受保护 token 29 项开工实测为准（TASK-188 收口真值 2030 参照）

## 2. 预注册实施设计

### 2.1 api 模块（pom.xml，依赖区 spring-cloud-starter-openfeign 之后）

```xml
<!-- Feign 传输层池化客户端（TASK-189，findings F07）：Spring Cloud OpenFeign 4.1.1 的
     HttpClient5FeignConfiguration 探测 feign.hc5.ApacheHttp5Client（本模块），classpath 出现后
     Feign Client 由每请求新建 TCP 切换为池化 ApacheHttp5Client；坐标为 feign-hc5（原 findings
     表述 feign-httpclient5 经实证修正——探测类不在该坐标）。版本由 spring-cloud-dependencies
     2023.0.1 → feign-bom 13.2.1 管理，与既有 feign-core 13.2.1 同源；底层 httpclient5 5.2.3 /
     httpcore5 5.2.4 由 Boot 3.2.4 BOM 仲裁，无需也禁止在父 pom 重复声明 -->
<dependency>
    <groupId>io.github.openfeign</groupId>
    <artifactId>feign-hc5</artifactId>
</dependency>
```

### 2.2 三消费服务配置（5 键逐字，record=properties 键风格、verify/leaderboard=yml 嵌套）

record-service/src/main/resources/application.properties（Feign 熔断段之后追加，注释说明池化与 ttl 300s 拓扑感知理由）：

```properties
# ===== Feign 传输层连接池（TASK-189，findings F07）=====
# hc5 客户端装配开关（默认 true，显式化便于检索）；池参数 200/50 为官方默认值显式化；
# ttl 300s：默认 -1 无限寿命的池化连接会粘住已下线实例（Nacos 发现 + LB 拓扑变更），
# 300s 定期重建平衡复用率与拓扑感知；容量与并发需求的匹配度由后续压测课题定参
spring.cloud.openfeign.httpclient.hc5.enabled=true
spring.cloud.openfeign.httpclient.max-connections=200
spring.cloud.openfeign.httpclient.max-connections-per-route=50
spring.cloud.openfeign.httpclient.time-to-live=300
spring.cloud.openfeign.httpclient.time-to-live-unit=seconds
```

verify-service / leaderboard-service application.yml（spring.cloud.openfeign 段内、circuitbreaker 与 client 之间插入 httpclient 子段，注释同上要旨）：

```yaml
      # Feign 传输层连接池（TASK-189，findings F07）：hc5 池化客户端装配（默认 true 显式化）；
      # 200/50 官方默认显式化；ttl 300s 防连接粘住已下线实例（Nacos 拓扑感知）
      httpclient:
        hc5:
          enabled: true
        max-connections: 200
        max-connections-per-route: 50
        time-to-live: 300
        time-to-live-unit: seconds
```

### 2.3 装配判别式单测（三服务同构，各 1 类 3 用例，纯 JVM 确定性不启上下文）

测试类 `FeignConnectionPoolConfigTest`（包：record=`com.sportverify.record.config`、verify=`com.sportverify.verify.config`、leaderboard=`com.sportverify.leaderboard.config`——无 config 测试包则新立，leaderboard 已有 config 测试先例 LeaderboardL2RedisRoundTripIT）：

| 用例 | 断言 |
| --- | --- |
| hc5ClientOnClasspath | `assertDoesNotThrow(() -> Class.forName("feign.hc5.ApacheHttp5Client"))`——依赖经 api 传递到位（误删 / 误换坐标即红） |
| poolParametersBoundFromRealConfigFile | record：`PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))` → `MapPropertySource`；verify/leaderboard：`new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))` → 装入 `MutablePropertySources` → `Binder(ConfigurationPropertySources.get(propertySources))`（import 自 `org.springframework.boot.context.properties.source.ConfigurationPropertySources`）→ `binder.bind("spring.cloud.openfeign.httpclient", Bindable.of(FeignHttpClientProperties.class)).get()` → 断言 `getMaxConnections()==200` / `getMaxConnectionsPerRoute()==50` / `getTimeToLive()==300` / `getTimeToLiveUnit()==TimeUnit.SECONDS`——读真实配置文件，守护键拼写静默失效 |
| hc5EnabledKeyPresent | 从同一 property source 断言 `spring.cloud.openfeign.httpclient.hc5.enabled` 值为 "true"（开关不在 properties 类字段，走环境键值） |

注意：verify/leaderboard 的 yml 若含多 document（---）YamlPropertySourceLoader 返回多个 PropertySource，逐个装入；断言只针对 httpclient 键，与其它段解耦。注释风格沿各服务既有测试（中文 Javadoc 说明断言目的）。

### 2.4 不做的事（红线对照）

零 main Java 改动；user/mapmatch 不加配置（无 Feign 调用，传递面登记说明项）；池策略与 hc5 子段超时不配；父 pom / ci.yml / compose / 前端零触碰；不写性能数字。

## 4. 白名单（只改清单与主题串）

- 派发笔：`spec/changes/add-feign-connection-pool/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + `work/mailbox/tasks/TASK-189/spec.md`（四件套零改动入库），主题：`docs(spec): 派发 TASK-189 Feign 连接池提案与任务书`
- C-01（7 文件）：`api/pom.xml`、`record-service/src/main/resources/application.properties`、`record-service/src/test/java/com/sportverify/record/config/FeignConnectionPoolConfigTest.java`（新）、`verify-service/src/main/resources/application.yml`、`verify-service/src/test/java/com/sportverify/verify/config/FeignConnectionPoolConfigTest.java`（新）、`leaderboard-service/src/main/resources/application.yml`、`leaderboard-service/src/test/java/com/sportverify/leaderboard/config/FeignConnectionPoolConfigTest.java`（新），主题：`feat(api): Feign 传输层引入 Apache HttpClient5 连接池（TASK-189）`
- C-02：tasks.json 全勾 + 本 spec §7 纯追加 + `work/mailbox/tasks/TASK-189/handoff.md` + `work/mailbox/PLAN.md` 纯追加，主题：`docs(mailbox): 登记 TASK-189 Feign 连接池验收与台账闭环（TASK-189）`

## 5. 受保护 token（29 项，口径 repo 全量 git grep -cF）

开工实测 SUM 为准（TASK-188 收口实测 2030 参照）；收口读数以**收口态实测**为准。只增不减；新文档**不枚举 token 字面量**（避免计数自增失准，沿 TASK-188 N1 教训）。

## 6. 门禁结构

1. **预提交门禁（每笔前亲跑记录 rc）**：tasks.json 语法（`python -m json.tool`）rc=0；词面门四形态 ZERO_HIT rc=1 + 探针 + 三态（正则字面量不入任何 tracked 文档与输出）；`git diff --check` rc=0；契约门在途 `--open TASK-189 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新增文件纯 LF 末尾换行完整（.java/.xml 均新文件 LF；修改文件保持既有行尾——pom.xml/application.* 均为既有 CRLF 保持）
2. **收口门禁（C-02 后亲跑留证）**：离线依赖面核验（.m2-repo 四构件 ls 留证）→ `bash scripts/verify/mvn-verify.sh --mode=offline test` 全量新基线逐位登记（541 只增不减，增量预计落 record/verify/leaderboard）→ 若缺构件按红线 8 预案补料后复跑；`bash scripts/verify/mvn-verify.sh --static=record-service` 811 不增；零 main 代码核验（`git diff --name-only <派发笔>..HEAD` 仅白名单 7 文件，无任何 main Java）；web 三件套与 frozen-lockfile **不受影响也零改动**（前端零触碰，typed-router 零漂移以 git diff 留证）；契约门无参 rc=0；PLAN.md 自派发笔起纯追加
3. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（坐标实证修正记录、三服务单测读数、离线依赖面核验读数、传递面说明项）/ 逐门实测表 / token 前后读数 / 未覆盖项（UNDETERMINED 真实链路观察）/ 提交表（显式哈希，禁时效指针）
4. **真实链路联调（可选不判失败）**：本机起 Nacos + 双服务后真实 Feign 调用观察连接复用（httpclient5 pool 指标或抓包）；不可达则 UNDETERMINED（沿 182/185/186/187/188 口径）

## 7. 收口记录（执行侧 C-02 纯追加）

### 7.1 提交记录

- 派发笔：`e4490c5186f306fb70ba79eac3f6a84da93a4c35`（`e4490c5`） `docs(spec): 派发 TASK-189 Feign 连接池提案与任务书`
- C-01 实施笔：`b19d4745fb3623c2a864e4a1f68607a23fcdae0a`（`b19d474`） `feat(api): Feign 传输层引入 Apache HttpClient5 连接池（TASK-189）`
- C-02 台账笔：`（由回传报告以显式哈希给出，见 handoff 提交表）` `docs(mailbox): 登记 TASK-189 Feign 连接池验收与台账闭环（TASK-189）`

### 7.2 单测矩阵读数（offline，C-01 实施态实测）

- record-service：`FeignConnectionPoolConfigTest` Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
  - `hc5ClientOnClasspath`：pass
  - `poolParametersBoundFromRealConfigFile`：pass（maxConnections=200, maxConnectionsPerRoute=50, timeToLive=300, timeToLiveUnit=SECONDS）
  - `hc5EnabledKeyPresent`：pass（spring.cloud.openfeign.httpclient.hc5.enabled=true）
- verify-service：`FeignConnectionPoolConfigTest` Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
  - `hc5ClientOnClasspath`：pass
  - `poolParametersBoundFromRealConfigFile`：pass（maxConnections=200, maxConnectionsPerRoute=50, timeToLive=300, timeToLiveUnit=SECONDS）
  - `hc5EnabledKeyPresent`：pass（spring.cloud.openfeign.httpclient.hc5.enabled=true）
- leaderboard-service：`FeignConnectionPoolConfigTest` Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
  - `hc5ClientOnClasspath`：pass
  - `poolParametersBoundFromRealConfigFile`：pass（maxConnections=200, maxConnectionsPerRoute=50, timeToLive=300, timeToLiveUnit=SECONDS）
  - `hc5EnabledKeyPresent`：pass（spring.cloud.openfeign.httpclient.hc5.enabled=true）

### 7.3 门禁读数（收口态实测）

- offline 全量逐位：`36/41/117/137/147/62/10` = **550**（基线 541 只增不减，+9 全落 record 134→137 / verify 144→147 / leaderboard 59→62 三模块），Failures/Errors/Skipped 全 0，BUILD SUCCESS
- 静态门：`--static=record-service` Checkstyle **811 持平**未增，rc=1 为基线违规模块预期
- 契约门在途：`bash scripts/verify/mailbox-contract.sh --open TASK-189 --baseline=e4490c5` rc=0（判据 A 两件套齐 + 判据 B 清单一致）
- 词面门四形态：改动文件集与 repo 全量（CI 权威 exclude 口径）四形态（default / C / zh_CN.UTF-8 / C.UTF-8）全 ZERO_HIT rc=1，探针三态 HIT rc=0，PROBE_GONE=yes
- token 29 项：开工实测 SUM=2030；C-01 后实测 SUM=2030；收口态实测 SUM=2030 只增不减（新文档不枚举 token 字面量，沿 TASK-188 N1 教训）
- 只改清单全等核验：C-01 恰白名单 7 文件，零 main Java 代码；C-02 恰白名单 4 文件；typed-router.d.ts 零漂移（`git diff --exit-code -- web/src/typed-router.d.ts` rc=0）
- 离线依赖面核验读数：.m2-repo 四构件（feign-hc5 13.2.1 / httpclient5 5.2.3 / httpcore5 5.2.4 / httpcore5-h2 5.2.4）`ls` 留证在位，首跑 offline 绿，无需在线补料
