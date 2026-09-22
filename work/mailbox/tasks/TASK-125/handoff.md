# TASK-125 handoff：actuator 白名单与详情暴露收窄

## 改动清单（contract 判据 B，与实际受版本控制改动集一致）

- gateway-service/src/main/resources/application.yml
- user-service/src/main/resources/application.yml
- record-service/src/main/resources/application.properties
- mapmatch-service/src/main/resources/application.yml
- leaderboard-service/src/main/resources/application.yml
- verify-service/src/main/resources/application.yml
- gateway-service/src/test/java/com/sportverify/gateway/auth/ActuatorWhitelistNarrowTest.java
- spec/changes/narrow-actuator-exposure/proposal.md
- spec/changes/narrow-actuator-exposure/tasks.json
- spec/changes/narrow-actuator-exposure/specs/sport-record-verify/spec-delta.md
- work/mailbox/tasks/TASK-125/spec.md
- work/mailbox/tasks/TASK-125/handoff.md
- work/mailbox/PLAN.md

开工基线 `04c6bbc`（`git status` 事前仅 `?? .trae/`），全程未 push、未用 `git stash`。

## 规格判定

Grep 主规格与已归档变更：主规格 `spec.md` 两处需求把 `/actuator/**` 写进需求文本
（「网关统一鉴权·白名单放行」GIVEN、「白名单收紧」正文 +「白名单无 /internal/**」AND 子句）
⇒ 建三件套 `spec/changes/narrow-actuator-exposure/`（MODIFIED 两需求 + EARS）。
已归档 add-jwt-auth / add-resilience-hardening 的 delta 同样提过该前缀，但归档为历史事实不改写；
在途变更 add-auth-degrade-header-strip 的 delta 场景「白名单路径下的伪造头清洗」GIVEN 写
`/actuator/**`——该变更按边界不自行归档、待并入主规格时同步修正（登记未决）。

## 前置核实（停止条件，均不触发）

- scripts/perf/*.sh 只打 /actuator/health（healthcheck.sh:5、ratelimit-test.sh:36/40、
  recover-verify.sh:12、run-perf.sh:100/117/131，其中 run-perf.sh:131 的 8080/health 是
  网关裸 /health 路径探测，不属 actuator）。
- prometheus/prometheus.yml 六个 target 全部 host.docker.internal 直连（8080-8085），
  不经网关；alert-rules.yml 只引用指标路径文本。
- docker-compose*.yml 无经网关的 actuator 依赖。

## 实现

1. 网关 `application.yml:114`：`whitelist: /api/auth/**,/actuator/**` → `/api/auth/**,/actuator/health`。
   依据 `AuthGlobalFilter.matchesPrefixList`（yml 无 /** 后缀 → `path.equals(pattern)` 精确匹配），
   `/actuator/health` 放行、`/actuator/metrics` `/actuator/env` `/actuator/prometheus` 等落回鉴权分支。
   相邻注释（111-113 行）同步改写；99 行探针描述同步。
2. 六处 `show-details: always` → `never`（实际命中数 6：gateway yml:152、user yml:95、
   record application.properties:88、mapmatch yml:72、leaderboard yml:117、verify yml:174）。
   health 端点仍返回 UP/status；include 列表（prometheus/metrics 端点本体）未动，监控栈内网直连不受影响。
3. 新测试 `ActuatorWhitelistNarrowTest`（gateway 22→25）：
   - 从 classpath application.yml 读真实 whitelist 灌进过滤器（承 LeaderboardDailyAdminOnlyTest 先例）；
   - 断言 /actuator/health 命中白名单、/actuator/metrics 与 /actuator/env 不命中、
     白名单不含 `/actuator/**` 整段通配、/api/auth/login 仍命中；
   - 行为断言：/actuator/health 无 token 放行（不写终态）；/actuator/metrics 无 token → 401（1001）。

## 红绿取证（实跑，日志 .trae/tmp/task125-{red,green,mutation}.log）

**红**（改前 yml，只加测试）：`mvn -B -q -pl gateway-service -am test -Dtest=ActuatorWhitelistNarrowTest`：

```
[ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0 -- in com.sportverify.gateway.auth.ActuatorWhitelistNarrowTest
org.opentest4j.AssertionFailedError: 指标查询端点 /actuator/metrics 不得经网关裸放行，实际=[/api/auth/**, /actuator/**] ==> expected: <false> but was: <true>
    at ...ActuatorWhitelistNarrowTest.deployedWhitelistIsHealthProbeOnly(ActuatorWhitelistNarrowTest.java:82)
org.opentest4j.AssertionFailedError: 指标查询端点无 token 必须 401（走鉴权分支），实际=[/api/auth/**, /actuator/**] ==> expected: <401 UNAUTHORIZED> but was: <null>
    at ...ActuatorWhitelistNarrowTest.metricsWithoutTokenGets401WithDeployedConfig(ActuatorWhitelistNarrowTest.java:112)
RC=1
```

（第 3 个用例 healthProbePassesWithoutTokenWithDeployedConfig 改前即绿——探针本来就在 /actuator/** 覆盖内，属回归守卫。）

**绿**（改后 yml）：同命令（-pl gateway-service，依赖已由红段装料）：

```
surefire: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ...ActuatorWhitelistNarrowTest
RC=0
```

**变异验证**（TASK-106 手法）：绿后 `cp` 留底修复态副本 + sha256 `5f676ce3…`；
`sed` 把 whitelist 临时改回 `/actuator/**` → 复跑复现同样 2 失败（Tests run: 3, Failures: 2, RC=1，
失败断言与行号 82/112 与红段逐字一致）；`cp` 还原 → `sha256sum -c` OK + `cmp` IDENTICAL
+ `grep` 确认 `whitelist: /api/auth/**,/actuator/health` 归位。

## 全仓回归（唯一入口）

`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS / 3m46s**，
逐模块 `17 25 31 80 81 50 6 = 290`（gateway 22→25，+3 即新测试；其余六模块与基线 287
逐位一致零扰动），Failures 0 / Errors 0 / Skipped 0，生效模式 offline（未触发退出码 3）。

## 词面自检（CI 同款正则 ci.yml:74 逐字，脚本 .trae/tmp/wording-check-task125.sh）

- `LC_ALL=C`（CI 语义）：全仓（三处排除项口径一致）**ZERO-HIT**。
- 默认 locale：仅余 TASK-118 起已登记的 2 条本机引擎伪影（`api/…/MapMatchResultDTO.java:17/36`，
  多分支模式 cp1252 折叠所致，本任务未触碰该文件），按「未覆盖」如实登记。

## 契约自证

（待补：--baseline=04c6bbc 判据 A/B；收口后无参数退出码。）

## 提交切分（每步可独立编译）

1. `feat(gateway)`：白名单收窄 + 新测试（红绿闭环主体，网关模块自洽）。
2. `feat(observability)`：六服务 show-details → never（各服务独立编译不受影响）。
3. `docs(spec)`：narrow-actuator-exposure 三件套（纯规范文本）。
4. `docs(mailbox)`：台账两件套 + PLAN 验收记录（纯文本）。

## 未决与边界声明

1. `AuthGlobalFilter` 的 `@Value("${app.auth.whitelist:/api/auth/**,/actuator/**}")` 默认值与
   javadoc「/actuator/**（健康探针）」未同步——Java 文件不在本任务只改清单内；yml 常在，
   fallback 语义漂移不生效，随下一处网关 Java 变更顺带收敛。
2. `add-auth-degrade-header-strip`（在途）delta 的 GIVEN 仍写 `/actuator/**`，待其并入主规格时修正。
3. 各服务 include 列表、监控栈编排、网关路由均未动（停止边界）。
4. 不 push；CI 效果待下次 push 复验。
