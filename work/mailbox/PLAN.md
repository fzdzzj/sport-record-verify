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
| 归档与后续 | 已并入能力规格并移入 `spec/changes/archive/`（`db3e341`，门槛 run `35574770124` 两个 job 全绿）；词面自检同期去掉扩展名白名单改为全部 tracked 文本载体（`6e00a62`）。**归档时新发现的遗留**：`web/src/typed-router.d.ts` 名义上是生成物、实为 11 行手写桩且承重——换成一次真实构建产出的 194 行版本后 `pnpm type-check` 即红（register/verdict 两处 TS2306 `vue-router-auto.d.ts` is not a module）。因此生成物一致性检查今天不能加（证据与正确修法已记在 ci.yml web job 注释），需另开变更修 vue-router 自动类型 |

## 验收记录：`真中间件路径的覆盖缺口（TASK-110，事项 2/5，2026-09-21）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `14d2600`；本条记录所在的收口提交（指导侧验收通过后执行，已推送） |
| 门槛来源 | 本地实跑，全部经 `scripts/verify/mvn-verify.sh`：`--mode=offline test` BUILD SUCCESS / 17/19/31/78/81/49/6 = **281**（与基线一致）；`--it` 真中间件在位 `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` / IT_RC=0 |
| 是否到达外部门槛 | **已到达**：push `67ddcdf..86024eb` 触发 run `35616258697`（2026-09-21 15:02，head=`86024eb`）——web/build 两 job 全绿（Build and test 8 模块 SUCCESS、compose 解析、代表镜像构建、词面自检；web 全 8 步含 `Generated router types match committed`）。批注仅 Node20 弃用等告警，非失败 |
| 未覆盖（不得写成通过） | **C 全栈 `/daily` 端到端：本期显式记未覆盖 + 分期理由**（宿主六服务拉起门槛本环境不具备；判据形态为独立 `smoke-daily.sh`，A/B 为可运行机器判据）。缺 `TASK110_IT_*` 时 Redis/RocketMQ IT 各 `Skipped:1`＝未覆盖，MySQL IT 3 绿 |
| 红绿取证 | Redis IT 红：改 scale 期望 2→3 → `expected: <3> but was: <2>`（`LeaderboardL2RedisRoundTripIT.java:119`），BUILD FAILURE；还原绿。RocketMQ IT 红：订阅 Tag 只 `SUBMITTED`、发 `VERIFIED` → 超时 `expected: not <null>`（`RocketMqBrokerRoundTripIT.java:122`），BUILD FAILURE；还原绿。两判别式各取到红对与绿对 |
| 环境核实 | Redis 连 `127.0.0.1:16379`（容器 `sport-verify-redis` override 映射），IT 打印 `run_id=d6f6ee451462b48ca165082afa519ad3d09cdfdf tcp_port=6379`，与 `docker exec sport-verify-redis INFO server` 逐字一致（容器实例，非原生 6379）；MySQL scratch 库 `task108_it` |
| 判定 | A/B 两条真中间件链路通过 `--it` 一键定向覆盖；C 分期未覆盖已显式记账 |
| 指导侧复验收（2026-09-21，现场复跑） | 六组取证逐字复现：① 契约脏树 `--open=TASK-018,TASK-106` 退出 1，TASK-110 段残余仅 `scripts/verify/env.example`（白名单 10 文件中 9 文件声明命中，`.example` 不在 `mailbox-contract.sh` 提取正则白名单内属已知盲区），TASK-104/109 段为历史清单共占公共文件的必然过冲；② `--it` 全 env `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` / rc=0，Redis IT 打印 `run_id=d6f6ee451462b48ca165082afa519ad3d09cdfdf tcp_port=6379` 与 `docker exec sport-verify-redis redis-cli INFO server` 逐字一致；③ 缺 `TASK110_*` 跳过路径：L2Redis/RocketMQ IT 各 `Skipped:1`、总计 `Tests run: 5, Skipped: 2`（MySQL 3 绿）；④ `--mode=offline test` 281（17/19/31/78/81/49/6 全绿零跳过，`*IT` 未进常规收集）；⑤ Redis 红对复演（scale 2→3）：`expected: <3> but was: <2>` @ `LeaderboardL2RedisRoundTripIT.java:119` / BUILD FAILURE / rc=1，还原 cmp 0 差异；⑥ RocketMQ 红对复演（订阅只 `SUBMITTED`、发 `VERIFIED`）：`expected: not <null>`（22.64s ≈ 20s poll 超时）/ BUILD FAILURE / rc=1，还原 cmp 0 差异。词面自检 ZERO-HIT。顺手订正 `mvn-verify.sh` 三处"真库/真实 MySQL"措辞为"真中间件"（该文件在只改清单内，`bash -n` 通过）。README（根/`scripts/verify/`）"真实 MySQL/真库"的文档同步缺口记为后续微变更，不扩大本次改动集。执行侧"契约退出 0 在收口提交后成立"论点由指导侧 commit 后复跑验证 |

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
11. ✅ **已分 7 批提交（用户指令"分批commit"，仅本地，已推送）**：
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

## 验收记录：`add-mailbox-contract-check`（2026-09-22，按上方收口清单写法）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 本条记录所在的收口提交（2026-09-22 指导侧验收通过后执行；含只改清单全部 8 文件与 tasks.json 状态回填） |
| 门槛来源 | 本地实跑：`mailbox-contract.sh --ledger=work/mailbox/tasks --open=TASK-018,TASK-106` → 退出 0（判据 A 25 目录两件套齐含 2 个待办进行中、判据 B 清单一致）；红 A / 红 B / TASK-108 隔离绿三对取证均为实测；`mvn-verify.sh --mode=offline test` → 281 全绿 / BUILD SUCCESS（模块合计见 TASK-109 handoff） |
| 是否到达外部门槛 | **已到达**：功能落地 `14d2600`（mailbox-contract 入口）由 run `35616258697`（head `86024eb`，2026-09-21 15:02，web/build 两 job 全绿）验绿；归档 `1773f0b`（TASK-115 并入主规格）由 run `35671465068`（head `620240b`，2026-09-22，两 job 全绿）复验 |
| open 任务集合 | 当前 `work/mailbox/tasks` 仅 `TASK-018`、`TASK-106` 为"仅 spec 无 handoff"进行中任务，经 `--open` 显式声明后在输出中列待办；后续台账演进须同步更新 `scripts/verify/README.md` 里给出的 `--open` 值 |
| 不作伪造 | 对 TASK-018 / TASK-106 未补写 handoff，只列待办；补写属停止边界，由指导侧另行决定 |
| 未覆盖 | 判据 B 对"已收口（足迹不在工作树）"的任务不重审既存记录，属设计取舍；`mvn-verify` 离线依赖来源以本机离线仓为准，未跑 online 全量 |
| 归档与后续 | 本任务不自行归档；`spec/changes/add-mailbox-contract-check/` 三件套在本变更验收通过后并入主规格并移入 `archive/`，另行派发 |
| 指导侧复验收 | 通过（不采信文字，现场复跑）：四组取证复现——红 A 退出 1 / 红 B 退出 1（含 `ghost-undeclared.md` 未声明项）/ TASK-108 隔离绿退出 0 / 全量绿退出 0 且 TASK-109 判据 B 清单一致（`.trae/` 排除生效）；额外实测退出码 3 路径（非 git 上下文即 3，不记通过）；`mvn-verify.sh --mode=offline test` → BUILD SUCCESS，281（17/19/31/78/81/49/6）0 失败 0 错误 0 跳过；词面自检按 CI 同款 pathspec 复跑（含 untracked 新文件）0 命中——回传 handoff 漏记此项，以本行为准。收口补齐两处：tasks.json 阶段 2–5 的 completed/passes 由指导侧回填（机械状态标记，非回传内容）；白名单 8 文件与 `git diff --name-only` 完全一致 |

## 验收记录：`sharding.yaml 的 MySQL host 参数化（TASK-111，事项 3/5，2026-09-21）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `9c90d32`；本条记录所在的收口提交（指导侧验收通过后执行，已推送） |
| 门槛来源 | 本地实跑，全部经唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test` → BUILD SUCCESS / 17/19/31/**80**/81/49/6 = **283**（record 基线 78→80，全仓 281→283，只增不减） |
| 是否到达外部门槛 | **已到达**：push `67ddcdf..86024eb` 触发 run `35616258697`（2026-09-21 15:02，head=`86024eb`）——web/build 两 job 全绿（Build and test 8 模块 SUCCESS、compose 解析、代表镜像构建、词面自检；web 全 8 步含 `Generated router types match committed`）。批注仅 Node20 弃用等告警，非失败 |
| 红绿取证 | 红（单测）：注入 `SHARDING_MYSQL_HOST=mysql` + 用例②临时固定期望 127.0.0.1 → `ShardingDataSourceConfigTest.realHostVariable_defaultOrOverrideBranch:70` `expected: <true> but was: <false>` / record BUILD FAILURE；还原绿。红（容器）：同网络 `-h 127.0.0.1` → `ERROR 2003 (HY000): Can't connect to MySQL server on '127.0.0.1:3306'(111)` 退出 1；`-h mysql` 绿 → 输出 `verdict/reachable` 退出 0。两判别式各取到红对与绿对 |
| 静态 mock 说明 | 本任务未用 `mockStatic(System.class)`：Mockito 凭类加载无限循环保护禁止 mock java.lang.System，直接写会抛该异常；红绿取证以「运行级注入 env + 临时期望编辑」复现，断言仍覆盖默认/覆盖两分支 |
| 构建产物 | `record-service/target/classes/sharding.yaml` L22 含 `jdbc:mysql://${SHARDING_MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/record_db?...`（源资源真进构建输出） |
| compose 口径 | `docker compose -f docker-compose.yml -f docker-compose.services.yml config` 退出 0；record-service environment 展开含 `SHARDING_MYSQL_HOST: mysql`（services.yml 注入 + compose 合并生效） |
| 容器内可达 | 核心判据：compose 网络 `sport-verify_sport-verify-net` 内一次性 mysql 客户端连 `mysql:3306` 服务名成功、`USE record_db` 可查、退出 0（容器口径而非宿主口径） |
| 未覆盖 | 无新未覆盖；TASK-110 全栈 `/daily` 端到端仍属其分期项（本任务不重提） |
| 契约自证 | 脏树 `mailbox-contract.sh --open=TASK-018,TASK-106` 退出 1 属预期（历史 TASK-104/109 清单与公共文件过冲）；收口提交后 ACTUAL 空 → TASK-111 足迹不在工作树视为已收口，契约退出 0 |
| 归档与后续 | 不自行归档；`add-sharding-host-parameterization` 三件套在本变更验收通过后并入主规格并移入 `archive/`，另行派发 |
| 指导侧复验收（2026-09-21，现场复跑） | 六组取证逐字复现：① 契约脏树退出 1，TASK-111 判据 B 通过（两件套齐全），残余"改动集未声明"3 条全为 `ShardingDataSourceConfigTest.java`（历史 TASK-105/109/110 清单过冲，与回传口径一致）；② `--mode=offline test` 283 = 17/19/31/80/81/49/6 全绿零跳过（record 78→80，`ShardingDataSourceConfigTest` 5/5）；③ 构建产物 `target/classes/sharding.yaml` 含 `${SHARDING_MYSQL_HOST:127.0.0.1}` 且无 `jdbc:mysql://127.0.0.1` 硬编码残留（`grep -c` 0 命中）；④ `compose config -q` 退出 0、展开含 `SHARDING_MYSQL_HOST: mysql`；⑤ 容器口径红绿：`mysql:8.0` 镜像在 `sport-verify_sport-verify-net` 内 `-h mysql -P 3306` 退出 0（`USE record_db` 可查）、`-h 127.0.0.1` `ERROR 2003` 退出 1——证明判据测的是容器内服务名寻址而非宿主回环；⑥ 单测红对（env 注入 `mysql` + 临时期望固定 `127.0.0.1`）：`realHostVariable_defaultOrOverrideBranch:74 设 SHARDING_MYSQL_HOST=mysql 应替换默认值 ==> expected: <true> but was: <false>` / record 80 中 Failures:1 / BUILD FAILURE / rc=1，还原 cmp 0 差异。词面自检 ZERO-HIT。`mockStatic(System)` 偏离已核实属 Mockito 类加载循环保护硬限制，env 分支断言等价覆盖默认/覆盖两分支，handoff 已注明——偏离成立。白名单 9 文件与 `git diff --name-only` + untracked 一致 |

## 验收记录：`规格模块枚举补正并归档（TASK-112，事项 4/5，2026-09-21）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `75ec1dd`；本条记录所在的收口提交（收口授权下放，执行侧自证全绿后直接收口，已推送） |
| 门槛来源 | 本地实跑，唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test` → BUILD SUCCESS（Reactor Summary 全 8 模块 SUCCESS）/ 17/19/31/80/81/49/6 = **283**（与基线一致，纯 spec 改动零扰动） |
| 是否到达外部门槛 | **已到达**：push `67ddcdf..86024eb` 触发 run `35616258697`（2026-09-21 15:02，head=`86024eb`）——web/build 两 job 全绿（Build and test 8 模块 SUCCESS、compose 解析、代表镜像构建、词面自检；web 全 8 步含 `Generated router types match committed`）。批注仅 Node20 弃用等告警，非失败 |
| 红绿取证（文档判据，判据脚本落盘 .sh） | 红（修正前）：「多模块工程结构」需求节 `mapmatch-service` 计数 **0**、需求写"8 个"、节内枚举 **7** 项 vs 父 pom `grep -c "<module>"` = **8** → 7≠8 矛盾成立（`RED_OK`）；绿（修正后）：节内 `mapmatch-service` 计数 **1**、枚举 **8** 项 = 父 pom **8** 模块、枚举名与 pom 模块名排序 diff 为空逐名一致（`GREEN_OK`）。主规格仅动 L35 枚举（补 `mapmatch-service`）与头部归档列表两处 |
| 归档动作 | `git mv spec/changes/update-spec-module-enum spec/changes/archive/`（先 `git add` 再 mv，git mv 只认已跟踪文件）；`git diff --cached --name-only` 仅含预期 3 文件（proposal.md / tasks.json / spec-delta.md，archive 路径）；`spec/changes/` 下无同名未归档目录 |
| 未覆盖 | 无新未覆盖；其余 14 个存量未归档变更（web 系列、perf 系列、gateway-browser-cors 等）整体归档另行派发，本任务只归档自己 |
| 契约自证 | 收口前脏树 `mailbox-contract.sh --open=TASK-018,TASK-106` 退出 **1** 属预期：TASK-112 自身判据 B 通过（只改清单与实际改动集 7=7 一致），残余为历史 TASK-111/110/109 清单与公共文件过冲（清单多报 + 改动集未声明并存）；收口提交后 ACTUAL 空（仅 `.trae/` 排除）→ 各任务足迹不在工作树视为已收口，契约退出 **0** |
| 指导侧复验收 | 收口授权下放（TASK-112 修订），指导侧不再复跑；判据不全绿不得合并由执行侧自证——红绿计数、offline 283、契约脏树 1 / 收口后 0 均已实测落档 |

## 验收记录：`CI 镜像构建时长余量评估（TASK-113，可接手事项 5/5，2026-09-21）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `0883bec`；本条记录所在的收口提交（收口授权下放，执行侧自证全绿后直接收口，已推送） |
| 门槛来源 | 本地实跑，唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test` → RC=0 / BUILD SUCCESS / 17/19/31/80/81/49/6 = **283**（与基线一致，纯文档零扰动）；计时对照为本机 `docker compose build` 三组实测（冷 340.0s / 热 2.3s / 增量 219.8s，本机口径限定见 ADR §3） |
| 是否到达外部门槛 | **已到达**：本任务即 run `35616258697` 的 head 提交（`86024eb`，2026-09-21 15:02，web/build 两 job 全绿）；ADR 所引 47s 基线为既有外部 run `35571634401`（`1fbf3eb`，本文件《add-controlled-verify-entrypoint》记录门槛来源行）的转引，非本任务新实跑 |
| 评估结论 | ADR-0010 五节齐备：**维持策略 B**（1 份实构 + config 覆盖）。A（BuildKit 层缓存）在上下文变更日与 B 等速（`COPY . .` 失效 → mvn 层必重跑），仅 docs/web/scripts 类提交日有 20~40s 级收益；cache mounts 不随 cache-to 导出（已查证）；本机实测证实冷构 97~98% 落在 mvn 层、`COPY . .` 跨 Dockerfile CACHED、热路径 2.3s——每多实构 1 份 ≈ +30~45s（机制估算）。重评触发：实构镜像数 > 3 或 build job > 5 分钟。切换属未来变更，动作清单见 ADR §5 |
| 出处勘误 | 任务包称 47s 系"概览 §8.5 转引"——经查 `docs/判定引擎-开发总览.md` 无 §8.5 小节，docs 全域 "47" 仅 GC 百分比与本台账命中；ADR 以 PLAN.md 上方记录绑定的外部门槛 run 为唯一可考出处，如实记录，未编造 |
| 未覆盖 | 未实测 runner 上的多实构与缓存行为（不实施、不 push 属本任务停止边界）；runner 口径增量为机制估算非实测；本机计时绝对值受本地到 Maven Central 带宽支配，仅取层机制份额 |
| 契约自证 | 收口前脏树 `mailbox-contract.sh --open=TASK-018,TASK-106` 退出 **1** 属预期（TASK-113 自身判据 B 只改清单与改动集 4=4 一致；历史 TASK-110/111/112 清单共占 PLAN.md 过冲）；收口提交后 ACTUAL 空（仅 `.trae/` 排除）→ 契约退出 **0** |
| 归档与后续 | 不自行归档：纯评估任务，无代码/规格需求改动，不建 `spec/changes/` 三件套，台账两件套即满足契约判据 A；ADR 编号顺延取 **0010**（`docs/adr/` 0001-0009 已占用，任务包起草时假设该目录不存在） |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：ADR 五节 + ci.yml 行号 sed 抽查 14 行全命中 + offline 283 + 词面自检两口径 ZERO-HIT（CI 原版 tracked 口径；`--untracked` 扩围排除 `.trae/` 后——不排除则命中指导侧残留脚本 `.trae/tmp/wording-check.sh` 自携的正则字面量，属工具伪影非交付载体，已辨析未改动）+ 契约脏树 1 / 收口后 0 |

## 验收记录：`契约提取盲区微变更（TASK-114，.example 白名单 + README 同步，2026-09-21）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `86024eb`；本条记录所在的收口提交（收口授权下放，执行侧自证全绿后直接收口，已推送） |
| 门槛来源 | 本地实跑，唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test` → RC=0 / BUILD SUCCESS / 17/19/31/**80**/81/49/6 = **283**（与基线一致，纯白名单一行 + README 说明，零扰动） |
| 是否到达外部门槛 | **已到达**：push `86024eb..620240b` 触发 run `35671465068`（2026-09-22）——web job 24s 全 8 步绿（含 `Generated router types match committed`），build job 2m21s 全 6 步绿（Build and test 8 模块 SUCCESS、compose 解析、代表镜像构建、词面自检、JaCoCo 上传）。批注仅 Node20 弃用等告警，非失败 |
| 红绿取证 | 红（修正前）：`echo 'scripts/verify/env.example' | grep -oE '<原白名单>'`→ `old_hit_count=0`（提取漏实证，即 TASK-110 声明却判法上提取不到的盲区根因）；绿（修正后）：同式 `new_hit_count=1`、命中串 `scripts/verify/env.example` 且 `eq=1`；提取层管道跑 TASK-110 handoff（`extract_claims` 同款 grep/sed/sort），`env_example_extracted=1`——TASK-110 的 `env.example` 声明现在可被契约提取 |
| 契约自证 | 收口前脏树 `mailbox-contract.sh --open=TASK-018,TASK-106` 退出 **1** 属预期（历史 TASK-110 的 PLAN.md/mvn-verify.sh/env.example 及公共文件过冲）；收口提交后 ACTUAL 空（仅 `.trae/` 排除，临时判据脚本已删）→ 契约退出 **0** |
| 词面自检 | CI 原版口径（git grep pathspec 三排除）ZERO-HIT 退出 1；临时取证脚本已清理 |
| 未覆盖 | 无新未覆盖；`--open` 值核对 TASK-018、TASK-106 与台账一致，无待改 |
| 归档与后续 | 不自行归档；停止边界内仅白名单一行 + README，不动契约判定逻辑，后续以既有 mailbox-contract 复跑验证 |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：红 0 / 绿 1 / 提取层 TASK-110 env.example 命中 + offline 283 零扰动 + 词面 ZERO-HIT + 契约脏树 1 / 收口后 0，全部实测落档 |

## 验收记录：`14 个存量 spec 变更归档并入主规格（TASK-115，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `8521f32`；14 个逐变更归档 commit：`1292596`(web-console-scaffold) `fdac48f`(web-auth-session) `ce2ca12`(web-admin-console) `f84762a`(web-record-console) `12cb4cd`(gateway-browser-cors) `15725e4`(db-migration-entrypoint) `1773f0b`(mailbox-contract-check) `1571172`(transaction-boundary-audit) `9c1bded`(perf-demo-innodb-flush) `8790307`(perf-g1-pause-target) `90ca84c`(perf-mq-publish-async) `d331db7`(perf-submit-aggregation-gate) `0fcb53c`(update-perf-optimized-defaults) `4be7929`(middleware-it-coverage)；本条记录所在的收口提交（收口授权下放，执行侧自证后直接收口，已推送） |
| 门槛来源 | 本地实跑，唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（`D:\git\Git\bin\bash.exe`）→ RC=0 / BUILD SUCCESS / 17/19/31/**80**/81/49/6 = **283**（与基线 `8521f32` 一致，纯 spec 文档改动零扰动，Failures 0 / Errors 0 / Skipped 0） |
| 是否到达外部门槛 | **已到达**：push `86024eb..620240b` 触发 run `35671465068`（2026-09-22）——web job 24s 全 8 步绿（含 `Generated router types match committed`），build job 2m21s 全 6 步绿（Build and test 8 模块 SUCCESS、compose 解析、代表镜像构建、词面自检、JaCoCo 上传）。批注仅 Node20 弃用等告警，非失败 |
| 并入 | 14 个变更的 ADDED 需求追加到主规格对应分区（新增「Web 控制台」分区分组）/ MODIFIED 需求替换基线文本；主规格头部「本规范已归档提案」新增 14 项；逐变更 `git mv` 入 `spec/changes/archive/`，每变更 1 commit 可回滚；每个 commit 的 `git diff --cached --name-only` 均只含预期（`spec.md` + archive 内 3 件套） |
| 冲突即停 | **add-sharding-host-parameterization 冲突停手**：与 `4be7929`(middleware-it-coverage) 共同 MODIFIED「真库端到端测试有确定路径」（均覆盖「真实中间件」泛化前提）；先并入 middleware 后，sharding 的 MODIFIED 基线文本与主规格当前文本对不上（强行并即将丢弃 middleware 已并入的清单/未覆盖内容）。按规则整体停手，不并入不归档，`spec/changes/` 下保留 `add-sharding-host-parameterization/`（非 archive），冲突明细回传指导侧 |
| 契约自证 | 收口前脏树 `mailbox-contract.sh --open=TASK-018,TASK-106` 退出 **1** 属预期（TASK-115 两件套 + PLAN.md 未提交 + add-sharding 未动为冲突停手预留）；收口提交后 ACTUAL 空（仅 `.trae/` 排除）→ 契约退出 **0** |
| 词面自检 | CI 原版口径（git grep 三排除：`spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）ZERO-HIT；全仓命中仅落在三排除路径内（`.trae/tmp/wording-check.sh` 为历史遗留、`ci.yml` 自身正则、`archive/add-two-level-cache` 历史表述） |
| 未覆盖 | add-sharding-host-parameterization（冲突停手未归档，其独立 ADDED「sharding 数据源 host 可由环境变量覆盖」未受影响，建议后续以该需求单开不 MODIFIED 既有需求的变更）；`spec/changes/ 仅剩 archive/` 判据因冲突停手未完全达成 |
| 归档与后续 | 已归档 14 个；sharding 冲突明细回传，处置留指导侧定口径；后续必要时以独立变更补开 sharding 需求 |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：14 逐变更 commit + offline 283 零扰动 + 词面 ZERO-HIT + 契约脏树 1 / 收口后 0 + 台账两件套，全部实测落档 |

## 验收记录：`全栈 /daily 端到端冒烟（TASK-116，闭环 TASK-110 C 项分期，2026-09-21）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 本条记录所在的收口提交（执行侧自证全绿后自行收口，已推送） |
| 门槛来源 | 冒烟脚本真机核验（非仅 `bash -n`）：红/绿/未就绪三维真实跑出，分别 exit 1/0/3；`mvn-verify.sh --mode=offline test` → BUILD SUCCESS（本轮无源码改动，纯新增冒烟脚本 + 文档，零扰动） |
| 是否到达外部门槛 | **已到达**：push `86024eb..620240b` 触发 run `35671465068`（2026-09-22）——web job 24s 全 8 步绿（含 `Generated router types match committed`），build job 2m21s 全 6 步绿（Build and test 8 模块 SUCCESS、compose 解析、代表镜像构建、词面自检、JaCoCo 上传）。批注仅 Node20 弃用等告警，非失败 |
| 环境前置自检（第一停止边界） | 六中间件 `docker compose ps` 全 `healthy`（nacos/mysql/redis/rocketmq-namesrv/rocketmq-broker/postgis）；六服务宿主拉起 8080-8085（含 mapmatch/PostGIS/sharding）。内存治理：停 exam 容器 + Docker VM 12GB；中途修正 DB 密码与迁移 `USE` 子句 |
| 红绿取证（三维退出码） | 绿：`top-1 距离 88.05 == 期望 88.05` / exit 0；红：`EXPECT_TOP=99.99` → `top-1 距离 88.05 ≠ 期望 99.99` / exit 1；未就绪：`BASE_URL=:9999` → 连接失败 / exit 3。关键：seed 固定 `SEED_TOP`（不随 `EXPECT_TOP` 变化），红绿可独立翻转 |
| 隔离岛设计 | `/daily` 沉淀由结算管线写 `CURDATE()`，故判定日期默认取 3 天前过去日期（该日快照行仅由脚本 preinsert、结果确定）；登录号须 ADMIN（`/daily` 在网关 `app.auth.admin.paths`，非 ADMIN 403）。临时 ADMIN 账号 `13900009999/smoke-daily-116` |
| 契约自证 | 收口前脏树 `mailbox-contract.sh --open=TASK-018,TASK-106` 退出 **1** 属预期（TASK-116 自身判据 B 只改清单与实际改动集一致；历史任务共占 `PLAN.md`/`README.md` 公共文件过冲）；收口提交后 ACTUAL 空（仅 `.trae/` 排除）→ 契约退出 **0** |
| C 项闭环 | **TASK-110 C 项（全栈 `/daily` 端到端）自此闭环**：A（Redis 真往返）/B（RocketMQ 真 broker）已于 TASK-110 经 `--it` 覆盖，C 于本任务交付可运行、可红绿的 `scripts/smoke/smoke-daily.sh` |
| 未覆盖 | 无新未覆盖 |
| 归档与后续 | 不自行归档；不建 `spec/changes/` 三件套（冒烟脚本非 spec 需求变更）；脚本依赖宿主「六中间件 + 六服务」全拉起，置于 `scripts/smoke/` 不并入 `mvn-verify.sh`（停止边界） |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：三维退出码实测 + offline 零扰动 + 契约脏树 1 / 收口后 0 + 台账两件套落档 |

## 验收记录：`sharding host 环境变量覆盖独立 ADDED 并入并闭合停手项（TASK-117，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `726cf63`；spec 侧逐变更 commit：`85252df`（归档 add-sharding-host-env-override 并入主规格）/ `466f7b1`（归档 add-sharding-host-parameterization 闭合 TASK-115 停手项）；本条记录所在的收口提交（收口授权下放，执行侧自证后直接收口，未 push） |
| 门槛来源 | 本地实跑，唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（`D:\git\Git\bin\bash.exe`）→ rc=0 / BUILD SUCCESS / 17/19/31/**80**/81/49/6 = **283**（与基线 `726cf63` 一致，纯 spec 文档改动零扰动，Failures 0 / Errors 0 / Skipped 0）；生效模式 offline、localRepository `D:/code/sports/.m2-repo`，依赖来源可判定（未触发退出码 3） |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；基线口径（含 `4be7929` middleware 泛化并入）已由 run `35671465068`（head `620240b`，2026-09-22，web/build 两 job 全绿）覆盖验证；本任务纯 spec 改动待下次 push 由 CI 复验，此处如实标注不作声称 |
| 红绿取证（文档判据） | 红（并入前，`726cf63`）：主规格 `grep -c SHARDING_MYSQL_HOST` = **0**、需求标题（临时模式文件精确匹配）= **0**、`ls spec/changes/` 仍列 `add-sharding-host-parameterization/`（非 archive）、`ls spec/changes/archive/ \| grep -i sharding` 无命中。绿（并入后）：需求标题计数 **1**、`SHARDING_MYSQL_HOST` 计数 **3**（正文 1 + 场景 2）、头部列表 L42 含 `add-sharding-host-env-override`；`git diff --cached --name-only` 逐 commit 仅含预期（commit 1 = 4 文件 196 行纯插入 / commit 2 = 3 文件全 R100）；`spec/changes/` 仅剩 `archive/` 且含两个 sharding 变更 |
| 并入内容（零 MODIFIED 自证） | spec-delta ADDED 段与主规格新增需求块逐字 diff 一致（两段 VERBATIM OK）；主规格总 diff **22 行纯插入 0 删除**（头部列表 1 行 + 需求块 21 行），落点为「校验引擎」分区「轨迹分片存储」之后（record-service `sharding.yaml` 即该需求 track_point 分片 ShardingSphere 数据源配置载体，与既有 sharding 表述同节）；「真库端到端测试有确定路径」保持 `4be7929` 泛化版原文未动（并入前复核通过，未触发冲突即停） |
| 停手项闭合 | **TASK-115 停手项自此闭合**：独立 ADDED 以零 MODIFIED 变更单开并入；原变更整体 `git mv` 入 `archive/`（R100 历史保留，MODIFIED 不再并入——容器口径判据已由 middleware 泛化版「可按清单覆盖多条 + 未覆盖记账」承载，与 TASK-115 停手判定一致）；`spec/changes/ 仅剩 archive/` 判据达成（TASK-115 完成定义唯一未达成项补齐） |
| 契约自证 | 收口提交前脏树 `mailbox-contract.sh --open=TASK-018,TASK-106` 退出 **1** 属预期（TASK-117 只改清单声明全量 10 文件，7 文件已随前两 commit 落库，工作树仅剩两件套 + PLAN.md，清单多报=在途口径）；收口提交后 ACTUAL 空（仅 `.trae/` 排除）→ 退出 **0** |
| 词面自检 | CI 原版口径（git grep 三排除：`spec/changes/archive/**`、`docs/internal/**`、`.github/workflows/ci.yml`）ZERO-HIT；禁用词模式经 `printf` 转义构造，命令行保持纯 ASCII |
| 未覆盖 | 无新未覆盖；原变更 MODIFIED（容器口径 E2E 细节）不再并入，由「真库端到端测试有确定路径」middleware 泛化版承载（判据形态：定向入口按清单覆盖多条真中间件测试、缺前提按未覆盖记账），不产生规范欠账 |
| 归档与后续 | `add-sharding-host-env-override` 与 `add-sharding-host-parameterization` 均已入 `spec/changes/archive/`；`spec/changes/` 下无未归档变更；后续无需跟进 |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：红绿计数 + 逐字并入 + 逐 commit 暂存清单 + offline 283 零扰动 + 词面 ZERO-HIT + 契约脏树 1 / 收口后 0，全部实测落档 |

## 验收记录：`CacheConfig 的 @Primary 人造歧义哨兵补测（TASK-106，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `a3e6b27`（`git status` 事前仅 `?? .trae/`）；本条记录所在的收口提交（收口授权下放，执行侧自证全绿后自行 commit，未 push） |
| 门槛来源 | 本地实跑，唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`（`D:\git\Git\bin\bash.exe`）→ rc=0 / BUILD SUCCESS / 模块合计 `17 19 31 80 81 50 6 = 284`（leaderboard **49→50**，全仓 **283→284**，只增 1 条）；生效模式 offline、`localRepository D:/code/sports/.m2-repo`（依赖来源可判定，未触发退出码 3）。基线同入口实跑 = `17 19 31 80 81 49 6 = 283` / BUILD SUCCESS，与任务书给的基线逐位一致 |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；基线口径已由既有 run `35671465068`（head `620240b`，2026-09-22，web/build 两 job 全绿）覆盖；本任务纯测试补测**待下次 push 由 CI 复验**，此处如实标注不作声称。另注：恢复对象库后本地领先 `origin/main`（`726cf63`）3 个提交（`85252df` / `466f7b1` / `a3e6b27`），本任务收口提交后为 **4** 个（含本条记录所在提交），与 TASK-117 记录的「未 push」一致 |
| 红绿取证 | 背景（P0-3/D14 遗留）：摘掉 `@Primary` 后全仓测试仍 7/7 绿，该注解无任何观测手段。**补测后**：① 定向跑 `CacheConfigTest` → `Tests run: 8, Failures: 0`（8/8 绿）；② 变异（`grep -c '^    @Primary$'` 由 1→0）→ rc=1、`Tests run: 8, Failures: 1`，唯一红为 `CacheConfigTest.typeLookupPrefersHierarchicalCacheManagerWhenAnotherCacheManagerExists`（**CacheConfigTest.java:107**，失败断言为 `assertThat(context).hasNotFailed()`），异常原文 `java.lang.IllegalStateException: No CacheResolver specified, and no unique bean of type CacheManager found. Mark one as primary or declare a specific CacheManager to use.`（抛点 `CacheAspectSupport.afterSingletonsInstantiated:273`）；任务书预期 `NoUniqueBeanDefinitionException`，实测因 `@EnableCaching` 在**容器启动期**自行做唯一性检查而更早失败，根因同一（CacheManager 非唯一且无 primary），已按实测原文留证、未放宽断言；既有 7 条在同次变异中保持绿 ⇒ 约束「不得污染共享 runner」成立；③ 还原：`cmp <备份> CacheConfig.java` 零差异 + `git diff --stat HEAD -- CacheConfig.java` 为空 + `javap ... | grep -c Primary` 变异 **0** / 还原 **2**，还原后定向复跑 8/8 绿；④ 终验（还原后全量、唯一入口）284 全绿 / `Failures 0 Errors 0 Skipped 0` |
| 测法说明（D14 预先裁定，未自行发明） | 生产上下文今天只有 1 个 `CacheManager` bean，`@Primary` 守的是「将来再多一个 `CacheManager`」的形状不变量，除自建第二个 bean 外无观测手段 ⇒ 接受「人造歧义」式测法。新测试**自带私有 runner**（`withUserConfiguration(CacheConfig.class)` + 既有 Redis 桩 + 一个次优 `ConcurrentMapCacheManager`），**不动**既有共享 runner（否则 `onlyHierarchicalCacheManagerIsExposed` 的 `hasSingleBean`/`containsExactly` 会连带变红 = 改写既有断言）；断言按类型解析 `isSameAs` 按名取到的 `hierarchicalCacheManager`。**未**采用「容器里有 2 个 CacheManager」式计数断言（有无 `@Primary` 都绿，测不到东西） |
| 生产代码零改 | `CacheConfig.java` 仅做「摘 `@Primary` → 复跑 → 还原」这一种临时变异，`cmp` 与备份零差异、`git diff HEAD` 为空；未新增/删除 `@Primary`、未提升裸 manager 为 bean、未改两层读写逻辑；未动 pom/依赖（离线仓外一律未用）、未动 `LeaderboardService*`/`application.yml`、未改既有 7 条断言内容 |
| 契约自证 | 收口提交前脏树 `--open=TASK-018,TASK-106` 退出 **1** 属预期（TASK-106 彼时仍仅 `spec.md` 且未在 `--open` 内 ⇒ 判据 A 失败；本任务白名单第 4 项即「补 handoff 后把 README 的 `--open` 收成 `TASK-018`」）；收口提交后 `mailbox-contract.sh --open=TASK-018` 退出 **0** |
| 环境事故（知会，不影响交付） | 任务执行中途（10:50）`.git` 对象库被**走回收站**批量删除（`.git/refs` 整目录消失、`objects/` 仅剩 6 文件、两个 `pack-*.pack` 丢失而 `.idx` 尚存）⇒ `git status` 一度报 `fatal: not a git repository`。**工作树零影响**（事后逐字与事前一致）；已用回收站 `$I`/`$R` 元数据配对**完整恢复**（未 re-clone、未丢提交）：`git fsck --no-reflogs` 无 broken link（仅 dangling）、`git rev-list --count HEAD`=240、`git status --porcelain` 与事前逐字一致、`origin/main...main = 0 3` 吻合。疑与本环境「沙箱对 `.git` 写操作受限」同源（用户级备忘第 3 条 gc 血案），触发点疑为一次 `git stash push`（本任务起弃用该命令，改用 `git show HEAD:<path>` + 非 `-p` 的 `cp` 做临时态）；已加仓库外全历史 `git bundle` 兜底。明细见 TASK-106 handoff |
| 词面自检 | CI 同款模式（`git grep -n -I -iE` + 三排除）改由 **UTF-8 脚本文件承载模式**（命令行保持纯 ASCII，本环境限制）：**本任务 4 个改动文件 0 命中**（`LC_ALL=C` 与默认 `C.UTF-8` 各跑一次均无命中）；全量扫描在 `LC_ALL=C` 下 **ZERO-HIT**。注：本机 MSYS `git grep -i` 在 `C.UTF-8` 下把字节 `0x8E/0x9E` 当大小写等价（cp1252 的 Ž/ž），使既有文件 `api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java` 的「大垂」误命中禁用词「大厂」——该文件本任务未触碰，且属上次 CI 绿（run `35671465068`）已含内容，判为 locale 伪影、非真命中 |
| 未覆盖 | 无新未覆盖；`@Primary` 的形状不变量由本测试的构造场景（容器内 2 个 `CacheManager`）覆盖，生产上下文本身仍只有 1 个 `CacheManager`（不属本任务范围） |
| 归档与后续 | 不自行归档：纯测试补测，无 spec 需求变更，不建 `spec/changes/` 三件套；台账两件套即满足契约判据 A。若后续要把「缓存 bean 唯一性」升为规范需求，另行派发 |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：基线 283 → 补测 8/8 → 变异 8-1 红（含方法名/行号/异常原文）→ 还原 `cmp` 0 + `javap` 2 → 终验 284 全绿 → 契约脏树 1 / 收口后 0，全部实测落档 |

## 验收记录：`leaderboard-service 静态检查三件套接入（checkstyle/spotbugs/pmd，TASK-018，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `dfa747a`（`git status` 事前仅 `?? .trae/`，领先 `origin/main` 4 提交）；本条记录所在的收口提交（收口授权下放，执行侧自证后自行 commit，**未 push**） |
| 门槛来源 | **三段式**：① 装料（在线，一次）`cd leaderboard-service && mvn -B -ntp -s ../.mvn-settings.xml test-compile checkstyle:check spotbugs:check pmd:check` → **rc=0** / 15.8 s；② 离线复现 `同上 + -o` → **rc=0** / 19.7 s / `grep -c Downloading` = **0**（依赖来源可判定）；②b 附加「离线 + `clean`」→ **rc=0** / 41.8 s（从零全量编译亦绿，排除「靠增量编译蹭过」）；③ 全仓回归唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0** / BUILD SUCCESS / 模块合计 `17 19 31 80 81 50 6` = **284**（Failures 0 / Errors 0 / Skipped 0）、生效模式 offline、localRepository `D:/code/sports/.m2-repo`（未触发退出码 3）。**284 与基线逐位一致 ⇒ 零扰动** |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；本任务改动待下次 push 由 CI 复验，此处如实标注不作声称。另注：**CI 当前不跑这三个 goal**（任务包硬边界规定不扩到 CI），故静态检查此刻没有外部门槛 —— 是否进 CI 留指导侧定 |
| 基准计数与治理判据 | 「代码异味减少≥30%」经指导侧裁定重构为「透明豁免」口径。N_default（各工具默认规则集首跑）：checkstyle/sun_checks **374**（13 种规则）、pmd/`maven-pmd-plugin-default.xml` **2**、spotbugs/引擎默认 **10**（全 Medium）。处理后：checkstyle **0 违规**（关闭 12 条规则 295 条违规 + 放宽 `LineLength` 80→140 覆盖 79 条）；PMD **0 条进失败判据**（`failurePriority=3` 降级，2 条仍写入 `target/pmd.xml` 并在日志以 WARNING 出现）；SpotBugs **0 条进失败判据**（`failThreshold=High`，10 条仍写入 `target/spotbugsXml.xml`、`Total bugs: 10` 照常打印）。分类分布：①误报 2（`HideUtilityClassConstructor` 命中 Spring Boot 启动类；`NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` 同行已有 null 三元）②Lombok/框架生成 13（`DesignForExtension` 4 + `EI_EXPOSE_REP2` 9，后者为 `@RequiredArgsConstructor` 注入字段的经典误报）③风格与既有代码库冲突 369 ④工具间重复 0。每条豁免的在位理由注释写在 `leaderboard-service/src/main/resources/checkstyle.xml` 与 `pom.xml` 对应插件配置内 |
| 红绿取证 | **红**：向既有文件 `LeaderboardService.java` 尾部注入 165 字符注释哨兵 → `checkstyle:check` **rc=1**，原文 `LeaderboardService.java:442: 本行字符数 165个，最多：140个。 [LineLength]`；**还原**（禁用 `git stash`）：`git show HEAD:<path>` + **非 `-p`** 的 `cp` + `touch` → `cmp` **IDENTICAL（exit 0）**、`sha256=3d1b4e189c670ed8` 与 git 对象逐位一致、`git diff --quiet` **EMPTY**、哨兵残留 **0**；**绿**：三 goal 复跑 **rc=0** / 0 violations / BUILD SUCCESS |
| 不绑 phase 的自证 | 三插件在 `leaderboard-service/pom.xml` 内均**无 `<executions>`**（配置只走插件级 `<configuration>`），故第 3 段 `mvn clean test` 既不执行也不解析它们；284 逐位等于基线即该项的直接证据。`spotbugs` goal 前缀在插件未声明时不可解析（插件组仅 `org.apache.maven.plugins`/`org.codehaus.mojo`），首跑用全限定 GAV，已在 handoff 注明 |
| 装料记录 | `.m2-repo` 内三插件与引擎原为**全空**，在线落料**新增 122 个 jar**（全部来自 central）：`maven-checkstyle-plugin:3.6.0` + `checkstyle:9.3`、`maven-pmd-plugin:3.28.0` + `pmd-core/pmd-java/pmd-javascript/pmd-jsp:7.17.0`、`spotbugs-maven-plugin:4.9.8.5` + `spotbugs:4.9.8`。传递依赖按顶层 groupId 概览（`org/apache` 47、`org/codehaus` 17、`com/github` 8 等）见 handoff；`.m2-repo` 与 `.mvn-settings.xml` 本在 `.gitignore` 内，不入改动集 |
| 修复记录 | ① **PMD 单规则引用不成立**：首版按「默认规则集减去 `UnnecessaryImport`」把 42 条规则逐条写进 `<rulesets>`，实测 PMD **不认「规则集/规则名」单规则引用**、退化成整个 category（违规 2 → **2056**）；处置为显式引用插件内置默认规则集 + `failurePriority=3` 降级（属「最多 1 次修复重试」内的必要纠偏）。② **`.m2-repo` 内部构件陈旧**：`sport-verify-common` 旧包（2026-09-12）早于其源码 `TraceIds`（2026-09-16 / `5846548`），独立模块构建一旦触发全量重编即报 `程序包 com.sportverify.common.trace 不存在`（还原后 `touch` 复跑时实际撞上）；已 `mvn -o -pl common,api -am install -DskipTests` 刷新本地仓（gitignored）后复跑全绿，**与本次改动无关**，是本仓独立模块构建路径的既有隐患 |
| 契约自证 | 收口前脏树 `mailbox-contract.sh --open=TASK-018` 退出 **1**（末行 `判据 A=0 判据 B=1`，**判据 A 已通过**——TASK-018 两件套齐全，输出已为「两件套齐全」，`--open` 不再需要）。判据 B 共 **12 个任务**报在途不一致（`TASK-006 018 106 109 110 111 112 113 114 115 116 117`），两类成因：① **11 个历史任务共占公共文件**——其历史 handoff 正文含 `leaderboard-service/pom.xml`/`PLAN.md`/`README.md`/`spec.md` 等 token，与本次改动集交叠触发在途强校验，与 TASK-106/116/117 台账已记录的「公共文件过冲」同源，与本任务改动无关；② **TASK-018 自身，唯一缺口是 `.editorconfig`**——其余 5 项声明与实际改动集逐项一致，唯一 `only_actual` 就是它，因不在契约脚本路径提取的扩展名白名单内、**写了也提取不出**（工具侧缺口，非清单漏报；本任务白名单不含该脚本，无法在此修掉）。收口提交后 `mailbox-contract.sh`（**不再带 `--open`**）退出 **0** |
| 白名单自证 | `git diff --name-only HEAD` + untracked 与任务包白名单**完全一致**：`leaderboard-service/pom.xml`（+108 行纯插入 0 删除）、`leaderboard-service/src/main/resources/checkstyle.xml`（新建）、`leaderboard-service/.editorconfig`（新建）、`work/mailbox/tasks/TASK-018/handoff.md`（新建）、`work/mailbox/PLAN.md`（本条记录）、`scripts/verify/README.md`（收口命令去 `--open`，另补 1 段 `.editorconfig` 提取局限的如实说明）；`.trae/` 为基线允许。未动 mvn-verify.sh / CI / 服务生产代码 / 迁移脚本 / 其他模块；全程未用 `git stash`，仓库外留 `pre-task018-dfa747a.bundle` 兜底 |
| 词面自检 | 本任务 6 个改动文件 **0 命中**（CI 同款正则 `git grep -n -I -iE` + 三排除、`LC_ALL=C` 下逐文件复核）。**但全仓扫描非零命中，且落点不在本任务**：`work/mailbox/PLAN.md` 与 `work/mailbox/tasks/TASK-106/handoff.md` 各 1 行 —— TASK-106 为描述「本机 MSYS 把某汉字的第二字节按 cp1252 判成大小写等价、致既有未触碰文件伪命中」这一 locale 伪影，把禁用词**原文引进了台账**，于是该两处自身成了命中项。二者均为**已入库但未 push** 的内容（本地领先 `origin/main` 4 提交），属白名单外文件（`PLAN.md` 本任务仅追加、未改既有行），本任务未动。**注意：下次 push 会被 CI 的公开文档口径自检拦下**，须指导侧处置（改写 TASK-106 两处表述即可，勿再原文引用禁用词） |
| 未覆盖 | ① 3 处真实无用 import（`InternalLeaderboardController.java:3`、`LeaderboardService.java:3`、`:21`）**未修**——白名单无 Java 源改动权，已在 handoff 建议单开最小变更；② spec 原验收命令（无 `test-compile` 前置）未单独复跑——`spotbugs`/`pmd` 分析 `target/classes`，冷 `target/` 下无类可析，任务包已注明该前置；③ 静态检查**未进 CI**（硬边界），外部门槛为空 |
| 归档与后续 | 不自行归档：静态检查配置接入非 spec 需求变更（不改主规格、不建 `spec/changes/` 三件套），台账两件套即满足契约判据 A。后续可选项：3 处无用 import 单开变更修掉、`mailbox-contract.sh` 提取白名单补 `.editorconfig`、把三 goal 纳入 CI（需重新派发授权） |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：装料 122 jar → N_default 374/2/10 → 三段式 0/0/0（含离线 0 下载、离线+clean 全量编译）→ 红绿取证（`LineLength` @442 / `cmp` 0 / `git diff` 空 / 哨兵 0）→ 全仓 284 零扰动 → 契约脏树 1 / 收口后 0，全部实测落档 |
