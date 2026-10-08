# 任务书：TASK-181 harden-surefire-agent-attach（CI attach 机制加固）

派发：指导 Agent（2026-10-08）。派发笔（本任务书 + 提案三件套）由指导侧亲笔；执行侧实施 C-01 + 台账 C-02 两笔，不得改动派发笔内容。

## 0. 硬约束与红线

1. **实施面唯一**：仅 root `pom.xml` pluginManagement 内 surefire 显式配置（argLine 一行 + 必要的插件声明骨架）；不锁 surefire 版本（沿现状继承 3.6.0）；不引入新 Maven 插件（红线延续）；不触碰 jacoco/enforcer/其他插件与模块 pom。
2. **唯一 mvn 入口**：官方门禁只经 `bash scripts/verify/mvn-verify.sh`；禁并发 mvn。
3. **测试基线 450 只增不减**（36/41/33/127/144/59/10）；`--static=record-service` 基线 811 不增。
4. **bash 纪律**：仅 `D:\git\Git\bin\bash.exe` 且仅执行脚本文件；临时 `*.tmp` 用毕删；脚本内 `exec > file 2>&1` 落盘。
5. **git 纪律**：禁 push / PR / `add -A` / stash；逐路径 add；提交信息 `-F` 文件且无 BOM（Write 工具先例）。
6. **token 纪律**：受保护 29 项只增不减（开工实测登记，C-02 追加后 ≥ 开工值）；PLAN.md 纯追加。
7. **措辞纪律**：不声称「已修复 CI 基础设施」，只登记「已消除仓库侧该类失败（agent 外部 attach 兜底）的触发机制」；第 23 次门槛绿为外部终验；新增文档零禁词、零词面门正则字面量。
8. **停止条件**：argLine 加固后本地出现任何测试红 ⇒ 停手回报（本地 Windows attach 行为与 CI 不同，本地红说明引入了新问题）；发现 root pom 结构与派发笔假设不符 ⇒ 停手回报。

## 1. 背景与史实（根因归档）

- 第 22 次门槛（run `37736029636`，head `d44c29c`，2026-10-08 06:08/06:21 两次执行）：gateway-service Errors 14→~6、Failures 恒 0；重跑 cause chain 完整暴露：Mockito 5 inline mock maker → ByteBuddy agent self-attach 被 JDK 21 默认禁用 → 外部子进程 attach 兜底（`ByteBuddyAgent.installExternal`）在 runner 上间歇失败且污染目标 JVM Attach Listener 线程（NPE）→ 级联 surefire `StackWalkerStrategy` NoClassDefFound 假红。
- 非代码回归证据链：同 commit 本地 offline 450 全绿；run 21（3 小时前，同镜像同 JDK `21.0.12+1`）全绿；本批改动全在 record-service + docs，gateway 在 reactor 中先于 record 执行、未触及改动面。
- 处置纪律：两次失败后禁止第三次盲跑（等待重试刷绿红线）；转机制加固。
- 修复原理：`-Djdk.attach.allowAttachSelf=true` 使 agent 走进程内确定性 attach；`@{argLine}` 晚绑定保留 jacoco prepare-agent 注入（root pom L227-248 亲验）。业界成熟修法（Mockito@JDK21 标准配置）。

## 2. 实施设计（预注册）

### 2.1 pom 改动形态

- root pom `<build><pluginManagement><plugins>` 内新增（或补全）surefire 显式配置：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>@{argLine} -Djdk.attach.allowAttachSelf=true</argLine>
    </configuration>
</plugin>
```

- 不写 `<version>`（沿 pluginManagement 现状继承；若现状无 surefire 条目则由 Spring Boot parent 管理版本，仍不锁定）。
- 唯一例外：若本地验证发现 `@{argLine}` 在无 jacoco property 时展开报错（未注入场景），允许改为 `<argLine>@{argLine} -Djdk.attach.allowAttachSelf=true</argLine>` 的等价组合形态（如 late-binding property 替代），但 SHALL 在 handoff 登记实际形态与理由。

### 2.2 生效验证

- 任一模块（推荐 record-service）离线跑 test，从 surefire 输出或 `-X` 调试日志取证：测试 JVM 命令行同时含 jacoco agent（`-javaagent:...jacoco...`）与 `-Djdk.attach.allowAttachSelf=true`。
- 取证方式可用 `mvn -X` 抓 argLine 展开值，或 surefire dump 文件、或测试内 `RuntimeMXBean.getInputArguments()` 断言打印（临时调试代码不入库）。

### 2.3 三支判定

- **PASSED**：450 恒等 + 静态 811 不增 + argLine 双参数取证在场 + 全门禁绿。
- **FAILED**：加固引入本地测试红或基线漂移 ⇒ 回滚 pom 改动、如实登记。
- **外部终验**：推送后第 23 次门槛 CI 绿 ⇒ 修复有效性落定；同签名再现 ⇒ 深诊断（不重试刷绿）。

## 3. 开工读数（时序差惯例）

- 任务书落盘时点 HEAD = `d44c29c94dc5835b4598608a5bf80f20ac2cbbae`（origin/main = 本地，`0 0`）；派发笔入库后基线前移（`0 1`）。
- 离线基线 450；静态基线 811。

## 4. 白名单

- **派发笔（指导侧，执行侧零触碰）**：`spec/changes/harden-surefire-agent-attach/{proposal.md, tasks.json, specs/sport-record-verify/spec-delta.md}` + 本任务书。
- **C-01 实施笔**：`pom.xml`（root）+ 生效取证落 `work/mailbox/tasks/TASK-181/evidence.md`（新建，argLine 展开原文粘贴）。
- **C-02 台账笔**：`tasks.json` 闭环 + 本任务书收口记录纯追加 + `handoff.md` + `PLAN.md` 纯追加（第 22 次门槛红×2 根因归档 + 修复因果链 + 第 23 次门槛待外部终验声明）。
- **禁触**：一切 `src/**`、模块 pom、`.github/workflows/ci.yml`、scripts/。

## 5. 受保护 tokens 基线（29 项，开工实测登记，只增不减）

`13.4`、`18.0`、`73.93`、`68.8`、`6315`、`1.8612`、`3.3066`、`5.7056`、`9.408`、`36525962432`、`36586847965`、`36438897772`、`36399582548`、`36098038547`、`2806`、`598`、`36736221648`、`36808102571`、`36821040708`、`36845152965`、`36871294588`、`36880083885`、`36958994260`、`36976873215`、`36992632143`、`36995450125`、`37008317295`、`37021305016`、`37591580687`（TASK-180 C-02 追加后部分已 +1，开工 `grep -cF` 实测为准）。

## 6. 门禁与提交结构

1. **预提交门禁（每笔前亲跑记录 rc）**：tasks.json 语法 rc=0；词面门四形态 ZERO_HIT rc=1 + 探针 rc=0 + 三态；`git diff --check` rc=0；契约门在途 `--open TASK-181 --baseline=<派发笔哈希>` rc=0；token 29 项只增不减；新增文件纯 LF 末尾换行完整。
2. **收口门禁（C-02 后亲跑留证）**：offline 450 分模块逐位；`--static=record-service` 不增（811）；契约门无参 rc=0；只改清单全等；PLAN.md 自派发笔起纯追加。
3. **提交结构**：C-01 `build(pom): surefire 测试 JVM 启用进程内 agent attach（TASK-181）`；C-02 `docs(mailbox): 登记 TASK-181 attach 加固验收与台账闭环（TASK-181）`。
4. **handoff.md**：开工规程核验 / 偏差登记 / 一句话结论 / 只改清单 / 实施证据（argLine 取证原文、450 恒等、静态读数）/ 逐门实测表 / token 前后读数 / 未覆盖项（本地 Windows 无法复现 CI flaky——修复有效性以第 23 次门槛为外部终验）/ 提交表（显式哈希，禁时效指针）。

---

## 7. 收口记录（执行侧 2026-10-08 纯追加，不改写上文任何预注册口径）

- **三支归属**：**PASSED（含两处经授权的形态订正 + 一处根因订正）**。offline 全量 `36/41/33/127/144/59/10` = 450 恒等 rc=0；`--static=record-service` 811 不增；`-X` 取证测试 JVM 命令行同时含 JaCoCo javaagent 与 `-Djdk.attach.allowAttachSelf=true`；词面门四形态 ZERO_HIT + 探针 9/9；token 29 项只增不减。
- **交付形态（与 §2.1 预注册的两处差异，均经指导侧授权）**：① `pluginManagement` 内 surefire **显式锁 `3.5.4`**（经 `<properties>` 集中声明）——预注册的「不写 version」形态在本机 offline 口径于 Maven 计划演算阶段即失败（离线仓缺该插件 metadata），§6.2 的 offline 450 取证不可获得；② 首次裁定的 `3.6.0` 经实测**确定性致红**（leaderboard-service `Tests run: 59, Errors: 59`，去掉 flag 的对照同红），故取最近一次绿的 `3.5.4`。`argLine` 组合形态与 §2.1 一致，未启用 §2.1 的「等价组合形态」例外。
- **根因订正（对 §1 归档口径）**：第 22 次门槛红的仓库侧触发机制是**测试插件版本从未声明 ⇒ 随 runner 镜像里 Maven 的默认绑定漂移**。外部读数：上次绿的 run `37722755341` = `surefire:3.5.4:test` ×14、零 attach 签名；红的 run `37736029636` = `surefire:3.6.0:test` ×3、gateway `Tests run: 41, Failures: 0, Errors: 14`。本机可在 3.6.0 上确定性复现同族失败 ⇒ 不是间歇 flake；`allowAttachSelf` 在 3.6.0 下救不回、在 3.1.2/3.5.4 下无害（保留以消除对外部子进程兜底的依赖）。§3「任务书落盘时点 HEAD = `d44c29c…`」等史实读数按上文原样保留，不改写。
- **§2.3 判定对照**：PASSED 四要件齐；未触发 FAILED（无本地测试红、无基线漂移）；**外部终验未达**——未 push，第 23 次门槛绿才算修复有效性落定，同签名再现转深诊断不重试刷绿。
- **门禁时序**：在途契约门 `--open TASK-181 --baseline=31733b6…` 开工态 rc=0，本笔 6 条路径入改动集后 rc=1——`extract_claims` 把历史 handoff「只改清单」小节正文里的**零触碰声明**（如 TASK-174 L43、TASK-005 L18–L19 的「未碰…`pom.xml`」）也提取成声明，与本笔真实改动的 `pom.xml` 交叠，遂对大批历史任务逐个判「清单多报」；**本笔自身判据 B 通过**（`TASK-181：判据 B 通过（只改清单与实际改动集一致）`），实际改动集与只改清单逐条一致；盲区与修法候选登记在 handoff §1.2.4 与 §7.5。收口无参契约门与收口复跑（offline 450、静态 811）在 C-02 落库后亲跑，读数见执行侧回传报告。
- **措辞口径**：本笔只登记「已消除仓库侧该类失败的触发机制」，**不声称「已修复 CI 基础设施」**。
- **订正笔 C-03（执行侧自纠）**：C-01 入库后自查发现 `pom.xml` 的版本说明注释仍写「显式锁 3.6.0」，而实锁值已按授权改为 `3.5.4`——改值未同步注释，注释与实物不一致。C-03 只订正该注释（把 3.6.0＝第 22 次门槛实读值、3.5.4＝上次绿的 run 实读值、3.6.0 本机确定性致红三件事写清），构建语义零变更，并按本仓"新提交优先于 amend"的纪律走追加笔而非改写 C-01；§6.3 的两笔结构因此为三笔，登记在 handoff §1.2.7 与 §8。
- **收口复跑读数（已落 handoff §5 G13）**：官方 offline 门两轮逐位 `36/41/33/127/144/59/10` = 450 恒等、rc=0（C-02 与 C-03 落库后各一轮），`--static=record-service` 811 rc=1 不增，契约门无参 rc=0；PLAN.md 与本任务书相对派发笔的 numstat 删除数均为 0（纯追加成立），派发笔三件套零改动。
