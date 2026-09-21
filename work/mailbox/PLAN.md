# sports 项目优化计划

## 当前进度
一句话：**主 agent 撤回本会话此前的全部"通过"判定**——验收用错了 Maven 口径（漏 `-s .mvn-settings.xml`），换规范口径后 leaderboard-service 连依赖都解析不了。

## 收口清单（每条验收记录必备，缺一不可收口）

1. **绑定修订**：记录结论所对应的 commit id（不是"最新提交"这种相对说法）。
2. **绑定门槛来源**：写清该结论出自哪一道门槛——外部门槛写 CI run 编号与其结果状态；
   本地实跑写"一次 `bash scripts/verify/mvn-verify.sh --mode=online <阶段>` 实跑结论"（含模块汇总数字）。
   两种来源等价可用，因为推送属外部写操作、需单独授权，**不得为凑门槛来源擅自 push**。
3. **显式标注是否到达外部门槛**：结论只来自本地且当前修订未推送时，记录里必须写明"未达外部门槛"，
   防止后续会话把本地绿读成已过门槛。
4. **未覆盖不得写成通过**：需要真实中间件的用例被 skip 时（如 `--it` 缺 `TASK108_IT_URL/USER/PASSWORD`），
   按"未覆盖"记录，并附该次跳过的判据输出。
5. **依赖来源不一致时以 online 为准**：offline 与 online 依赖集不同，结论冲突时按 online 记，
   并把差异与缺失构件一并记录（这是 D12 那类假绿的判别式）。

验收命令的拼写不再由本文件承载：唯一出处是 `scripts/verify/mvn-verify.sh`（退出码语义与
`--mode` 判据见 `scripts/verify/README.md`）。下方 D12 那句"今后验收口径固定"作为作废史原文保留，
其参数组合现由该脚本的 `--mode=offline` 表达。

## 验收记录：`add-controlled-verify-entrypoint`（2026-09-21，按上方清单写法）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | `fe76219` 入口脚本 · `2080d40` CI 改调入口 · `8c58cfc` compose/镜像门槛 · `7adeeaa` IT 与前端接线 · `6daf863` 词面清理+扩围 · `ee3f594` env 样例+清单 · `5c68a80` 第 7 阶段取证 · `a18f19a` 订正 `--it` 的 surefire 属性名 · `b1afb84` 忽略评审产物 · `7fb7c1d` compose 占位修复与本行证据订正 · 本条记录所在的收口提交 |
| 门槛来源 | 外部门槛 run `35571634401`（`1fbf3eb`，1m48s，**build 与 web 两个 job 全绿**，15 个步骤无一 skip）：入口 `--mode=online` 46s 通过；compose 占位与 `config -q` 通过；代表镜像 47s 真建成（日志含 Maven `BUILD SUCCESS` 与 `writing image sha256:4caa1f83…`）；扩围后的词面自检首次真实执行且范围内 0 命中；web job 四步（corepack pnpm@10.25.0、frozen-lockfile、type-check、build）全绿。首跑 run `35570779585` 的红与根因记在下行与本表下方。本地补充：`--mode=online` 与 `--mode=offline` 各一次全量 `clean verify` 均 rc=0 / 281（17/19/31/78/81/49/6），两模式结论一致 |
| 其他判别式来源 | 本地实跑：`--it` 在 scratch 库 3/3 通过、缺 env 时 Skipped: 3（记为未覆盖）；代表镜像 compose build rc=0，Dockerfile 退回部分 COPY 时 rc=1；6 份 Dockerfile 逐份 `docker build` 全 OK；词面自检扩围后范围内 0 命中；compose 步骤的红绿对为「无 .env rc=1 / 放占位 rc=0」两条实测 |
| 结果 | 8 个任务 23 个 step 全 `completed`，各任务 `passes=true`；外部门槛在 `1fbf3eb` 上为绿 |
| 是否到达外部门槛 | **已到达**。推送两次：`2cfa16c..b1afb84`（26 个提交）触发首跑 `35570779585`，build job 红在 Compose files parse check——根因是 compose 六个服务声明 `env_file: [.env]` 而 `.env` 按约定不入库，干净检出下解析阶段就失败，其后的镜像构建与扩围自检被 skip；提案原写的"`config -q` HEAD 实测 0 退出"是在有 `.env` 的开发机上量的，属本变更要堵的"机器态当仓库态"同一类错误。修法为在该步前补 `cp scripts/verify/env.example .env`（不把 compose 的 `env_file` 改成可选，以保留 `docker compose up` 缺 `.env` 时的硬防护），本地红绿对：无 `.env` rc=1 / 放占位 rc=0。第二次推送 `b1afb84..1fbf3eb` 触发 `35571634401`，四条新步骤与 web job 首次全部真实执行、无一 skip |
| 未覆盖 | `LeaderboardDailySummaryMapperMysqlIT` 之外的真中间件路径（Redis L2 真序列化、RocketMQ 真 broker、全栈 `/daily` 端到端）本次不新增覆盖 |

## ⚠️ D12：验收口径作废（2026-09-20 自查发现）

本会话此前所有复跑用的是 `mvn -B -ntp -pl <模块> -am test`，**没带 `-s .mvn-settings.xml`**，
类路径因此来自默认 `~/.m2`，而 `.mvn-settings.xml:7` 明确把 localRepository 指到仓内 `.m2-repo`。

规范口径现场重跑 `mvn -B -ntp -o -s .mvn-settings.xml test`：

```
common 17 / gateway 16 / user 31 / record 78 / verify 81  → 全绿
sport-verify-leaderboard-service: Could not resolve dependencies
  io.lettuce:lettuce-core:jar:6.3.2.RELEASE (absent)
  com.xuxueli:xxl-job-core:jar:2.4.0        (absent)
mapmatch-service → SKIPPED
BUILD FAILURE
```

根因（均在 `.m2-repo` 内实地核过）：

- 本项目 Redis 栈是 `redisson-spring-boot-starter:3.27.2` + `redisson-spring-data-32` + `spring-data-redis:3.2.4`，
  **仓内没有 lettuce**（`find .m2-repo -ipath "*lettuce*" -name "*.jar"` 命中 0）。
  TASK-002 新加的 `spring-boot-starter-data-redis` 必然拖进 lettuce → 离线不可解。
- TASK-004 新加的 `xxl-job-core:2.4.0` 仓内不存在——与当初给 scheduler 文件加 `<excludes>` 同根。

后果：**TASK-002/003/005 的"通过"、39/39 绿、变异验证结论一律作废**（结论大概率仍成立，
但凭据不合规，须在规范口径下重取）；`37/39/268` 这些数字都产自错误依赖集。
P0-2 不受影响：规范口径 `dependency:tree` 显示 record-service 只有
feign-core/feign-slf4j/feign-form，**无 feign-hc5**，孤儿测试删得对。

今后验收口径固定：`mvn -B -ntp -o -s .mvn-settings.xml [-pl <模块> -am] test`。

## D13：P0-3 已由主 agent 规范口径验收通过

`mvn -B -ntp -o -s .mvn-settings.xml test` → **BUILD SUCCESS**，模块合计
common 17 + gateway 16 + user 31 + record 78 + verify 81 + leaderboard 39 + mapmatch 6 = **268**。
pom 已无 `spring-boot-starter-data-redis` 与 `xxl-job-core`（Redis 走已声明的
`redisson-spring-boot-starter`，其自带 spring-data-redis 在 `.m2-repo` 内）；
`scheduler/` 目录消失，`XxlJob`/`DailyLeaderboardReportJob`/`xxl` 在 java+xml+yml 命中 **0**；
`work/mailbox/rollback/TASK-004-xxl.patch` 在位。**D12 作废的数字自本条起重取，均为规范口径产物。**

## D14：`@Primary` 无守卫（主 agent 亲种变异确认），TASK-106 批准派发

摘掉 `CacheConfig.java:55` 的 `@Primary` → `-Dtest=CacheConfigTest` **仍 7/7 绿 / BUILD SUCCESS**；
加回后 39/39 绿、`@Primary` 命中 1。即这条注解当前无任何测试可观测——我上轮的"变异验证"只覆盖
bean 改名、未覆盖 `@Primary`，TASK-106 对这一点的批评成立。

预先裁定其退路：**接受"人造歧义"式测法**（新 runner 里挂第二个 `ConcurrentMapCacheManager`，
断言按类型解析 `isSameAs` 按名取到的那个）。理由是生产上下文今天确实只有一个 CacheManager bean，
`@Primary` 守的是"将来有人再加一个"的形状不变量，除造第二个 bean 外没有别的观测手段。
若仍补不出能变红的测试，按 spec 停下写「待主 agent 决定」，届时选**删掉 `@Primary`**——不留无人看守的注解。

<!-- 以下为口径作废前的旧证据，保留仅作追溯 -->

其余核实：`grep -ri "rabbit|amqp"` 在 src/pom/yml/bak 全仓命中 **0**；
`consumer/`、`event/` 目录已不存在；`work/mailbox/rollback/TASK-003-rabbitmq.patch` 实到 21998 字节；
唯一的 `CacheEvict|CachePut` 命中是测试方法名 `overallCachePutGetRoundTrip`，非注解——回传称"无失效注解"属实。

## 拍板决议

| # | 决议 | 状态 |
|---|---|---|
| D1 | 回滚 TASK-003 的 RabbitMQ 栈（0 生产方、0 broker、逻辑空桩） | ✅ 已执行并验收 |
| D2 | TASK-006 采纳其自带方案 A（保持 RocketMQ），任务作废 | ✅ 已写入 spec |
| D3 | "异步消息"方向前提作废：消费侧 `mq/LeaderboardEventConsumer`、发布侧 `VerifyOutboxRelay` 早已存在 | ✅ 已写入 spec |
| D4 | TASK-002 缓存必须真接通：只暴露一个 `@Primary hierarchicalCacheManager`，删裸 `ObjectMapper` bean | ✅ 已执行并验收 |
| D5 | **TASK-004 与 TASK-003 同病**：`@XxlJob` 桩方法体只有 `Thread.sleep`+TODO，全仓无 executor bean、3 个 compose 文件 xxl 命中 **0**；而真实定时任务早就在跑——`LeaderboardApplication.java:24` 已 `@EnableScheduling`，`LeaderboardService.java:323` 的 `@Scheduled settleAndReconcile()` 做的是真结算（`selectActiveSummaries`→日汇总→`markSettled`），record-service 另有 3 处 `@Scheduled` | ⏸ 待用户拍板：删桩、把"每日报表"并进现有 `@Scheduled` 链路 |
| D6 | TASK-005 的 P0 已修并验收：删自建 `transactionManager()` 与裸 `@EnableTransactionManagement`，交回 Boot 自动装配 | ✅ 主 agent 复跑 39/39 绿 |
| D7 | **不补 `@Transactional`**：子 agent 的裁定经主 agent 核对 ADR 属实——`docs/adr/0009-事务边界.md:37-38` 明列 `applyVerified`/`rollbackOnRejected` 禁止把 Redis 纳入事务、`settleAndReconcile` 保持最终一致，`:53` 禁止批量铺注解。切面因该模块 `@Transactional` 恒为 0 而永久空切 | ✅ 结论成立 |
| D8 | 切面本体**删除**（永久空切 + `getArgs()` 落 INFO 有 PII 风险且 TASK-023 脱敏未落；Spring 自身在 DEBUG 已打 begin/commit/rollback）。但它那条"一旦 `LeaderboardService`/`LeaderboardController` 被代理就变红"的**守卫语义要保留**，改名成 ADR-0009 边界哨兵，不随切面一起丢 | ✅ 已执行并验收（D11） |
| D9 | `TransactionConfig.java` 现为空 `@Configuration`（Javadoc 与 `TransactionConfigTest` 顶部注释重复）→ **删文件**，测试随之简化为"自动装配的 TM 真绑 DataSource"一段，守卫不丢。文件 untracked，删前存 patch | ✅ 已执行并验收（D11） |
| D10 | 用例数按 **39** 收，不强凑 38：删的是两个 main 类（不携带用例），两个 test 类都保留才满足"守卫一条不丢"。子 agent 拒绝凑数、停下回传，行为正确 | ✅ 已裁定 |
| D11 | D8/D9 验收证据（主 agent 亲自复核）：`config/` 只剩 `CacheConfig.java`、`aop/` 目录（main 与 test）均已消失、哨兵两条 `isAopProxy(...).isFalse()` 带 ADR-0009 文案在位、`src/main` 内 `Transactional` 命中 **0**、`TASK-005-aspect.patch` 实到 4564 字节、复跑 `Tests run: 39, Failures: 0 / BUILD SUCCESS`。另接受其两点：`as(...)` 改指 ADR-0009、`TransactionConfigTest` 改名 `TransactionManagerWiringTest`（类名不得指向已删除的类） | ✅ |

## 已核实缺陷（回滚/修复后剩余）

| 位置 | 缺陷 | 状态 |
|---|---|---|
| **record/verify 两模块 `testCompile` 红** | 两个 untracked 的 `FeignHttpClientPoolConfigTest.java`（`record-service/.../config/`、`verify-service/.../config/`）import `feign.hc5.*`，全仓 pom 声明 hc5/httpclient5 命中 **0**；主 agent 实跑：`程序包feign.hc5不存在` / `BUILD FAILURE`。且其断言的 `spring.cloud.openfeign.httpclient.*` 键在配置文件命中 **0** | **P0，待修** |
| **`leaderboard-service/pom.xml:126-130`** | maven-compiler-plugin 用 `<excludes>` 把 `**/scheduler/DailyLeaderboardReportJob.java` 排除编译（该文件 import 的是 `xxl.job.core.*`，真实包名应为 `com.xxl.job.core`，且依赖在本地仓不存在）。后果：TASK-004 产物从未进入构建，`xxl.job.enabled: true` 与整套 executor 配置（yml:125-138）承诺了一个根本不编译的调度器 | **P0，待拍板 D5** |
| 两个测试是**孤儿** | `FeignHttpClientPoolConfigTest` 只存在于 record/verify 的 test 目录，**全仓无同名生产类**；`grep FeignHttpClientPoolConfig` 命中的除这两个测试外只有 `work/mailbox/tasks/TASK-016/spec.md`。断言的 `spring.cloud.openfeign.httpclient.*` 键在配置命中 0 | 并入 P0-2 |
| `common/pom.xml`（+7 行，未提交） | TASK-005 为切面加的 `spring-boot-starter-aop`：切面已按 D8 删除，此依赖现无人使用（`spring-boot-starter-jdbc` 需单独判，`GlobalExceptionHandler` 捕获 `DuplicateKeyException` 要用 spring-tx） | 待处置 |
| `user-service/application.yml`（+14 行，未提交） | 注释掉的 XXL-JOB 配置块，写着"待主 agent 配置"，且 appname 是 `record-service-executor` 却落在 **user-service** 的配置里 | 并入 D5 |
| `leaderboard-service/pom.xml` | `spring-cloud-starter-circuitbreaker-resilience4j` 本模块 src 零引用（TASK-011 目标是 mapmatch-service） | 待定归属 |
| `TransactionLogAspect` | 切面存在，但无测试证明真的拦截了事务 | 待补证据 |
| Redis L2 真实序列化 | `JdkSerializationRedisSerializer` 存 `List<LeaderboardDTO>` 的往返只在内存 manager 上验过，无真 Redis 凭据 | 需集成测试 |
| TASK-003 RabbitMQ 的 5 项硬缺陷 | `x-max-length=3`、DLX 成环、manual ack 无 Channel、`deleteDedupKey` 无调用方、重试键与 RocketMQ 侧共用 | 随 D1 消失 |

## P2 前提体检结果（`work/mailbox/triage.md`，15 个任务）

口径修正：007、010、012~017、019~025 展开是 **15** 个（我在派发词里误写 18），且 **TASK-025 无 spec.md**，实读 14 份。

| 判定 | 任务 | 主 agent 复核 |
|---|---|---|
| 前提不成立（8） | 007、012、014、016、021、023、024、025 | 抽验 021/024 两条成立：`RuleCacheService` 确有 `EMPTY`/`EMPTY_JSON` 空值哨兵（`:41-50`，防穿透）、Redisson `RLock` 互斥重建（`:52-53,130-139`，防击穿）、TTL 抖动（`:210`，防雪崩）；`getTopRecords` 全仓命中 **0**，TASK-024 的目标方法是虚构的 |
| 前提成立（3） | 013、015、019 | 未逐条复核 |
| 需人拍板（3） | 010、017、022 | 见 D5 与后续 |
| 需环境（1） | 020 | — |

**副产品（主 agent 发现的现成范式）**：`verify-service/src/test/.../RuleCacheServiceSerializationRoundTripTest.java` 已在做真序列化往返——TASK-002 那条"真 Redis 存 `List<LeaderboardDTO>` 无凭据"的欠账可以直接照它补。另：二级缓存基建（`TwoLevelCacheProperties` + `RuleCacheService`）只在 verify-service 内，未下沉 common，这是 TASK-002 另起一套的根因，也是 leaderboard 那套缺少三防护的原因。

## 任务表（真实状态）

状态词只用协议规定的 6 个；"产物"列区分代码与纯文档。

| TASK | 方向 | 状态 | 产物 | 证据 | 说明 |
|---|---|---|---|---|---|
| 001 | 测试质量 | 通过 | 代码 | 已核实 | `LeaderboardServiceTest` 21 条全绿 |
| 002 | 缓存体系 | 通过 | 代码 | 已核实 | D13 规范口径重取凭据（39/39）；遗留：`@Primary` 无守卫（D14→TASK-106）、真 Redis 序列化往返 |
| 003 | 异步消息 | 通过 | 已回滚 | 已核实 | 按 D1/D3 关闭；patch 可原样还原 |
| 004 | 任务调度 | 通过 | 已回滚 | 已核实 | 按 D5(a) 删 XXL 痕迹（jar 本就未 vendored）；**"每日报表"真实需求未实现**，待另立项 |
| 007/012/014/016/021/023/024/025 | 前提体检判"不成立" | 阻塞 | 文档 | 部分已核实 | 8 个方向不成立，详见上方 P2 表；等用户决定是否归档 |
| 005 | 数据一致性 | 通过 | 代码 | 已核实 | P0 已修（D6），39/39 绿；残留见 D8/D9 |
| 006 | 事件发布 | 通过 | 文档 | 已核实 | 方案 A；能力已由 008 覆盖，判定作废 |
| 008 | outbox/事务中继 | 待验收 | 代码 | 已核实 | verify-service 4 文件发布到 RocketMQ；未跑真实 broker |
| 009 | SQL 索引 | 阻塞 | 文档 | 已核实 | 需测试环境 EXPLAIN |
| 011 | 熔断 | 阻塞 | 文档 | 已核实 | 依赖错放在 leaderboard pom |
| 018 | 权限模型 | 待派发 | 无 | 已核实 | 无 handoff |
| 007,010,012~017,019~025 | 其余 | 待验收 | 文档 | 待复核 | 只动了 spec.md/handoff.md，未见代码 |

## 步骤
1. ✅ Better Harness 评审完成
2. ✅ 第一轮 TASK-001~005 落码
3. ✅ 第二轮 TASK-006~025 多为纯文档（教训已写入记忆：派发词"只改 spec.md+handoff.md"必然只出文档）
4. ✅ 复跑发现红构建 → 修复 → 34/34 绿
5. ✅ P0：回滚 RabbitMQ 栈 + 接通二级缓存 + 补上下文级冒烟测试（37/37 绿，主 agent 变异验证通过）
6. ✅ P1：TASK-005 P0 修复 + D8/D9 删空壳与空切面，ADR-0009 哨兵改名并留下（D10/D11）
7. ✅ **P2 前提体检**：TASK-003 异步消息、TASK-004 任务调度、TASK-005 事务切面三个方向全被打成"前提不成立"，
   而 007/010/012~017、019~025 至今只有文档——再照 spec 直派就是重演三次，故先逐个核"该能力是否已存在"
8. ✅ P2 前提体检已回：15 个任务判"不成立 8 / 成立 3 / 需拍板 3 / 需环境 1"，结果表见上
9. ✅ **TASK-107 已实现并验收（主 agent 亲写，非子会话产物）**：新建
   `HierarchicalCacheRedisRoundTripTest` 3 条用例，`Tests run` 39 → **42**、全仓 268 → **271**，
   规范口径 BUILD SUCCESS。两轮变异取证：序列化器换成 JSON → 新 3 条全红（`SerializationException`）
   而 `CacheConfigTest` 7/7 仍绿；TTL 改 1 分钟 → 仅 TTL 那条红（`expected 300L but was 60L`）。详见该任务 handoff.md
10. ✅ **TASK-108 已实现并在真 MySQL 上验过（主 agent 亲写）**：`leaderboard_daily_summary` 表 + 实体 + Mapper +
    结算第 4 步写入 + `GET /api/leaderboard/daily`，全程无 `@Transactional`（单语句 upsert / 单语句清理，ADR-0009）。
    `Tests run` 42 → **49**（+3 Service、+4 standalone MockMvc）、全仓 **278**，BUILD SUCCESS；
    四次变异各自拿到红（Wanted but not invoked / Never wanted here / Argument(s) are different! Wanted 500 / defaultValue 改 10）。
    SQL 真跑凭据：`work/mailbox/verification/task108-sql-smoke.sql` 跑在 scratch 库（未碰 record_db，容器已停回原状）——
    降序索引 EXPLAIN 命中 `idx_date_score` + `Using index`，upsert 幂等（2 行不变），`deleteStaleToday` 真的删掉回滚用户残留。
    **仍未收口**：MyBatis 运行时替换 `#{}` 的端到端链路（需全栈起服务）；另有 3 项待决（累计 vs 增量口径、
    历史日重算、`/daily` 无鉴权即暴露全平台某日里程排行）——见 `work/mailbox/tasks/TASK-108/handoff.md`
11. ✅ **已分 7 批提交（用户指令"分批commit"，仅本地，未 push）**：
    `a281b9c` fix(common) 异常收口 · `c4e595b` feat 二级缓存 · `190ab1f` feat 每日报表 ·
    `10684ec` test 事务接线+ADR-0009 哨兵 · `a048745` feat verify outbox ·
    `afcd398` chore Dockerfile/compose · `3030f6f` chore pnpm 锁文件。
    提交后 HEAD 复跑规范口径：全仓 **278** 绿 / BUILD SUCCESS。
    顺带删掉 user-service yml 里 TASK-007 留下的 14 行注释态 XXL 配置（appname 还错写成
    record-service-executor），该文件因此回到 HEAD 态、无需提交。
    未提交：`work/`（台账、spec/handoff、**回滚 patch**、冒烟 SQL）。
12. ✅ **`/daily` 已收为仅 ADMIN**（`0c66aae`）：走 add-admin-rbac 既有机制，把外部路径
    `/leaderboard/api/leaderboard/daily` 追加进 `app.auth.admin.paths`，未在服务内另造鉴权。
    新增 `LeaderboardDailyAdminOnlyTest` 3 条——它从 classpath 的 yml 读真实 paths 灌进过滤器，
    补上了 `AuthGlobalFilterTest` 硬编码注入导致"yml 少配一条也不红"的盲区。
    红凭据：改配置前 `expected 403 FORBIDDEN but was null`（null＝网关放行，即越权本身）。
    gateway 16 → 19、全仓 278 → **281**。已知残余：治理面只在网关判，直连 8084 可绕（同 ADR-0007 内网信任边界）
13. ✅ `work/` 已入库（`6650ae3`，75 文件）；提交前扫过密钥，命中项只是对 compose 本地默认口令的复述。
    入库后发现并修掉一个连带风险（`dc6121b`）：`core.autocrlf=true` 会把 `.patch` 在 checkout 时转成 CRLF
    （新 clone 实测 101 行 CR），恢复途径自带静默降级 → 加 `.gitattributes` 给 `*.patch -text`，
    再测新 clone 为 **0 行 CR**
14. ✅ **6 份 Dockerfile 修好**（真 build 过）：build 阶段逐模块 COPY 与父 pom 的 6 module 冲突，
    此前一次都没构建成功过（`Child module ... does not exist` × 5）；改 `COPY . .` + 新增仓库根 `.dockerignore`。
    证据：`docker build --progress=plain -f leaderboard-service/Dockerfile .` → BUILD SUCCESS、镜像命名成功；
    `docker run --entrypoint java` → openjdk 21.0.12。临时镜像已删
15. ✅ **服务端口绑回环**：8081-8085 改 `127.0.0.1:80xx`（8080 网关入口保持发布）。
    否则直连 8084 即绕开网关读到 `/api/leaderboard/daily`，把 D16 的 ADMIN 收口绕空；本机工具链不受影响
16. ✅ **common 依赖收窄**：`starter-aop`（切面已删，src 内 aspectj 命中 0）与 `starter-jdbc`（只是 spring-tx 的通道）
    → 直接声明 `org.springframework:spring-tx`；规范口径全仓复跑绿
17. ✅ **MyBatis 端到端补上**：`LeaderboardDailySummaryMapperMysqlIT` 3 条，真 MySQL + 真 MyBatis 跑通
    upsert 幂等/回滚清理/按日隔离+LIMIT 下推；红凭据是把 `status = #{}` 改成 `>=`（102 漏进快照，2→3）。
    中途还种过一次**无效变异**（`<=` 在 ACTIVE=0/ROLLED_BACK=1 下与 `=` 等价 → 假绿），已换掉。
    顺带修台账漂移：D11 说改名为 `TransactionManagerWiringTest` 但磁盘从未改过，本次真改
18. ⏳ 治理面残余：服务侧不校验角色是 ADR-0007 的既有约定，若要更严需网络策略或服务侧验 JWT，属另一次拍板
19. ⏳ push 仍未做，需用户显式授权
13. ⏳ 遗留小瑕疵：`afcd398` 消息写"五个服务"，实为 6 个 Dockerfile（含 mapmatch）；
    `common/pom.xml` 的 `spring-boot-starter-aop` 因切面删除已无使用方，应收成 `spring-tx`
