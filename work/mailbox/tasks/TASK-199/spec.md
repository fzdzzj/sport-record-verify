# 任务书：探针测试环境隔离修复 v2（TASK-199 / fix-probe-nacos-isolation 改派）

本版为 round-2 改派修订版。round-1 预注册「仅补 Nacos 隔离属性」方案经 CI 权威日志与本地等效复现双证不可行（停止条件触发，零提交停工），归因勘误与修订范围见 §1/§8，冲突以本版为准。唯一指令源：本任务书 + spec/changes/fix-probe-nacos-isolation/。全程 bash 经 `D:\git\Git\bin\bash.exe`；命令行参数禁携中文；唯一验证入口 scripts/verify/mvn-verify.sh。

## §0 红线（八条，违反即 FAILED）

1. **白名单制改动面**：C-01 主改动 `user-service/src/test/java/com/sportverify/user/health/UserProbeTest.java`（1 文件）；其余五探针不触碰（依据固化见 §1.3）；例外通道——六探针 CI 等效验证若暴露其余探针隐藏环境依赖，允许以同范式最小隔离补配对应探针文件，C-01 上限仍为至多 6 个 `*ProbeTest.java`（逐个登记依据）。C-02 恰 4 台账文件（§4）。零触碰：一切 src/main、application 配置、pom、ci.yml、docker-compose*、scripts/verify/*、web、sql、其余测试文件。
2. **断言零改动 + accommodation 定向解冻**：既有断言语义（liveness UP / readiness 端点可达 / readiness 组结构静态断言 / 禁断言 db-redis UP）零改动；既有 @Import(StompConnectAuthInterceptor) 零改动；定向解冻——允许 UserProbeTest（及例外通道内的其余探针）新增 @TestConfiguration 与 @Primary mock bean（RedissonClient 为主，次生断点同范式扩展），照 VerifyProbeTest.ProbeTemplateConfig 范式。
3. **CI 等效验证义务**：以属性覆盖（不可达地址/禁用开关）模拟无 Nacos/Redis/MySQL/RocketMQ 环境逐服务验证六探针全绿；禁止停共享中间件容器；不以本机中间件在跑作任何 PASS 依据；无法构建等效环境 → 停止条件 2。
4. **offline 逐位不减**：609 逐位不减不增（mock 补配不加用例）；既有用例零回归。
5. **static 触源门**：record/leaderboard 测试源在 checkstyle 扫描范围，改动后 `--static=record-service` 与 `--static=leaderboard-service` 均 rc=0（本课题预期不触碰该两模块，跑门为基线确认）。
6. **token 29 项只增不减**：tracked `git grep -cF` 权威口径；台账纯追加；handoff 含「只改清单」小节（契约门 --open 态走真实判据 B 比对）。
7. **提交纪律**：两笔 `type(scope): description (TASK-199)` 未推送不建 PR；新文件/行尾规范照旧；MSYS sed -i 禁用于 tracked 文件；CI 红禁重试刷绿。
8. **零环境泄漏读数**：handoff 须登记 CI 等效验证方法（属性覆盖口径）与六探针逐个读数；不以「本机中间件在跑」作为任何 PASS 依据（TASK-198 假绿教训）。

## §1 归因基线（v2 勘误重写，CI 日志权威留证）

- **§1.1 user 档首因**：run 38113472212 中 UserProbeTest 3 例 `Failed to load ApplicationContext`，首因链 `healthEndpointGroupMembershipValidator → healthContributorRegistry → RedisReactiveHealthContributorAutoConfiguration → redissonConnectionFactory → redisson`（RedissonAutoConfigurationV2 急切建连）→ `RedisConnectionException: Unable to connect to Redis server: 127.0.0.1:6379`。`spring.main.lazy-initialization=true` 不覆盖 actuator 健康注册链的急切依赖。
- **§1.2 Nacos 勘误**：全部 `9848 Connection refused` 属 gateway 段噪音（05:02:42–45），gateway 随后 `Started GatewayProbeTest` 正常——fail-fast=false 降级路径在 CI 存活。round-1 任务书「首因 Nacos gRPC 9848」为 gateway 段噪音误归因（D1）。
- **§1.3 根因定性与其余五探针不动依据**：TASK-198 批3 为 verify/leaderboard 配 @Primary Redisson mock、record 以 ProbeApp 定制上下文全隔离，唯 user 漏配（批3 测试设计缺陷）；user 探针 javadoc「db/redis 探针无中间件返回 DOWN 不阻断启动」假设对 Redisson 急切 bean 不成立（D2）。mapmatch 无 Redis 且 MySQL 懒加载 DOWN 容忍；gateway CI 实跑绿留证。
- **§1.4 等效复现与假绿定性**：round-1 本地等效复现（Nacos 双禁 + MySQL/Redis/RocketMQ 指向不可达地址）user 档 130 例 3 错与 CI 逐位一致、首因同构，余 127 例全绿——证伪「仅补 Nacos 属性」方案。本地 offline 全绿为本机 Redis/MySQL/Nacos 均在跑的环境依赖泄漏假绿，非 flaky。

## §2 预注册设计（v2）

- **主修复**：UserProbeTest 新增 @TestConfiguration（范式照 VerifyProbeTest.ProbeTemplateConfig）提供 @Primary RedissonClient mock（Mockito），自动配置 @ConditionalOnMissingBean 让位；既有 @Import 与断言零改动。
- **次生断点预案**：Redis mock 后上下文继续加载，若暴露其它急切中间件 bean（MQ 类等），同文件内同范式 @Primary mock 补配并登记；超出探针文件可隔离范畴 → 停止条件 1。
- **备选第二手段**：Nacos 双禁用属性（spring.cloud.nacos.discovery.enabled=false / spring.cloud.nacos.config.enabled=false）仅当等效验证证明 mock 后仍有 Nacos 致命依赖时启用（断言为静态资源文件断言，属性扩展不影响断言语义）。
- **不搭车**：StompConnectAuthInterceptor 生产 bean 注册缺陷维持 @Import 临时供 bean（已列 PLAN 后续项）；D3 类课题另立。

## §3 停止条件（v2）

1. mock 补配后等效口径仍红且归因超出探针测试文件内可隔离范畴（需改 src/main/配置/pom）；
2. 无法构建 CI 等效验证环境（须申报，不得以本机中间件替代）；
3. 需触碰白名单外文件（含例外通道超出 6 探针文件上限）；
4. offline 逐位减少或既有用例回归；
5. 发现生产缺陷须先行修复（含 StompConnectAuthInterceptor bean 注册、rocketmq-spring fastjson 类加载兼容——若 user 探针暴露同类主源级缺陷）。

## §4 白名单（只改清单 v2）

- **C-01 实施笔**（主 1 文件，例外通道上限 6）：`user-service/src/test/java/com/sportverify/user/health/UserProbeTest.java`；例外通道：`record-service/.../health/RecordProbeTest.java`、`verify-service/.../health/VerifyProbeTest.java`、`leaderboard-service/.../health/LeaderboardProbeTest.java`、`mapmatch-service/.../health/MapMatchProbeTest.java`、`gateway-service/.../GatewayProbeTest.java`（仅当其等效验证暴露隐藏依赖，逐个登记依据）
- **C-02 台账笔**（恰 4 文件）：spec/changes/fix-probe-nacos-isolation/tasks.json、work/mailbox/tasks/TASK-199/spec.md（§7 回填）、work/mailbox/tasks/TASK-199/handoff.md（新建，含「只改清单」小节）、work/mailbox/PLAN.md（纯追加）
- 提交主题：C-01 `test(obs): user探针Redisson隔离补配修复CI环境依赖泄漏（TASK-199）`；C-02 `docs(mailbox): 登记TASK-199探针隔离修复验收与台账闭环（TASK-199）`

## §5 受保护 token（29 项）

开工实测 SUM（预期 2030）→ 收口复测只增不减；清单见 TASK-187 handoff §7。

## §6 门禁结构（v2）

- **开工规程**：HEAD=改派笔（git log 现读）、工作区净、token 开工实测
- **收口门禁**：offline 609 逐位 + static 双模块 + 契约门双态（--open TASK-199 --baseline=<改派笔哈希>，判据 B 走真实比对）+ token 复测 + 只改清单全等 + 零越界 + 台账纯追加
- **外部终验**：push 后第 40 次 CI 全绿（user 档探针必须实跑全绿；record/verify/leaderboard/mapmatch 四档若仍 SKIPPED，以本地等效读数登记为等效终验依据，不阻断；若实跑红则按签名归因禁重试）

## §7 收口记录（执行侧 C-02 纯追加回填）

### §7.1 提交记录

- 派发笔（round-1）：`bf62f84`
- 改派笔（round-2）：`（C-02 回填显式哈希）`
- C-01 实施笔：`（回填显式哈希）`
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff 给出）`

### §7.2 实施读数（回填：user 探针改动口径与次生断点处置 / 六探针 CI 等效验证方法与逐个读数 / 例外通道使用情况或未使用声明 / 备选第二手段启用情况）

### §7.3 门禁读数（回填：offline 逐位 / static 双模块 / 契约门双态 / token / 只改清单 / diff --check / 行尾）

## §8 改派记录（round-1 停止归档）

- round-1 执行侧于开工基线核验（HEAD=bf62f84、工作区净、token SUM=2030）后，CI 日志全量复核（4699 行）发现任务书 §1 归因与权威日志不符：真首因 Redis 非 Nacos（D1）；随即以属性覆盖构建 CI 等效环境复现（Nacos 双禁 + 中间件全指不可达），user 档 130 例 3 错与 CI 逐位一致、首因链同构，证明「仅补 Nacos 隔离属性」不可行，触发 round-1 停止条件 §3.1，零提交停工（D3：tasks.json 未打勾、台账未动，避免无提交脏树）。
- 本 v2 由指导侧裁定改派：归因勘误入 §1、白名单收窄与 §0.2 定向解冻入 §0/§2/§4、停止条件修订入 §3、六探针等效验证全覆盖入 §0.3/§6。round-1 全部读数（CI 日志复核、六探针 accommodation 排查、等效复现读数）作为 v2 开工依据沿用。
