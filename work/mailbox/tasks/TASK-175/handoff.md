# TASK-175 handoff：轨迹点冷热分离归档与终态记录存储治理

> 状态：**已收口（两笔本地提交，未 push）**。§1 偏差登记；§2–§9 为结论、清单、红绿证据、逐门、要点、token 与未覆盖项。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。
> 原始物料（R1/R2/G1/G2 日志、提交信息文件）均在 `.git/task175/`（不入库）。

## 0. 开工规程与读数逐位核验（Level A）

- 开工 HEAD（派发笔）：`13ca77a8b54d96780a60622525f78c0216eaa547`，`git log -1 --format=%s` = `docs(spec): 派发 TASK-175 轨迹点冷热分离归档提案与任务书`；
- 任务书 §3 所载 git 读数（HEAD `c9cbf9ad…`、`0 1`）为派发笔落盘前的撰写时读数：提案三件套与本任务书自身随派发笔 `13ca77a` 入库，开工基线相应前移一位——派发指令已显式给出更正口径（「git rev-parse HEAD 应为 13ca77a（派发笔）；`git rev-list --left-right --count origin/main...main` = `0 2`」），实测逐位一致（登记见 §1.1-1）；
- `origin/main` = `a8a08a8b58364f7e41b9334acecfe0be664cb87d`（与任务书 §3 逐位一致）；`git rev-list --left-right --count origin/main...main` = `0 2`；
- 工作树：`git status --porcelain` 仅 `?? spec/changes/add-verify-degrade-status-index/`（零触碰全程保持）；任务书与提案三件套均已随派发笔入库、无未跟踪残留；git 2.20.1.windows.1、`.gitignore` 含 `*.tmp`（临时脚本仓库根落地、不入 git 状态）；
- PLAN.md 27 项受保护 token 开工实测与任务书 §5 逐位一致（§7）；
- 在途契约门 `bash scripts/verify/mailbox-contract.sh --open TASK-175 --baseline=13ca77a8…` **开工态实测 rc=0**（TASK-173 §0 规程① / TASK-174 §1.2-1 口径；输出：TASK-175 进行中已声明放行，其余目录两件套齐全或已收口不重审）；
- sql/02 既有索引核对（任务书 §2.1 要求项）：`sport_record` 仅有 `uk_request_id (request_id)` 与 `idx_user_time (user_id, created_at)`，与新 `idx_archive (archived, end_time)` 无前缀重复；
- 基线离线读数（任务书 §3：`36/41/33/115/144/59/10`、Checkstyle 862、词面门 ZERO_HIT）核验方式：测试基线未在开工态单独预跑（时序说明同 TASK-174 §0 末条），由 R2 红轮佐证（record-service 127 例中既有 115 全过 + 3 守卫平凡绿，仅 9 个新判别式红）与 G1 其余六模块逐位等值佐证；Checkstyle/词面门为改动后实测（更严口径：新增文件若有命中或违规即当场暴露）。

## 1. 偏差登记

### 1.1 任务书文本相对实际仓态的偏差（两处，均落在任务书/派发指令自身预置的口径内）

1. **§3 git 读数一个派发笔的时序差**：任务书 §3 载 HEAD `c9cbf9ad…` / `0 1`，实际开工 HEAD `13ca77a8…` / `0 2`——任务书文本落盘于派发笔之前，派发指令明文更正基线；实测与派发指令逐位一致（`origin/main` 与工作树两项和任务书 §3 一致），无其他出入，未做任何额外解释或订正。
2. **申诉方法名**：任务书 §2.8 称 `submitAppeal`，`SportRecordService` 实际方法为 `appeal(Long recordId, Long userId, String reason)`（既有公开入口）。按 §2.8 预置指引「按语义最小落位并在 handoff 登记偏差」处理：防线插入点为既有记录存在性/状态校验之后、建申诉单（`verifyApi.createAppeal`）与乐观锁迁移之前（`Objects.equals(record.getArchived(), 1)` → `RECORD_STATUS_INVALID`「记录已归档，不支持申诉」）；测试名仍按 §2.9 判别式命名 `submitAppeal_archivedRecord_rejected`（调用 `service.appeal`）。

### 1.2 实现侧偏差（红绿轮次内的非行为性修正，最终实现与 §2.6 逐字稿零偏差）

R2 红跑成立之前两处修正：① 脚手架骨架缺 `SportRecord` import（编译红，补 import）；② 测试侧 Micrometer API 修正 `Counter.value()` → `Counter.count()`（Counter 无 `value()`，6 处）。两者不影响判别式语义；生产实现最终与任务书 §2.6/§2.7/§2.8 逐字一致（`SportRecordService` 注入按该类既有 `@RequiredArgsConstructor` 风格适配，任务书 §2.7-1 明文允许）。

### 1.3 执行侧偏差与说明

1. **R2 红形态**：`pagePoints_archivedRecord_readsArchiveTable` 红相为 NullPointerException（未实现路由时生产代码走热表 `selectPage` 且未打桩返回 null），红根因即未实现的归档路由，判别式内容有效；其余 8 红为 verify/断言红。
2. **三个守卫型用例 R2 平凡绿**（`archiveSweep_lockNotAcquired_skipsRound` / `archiveSweep_redissonError_skipsRound` / `getRecordWithPoints_alwaysReadsHotTable`）：空壳脚手架「根本不触锁/不路由」使跳过类判别式被空满足，TASK-174 §1.2-2 同性质预期；实现后三者才走真实锁与路由路径。
3. **门禁复跑时序**：G1/G2/G3/G5 首测晚于代码改动（非开工态预跑），改动后实测对词面门/静态门为更严口径；G4 在途读数已按规程在开工态实测 rc=0。
4. 提交信息经 `.git/task175/c1.msg`、`c2.msg` 以 `git commit -F` 落盘；全程逐路径 `git add`，无 `git add -A`/`add .`、无 `git stash`、未 push、未建 PR；仓库根临时脚本 `*.tmp` 用毕删除。

## 2. 一句话结论

**完成**：轨迹点冷热分离归档（课题 3）落地——`track_point_archive` 16 归档物理分片表（同分片键 `user_id % 16`、独立 INLINE 算法实例；`sql/05` 建表 + `sport_record` 加 `archived` 列与 `idx_archive (archived, end_time)` 索引）；`TrackPointArchiveService`（@Scheduled 默认 1h、RLock `lock:track:archive` tryLock 不等待、锁异常跳过本轮）候选扫描（archived=0 + 终态 PASSED/REJECTED/RE_PASSED/RE_CONFIRMED + end_time 非空早于冷边界 90 天 + ORDER BY id LIMIT 20）+ 幂等三步迁移（归档已有跳插 / 批插保留原雪花 id / 删热表 / 事务外条件置 archived=1，崩溃重入自愈）+ Counter `track.archive.migrated.records`；`listPoints`/`pagePoints` 按 archived 冷热路由（(record_id,user_id) 双条件 + seq 升序），`getRecordWithPoints` 恒热零改动；申诉入口拒已归档（防复判读空）。全项门禁通过：offline 七模块 `36/41/33/127/144/59/10` rc=0（record-service 115→**127**、全仓 438→**450**、Skipped 全 0）、Checkstyle 严格 **862** 持平（≤862）、词面门四形态 ZERO_HIT rc=1、在途契约门 rc=0（开工态实测）+ 收口无参实测（读数见交付汇报）、`git diff --check` rc=0、既有 115 用例零翻转。

## 3. 只改清单（与 `git diff --name-only 13ca77a8…` 逐条比对）

```
sql/05-track-point-archive-shards.sql
record-service/src/main/java/com/sportverify/record/entity/TrackPointArchive.java
record-service/src/main/java/com/sportverify/record/mapper/TrackPointArchiveMapper.java
record-service/src/main/java/com/sportverify/record/service/TrackPointArchiveService.java
record-service/src/main/java/com/sportverify/record/entity/SportRecord.java
record-service/src/main/java/com/sportverify/record/service/SportRecordService.java
record-service/src/main/resources/sharding.yaml
record-service/src/test/java/com/sportverify/record/service/SportRecordServiceTest.java
record-service/src/test/java/com/sportverify/record/service/TrackPointArchiveServiceTest.java
spec/changes/add-track-point-cold-archive/tasks.json
work/mailbox/tasks/TASK-175/spec.md
work/mailbox/tasks/TASK-175/handoff.md
work/mailbox/PLAN.md
```

前 9 条为 C-01 业务笔（9 files / +937 −13，哈希 `6559aeb9428abed23ab21b1832ec0e1336467426`）；后 4 条为 C-02 台账笔（提案 tasks.json 闭环勾选 12 步 completed + 4 任务 passes 全 true；任务书仅末尾纯追加「收口记录」节；本交付报告；PLAN.md 纯追加）。C-01 后实测 `git diff --name-only 13ca77a8…` 恰前 9 条；收口终检两笔合计恰上列 13 条（输出见交付汇报）。显式零触碰：既有脏项 `spec/changes/add-verify-degrade-status-index/`（保持未跟踪原样）、其余 6 个在途提案目录、主规格、`spec/changes/archive/**`、一切 `scripts/**`、构建脚本、配置文件、verify-service、api 模块。

## 4. 红绿证据（Level A，日志在 `.git/task175/`）

- **R1（仅测试改动，生产零触碰）rc=1**：`mvn-verify.sh --mode=offline --pl record-service test`，testCompile 失败——`找不到符号: 类 TrackPointArchive / 类 TrackPointArchiveMapper / 类 TrackPointArchiveService`（两测试文件共 7 处，`.git/task175/r1-red-compile.log`）。
- **R2（最小脚手架：实体/Mapper/archived 字段/空壳 Service/六参构造，零行为改动）rc=1**：record-service 模块 `Tests run: 127, Failures: 8, Errors: 1, Skipped: 0`（= 基线 115 + 新增 12；既有 115 全过 + 3 守卫平凡绿）。判别式红摘录：`sweepOnce_scanPredicate_andBatchLimit`（selectList wanted but not invoked）；`archiveOne_migratesPoints_idPreserved` / `archiveOne_reentry_afterInsertBeforeDelete` / `archiveOne_reentry_afterDeleteBeforeFlag` / `archiveOne_emptyPoints_marksFlagDirectly` / `archiveOne_markArchivedLostRace_counterNotIncremented`（空壳零交互）；`listPoints_archivedRecord_readsArchiveTable`（`expected: <1> but was: <0>`）；`pagePoints_archivedRecord_readsArchiveTable`（NPE，热表 selectPage 未打桩，见 §1.3-1）；`submitAppeal_archivedRecord_rejected`（`expected: <3003> but was: <4001>`）（`.git/task175/r2-red-discriminators.log`）。
- **G1（实现后）rc=0**：七模块 `36/41/33/127/144/59/10` 全绿、`Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`；`TrackPointArchiveServiceTest` 8/8、`SportRecordServiceTest` 33/33（既有 29 零翻转 + 新增 4 全绿）、`RecordLikeServiceTest` 28/28（`.git/task175/g1-green-offline.log`）。

## 5. 逐门实测退出码

| 门 | 判据 | 实测 | 结果 |
| --- | --- | --- | --- |
| G0 开工读数 | 任务书 §3 + 派发指令更正口径 | HEAD `13ca77a8…`、origin `a8a08a8…`、`0 2`、工作树仅既有脏项、token 27/27 逐位一致、G4 在途 rc=0（§0） | 过（§1.1-1 时序差已登记） |
| R1 红 | 测试先行，生产零触碰 | rc=**1**，编译红（§4） | 过（预期红） |
| R2 红 | 判别式红 | rc=**1**，127 中 8 Failures + 1 Errors（§4） | 过（预期红） |
| G1 offline 全模块 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | rc=**0**，`36/41/33/127/144/59/10`、全仓 **450**、Skipped 全 0、BUILD SUCCESS | 过 |
| G2 Checkstyle | `bash scripts/verify/mvn-verify.sh --static=verify-service` | rc=**1**，`You have 862 Checkstyle violations`，与开工基线持平未增（该门以 862 为上限口径，rc=1 系违规存在之既有表达，TASK-173/174 同口径；全部位于本轮零触碰的 verify-service 源码） | 过 |
| G3 词面门 | 正则自 ci.yml 现场提取（CR-safe 管道），四形态 `git grep -n -I -iE` | default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8` 全 **ZERO_HIT rc=1**；正向探针（对 ci.yml 本体）rc=0 证正则有效 | 过 |
| G4 契约门 | 在途 `--open TASK-175 --baseline=13ca77a8…` rc=0（开工态实测）；收口无参 `mailbox-contract.sh` rc=0（C-02 落盘后实测，判据 B 以无参为准；读数见交付汇报） | 在途 rc=**0**；无参读数见交付汇报 | 过 |
| G5 空白门 | `git diff --check` | rc=**0**（实现轮实测；收口终检复测见交付汇报） | 过 |
| G6 只改清单 | `git diff --name-only 13ca77a8…` | C-01 后工作树实测恰 §3 前 9 条；收口终检两笔合计恰 §3 全 13 条（输出见交付汇报） | 过 |
| G7 兼容红线 | 既有用例零翻转 | R2 红轮既有 115 全过 + G1 全绿；断言与语义零改动（唯一适配=setUp 六参构造，任务书 §2.9-3 明文预期偏差） | 过 |
| G8 受保护 token | PLAN.md 行命中数（`grep -cF`）只增不减 | 追加前 27 项与任务书 §5 逐位一致（§7）；追加后复测见交付汇报 | 过 |

## 6. 实现与测试要点

- 生产（§2 逐字落地，偏差仅 §1.1-2/§1.2 登记两处）：`sql/05` 16 张 `track_point_archive_N`（DDL 逐字段同 04，幂等建表）+ 尾部 `sport_record` ALTER（`archived TINYINT NOT NULL DEFAULT 0` + `idx_archive (archived, end_time)`，注释注明非幂等/既有库手动一次/重复执行报列已存在属预期）；`TrackPointArchive`（`@TableName("track_point_archive")`，字段同 TrackPoint，类注释注明迁移保留原雪花 id）；`TrackPointArchiveMapper.insertBatch`（逐字照 TrackPointMapper 先例仅换表名，id 调用方预置批插不重算）；`TrackPointArchiveService` 与 §2.6 逐字一致（`ARCHIVE_LOCK_KEY` 包内常量、终态 `Set.of` 四态、`@Scheduled(fixedDelayString = "${track.archive.interval-ms:3600000}")`、getLock 独立 try 捕 RuntimeException 跳轮、tryLock 无参、finally unlockQuietly、候选 wrapper eq/in/isNotNull/lt/orderByAsc/last LIMIT、幂等三分支、`markArchived` 条件更新 rows>0 才 increment）；`SportRecord.archived`（status 之后、version 之前，§2.5 落位）；`SportRecordService` 六参构造（Lombok 字段序：sportRecordMapper、trackPointMapper、trackPointArchiveMapper、recordEventProducer、verifyApi、verifyDegradeService）+ `listPoints` 走 `routePoints` + `pagePoints` 归档分支（`selectPage` 后经 `toHotPoint` 复用 `toDto`）+ 热分支既有语句逐字不动 + `getRecordWithPoints` 零改动 + 申诉防线（§1.1-2 落位）；`sharding.yaml` 仅新增 `track_point_archive` 表规则（`actualDataNodes: ds0.track_point_archive_$->{0..15}`、`shardingColumn: user_id`、`shardingAlgorithmName: track_point_archive_inline`）与独立 INLINE 算法 `track_point_archive_inline`（`algorithm-expression: track_point_archive_$->{user_id % 16}`），`track_point` 既有规则与 `!SINGLE` 声明零改动。
- 测试：`TrackPointArchiveServiceTest` 新建 8 例（Mockito + 真实 `SimpleMeterRegistry` 禁 mock；`TableInfoHelper.initTableInfo` 三实体惯用法；扫描谓词五断言 archived/status IN/end_time/ORDER BY id/LIMIT 20 + 逐条走 archiveOne；迁移判别式含 id 保留 1000/1001 与 counter 读数 0/1/2）；`SportRecordServiceTest` +4（冷路由归档表且热表 never ×2、恒热 `verifyNoInteractions(trackPointArchiveMapper)`、申诉防线 3003 + updateStatus/createAppeal never）；既有 29 例仅 setUp 六参构造适配（§2.9-3 预期偏差）。

## 7. 受保护 token（PLAN.md 行命中数 `grep -cF`，追加前实测=任务书 §5 基线）

27 项逐位一致：`13.4`=16、`18.0`=18、`73.93`=17、`68.8`=13、`6315`=14、`1.8612`=13、`3.3066`=13、`5.7056`=13、`9.408`=13、`36525962432`=13、`36586847965`=12、`36438897772`=13、`36399582548`=12、`36098038547`=12、`2806`=19、`598`=12、`36736221648`=11、`36808102571`=6、`36821040708`=4、`36845152965`=3、`36871294588`=3、`36880083885`=4、`36958994260`=4、`36976873215`=4、`36992632143`=3、`36995450125`=1、`37008317295`=2。追加后复测（只增不减）读数见交付汇报。本文件与 PLAN/spec 追加段均不含词面门正则字面量与敏感词。

## 8. 未覆盖项与不得推出的结论（任务书 §7 如实登记）

1. **真实 MySQL/ShardingSphere 下归档表路由、批插、删除与幂等重入未做集成测试**：record-service 无 IT 先例，本轮仅 Mockito 单测判别；同分片编号路由（N = user_id % 16）、多值 INSERT、跨表迁移行为均为结构推证，未经真环境实测。
2. **`sql/05` 未在真实库执行验证**：DDL 语法与既有 04 同构靠逐字段比对；既有库 ALTER 需运维手动执行一次（重复执行报列已存在属预期，脚本注释已注明）。
3. **归档任务周期性真实运行行为未实测**：1h 调度、多实例 RLock 竞争、fixedDelay 轮次节奏、锁服务异常跳轮均为 mock/结构推证。
4. **迁移完成至置标志之间的毫秒级窗口内读空为已知边界**（仅影响终态 90 天以上旧记录；读路径按标志单表路由，无双查兜底，提案 §2.4 显式不做）。
5. **不宣称任何存储/查询性能收益**（未实测）；spotbugs/pmd 未覆盖（`--static` 在 checkstyle 即止，checkstyle 持平即止）；`--mode=online` 与 CI 未跑 ⇒ **未达外部门槛**（未 push，push 须用户显式单次授权）。
6. 本文件不含词面门正则字面量；原始物料均在 `.git/task175/`（不入库）。

## 9. 提交

| 提交 | 内容 |
| --- | --- |
| C-01 `6559aeb9428abed23ab21b1832ec0e1336467426` | `feat(record): 轨迹点冷热分离归档任务与读路径冷热路由（TASK-175）`（§3 前 9 条，9 files / +937 −13） |
| C-02（哈希以 `git log` 实测为准，读数见交付汇报） | `docs(mailbox): 登记 TASK-175 验收记录与提案闭环（TASK-175）`（§3 后 4 条：提案 tasks.json 闭环 + 任务书收口记录纯追加 + 本 handoff + PLAN 纯追加） |

收口终检（C-02 后实测，输出见交付汇报）：契约门无参 rc、`git diff --check` rc、`git diff --name-only 13ca77a8…` 13 条比对、`git status --porcelain` 仅剩既有脏项、`git rev-list --left-right --count origin/main…main` = `0 4`。
