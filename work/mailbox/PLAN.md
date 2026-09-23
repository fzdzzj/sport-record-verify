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

## 验收记录：`TASK-135`（2026-09-23，总榜缓存真实入口，未提交）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `a771da381389359439f1c7da5a19ac72e319c85b`；当前为未提交工作树 diff，未 push、未建 PR。 |
| 目标与范围 | 核实 Controller 正常入口的 Spring 缓存代理；把总榜缓存从 self-invocation 不可达的 `topOverall` 移到 `top` 的 overall 条件；好友榜不缓存。 |
| 受控红绿 | 旧实现 Spring 代理判别式：`LeaderboardCacheInvocationTest` `1/1/0/0`，底层 ZSet wanted 1 / actual 2；修正后该测试 `3/0/0/0`，好友榜逐次调用与故障空榜同测通过。 |
| 本地门槛来源 | `bash scripts/verify/mvn-verify.sh --mode=offline --pl leaderboard-service test` → `rc=0` / `BUILD SUCCESS` / 目标模块 `57/0/0/0`；因缓存入口说明与断言同步，`bash scripts/verify/mvn-verify.sh --mode=offline --static=leaderboard-service` → `rc=0`，Checkstyle 0 violations，SpotBugs Error size 0，PMD 成功。 |
| 是否到达外部门槛 | **未达到**：未 push、未建 PR；online/CI 未覆盖。 |
| 契约 | `bash scripts/verify/mailbox-contract.sh --baseline=a771da381389359439f1c7da5a19ac72e319c85b` → 总体 `rc=1`；`TASK-135` 判据 B 通过。总体失败来自既有在途任务与共享公共文件交叠，不是本任务清单不一致。 |
| 未覆盖/跳过 | 未跑真实 Redis Controller→缓存→ZSet IT、真实 user-service 跨服务调用、MySQL/RocketMQ IT、online/CI；未把这些写成通过。 |
| 未解决边界 | 缓存 key 按入口规范化后的 topN（默认 50、上限 1000）复用；好友榜仍不缓存，好友关系变化的一致性未作产品决策。 |

## 验收记录：`TASK-134`（2026-09-23，好友榜读取修复，未提交）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `9fef29119ba5a2b1c5c7bc528f1c54e181afe21`；业务/测试/spec 修订绑定本地 commit `27399f04a606220e03a67eea2ff8ed4855e509c5`（`fix(leaderboard): 完善好友榜分页完整性`）。本行与 handoff 为后续记录补录，未 push。 |
| 目标与范围 | 仅 `leaderboard-service`：好友分页按 `PageResult.total` 取齐；好友榜按 500 条窗口分批扫描 ZSet，保持只显示好友、排序、过滤后 rank、服务降级空榜；明确不添加 `@Cacheable`。 |
| 受控红绿 | 新增分页完整性回归后，Git Bash 改前定向测试 `rc=1`，`28` 例中 `2` 失败；最小修复后 `rc=0`，`28/0/0/0`。 |
| 本地门槛来源 | Git Bash 调用 offline 目标入口：`--mode=offline --pl leaderboard-service test` `rc=0`，目标模块 `54/0/0/0`；offline 静态入口 `--mode=offline --static=leaderboard-service` `rc=0`，Checkstyle 0 violations，SpotBugs Error size 0，PMD 构建成功。 |
| 仓库验收入口 | offline 目标模块测试与静态入口均已通过；均由 `D:\git\Git\bin\bash.exe` 调用仓库脚本。 |
| 是否到达外部门槛 | **未达到**：未 push、未建 PR；online/CI 本轮未覆盖，无 CI run。 |
| 契约 | Git Bash `bash scripts/verify/mailbox-contract.sh` 总体 `rc=1`，但 `TASK-134` 判据 B 明确通过；总体失败由既有在途任务与共享工作树交叠造成，不是 TASK-134 清单不一致。 |
| 未覆盖/跳过 | 未新增真实 Redis/MySQL/RocketMQ IT；好友服务真实跨服务分页与生产规模性能未覆盖；online/CI 未覆盖。既存 `.trae/` 未触碰。 |
| 未解决边界 | 最坏仍可能扫描整榜，但每次读取最多 500 条；好友集合仍汇总在内存；分页依赖 `total` 契约；缓存一致性未作产品决策且本任务不加缓存。 |


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
| 词面自检 | CI 同款模式（`git grep -n -I -iE` + 三排除）改由 **UTF-8 脚本文件承载模式**（命令行保持纯 ASCII，本环境限制）：**本任务 4 个改动文件 0 命中**（`LC_ALL=C` 与默认 `C.UTF-8` 各跑一次均无命中）；全量扫描在 `LC_ALL=C` 下 **ZERO-HIT**。注：本机 MSYS `git grep -i` 在 `C.UTF-8` 下把字节 `0x8E/0x9E` 当大小写等价（cp1252 的 Ž/ž），使既有文件 `api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java` 中「垂距」的「垂」（`0xE5 0x9E 0x82`）被误判为**词面自检的禁用词**命中——二者仅差第二字节、被判成大小写等价（TASK-118 实测：该误判只在模式含多分支时复现），该文件本任务未触碰，且属上次 CI 绿（run `35671465068`）已含内容，判为 locale 伪影、非真命中。**该处原文引用已由 TASK-118 改写为指代表述**（原文会被 CI 的公开文档口径自检拦下） |
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

## 验收记录：`台账禁用词原文改写（TASK-118，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `0f31c18`（`git status` 事前仅 `?? .trae/`，领先 `origin/main`（`726cf63`）5 个提交）；`a424b67` 建档两件套 · `2f8c090` 两处改写 · 本条记录所在的收口提交（收口授权下放，执行侧自证后自行 commit，**未 push**） |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0 / BUILD SUCCESS / 模块合计 `17 19 31 80 81 50 6 = 284`（与基线 `0f31c18` 逐位一致，纯台账文本改动零扰动，Failures 0 / Errors 0 / Skipped 0）→ 生效模式 offline、localRepository `D:/code/sports/.m2-repo`，依赖来源可判定（未触发退出码 3）。前置环境坑如实登记：首次裸跑 rc=1 报 `找不到或无法加载主类 …plexus.classworlds.launcher.Launcher`，根因是本机继承 `MSYS_NO_PATHCONV`/`MSYS2_ARG_CONV_EXCL`，`unset` + `JAVA_HOME=/d/develop1/jdk21` 后 rc=0 —— 该 1 不计作用例红，也不计作退出码 3 |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；本任务纯台账改写**待下次 push 由 CI 复验**，此处如实标注不作声称。反向影响面已实测：CI 的公开文档口径自检在 `620240b`（上次绿 run `35671465068` 的 head）上已存在同款步骤与三排除、且该 head 上被伪命中文件已含触发内容 ⇒ 本机默认 locale 残留的 2 条属本机引擎伪影，不代表 CI 会红 |
| 红绿取证（词面判据） | **红**（改前）：CI 同款 `git grep -n -I -iE <禁用词表> -- 三排除` → `LC_ALL=C` 命中 **2** 行（`PLAN.md:353`、`TASK-106/handoff.md:97`），默认 `C.UTF-8` 命中 **4** 行（另 2 行在只改清单外的既有 Java 文件上），脚本退出码 **1**。**绿**（改后 `2f8c090`）：同命令 `LC_ALL=C` **ZERO-HIT**（台账两行在两种 locale 下均零命中）；默认 locale **2** 行且**全部**在白名单外 ⇒ 见「未覆盖」。脚本为 `.trae/tmp/wording-check-118.sh`（UTF-8 承载模式，命令行保持纯 ASCII），退出码 0/1/2 语义与 CI 结构同构 |
| 伪影机制修正（本任务实测） | 原台账把伪影写成「把字节 `0x8E`/`0x9E` 当大小写等价」，**该描述不足以复现**：实测单分支字面量 rc=1 零命中、单字 rc=1 零命中，**仅在模式含多分支（`|`）时**才命中只改清单外那 2 行。故「字节等价」降级为被观测现象，「引擎在哪一层折叠」标注为**本机推断、未证实到引擎层**；该修正已同步写进被改写的两行表述与 TASK-118 handoff，不再冒充结论 |
| 改写范围自证 | `git diff -U0` 实测：`PLAN.md` **仅第 353 行 1 行替换**；`TASK-106/handoff.md` **仅 97–98 两行**同一句换行重排（净 +2 行）；两者均未动其他既有行。改写把「被误判的词」改为**按字节书写**（`0xE5 0x9E 0x82`），故不可能再与该表内的词形成字面或分支等价关系；语义三点（字节被判等价 / 该文件未触碰且属上次 CI 绿已含内容 / 判为伪影非真命中）逐条保留 |
| 契约自证 | 在途 `mailbox-contract.sh --baseline=0f31c18` 退出 **1**：TASK-118 段初版曾因「只改清单」小节夹带 CI 工作流的路径 token 被判「清单多报」，已修（该节现只留 4 个路径，其后另起子标题隔断）；其余 11 个历史任务（TASK-018/106/109~117）报在途不一致，成因是历史 handoff 正文含 `PLAN.md`/`README.md`/`pom.xml` 等公共文件路径与本任务改动集交叠，与既有「公共文件过冲」同源，与本任务改动无关。收口提交后 `mailbox-contract.sh`（**无参数**）退出 **0** |
| 只改清单一致性 | 实际改动集（`git diff --name-only 0f31c18` + untracked 排除 `.trae/`）= `PLAN.md` · `TASK-106/handoff.md` · `TASK-118/spec.md` · `TASK-118/handoff.md`，与 handoff 声明**逐字一致**（无多报、无漏报）；未改 CI 工作流、未改契约脚本与统一验收入口、未动归档变更与内部文档、未动任何生产代码；全程未用 `git stash`、未 push、未建 PR |
| 未覆盖 | ① 默认 locale（本机 `C.UTF-8`）下 CI 同款全量判据仍余 **2 条**，落点 `api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17/36`——该文件在只改清单外，**未修、按未覆盖记账**（处置建议见 handoff「待主 agent 决定」第 1 条）；② CI 侧 step 级结论**未独立复核**（本机 `gh` 未登录），只以上次绿 run 的 head 上「该步骤已存在 + 触发内容已存在 + 该 run 两 job 全绿」三点间接判定；③ 本任务全部取证限于本机，CI 真伪须待下次 push |
| 归档与后续 | 不自行归档：纯台账文本改写，无 spec 需求变更（不改主规格、不建 `spec/changes/` 三件套），台账两件套即满足契约判据 A。后续可选项：默认 locale 那 2 条伪命中的最小改法、以及本机双 locale 词面自检是否沉淀为仓内脚本（该脚本现位于不入库目录） |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：红 2/4 → 绿 0/2（`LC_ALL=C` 全量 ZERO-HIT）→ 伪影最小复现形态实测 → offline 284 零扰动（含首次环境坑 1 次的根因与处置）→ 契约在途 1（成因逐条拆开）/ 收口后 0 → 实际改动集与只改清单逐字一致，全部实测落档 |

## 验收记录：`鉴权降级与白名单路径剥离身份头（TASK-119，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `1c1b5c2`（`git status` 事前仅 `?? .trae/`，领先 `origin/main`（`726cf63`）8 个提交）；`afb69e2` 规范三件套 · `fff52ee` 台账两件套 · `447a40e` 先落红判别式 · `9d574c7` 剥离实现 · `5e93525` 收口记录 · 本条最终修订复跑补录所在的提交（**未 push**、未建 PR） |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh`。① **红对**（定向 `--pl gateway-service test`）rc=**1**：`Tests run: 22, Failures: 2`，原文 `AuthGlobalFilterTest.degradeDisabledStripsForeignIdentityHeaders:144 expected: <null> but was: <999>` 与 `AuthGlobalFilterTest.whitelistedPathStripsForeignIdentityHeaders:154 expected: <null> but was: <999>`，BUILD FAILURE。② **绿对**（同命令）rc=**0**：`AuthGlobalFilterTest` 11 条 / 模块 22 条全绿、BUILD SUCCESS、32.2 s。③ **变异体**（临时把透传分支改回 `chain.filter(exchange)`，仅此一处）rc=**1**，失败行与红对逐字相同；还原后 `sha256sum -c` 报 `OK`、`cmp` 退出 0（`ZERO-DIFF`），修复态哈希 `f70b81daef323372fe12434a4eb6219b871ce4290caaeca96db153877b654e3a` 与变异前逐位一致。④ **全量** `--mode=offline test` rc=**0** / BUILD SUCCESS / 05:06 min / 逐模块 `17 22 31 80 81 50 6` = **287**（Failures 0 / Errors 0 / Skipped 0）；相对基线 284 的**唯一差异**是 gateway 19→22（+3 本次新增用例），其余六模块逐位不变；生效模式 offline、localRepository `D:/code/sports/.m2-repo` ⇒ 依赖来源可判定（未触发退出码 3）。⑤ **最终修订复跑**（收口提交 `5e93525` 之上、工作树仅剩文档差异后同口径再跑一次）：rc=**0** / BUILD SUCCESS / 03:22 min / 同为 `17 22 31 80 81 50 6` = **287** ⇒ 门槛结论绑定的就是收口修订本身，不是收口前某次中间态 |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；本任务改动待下次 push 由 CI 的 `Build and test` 与 `Public docs wording self-check` 复验，此处如实标注不作声称 |
| 改动与边界 | `AuthGlobalFilter` 透传分支改为 `chain.filter(stripIdentityHeaders(exchange))`，新增私有方法 mutate 后 `remove` 掉 `X-User-Id`/`X-Role` 两头；降级开关分支与白名单分支共用该逻辑。**鉴权开启分支一行未动**（仍是 `headers.set` 覆盖式注入），`app.auth.enabled` 默认值未改（关闭态是本地演示与压测的既有口径）。影响面已核对：仓库内无任何脚本/前端经网关发这两个头（`web/src/api/client.ts` 明确不发，`LoadTest.java` 只用请求体占位符传 userId，冒烟脚本的 userId 在 JSON body 里，其余脚本直连 80xx 不经网关） |
| 词面自检 | `.trae/tmp/wording-check-119.sh`（UTF-8 承载模式，命令行纯 ASCII，与 `ci.yml` 自检步骤同构：同款正则 + 三处排除）两 locale 各一次：`LC_ALL=C`（CI 语义）**ZERO-HIT**（本任务 8 个改动文件零命中）；默认 `C.UTF-8` 余 **2** 条，落点为 `api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17/36`——与 TASK-118 已登记的是**同一处**既有文件（本任务未触碰），属本机引擎伪影、非真命中，按未覆盖记账 |
| 契约自证 | **在途跑法①**（工作树 7 项、本记录待写，`--baseline=1c1b5c2`）：**判据 A 通过**（两件套齐全）；TASK-119 判据 B **失败**，唯一差异是 `清单多报（实际未改动）：work/mailbox/PLAN.md` —— 即本记录自身，符合在途预期；其余历史任务全部「足迹不在工作树，视为已收口，不重审」（当时 `PLAN.md` 未进改动集，历史回传的声明与本改动集无交集）。**在途跑法②**（工作树 8 项、含本记录）：**TASK-119 判据 B 通过**（只改清单 8 项与实际改动集逐项一致）；该次整体退出码仍为 1，但成因**不在 TASK-119** —— `PLAN.md` 进入改动集后，**12 个历史任务**（TASK-018 / 106 / 109~118）的回传正文含 `PLAN.md` 等公共文件 token 与本改动集交叠，触发既往台账（TASK-018/106/116/117/118）已记录的「公共文件过冲」强校验。**收口提交后** `mailbox-contract.sh`（**无参数**）退出 **0**（工作树无迹 ⇒ 不重审） |
| 只改清单一致性 | 实际改动集（`git diff --name-only 1c1b5c2` + untracked 排除 `.trae/`）＝ 规范三件套（`proposal.md`/`tasks.json`/`spec-delta.md`）· `AuthGlobalFilter.java` · `AuthGlobalFilterTest.java` · `TASK-119/spec.md` · `TASK-119/handoff.md` · `PLAN.md`，与 handoff 声明**逐字一致**（无多报、无漏报）。未改 CI 工作流、未改契约脚本与统一验收入口、未改 `spec/specs/**`、未动其它模块与 `application.yml`；全程未用 `git stash`（临时态用 `cp` 副本 + 还原后哈希校验） |
| 未覆盖（不得写成通过） | ① **端到端面未覆盖**：本次为单元级判别式（`MockServerWebExchange` + 内联链），「真实起网关 → 打 8080 → 观察下游收到的头」的全栈验证本机不具备（需六服务 + 中间件齐备），按未覆盖记账；② 默认 locale 下 CI 同款全量判据仍余 2 条（同 TASK-118 登记的 `MapMatchResultDTO.java:17/36`，本任务只改清单外，未修）；③ CI 侧结论未到达，须待下次 push 复验 |
| 归档与后续 | **不自行归档**：`spec/changes/add-auth-degrade-header-strip/` 保持变更态，需求并入 `spec/specs/sport-record-verify/spec.md` 由后续变更统一处理。后续可选项：`gateway-service/src/main/resources/application.yml:104` 的注释已与实现漂移（仍写「false=旧行为（透传不校验，显式携带 userId）」，未提剥离身份头）——该文件不在本任务只改清单内故未动，建议与「是否按 profile 强制开启 `app.auth.enabled`」一并处理 |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：红（`:144`/`:154`）→ 绿（gateway 22）→ 变异复红 → 还原 `cmp` 零差异 → 全量 287 全绿 → 词面自检 `LC_ALL=C` 零命中 → 契约在途逐项一致 / 收口后 0，全部实测落档 |


## 验收记录：`契约提取白名单补 .editorconfig（TASK-120，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `ac2a8ff`（`git status` 事前仅 `?? .trae/`）；`14a2697` 白名单一行 · `7c1f1ce` README 同步 · 本条记录所在的台账收口提交（**未 push**、未建 PR） |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0 / BUILD SUCCESS / 模块合计 `17 22 31 80 81 50 6 = 287`（与开工基线 `ac2a8ff` 逐位一致，纯脚本一行 + 文档零扰动，Failures 0 / Errors 0 / Skipped 0）。任务包原文写"284"为 TASK-119 增量前的旧锚点，实跑以 287 为准并在此登记。生效模式 offline、依赖来源可判定（未触发退出码 3） |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；待下次 push 由 CI 复验，此处如实标注不作声称 |
| 红绿取证（提取判据） | **红**（改前）：`echo 'leaderboard-service/.editorconfig' | grep -oE '<原白名单>'` → 0 命中（grep 计数 0）；提取层管道（awk 截节 + grep/sed/sort，与 `extract_claims` 同构）对 TASK-018 handoff 跑一遍 → 6 个声明路径只出 5 个，`leaderboard-service/.editorconfig` 缺席——"清单多报"假阳性的实证。**绿**（改后）：同式命中 1 且串一致（eq=1）；同管道对 TASK-018 handoff 出全 6 个路径。**变异验证**（TASK-106 手法）：临时回退白名单复现红（0 命中）、还原后 `sha256sum -c` 报 OK、`cmp` 退出 0（逐位一致）。`bash -n` 语法自检通过 |
| 契约自证 | 在途 `mailbox-contract.sh --baseline=ac2a8ff`：TASK-120 判据 A 通过（两件套齐全）、判据 B 通过（只改清单 5 项与实际改动集逐项一致）；整体退出码 1 的成因**不在 TASK-120**——`PLAN.md` 进改动集后历史 handoff 的公共文件 token 交叠触发既往已登记的"公共文件过冲"。收口提交后 `mailbox-contract.sh`（**无参数**）退出 **0** |
| 只改清单一致性 | 实际改动集（`git diff --name-only ac2a8ff` + untracked 排除 `.trae/`）＝ `scripts/verify/mailbox-contract.sh` · `scripts/verify/README.md` · `TASK-120/spec.md` · `TASK-120/handoff.md` · `PLAN.md`，与 handoff 声明**逐字一致**。未动契约判定逻辑（判据 A/B 分支、比对、退出码）、未动 `--open` 机制、未改 CI 工作流与统一验收入口；全程未用 `git stash`（变异验证用 `cp` 副本 + 哈希校验） |
| 未覆盖 | ① 默认 locale 下 CI 同款词面自检仍余 2 条（TASK-118/119 已登记的 `api/**/MapMatchResultDTO.java:17/36` 本机引擎伪影，只改清单外，未修）；② 本任务改动待下次 push 由 CI 复验 |
| 归档与后续 | 不自行归档：纯脚本一行 + 文档，无 spec 需求变更（不建 `spec/changes/` 三件套），台账两件套即满足契约判据 A。`.editorconfig` 是扩展名提取白名单的第二次同源盲区（第一次 `.example`，TASK-114）；README 已把两次修法沉淀为"新载体类型先验证提取管道再交付"的判别样本 |


## 验收记录：`leaderboard-service 无用 import 清理（TASK-121，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `095fd98`（`git status` 事前仅 `?? .trae/`）；`8ce8171` 删 import 代码改动 · 本条记录所在的台账收口提交（**未 push**、未建 PR） |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test`（删后、收口前工作树实跑；收口提交仅新增台账文本，Java 源零变化）→ rc=0 / BUILD SUCCESS / 2m47s / 模块合计 `17 22 31 80 81 50 6 = 287`（与开工基线逐位一致，删 import 不改行为零扰动，Failures 0 / Errors 0 / Skipped 0）。任务包原文写"284"为 TASK-119 增量前的旧锚点，实跑以 287 为准并在此登记。生效模式 offline、依赖来源可判定（未触发退出码 3） |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；待下次 push 由 CI 复验，此处如实标注不作声称 |
| 红绿取证（反向取证 + 删后绿） | 本任务为删除类变更，无"先红"判别式，改为**逐处核实（引用计数）+ 删后绿**：三处候选用 Grep 全文件计数逐一核实——① `InternalLeaderboardController.java:3`（`LeaderboardApi`）除 import 行外计数 **1**（第 16 行 javadoc `{@link LeaderboardApi}`，命中判据 3 排除项）→ **核实不成立未删**；② `LeaderboardService.java:3`（`RecordVerifyEvents`）计数 **0** → 已删；③ `LeaderboardService.java:21`（`EnableCaching`）计数 **0**（另核对本文件只有 `@Cacheable` 无 `@EnableCaching` 注解）→ 已删。删后 `git diff 095fd98` 仅 2 行删除 0 行新增；offline 全量 287 全绿即"删了不红"的绿对。TASK-018 handoff 把第①处列为待删是就 checkstyle `UnusedImports` 而言（其不解析 javadoc 引用），本任务判据更严，以实测为准 |
| 契约自证 | 在途 `mailbox-contract.sh --baseline=095fd98`（PLAN 记录追加前）：TASK-121 判据 B 失败且**唯一差异**为 `清单多报（实际未改动）：work/mailbox/PLAN.md`——即本记录自身，符合在途预期；其余历史任务全部"足迹不在工作树"（当时 PLAN.md 未进改动集）。PLAN 记录追加后：TASK-121 判据 B 通过（只改清单 4 项与实际改动集逐项一致）；整体退出码 1 的成因**不在 TASK-121**——PLAN.md 进改动集后历史 handoff 的公共文件 token 交叠触发既往已登记的"公共文件过冲"。收口提交后 `mailbox-contract.sh`（**无参数**）退出 **0** |
| 只改清单一致性 | 实际改动集（`git diff --name-only 095fd98` + untracked 排除 `.trae/`）＝ `LeaderboardService.java` · `TASK-121/spec.md` · `TASK-121/handoff.md` · `PLAN.md`，与 handoff 声明**逐字一致**（无多报、无漏报）。只删 import 行，未动 checkstyle/pmd 配置、未做顺手清理、未改 CI 工作流与统一验收入口；全程未用 `git stash`、未 push |
| 词面自检 | CI 同款正则、`LC_ALL=C`：新增/改动文本载体（台账两件套 + PLAN 追加段）**0 命中**；收口后全仓 `git grep` 同款复跑 0 命中（输出留档于 TASK-121/handoff「删后绿」节） |
| 未覆盖 | ① 第①处 import 未删（javadoc `{@link}` 引用，删了破坏 javadoc 解析），如需让静态检查对该处归零须改 javadoc 为全限定名，属另一最小变更（见 TASK-121/handoff「未决」）；② 默认 locale 下 CI 同款词面自检仍余 2 条（TASK-118/119/120 已登记的 `api/**/MapMatchResultDTO.java:17/36` 本机引擎伪影，只改清单外，未修）；③ 本任务改动待下次 push 由 CI 复验 |
| 归档与后续 | 不自行归档：纯清理 + 台账，无 spec 需求变更（不建 `spec/changes/` 三件套），台账两件套即满足契约判据 A。TASK-018 建议单的 3 处就此闭合 2 处、1 处改判保留（javadoc 引用），后续若做 javadoc 全限定名最小变更可一并复跑 checkstyle/pmd 观察违规归零 |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：三处计数逐项留档 → 删 2 留 1 → diff 仅 2 行删除 → offline 287 全绿零扰动 → 词面自检 0 命中 → 契约在途逐项一致 / 收口后 0，全部实测落档 |

## 验收记录：`静态检查三件套经唯一入口接入 CI（TASK-122，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `ad63c3b`（`git status` 事前仅 `?? .trae/`）；`8858b5f` 入口 --static 子命令 + README · `c32be58` CI 门槛步骤 · 本条记录所在的台账收口提交（**未 push**、未建 PR） |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --static=leaderboard-service`（auto→offline、生效模式 offline、依赖来源可判定未触发退出码 3）→ rc=0 / 两段 BUILD SUCCESS / 0 Checkstyle violations / Total bugs 10；全仓回归 `--mode=offline test` → rc=0 / 2m44s / 模块合计 `17 22 31 80 81 50 6 = 287`（与开工基线逐位一致，Failures 0 / Errors 0 / Skipped 0）。任务包原文写"284"为 TASK-119 增量前旧锚点，实跑以 287 为准并在此登记 |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；CI 新步骤（`--static=leaderboard-service`，online 模式）实际效果待下次 push 复验——尤其第 1 段 install 装料在冷仓无缓存的耗时未实测——此处如实标注不作声称 |
| 红绿取证 | 红绿取证（哨兵）：向 `LeaderboardService.java` 尾部注入 163 字符 CRLF 哨兵注释（原 439 行，工作树 sha256 `4306d384…`）→ `--static` **rc=1**，判别式红在静态检查本身（第 1 段装料 BUILD SUCCESS）：`[ERROR] …LeaderboardService.java:440: 本行字符数 163个，最多：140个。 [LineLength]`（两处输出均含行号 440）。还原（`git show HEAD:<path>` + 非 `-p` 的 `cp` + `touch` + `git checkout-index -f` 归位）：cmp **IDENTICAL**、`git diff` EMPTY、归位后工作树 sha256 回到 `4306d384…` 逐位一致、哨兵残留 0。绿：还原后同命令 **rc=0**（0 violations / Total bugs 10 / 两段 BUILD SUCCESS）。参数错四组均 **rc=2**（`--static=nonexistent-module`、`--static test`、`--static --it`、`--static --pl common`，均不调 Maven） |
| 实现要点 | 子命令两段执行：第 1 段 `-pl <模块> -am clean install -DskipTests` 装料是 **CI 可用性前提**（单模块 reactor 解析不到兄弟模块 SNAPSHOT，CI 从未 deploy），offline 下顺带消除 TASK-018 登记的陈旧内部构件隐患；第 2 段 `-f <模块>/pom.xml test-compile checkstyle:check com.github.spotbugs:spotbugs-maven-plugin:4.9.8.5:check pmd:check`（spotbugs 用全限定 GAV：前缀不在默认插件组、插件只声明在目标模块 pom）。三 goal 依旧**不绑 lifecycle phase**（pom 零改动），CI 既有步骤判据逐字未动 |
| 契约自证 | 在途 `mailbox-contract.sh --baseline=ad63c3b`（PLAN 记录追加后）：TASK-122 判据 A 通过（两件套齐全）、判据 B 通过（只改清单 6 项与实际改动集逐项一致）；整体退出码 1 的成因**不在 TASK-122**——PLAN.md 进改动集后历史 handoff 公共文件 token 交叠触发既往已登记的"公共文件过冲"。收口提交后 `mailbox-contract.sh`（**无参数**）退出 **0** |
| 只改清单一致性 | 实际改动集（`git diff --name-only ad63c3b` + untracked 排除 `.trae/`）＝ `scripts/verify/mvn-verify.sh` · `scripts/verify/README.md` · `.github/workflows/ci.yml` · `TASK-122/spec.md` · `TASK-122/handoff.md` · `PLAN.md`，与 handoff 声明**逐字一致**。未把三 goal 绑进 verify 生命周期、未扩展到其余模块、未动 CI 既有步骤判据、未动契约脚本；全程未用 `git stash`（哨兵还原用 `cp` + 哈希校验）、开工前后各留一份仓库外 bundle |
| 词面自检 | CI 同款正则、`LC_ALL=C`：全仓 **0 命中**、本任务 6 个改动载体单独扫 **0 命中**；默认 locale 仅余 TASK-118 起已登记的 2 条本机伪影（`api/**/MapMatchResultDTO.java:17/36`，本任务未触碰） |
| 未覆盖 | ① CI 效果（online 模式、冷仓装料耗时）待下次 push 复验；② `--static` 传入未声明三插件的模块时按插件默认规则集判定通常直接红，逐模块治理另立变更（README 已写明）；③ 287 之外的既有未覆盖面（真中间件路径等）本任务不新增 |
| 归档与后续 | 不自行归档：纯工程接线 + 台账，无 spec 需求变更（不建 `spec/changes/` 三件套），台账两件套即满足契约判据 A。TASK-018「待主 agent 决定」第 5 条（CI 不跑三 goal、外部门槛为空）由本任务闭合 |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：哨兵红（行号+字数原文）→ 还原 cmp 零差异 → 绿 rc=0 → 参数错 rc=2 ×4 → offline 287 零扰动 → 词面 0 命中 → 契约在途逐项一致 / 收口后 0，全部实测落档（日志 `.trae/tmp/task122-*.log`，不入库） |

## 外部门槛登记：TASK-118~122 整批（2026-09-22，指导侧亲笔）

push `726cf63..8fdb03f`（22 个提交，TASK-118/119/120/121/122 五任务及其收口台账）触发 run
`35745872136`（head `8fdb03f`，2026-09-22 15:13 UTC）——**web/build 两 job 全绿**（web 23s 全 8 步含
Generated router types match committed；build 2m44s 全 7 步：入口 online verify、compose 解析、代表镜像构建、
静态检查门槛、词面自检、JaCoCo 上传）。据此结清五条验收记录中的「待下次 push 由 CI 复验」未决项：

- **TASK-118**：改写后的词面自检在 CI（Linux 口径）首跑零命中，「本机默认 locale 伪影不影响 CI」的判定
  由外部门槛实证结清；台账两处指代表述随本 run 全绿背书。
- **TASK-119**：网关清洗 +3 用例（gateway 19→22）随 build job 全量绿背书；全仓口径自此为
  **287 = 17/22/31/80/81/50/6**，后续任务包以 287 为基线锚点。
- **TASK-120 / TASK-121**：287 零扰动与契约 rc=0 结论由本 run build job 全绿背书。
- **TASK-122**：新 CI 步骤「Static analysis gate（--static=leaderboard-service，online 模式）」**首次外跑
  真实执行且绿**（run 日志含该步骤执行记录，非 skip）；「CI 效果待 push 复验」未决项闭合。
- 五条记录的「是否到达外部门槛」自本登记起统一按 **已到达（run `35745872136`）** 读，记录原文的
  「未 push」表述属登记前事实、保留不改。

指导侧复验收（2026-09-22，现场复跑，不采信文字）：契约脚本无参数 rc=0（判据 A 两件套齐 +
判据 B 清单一致）；唯一入口 `--mode=offline test` → rc=0 / BUILD SUCCESS / 287（17/22/31/80/81/50/6）
零失败零跳过；`--static=leaderboard-service` → rc=0 两段 BUILD SUCCESS；词面自检 CI 同款脚本
（.trae/tmp 不入库）范围内零命中。

## 验收记录：`actuator 白名单与详情暴露收窄（TASK-125，2026-09-22）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `04c6bbc`（`git status` 事前仅 `?? .trae/`）；`0e48d9a` 网关白名单收窄 + 新测试 · `5f9463c` 六服务 show-details · `6156b82` spec 三件套 · 本条记录所在的台账收口提交（**未 push**、未建 PR） |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0 / BUILD SUCCESS / 3m46s / 模块合计 `17 25 31 80 81 50 6 = 290`（gateway 22→25 +3 即 ActuatorWhitelistNarrowTest，其余模块与基线 287 逐位一致零扰动，Failures 0 / Errors 0 / Skipped 0）。生效模式 offline、依赖来源可判定（未触发退出码 3） |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；待下次 push 由 CI 复验，此处如实标注不作声称 |
| 红绿取证 | **红**（改前 yml，只加测试）：`mvn -pl gateway-service -am test -Dtest=ActuatorWhitelistNarrowTest` → Tests run: 3, Failures: 2, **rc=1**，失败原文：`指标查询端点 /actuator/metrics 不得经网关裸放行，实际=[/api/auth/**, /actuator/**] ==> expected: <false> but was: <true>`（:82）、`指标查询端点无 token 必须 401（走鉴权分支）… expected: <401 UNAUTHORIZED> but was: <null>`（:112）。**绿**（改后）：Tests run: 3, Failures: 0, **rc=0**。**变异验证**（TASK-106 手法）：修复态副本 sha256 `5f676ce3…` 留底 → whitelist 临时改回 `/actuator/**` → 复现同样 2 失败 rc=1（断言与行号逐字同红）→ `cp` 还原 → `sha256sum -c` OK + `cmp` IDENTICAL |
| 实现要点 | 网关 `whitelist: /api/auth/**,/actuator/**` → `/api/auth/**,/actuator/health`（matchesPrefixList 对无 `/**` 后缀模式走精确匹配，探针放行、metrics/env/prometheus 落回鉴权分支，相邻注释同步）；六处 `show-details: always` → `never`（gateway yml:152、user yml:95→96、record properties:88→89、mapmatch yml:72→73、leaderboard yml:117→118、verify yml:174→175，实际命中数 6、其中 record 为 properties 格式）。include 列表与监控栈编排未动（prometheus 六 target 均内网直连 8080-8085，不经网关） |
| 规格判定 | Grep 主规格：`/actuator/**` 写进两处需求文本（「网关统一鉴权·白名单放行」GIVEN spec.md:1988、「白名单收紧」正文 :2083 +「白名单无 /internal/**」AND 子句 :2104）⇒ 建三件套 `spec/changes/narrow-actuator-exposure/`（MODIFIED 两需求 + EARS，见提交 `6156b82`）；scripts/perf 与 compose 仅依赖 /actuator/health 或内网直连，停止条件不触发 |
| 契约自证 | 在途 `mailbox-contract.sh --baseline=04c6bbc`（PLAN 记录追加后、收口提交前）：TASK-125 判据 A 通过（两件套齐全）、判据 B 通过（只改清单 13 项与实际改动集逐项一致）；整体退出码 1 的成因**不在 TASK-125**——PLAN.md 进改动集后历史 handoff 公共文件 token 交叠触发既往已登记的"公共文件过冲"。收口提交后 `mailbox-contract.sh`（**无参数**）退出 **0**（收口提交后实测回填） |
| 只改清单一致性 | 实际改动集（`git diff --name-only 04c6bbc` + untracked 排除 `.trae/`）＝ 网关 application.yml · 五服务 yml/properties（5 个）· ActuatorWhitelistNarrowTest.java · spec/changes/narrow-actuator-exposure/ 三件套（3 个）· TASK-125/spec.md · TASK-125/handoff.md · PLAN.md，与 handoff 声明**逐字一致**。未动 include 列表、监控栈编排、网关路由、AuthGlobalFilter.java（@Value 默认值与 javadoc 的 /actuator/** 漂移登记 handoff 未决）；全程未用 `git stash`（变异用 cp + 哈希校验） |
| 词面自检 | CI 同款正则、`LC_ALL=C`：全仓 **ZERO-HIT**；默认 locale 仅余 TASK-118 起已登记的 2 条本机伪影（`api/**/MapMatchResultDTO.java:17/36`，本任务未触碰） |
| 未覆盖 | ① AuthGlobalFilter 的 `@Value` 默认值 `,/actuator/**` 与 javadoc 未同步（Java 文件不在只改清单，fallback 漂移不生效）；② 在途变更 add-auth-degrade-header-strip 的 delta 场景 GIVEN 仍写 `/actuator/**`，待其并入主规格时修正；③ 默认 locale 词面伪影 2 条（同上，只改清单外）；④ 本次改动待下次 push 由 CI 复验 |
| 归档与后续 | **不自行归档**（按任务包边界）：三件套已建，归档（spec-delta 并入 spec.md + 移 archive/）待统一 openspec 归档步骤；届时 `spec/changes/` 下将有 2 个未归档变更（add-auth-degrade-header-strip + 本变更） |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：红（断言原文+行号）→ 绿 rc=0 → 变异复红 → 还原 cmp 零差异 → offline 290（gateway+3 其余零扰动）→ 词面 LC_ALL=C 零命中 → 契约在途逐项一致 / 收口后 0，全部实测落档（日志 `.trae/tmp/task125-*.log`，不入库） |

## 验收记录：`Sentinel 网关兜底路由补全（TASK-124，2026-09-23）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `e6c2643`（`git status` 事前仅 `?? .trae/`；但工作树已含并行会话 TASK-126（add-strict-secret-fail-fast）的在途改动，见下「并行在途」行）；`d181b90` ROUTE_IDS 补全 + SentinelRouteCoverageTest · 本条记录所在的台账收口提交（**未 push**、未建 PR） |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0 / BUILD SUCCESS。**取证快照**（00:26，TASK-126 尚未落新用例）：模块合计 `17/27/31/80/81/50/6 = 292`（gateway 25→27，+2 即 SentinelRouteCoverageTest 两用例；其余 6 模块与基线 290 逐位一致零扰动，Failures 0 / Errors 0 / Skipped 0）。**收口修订复跑**（docs commit 前）：rc=0 / `20/29/33/80/81/50/6 = 299`——较快照 +7 全部为并行会话 TASK-126 在途新增用例（common +3、gateway +2 即 JwtTokenParserTest 3→5、user +2），**本任务贡献恒为 gateway +2**，与基线 290 的逐位对照以 292 快照为准。生效模式 offline、依赖来源可判定未触发退出码 3。任务包原文「gateway 22→23+，全仓 287→288+」为 TASK-125 增量前旧锚点，实跑以 292 快照/299 终跑为准并登记 |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；待下次 push 由 CI 复验，此处如实标注不作声称 |
| 红绿取证 | **红**（改前代码，只加测试）：`mvn -pl gateway-service -am test -Dtest=SentinelRouteCoverageTest -Dsurefire.failIfNoSpecifiedTests=false` → Tests run: 2, Failures: 1, **rc=1**，失败原文（SentinelRouteCoverageTest.java:66）：`Sentinel 兜底路由缺失（Nacos 无规则时这些路由无限流兜底），缺失=[route-admin-service, route-auth-service, route-leaderboard-service, route-mapmatch-service]，兜底实际覆盖=[route-record-service, route-user-service, route-verify-service]，yml 路由表=[7 条] ==> expected: <true> but was: <false>`——缺失清单与任务包「缺的 4 条」逐字一致。**绿**（补全后同命令）：Tests run: 2, Failures: 0, **rc=0**。**变异验证**（TASK-106/125 手法）：修复态副本 sha256 `617fb308…` 留底 → 临时删 `route-mapmatch-service` → 复红 rc=1 且缺失清单恰为 `[route-mapmatch-service]` → `cp` 还原 → sha256 回 `617fb308…` 逐位一致 + `cmp` IDENTICAL；全程未用 `git stash` |
| 实现要点 | `ROUTE_IDS` 3→7 条（route-auth/user/record/leaderboard/verify/admin/mapmatch-service，顺序对齐路由表），javadoc 注明一致性由测试机械校验；判别式测试从 classpath `application.yml` 以正则提取 `- id:` 路由（自检 route- 前缀防锚点漂移）与 `defaultRules()` 本体（非 ROUTE_IDS 字段镜像）双向比对；Nacos 数据源注册、阈值/窗口默认值、fallback 配置零改动（git diff 仅 ROUTE_IDS 块 + 注释） |
| 规格判定 | 不建 spec 三件套：补全既有 §8.3 限流基线（`add-sentinel-dynamic-rules` 落地的兜底默认）的实现覆盖，无新需求无 spec 文本变更；台账两件套即满足契约判据 A，handoff 注明 |
| 契约自证 | 在途 `mailbox-contract.sh --baseline=e6c2643 --open=TASK-126`（PLAN 记录追加后）：TASK-124 判据 A 通过（两件套齐全）、判据 B **零多报**——5 项声明全部在实际改动集中，12 条「改动集未声明」逐项核对全部为并行会话 TASK-126 的在途足迹（JwtTokenParser/JwtTokenParserTest/JwtUtil/JwtUtilTest/InternalApiFeignInterceptor/InternalApiAuthFilter/gateway yml app.security.strict 段/TASK-126 台账/add-strict-secret-fail-fast 三件套），不属本任务；整体 rc=1 的其余成因还有 PLAN.md 公共文件过冲（历史 handoff 交叠，既往已登记）。收口提交后无参数跑：**rc=1，成因不在 TASK-124**——TASK-124 足迹已全部落库（「足迹不在工作树，视为已收口」），残留失败为 TASK-126 在途（仅 spec 未声明压判据 A + 其在途文件压历史 PLAN 声明），待该会话自行收口（实测回填见 handoff） |
| 并行在途 | 本任务执行期间工作树存在并行会话 TASK-126 的实时改动（JwtTokenParserTest 在 `getStartupError`/`getFailure` 两种形态间迭代，曾致 2 次 `--pl gateway-service` 定向复跑以既有测试编译红 rc=1 收场——编译错误位于 TASK-126 正在编辑的 JwtTokenParserTest.java:94，与本任务改动无因果；该文件最终形态的编译/用例结论归属 TASK-126）。本任务红绿/变异/全量取证均只用与本任务改动有因果的判据，TASK-126 中间态不参与任何「通过」声称 |
| 只改清单一致性 | 实际改动集中属本任务的文件（`git diff --name-only e6c2643` + untracked 排除 `.trae/` 与 TASK-126 足迹）＝ `SentinelGatewayRuleConfig.java` · `SentinelRouteCoverageTest.java` · `TASK-124/spec.md` · `TASK-124/handoff.md` · `PLAN.md`，与 handoff 声明**逐字一致**。未动 Nacos 注册逻辑、阈值/窗口、fallback、路由表；未触碰 TASK-126 的任何在途文件；全程未用 `git stash`（变异用 cp + 哈希校验），开工前仓库外 bundle 留底 |
| 词面自检 | CI 同款正则、`LC_ALL=C`：全仓 **ZERO-HIT**；本任务 5 个改动载体单独扫 **ZERO-HIT**；默认 locale 仅余 TASK-118 起已登记的 2 条本机伪影（`api/**/MapMatchResultDTO.java:17/36`，本任务未触碰） |
| 未覆盖 | ① 本次改动待下次 push 由 CI 复验；② TASK-126 在途任务的最终编译/用例结论（含 gateway 用例总数随之变化）归属该任务，本任务的全量计数以取证时刻快照为准并如实登记；③ 默认 locale 词面伪影 2 条（清单外既有登记） |
| 归档与后续 | 不自行归档：纯兜底补全 + 台账，无 spec 需求变更（不建 `spec/changes/` 三件套），台账两件套即满足契约判据 A |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：红（缺失清单原文+行号）→ 绿 rc=0 → 变异复红 → 还原 cmp 零差异 → offline 快照 292（gateway+2 其余零扰动）/ 收口修订 299（差值 +7 归属 TASK-126 在途）→ 词面 LC_ALL=C 零命中 → 契约在途 TASK-124 范围逐项一致 / 收口后 0，全部实测落档（日志 `.trae/tmp/task124-*.log`，不入库） |

## 验收记录：`密钥默认值治理与常量时间比较（TASK-126 / add-strict-secret-fail-fast，2026-09-23）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `e6c2643`（开工时 `git status` 仅 `?? .trae/`；执行中并行会话 TASK-124 落 `d181b90`，其台账两件套至本记录时仍处已暂存未提交，本任务全程未触碰）；6 个分批提交（common/api/user/gateway/spec/docs），末位为本条记录所在的收口提交（**未 push**、未建 PR） |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0 / BUILD SUCCESS / 模块合计 `20/29/33/80/81/50/6 = 299`（Failures 0 / Errors 0 / Skipped 0）。开工快照 292（17/27/31/80/81/50/6，含 TASK-124 并行 +2），净增 7 = common +3、gateway +2、user +2，其余四模块逐位一致零扰动。生效模式 offline、依赖来源可判定（未触发退出码 3）。全量第一次 rc=1 为 record-service「程序包 com.sportverify.common.* 不存在」编译红——common target/classes 实际完整、源码零改动，判定瞬时类路径抖动非用例红，原样重跑即 rc=0（已登记，CI 复现需另查） |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；待下次 push 由 CI 复验，此处如实标注不作声称 |
| 红绿取证 | 判别式形态：`ApplicationContextRunner` 纯属性驱动（不引用新 API）。**红**（实现前）：common `InternalApiAuthFilterTest.strictTrueWithoutTokenFailsStartup:85→86` rc=1（Tests run 20, Failures 1——strict=true 不注入 token 上下文仍启动成功）；gateway `JwtTokenParserTest.strictTrueWithoutSecretFailsStartup:92→93` rc=1（29/1）；user 定向被 `-am` 链 common 红阻断，其自身红以变异轮补齐。**绿**（实现后）：common 20/0 rc=0、user 33/0 rc=0、gateway 29/0 rc=0，反向绿（strict=true+显式密钥启动成功）与零扰动判别式（strict 缺省）均绿，既有测试零回归。过程注记（如实登记）：①判别式初版误用 `getStartupError()`（不在本仓 spring-boot-test 3.2.4 的公开 API，javap 实核为 `getFailure()`；BeanCreationException 三层包装下改 `hasRootCauseInstanceOf`+`hasStackTraceContaining`），两轮编译红后订正；②全量第一次 rc=1 同上 |
| 变异验证 | 三判别式逐一摘除（`strictMode`→`false &&`，可编译）：common `:85→86` 红 rc=1、gateway `:92→93` 红 rc=1、user `JwtUtilTest.strictTrueWithoutSecretFailsStartup:75→76` 红 rc=1（33/1，需单独变异——三文件同变时 user 定向被 `-am` 链 common 红阻断，首轮还因占位符 `MUTATED` 未声明成编译红作废重做，均如实登记）；还原为字节级 `copyfile`，sha256 一致 + cmp 零差异（文本模式回写曾引入行尾漂移，已用字节级备份消除）；全程未用 `git stash` |
| 实现要点 | `app.security.strict`（默认 false 零扰动）：JwtUtil/JwtTokenParser 新增 `@Autowired` 构造器（带 strict 参数，旧三参/单参构造保留 strict=false 委托，既有测试零改动）；common `@PostConstruct` 判别 + `MessageDigest.isEqual`（UTF-8 字节常量时间比较，`expectedToken != null && provided != null` 守卫与原 equals 语义等价）；api `InitializingBean.afterPropertiesSet`（模块无 jakarta.annotation-api 直接依赖，同型判别）；gateway yml 仅新增 `security.strict: false` 声明，各密钥演示默认值原样保留；@Value 兜底字面量改为与 `DEMO_TOKEN`/`DEMO_SECRET` 常量拼接防漂移 |
| 规格判定 | 主规格有对象：「内部接口共享密钥校验」（spec.md:2113 + Scenario 密钥默认值不得用于生产 :2132-2135）与 JWT 鉴权域（:1967）⇒ 建三件套 `spec/changes/add-strict-secret-fail-fast/`（ADDED 2 Requirement：密钥注入严格模式、内部接口密钥常量时间比较，EARS） |
| 契约自证 | 在途 `--baseline=e6c2643`（全工作树）整体 rc=1：TASK-124 并行足迹 + 历史公共文件过冲，非本任务；`--baseline=d181b90 --diff-file=<本任务 14 文件>` → **`TASK-126：判据 B 通过（只改清单与实际改动集一致）`**（整体 rc=1 为 18 个历史任务在 diff-file 口径下的交叠噪声）。收口提交后无参数跑：**退出码 0**（契约校验通过：判据 A 两件套齐含 0 个待办进行中 + 判据 B 清单一致；TASK-126 足迹不在工作树视为已收口）；收口修订树（`96b60bd` 后仅台账回填）复跑全量 offline rc=0 / `20/29/33/80/81/50/6 = 299`，门槛数字绑定收口修订 |
| 只改清单一致性 | 实际改动集（`git diff --name-only d181b90` + untracked 排除 `.trae/`，剔除 TASK-124 并行足迹）＝ api/api-Interceptor · common/Filter + FilterTest · gateway/JwtTokenParser + application.yml + JwtTokenParserTest · user/JwtUtil + JwtUtilTest · spec 三件套（3）· TASK-126/spec.md · TASK-126/handoff.md · PLAN.md，与 handoff 声明逐字一致（14 项） |
| 词面自检 | CI 同款正则与排除、`LC_ALL=C`：全仓 **ZERO-HIT**；默认 locale 仅余 TASK-118 起登记的 2 条本机伪影（`api/**/MapMatchResultDTO.java:17/36`，本任务未触碰） |
| 未覆盖 | ① api 侧 strict 判别未建独立测试（api 模块无测试基建 + 不新增依赖约束；实现与 common 逐字同型）；② 全量第一次 rc=1 的瞬时抖动根因未深挖（未复现第二次）；③ 默认 locale 词面伪影 2 条（清单外既有）；④ 未 push，待 CI 复验 |
| 归档与后续 | **不自行归档**（按任务包边界）：三件套已建；届时 `spec/changes/` 未归档变更将达 3 个（add-auth-degrade-header-strip、narrow-actuator-exposure、本变更） |
| 指导侧复验收 | 收口授权下放（指导侧不复跑）；执行侧自证：红（判别式 + 行号 + Tests run）→ 绿 rc=0 → 三判别式变异复红 → 字节级还原 cmp 零差异 → offline 299（+7 全部为本任务新增判别式）→ 词面 LC_ALL=C 零命中 → 契约 diff-file 口径判据 B 通过，全部实测落档（日志 `.trae/tmp/t126-*.log`，不入库） |

## 裁定记录：TASK-123 停手冲突裁决（2026-09-23，指导侧亲笔）

执行侧前置核实命中 ADR-0009 既有约定（`docs/adr/0009-事务边界.md:57` 禁止事项「不改 FriendService
锁与事务的嵌套顺序」、`:32` 分类表「保持」、守卫测试 `FriendServiceTest.java:226`
accept_twoWritesShareTransactionalMethod 断言 accept 带方法级 @Transactional），按任务包停止条款
停手回传，未留任何工作树足迹。指导侧裁定采 **方案 B：维持 ADR-0009 权衡，关闭 TASK-123**。理由：

1. **失效路径全部拒绝式收敛，无正确性缺陷实证**。释锁早于提交的窗口内，并发 createRequest 读到的
   是旧已提交状态（申请仍 PENDING）——既有守卫「同向重复申请被拒」「反向 PENDING 已存在被拒」
   在该状态下恰好照常拒绝；friendship 双插被规范化主键兜底（DuplicateKeyException 幂等），
   accept/reject 竞态被 updateStatus 乐观流转（rows==0 → 5002）兜住。旧状态只会多拒不会漏放，
   未见可复现的错误终态。
2. **ADR-0009 是带守卫测试的已采纳决策**，推翻它需要实证级别的正确性论据；本项收益是并发窗口的
   概率性优化，不构成推翻条件（「不做无实证的重构」）。
3. 方案 A 的成本（修订两处 ADR + 备选否决节 + 重写守卫测试）远超收益，且开了「任务顺手改 ADR」的口子。

F04 据此在 findings 台账标记为「已裁定维持权衡、不修」。若未来出现可复现的好友状态错乱缺陷，
以缺陷工单重开（附复现路径），届时按方案 A 的扩权重派路径执行。

## 外部门槛登记：TASK-124~126 + TASK-123 裁定（2026-09-23，指导侧亲笔）

push `04c6bbc..eba0108`（13 个提交：TASK-125 四笔 → TASK-124 两笔 → TASK-126 六笔 + 裁定登记一笔）触发
run `35802403723`（head `eba0108`，2026-09-23 00:31 UTC）——**web/build 两 job 全绿**（3m6s，含
--static 门槛与词面自检；上一次 docs-only run `35746589858` 亦全绿，补记）。据此结清：

- **TASK-124**：Sentinel 兜底路由 7 条补全 + 路由覆盖测试，随 build job 全量绿背书；
- **TASK-125**：actuator 白名单收窄（/actuator/health 精确匹配）+ 六服务 show-details never +
  ActuatorWhitelistNarrowTest，CI 词面/静态门槛全绿背书；spec 变更 narrow-actuator-exposure 在途待归档；
- **TASK-126**：app.security.strict 开关 + 常量时间比较，全量 299（20/29/33/80/81/50/6）绿背书；
  spec 变更 add-strict-secret-fail-fast 在途待归档；
- **TASK-123**：指导侧裁定方案 B（维持 ADR-0009 权衡，F04 标记已裁定不修），无代码足迹；
  裁定提交内含 TASK-124 台账词面自伤修复，该修复的红绿证据（修前双 locale 各 1 命中 → 修后双 locale
  ZERO-HIT）由本 run 词面自检步骤绿最终实证。
- 基线锚点自此为 **299 = 20/29/33/80/81/50/6**，后续任务包以 299 为基线。
- 三条验收记录的「待下次 push 由 CI 复验」未决项自本登记起结清，按**已到达（run `35802403723`）**读。
- 通用规则示例更新要求：「台账不得复制禁用词表原文」为第二次复发（TASK-118、TASK-124），
  已写入后续任务包通用规则第 10 条示例。

## 验收记录：`归档三个 spec 变更并收敛两处 actuator 漂移（TASK-127，2026-09-23）`

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `415d36d`（开工时 `git status` 仅 `?? .trae/`，无并行在途足迹）；3 个分批提交（fix(gateway) / docs(spec) / docs(mailbox)），末位为本条记录所在的收口提交（**未 push**、未建 PR） |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test` → rc=0 / BUILD SUCCESS / 模块合计 `20/30/33/80/81/50/6 = 300`（Failures 0 / Errors 0 / Skipped 0）。基线 299（20/29/33/80/81/50/6）：gateway 29→30（+1 判别式），其余六模块逐位一致零扰动。生效模式 offline、依赖来源可判定（未触发退出码 3） |
| 是否到达外部门槛 | 本次**不 push**（任务硬边界），无新外部 run；待下次 push 由 CI 复验，此处如实标注不作声称 |
| 漂移②红绿取证 | 判别式：ApplicationContextRunner 不注入 whitelist 属性实例化 AuthGlobalFilter（注册 JwtTokenParser bean + 名为 conversionService 的 ApplicationConversionService bean 复刻 Boot 生产语义——裸 runner 对 @Value 的 List 不按逗号拆分，首轮红证实测为单元素整串，对齐后以最终形态重取红），断言生效白名单等于新默认值及 health 放行 / metrics 不放行 / login 放行三条行为判别。**红**：定向 `--pl gateway-service test` rc=1，`Tests run: 30, Failures: 1`，唯一红 `defaultWhitelistEqualsHealthProbeOnly:133->lambda:138`，`but was: ["/api/auth/**", "/actuator/**"]`。**绿**：@Value 默认值改为健康探针精确匹配 + 类注释同步 → 同命令 rc=0，`Tests run: 30, Failures: 0`。**变异验证**：修复态 cp + sha256 留底（`8a415b32…` / `6659a251…`）→ sed 临时还原旧默认值复红 rc=1（同一判别式）→ 字节级 cp 还原 → `sha256sum -c` 两文件 OK + cmp IDENTICAL；全程未用 `git stash` |
| 漂移① | add-auth-degrade-header-strip 的 spec-delta 白名单场景 GIVEN 整段通配 → `/actuator/health`（并档前修正，TASK-125 登记的未决项结清）；同文件头部「本次不归档」说明同步为已归档事实（描述归档状态句，非需求原文）；需求 WHEN/SHALL 与 Scenario 语义零改写 |
| 归档 | 三个 delta 按 MODIFIED/ADDED 原文逐字并入主规格「鉴权」域（网关统一鉴权 +2 场景、白名单收紧整节 5 场景替换、网关降级路径剥离身份头 / 密钥注入严格模式 / 内部接口密钥常量时间比较三条新增）；头部提案清单与变更历史各补三条；tasks.json 三个各补归档阶段（全 completed）；`git mv` 整目录入 `spec/changes/archive/`，收口后 `spec/changes/` 下在途 0 个 |
| 契约自证 | 在途 `mailbox-contract.sh --baseline=415d36d`：TASK-127 判据 A 两件套齐全、**判据 B 通过**（只改清单 15 项与实际改动集逐项一致，含重命名落点新路径口径；首轮曾因清单列表后的说明段落被截取块吸收、裸文件名 token 记为多报，把说明移出截取块后复跑通过，日志 .trae/tmp/task127-contract-inflight2.log）；整体在途 rc=1 为 19 个历史任务公共文件过冲（本任务 PLAN.md 进改动集的既知交叠），不属本任务。收口提交后无参数复跑：**退出码 0**（`契约校验通过：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`，.trae/tmp/task127-contract-final.log 实测回填）；收口修订终跑全量 offline rc=0 / `20/30/33/80/81/50/6 = 300`，门槛数字绑定收口修订 |
| 只改清单一致性 | 实际改动集（`git diff --name-only 415d36d` + untracked 排除 `.trae/`）＝ AuthGlobalFilter.java · ActuatorWhitelistNarrowTest.java · 主规格 spec.md · 三个变更目录 9 文件（重命名落点口径：proposal 3 份纯移动零改动、delta 3 份中 1 份漂移①修正、tasks.json 3 份补归档阶段）· TASK-127/spec.md · TASK-127/handoff.md · 本文件，与 handoff 声明逐字一致（15 项） |
| 词面自检 | CI 同款正则、双 locale：`LC_ALL=C` 全仓 **ZERO-HIT**；默认 locale 本轮实测同为 0 命中（既往登记的 api 模块 DTO 2 条伪影本轮未复现，如实记录不据此销案） |
| 未覆盖 | ① 本次改动待下次 push 由 CI 复验；② 判别式的 conversionService bean 是对 Boot 生产转换语义的复刻（若未来 Boot 升级改变该机制，判别式形态需随之复核，测试注释已说明） |
| 归档与后续 | 本任务即归档执行：收口后 `spec/changes/` 仅剩 archive/（22 个），TASK-125 handoff 两条未决（漂移①②）自本记录起结清 |

## 验收记录：TASK-128 核实 F03/F09 事件可靠性现状（2026-09-23，子 agent 纯核实）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `c2479ad`（开工时 `git status` 仅 `?? .trae/`，无并行在途足迹）；1 个收口提交 docs(mailbox)，未 push |
| 任务性质 | 纯核实 + 台账更新，**零代码改动**，无先红/后绿/变异环节 |
| 核实结论 | **F03 仍在**：a048745（实为 TASK-102 产物，非派发所称 TASK-108）只新增 outbox 组件 6 文件，VerifyService（:109/:234）与 VerifyEventProducer（:53-60 直发 + :57-60 catch 吞异常）从未接线；全仓无写 verify_event_outbox 行的代码，sql/ 无 DDL，运行日志实证表不存在。**F09 仍在**：双消费者 RECONSUME_LATER + 自建 DLQ 双轨与无 TTL retryKey 原样（VerifyEventConsumer :52/:140/:225-233/:236-245；LeaderboardEventConsumer :55/:126/:216-224/:227-236）。台账原判断正确，派发背景「已闭环」不成立 |
| 附带发现 | TASK-102 handoff 虚报接线（git log -S 全历史无 VerifyService 调用证据）；TASK-102 spec 第 2/3 条（F09 去双轨、F22 重入锁）按代码现状未见落地 |
| 门槛来源 | 本地实跑 offline 全量 rc=0 / BUILD SUCCESS / `20/30/33/80/81/50/6 = 300`，与 TASK-127 锚点逐位一致零扰动（任务包 299 为过期锚点） |
| 词面自检 | `LC_ALL=C` ZERO-HIT；默认 locale 2 命中为 TASK-118 起既登记的本机伪影（api 模块 DTO，本任务未触碰） |
| 契约 | 在途 `--baseline=c2479ad --diff-file=<本任务 4 文件>` → **`TASK-128：判据 B 通过（只改清单与实际改动集一致）`**（.trae/tmp/task128-contract-difffile.log；整体 rc=1 为 19 个历史任务在 diff-file 口径下的交叠噪声，非本任务）；收口提交后无参数复跑：**退出码 0**（`契约校验通过：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`，.trae/tmp/task128-contract-final.log；首次前台跑曾被看门狗 SIGTERM 截断，后台重跑取到完整样本） |
| 未决 | 待主 agent 裁定立项：① outbox 接线（VerifyService 两处 + VerifyOutboxService 真写 outbox 行 + sql DDL + producer catch 去留）② F09 去双轨 ③ TASK-102 handoff 虚报口径修订。差距原文见 tasks/TASK-128/handoff.md |

## 验收记录：TASK-129 VerifyService 同 recordId 并发重入后果链核实（2026-09-23，子 agent 核实 + 文档化）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `ae811e0`（开工时 `git status` 仅 `?? .trae/`）；`a5e666a` javadoc 权衡段 · `614668c` findings F22 裁定 + 台账两件套 · 本条记录所在的收口提交，未 push |
| 任务性质 | 核实 + 文档化：VerifyService.java 仅 javadoc（15 行插入，零逻辑变更），无先红/后绿/变异环节 |
| 核实结论 | **F22 已裁定文档化接受（2026-09-23）**：后果链四环节——① 并发双判定窗口实证（verify :79-118 无互斥；MQ 消费 eventId 去重拦不住同 recordId 重入，VerifyEventProducer :41 每次 UUID；Feign 直调入口 InternalVerifyController :42-46 无去重）；② 落库无冲突（initVerifying INSERT IGNORE / upsert ON DUPLICATE KEY 只覆盖，VerificationResultMapper :17-28），回调冲突路径 3003 = RECORD_STATUS_INVALID（ResultCode :44；SportRecordService :171 幂等跳过 / :175-179 乐观锁 rows==0 抛 3003）；③ 收敛：消费端删去重键 + RECONSUME_LATER 重投（VerifyEventConsumer :190-197），重入 verify 读终判走补偿回调（VerifyService :81-84/:131-142）；④ 榜单幂等：per-record Redisson 锁 + 锚点行 INSERT IGNORE/乐观 UPDATE，双 VERIFIED 事件只加分一次（LeaderboardService :128-154），结算任务 10min 纠偏兜底（:327-372）。**无双份加分可复现路径**——「冲突拒绝式收敛 + 消费幂等兜住」成立。窄窗备注（登记不立项）：灰度规则集中途变更可致一 VERIFIED 一 REJECTED，净效果零加分，属规则热更新既有最终一致设计 |
| 文档化改动 | javadoc 补「并发重入权衡」段（三层兜底 + 重开条件，对齐 ADR-0009 表述风格）；findings F22 标裁定 + 重开条件（锚点行幂等或回调收敛链路失效/出现可复现错态时再立项可配锁）。未引入任何新互斥 |
| 门槛来源 | 本地实跑 offline 全量两跑均 rc=0 / BUILD SUCCESS / `20/30/33/80/81/50/6 = 300`（首跑 .trae/tmp/task129-offline.log；收口修订终跑 .trae/tmp/task129-offline-final.log），与 TASK-127 锚点逐位一致零扰动（任务包 299 为过期锚点） |
| 词面自检 | `LC_ALL=C` 与 `zh_CN.UTF-8` 均 ZERO-HIT（.trae/tmp/task129-wording.sh）；无 LC_ALL 默认 locale 2 命中为 TASK-118 起既登记的本机伪影（api 模块 DTO :17/:36，本任务未触碰），按未覆盖登记 |
| 契约 | 在途 `--baseline=ae811e0` 首跑 TASK-129 判据 B 红，根因是本 handoff 早先的「文档化改动」标题命中 awk 截取词、说明节裸文件名 token 被判清单多报（TASK-127 同款坑），改标题隔断后复跑本任务仅余 `PLAN.md` 未落盘的预期中间态多报（.trae/tmp/task129-contract-inflight2.log；TASK-128 段 2 条「改动集未声明」为其历史清单扫到本任务新文件的既有交叠噪声）；收口提交后无参数复跑：**退出码 0**（.trae/tmp/task129-contract-final.log） |
| 未决 | 无待裁定项（裁定按任务包口径落地）；未达外部门槛（未 push，仅本地实跑） |

## 验收记录：TASK-130 性能热点与治理面现状侦察（2026-09-23，子 agent 纯侦察）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `f2b58c3`（开工时 `git status` 仅 `?? .trae/`）；本条记录所在的收口提交，未 push |
| 任务性质 | 纯侦察：**零代码/配置/规格改动**（只改 `work/` 下 4 个 md），无先红/后绿/变异环节 |
| 核实结论（清单 4 项全部仍在） | ① **F05**：MapMatchService.java:110-126 逐点一次 ST_DWithin 原样（往返上界由 max-sampled-points=200 决定，原文「50」为示意值）；② **F06**：LeaderboardService.java:260 `reverseRangeWithScores(0,-1)` 原样（**原文行号 250-251 已漂移，订正为 259-260**；且 topFriends 无 @Cacheable，每请求付全量成本）；③ **F15~F17**：RecordLikeService.java:264-291 N+1 对账、:80 硬编码 FLUSH_BATCH=200、:329-339 readCount 无防击穿，**三条全部仍在**，且**未被 TASK-108 系列顺带解决**（该服务全史仅 4 笔提交，无一条命中）；④ **治理面**：InternalApiAuthFilter 只护 `/internal/**`，5 服务全覆盖但治理面路径（`/admin/**`→verify `/api/appeals/**`、`/verify/rules/**`→`/rules/**`、榜单日报）不在覆盖面内；角色校验唯一落点 AuthGlobalFilter:102-105，服务侧 `X-Role` 读取点 0、声明式鉴权 0 命中 |
| 跨条目重大发现 | **TASK-103 台账虚报（七条声称改动全仓零落地）**：`git log --all -S` 对 6 个标识（fetchCandidateEdges / FRIEND_SCAN_BATCH / selectCountsByRecord / lock:like:count-init / idx_status_created / record.like.flush-batch）**全部只命中 `6650ae3`（台账提交自身）**，无任何代码提交；当前代码逐条反证（selectDistinctRecordIds 仍在 :68-69、FriendService 仍 @Transactional:151 等）；PLAN 无 TASK-103 验收记录。与 TASK-102（TASK-128 已核实）同类 |
| 附带发现（登记不立项 → 本轮转立项建议） | **F08 残留**：gateway application.yml:158 仍 `show-details: always`（TASK-125 目标写「六个服务」但只改文件漏了网关该行），而 `/actuator/health` 在网关白名单内免 token → 匿名可读组件明细；**F18 仍在**：sql/02-record-db.sql:13-30 sport_record 无 idx_status_created；**好友榜静默截断**：listFriends(page=1,size=1000) 只取首页 |
| 环境可行性 | **DB 侧实测不可行（记未覆盖）**：Docker daemon 未运行；`.env` 指向容器端口 3307/5433 均 CLOSED；本机 3306/5432 为原生 MySQL 8.0.44 / PostgreSQL 16.14（非本项目实例）且口令不符 → 无任何可达业务库或 scratch 库，报告量化一律标注为静态推演假设 |
| 门槛来源 | 本地实跑 `bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS / `20/30/33/80/81/50/6 = 300`**（Failures 0 / Errors 0 / Skipped 0），与 TASK-127 起收口锚点逐位一致**零扰动**（任务包所写 299 为过期锚点）。日志 `.trae/tmp/task130-offline.log` |
| 未达外部门槛 | **未达**（未 push，仅本地实跑） |
| 词面自检 | `LC_ALL=C` **ZERO-HIT**（CI 同款正则，`.trae/tmp/task130-wording.sh`，含本任务 4 文件直扫）；默认 locale 2 命中为 TASK-118 起既登记的本机伪影（`api/.../MapMatchResultDTO.java:17/36`，本任务未触碰），按未覆盖登记 |
| 契约 | 在途 `--baseline=f2b58c3` → **`TASK-130：判据 B 通过（只改清单与实际改动集一致）`**（.trae/tmp/task130-contract-inflight2.log；整体 rc=1 为历史任务（TASK-128/129 等）在公共文件 `PLAN.md` 上的既有交叠噪声，本任务段零多报零未声明）；收口提交 `a93c88b` 后无参数复跑：**退出码 0**（`契约校验通过：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`，.trae/tmp/task130-contract-final.log） |
| 立项建议（交主 agent 写任务包） | **建议立项**：F05 批量预筛（P1，叠 K 分块）、F06 分批取数（P1，含 rank 等价性说明）、F15+F16 合成「点赞规模化」（P1+P2）、F08 残留一行（P2）、F18 索引（P2）；**需用户拍板**：F17 防击穿口径（Caffeine 单飞 vs SETNX，需与 F13 既有结论对齐）、治理面方向（维持 ADR 边界并把凭证硬化扩到治理面路径 vs 服务侧二道防线——**「服务侧读 X-Role」为零增量假硬化，两方向都应排除**）、好友榜 >1000 截断是否按演示规模口径接受、TASK-103 台账虚报订正口径；**建议关闭**：F16 单列（并入 F15 同一变更）。逐项证据/量化假设/红绿判别式可行性见 tasks/TASK-130/handoff.md |
| 未决 | 上表「需用户拍板」四项待裁定；scratch PostGIS / MySQL 语义 IT 与全栈直连实测本期未覆盖（环境不可用），已如实登记 |

## 验收记录：TASK-131 判定事件走事务内 outbox、relay 唯一投递（2026-09-23）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `9e64196`（开工时 `git status` 仅 `?? .trae/`）；`d866ca0` outbox 接线 + 建表脚本 · `e660d4e` 变更三件套 · `7333ed3` 台账两件套与 PLAN · 本条记录所在的契约实测回填提交（收口），未 push |
| 任务性质 | 代码接线：`VerifyService` 两处发事件改经 `VerifyOutboxService` 同事务写 outbox 行，relay 成唯一投递出口，`sql/03-verify-db.sql` 补表 |
| 红①（接线缺失实证） | `--mode=offline --pl verify-service test` → **rc=1 / BUILD FAILURE**：`org.mockito.exceptions.verification.NeverWantedButInvoked` … `Never wanted here: -> at VerifyEventProducer.publish(VerifyEventProducer.java:39)` / `But invoked here: -> at VerifyService.verify(VerifyService.java:124) with arguments: [PASSED, 1, 100]`，模块 `Tests run: 82, Failures: 1`（`.trae/tmp/task131-red1.log`）。行号 `:124` 即 TASK-128 记录的 `:105-109` 直发点（TASK-129 javadoc 插入所致漂移） |
| 红②（DDL 缺失实证） | scratch 容器 `task131-scratch-mysql`（mysql:8.0.46，宿主 13318）：按基线 `sql/03-verify-db.sql` 初始化 → `mysql_apply_rc=0` 但 `information_schema` 查 `verify_event_outbox` = **0**，库内仅 appeal/rule_version/verification_result，脚本命中数 0（`.trae/tmp/task131-ddl-red.log`） |
| 绿②（表结构交付） | 补 DDL 后同一路径重建 → 表存在 = **1**，10 列与实体逐条对齐、`uk_event_id` 唯一键 + `idx_status_id` 在位，脚本连跑两次均 **rc=0**（IF NOT EXISTS 幂等），脚本命中数 1（`.trae/tmp/task131-ddl-green.log`） |
| 绿与变异 | 定向绿 `Tests run: 88, Failures: 0` / rc=0（`.trae/tmp/task131-green2.log`）；变异（注释 `VerifyOutboxService:45` 的 outbox insert）→ 复现 3 条红（`VerifyServiceTest` 2 条 + `VerifyOutboxServiceTest` 1 条，均 `Wanted but not invoked: verifyEventOutboxMapper.insert`）/ rc=1（`.trae/tmp/task131-mutation.log`）；还原后 `sha256sum -c` OK + `cmp` 零差异（`d79c2804…9b43`） |
| 门槛来源 | 本地实跑 `bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS / `20/30/33/80/88/50/6 = 307`**（Failures 0 / Errors 0 / Skipped 0）；开工基线同命令 rc=0 / **300**（`.trae/tmp/task131-offline-baseline.log`、终态 `.trae/tmp/task131-offline-final.log`；收口修订 `7333ed3` 终跑同命令 **rc=0 / 307**，`.trae/tmp/task131-offline-close.log`）。用例数只增不减：verify 81→88（+7），其余模块逐位不变 |
| 是否到达外部门槛 | **未达**（未 push，仅本地实跑） |
| 词面自检 | `LC_ALL=C` **ZERO-HIT**（CI 同款正则，`.trae/tmp/wording-check-131.sh`，`--untracked` 覆盖本任务新文件）；默认 locale 2 命中为 TASK-118 起既登记的本机伪影（`api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17/36`，本任务未触碰），按未覆盖登记 |
| 契约 | 在途 `--baseline=9e64196` → **`TASK-131：判据 B 通过（只改清单与实际改动集一致）`**（`.trae/tmp/task131-contract-inflight2.log`；整体 rc=1 为历史任务在公共文件 `PLAN.md`／本任务新文件上的既有交叠噪声：TASK-102/106/109/110/130 等段的「清单多报 + 改动集未声明」，本任务段零多报零未声明）；收口提交 `7333ed3` 后无参数复跑 → **rc=0**（`契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`，`.trae/tmp/task131-contract-final.log`） |
| 未覆盖 | 真 broker 端到端（relay → RocketMQ → leaderboard 消费）与全栈入榜时延本期未跑：判据形态为 mock MQ + 真 Mapper/写侧，relay 行内 eventId 透传由单测判定；「5s 周期延迟」为配置推演而非实测时延 |
| 未决（交主 agent/用户） | ① 终判后 `verification_result.verdict` 是否随改判更新（本任务保持既有读语义，未擅自扩大）；② `spec/changes/wire-verify-outbox/` 归档按后续流程收口；③ 事件延迟口径（若演示/压测要毫秒级需调 relay 周期，属参数而非缺陷）；④ F09 消费端双轨重试归 TASK-132 |

## 验收记录：TASK-132 消费重试去双轨（RocketMQ 原生重试替换自建计数 + 自建 DLQ，2026-09-23）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `5f89566`（开工时 `git status` 仅 `?? .trae/`，无并行在途足迹，即 TASK-131 收口态）；`b04abb8` verify 侧去双轨 · `75f7c66` leaderboard 侧去双轨 · `6a18392` 变更三件套 · 本条记录所在的台账收口提交 · 契约与终跑实测回填提交（收口），**未 push** |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS / `20/30/33/80/88/50/6 = 307`**（Failures 0 / Errors 0 / Skipped 0）；开工基线同命令 **rc=0 / 307**（`.trae/tmp/task132-offline-baseline.log`）。**用例数净 0**：verify 88→88（类级 8→8）、leaderboard 50→50（类级 9→9），其余五模块逐位不变——删 6 增 6 改 5（撤销清单与理由见 handoff）。生效模式 offline、依赖来源可判定（未触发退出码 3） |
| 是否到达外部门槛 | **未达**（未 push，仅本地实跑） |
| 红取样（改前） | 判别式先落在**行为中性观测缝**（`startConsumer` 的建实例/配置/订阅/监听注册抽成 `buildConsumer(ns)`，不 `start()`；抽取态两侧定向 rc=0 且 88/50 与基线逐位一致）之上，再对旧实现取红：`--pl verify-service test` → **rc=1 / `Tests run: 88, Failures: 4`**，`--pl leaderboard-service test` → **rc=1 / `Tests run: 50, Failures: 4`**（`.trae/tmp/task132-red-verify.log` / `task132-red-leaderboard.log`）。关键原文：`buildConsumer_enablesNativeMaxReconsumeTimes3:84 expected: <3> but was: <-1>`（`-1` 即客户端默认，坐实「改前从未设过上限」）；`NeverWantedButInvoked: redissonClient.getAtomicLong(<any string>)` / `But invoked here: -> at VerifyEventConsumer.markRetryAndExceed(VerifyEventConsumer.java:240) with arguments: [verify:retry:evt-1]`（leaderboard 同构：`LeaderboardEventConsumer.java:231` + `leaderboard:retry:evt-x`，键名与 F09 原文逐字一致）；`handleMessage_failureAfterSelfBuiltThreshold_rethrows:155 Expected java.lang.RuntimeException to be thrown, but nothing was thrown`（改前第 4 次失败被本地转投自建 DLQ 并正常返回，故断言必红）。**行号漂移**：F09 登记的 `:225-233` / `:216-224` 实测为 `:240` / `:231`（观测缝抽取插入 12 行所致） |
| 绿取样（改后） | 同命令 → **rc=0 / BUILD SUCCESS**：`Tests run: 88, Failures: 0`、`Tests run: 50, Failures: 0`，类级 `VerifyEventConsumerTest` 8、`LeaderboardEventConsumerTest` 9（与基线逐位一致、零跳过），`.trae/tmp/task132-green-verify.log` / `task132-green-leaderboard.log`。过程注记（如实登记，非用例语义红）：绿轮首跑两模块 rc=1 为**编译红**「找不到符号：markRetryAndExceed / sendToDlq」——同一消息内对同一文件并行提交多处编辑时后一笔覆盖前一笔（verify 的 catch 分支、leaderboard 的字段删除未落盘），重放同样编辑后消失 |
| 变异验证 | 冻结修订后 `cp` 修复态副本 + `sha256sum` 留底（`0855e22a…` / `8a34c0f7…`，`.trae/tmp/task132-mut.sha256`；留底前另修一处注释口径：监听器 catch 的「未超重试阈值」措辞随本地阈值判定一并更新）→ 逐文件摘除 `c.setMaxReconsumeTimes(MAX_RECONSUME_TIMES);` 一行 → 定向**复现红 rc=1 ×2**，两模块各**恰 1 条**红：`buildConsumer_enablesNativeMaxReconsumeTimes3:83/:86 expected: <3> but was: <-1>`（其余 87 / 49 条全绿，`.trae/tmp/task132-mutation.log`）→ 字节级 `cp` 还原 → `sha256sum -c` 两文件 **OK** + `cmp` **零差异**；全程未用 `git stash` |
| 静态检查 | `bash scripts/verify/mvn-verify.sh --static=leaderboard-service` → **rc=0 / BUILD SUCCESS**（`.trae/tmp/task132-static.log`）：checkstyle **0 violations**、PMD 已分析无失败项（`PMD version: 7.17.0`）、SpotBugs **Total bugs: 9**——基线 10（9 条 `EI_EXPOSE_REP2` + 1 条 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`，TASK-018 记账），本次随仅供自建 DLQ 的 `RocketMQTemplate` 注入字段删除恰好减少 1 条；`failThreshold=High` 未动、存量仍全为 Medium ⇒ 删字段**未新增违规**。checkstyle 只扫主源码，测试改动不在其口径内 |
| 语义等价 | 逐数等价：原生 `reconsumeTimes >= 3` 转 DLQ ⇔ 自建 `count > 3` 转 DLQ（均为「首发 + 3 次重投 = 消费 4 次、第 4 次失败入死信」）；退避同走 broker `messageDelayLevel`。差异如实登记：①死信载体由自建普通 topic `record-verify-events-dlq` 变为 `%DLQ%verify-consumer-group` / `%DLQ%leaderboard-consumer-group`（broker 内建、按消费组隔离、排查入口改为按组查询）；②计数载体由**无 TTL** 的 Redis 键变为 broker 消息属性 `reconsumeTimes`（Redis 故障不再影响上限判定、多实例不再各判一次）；③自建轨「DLQ 投递失败被 catch 吞掉且调用方随即『视为处理完成』」的**静默丢消息缺口**随之消除 |
| 规格判定 | 主规格存在对象：「校验事件与幂等」（`spec/specs/sport-record-verify/spec.md:897`）含 `失败进死信` 场景（`:916`）⇒ 建三件套 `spec/changes/adopt-native-mq-retry/`（**MODIFIED**「校验事件与幂等」：SHALL 行补原生重试上限与 `%DLQ%<consumerGroup>`，场景新增「重试上限走 MQ 原生」、改写「失败进死信」的载体与排查口径） |
| 词面自检 | CI 同款正则与排除（`.trae/tmp/wording-check-132.sh`，UTF-8 承载、命令行纯 ASCII），收口提交后在 tracked 载体双跑：`LC_ALL=C` **ZERO-HIT**；默认 locale **2 命中**，全在 `api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17/36`——TASK-118 起既登记的本机 locale 伪影（本任务未触碰该文件，判据以 `LC_ALL=C` 为准），按未覆盖登记，不写成通过也不写成用例红 |
| 契约 | 在途 `bash scripts/verify/mailbox-contract.sh --baseline=5f89566`（`.trae/tmp/task132-contract-inflight.log`）：判据 A 两件套齐全；**`TASK-132：判据 B 通过（只改清单与实际改动集一致）`**（10 项声明零多报、零未声明）。整体 rc=1 的成因是公共文件 `PLAN.md` 进入本轮改动集后与全部历史 handoff（各自声明过该文件）交叠而触发强校验，报「改动集未声明：TASK-132/*」等历史过冲项，非本任务清单不一致。收口提交 `b583059` 后无参数复跑 → **rc=0**（`契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`，`.trae/tmp/task132-contract-final.log`） |
| 只改清单一致性 | 实际改动集（`git diff --name-only 5f89566` + untracked 排除 `.trae/`）＝ VerifyEventConsumer.java · VerifyEventConsumerTest.java · LeaderboardEventConsumer.java · LeaderboardEventConsumerTest.java · 变更三件套（3）· TASK-132/spec.md · TASK-132/handoff.md · 本文件，与 handoff 声明逐字一致（10 项）；未动消费幂等逻辑（SETNX/去重键 TTL/删键放行全保留）、未动 TASK-131 的 outbox 与 relay 链路、未动 broker/producer 配置与既有表结构 |
| 未覆盖 | ① **真 broker 端到端未覆盖**：`%DLQ%<group>` 的实际生成与消息转投需真 broker，本机未起 Nacos + RocketMQ 全链路；语义按 RocketMQ `consumerSendMsgBack`（`reconsumeTimes >= maxReconsumeTimes` 时转 DLQ）路径 + 离线单测断言登记，不得写成通过（TASK-110 的 `RocketMqBrokerRoundTripIT` 不在本次改动集）；② `api` 模块 `RecordVerifyEvents.DLQ_TOPIC` 常量与类 javadoc 在本仓已无调用方（public API，删除无授权）；③ 公开文档三处口径漂移（`docs/判定引擎-开发总览.md:103/104`、`docs/运动记录校验系统需求文档（审批版）.md:345`）与 leaderboard `application.yml:59` 注释，均不在只改清单；④ 未 push，待 CI 复验 |
| 未决（交主 agent/用户） | ① **spec 归档顺序**：本变更与在途 `wire-verify-outbox` 同时 MODIFIED「校验事件与幂等」，本 delta 已按目标态整段书写（含对方 SHALL 行与 `判定事件经待发行表投递` 场景），任一先后归档均收敛到同一终态，但建议**先归档 `wire-verify-outbox`**；② `work/mailbox/findings-summary.md` 的 F09 状态标注未改（不在只改清单）；③ F09 原文「两份消费者公共逻辑抽到 common」未做（不在任务范围，属可选重构） |

## 验收记录：TASK-133 mapmatch 逐点 SQL 改轨迹级批量预筛（F05 收口，2026-09-23）

| 项 | 内容 |
| --- | --- |
| 绑定修订 | 开工基线 `2b4cb8a`（开工时 `git status` 仅 `?? .trae/`，无并行在途足迹，即 TASK-132 收口态）；`b0203e9` mapmatch 实现与判别式 · `7552256` 台账两件套与 PLAN · 本条记录所在的回填提交（收口），**未 push** |
| 门槛来源 | 本地实跑，唯一入口 `bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS / `20/30/33/80/88/50/10 = 311`**（Failures 0 / Errors 0 / Skipped 0，`.trae/tmp/t133-offline.log`）；开工基线模块级同命令 **rc=0 / mapmatch `Tests run: 6`**（`.trae/tmp/t133-baseline-mapmatch.log`）。**用例数净 +4**，全部落在 mapmatch（6→10），其余六模块逐位不变（20/30/33/80/88/50 与 TASK-132 收口态一致）。生效模式 offline、依赖来源可判定（未触发退出码 3）。**收口修订 `7552256` 终跑同命令 → rc=0 / BUILD SUCCESS / `311` 全绿**（`.trae/tmp/t133-offline-final.log`，门槛数字绑定收口修订而非中间态） |
| 是否到达外部门槛 | **未达**（未 push，仅本地实跑） |
| 红取样（改前） | 判别式先行落在**签名不变的类**上（不引用尚未存在的观测缝，避免编译红）：`--pl mapmatch-service test` → **rc=1 / `Tests run: 9, Failures: 3, Errors: 0`**（`.trae/tmp/t133-red2.log`），3 条新判别式全红、既有 6 条全绿。关键原文：① `轨迹级预筛_单块_往返数与采样点数无关` → Mockito `Argument(s) are different! Wanted: queryForList(<any String>, class String, <any double>×5)`，`Actual invocations` 指名 `-> at com.sportverify.mapmatch.service.MapMatchService.distanceToNearestRoad(MapMatchService.java:112)`（3 参、每点一条 SQL，实参带单个点的 lng/lat/半径 0.0035294117647058825）；② `轨迹级预筛_跨块_往返数等于分块数` 同形态（`MapMatchServiceTest.java:307 → verifyTrajectoryPrefilter:164`）；③ `轨迹级预筛_固定夹具_聚合指标逐位命中黄金值` `expected: 0.38461538461538464 but was: 0.0`（`MapMatchServiceTest.java:327`，旧实现取不到候选 → 全部退化为 300m 封顶） |
| 绿取样（改后） | 同命令 → **rc=0 / BUILD SUCCESS / `MapMatchServiceTest: Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`**（`.trae/tmp/t133-green1.log`），既有 6 条零回退，新增 4 条（单块往返数 / 跨块往返数 / 聚合黄金值 / 逐点黄金值）。同一步内既有打桩由基线 3 参升为目标态 5 参（随 SQL 形态变更） |
| 变异验证 | 冻结修订后 `cp` 修复态副本 + `sha256sum` 留底（`89cf59eec0b3aad63276171050feba48d6a3fb9e9b5255ca1fa6262a20554528`，`.trae/tmp/t133-frozen.sha256`）→ `match` 块循环临时回退为逐点查询（并恢复 3 参逐点 SQL）→ 定向 **rc=1 / `Tests run: 10, Failures: 4`**（往返数两条判别式 + 聚合黄金值 + 既有 `沿路轨迹`，即红①复现，`.trae/tmp/t133-mutation.log`）→ 字节级 `cp` 还原 → `sha256sum -c` 两行 **OK** + `cmp` **零差异**；全程未用 `git stash` |
| 语义等价 | **聚合**：固定夹具 13 点，`matchedRatio`/`offRoadRatio`/`maxOffRoadDistance` 位级相等（`isEqualTo`）、`avgOffRoadDistance` `within(1e-9)`。**逐点**：13/13 点的「best 边（WKT 身份）+ 垂距」逐位命中改前实现留档的黄金值（`within(1e-9)` 米 = 1 纳米），且同点「块级候选集（含超半径干扰边 E3 与各点各自远景边）」与「逐点候选集」给出**同一条**最佳边。**真库独立复核**（临时 PostGIS 3.4 容器 `--rm -p 5433:5432`，验证后已 stop + 自动移除、未留卷/容器；psql scratch）：`new_superset_of_old = t`；旧形态/新形态候选集最小**大地线**距离 13/13 相等；第 13 点旧形态无候选（→封顶 300）、新形态最小 753.9293m 仍被封顶；`EXPLAIN (COSTS OFF)` → `Index Scan using idx_t133_geom` + `Index Cond: (geom && st_expand(<envelope>, 0.0035294…))`（新 SQL 仍走 GIST）；退化 envelope（单点块 `min=max`）语法可用 |
| DB 往返数 | 20 点 20→**1**；200 点（= 采样上限整值，`thin` 不缩点）200→**4** = `ceil(200/prefilter-chunk-points=50)`；旧三参逐点形态 **0 次**（`verify(never())`）。上界与采样点数无关（单块恒 1 次），判据 = mock 计数 + 外接矩形实参逐项断言（`minLng/minLat/maxLng/maxLat` + 半径 = `300/85_000` 度，`within(1e-12)`） |
| 规格判定 | `LC_ALL=C git grep -n "候选边\|预筛\|ST_DWithin\|采样点上限\|max-sampled" -- spec/specs/` → **0 命中**；在途 `spec/changes/`（`wire-verify-outbox`、`adopt-native-mq-retry`）同样 0 命中 ⇒ 主 spec 与「空间匹配」相关的两条需求（`独立路网匹配服务 → 匹配接口返回`、`真实路网数据 → 数据可查询`）只约束对外行为，本任务字段一行不改、真库 EXPLAIN 仍走空间索引 ⇒ **语义不变的实现级性能优化，无 delta 可写**，按台账两件套收口 |
| 词面自检 | CI 同款正则与排除（`.trae/tmp/wording-check-133.sh`，UTF-8 承载、命令行纯 ASCII）收口前双跑：`LC_ALL=C` **ZERO-HIT**；默认 locale **2 命中**，全在 `api/src/main/java/com/sportverify/api/mapmatch/dto/MapMatchResultDTO.java:17/36`——既有本机 locale 伪影（本任务未触碰该文件，判据以 `LC_ALL=C` 为准），按未覆盖登记，不写成通过也不写成用例红 |
| 契约 | 在途 `bash scripts/verify/mailbox-contract.sh --baseline=2b4cb8a`（`.trae/tmp/t133-contract-inflight.log`）：判据 A `两件套齐全：TASK-133`；**`TASK-133：判据 B 通过（只改清单与实际改动集一致）`**（7 项声明零多报、零未声明）。整体 rc=1 的成因是公共文件 `PLAN.md` 进入本轮改动集后与全部历史 handoff（各自声明过该文件）交叠而触发强校验，历史段逐一报「改动集未声明：`work/mailbox/tasks/TASK-133/*`、`mapmatch-service/…`」等过冲项，非本任务清单不一致。收口提交后无参数复跑 → **rc=0**（`契约校验通过（退出码 0）：判据 A 两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`，TASK-133 段为「足迹不在工作树，视为已收口，不重审」；`.trae/tmp/t133-contract-final.log`） |
| 未覆盖 | ① **真库 IT 类未补**：`--it` 入口（`mvn-verify.sh`）只定向 `LeaderboardDailySummaryMapperMysqlIT`，新增 mapmatch IT 无法经唯一验收入口执行，且 `scripts/verify/**` 不在本任务只改清单 → 真库语义虽已用临时容器 + psql 直测（含候选集包含关系/距离/索引命中/退化 envelope 四项），但**无常驻判据、CI 不覆盖**，不得写成通过；② **公开文档未同步**：`docs/adr/0006-空间匹配.md:27` 的预筛描述未点明「参照物 = 块外接矩形」（决策本身仍成立）、`work/mailbox/findings-summary.md` 的 F05 状态标注未改，均不在只改清单；③ 未 push，待 CI 复验 |
| 未决（交主 agent/用户） | ① F05 状态标注需另立微变更收口（与本条同一改动集越界会触发契约判据 B）；② **单块 bbox 由块内点确定**：跳点/瞬移形态会把该块外接矩形拉大（本任务夹具第 13 点即此形态）——正确性无损（超集只多不少），但预筛选择性下降，若要压这条边界需改成按点间距/航向切块；③ **距离口径 ±0.38%**：Java 侧常量 111320 米/度在 31.23°N 系统性偏高 0.38%（真库大地线反算实证），吸附阈值 25m 下绝对偏差 ~0.1m，属既有实现口径（类注释声称 <0.5% 成立），本任务未改；④ `ST_Intersects(geom, ST_Buffer(envelope, r))` 形态未采用（需先缓冲出多边形、包围盒扩张更大、索引选择性更差），若后续要求「缓冲多边形相交」语义严格对齐可另立变更 |
