# TASK-177 handoff：add-verify-degrade-status-index 复活与实施

> 状态：**已收口（两笔本地提交，未 push）**。§0 开工读数；§1 偏差登记；§2–§10 为结论、清单、测量证据、逐门、机制归因、token、未覆盖项、提交与勘误。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。
> 原始证据日志为仓库根 `task177-*.tmp` 临时文件（gitignore，用毕删除）；关键读数已内联本文件与 `verification.md` attempt-2。

## 0. 开工规程与读数逐位核验（Level A）

- 开工 HEAD（派发笔）：`06c8e6133e8e87fb30186c56a670c9838821fb2d`，`git log -1 --format=%s` = `docs(spec): 派发 TASK-177 滞留扫描复合索引提案修订与任务书`；
- `origin/main` = `f47e8bd7c03c63daea5b42a49cd754033459ce48`；`git rev-list --left-right --count origin/main...main` = `0 1`；
- 工作树：`git status --porcelain` 为空——零残留核验通过（原受保护脏项 `spec/changes/add-verify-degrade-status-index/` 已随派发笔转正，历史「受保护零触碰」约定就此反转，依据任务书 §3）；
- PLAN.md 29 项受保护 token 开工实测与任务书 §5 逐位一致（C-01 前复测与基线全等，详 §7）；
- 在途契约门 `bash scripts/verify/mailbox-contract.sh --open TASK-177 --baseline=06c8e6133e8e87fb30186c56a670c9838821fb2d` **开工态实测 rc=0**；
- 基线离线测试与静态门：
  - offline 七模块 `scripts/verify/mvn-verify.sh --mode=offline test`：`36/41/33/127/144/59/10` 全绿（共 450），Failures/Errors/Skipped 全 0，BUILD SUCCESS，rc=0；
  - Checkstyle `scripts/verify/mvn-verify.sh --static=record-service`：严格 814 处（≤814 达标口径）；
  - 词面门四形态（default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8`）：全 ZERO_HIT rc=1，正向探针 rc=0（工具链双版本差异见 §1.1）。
- 环境核验：docker 确认 `sport-verify-mysql`（MySQL 8.0.46，宿主 3307）健康；scratch 库 `record_idx_scratch` / `record_idx_scratch_mig` / `record_idx_it_scratch` 全自建隔离，演示 `record_db` 与用户数据卷零触碰。

## 1. 偏差登记

### 1.1 工具链双 bash/git 版本差异与词面门假阳性（执行工具侧，已根因定位并留证）

1. 本机存在两套 git 入口：红线指定入口 `D:\git\Git\bin\bash.exe` 携带 git **2.20.1.windows.1**（全部门禁复跑四形态全净）；工具自带 `C:\Users\fzdzzj\.qoder-cn\bin\git\bin\bash.exe` 携带 git **2.52.0.windows.1**，其 `LC_ALL=C.UTF-8` 一种形态下对词面门正则第 3 备选词产生**字节折叠假阳性**——匹配对象为 `MapMatchResultDTO.java` 第 17/36 行「最大垂距」的「大垂」二字（正常业务取词，非违规词），机制为 CP1252 字节 0x8E↔0x9E 在小写折叠下误配。
2. 已留证（最小复现单行文件、locale A/B 矩阵：`LC_ALL=C.utf8` 干净 / 父 shell 环境变量无关、两版 git 交叉复跑日志）；`git hash-object` 证明 `MapMatchResultDTO.java` 工作树 blob 与派发笔 blob 全等（`edacf189ab4ea912ab90de7fbbee3c0ed251f40f`），该文件全程零触碰。
3. 处理：全部正式门禁以红线指定入口复跑（C-01 门禁全项全净、词面门四形态 ZERO_HIT rc=1）；2.52.0 侧读数仅作异常证据登记。CI（Linux git）历次 run 词面门全 success，不受 Windows 具名差异影响。

### 1.2 契约门在途 rc=1 为结构性交叉触发（历史回传声明与白名单动作交叠，非本任务足迹问题）

1. 触发链：契约工具判据 B 对「清单节与实际改动集有交叠」的任务触发「完全一致」要求；本任务两笔白名单改动分别与历史回传声明交叠——① C-01 依 §2.4 D5 触碰 `sql/02-record-db.sql`，与 TASK-103、TASK-108 的清单节交叠（TASK-103 另多报 6 处历史文件、TASK-108 另多报 leaderboard 相关文件）；② C-02 依 §4 白名单触碰 `work/mailbox/PLAN.md`（每轮台账闭环既定动作），与 69 条历史回传的清单节交叠。交叠任务相对本任务改动集必然多报/漏报 ⇒ 在途 rc=1：**C-01 期失败 2 条**（TASK-103/TASK-108）、**C-02 期失败 71 条**（69 条经 `work/mailbox/PLAN.md`，TASK-103/TASK-108 经 `sql/02-record-db.sql`）。
2. 不可修复性：历史回传不可改（不在 §4 白名单，红线零触碰既往台账）；C-01 触碰 `sql/02-record-db.sql` 为 D5 规定动作、C-02 触碰 `work/mailbox/PLAN.md` 为 §4 白名单规定动作。交叠在「基线=派发笔」口径下于两笔在途窗口必然发生，属工具设计内的结构性结果（防虚报复现——TASK-103 正是当年虚报本索引的来源，任务书 §1.1）。
3. 替代证据与判定（四段读数）：① 开工态 rc=0（ACTUAL 空集，全部历史任务「足迹不在工作树」放行）；② C-01 在途 rc=1，失败项仅 TASK-103/TASK-108（TASK-177 彼时无回传未进入判据 B 比对；其余历史任务零失败）；③ C-02 在途 rc=1，失败面为 71 条历史回传（69 条经 `work/mailbox/PLAN.md` 交叠、2 条经 `sql/02-record-db.sql` 交叠），**TASK-177 自身「判据 B 通过（只改清单与实际改动集一致）」行成立**（以工具同构脚本逐任务复算交叠集：71 条失败任务的交叠源与上列逐条一致）；④ 收口无参 rc=0（全部足迹并入提交后 ACTUAL 空集，全库全量放行）。行内其余门禁不受影响。

### 1.3 IT 无法经 harness `--it` 分支承载（Notice）+ 直跑例外

`scripts/verify/mvn-verify.sh` 的 `--it` 分支硬编码 leaderboard IT 清单（脚本 L33-36），record-service IT 无法经该分支承载。按任务书 §0.1 直跑例外执行（镜像 harness offline 纪律的完整命令：`mvn -B -ntp -o -s .mvn-settings.xml -pl record-service -am test -Dtest=SportRecordIndexMysqlIT -Dsurefire.failIfNoSpecifiedTests=false`），完整命令与退出码登记入 `verification.md`；harness 缺口登记为 Notice（供指导侧评估后续参数化 IT 入口）。

### 1.4 scratch 测量保真度口径（非偏差，登记测量面）

scratch 库由改动前建表脚本（`git show 06c8e61…:sql/02-record-db.sql` 机械改名）灌出，并套用 TASK-175 存量迁移的 scratch 适配段补齐 `archived` 列与 `idx_archive`，使索引环境（四索引）与现网存量库一致；被测 SQL 列清单含 `archived`，与当前 `SportRecord` 实体列序逐列对照后固定（WHERE / ORDER / LIMIT 沿 attempt-1 文本）。既有迁移脚本本体零修改（仅以 `sed` 替换 USE 行指向 scratch 库的副本执行）。

## 2. 一句话结论

**完成**：TASK-177 第二轮实施收口——`sport_record` 真实落地复合索引 `idx_status_created (status, created_at)`（建表脚本一行 + 幂等迁移新文件 + IT 2 例复活），滞留扫描 SQL、阈值、扫描上限、状态迁移与业务 Java 零改动、无 index hints。双形态各 16000 行种子四组合实测：加索引后两形态计划均 `type=range key=idx_status_created`；S-sparse 实读行数 16000（全表级）→ 3（status=1 条目级）；S-dense 后置实读 480（小于前置 3266），代价估计升高属预注册豁免、数字如实登记。IT 2/2 绿、迁移双跑幂等。三支裁决归属**第一支（PASSED）**。全项门禁实测通过（在途契约门 rc=1 为历史回传结构性交叉触发——C-01 期 2 条、C-02 期 71 条；本任务自身判据 B 通过、收口无参 rc=0，见 §1.2），两笔提交本地落盘，未达外部门槛。仅登记访问路径变化与实读行数，不声称线上延迟或业务耗时改善。

## 3. 只改清单（与 `git diff --name-only 06c8e6133e8e87fb30186c56a670c9838821fb2d` 逐条比对）

```
record-service/src/test/java/com/sportverify/record/db/SportRecordIndexMysqlIT.java
spec/changes/add-verify-degrade-status-index/tasks.json
spec/changes/add-verify-degrade-status-index/verification.md
sql/02-record-db.sql
sql/migrations/add-idx-record-status.sql
work/mailbox/PLAN.md
work/mailbox/tasks/TASK-177/handoff.md
work/mailbox/tasks/TASK-177/spec.md
```

前 4 条为 C-01 实施笔（4 files / +496 −1：IT 样例 +242、验收记录 attempt-2 +227、建表脚本 +2 −1、幂等迁移新文件 +25；哈希 `2876cd8ab998064fff02db8f99abf5b5ee6e7dac`）；后 4 条为 C-02 台账笔（提案任务清单闭环勾选 10 步 completed + 3 分组 passes 全 true；任务书末尾纯追加「收口记录」；本回传；台账纯追加验收记录与 29 项 token 表）。收口终检两笔合计恰上列 8 条，与任务书 §4 白名单完全一致。显式零触碰：业务 Java（服务类与其余生产源码）、既有迁移脚本、配置、构建脚本、pom 模块、`spec/changes/` 其余目录。

## 4. 测量证据与三支判定（Level A）

### 4.1 环境与正交性

- scratch 库 `record_idx_scratch`（容器 `sport-verify-mysql`，MySQL **8.0.46**），绝不触碰演示 `record_db`；scratch 由改动前 DDL 灌出并套用 TASK-175 存量迁移的 scratch 适配段（`archived` 列 + `idx_archive`），索引环境与现网存量库一致（PRIMARY / uk_request_id / idx_user_time / idx_archive）。
- 被测 SQL（列清单与当前 `SportRecord` 实体列序一致，含 `archived`；WHERE / ORDER / LIMIT 沿 attempt-1 文本）：

```sql
SELECT id, request_id, user_id, sport_type, start_time, end_time, distance, duration, status, archived, version, created_at
FROM sport_record
WHERE (status = 1 AND created_at < '2026-10-07 15:00:00')
ORDER BY id ASC
LIMIT 100;
```

- 阈值字面量 T = `2026-10-07 15:00:00`；双形态种子各 16000 行（S-sparse：14537 PASSED + 640 REJECTED + 320 MANUAL_REVIEW + 500 新鲜 VERIFYING + 3 滞留 VERIFYING；S-dense：14560 PASSED + 640 REJECTED + 320 MANUAL_REVIEW + 480 全滞留 VERIFYING；均以 7919-mod-16000 双射交错注入，无整段聚集），种子 SQL 全文入 `verification.md` attempt-2；
- 正交性：前后两次捕获之间数据不变，仅差一步 `ALTER TABLE sport_record ADD INDEX idx_status_created (status, created_at)`；每次捕获前先 `ANALYZE TABLE sport_record`，EXPLAIN 与 EXPLAIN ANALYZE 双留档。

### 4.2 四组合关键读数矩阵（MySQL 8.0.46 实测）

| 组合 | 加索引后计划 | 实读行数（ANALYZE） | 单次时间读数 |
| --- | --- | --- | --- |
| S-sparse 前置 | `type=index key=PRIMARY`（主键序扫描 + 过滤） | **16000**（全表级） | 2.35..3.36ms |
| S-sparse 后置 | `type=range key=idx_status_created key_len=7`，`Using index condition; Using filesort` | **3**（status=1 条目级） | 0.297ms |
| S-dense 前置 | `type=index key=PRIMARY` | **3266**（凑满 100 条命中前扫描行数） | 0.0985..1.19ms |
| S-dense 后置 | `type=range key=idx_status_created key_len=7`，`Using index condition; Using filesort` | **480**（= status=1 全量候选） | 1.22..1.23ms |

加索引后两形态 `SHOW INDEX` 均含 `idx_status_created`（Non_unique=1，列序 status → created_at）。时间读数为单次 EXPLAIN ANALYZE 口径，非基准，不外推。

### 4.3 三支判定归属（任务书 §2.2 预注册）

- 判别式逐条：① 两形态加索引后计划均 `key=idx_status_created (type=range)`——满足；② S-sparse 前置实读 16000 行（>15000 全表级）、后置实读 3 行（status=1 条目级，数百内）——满足；③ IT 2/2 绿——满足（§5 G3）。**判定：第一支（PASSED）**。
- S-dense 豁免登记（预注册）：后置代价估计（cost=216）高于前置（cost=9.82）不构成否决——索引服务生产形态（S-sparse），dense 为事故场景且 attempt-1 已证优化器会选中该索引；数字如实登记：前置扫描 3266 行才凑满 100 条命中，后置范围扫描 480 条候选（filesort 输入规模 480）后取前 100；后置实读行数（480）小于前置（3266），代价估计升高来自 filesort 估计；两端末段单次时间读数同量级（1.19ms vs 1.23ms，单次读数，非基准）。
- 已考虑不采纳方案登记（任务书 §2.3）：`(status, id)` 替代索引——created_at 不在索引内，常见路径需对 status=1 的每一条回表核 created_at，本轮不实施。
- 迁移幂等验证（`record_idx_scratch_mig`）：迁移脚本 scratch 适配副本连续执行两次，第一次建出索引（rc=0），第二次输出「已存在，跳过」（rc=0）；`SHOW INDEX` 恰为四索引。

## 5. 逐门实测退出码

| 门 | 判据 | 实测 | 结果 |
| --- | --- | --- | --- |
| G0 开工读数 | 任务书 §3 开工态逐位核验 | HEAD `06c8e61…`、origin `f47e8bd…`、`0 1`、工作树零残留、token 29/29 逐位一致、契约门开工态 rc=0 | 过 |
| G1 offline 全模块 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | rc=**0**，`36/41/33/127/144/59/10`、全仓 **450**、Failures/Errors/Skipped 全 0、BUILD SUCCESS | 过 |
| G2 Checkstyle | `bash scripts/verify/mvn-verify.sh --static=record-service` | rc=**1**，`You have 814 Checkstyle violations`，严格 814 持平（≤814 达标口径；`src/test` 不在扫描面，新文件零新增违规） | 过 |
| G3 IT 直跑 | §0.1 直跑例外（harness `--it` 无法承载，见 §1.3） | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，rc=**0** | 过 |
| G4 词面门 | 正则自 ci.yml 现场提取，四形态 `git grep -n -I -iE`（红线指定 bash 入口） | default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8` 全 **ZERO_HIT rc=1**；正向探针 rc=0；工具链双版本差异见 §1.1 | 过 |
| G5 契约门 | 开工态 rc=0；C-01 在途 rc=1（失败仅 TASK-103/TASK-108）；C-02 在途 rc=1（失败 71 条：69 条经 `work/mailbox/PLAN.md`、2 条经 `sql/02-record-db.sql`；TASK-177 自身「判据 B 通过」行成立）；收口无参 rc=0 | 四段读数见 §1.2；结构性交叠经登记 | 过（含 §1.2 登记） |
| G6 空白门 | `git diff --check`（工作树 + staged） | rc=**0**（无 trailing whitespace、无 EOF extra newline） | 过 |
| G7 只改清单 | `git diff --name-only 06c8e61…` + untracked | C-01+C-02 合计恰 §3 全 8 条，与任务书 §4 白名单完全一致 | 过 |
| G8 红线（语义冻结/工具链） | `VerifyDegradeService.java` 及其余业务 Java 零改动；禁 index hints；既有迁移零修改；无新插件；bash 仅红线入口脚本文件 | 全程零触碰（新增文件仅 §4 白名单两项）；C-01 diff 无服务类改动、零 index hints | 过 |
| G9 受保护 token | PLAN.md 行命中数（`grep -cF`）只增不减 | 开工 29 项与任务书 §5 逐位一致；C-01 前复测与基线全等；C-02 后复测全部 +1（§7） | 过 |

## 6. 机制归因与访问路径要点

1. **访问路径变化（本轮唯一断言）**：滞留扫描由无索引下的主键序扫描 + 过滤（S-sparse 实读全表级 16000 行）变为 `idx_status_created` 范围扫描（实读 3 行）+ filesort 取前 100；服务形态的实读行数从全表级降至 status=1 条目级。这是 EXPLAIN / EXPLAIN ANALYZE 口径的访问路径证据，不是线上延迟结论。
2. **S-dense 事故形态边界**：480 条全滞留候选时 LIMIT 100 不能提前停止（正是 attempt-1 否决理由②的场景），filesort 输入 480、代价估计升高于前置；预注册豁免下如实登记，不翻案 attempt-1 裁决原文。
3. **幂等迁移模式**：沿 `add-idx-record-seq.sql` 的 information_schema 计数 + PREPARE/EXECUTE 模式，仅 sport_record 单表；存量库升级由运维执行 `bash scripts/db/migrate.sh` 补齐，本轮不触碰演示卷。
4. **测量正交性设计**：双形态种子 7919 双射交错注入避免整段聚集伪象；捕获前强制 ANALYZE TABLE；前后仅差 ADD INDEX 一步；scratch 独立库与 IT 自建库双重隔离。

## 7. 受保护 token（PLAN.md 行命中数 `grep -cF`）

29 项基线实测与终态实测对照（全部满足只增不减；C-01 不含 PLAN.md 改动，其前置复测与基线全等，此处列 C-02 后终态）：

| Token | 基线值 | 终态值 | 变动 |
| --- | --- | --- | --- |
| 13.4 | 17 | 18 | +1 |
| 18.0 | 19 | 20 | +1 |
| 73.93 | 18 | 19 | +1 |
| 68.8 | 18 | 19 | +1 |
| 6315 | 15 | 16 | +1 |
| 1.8612 | 14 | 15 | +1 |
| 3.3066 | 14 | 15 | +1 |
| 5.7056 | 14 | 15 | +1 |
| 9.408 | 14 | 15 | +1 |
| 36525962432 | 14 | 15 | +1 |
| 36586847965 | 13 | 14 | +1 |
| 36438897772 | 14 | 15 | +1 |
| 36399582548 | 13 | 14 | +1 |
| 36098038547 | 13 | 14 | +1 |
| 2806 | 20 | 21 | +1 |
| 598 | 13 | 14 | +1 |
| 36736221648 | 12 | 13 | +1 |
| 36808102571 | 7 | 8 | +1 |
| 36821040708 | 5 | 6 | +1 |
| 36845152965 | 4 | 5 | +1 |
| 36871294588 | 4 | 5 | +1 |
| 36880083885 | 5 | 6 | +1 |
| 36958994260 | 5 | 6 | +1 |
| 36976873215 | 5 | 6 | +1 |
| 36992632143 | 4 | 5 | +1 |
| 36995450125 | 2 | 3 | +1 |
| 37008317295 | 3 | 4 | +1 |
| 37021305016 | 4 | 5 | +1 |
| 37591580687 | 3 | 4 | +1 |

本文件与 PLAN / spec 追加段均不含词面门正则字面量与敏感词。

## 8. 未覆盖项与不得推出的结论（任务书 §7 如实登记）

1. **无线上延迟/吞吐测量**：本轮仅访问路径（执行计划）与 ANALYZE 实读行数；不得外推为线上延迟、业务耗时或吞吐改善；不计算任何百分比推导。
2. **环境与数据限定**：16000 行合成种子、单机容器 MySQL 8.0.46、单次 EXPLAIN / ANALYZE 口径；生产真实数据分布与并发负载下优化器行为未测。
3. **存量库未升级**：演示卷 `record_db` 零触碰；存量环境索引补齐需运维执行 `bash scripts/db/migrate.sh`；灰度与回滚流程未演练。
4. **S-dense 事故形态收益不确定**：全滞留场景下 filesort 代价估计升高（预注册豁免、如实登记）；极端滞留堆积下收益不做承诺。
5. **外部门槛**：`--mode=online` 与 CI 未跑 ⇒ **未达外部门槛**（未 push；push 须用户显式单次授权）。
6. **harness `--it` 缺口**：record-service IT 直跑为例外路径，未纳入官方门禁自动化（Notice 登记 §1.3）。
7. **范围外**：服务内第二条 MANUAL_REVIEW 扫描（`VerifyDegradeService` L131-134）不在本提案范围，未测；不翻案 attempt-1 裁决原文。

## 9. 提交

| 提交 | 内容 |
| --- | --- |
| C-01 `2876cd8ab998064fff02db8f99abf5b5ee6e7dac` | `feat(record): sport_record 新增滞留扫描复合索引 idx_status_created（TASK-177）`（§3 前 4 条，4 files / +496 −1） |
| C-02（哈希以 `git log` 实测为准，读数见交付汇报） | `docs(mailbox): 登记 TASK-177 验收记录与提案闭环（TASK-177）`（§3 后 4 条：提案任务清单闭环 + 任务书收口记录纯追加 + 本回传 + 台账纯追加） |

收口终检（C-02 后实测，输出见交付汇报）：契约门无参 rc、`git diff --check` rc、`git diff --name-only 06c8e61…` 8 条比对、`git status --porcelain` 为空、`git rev-list --left-right --count origin/main…main` = `0 3`。

## 10. 勘误登记

本笔无勘误。若后续独立复核退回订正，沿 TASK-176 C-03/C-04 先例以纯台账修正笔追加。
