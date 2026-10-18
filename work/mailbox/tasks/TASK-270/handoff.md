# TASK-270 执行交接（handoff）

> 状态：**已终裁 ACCEPT 予以封盘**（2026-10-18 指导亲核三块全 PASS，七门禁全绿，予以封盘）

## 1. 开工基线

- 开工基线：main @ `bcd073e`（TASK-270 立项笔；开工基线与派发令口径一致）
- C-01 业务测试提交：`6c32f23`（`fix(record): 补齐BadgeServiceTest在BeforeEach中的LambdaCache初始化消除用例执行序依赖（TASK-270）`；1 文件 +6/−1）
- 任务书：`work/mailbox/tasks/TASK-270/spec.md`

## 2. 改动集清单（C-01/C-02 落库后逐行回填，与 `git diff --name-only bcd073e` 双向全等）

- 改动清单（恰 2 项 = C-01 测试 1 + C-02 台账 1，逐行路径 token 供判据 B 机器比对）：
  - record-service/src/test/java/com/sportverify/record/service/BadgeServiceTest.java
  - work/mailbox/tasks/TASK-270/handoff.md
- 零触碰面声明：除上述 2 文件外，全仓其他微服务生产 Java 源码、业务逻辑、其他单测与 E2E、构建脚本、前端、编排与环境配置、数据库脚本等 100% 零触碰；未引入任何新的外部依赖。

## 3. 门禁读数（实测回填）

| # | 门禁 | 命令 | rc | 关键读数 |
| --- | --- | --- | --- | --- |
| 1 | record 模块全量测试 | `mvn-verify.sh --mode=offline --pl record-service test` | 0 | BUILD SUCCESS（Total time 02:11 min）；surefire **351** 测全绿（0 失败、0 错误、0 跳过），全仓保持 1063 测总基线。读数时点：C-01 后实测 |
| 2 | record 静态三件套 | `mvn-verify.sh --mode=offline --static=record-service` | 0 | BUILD SUCCESS（Total time 32.119 s）；**You have 0 Checkstyle violations**，spotbugs/pmd 同轮通过（pmd 13 warnings 属非违规提示，rc=0）。读数时点：C-01 后实测 |
| 3 | 契约门 | `mailbox-contract.sh --open=TASK-270 --baseline=bcd073e` | 0 | C-01 后预跑 rc=0；C-02 后终态复跑 rc=0，判据 B 2 路径双向全等（清单多报 0、改动集未声明 0） |
| 4 | token 门 29 项 | `git grep -cF -e <tok> <sha>` 三树校验（全仓 tracked 累加口径） | 0 | 基线 bcd073e 树实测 SUM = **2307**；C-01 树（6c32f23）实测 SUM = **2307**（改动文件 29-token 扫描零命中，逐位持平零减少，diff 全等）；C-02 树期望 SUM = **2336** = 2307 + 29（本 handoff 本行逐位表自枚举恰各 +1，C-02 后实测复核）。29 项逐位基线读数（bcd073e 树）：13.4=164、18.0=180、73.93=121、68.8=116、6315=96、1.8612=95、3.3066=95、5.7056=101、9.408=86、36525962432=79、36586847965=76、36438897772=78、36399582548=76、36098038547=74、2806=116、598=113、36736221648=73、36808102571=61、36821040708=55、36845152965=52、36871294588=50、36880083885=52、36958994260=51、36976873215=46、36992632143=43、36995450125=41、37008317295=40、37021305016=39、37591580687=38 |
| 5 | 词面门 | `git grep -n -I -iE -f .trae/tmp/lex268.txt` | 1 | 四 locale 形态 default / LC_ALL=C / zh_CN.UTF-8 / C.UTF-8 全 ZERO_HIT rc=1；排除公共模板与归档后机器判据稳定生效 |
| 6 | 隔离单测实证 | `mvn test -pl record-service -Dtest=BadgeServiceTest#unwearBadge_resetsAllEquippedFlags -s .mvn-settings.xml -o` | 0 | BUILD SUCCESS（约 18 秒）；Tests run: 1, Failures: 0, Errors: 0, Skipped: 0；彻底根除原 CI 中在独立 JVM 进程下执行时抛出的 `MybatisPlus can not find lambda cache for this entity [com.sportverify.record.entity.UserBadge]` 错误 |
| 7 | diff 净与行尾 | `git diff bcd073e HEAD --check` / 入库 blob CR 计数 | 0 | diff --check 输出空 rc=0；C-01 文件入库 blob 逐字节 CR=0 纯 LF |

## 4. 条件项与实施要点（如实回填）

- **R1（补齐 @BeforeEach setUp 初始化）**：
  - 在 `BadgeServiceTest.java` 引入 `import org.junit.jupiter.api.BeforeEach;`；
  - 增设 `@BeforeEach void setUp()` 方法，统一执行 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserBadge.class);`，与仓内既有 `GoalServiceTest`、`TrackPointArchiveServiceTest` 等家法完全对齐；
  - 移除 `wearBadge_success_resetsAllThenEquipsTarget` 内部单处冗余的初始化调用；
- **R2（用例隔离度验证坐实）**：
  - 单独运行 `BadgeServiceTest#unwearBadge_resetsAllEquippedFlags` 独立测试通过（1 测耗时 3.026s，退出码 0）；
  - `record-service` 全量 351 个单测全部保持通过，零失败、零错误、零跳过。

## 5. 收口自证

- `git status --porcelain` 输出为空（C-02 后实测）；
- 未推送：`origin/main..HEAD` 收口后 = **3 笔**（开工基线 a7a3159 领先 0 笔 + 立项笔 `bcd073e` + C-01 `6c32f23` + C-02 本笔）——推送由指导侧批次统一执行，本任务严格零推送。
- handoff 状态行回填「已实施待复核」；契约门 C-02 后终态复跑 rc=0（判据 B 2 路径双向全等）；token 门 C-02 树终态实测复核 SUM 与期望 2336 一致。
