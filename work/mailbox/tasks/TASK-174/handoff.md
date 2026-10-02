# TASK-174 handoff：点赞读路径防击穿治理与 pending 队列可观测（互斥重建 + 空值哨兵 + 队列度量）

> 状态：**已收口（两笔本地提交，未 push）**。§1 偏差登记；§2–§8 为结论、红绿证据、逐门、清单与未覆盖项。
> 证据等级：A = 本会话亲跑一手读数；B = 仓库文件比对或算术派生；C = 上游散文（未复核）。
> 原始物料（R1/R2/G1/G2/G3/G4/G8 日志、补丁、提交信息文件）均在 `.git/task174/`（不入库）。

## 0. 开工规程与读数逐位核验（Level A）

- 派发笔（开工 HEAD）：`fe3ac4f1a9ad2d9f708c8b40f153ba10004bca04`，`git log -1 --format=%s` = `spec(record): 建立 add-like-read-cache-breakdown-protection 变更提案三件套与 TASK-174 任务书`，与任务书 §3「派发笔自身即开工基线」一致；
- 派发笔父提交 = `c4f92abc7feb3dddc1880e1160a1b3a5ea893e03`（= 任务书 §3 所载门槛基线，逐位一致）；`origin/main` = `af17908d8cde8a77687e2340d993aec68de81d69`（逐位一致）；
- `git rev-list --left-right --count origin/main...main` = `0 2`（逐位一致）；
- 工作树：`git status --porcelain` 仅 `?? spec/changes/add-verify-degrade-status-index/`（零触碰全程保持）；git 2.20.1.windows.1、`.gitignore` 含 `*.tmp`（临时脚本仓库根落地、不入 git 状态）；
- 任务书与提案三件套零修改（本任务仅按 §4 白名单勾选提案 tasks.json 闭环，proposal 与 spec-delta 正文零改动）；
- 基线离线读数（任务书 §3：`36/41/33/105/144/59/10`、Checkstyle 862、词面门 ZERO_HIT、契约在途 rc=0）核验方式说明：git 态读数开工逐位亲验；测试/静态基线未在开工态单独预跑（时序偏差见 §1.2-3），由 R2 轮模块聚合（record-service `Tests run: 115` = 基线 105 + 本轮新增 10）与收口轮其余六模块 `36/41/33/_/144/59/10` 逐位等值佐证，Checkstyle/词面门/契约门为改动后实测且均达基线口径（改动后实测对词面门/静态门是更严口径：新增文件若有词面命中或违规即当场暴露）。

## 1. 偏差登记

### 1.1 实现相对任务书 §2.2 逐字草案的偏差（一处，最小化）

1. **`getLock` 移入 try 块**：§2.2 草案将 `RLock lock = redissonClient.getLock(...)` 置于 try 之外，而任务书自身三处要求覆盖锁服务全路径异常——§2.6 判别式 `getLike_redissonError_degradesToDirectBackfill` 对 **getLock** 打桩抛错且要求「不抛锁异常」；spec-delta「锁服务异常降级」Scenario 的 GIVEN 为「Redisson 锁服务**在重建路径上**抛出异常」；提案 tasks.json 任务 1 步 2 为「不向上抛锁异常」。首版逐字落地后 G1 首跑该用例红（`Runtime redisson down` 上抛，`.git/task174/g1-green-offline.log`）。处置：仅把该语句移入 try（声明改为 `RLock lock = null`，锁引用仅在 `locked==true` 分支解引用，无新增空引用路径），其余逐字不变；修正后全绿。属满足任务书自身判别式与 spec-delta SHALL 的最小语法位偏差（TASK-173 §1.2-1 同性质：照抄将与更高效力的验收判据冲突）。

### 1.2 执行侧偏差与说明

1. **G4 在途口径时序**：首次实测发生在代码改动之后，rc=1——根因是历史任务 TASK-137 的既有 claims 含 `RecordLikeService.java` 与 `RecordLikeServiceTest.java`，与本任务在途同文件足迹相对基线 `fe3ac4f` 交叠，触发判据 B 对其重审（其清单内其余历史路径与既有脏项四文件被记不一致）。按 TASK-173 §0 规程①口径，该读数应在开工态实测：遂将本任务两文件改动存补丁（`.git/task174/wip-before-g4.patch`）→ `git checkout -- <两路径>` 恢复开工态（`git status --porcelain` 仅剩既有脏项）→ 实测 `--open TASK-174 --baseline=fe3ac4f…` **rc=0** → `git apply` 还原，改动 diff 经 `git hash-object --stdin` 前后同为 `53d485506ff2f8d655a7eb1ea36dbea1bd4cee72`（逐字节一致）。rc=0 为开工态真实实测，全程无 `git stash`。同族表达边界（既有脏项未跟踪文件恒在判据 B 实际改动集内）与 TASK-173 §1.2-3、TASK-161 §203、TASK-167 G10 登记一致。
2. **红绿三轮形态**：R1 仅改测试（生产零触碰）→ 编译红；R2 最小脚手架（仅七参构造器落地，不注册 Gauge、不改任何行为）→ 判别式红；实现后全绿。R2 轮中 `getLike_redissonError_degradesToDirectBackfill` 平凡通过属预期（旧实现根本不触锁，判别式内容被空满足）；实现后该用例才真正走 `getLock` 抛错→降级路径。R2 脚手架为工作树中间态、未单独成笔，C-01 即最终实现。
3. **门禁复跑时序**：G1/G2/G3/G5/G8 首测均晚于代码改动（非开工态预跑），其中 G3/G2 改动后实测覆盖新增文件、口径更严；基线一致性佐证方式见 §0 末条。
4. 提交信息经 `.git/task174/c1.msg`、`c2.msg` 以 `git commit -F` 落盘；全程逐路径 `git add`，无 `git add -A`/`add .`、无 `git stash`、未 push、未建 PR；仓库根临时脚本 `g*.tmp` 用毕删除。

## 2. 一句话结论

**完成**：`RecordLikeService.readCount` miss 路径以 per-record Redisson 锁 `lock:like:count-init:{recordId}`（tryLock 等待 1s、lease=-1 看门狗）互斥重建——获锁者双重检查后单线程回源回填（0 计数写 60s 空值哨兵、非 0 持久回填），未获锁者自旋重读至多 3 次共享结果、耗尽兜底直读不回填，锁服务异常降级既有直读回填；INCR/DECR 后 persist 清 TTL 防哨兵键过期计数幽灵回退；`PendingOp` 增 `enqueuedAt`，flush 每轮锁内消费前采集 LLEN 与队头年龄进两 Gauge（`like.pending.queue.size` / `like.pending.head.age.ms`），堆积超 1000 记 WARN（不背压、不拒写、不改 flush 周期/批大小/对账语义/幂等双保险/ADR-0009/LTRIM 时序）。全项门禁通过：offline 七模块 `36/41/33/115/144/59/10` rc=0（record-service 105→**115**、全仓 428→**438**、Skipped 全 0）、Checkstyle 严格 **862** 未增、词面门四形态 ZERO_HIT rc=1、契约门在途 `--open` rc=0（开工态实测）＋收口无参 C-02 后实测、`git diff --check` rc=0、既有 18 用例零翻转。

## 3. 只改清单（与 `git diff --name-only fe3ac4f…` 逐条比对）

```
record-service/src/main/java/com/sportverify/record/service/RecordLikeService.java
record-service/src/test/java/com/sportverify/record/service/RecordLikeServiceTest.java
spec/changes/add-like-read-cache-breakdown-protection/tasks.json
work/mailbox/tasks/TASK-174/handoff.md
work/mailbox/PLAN.md
```

前 2 条为 C-01 业务笔（生产实现 + 测试，2 files / +307 −14，哈希 `8088645788dd3521d18d25e31aa3163c50b6d2c6`）；第 3 条为提案闭环勾选（12 步 completed + 4 任务 passes 全 true，proposal 与 spec-delta 正文零修改）；第 4 条为本交付报告；第 5 条为台账纯追加（1526→追加后行数见交付汇报，纯追加、无既有行改动）。收口终检 `git diff --name-only fe3ac4f…` 与上列 5 条逐条一致（实测输出见交付汇报）；显式零触碰：既有脏项 `spec/changes/add-verify-degrade-status-index/`（保持未跟踪原样）、其余 6 个在途提案目录、主规格、`spec/changes/archive/**`、一切 `scripts/**`、`pom.xml`、配置、SQL。

## 4. 红绿证据（Level A，日志在 `.git/task174/`）

- **R1（仅测试改动，生产零触碰）rc=1**：`mvn-verify.sh --mode=offline --pl record-service test`，record-service testCompile 失败——`RecordLikeServiceTest.java:[92,19] 无法将类…构造器 RecordLikeService 应用到给定类型; 需要:…6 参, 找到:…+SimpleMeterRegistry 7 参, 原因: 实际参数列表和形式参数列表长度不同`（`.git/task174/r1-red-compile.log`）。
- **R2（最小脚手架：七参构造器，零行为改动）rc=1**：`RecordLikeServiceTest Tests run: 28, Failures: 7, Errors: 2`；模块聚合 `Tests run: 115, Failures: 7, Errors: 2, Skipped: 0`（= 基线 105 + 新增 10，既有 18 + 判别式 5 平凡绿共 19 绿）。判别式红摘录：`getLike_miss_rebuildUnderMutexLock` → `Wanted but not invoked: rLock.unlock(); Actually, there were zero interactions with this mock.`；`getLike_lockWaiter_readsSharedResult` → `expected: <5> but was: <0>`；`getLike_missAfterLock_doubleCheckHitsCache` → `expected: <7> but was: <0>`；`flush_publishesPendingGauges` → `MeterNotFoundException: Unable to find a meter that matches all the requirements at once`；其余 `like_firstLike_persistsCountKey`/`unlike_success_persistsCountKey`/`getLike_zeroCount_sentinelShortTtl`/`getLike_lockWaiter_fallbackDirectReadNoBackfill` 为 persist/set 判别 verify 红（`.git/task174/r2-red-discriminators.log`）。
- **G1（实现后）rc=0**：七模块 `36/41/33/115/144/59/10` 全绿、`Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`；`RecordLikeServiceTest Tests run: 28, Failures: 0, Errors: 0`（既有 18 零翻转 + 新增 10 全绿）（`.git/task174/g1-green-offline-2.log`）。

## 5. 逐门 G0–G8 实测退出码

| 门 | 判据 | 实测 | 结果 |
| --- | --- | --- | --- |
| G0 开工读数 | 任务书 §3 逐位 | HEAD `fe3ac4f…`、父 `c4f92ab…`、origin `af17908…`、`0 2`、工作树仅既有脏项，逐位一致（§0） | 过 |
| R1 红 | 测试先行，生产零触碰 | rc=**1**，编译红（§4） | 过（预期红） |
| R2 红 | 判别式红 | rc=**1**，28 中 7 Failures + 2 Errors（§4） | 过（预期红） |
| G1 offline 全模块 | `bash scripts/verify/mvn-verify.sh --mode=offline test` | rc=**0**，`36/41/33/115/144/59/10`、全仓 **438**、Skipped 全 0、BUILD SUCCESS | 过 |
| G2 Checkstyle | `bash scripts/verify/mvn-verify.sh --static=verify-service` | rc=**1**，`You have 862 Checkstyle violations`，与开工基线持平未增（该门以 862 为上限口径，rc=1 系违规存在之既有表达，TASK-173 同口径） | 过 |
| G3 词面门 | 正则自 ci.yml 现场提取（CR-safe 管道），四形态 `git grep -n -I -iE` | default / `LC_ALL=C` / `zh_CN.UTF-8` / `C.UTF-8` 全 **ZERO_HIT rc=1**；正向探针（对 ci.yml 本体）rc=0 证正则有效 | 过 |
| G4 契约门 | 在途 `--open TASK-174 --baseline=fe3ac4f…` rc=0（开工态实测，§1.2-1）；收口无参 `mailbox-contract.sh` rc=0（C-02 落盘后实测——判据 B 以无参为准；两笔提交结构无 C-03 回填笔，实测读数登记于本轮交付汇报） | 在途 rc=**0**；无参读数见交付汇报 | 过 |
| G5 空白门 | `git diff --check` | rc=**0** | 过 |
| G6 只改清单 | `git diff --name-only fe3ac4f…` | C-01 后实测恰 §3 前 2 条；C-02 后恰 §3 全 5 条（终检输出见交付汇报） | 过 |
| G7 兼容红线 | 既有 18 用例零翻转 | 既有用例断言与语义零改动（唯一适配=setUp 七参构造，任务书 §2.6-1 明文要求）；G1 全绿 | 过 |
| G8 受保护 token | PLAN.md 行命中数（`grep -cF`）只增不减 | 追加前 26 项与任务书 §5 逐位一致（§7）；追加后复测见交付汇报 | 过 |

## 6. 实现与测试要点

- 生产（逐字取任务书 §2，偏差仅 §1.1-1 一处）：常量 5 枚（`COUNT_INIT_LOCK_PREFIX`/等待 1s/重读 3 次/哨兵 60s/告警阈值 1000）；移除 `@RequiredArgsConstructor` 改手写七参构造并在构造时注册两 Gauge（`AtomicLong` 载体初始 -1，description 英文、日志中文风格不变）；`readCount` 互斥重建三私有方法（`readCount`/`rebuildCountFromDb`/`awaitSharedCount`）；`incrementCount`/`decrementCount` 后 `persistCountKey`（persist 失败仅 WARN 不阻断，DECR 下限分支 `set(key,"0")` 本身清 TTL 不再 persist）；`PendingOp` 增 `enqueuedAt`（pushPending 写 `System.currentTimeMillis()`，parseOp 对缺失容错记 -1）；`flushPendingLikes` 获锁后、取批前调 `observePendingQueue()`（空队列双 Gauge 归 0、旧格式队头年龄 -1、超 1000 WARN，不改变任何消费行为）。
- 测试：`RecordLikeServiceTest` 18→28（+10，全走 `getLike`/`like`/`unlike`/`flushPendingLikes` 公有或包内入口，确定性 Mockito 判别式，无多线程 latch；MeterRegistry 用真实 `SimpleMeterRegistry` 禁 mock）；既有用例适配仅 setUp 构造器七参 + `meterRegistry` 字段 + 两 import，断言与语义零改动；既有 flush 用例与 `getLike_dbFallbackBackfill` 按 §2.6-1 预期**零适配**通过（`listOps.size` 未打桩返回 null → len=0 → 仅度量不干扰主流程）。

## 7. 受保护 token（PLAN.md 行命中数 `grep -cF`，追加前实测=任务书 §5 基线）

| token | 基线 | token | 基线 | token | 基线 |
| --- | --- | --- | --- | --- | --- |
| `13.4` | 16 | `36438897772` | 13 | `36821040708` | 4 |
| `18.0` | 18 | `36399582548` | 12 | `36845152965` | 3 |
| `73.93` | 17 | `36098038547` | 12 | `36871294588` | 3 |
| `68.8` | 13 | `2806` | 19 | `36880083885` | 4 |
| `6315` | 14 | `598` | 12 | `36958994260` | 4 |
| `1.8612` | 13 | `36736221648` | 11 | `36976873215` | 4 |
| `3.3066` | 13 | `36808102571` | 6 | `36992632143` | 3 |
| `5.7056` | 13 | `36525962432` | 13 | `36995450125` | 1 |
| `9.408` | 13 | `36586847965` | 12 | （26 项完） | |

追加后复测（只增不减）读数见交付汇报。本文件与 PLAN 追加段均不含词面门正则字面量与敏感词。

## 8. 未覆盖项与不得推出的结论

1. **真实 Redis/MySQL 下互斥锁行为未做集成测试**：record-service 无 IT 先例，互斥重建、看门狗续期、锁竞争/重入、哨兵 TTL 真实过期、persist 真实清 TTL、Gauge 在 `/actuator/prometheus` 的真实导出均为 mock 交互断言＋结构推证，未经真环境实测；不得据此推出任何击穿防护的吞吐/延迟收益数字（任务书 §0.10 亦禁止）。
2. `awaitSharedCount` 为无退避的三次紧循环重读（任务书 §2.2 草案逐字形态）：真实高并发下的等待者自旋行为（CPU 代价、1s 等锁上限与 3 次重读的实际覆盖窗口）未实测；未获锁者兜底直读在「持锁者回填耗时 > 重读窗口」时仍会各产生一次 COUNT（判别式允许的兜底路径），非「全场至多一次 COUNT」语义，不得外推为该强表述。
3. `--mode=online` 与 CI 未跑 ⇒ **未达外部门槛**（未 push，push 须用户显式单次授权）；`--static` 在 checkstyle 处即失败，spotbugs/pmd 未覆盖。
4. 堆积 WARN 不做单测断言（任务书 §2.5 明示为实现细节）；`like.pending.*` 两 Gauge 的 Prometheus 命名暴露仅到 Micrometer 注册层为止。
5. 不翻案 TASK-103（F17 虚报登记）、TASK-130（复核结论）、TASK-137（F15/F16 修复）任何历史数字与结论；本任务不涉及任何性能实验，无吞吐/延迟结论可翻。
6. 本文件不含词面门正则字面量；原始物料均在 `.git/task174/`（不入库）。

## 9. 提交

| 提交 | 内容 |
| --- | --- |
| C-01 `8088645788dd3521d18d25e31aa3163c50b6d2c6` | `feat(record): 点赞读路径互斥重建与空值哨兵及 pending 队列度量（TASK-174）`（§3 前 2 条，2 files / +307 −14） |
| C-02（哈希以 `git log` 实测为准，读数见交付汇报） | `docs(mailbox): 登记 TASK-174 验收记录与提案闭环（TASK-174）`（§3 后 3 条：提案 tasks.json 闭环 + 本 handoff + PLAN 纯追加） |

收口终检（C-02 后实测，输出见交付汇报）：契约门无参 rc、`git diff --check` rc、`git diff --name-only fe3ac4f…` 5 条比对、`git status --porcelain` 仅剩既有脏项、`git rev-list --left-right --count origin/main…main` = `0 4`。
