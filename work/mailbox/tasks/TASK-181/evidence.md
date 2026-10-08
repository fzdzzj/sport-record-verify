# TASK-181 生效取证与根因订正证据（执行侧）

派发笔 `31733b636d80667101b35f854a2bc0fb7c8be046`（指导侧亲笔，零触碰）。本文件登记 C-01 交付形态、双参数生效取证、四条门径实测，以及本地验证得出的**根因订正**：该类失败的仓库侧触发机制是「测试插件版本从未声明」，而不是「agent 外部 attach 兜底间歇失败」。

## 0. 结论

交付形态 = root pom 显式锁 `maven-surefire-plugin` **3.5.4**（= 上一次全绿外部门槛实读版本）+ `argLine = @{argLine} -Djdk.attach.allowAttachSelf=true`。四条门径实测全绿：offline 全量 **450 恒等 rc=0**、offline 静态 **811 不增**、同形态 online 全量 450 rc=0、`-X` 取证显示测试 JVM 命令行同时含 JaCoCo agent 与 `allowAttachSelf`。

任务书 §2.1 预注册的「不写 `<version>`」形态在本机不可实施（§5.1）；而指导侧一度裁定的「锁 3.6.0（CI 现用值）」经实测是**确定性致红**（§5.2）——本轮改锁 3.5.4 的授权来自这两项实测。措辞口径：**不声称「已修复 CI 基础设施」**，只登记「已消除仓库侧该类失败的触发机制」，有效性由第 23 次外部门槛终验。

## 1. 开工基线留证（HEAD = 派发笔，工作树干净）

| 项 | 命令 | 读数 | rc |
| --- | --- | --- | --- |
| G0 开工核验 | `git rev-parse HEAD` / `git rev-list --left-right --count origin/main...main` / `git status --porcelain` | `31733b636d…` / `0 1` / 空 | — |
| G1 offline 全量测试 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | 逐位 `36/41/33/127/144/59/10` = **450**，Failures/Errors/Skipped 全 0，Total 03:40 min | 0 |
| G2 静态门 | `bash scripts/verify/mvn-verify.sh --static=record-service` | `You have 811 Checkstyle violations.`（第 1 段 install SUCCESS，第 2 段 checkstyle 判红＝既有基线态） | 1 |
| G3 词面门 | 正则现场取自 ci.yml L74（58 字节、8 分支；字面量不落任何入库文件），权威解释器 git 2.20.1 | 四形态（CI 原样 / `C` / `zh_CN.UTF-8` / `C.UTF-8`）全 **ZERO_HIT rc=1**，无 TOOL_ERROR；撤除 ci.yml 排除的对照四形态 HIT rc=0 各 1 命中；由正则自身拼装的 9 行探针四形态 HIT rc=0 各 **9/9**，探针已删、`git status --porcelain` 空 | 判据符合 |
| G4 受保护 token | `grep -cF` 逐项，`work/mailbox/PLAN.md` | 29 项实测 `13.4`=21、`18.0`=23、`73.93`=22、`68.8`=22、`6315`=19、`1.8612`=18、`3.3066`=18、`5.7056`=18、`9.408`=18、`36525962432`=18、`36586847965`=17、`36438897772`=18、`36399582548`=17、`36098038547`=17、`2806`=24、`598`=17、`36736221648`=16、`36808102571`=11、`36821040708`=9、`36845152965`=8、`36871294588`=8、`36880083885`=9、`36958994260`=9、`36976873215`=9、`36992632143`=8、`36995450125`=6、`37008317295`=7、`37021305016`=8、`37591580687`=7，逐项与 TASK-180 收口值一致 | — |
| G5 契约门（在途） | `bash scripts/verify/mailbox-contract.sh --open TASK-181 --baseline=31733b6…` | `契约校验通过（退出码 0）：判据 A 两件套齐（含 1 个待办进行中）+ 判据 B 清单一致` | 0 |
| G5b 契约门（无参，开工态） | `bash scripts/verify/mailbox-contract.sh` | `判据 A=1`（TASK-181 尚未声明 `--open`，开工态预期） | 1 |
| G6 空白门 | `git diff --check` / `git diff --cached --check` | 无违规 | 0 / 0 |
| G7 三件套语法 | `python -m json.tool spec/changes/harden-surefire-agent-attach/tasks.json` | 可解析 | 0 |

## 2. 交付形态原文（C-01，仅 root pom 一处，+18 行）

```
+        <!-- Surefire 显式锁 3.5.4：与上一次全绿外部门槛的实读版本一致（run 37722755341 → surefire:3.5.4:test），
+             同时把测试插件版本钉住，不再随 runner 镜像里 Maven 的默认绑定漂移；
+             机制与实测见 work/mailbox/tasks/TASK-181/evidence.md。 -->
+        <maven-surefire-plugin.version>3.5.4</maven-surefire-plugin.version>
```

```
+                <!-- 测试 JVM 启动参数加固（TASK-181）：@{argLine} 晚绑定取回 JaCoCo prepare-agent
+                     注入的 argLine property（写成 ${argLine} 会在属性尚未注入时展开为空、覆盖率 agent
+                     随之丢失），再追加 -Djdk.attach.allowAttachSelf=true，让 Mockito inline mock maker
+                     的 ByteBuddy agent 走进程内确定性 self-attach，不再依赖 JDK 21 默认禁用的
+                     外部子进程 attach 兜底路径。 -->
+                <plugin>
+                    <groupId>org.apache.maven.plugins</groupId>
+                    <artifactId>maven-surefire-plugin</artifactId>
+                    <version>${maven-surefire-plugin.version}</version>
+                    <configuration>
+                        <argLine>@{argLine} -Djdk.attach.allowAttachSelf=true</argLine>
+                    </configuration>
+                </plugin>
```

版本走 `<properties>` 集中声明，与本 pom 既有插件版本写法（compiler / enforcer / jacoco / spring-boot）同构；未新增 Maven 插件，未触碰模块 pom、jacoco、enforcer、`ci.yml`、`scripts/`、`src/**`。

## 3. 双参数生效取证（offline，`-X`，record-service）

```
[INFO] --- surefire:3.5.4:test (default-test) @ sport-verify-record-service ---
[DEBUG]   (s) argLine = @{argLine} -Djdk.attach.allowAttachSelf=true
[DEBUG] Forking command line: cmd.exe /X /C "D:\develop1\jdk21\bin\java
  -javaagent:D:\\code\\sports\\.m2-repo\\org\\jacoco\\org.jacoco.agent\\0.8.12\\org.jacoco.agent-0.8.12-runtime.jar=destfile=D:\\code\\sports\\record-service\\target\\jacoco.exec
  -Djdk.attach.allowAttachSelf=true
  -jar C:\Users\fzdzzj\AppData\Local\Temp\surefire18376402435519604076\surefirebooter-20261008174040971_26.jar …"
```

日志原文位置：`.trae/tmp/t181-20-candidate-gates.log:6319`（版本行）、`:6324`（参数注入行）、`:6413`（fork 命令行）。JaCoCo 侧同轮 `argLine set to -javaagent:…` 在场 ⇒ 晚绑定未被覆盖，spec-delta「二者 SHALL NOT 互相覆盖」成立。

## 4. 门禁实测（交付形态，逐条经官方入口 `scripts/verify/mvn-verify.sh`）

| 门径 | 命令 | 读数 | rc |
| --- | --- | --- | --- |
| offline 全量测试 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | 逐位 `36/41/33/127/144/59/10` = **450 恒等**，BUILD SUCCESS | 0 |
| offline 静态门 | `bash scripts/verify/mvn-verify.sh --static=record-service` | `You have 811 Checkstyle violations.`（与 G2 基线同值，不增） | 1 |
| online 全量测试 | `mvn -B -ntp -s .mvn-settings.xml clean test`（在线落料同跑，非门禁拼写） | 逐位 `36/41/33/127/144/59/10` = 450，BUILD SUCCESS | 0 |
| offline `-X` 取证 | `mvn -B -ntp -o -s .mvn-settings.xml -pl record-service -am clean test -X` | BUILD SUCCESS（含 §3 三行取证） | 0 |

`-X` 两跑是任务书 §2.2 授权的取证拼写（手拼带 `-o -s` 与 `-am`）；四组门禁结论一律出自 `mvn-verify.sh`。全程无并发 mvn。

## 5. 根因订正

### 5.1 预注册形态（不写 `<version>`）在本机不可实施

形态＝任务书 §2.1 原样（`pluginManagement` 内 surefire 只带 `<configuration>`）。实测：

| 门径 | 读数 | rc |
| --- | --- | --- |
| `--mode=offline test` | 计划演算阶段失败，`sport-verify-parent … FAILURE [ 0.004 s]`，其余 8 模块 SKIPPED，零用例执行 | 1 |
| `--static=record-service` | 第 1/2 段（装料）同因失败，checkstyle 从未执行 | 1 |
| `--mode=online test` | 逐位 `36/41/33/127/144/59/10` = 450，BUILD SUCCESS | 0 |
| `--mode=online --static=record-service` | `811 Checkstyle violations`（与基线同值） | 1 |

```
[ERROR] Error resolving version for plugin 'org.apache.maven.plugins:maven-surefire-plugin' from the repositories
        [local (D:\code\sports\.m2-repo), central (https://repo.maven.apache.org/maven2)]:
        Plugin not found in any plugin repository -> [Help 1]
```

机制四源：① 本仓所有 pom 原本都不含 surefire 声明（全仓 `grep surefire` 零命中），测试插件版本来自 Maven 默认生命周期绑定，本机 Maven 3.9.4 为 `surefire:3.1.2:test`；② 显式声明而不给版本会让 Maven 调用 `LifecyclePluginResolver.resolveMissingPluginVersions` 改走**仓库 metadata**，在线实读 `Resolved plugin version … to 3.6.0 from repository central`（`.trae/tmp/t181-08-forma.log:310`），而 jar 模块执行仍取绑定版本 3.1.2（`t181-09-forma-online.log:63`）；③ 本机离线仓 `.m2-repo/org/apache/maven/plugins/maven-surefire-plugin/` 只有各版本 jar、没有插件 metadata 目录（`ls`/`find` 实读）⇒ offline 必失败；④ 把声明挪进 `<build><plugins>` 同样失败，并多出 8 条 `'build.plugins.plugin.version' … is missing` 警告（`t181-07-formb.log`）。⇒ 「不锁版本」在 offline 口径不等于「沿现状继承」，而是「不可构建」；§6.2 要求的 offline 450 取证在该形态下拿不到。

### 5.2 致红变量是版本，不是 allowAttachSelf（归因矩阵）

同一台机、同一模块（leaderboard-service，本轮唯一变红者）、`--mode=offline`：

| surefire | allowAttachSelf | 读数 | rc | 证据 |
| --- | --- | --- | --- | --- |
| 3.1.2（无声明→绑定） | 无（开工基线） | 全量 450 绿 | 0 | `t181-01-baseline-test.log` |
| 3.1.2（显式锁） | 有 | leaderboard `Tests run: 59, Errors: 0` | 0 | `t181-17-e1-v312-flag.log:5962`，fork 行 `:4950` 含双参数 |
| 3.5.4（显式锁） | 有 | 全量 450 绿（含 leaderboard 59） | 0 | `t181-19-candidate-354.log` |
| 3.6.0（显式锁） | 有 | leaderboard `Tests run: 59, Failures: 0, Errors: 59`，其余模块 36/41/33/127/144/10 绿 | 1 | `t181-14-prime-360.log:7076` |
| 3.6.0（显式锁） | 无（`@{argLine}` 仅 jacoco） | 同样 `Tests run: 59, Errors: 59`（复跑两次一致） | 1 | `t181-17-e2-v360-noflag.log:8526` |

本机 3.6.0 失败签名（`t181-14-prime-360.log:3185`起）：

```
Could not initialize inline Byte Buddy mock maker.
It appears as if your JDK does not supply a working agent attachment mechanism.
Caused by: java.lang.IllegalStateException: Error during attachment using: net.bytebuddy.agent.ByteBuddyAgent$AttachmentProvider$Compound@…
Caused by: com.sun.tools.attach.AgentInitializationException: Agent JAR loaded but agent failed to initialize
```

外部门槛两 run 的插件版本实读（`gh run view --log` 只读取证，未触发任何 CI）：

| run | 时点 | head | conclusion | surefire 实读 | attach 签名 |
| --- | --- | --- | --- | --- | --- |
| `37722755341`（#41，即第 21 次门槛） | 2026-10-08T03:26:49Z | `b6086b6` | success | `surefire:3.5.4:test` ×14 | 零命中 |
| `37736029636`（#42，即第 22 次门槛） | 2026-10-08T06:08:52Z | `d44c29c` | failure | `surefire:3.6.0:test` ×3 | `gateway` 模块 `Tests run: 41, Failures: 0, Errors: 14`，含 `Could not self-attach to current VM using external process`、`Exception java.lang.NullPointerException [in thread "Attach Listener"]`、`NoClassDefFoundError: Could not initialize class org.apache.maven.surefire.api.report.StackWalkerStrategy` |

⇒ 订正后的因果链：**root pom 从未声明 surefire 版本 ⇒ 测试插件版本随 runner 镜像的 Maven 默认绑定漂移 ⇒ 3.5.4→3.6.0 跳档后 inline mock maker 的 agent 挂载在该版本下失败 ⇒ 第 22 次门槛红（同 commit 本地仍绿，因为本机绑定是 3.1.2）**。派发笔 §1 记为「runner 机群条件漂移 + 外部子进程 attach 间歇失败」，其中"漂移"的落点是版本，且本机表征是确定性而非间歇。修法相应落到「显式声明版本」，`allowAttachSelf` 保留（在 3.1.2/3.5.4 下实测无害，且消除对外部子进程兜底的依赖）。

## 6. 离线仓落料登记（不入库）

`.m2-repo`（gitignored，`.mvn-settings.xml` 声明的 localRepository）经在线落料新增 surefire 相关 jar **20 个**，插件目录现有 `3.1.2/ 3.5.4/ 3.6.0/` 三档（`find -newermt "2026-10-08 14:00"` 计数）。先例：PLAN.md:422 记录的三件套在线落料。仓库改动集不受影响，落料不属于交付面。

## 7. 未覆盖与不得推出的结论

1. **未诊断 3.6.0 的内部机制**：本机只证明「3.6.0 下 attach 失败、与 flag 无关」，未拆解 3.6.0 具体改了 fork/agent 装载的哪一环；JaCoCo 晚绑定与该失败的耦合关系未单独隔离（未跑 `-Djacoco.skip` 对照）。
2. **本机无法复现 CI 的间歇性**：Windows 与 ubuntu runner 的 attach 路径末端 cause 不同（本机 `AgentInitializationException`，CI `Could not self-attach … using external process`），故 `allowAttachSelf` 对 CI 那条路径的有效性**不由本机证明**。
3. **不声称已修复 CI 基础设施**；本轮只登记仓库侧机制的消除。修复有效性以**第 23 次外部门槛绿**为外部终验；同签名再现则转深诊断，不再重试刷绿。
4. **未跑 `--it` 真库端到端**（本笔零生产代码、零测试语义改动，不需要）；未起服务、未连中间件。
5. **Dockerfile 交付面仅静态阅读**：六份服务镜像 build 阶段为 `maven:3.9-eclipse-temurin-21` + 在线 `mvn … package`，显式锁版本后该路径不再依赖 metadata 解析；未在容器内实跑。

## 8. 临时件与状态确认

- 门禁与取证日志、脚本全部位于 gitignored 的 `.trae/tmp/`（`t181-01…t181-20` 成对 `.sh`/`.log`），作为上述读数的原文凭据保留；词面门探针 `t181-02-probe.txt.tmp` 用毕已删（`PROBE_GONE` 实读）；未生成 `*.orig`、`*.patch`、`*.bak`。
- 未 `git add -A`、未 stash、未 push、未建 PR；派发笔三件套与本任务书正文（§0–§6 预注册部分）零改写，收口记录纯追加。
- 取证期间曾把 root pom 短暂改回 `3.1.2` / `3.6.0` / 去 flag 等对照形态，均已按归因需要逐次复原；终态交付形态＝锁 3.5.4 + 双参数 argLine，`git diff --check` rc=0，`python -c xml.etree` 解析 OK。root `pom.xml`（仓库既有文件）入库 blob 纯 LF、工作树纯 CRLF（`autocrlf=true` 的 checkout 口径）、无 BOM；本 `evidence.md`（新增文件）入库与工作树均纯 LF（LF=143 / CR=0）、无 BOM、末尾换行完整（与 handoff §5 G5 同读数；实测 `git ls-files --eol`：`i/lf w/crlf` 与 `i/lf w/lf`）。
