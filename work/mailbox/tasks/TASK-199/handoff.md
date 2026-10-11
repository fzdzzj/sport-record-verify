# TASK-199 fix-probe-nacos-isolation 探针测试环境隔离修复 —— 回传笔（handoff / 执行侧）

## 1. 一句话结论与三支裁决

round-2 改派轮单点修复闭环：C-01 恰 1 文件（UserProbeTest 新增 @TestConfiguration 提供 @Primary RedissonClient mock，照 VerifyProbeTest.ProbeTemplateConfig 范式，自动配置让位），CI 等效无中间件口径六探针全绿（user 档上下文真实 Started，与 CI 同构红被根除）；offline 609 逐位持平、static 双模块 rc=0、契约门双态 rc=0、token 29 项 SUM=2030 开工/C-01 后/收口三次全持平。round-1「仅补 Nacos 隔离属性」方案经 CI 权威日志与本地等效复现双证不可行（真首因 Redis 非 Nacos），round-1 零提交停工、本 v2 改派后按修订范围实施。

**三支裁决**：
1. **改派合规**：round-1 停止触发 §3.1（等效口径仍红且归因超出探针文件可隔离范畴的误判前提被勘误）——v2 任务书 §1 归因勘误（首因 Redisson 急切建连，Nacos 9848 为 gateway 段噪音、fail-fast=false 降级存活）即本修复的靶点，C-01 与 v2 §2 预注册设计逐字一致。
2. **例外通道未使用声明**：六探针 CI 等效验证中 record/verify/leaderboard/mapmatch/gateway 五档无任何隐藏环境依赖暴露，其余五探针文件零触碰（C-01 实际改动面 = 主 1 文件，未走例外通道上限 6 文件）。
3. **备选第二手段未启用**：Nacos 双禁用属性仅作为 CI 等效验证的环境模拟配方（SPRING_APPLICATION_JSON，非测试文件属性、非修复手段）；mock 后无 Nacos 致命依赖暴露，测试侧属性零改动（既有 `spring.cloud.nacos.discovery.fail-fast=false` 保留）。

## 2. 开工规程核验

| 项 | 实测 |
| --- | --- |
| 基线 | 改派笔 HEAD `007427f`（`007427fd255fa3cabd7dc70686631670e87cecbe`，父 `bf62f84` round-1 派发笔）；`origin/main...main` = `0 2`（恰 bf62f84 + 007427f 两笔未推送）；工作区净（porcelain EMPTY） |
| token 开工 | 29 项 tracked `git grep -cF` 权威口径 SUM=2030（清单 TASK-187 handoff §7，与 v2 任务书 §5 预期一致） |
| 四件通读 | 任务书 spec.md（v2 §0-§8）+ proposal.md + spec-delta.md + tasks.json 全读；§8 改派记录确认 round-1 全部读数沿用 |

## 3. 只改清单（与 `git diff --name-only` 逐条比对）

- user-service/src/test/java/com/sportverify/user/health/UserProbeTest.java（C-01 实施笔恰 1 文件）
- spec/changes/fix-probe-nacos-isolation/tasks.json（C-02 台账笔：任务勾选）
- work/mailbox/tasks/TASK-199/spec.md（C-02 台账笔：任务书 §7 回填）
- work/mailbox/tasks/TASK-199/handoff.md（C-02 台账笔：本文件新建）
- work/mailbox/PLAN.md（C-02 台账笔：纯追加）

## 4. round-1 停止与改派记录（v2 §8 沿用留证）

- round-1 执行侧开工基线核验（HEAD=bf62f84、工作区净、token SUM=2030）后，CI 日志（run 38113472212，4699 行）全量复核发现派发任务书 §1 归因与权威日志不符：全部 `9848 Connection refused` 属 gateway 段噪音（05:02:42-45）且 gateway 随后 `Started GatewayProbeTest` 正常（fail-fast=false 降级路径 CI 实跑存活）；user 档 3 例首因链 `healthEndpointGroupMembershipValidator → healthContributorRegistry → RedisReactiveHealthContributorAutoConfiguration → redissonConnectionFactory → redisson`（RedissonAutoConfigurationV2 急切建连）→ `RedisConnectionException: Unable to connect to Redis server: 127.0.0.1:6379`。
- round-1 本地等效复现（Nacos 双禁 + MySQL/Redis/RocketMQ 指向不可达地址）user 档 130 例 3 错与 CI 逐位一致、首因链同构，证伪「仅补 Nacos 隔离属性」方案，触发停止条件 §3.1，零提交停工（tasks.json 未打勾、台账未动，避免无提交脏树）。
- 本轮（round-2）按 v2 任务书改派范围实施：白名单收窄至 UserProbeTest 单文件 + 定向解冻（@TestConfiguration 与 @Primary mock）+ 六探针等效验证全覆盖义务，见 §5/§6。

## 5. 实施读数（C-01 改动口径与次生断点处置）

- **改动口径**（`git diff --stat` 恰 1 文件 30 insertions / 2 deletions）：+4 import（TestConfiguration/Bean/Primary/静态 mock）；javadoc 偏差登记段新增（TASK-199 redis 健康贡献者急切建连勘误，照 VerifyProbeTest 偏差登记范式）+ 首段「db/redis 探针 DOWN 容忍」最小修正为「db 探针」（redis 侧由偏差登记段承接，属文档勘误非断言改动）；+ProbeRedisConfig 内嵌 @TestConfiguration（@Bean @Primary RedissonClient mock 单 bean）。既有 3 断言方法（liveness UP / readiness 端点可达 / readiness 组结构资源断言）与既有 @Import(StompConnectAuthInterceptor)、@SpringBootTest 属性逐字零改动。
- **次生断点处置**：Redis mock 后上下文继续加载，**未暴露任何次生断点**——user 主源四 bean（FriendService/LikeEventConsumer/NotificationEventConsumer/NotificationPushRelay）注入 RedissonClient 均由 mock 供豆（lazy-initialization 下业务 bean 懒加载，MQ 监听容器对不可达 nameserver 为后台重试不致命，round-1 等效复现与本轮等效验证双证）；user 主源无 RocketMQTemplate 生产端（无 fastjson 类加载断点）；停止条件 §3.1/§3.5 未触发。
- **CI 等效读数锚点**：UserProbeTest 上下文 `Started UserProbeTest in 16.187 seconds`（等效配方下真实起上下文），3 例全绿——CI run 38113472212 的 3 例同构红被根除。

## 6. 六探针 CI 等效验证（方法与逐个读数）

**方法**（红线 §0-3，round-1 配方沿用）：以属性覆盖模拟无 Nacos/Redis/MySQL/RocketMQ 环境——`MYSQL_PORT=1`、`POSTGRES_PORT=1`、`SPRING_APPLICATION_JSON={"spring.cloud.nacos.discovery.enabled":false,"spring.cloud.nacos.config.enabled":false,"spring.data.redis.port":1,"rocketmq.name-server":"127.0.0.1:1"}`，经唯一验证入口 `bash scripts/verify/mvn-verify.sh --pl <模块> test` 逐档实跑（offline 模式）。共享中间件容器全程未停未触；配方把全部中间件指向不可达地址，本机中间件实况不构成任何 PASS 依据（若隔离补配失效，上下文即红）。record/verify/leaderboard/mapmatch 四档为 CI 从未跑到档（CI #39 fail-fast 止于 user），本轮等效读数即其等效终验依据（v2 §6 外部终验条款）；user/gateway 两档 CI 实跑在案，等效读数为同口径留证。

| 模块 | 模块总读数 rc | 探针专项 | 探针上下文真实启动留证 |
| --- | --- | --- | --- |
| user-service | rc=0，130/0/0/0 | UserProbeTest 3/0/0/0（80.32 s） | `Started UserProbeTest in 16.187 seconds` |
| gateway-service | rc=0，48/0/0/0 | GatewayProbeTest 4/0/0/0（13.10 s） | `Started GatewayProbeTest in 7.216 seconds` |
| record-service | rc=0，151/0/0/0 | RecordProbeTest 3/0/0/0（84.17 s） | `Started RecordProbeTest in 19.419 seconds` |
| verify-service | rc=0，159/0/0/0 | VerifyProbeTest 3/0/0/0（97.10 s） | `Started VerifyProbeTest in 30.863 seconds` |
| leaderboard-service | rc=0，70/0/0/0 | LeaderboardProbeTest 3/0/0/0（82.03 s） | `Started LeaderboardProbeTest in 18.842 seconds` |
| mapmatch-service | rc=0，15/0/0/0 | MapMatchProbeTest 3/0/0/0（78.69 s） | `Started MapMatchProbeTest in 12.043 seconds` |

模块总读数与 offline 收口真值逐位一致（130/48/151/159/70/15），六档全部以「等效无中间件」口径真实起上下文并全绿。

## 7. 逐门实测表

| 门禁 | 时点 | 读数 | 结论 |
| --- | --- | --- | --- |
| token | 开工 | 29 项 tracked `git grep -cF` SUM=2030（v2 §5 预期一致） | 绿 |
| 六探针等效 | C-01 后 | §6 表六档 rc=0 全绿（user 修复靶档 + 五档 CI 未跑到档等效留证） | 绿 |
| offline 全量 | C-01 后 | `--mode=offline test` rc=0，逐位 36/48/130/151/159/70/15 = 609（与 TASK-198 收口真值逐位持平，不减不增） | 绿 |
| static record-service | C-01 后 | `--static=record-service` rc=0（基线确认，本课题不触该模块） | 绿 |
| static leaderboard-service | C-01 后 | `--static=leaderboard-service` rc=0（基线确认） | 绿 |
| 契约门在途态 | C-01 后、台账四件在树未提交时 | `--open TASK-199 --baseline=007427f` rc=0（判据 A 两件套齐 + 判据 B 真实比对：清单 5 文件与实际改动集全等） | 绿 |
| 契约门无参态 | C-02 提交后复跑 | rc=0（读数时点显式登记，沿 TASK-196 教训：提交前工作树窗口 rc=1 属时点窗口 artifact） | 绿 |
| token 收口 | C-02 后 | 29 项 tracked 口径复测 SUM=2030，只增不减 | 绿 |
| 只改清单 | 收口 | C-01 恰 1 文件（UserProbeTest.java）；C-02 恰 4 台账文件；§3 清单与 `git diff --name-only 007427f` + untracked 全等；porcelain 收口 EMPTY | 全等 |
| diff --check | C-01 前/后 | 干净（无行尾空白/冲突标记） | 干净 |
| 行尾 | 收口 | 修改文件 UserProbeTest.java blob 级既有 LF 保持（`git ls-files --eol` i/lf w/lf，工作区 0 CR）；新文件 handoff.md 提交 blob 级 pure LF + 末尾换行（`git show HEAD:<file>` 复验 0 CR） | 合规 |
| 零越界 | 收口 | 零触碰 src/main/application 配置/pom/ci.yml/docker-compose*/scripts/verify/*/web/sql/其余测试文件；断言语义与既有 @Import 零改动 | 合规 |

## 8. 偏差登记

1. **D1 javadoc 首段最小修正（登记性改动）**：首段「db/redis 探针在无中间件环境下由 HealthIndicator 捕获连接异常返回 DOWN，不阻断启动」对 redis 侧不成立（round-1 D2 勘误），修正为「db 探针」并以括注指向新增偏差登记段；属 javadoc 文档勘误，断言语义/注解/结构零改动（红线 §0-2 判定：解冻范围为 @TestConfiguration 与 @Primary mock，javadoc 勘误系同文件文档口径自洽所需，逐字 diff 见 C-01）。
2. **D2 tasks.json「词面门」口径归并**：tasks.json 步骤 3 含「词面门」，v2 任务书 §6 门禁结构无独立词面门（冲突以任务书为准）——按 token 29 项 tracked `git grep -cF` 字面量口径执行并三次实测持平（TASK-164 词面门为任务特定正则扫描，非通用脚本）；tasks.json 照常勾选，豁免依据即本条。
3. **D3 等效验证 scratch 清理**：等效验证原始日志与本轮测量脚本临时落于 work/tmp199/（*.log 为 gitignore 载体；测量脚本 4 件为未跟踪 scratch），读数提取入本 handoff 后整目录删除——保证契约门判据 B 真实比对时 ACTUAL 无未跟踪残留（留证以本 handoff §5/§6/§7 读数为准）。
4. **D4 masked-errata 沿用**：round-1 归因勘误的被移除表述在台账内按既有掩码规范书写（「误归因（D1）」），逐字原文仅存于改派笔 007427f 提交正文——本笔台账未复活任何被移除字符串（token 收口复测 SUM=2030 佐证零复活）。

## 9. 提交表（显式哈希，禁时效指针）

| 笔 | 提交 | 主题（`-F` 消息文件无 BOM） |
| --- | --- | --- |
| 派发笔（round-1） | `bf62f84e9931dfc40829cb68776969899eca994a`（`bf62f84`） | `docs(spec): dispatch TASK-199 probe test nacos isolation fix` |
| 改派笔（round-2） | `007427fd255fa3cabd7dc70686631670e87cecbe`（`007427f`） | `docs(spec): 改派TASK-199归因勘误与user探针Redis隔离补配（TASK-199）` |
| C-01 实施 | `2ebba23b01a5068e33d630d48a85fd09ad5a0d64`（`2ebba23`） | `test(obs): user探针Redisson隔离补配修复CI环境依赖泄漏（TASK-199）` |
| C-02 台账收口 | 本笔（自指，显式哈希以回传报告给出） | `docs(mailbox): 登记TASK-199探针隔离修复验收与台账闭环（TASK-199）` |

父锚定：`git rev-list --left-right --count origin/main...main` 开工 `0 2`、C-01 后 `0 3`、C-02 后 `0 4`（对外显式计数）。push 由指导侧另行授权执行，执行侧不推送不建 PR。

## 10. 未覆盖项与后续项

- **外部终验**：push 后第 40 次外部门槛 CI 全绿——user 档探针必须实跑全绿（CI 从 run 38113472212 的 3 例红到本笔根除）；record/verify/leaderboard/mapmatch 四档若因前序模块红仍 SKIPPED，以 §6 等效读数登记为等效终验依据不阻断；若实跑红则按签名归因禁重试刷绿。
- **后续项（既有登记不变）**：StompConnectAuthInterceptor 生产 bean 注册缺陷（@Import 临时供 bean 维持，D3 类课题另立）；rocketmq-spring 2.3.1 fastjson 类加载兼容（record/verify/leaderboard 仍依赖 @Primary 模板 mock，user 无生产端不受累）；两者均列 PLAN 后续项，本课题未搭车。
- 词面门独立脚本化（若后续任务书再引用，建议指明口径归属 token 测量或另立脚本）——观察项，不构成本课题义务。
