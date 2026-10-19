# TASK-277 mapmatch-service Checkstyle 存量清零治理

> 状态：**进行中（待实施）**（2026-10-19 指导侧立项；开工基线 main @ `<TASK-276 封盘笔>`（TASK-276 封盘后串行开工）；MEDIUM 档（零核心逻辑/安全语义改动，触碰 pom 基建故门禁全量逐位），免独立复核子 Agent，指导侧亲核收口）

## 一、背景与动机

TASK-274 实施期确证 mapmatch-service Checkstyle 结构性红灯 **266 项全属基线存量**、新增违规为 0，获指导侧基线豁免挂账。根因：mapmatch-service 无独立 Checkstyle 规则集（record-service / leaderboard-service 各有 `src/main/resources/checkstyle.xml`，从 sun_checks 派生，maven checkstyle 插件 3.6.0 内置 9.3 引擎与基准计数同源），存量 6 个主类从未纳入整改。本课题补齐规则集并清零 266 项存量。

## 二、目标与验收标准（R0–R3）

- **R0 侦察确认基线**：开工时 `git status --porcelain` 为空、TASK-276 已封盘；先实测 `--static=mapmatch-service` 取得违规分布快照（按文件 × 规则类型计数）登记 handoff，作为整改基准。
- **R1 规则集与插件对齐**：新增 `mapmatch-service/src/main/resources/checkstyle.xml`（与 leaderboard-service 现行文件**逐字节一致**复制）；`mapmatch-service/pom.xml` 挂 checkstyle 插件段（版本、绑定 phase、执行目标与 leaderboard-service pom 现行配置逐项对齐，仅路径差异）。
- **R2 存量清零（零语义变更）**：266 项存量全部消除——Javadoc 补齐（类/公有方法/公有字段）、命名规范、行长折行、导入排序等；**只允许注释/格式/标识符重命名级改动，严禁任何可执行语义变更**（不改逻辑、不改签名、不改注解、不改配置值）；改前后逐类目视核对语义零变化。
- **R3 门禁收口**：`--static=mapmatch-service` Checkstyle 0 违规、SpotBugs/PMD 通过；mapmatch 测试数 57 只增不减（本任务不强制新增测试，保持 57 即可）。

## 三、改动白名单（上限 19 项，实际以 handoff 声明为准与 diff 双向全等）

1. `mapmatch-service/pom.xml`
2. `mapmatch-service/src/main/resources/checkstyle.xml`（新增）
3. `mapmatch-service/src/main/java/com/sportverify/mapmatch/MapMatchApplication.java`
4–8. `mapmatch-service/src/main/java/com/sportverify/mapmatch/` 下其余 5 个主类（以 `git ls-files mapmatch-service/src/main/java` 6 文件全集为准）
9–18. `mapmatch-service/src/test/java/` 10 个测试文件（仅实测存在违规者进入改动集，零违规者不得列入）
19. `work/mailbox/tasks/TASK-277/handoff.md`（C-02 台账）

**零触碰红线**：其余六模块一切文件、`scripts/`、`.github/`、`docker-compose*.yml`、`web/`、`sql/`、`api/`、根 pom、`web/package.json`、状态机/MQ/批量/索引参数、`application.yml` 配置值（注释订正也不允许——本任务只有 Java 与 pom 与新配置文件）。

## 四、门禁要求（七门禁全绿，触碰基建全量逐位）

| # | 门禁 | 达标标准 |
| :- | :--- | :--- |
| 1 | offline 全量单测 | BUILD SUCCESS，1164 基线只增不减（mapmatch 57 持平或增），0 败 0 错 0 跳 |
| 2 | 静态门禁（本任务核心） | `--static=mapmatch-service` **Checkstyle 0 违规**；同时复跑 `--static=leaderboard-service` 确认无连带漂移 |
| 3 | 前端三件套 | vitest 90 全绿、vue-tsc 0 错、E2E 14/14（零前端改动，基线论证不变） |
| 4 | 契约门 | `--open=TASK-277 --baseline=<立项笔>` rc=0，判据 A/B 双过 |
| 5 | token 门 29 项 | 基线树与 C-01 树逐位持平；C-02 树 = 基线 + 29 |
| 6 | 词面门 | 四 locale 全 ZERO_HIT（等效手跑 + 阳性对照） |
| 7 | diff 净与行尾 | `git diff --check` 空、CR=0、纯 LF、无 BOM |

## 五、提交纪律

- C-01：`chore(mapmatch): ...（TASK-277）`——实际改动文件与 handoff §2 声明全等（⊆ 白名单上限 18 项）；
- C-02：`docs(mailbox): TASK-277 实施 handoff 回填（TASK-277）`——仅 handoff.md；
- 封盘前 `origin/main..HEAD` 恰 3 笔（立项笔 + C-01 + C-02），零推送。

## 六、停止条件（触发即停并汇报）

1. 266 项中存在必须改可执行语义才能消除的违规类型（如规则与框架生成代码冲突）；
2. checkstyle 插件/引擎版本无法与 leaderboard-service 现行基准对齐；
3. 整改导致 mapmatch 测试红或测试数回退；
4. 需要触碰白名单外文件（含其他模块规则集文件）。

## 七、风控与回滚

- 零语义整改 + 单模块隔离，风险极低；改前后 `mvn -o test`（mapmatch 模块）双跑对照，测试结果必须逐位一致；
- 回滚：revert C-01+C-02 即回到豁免挂账状态，无数据/配置残留。
