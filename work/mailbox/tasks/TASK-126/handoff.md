# TASK-126 handoff（回传）：密钥默认值治理与常量时间比较（app.security.strict）

## 结论

已实现并全量绿。四处密钥落点新增 `app.security.strict` 开关（默认 false 零扰动）：
strict=true 时任一密钥项缺失（解析为 null 或等于演示默认串）即启动失败（JWT 两侧构造期
`IllegalStateException`、内部接口两侧 init 期同型判别）；`InternalApiAuthFilter` 共享密钥
比较改 `MessageDigest.isEqual`（常量时间），行为等价。红绿、变异、全量、词面、契约取证如下。

## 开工核实（指导侧行号已复核）

| # | 落点 | 实核 |
| --- | --- | --- |
| 1 | user-service `JwtUtil` | `JwtUtil.java:48` 构造器 `@Value` 兜底演示密钥 |
| 2 | gateway | `application.yml:110` `secret: ${JWT_SECRET:<演示默认>}`；`JwtTokenParser.java:34` Java 侧同串兜底（与指导侧口径合并计为一处落点的两侧） |
| 3 | common `InternalApiAuthFilter` | `:41-42` token 兜底 `local-demo-internal-token`；`:52` `equals` 非常量时间比较 |
| 4 | api `InternalApiFeignInterceptor` | `:19` 发送侧同一兜底 |

按实际核实数登记：4 处逻辑落点、5 个文件位置。api 模块无测试基建（无 junit/spring-boot-test），
按「不新增依赖」边界未建 api 侧测试（不在只改清单），实现与 common 同构。

## 红绿取证

判别式形态：`ApplicationContextRunner` 纯属性驱动（不引用新 API，红阶段可编译可运行）。
strict=true 且不注入密钥 → 期望上下文启动失败；strict=true + 显式注入 → 启动成功（反向绿）；
strict 缺省 → 启动成功（零扰动）。

- 笔误轮（实现前，编译红，非判别式红，如实登记）：判别式初版误用 `context.getStartupError()`
  —— 该方法不在 `AssertableApplicationContext` 也不在本仓 `ApplicationContextAssert`
  （javap 离线仓 spring-boot-test 3.2.4 实核：公开方法为 `getFailure()`，`getStartupFailure()`
  为 protected），两轮编译红后订正为 `getFailure().hasRootCauseInstanceOf(...)` +
  `hasStackTraceContaining("app.security.strict=true")`（三层包装下顶层为 BeanCreationException，
  `isInstanceOf`/`hasMessageContaining` 均不可用，各订正一轮）。
- 判别式红（实现前 red3 轮）：
  - common：`InternalApiAuthFilterTest.strictTrueWithoutTokenFailsStartup:85→86` 红
    （strict=true 不注入 token，上下文仍启动成功），`Tests run: 20, Failures: 1`，rc=1；
  - gateway：`JwtTokenParserTest.strictTrueWithoutSecretFailsStartup:92→93` 红，
    `Tests run: 29, Failures: 1`，rc=1；
  - user：定向跑被 `-am` 链上 common 红阻断（BUILD FAILURE 在 common），其自身判别式红以
    实现后变异轮补齐（见下）。
- 绿（实现后 green4 轮）：common rc=0 `Tests run: 20, Failures: 0`；user rc=0 `33, 0`
  （JwtUtilTest 3→5）；gateway rc=0 `29, 0`（JwtTokenParserTest 3→5）。反向绿与零扰动判别式
  均绿，既有测试零回归。

## 变异验证（TASK-106/125 手法）

- 第 1 次手法失误（作废重做，如实登记）：占位符写成未声明变量 `MUTATED` → 编译红非行为红；
- 第 2 次（三文件同变 `strictMode`→`false &&`）：common
  `strictTrueWithoutTokenFailsStartup:85→86` 红 rc=1、gateway
  `strictTrueWithoutSecretFailsStartup:92→93` 红 rc=1；user 定向又被 `-am` 链 common 红阻断；
- 第 3 次（仅变异 user `JwtUtil`）：`JwtUtilTest.strictTrueWithoutSecretFailsStartup:75→76`
  红 rc=1（`Tests run: 33, Failures: 1`）——三判别式变异红齐；
- 还原：字节级备份 `copyfile` 回写，sha256 一致 + cmp 零差异（全 True）。过程注记：第 1 次曾用
  文本模式回写引入行尾漂移（LF/CRLF），改字节级还原后消除；全程未用 `git stash`。

## 全量 offline（唯一入口 `scripts/verify/mvn-verify.sh --mode=offline test`）

- 第一次 rc=1：record-service 编译报「程序包 com.sportverify.common.* 不存在」——common
  `target/classes` 实际完整、源码零改动，判定瞬时类路径抖动（非用例红），原样重跑取证；
- 第二次（终跑）rc=0 BUILD SUCCESS：**20/29/33/80/81/50/6 = 299**，Failures 0 / Errors 0 /
  Skipped 0。开工快照 292（17/27/31/80/81/50/6，含并行会话 TASK-124 的 gateway +2），净增 7 =
  common +3（17→20）、gateway +2（27→29）、user +2（31→33），其余四模块逐位一致零扰动。

## 词面自检

CI 同款正则、排除同款（archive/**、docs/internal/**、ci.yml 自身）：`LC_ALL=C` **ZERO-HIT**；
默认 locale 仅 `api/**/MapMatchResultDTO.java:17/36`（TASK-118 起登记的本机伪影，本任务未触碰）。

## 规格判定

主规格有对象：「内部接口共享密钥校验」（`spec.md:2113`，含 Scenario「密钥默认值不得用于生产」
:2132-2135，其「使用演示默认值属于不安全配置」的 THEN 由本开关在 strict 下落地）+ JWT 鉴权域
（「网关统一鉴权」:1967）⇒ 建三件套 `spec/changes/add-strict-secret-fail-fast/`
（proposal + tasks.json + spec-delta，ADDED 2 Requirement：密钥注入严格模式、内部接口密钥常量
时间比较，EARS 格式）。

## 契约自证（实测）

- Run A（`--baseline=e6c2643 --open=TASK-126`，真开工基线全工作树审计）：整体 rc=1，成因不在本任务——
  并行会话 TASK-124 的足迹（2 个已提交文件 + 2 个已暂存未提交台账）与本任务文件均不在历史 handoff
  声明内（既有「公共文件过冲」同类噪声），日志 `.trae/tmp/t126-contract-B.log`（Run A 输出并入其中）。
- Run B（`--baseline=d181b90 --diff-file=<本任务 14 文件> --open=TASK-126`）：
  **`TASK-126：判据 B 通过（只改清单与实际改动集一致）`**；整体 rc=1 由 18 个历史任务
  （TASK-018/106/109/112~122/124/125）审计在本 diff-file 口径下的交叠噪声构成，与本任务清单无关，
  日志 `.trae/tmp/t126-contract-B3.log`。
- 收口提交后无参数跑：实测见文末「实测回填」。
- 过程注记：第一次 Run B 曾因 diff-file 生成早于 handoff 落盘（少 handoff.md）且把并行会话已暂存的
  TASK-124 台账误计入 ACTUAL 而失败，修正口径后如上。

## 未决 / 未覆盖

1. api 侧 `InternalApiFeignInterceptor` 的 strict 判别未建独立测试（api 模块无测试基建，
   不新增依赖约束）；实现与 common 侧逐字同型，其行为由 common 侧同构判别式间接佐证。
2. 全量 offline 第一次 rc=1（瞬时类路径抖动）已重跑取证 rc=0；若 CI 复现需另行排查
   （未复现第二次，暂记环境异常）。
3. gateway `application.yml` 仅新增 `security.strict: false` 声明（默认值原样保留）；
   user-service yml 未加同款声明（`@Value` 兜底 false 已覆盖），行为一致、口径登记。
4. 默认 locale 词面伪影 2 条（清单外既有登记）。
5. 本次改动未 push，待下次 push 由 CI 复验（未达外部门槛）。
6. 并行在途：TASK-124 会话中途提交 `d181b90`（Sentinel 兜底路由），其台账两件套仍 untracked；
   本任务全程未触碰其文件，收口提交亦不包含。

## 只改清单

api/src/main/java/com/sportverify/api/internal/InternalApiFeignInterceptor.java
common/src/main/java/com/sportverify/common/internal/InternalApiAuthFilter.java
common/src/test/java/com/sportverify/common/internal/InternalApiAuthFilterTest.java
gateway-service/src/main/java/com/sportverify/gateway/auth/JwtTokenParser.java
gateway-service/src/main/resources/application.yml
gateway-service/src/test/java/com/sportverify/gateway/auth/JwtTokenParserTest.java
user-service/src/main/java/com/sportverify/user/auth/util/JwtUtil.java
user-service/src/test/java/com/sportverify/user/auth/util/JwtUtilTest.java
spec/changes/add-strict-secret-fail-fast/proposal.md
spec/changes/add-strict-secret-fail-fast/specs/sport-record-verify/spec-delta.md
spec/changes/add-strict-secret-fail-fast/tasks.json
work/mailbox/tasks/TASK-126/spec.md
work/mailbox/tasks/TASK-126/handoff.md
work/mailbox/PLAN.md

## 提交拆分

1. `feat(common)` InternalApiAuthFilter strict 判别 + MessageDigest.isEqual + 测试
2. `feat(api)` InternalApiFeignInterceptor strict 判别（InitializingBean）
3. `feat(user)` JwtUtil strict 构造期判别 + 测试
4. `feat(gateway)` JwtTokenParser strict 判别 + yml 开关声明 + 测试
5. `docs(spec)` add-strict-secret-fail-fast 三件套
6. `docs(mailbox)` TASK-126 台账两件套 + PLAN 验收记录（收口提交）

## 实测回填（收口提交后）

- 收口修订树（`96b60bd`，其后仅台账文档回填、代码零差异）全量 offline 复跑：rc=0 / BUILD SUCCESS / `20/29/33/80/81/50/6 = 299`
  （日志 `.trae/tmp/t126-final-test.log`）——门槛数字绑定到收口修订而非中间态。
- 收口提交后无参数 `mailbox-contract.sh`：**退出码 0**（`契约校验通过（退出码 0）：判据 A
  两件套齐（含 0 个待办进行中）+ 判据 B 清单一致`，TASK-126 足迹不在工作树视为已收口，
  日志 `.trae/tmp/t126-final-contract.log`）。
