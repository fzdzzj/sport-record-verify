# TASK-266 执行交接（handoff）

> 状态：**已实施待复核**（2026-10-18 实施；MEDIUM 档双提交 + 七门禁全绿 + 受控红探针实证防回潮，指导亲核三块把关）

## 1. 开工基线

- 开工基线：main @ `83ebeaf44a6153a0ea53b006739898953364ef0e`（TASK-266 立项笔；派发书口径一致）
- C-01 业务提交：`fe5a2e7`（`fix(record): 解耦SportRecordService与RecordLikeService启动循环依赖并增加防回潮单测（TASK-266）`；4 文件 +146/−4）
- 任务书：`work/mailbox/tasks/TASK-266/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only 83ebeaf` 双向全等）

- 改动清单（恰 5 项 = C-01 业务/配置/测试 4 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
  - record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java
  - record-service/src/main/resources/application.properties
  - record-service/src/test/java/com/sportverify/record/service/RecordServiceCircularDependencyTest.java
  - work/mailbox/tasks/TASK-266/handoff.md
- 零触碰面声明：除上述 5 文件外，全仓其他微服务生产代码、既有单测与契约测试、pom.xml、package.json、web 前端、docker-compose、.env、sql 等 100% 零触碰；未引入任何新的外部依赖（@Lazy 为 spring-context 既有注解，registerBean 为 spring-context 既有 API，测试既有 Mockito/JUnit 依赖）。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | 后端 offline 全量七模块 | `mvn-verify.sh --mode=offline test`（Git Bash --login） | 0 | BUILD SUCCESS；surefire **63/78/221/322/203/110/36**（common/gateway/user/record/verify/leaderboard/mapmatch），record 321→322 净增 +1（新测试 RecordServiceCircularDependencyTest 1 测），总计 1033 测，零失败零错误零跳过 |
| 2 | 契约门 | `mailbox-contract.sh --open=TASK-266 --baseline=83ebeaf` | 0 | C-01 后预跑 rc=0（进行中声明放行，含 1 个待办进行中）；C-02 后终态复跑 rc=0，判据 B 5 路径双向全等（清单多报 0、改动集未声明 0） |
| 3 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | 0 | 基线 83ebeaf 树实测 SUM = **2191** = 派发书期望；C-01 树（fe5a2e7）实测 SUM = **2191**（4 个改动文件 29-token 扫描零命中）；C-02 树期望 SUM = **2220** = 2191 + 29（本 handoff 本行逐位表自枚举恰各 +1，C-02 后实测复核）。29 项逐位持平零减少（基线读数）：13.4=160、18.0=176、73.93=117、68.8=112、6315=92、1.8612=91、3.3066=91、5.7056=97、9.408=82、36525962432=75、36586847965=72、36438897772=74、36399582548=72、36098038547=70、2806=112、598=109、36736221648=69、36808102571=57、36821040708=51、36845152965=48、36871294588=46、36880083885=48、36958994260=47、36976873215=42、36992632143=39、36995450125=37、37008317295=36、37021305016=35、37591580687=34 |
| 4 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex266.txt`（8 禁词模式文件法，三排除路径） | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1；阳性探针 probe266.txt `--untracked` rc=0 恰命中后即删、带排除复跑 rc=1 状态复原——词面机器活性坐实 |
| 5 | checkstyle delta=0 | `mvn-verify.sh --mode=offline --static=record-service` | 1 | rc=1 为 record-service 既有存量形态：恰 4 违规全为 SportRecordService.java L1076 JavadocType（DerivedTrackMetrics 4 组件缺 @param，TASK-234 遗留；基线 L1075 因新增 import 行位移 1 行），零新增违规 delta=0 |
| 6 | diff 净与行尾 | `git diff 83ebeaf HEAD --check` / 逐文件 CR 计数 | 0 | diff --check 输出空 rc=0；两个 Java 主文件 CRLF 既有行尾保持（CR=LF=1325/816 逐行对齐）；application.properties 与新测试文件纯 LF（CR=0，末字节 0x0A） |

## 4. 条件项与实施要点（如实回填）

- **R1（@Lazy 注入解耦）**：`SportRecordService.setRecordLikeService` 与 `RecordLikeService.setSportRecordService` 双向 setter 参数均加 `@org.springframework.context.annotation.Lazy`（各 +1 import +1 注解参数）；业务方法调用、签名与返回值 100% 不变，既有 Mockito 单测零改动全兼容；两 setter javadoc 各补一句解耦说明。
- **R2（防倒退配置）**：`application.properties` 新增 `spring.main.allow-circular-references=false`（含注释块，置于 spring.application.name 之后）；显式声明对齐 Spring Boot 3 默认严格模式，此后任何新循环依赖启动期 fail-fast。
- **R3（契约单测）**：新增 `RecordServiceCircularDependencyTest`（1 测，常规 surefire 收集，Test 结尾）：
  - 构造 `AnnotationConfigApplicationContext`：环境属性显式注入 `spring.main.allow-circular-references=false` 并读回落地到 BeanFactory（轻量上下文不经 SpringApplication 引导，spring.main.* 不自动绑定，读回后等价应用，与 application.properties 同键同值）；
  - 两 Service 与全部基础设施依赖（4 个 Mapper 接口、2 个 MQ 生产者、VerifyApi / VerifyDegradeService / UserApi / LeaderboardApi、StringRedisTemplate / RedissonClient、ObjectMapper、PlatformTransactionManager、SimpleMeterRegistry）经 `registerBean` 编程式注册，**不定义任何 @Configuration 类**；
  - 断言 refresh() 成功（严格模式下无 BeanCurrentlyInCreationException / UnsatisfiedDependencyException）、两 bean 非空且互相持有对方（ReflectionTestUtils 读私有字段 + AopUtils.isAopProxy 判定注入形态为 @Lazy 懒加载代理）；
  - **受控红探针实证**：临时移除单侧 @Lazy 复跑该单测 → FAIL（isAopProxy 断言红：另一侧注入真实实例而非代理）；双向移除时 refresh() 在严格模式下抛 BeanCurrentlyInCreationException——两种形态均转红，防回潮判据真实有效；探针后立即恢复原状并复验全绿。
- **实施过程中的两处实测教训（已消化，零遗留）**：
  1. 新测试首版用嵌套 @Configuration + @Bean 顶 mock，全量跑红：ProbeApp（RecordProbeTest @SpringBootTest）的 `@ComponentScan("com.sportverify.record")` 扫描面覆盖测试类路径，嵌套配置类的 @Bean 定义与 @MapperScan 同名 Mapper 注册冲突（recordLikeMapper ConflictingBeanDefinitionException）——改 registerBean 编程式注册后归绿。教训：测试侧任何新增可扫描组件都会进入既有测试上下文的扫描面；
  2. `registerBean(X.class, X::new)` 方法引用在 registerBean 的 varargs 双重载间编译歧义（无法推断 T），改显式 lambda 消解。
- **口径事实登记**：
  1. 派发书 R3 描述「移除 @Lazy 后 refresh() 即抛异常」在单侧移除情形下实测不成立（单侧懒代理即足以解环，refresh 成功）——测试以 isAopProxy 断言补位该形态，双向移除才触发 refresh 异常红；两种形态均红，判据覆盖完整；
  2. checkstyle 违规行号 L1076 为新增 import 行的位移结果，违规集与基线逐项同源（TASK-234 存量，非新增）。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；`.trae/tmp` 临时件（toktree/tokscan/词面门脚本、lex266.txt 模式、commit msg 文件、门禁日志）留于 gitignored 目录不入库，探针文件已删。
- 未推送：`origin/main..HEAD` 收口后 = **3 笔**（本任务开工前 0 笔未推送 + 立项笔 `83ebeaf` + C-01 `fe5a2e7` + C-02 本笔）——推送由指导侧批次统一执行，本任务严格零推送。
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 5 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望一致。
