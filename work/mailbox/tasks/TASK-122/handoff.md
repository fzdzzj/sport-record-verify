# TASK-122 handoff：静态检查三件套经唯一入口接入 CI（--static 子命令）

## 改动清单（contract 判据 B，与实际受版本控制改动集一致）

- scripts/verify/mvn-verify.sh
- scripts/verify/README.md
- .github/workflows/ci.yml
- work/mailbox/tasks/TASK-122/spec.md
- work/mailbox/tasks/TASK-122/handoff.md
- work/mailbox/PLAN.md

开工基线 `ad63c3b`（`git status` 事前仅 `?? .trae/`），全程未 push、未建 PR、未用 `git stash`。
仓库外兜底：`../sports-bundle/pre-task122-ad63c3b.bundle`（`git bundle create --all`，2082576 字节）。

## 实现

`mvn-verify.sh` 新增 `--static[=<模块>]` 子命令（缺省 `leaderboard-service`，沿用 `--it` 定向先例），
两段执行：

1. **第 1 段（装料，CI 可用性前提）**：`mvn … -pl <模块> -am clean install -DskipTests` —— 单模块
   reactor 解析不到兄弟模块 SNAPSHOT（CI 里这些构件从未 deploy），必须先把目标模块与上游装进
   本地仓（offline 模式写入 settings 声明的 `localRepository`，顺带消除 TASK-018 登记过的
   「独立模块构建解析到陈旧内部构件」隐患）。
2. **第 2 段（三 goal，TASK-018 口径）**：`mvn … -f <模块>/pom.xml test-compile checkstyle:check
   com.github.spotbugs:spotbugs-maven-plugin:4.9.8.5:check pmd:check` —— spotbugs 用全限定 GAV
   （其前缀不在 Maven 默认插件组内、插件只声明在目标模块 pom），`-f` 与 TASK-018 实证的
   `cd <模块> && mvn …` 同上下文；插件配置全部来自目标模块自身 pom，脚本不带任何 `-D` 覆盖。

参数语义：与阶段参数 / `--it` / `--pl` 互斥（冲突即退出码 2）；`<模块>` 须为含 `pom.xml` 的模块
目录名，否则退出码 2（不调 Maven）；`--mode` 语义完整继承（offline 的退出码 3 判据照常生效）；
本子命令不执行测试。三 goal 依旧**不绑 lifecycle phase**（pom 零改动，常规 `clean test/verify`
路径既不执行也不解析它们）。

CI：build job 在「Build representative service image」之后、「Public docs wording self-check」之前
插入一步 `bash scripts/verify/mvn-verify.sh --static=leaderboard-service`；既有步骤判据逐字未动
（词面正则及其三处排除项原样保留）。

## 红绿取证（实跑原文，日志在 `.trae/tmp/task122-{red,green}.log`）

**红**：向 `LeaderboardService.java` 尾部注入 163 字符 CRLF 注释哨兵（原文件 439 行、全 CRLF、
工作树 sha256 `4306d384…`；哨兵为第 440 行），`bash scripts/verify/mvn-verify.sh --static`：

```
第 1/2 段 BUILD SUCCESS（装料段通过，判别式红在静态检查本身）
[ERROR] D:\code\sports\leaderboard-service\src\main\java\...\LeaderboardService.java:440: 本行字符数 163个，最多：140个。 [LineLength]
[ERROR] src\main\java\...\LeaderboardService.java:[440] (sizes) LineLength: 本行字符数 163个，最多：140个。
[ERROR] Failed to execute goal ...maven-checkstyle-plugin:3.6.0:check ... You have 1 Checkstyle violation.
[verify-entry] Maven 以退出码 1 结束（模式 offline）
RC=1
```

违规规则与位置：**`LineLength` @ `LeaderboardService.java:440`**（哨兵行号 440，163 > 140）。

**还原**（禁用 `git stash`）：`git show HEAD:<path>` 取库内字节 + 非 `-p` 的 `cp` + `touch`，
再 `git checkout-index -f` 归位 autocrlf 检出形态：

```
cmp orig vs restored: IDENTICAL (exit 0)，两侧 sha256 = 993f645c…
git diff --quiet: EMPTY
checkout-index 后工作树 sha256 = 4306d384…（与开工前快照逐位一致）
git status: 干净；哨兵残留 occurrences = 0
```

**绿**：还原后同命令复跑：

```
[INFO] You have 0 Checkstyle violations.
[INFO] Total bugs: 10
[INFO] BUILD SUCCESS（第 1/2 段与第 2/2 段各一次）
RC=0
```

（PMD「2 warnings」提示不再出现：TASK-121 已删除那两处无用 import，属上游变更的正常结果，
`Total bugs: 10` 与 checkstyle 0 违规均与 TASK-018 基准一致。）

**参数错**（均不调 Maven）：

```
--static=nonexistent-module → rc=2
--static test → rc=2；--static --it → rc=2；--static --pl common → rc=2
```

## 全仓回归（唯一入口）

`bash scripts/verify/mvn-verify.sh --mode=offline test` → **rc=0 / BUILD SUCCESS / 2m44s / 2.7 分钟**，
模块合计 `17 22 31 80 81 50 6 = 287`，与开工基线 `ad63c3b` 逐位一致，Failures 0 / Errors 0 /
Skipped 0。任务包原文写「284」为 TASK-119 增量前的旧锚点（TASK-120/121 已同款登记，实跑以
287 为准）。生效模式 offline、依赖来源可判定（未触发退出码 3）。静态段与测试段互不扰动：
三插件无 `<executions>`，pom 零改动。

## 词面自检（CI 同款正则，脚本留 `.trae/tmp/wording-check-task122.sh`）

- `LC_ALL=C`（CI 语义）：全仓（含三处 CI 排除项）**0 命中**；本任务 6 个改动载体单独扫 **0 命中**。
- 默认 locale：仅余 TASK-118/119/120/121 已登记的 2 条本机引擎伪影
  （`api/…/MapMatchResultDTO.java:17/36`，多分支模式按 cp1252 折叠所致，本任务未触碰该文件）；
  本任务改动载体仍 **0 命中**。

## 契约自证

- 在途（PLAN 记录追加后、收口提交前）：`bash scripts/verify/mailbox-contract.sh --baseline=ad63c3b`
  → TASK-122 判据 A 通过（两件套齐全）、判据 B 通过（只改清单 6 项与实际改动集逐项一致）；
  整体退出码 1 的成因**不在 TASK-122** —— `PLAN.md` 进改动集后历史 handoff 的公共文件 token
  交叠触发既往已登记的「公共文件过冲」（与 TASK-018/120/121 台账同一现象）。
- 收口提交后：`bash scripts/verify/mailbox-contract.sh`（**无参数**）→ 退出 **0**（实测记于 PLAN.md）。

## 提交切分（每步可独立编译）

1. `feat(verify)`：mvn-verify.sh 子命令 + README 说明（纯脚本与文档，不影响任何构建产物）。
2. `ci`：ci.yml 增加静态检查门槛步骤（引用同一入口，工作树代码零变化）。
3. `docs(mailbox)`：台账两件套 + PLAN 验收记录（纯文本）。

## 未决与边界声明

1. **未达外部门槛**：按停止边界本次不 push，CI 新步骤的实际效果（尤其 online 模式下第 1 段
   install 装料在无缓存冷仓的耗时）待下次 push 复验，不作已过门槛的声称。
2. **未把三 goal 绑进 verify 生命周期**（pom 零改动）、**未扩展到其余模块**（`--static=<其他模块>`
   会按该模块自身 pom 判定，未声明三插件的模块按插件默认规则集通常直接红——逐模块治理另立变更），
   均按任务包停止边界执行。
3. 任务包基线数字勘误：包内写 284，实跑 287（TASK-119 起即 287，非本任务增量）。
