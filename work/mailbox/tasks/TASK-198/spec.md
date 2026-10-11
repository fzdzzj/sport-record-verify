# 任务书：可观测性基线建设（TASK-198 / add-observability-baseline）

唯一指令源：本任务书 + spec/changes/add-observability-baseline/。冲突以本任务书为准。全程 bash 经 `D:\git\Git\bin\bash.exe`；命令行参数禁携中文；唯一验证入口 scripts/verify/mvn-verify.sh（`mvn dependency:tree` 只读分析允许）；offline 与 --static 禁并行（历史 race）。

## §0 红线（十条，违反即 FAILED）

1. **白名单制改动面**：C-01 ≤29 文件按类别——批1 主源 5（verify/mq/VerifyOutboxRelay.java、record/service/VerifyDegradeService.java、record/service/RecordLikeService.java、record/service/TrackPointArchiveService.java、leaderboard/service/LeaderboardService.java）+ 批1 新增单测（对应测试树）+ 父 pom.xml + 六服务 logback-spring.xml（新建）+ 六服务 application 配置（gateway/user/verify/leaderboard/mapmatch yml + record properties）+ 探针测试 ×6；C-02 恰 4 台账文件（§4）。零触碰：ci.yml、docker-compose*、scripts/verify/*、web、sql、ADR、api/common 模块（TraceIds 零改动）、既有测试用例语义。
2. **MDC 包裹零侵入**：try/finally + TraceIds.put/clear，零方法签名改动、零业务逻辑改动、零 @Scheduled 参数改动；relay 用 row.traceId 续链，其余入口 TraceIds 生成新值。
3. **批2 版本与格式纪律**：logstash-logback-encoder 锁 7.4（禁 8.x）；console appender pattern 逐字保留；record 配置只落 application.properties（禁建 yml，snakeyaml 冲突）；开工一次性在线补仓后门禁照常 offline。
4. **批3 组裁剪纪律**：include 与该服务真实依赖严格一致（validate-group-membership 默认 true，跨服务复制即启动失败属预期防线）；liveness 不挂外部依赖；MQ 不进组；show-details=never 维持；gateway 白名单精确追加两路径（保持既有精确匹配语义）。
5. **探针测试口径**：只断言 liveness UP 与 readiness 组结构存在，禁断言 db/redis UP（CI 无中间件）；local 冒烟：本地中间件在跑则实测 readiness 全 UP 留证，不可用则如实登记口径，不造假读数。
6. **offline 基线只增不减**：开工实测 569（36/44/127/137/149/64/12）→ 收口逐模块登记新增数；既有用例语义零改动。
7. **static 触源门**：触碰 record/leaderboard 源码后 `--static=record-service` 与 `--static=leaderboard-service` 均 rc=0；checkstyle 9.3 口径（80 列/FinalParameters/Javadoc 全参）零新增违规；SpotBugs failThreshold=High。
8. **token 29 项只增不减**：tracked `git grep -cF` 权威口径（禁 --untracked 口径标注，TASK-197 已定规范）；新文档禁枚举字面量；台账纯追加。
9. **提交纪律**：两笔 `type(scope): description (TASK-198)` 未推送不建 PR；CI 红禁重试刷绿；新文件 pure LF + 末尾换行、修改文件保持既有行尾；`git diff --check` 干净；MSYS sed -i 禁用于 tracked 文件（F2 教训）。
10. **零性能数字 claim**：观测能力只登记机制性事实（字段存在、组结构、用例计数），不写任何延迟/吞吐/改善数字。

## §1 现状基线（侦察实证，不必重查）

- traceId 链路既有设施：common/trace/TraceIds（HEADER=X-Request-Id）、gateway/trace/RequestIdGlobalFilter（order -200）、五服务 TraceIdFilter、api 模块 TraceIdFeignInterceptor；pattern 已含 `%X{traceId}` 六服务逐字统一。
- 六处 @Scheduled 入口位置：VerifyOutboxRelay.java:150（+批内 worker 线程路径）、VerifyDegradeService.java:96、RecordLikeService.java:297 与 :382、TrackPointArchiveService.java:80、LeaderboardService.java:373。
- 单测范式：Servlet 用 MockHttpServletRequest（TraceIdFilterTest）、MQ 断言用 Mockito 捕获 Message（LikeEventProducerTest）、relay 打桩 Mapper。
- 依赖：micrometer 1.12.4 全家已在树；logstash-encoder 不在本地仓（批2 补仓）；Boot 3.2.4 BOM 管理 micrometer-tracing 但路线甲否决（ADR-0003），零引入。

## §2 预注册设计

- **§2.1 批1**：五主源 MDC 包裹 + 单测；leaderboard/record 触 checkstyle 9.3，MDC 行注意 80 列。
- **§2.2 批2**：父 pom dm 锁 7.4；logback-spring.xml 模板（console 逐字保留 + file LogstashEncoder + springProperty app 名）；六服务 logging.file；record 落 properties。
- **§2.3 批3**：management 配置六服务分叉口径（§0-4）；gateway 白名单追加；探针测试六服务各一。
- **§2.4 三批序贯**，每批完成即跑该批门禁留证，批间不回退已绿批次。

## §3 停止条件

1. MDC 包裹引发既有 outbox/flush/结算语义测试红且无法当日归因（relay 语义历次课题高频验证，不得为 MDC 放松）；
2. JSON file appender 破坏现网 console 人读口径或 logback 上下文启动失败；
3. 某服务 health 组配置引发启动失败且裁剪后仍失败 → 该服务回退仅 readinessState 并申报；
4. logstash-encoder 7.4 与 logback 1.4.14 实测不兼容（编译或运行期冲突）；
5. 需触碰白名单外文件（含 api/common、scripts、compose、ci.yml）；
6. offline 用例数较开工基线减少或既有用例语义被迫改动。

## §4 白名单（只改清单）

- **C-01 实施笔**（≤29 文件，类别清单见 §0-1，新增测试文件路径随实施登记入 handoff 只改清单）
- **C-02 台账笔**（恰 4 文件）：spec/changes/add-observability-baseline/tasks.json、work/mailbox/tasks/TASK-198/spec.md（§7 回填）、work/mailbox/tasks/TASK-198/handoff.md（新建）、work/mailbox/PLAN.md（纯追加）
- 提交主题：C-01 `feat(obs): traceId定时链收口与结构化日志及健康探针基线（TASK-198）`；C-02 `docs(mailbox): 登记TASK-198可观测性基线验收与台账闭环（TASK-198）`

## §5 受保护 token（29 项）

开工实测 SUM（tracked git grep -cF，预期 2030 持平）→ 收口复测只增不减；清单见 TASK-187 handoff §7。

## §6 门禁结构

- **开工规程**：基线 HEAD=派发笔、工作区净、offline 569 开工实测、token 开工实测、六 @Scheduled 入口行号复核
- **预提交门禁**：三批门禁读数齐；`git diff --check` 干净；行尾核验；词面门（改动文件集 ZERO_HIT）
- **收口门禁**：offline 全量（569+新增逐模块）+ static 双模块 + 契约门双态（--open TASK-198 --baseline=<派发笔哈希> 与无参）+ token 复测 + 只改清单全等 + 零越界 + 台账纯追加
- **外部终验**：push 后第 39 次 CI 全绿（web 档 typed-router 零改动），红则按签名归因禁重试

## §7 收口记录（执行侧 C-02 纯追加回填）

### §7.1 提交记录

- 派发笔：`（回填显式哈希）`
- C-01 实施笔：`（回填显式哈希）`
- C-02 台账笔：`（本笔自指：显式哈希以回传报告与 handoff §8 给出）`

### §7.2 实施读数（回填：三批逐批读数 / MDC 包裹点清单与续链口径 / logstash 补仓与版本 / 六服务 readiness 组 include 实配 / 冒烟口径 / 新增用例逐模块计数）

### §7.3 门禁读数（回填：offline 逐模块 / static 双模块 / 契约门双态 / token / 只改清单 / diff --check / 行尾）
