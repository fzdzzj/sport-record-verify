# TASK-189 add-feign-connection-pool Feign 传输层引入 Apache HttpClient5 连接池 —— 回传笔（handoff / 执行侧）

派发：指导 Agent（2026-10-09）；执行：执行侧。结论见 §2，证据见 §5，提交表见 §8（显式哈希）。push 由指导侧另行授权执行，执行侧不推送。

## 1. 开工规程核验

- **工作树基线全符**：开工基线 HEAD 为 `e4490c5`（`docs(spec): 派发 TASK-189 Feign 连接池提案与任务书`）；开工前 `git status --porcelain` 为空，HEAD 为 `e4490c5186f306fb70ba79eac3f6a84da93a4c35`。
- **红线逐条核验**（§0）：
  1. 依赖坐标已裁决：`io.github.openfeign:feign-hc5`（无版本号），仅加 `api/pom.xml` 一处；父 pom dependencyManagement 零触碰；未引入 feign-httpclient5 / feign-okhttp / feign-httpclient(HC4) / okhttp。
  2. 零 Java main 源码改动：装配全由 Spring Cloud 自动配置完成；仅改动 pom + 配置文件 + 新增测试文件。
  3. 池参数已裁决（5 键逐字）：`hc5.enabled=true` / `max-connections=200` / `max-connections-per-route=50` / `time-to-live=300` / `time-to-live-unit=seconds`；池策略与 hc5 子段超时未配（保持默认）。
  4. 既有 Feign 治理配置零改动：三服务 `client.config.default` 超时（1s/3s）、显式覆盖、circuitbreaker 开关、Resilience4j 参数段全部原样保持。
  5. 零触碰清单遵守：`ci.yml`、`docker-compose*`、`scripts/verify/mvn-verify.sh`、`scripts/verify/mailbox-contract.sh`、`gateway-service/`、`web/`、`web/src/typed-router.d.ts`、`sql/`、父 pom、`user-service/` 与 `mapmatch-service/` 文件均零触碰。
  6. 性能数字零 claim：本报告与台账禁写任何延迟 / QPS / 性能改善读数；连接复用真实链路观察登记 UNDETERMINED（沿 182/185/186/187/188 口径不判失败）。
  7. 词面门正则字面量绝不写入任何 tracked 文档与输出；受保护 token 29 项字面量不枚举进新文档（沿 TASK-188 N1 教训，避免全仓计数自增失准）。
  8. 离线依赖面核验：.m2-repo 四构件预先确认在位，首跑 offline 绿；验收读数一律走 `scripts/verify/mvn-verify.sh`。
  9. 停止条件核验：offline 基线 541 只增不减（实测 550），811 持平未增，checkstyle 零违规增量，装配判别式单测以纯 JVM 确定性落地，未触发任何停止条件。

## 2. 一句话结论与三支裁决

**Feign 传输层池化客户端依赖（feign-hc5）于 api 模块单点引入，三消费服务（record/verify/leaderboard）显式化 5 项连接池配置（200/50/ttl 300s），三服务新增纯 JVM 装配判别式单测 9 例全绿（单测总数 541→550，record 134→137，verify 144→147，leaderboard 59→62），静态 811 持平，零 main Java 改动，全门禁通过，判定 PASSED**；契约门在途/无参均 rc=0。外部终验待推送后下一次外部门槛（第 31 次）CI 绿。

### 2.1 三支判定

| 支 | 判定 | 依据 |
| --- | --- | --- |
| PASSED | ✓ 主支 | 依赖单点引入 + 三消费服务配置显式化 + 三服务判别式单测全绿（全仓 550，+9）+ 零 main Java 代码 + 静态 811 持平 + 契约门在途/无参 rc=0 + 全门禁绿 |
| FAILED | ✗ | 未触发（无停止条件触发、无门禁回归） |
| 外部终验 | 待推送 | 推送后下一次外部门槛（第 31 次）CI 绿；红则按签名归因，禁重试刷绿 |

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

全任务实际足迹 = 派发笔 4 条 + C-01 7 条 + C-02 4 条，去重后共 15 处文件：

**派发笔（执行侧零改动入库）**：
1. `spec/changes/add-feign-connection-pool/proposal.md`
2. `spec/changes/add-feign-connection-pool/tasks.json`
3. `spec/changes/add-feign-connection-pool/specs/sport-record-verify/spec-delta.md`
4. `work/mailbox/tasks/TASK-189/spec.md`

**C-01（实施笔）**：
1. `api/pom.xml`
2. `leaderboard-service/src/main/resources/application.yml`
3. `leaderboard-service/src/test/java/com/sportverify/leaderboard/config/FeignConnectionPoolConfigTest.java`
4. `record-service/src/main/resources/application.properties`
5. `record-service/src/test/java/com/sportverify/record/config/FeignConnectionPoolConfigTest.java`
6. `verify-service/src/main/resources/application.yml`
7. `verify-service/src/test/java/com/sportverify/verify/config/FeignConnectionPoolConfigTest.java`

**C-02（台账收口笔）**：
1. `spec/changes/add-feign-connection-pool/tasks.json`
2. `work/mailbox/tasks/TASK-189/spec.md`
3. `work/mailbox/tasks/TASK-189/handoff.md`
4. `work/mailbox/PLAN.md`

> 零触碰清单：`gateway-service/`、`docker-compose*.yml`、`ci.yml`、`scripts/verify/mvn-verify.sh`、`scripts/verify/mailbox-contract.sh`、`web/`、`web/src/typed-router.d.ts`、`sql/`、父 pom、`user-service/` 与 `mapmatch-service/` 源码与配置、`.codex/`、`.trae/`。`git status --porcelain` 收口后为空。

## 4. 偏差登记

| # | 偏差 | 性质与处置 |
| --- | --- | --- |
| D1 | **C-02 自身哈希自指**：handoff §8 无法在写文件时预知本笔哈希（TASK-189 执行范围为派发/C-01/C-02 三笔，不另开订正笔）。处置：§8 列出派发笔 `e4490c5`、C-01 `b19d474` 显式哈希；C-02 自指为台账收口笔，其显式哈希在本回传报告给出（沿 TASK-182/185/186/187/188 台账终态化先例的固有自指事实）。 | 台账提交表终态化固有的单一自指；三笔主题与任务书 §4 逐字一致，无主题偏差。 |
| D2 | **词面门 repo 全量口径**：本仓在历史存档 `spec/changes/archive/add-two-level-cache/tasks.json` 命中既有禁用措辞、`.github/workflows/ci.yml` 自带正则本体——两处均为公开门禁 CI 显式排除路径（见 ci.yml `Public docs wording self-check` 步骤 exclude 集：`spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）。按 CI 权威口径复扫 repo 全量四形态 ZERO_HIT。本 D2 行不落任何词面门正则字面量（TASK-184 F1 教训）。 | CI 权威口径处置；本任务改动文件集四形态独立 ZERO_HIT 已实测。 |

## 5. 实施证据（含验收要点判据）

### 5.1 坐标实证修正记录
- findings F07 原文建议坐标为 `feign-httpclient5`，经实证修正为 `io.github.openfeign:feign-hc5`：
  Spring Cloud OpenFeign 4.1.1 的 `FeignAutoConfiguration$HttpClient5FeignConfiguration` 类中 `@ConditionalOnClass` 条件探测类为 `feign.hc5.ApacheHttp5Client`（javap 常量池实证），该类位于 `feign-hc5` 模块而非 `feign-httpclient5`。若引入错误坐标，装配条件不触发，连接池静默失效。
- 版本由 `spring-cloud-dependencies:2023.0.1` → `spring-cloud-openfeign-dependencies:4.1.1` → `feign-bom:13.2.1` 管理，与既有 `feign-core:13.2.1` 同源；底层 `httpclient5:5.2.3` / `httpcore5:5.2.4` 由 Spring Boot 3.2.4 BOM 仲裁。api 模块无版本号引入，父 pom 零触碰，避免版本双源。

### 5.2 三服务装配判别式单测读数（纯 JVM，不启上下文）
- **record-service**（`com.sportverify.record.config.FeignConnectionPoolConfigTest`）：
  - `hc5ClientOnClasspath`：`assertDoesNotThrow(() -> Class.forName("feign.hc5.ApacheHttp5Client"))` 通过。
  - `poolParametersBoundFromRealConfigFile`：读真实 `application.properties` 经 Spring `Binder` 绑定 `FeignHttpClientProperties`，断言 maxConnections=200, maxConnectionsPerRoute=50, timeToLive=300, timeToLiveUnit=SECONDS 通过。
  - `hc5EnabledKeyPresent`：断言 `spring.cloud.openfeign.httpclient.hc5.enabled` 键值为 `"true"` 通过。
  - 模块测试数：134 → 137（+3）。
- **verify-service**（`com.sportverify.verify.config.FeignConnectionPoolConfigTest`）：
  - `hc5ClientOnClasspath`：通过。
  - `poolParametersBoundFromRealConfigFile`：读真实 `application.yml` 经 `YamlPropertySourceLoader` 与 `Binder` 绑定 `FeignHttpClientProperties`，断言 200/50/300/SECONDS 通过。
  - `hc5EnabledKeyPresent`：断言 `spring.cloud.openfeign.httpclient.hc5.enabled` 键值为 `"true"` 通过。
  - 模块测试数：144 → 147 (+3）。
- **leaderboard-service**（`com.sportverify.leaderboard.config.FeignConnectionPoolConfigTest`）：
  - `hc5ClientOnClasspath`：通过。
  - `poolParametersBoundFromRealConfigFile`：读真实 `application.yml` 经 `YamlPropertySourceLoader` 与 `Binder` 绑定 `FeignHttpClientProperties`，断言 200/50/300/SECONDS 通过。
  - `hc5EnabledKeyPresent`：断言 `spring.cloud.openfeign.httpclient.hc5.enabled` 键值为 `"true"` 通过。
  - 模块测试数：59 → 62 (+3）。

### 5.3 离线依赖面核验读数
- 依赖构件实测在位（`ls -la` 留证）：
  - `.m2-repo/io/github/openfeign/feign-hc5/13.2.1/feign-hc5-13.2.1.jar`（15,507 字节）
  - `.m2-repo/org/apache/httpcomponents/client5/httpclient5/5.2.3/httpclient5-5.2.3.jar`（843,054 字节）
  - `.m2-repo/org/apache/httpcomponents/core5/httpcore5/5.2.4/httpcore5-5.2.4.jar`（855,013 字节）
  - `.m2-repo/org/apache/httpcomponents/core5/httpcore5-h2/5.2.4/httpcore5-h2-5.2.4.jar`（237,145 字节）
- 离线门禁首跑 `BUILD SUCCESS`，未发生缺失构件，未触发在线补料预案。

### 5.4 user-service 与 mapmatch-service 传递面说明项
- user-service 与 mapmatch-service 依赖 `sport-verify-api` 模块，因而传递获得 `feign-hc5` 依赖。
- 两服务均未声明 `@EnableFeignClients`，无 `@FeignClient` 客户端调用，因此运行期不受任何影响；按规格要求零触碰、不为其添加无用配置。

### 5.5 零 main Java 代码核验
- `git diff --stat e4490c5..b19d474` 显示恰 7 文件、+253 行：仅 pom.xml（+10）、三服务配置文件（+10/+9/+9）、三服务测试文件（+71/+72/+72）。
- 零 main Java 源码变动，装配全由 Spring Cloud 自动配置完成。

## 6. 逐门实测表

| 门 | 读数 | rc |
| --- | --- | --- |
| tasks.json 语法（收口态） | `python -m json.tool` 可解析；2 tasks 全 passes、steps 全 completed | 0 |
| 词面门（C-01 改动文件集 tracked 四形态） | 四形态全 ZERO_HIT（default / C / zh_CN.UTF-8 / C.UTF-8 均 rc=1）；探针三态 HIT rc=0；PROBE_GONE=yes | 1（预期非零） |
| 词面门（repo 全量，CI 权威 exclude 口径四形态） | 四形态全 ZERO_HIT（见 §4 D2） | 1（预期非零） |
| `git diff --check`（C-01 提交前 + C-02 提交前） | 干净，无空白错误 | 0 |
| 契约门在途 `--open TASK-189 --baseline=e4490c5`（C-01 提交前） | 判据 A 两件套齐 + 1 待办放行 + 判据 B 一致 | 0 |
| 契约门无参（C-02 后复跑） | 判据 A 两件套齐（TASK-189 含 handoff）+ 判据 B 清单一致 | 0 |
| typed-router.d.ts 零漂移 | `git diff --exit-code -- web/src/typed-router.d.ts` 无声 | 0 |
| offline 全量 `--mode=offline test`（C-01 实施态） | `36/41/117/137/147/62/10` = **550**，Failures/Errors/Skipped 全 0，BUILD SUCCESS（基线 541→550，+9） | 0 |
| `--static=record-service` | Checkstyle **811**（持平，未增），rc=1 为基线违规模块预期 | 1（预期） |
| token 29 项 | 开工 SUM=2030；C-01 后 SUM=2030；收口态实测 SUM=2030，只增不减（见 §7） | 只增不减 |
| 只改清单全等 | 实际改动集恰 §3 清单；`git status --porcelain` 收口后为空 | 全等 |

## 7. token 前后读数（口径 repo 全量 `git grep -cF`）

- **开工实测（SUM=2030，TASK-188 收口实测 2030 为参照）**：全量实测 29 项和为 **2030**。沿 TASK-188 N1 教训，本报告不枚举 29 项字面量，避免全仓计数自增失准。
- **C-01 后实测（SUM=2030）**：C-01 依赖引入、配置与测试均未引入受保护 token 字面量，29 项读数逐位与开工持平，SUM=2030。
- **C-02 收口复测（SUM=2030，只增不减）**：台账/handoff/PLAN 纯追加不引入任何受保护 token 字面量，收口态实测 repo 全量 `git grep -cF` 仍为 **SUM=2030**，只增不减。

## 8. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔 | `e4490c5186f306fb70ba79eac3f6a84da93a4c35`（`e4490c5`） | `docs(spec): 派发 TASK-189 Feign 连接池提案与任务书` |
| C-01 实施 | `b19d4745fb3623c2a864e4a1f68607a23fcdae0a`（`b19d474`） | `feat(api): Feign 传输层引入 Apache HttpClient5 连接池（TASK-189）` |
| C-02 台账收口 | 本笔（自指，见 §4 D1；显式哈希以本回传报告给出） | `docs(mailbox): 登记 TASK-189 Feign 连接池验收与台账闭环（TASK-189）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 1`、派发后 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧不推送。

## 9. 未覆盖项

1. **真实链路联调（可选）UNDETERMINED**：本机 Nacos + 双服务真实 Feign 调用的连接复用观察未展开（中间件全栈未起）。沿 TASK-182/185/186/187/188 口径登记 UNDETERMINED 不判失败；装配机制性与配置绑定已由判别式单测完全覆盖。
2. **连接池容量压测定参**（说明项，后续课题）：maxConnections=200 / maxConnectionsPerRoute=50 为官方默认值显式化，与高并发需求之匹配度由后续压测课题定参。
3. **性能数字零 claim**（纪律遵守）：本变更不写任何延迟/QPS/性能改善读数（无压测依据）。

## 10. 外部终验

推送后下一次外部门槛（GitHub Actions，第 31 次）CI 绿（web 档 + build 档 + 词面门）为外部终验；红则按签名归因（不带预设、禁重试刷绿）。新 run 号以文本登记，不扩受保护 token 集合。
